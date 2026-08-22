package feixue.chat.server.com;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// 客户端消息处理线程（每个连接一个实例，负责登录、群聊、私聊、语音、图片等协议消息处理）
class ClientHandler implements Runnable {
    private final ChatServer server;
    Socket socket;
    private ClientTransport transport;
    private final boolean webClient;
    String clientId;
    String nickname; // 客户端昵称
    String group;    // 客户端所属群组
    boolean versionChecked = false; // 版本验证标志
    private boolean webVerified;
    private int webVerificationAttempts;
    private String webAuthorizedGroup;
    volatile long lastWebUserActivity = System.currentTimeMillis();
    private final BlockingQueue<String> liveAudioOutboundQueue = new ArrayBlockingQueue<>(12);
    private volatile boolean handlerActive = true;
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private Thread liveAudioWriterThread;

    public ClientHandler(ChatServer server, Socket socket, ClientTransport transport, boolean webClient) {
        this.server = server;
        this.socket = socket;
        this.transport = transport;
        this.webClient = webClient;
        try {
            if (webClient && !server.webAccessEnabled) {
                transport.close();
                return;
            }
            socket.setTcpNoDelay(true);
            this.clientId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();

            // 检查客户端ID是否过长
            if (server.messageGuard.isUserIdTooLong(clientId)) {
                server.log("拒绝客户端连接：客户端ID过长 " + clientId);
                socket.close();
                return;
            }

            this.nickname = clientId; // 默认使用客户端ID作为昵称
            server.allClientHandlers.add(this);
            if (webClient) {
                server.webClientHandlers.add(this);
                server.ui.refreshWebControlState();
            }
            startLiveAudioWriter();
            server.clientNicknames.put(clientId, nickname); // 添加到昵称映射
            server.clientLastActiveTime.put(clientId, System.currentTimeMillis()); // 记录客户端连接时间

            new Thread(this).start(); // 启动处理线程
            if (webClient) {
                sendMessage("/web_challenge|" + server.http.encodeWebValue(server.webVerificationQuestion));
            }
        } catch (IOException e) {
            server.allClientHandlers.remove(this);
            server.webClientHandlers.remove(this);
            server.http.closeQuietly(socket);
            server.log("初始化客户端连接失败: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            String message;
            while ((message = transport.readMessage()) != null && server.isRunning) {
                // 更新客户端最后活跃时间
                server.clientLastActiveTime.put(clientId, System.currentTimeMillis());
                if (webClient && !handleWebControlMessage(message)) {
                    continue;
                }
                if (webClient && isWebUserActivity(message)) {
                    lastWebUserActivity = System.currentTimeMillis();
                }
                if (isMutedForSending(message)) {
                    sendMessage("您已被服务器禁言，剩余" + server.userManager.getMuteRemainingText(nickname) + "，暂时无法发送内容");
                    continue;
                }
                
                // 检查是否是版本号信息
                if (message.startsWith("/version|")) {
                    String clientVersion = message.substring(9); // 提取版本号
                    if (server.messageGuard.isVersionCompatible(clientVersion)) {
                        versionChecked = true;
                        sendMessage("/version_check|success"); // 发送验证成功消息
                        server.log("客户端 " + clientId + " 版本验证成功: " + clientVersion);
                    } else {
                        sendMessage("/version_check|failed"); // 发送验证失败消息
                        server.log("客户端 " + clientId + " 版本验证失败: " + clientVersion);
                        // 关闭连接
                        break;
                    }
                }
                // 检查是否是登录验证消息
                else if (message.startsWith("/login|")) {
                    String[] parts = message.substring(7).split("\\|", -1);
                    String account, password;

                    // 支持两种登录格式：
                    // 1. 旧格式（电脑端）: /login|账户|密码
                    // 2. 新格式（手机端）: /login|账户|群组|密码
                    if (parts.length == 2) {
                        // 旧格式（电脑端）
                        account = parts[0];
                        password = parts[1];
                    } else if (parts.length == 3) {
                        // 新格式（手机端）
                        account = parts[0];
                        password = parts[2];
                    } else {
                        sendMessage("/login_result|failure"); // 格式错误
                        server.log("客户端 " + clientId + " 发送了格式错误的登录信息");
                        break;
                    }

                    if (server.userManager.isReservedServerUsername(account)) {
                        sendMessage("/login_result|failure: server 为服务器保留ID");
                        server.log("客户端 " + clientId + " 尝试使用服务器保留账户名: " + account);
                        break;
                    }

                    // 验证账户和密码
                    String correctPassword = server.accountPasswords.get(account);
                    if (correctPassword != null && correctPassword.equals(password)) {
                        if (webClient) {
                            webAuthorizedGroup = server.accountGroups.get(account);
                            if (webAuthorizedGroup == null) {
                                sendMessage("/login_result|failure");
                                server.log("网页客户端 " + clientId + " 请求了无效频道: " + account);
                                continue;
                            }
                        }
                        sendMessage("/login_result|success"); // 发送登录成功消息
                        server.log("客户端 " + clientId + " 登录验证成功: " + account);
                    } else {
                        sendMessage("/login_result|failure"); // 发送登录失败消息
                        server.log("客户端 " + clientId + " 登录验证失败: " + account + " (密码错误)");
                        break; // 密码错误，断开连接
                    }
                }
                // 检查是否是公共频道登录消息
                else if (message.startsWith("/login_public|")) {
                    String username = message.substring(14); // 提取用户名
                    
                    // 验证用户名是否有效
                    if (username == null || username.trim().isEmpty()) {
                        sendMessage("/login_result|failure: 用户名不能为空");
                        server.log("客户端 " + clientId + " 发送了空的公共频道用户名");
                        break;
                    }

                    if (server.userManager.isReservedServerUsername(username)) {
                        sendMessage("/login_result|failure: server 为服务器保留ID");
                        server.log("客户端 " + clientId + " 尝试使用服务器保留ID: " + username);
                        break;
                    }
                    
                    // 检查用户名是否过长
                    if (server.messageGuard.isUserIdTooLong(username)) {
                        sendMessage("/login_result|failure: 用户名超过" + ChatServer.MAX_USER_ID_BYTES + "字节限制");
                        server.log("客户端 " + clientId + " 的公共频道用户名过长: " + username);
                        break;
                    }
                    
                    // 公共频道登录成功
                    sendMessage("/login_result|success");
                    server.log("客户端 " + clientId + " 公共频道登录成功: " + username);
                    
                    // 自动设置昵称和群组
                    this.nickname = username;
                    server.clientNicknames.put(clientId, nickname);
                    this.group = ChatServer.PUBLIC_CHANNEL_GROUP;
                    server.clientGroups.put(clientId, group);
                    
                    // 将客户端添加到公共频道群组
                    server.groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(this);
                    
                    // 添加到在线用户列表
                    server.userManager.addOnlineUser(nickname);
                    server.userManager.notifyMutedStatus(this);
                    
                    server.log("客户端 " + clientId + " 加入公共频道: " + group);
                    
                    // 发送历史聊天记录给新加入的客户端
                    sendChatHistory();
                }
                // 检查是否是设置群组的特殊消息
                else if (message.startsWith("/group|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝设置群组");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    String newGroup = message.substring(7); // 提取群组部分
                    if (!newGroup.isEmpty()) {
                        this.group = newGroup;
                        server.clientGroups.put(clientId, group);

                        // 将客户端添加到对应群组
                        server.groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(this);

                        server.log("客户端 " + clientId + " 加入群组: " + group);
                        server.voiceManager.refreshVoiceChannelPanel();

                        // 发送历史聊天记录给新加入的客户端
                        sendChatHistory();
                    }
                }
                // 检查是否是设置昵称的特殊消息
                else if (message.startsWith("/nickname|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝设置昵称");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    String newNickname = message.substring(10); // 提取昵称部分
                    if (!newNickname.isEmpty()) {
                        // 检查昵称是否为保留的"server"名称
                        if (server.userManager.isReservedServerUsername(newNickname)) {
                            sendMessage("昵称 \"server\" 为服务器保留名称，无法使用");
                            server.log("客户端 " + clientId + " 尝试使用服务器保留名称: " + newNickname + "，连接被拒绝");
                            closeConnection();
                            break;
                        }

                        // 检查昵称是否过长
                        if (server.messageGuard.isUserIdTooLong(newNickname)) {
                            sendMessage("服务器拒绝：昵称超过" + ChatServer.MAX_USER_ID_BYTES + "字节限制");
                            server.log("客户端 " + clientId + " 的昵称被拒绝（过长）: " + newNickname);
                            continue;
                        }

                        // 检查用户是否被禁止
                        if (server.bannedUsers.contains(newNickname)) {
                            sendMessage("您已被服务器禁止，无法加入聊天");
                            server.log("被禁止的用户试图加入: " + newNickname);
                            closeConnection();
                            break;
                        }

                        // 检查是否已有同名用户在线
                        if (server.userManager.isUserOnline(newNickname)) {
                            sendMessage("同名用户已在线，无法使用该昵称");
                            server.log("拒绝重复昵称: " + newNickname);
                            closeConnection();
                            break;
                        }

                        // 更新昵称
                        String oldNickname = nickname;
                        nickname = newNickname;
                        server.clientNicknames.put(clientId, nickname); // 更新昵称映射

                        // 更新在线用户列表
                        server.userManager.removeOnlineUser(oldNickname);
                        server.userManager.addOnlineUser(nickname);
                        server.userManager.notifyMutedStatus(this);

                        server.log("客户端 " + clientId + " 设置昵称为: " + nickname);
                        
                        // 生成点对点聊天密码
                        String p2pPassword = server.userManager.generateP2PPassword();
                        server.userP2PPasswords.put(nickname, p2pPassword);
                        server.passwordToUser.put(p2pPassword, nickname);
                        server.userHandlers.put(nickname, this);
                        
                        // 发送密码给客户端
                        sendMessage("/p2p_password|" + p2pPassword);
                        
                        // 广播更新后的在线用户列表
                        server.userManager.broadcastOnlineUsers();
                        sendMessage("/session_ready|success");
                    }
                }
                else if (message.equals("/ping")) {
                    // 心跳消息只用于保活，不广播到频道
                    continue;
                }
                // 加入当前文字频道对应的实时语音区
                else if (message.equals("/live_group_join")) {
                    if (!versionChecked || group == null || !server.userHandlers.containsKey(nickname)) {
                        sendMessage("/live_voice_error|请先完成登录并加入频道");
                        continue;
                    }
                    if (!server.voiceChannelEnabled.getOrDefault(group, true)) {
                        sendMessage("/live_voice_error|该语音频道当前已关闭");
                        continue;
                    }
                    if (server.activeP2PVoicePeers.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsValue(nickname)) {
                        sendMessage("/live_voice_error|请先结束或处理私聊语音申请");
                        continue;
                    }
                    Set<ClientHandler> members = server.voiceRooms.computeIfAbsent(group, key -> java.util.concurrent.ConcurrentHashMap.newKeySet());
                    members.add(this);
                    sendMessage("/live_group_joined|" + group + "|" + members.size());
                    server.voiceManager.broadcastVoiceRoomMemberCount(group);
                    server.log("用户 " + nickname + " 加入频道语音: " + group);
                }
                // 退出当前频道的实时语音区
                else if (message.equals("/live_group_leave")) {
                    server.voiceManager.leaveVoiceRoom(this, true);
                }
                // 转发当前频道的实时语音块
                else if (message.startsWith("/live_group_audio|")) {
                    if (!server.voiceManager.isVoiceRoomMember(this)) {
                        sendMessage("/live_voice_error|您尚未加入频道语音");
                        continue;
                    }
                    String audioData = message.substring(18);
                    if (audioData.isEmpty() || audioData.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
                        continue;
                    }
                    try {
                        byte[] audioFrame = Base64.getDecoder().decode(audioData);
                        if (audioFrame.length == 0 || audioFrame.length > ChatServer.LIVE_AUDIO_CHUNK_BYTES) {
                            continue;
                        }
                        if (audioFrame.length != ChatServer.LIVE_AUDIO_CHUNK_BYTES) {
                            audioFrame = Arrays.copyOf(audioFrame, ChatServer.LIVE_AUDIO_CHUNK_BYTES);
                        }
                        Map<ClientHandler, BlockingQueue<byte[]>> senderQueues = server.voiceRoomAudioQueues
                                .computeIfAbsent(group, key -> new java.util.concurrent.ConcurrentHashMap<>());
                        BlockingQueue<byte[]> senderQueue = senderQueues.computeIfAbsent(this,
                                key -> new ArrayBlockingQueue<>(ChatServer.GROUP_AUDIO_INPUT_QUEUE_CAPACITY));
                        server.voiceManager.offerAudioFrame(senderQueue, audioFrame, ChatServer.GROUP_AUDIO_INPUT_QUEUE_CAPACITY);
                    } catch (IllegalArgumentException ignored) {
                        // 忽略无效Base64音频帧
                    }
                }
                // 向私聊对象申请实时语音
                else if (message.startsWith("/live_p2p_request|")) {
                    if (!versionChecked || !server.userHandlers.containsKey(nickname)) {
                        sendMessage("/live_voice_error|请先完成登录");
                        continue;
                    }
                    if (server.voiceManager.isVoiceRoomMember(this) || server.activeP2PVoicePeers.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsKey(nickname)
                            || server.pendingP2PVoiceRequests.containsValue(nickname)) {
                        sendMessage("/live_voice_error|您当前已有语音会话或待处理申请");
                        continue;
                    }
                    String targetPassword = message.substring(18);
                    String targetUser = server.passwordToUser.get(targetPassword);
                    ClientHandler targetHandler = targetUser == null ? null : server.userHandlers.get(targetUser);
                    if (targetHandler == null || targetUser.equals(nickname)) {
                        sendMessage("/live_voice_error|用户不存在或已离线");
                        continue;
                    }
                    if (server.voiceManager.isVoiceRoomMember(targetHandler) || server.activeP2PVoicePeers.containsKey(targetUser)
                            || server.pendingP2PVoiceRequests.containsKey(targetUser)
                            || server.pendingP2PVoiceRequests.containsValue(targetUser)) {
                        sendMessage("/live_voice_error|对方当前正在通话或有待处理申请");
                        continue;
                    }
                    String senderPassword = server.userP2PPasswords.get(nickname);
                    if (senderPassword == null) {
                        sendMessage("/live_voice_error|系统未分配私聊密码");
                        continue;
                    }
                    server.pendingP2PVoiceRequests.put(targetUser, nickname);
                    server.pendingP2PVoiceRequestTimes.put(targetUser, System.currentTimeMillis());
                    targetHandler.sendMessage("/live_p2p_request|" + nickname + "|" + senderPassword);
                    sendMessage("/live_p2p_request_sent|" + targetUser);
                    server.log("私聊语音申请: " + nickname + " -> " + targetUser);
                }
                // 同意私聊语音申请
                else if (message.startsWith("/live_p2p_accept|")) {
                    String requesterPassword = message.substring(17);
                    if (server.voiceManager.handleServerP2PVoiceAccept(this, requesterPassword)) {
                        continue;
                    }
                    String requester = server.passwordToUser.get(requesterPassword);
                    if (requester == null || !requester.equals(server.pendingP2PVoiceRequests.get(nickname))) {
                        sendMessage("/live_voice_error|语音申请已失效");
                        continue;
                    }
                    ClientHandler requesterHandler = server.userHandlers.get(requester);
                    if (requesterHandler == null || server.voiceManager.isVoiceRoomMember(this) || server.voiceManager.isVoiceRoomMember(requesterHandler)
                            || server.activeP2PVoicePeers.containsKey(nickname) || server.activeP2PVoicePeers.containsKey(requester)) {
                        server.pendingP2PVoiceRequests.remove(nickname, requester);
                        server.pendingP2PVoiceRequestTimes.remove(nickname);
                        sendMessage("/live_voice_error|双方当前无法建立语音通话");
                        if (requesterHandler != null) {
                            requesterHandler.sendMessage("/live_p2p_rejected|" + nickname + "|对方当前无法接听");
                        }
                        continue;
                    }
                    server.pendingP2PVoiceRequests.remove(nickname, requester);
                    server.pendingP2PVoiceRequestTimes.remove(nickname);
                    server.activeP2PVoicePeers.put(nickname, requester);
                    server.activeP2PVoicePeers.put(requester, nickname);
                    String myPassword = server.userP2PPasswords.get(nickname);
                    requesterHandler.sendMessage("/live_p2p_started|" + nickname + "|" + myPassword);
                    sendMessage("/live_p2p_started|" + requester + "|" + requesterPassword);
                    server.log("私聊语音已建立: " + nickname + " <-> " + requester);
                }
                // 拒绝私聊语音申请
                else if (message.startsWith("/live_p2p_reject|")) {
                    String requesterPassword = message.substring(17);
                    if (server.voiceManager.handleServerP2PVoiceReject(this, requesterPassword)) {
                        continue;
                    }
                    String requester = server.passwordToUser.get(requesterPassword);
                    if (requester != null && server.pendingP2PVoiceRequests.remove(nickname, requester)) {
                        server.pendingP2PVoiceRequestTimes.remove(nickname);
                        ClientHandler requesterHandler = server.userHandlers.get(requester);
                        if (requesterHandler != null) {
                            requesterHandler.sendMessage("/live_p2p_rejected|" + nickname + "|对方已拒绝");
                        }
                        sendMessage("/live_p2p_rejected_ack|" + requester);
                    }
                }
                // 转发已建立私聊通话的实时语音块
                else if (message.startsWith("/live_p2p_audio|")) {
                    if (server.voiceManager.handleServerP2PVoiceAudio(nickname, message.substring(16))) {
                        continue;
                    }
                    String peer = server.activeP2PVoicePeers.get(nickname);
                    if (peer == null) {
                        continue;
                    }
                    String audioData = message.substring(16);
                    if (audioData.isEmpty() || audioData.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
                        continue;
                    }
                    ClientHandler peerHandler = server.userHandlers.get(peer);
                    if (peerHandler != null) {
                        peerHandler.sendMessage("/live_p2p_audio|" + nickname + "|" + audioData);
                    } else {
                        server.voiceManager.endP2PVoiceCall(nickname);
                    }
                }
                // 挂断私聊语音
                else if (message.equals("/live_p2p_end")) {
                    if (nickname.equals(server.serverP2PVoicePeer)) {
                        server.voiceManager.stopServerP2PVoiceSession(false);
                        continue;
                    }
                    server.voiceManager.endP2PVoiceCall(nickname);
                }
                // 检查是否是语音消息
                else if (message.startsWith("/voice|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送语音消息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        // 验证消息格式
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3) {
                            server.log("客户端 " + clientId + " 发送的语音消息格式错误");
                            continue;
                        }
                        String voiceId = parts[1];
                        server.log("收到来自 " + nickname + " 的语音消息，ID: " + voiceId);
                        // 保存语音消息到聊天记录
                        ChatMessage chatMsg = new ChatMessage(nickname, "[语音消息]");
                        server.history.saveChatHistory(group, chatMsg);
                        // 创建包含发送者信息的语音消息
                        String voiceWithSender = "/voice_with_sender|" + nickname + "|" + voiceId + "|" + parts[2];
                        broadcastSpecialMessage(voiceWithSender, this);  // 在群组内广播带发送者信息的语音消息
                    }
                }
                // 检查是否是图片信息消息
                else if (message.startsWith("/image_info|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送图片信息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        String[] parts = message.split("\\|", 4);
                        if (parts.length == 4) {
                            String imageId = parts[1];
                            int totalChunks;
                            try {
                                totalChunks = Integer.parseInt(parts[2]);
                            } catch (NumberFormatException e) {
                                server.log("客户端 " + clientId + " 发送了无效的图片块数量");
                                continue;
                            }

                            String fileName = parts[3];
                            server.log("收到来自 " + nickname + " 的图片信息: " + fileName + " (" + totalChunks + " 块)");

                            // 创建图片接收器
                            ImageChunkReceiver receiver = new ImageChunkReceiver(imageId, fileName, group, nickname, totalChunks);
                            server.imageReceivers.put(imageId, receiver);

                            // 广播图片信息到群组
                            broadcastSpecialMessage(message, this);
                        }
                    }
                }
                // 检查是否是图片块消息
                else if (message.startsWith("/image_chunk|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送图片块");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        String[] parts = message.split("\\|", 4);
                        if (parts.length == 4) {
                            String imageId = parts[1];
                            int chunkIndex;
                            try {
                                chunkIndex = Integer.parseInt(parts[2]);
                            } catch (NumberFormatException e) {
                                server.log("客户端 " + clientId + " 发送了无效的图片块索引");
                                continue;
                            }
                            String chunkData = parts[3];

                            // 查找对应的图片接收器
                            ImageChunkReceiver receiver = server.imageReceivers.get(imageId);
                            if (receiver != null) {
                                boolean isComplete = receiver.addChunk(chunkIndex, chunkData);
                                if (isComplete) {
                                    // 图片接收完成，保存到文件
                                    server.saveImageToFile(receiver);
                                    server.imageReceivers.remove(imageId);
                                }
                            }
                        }

                        // 直接转发图片块消息到群组
                        broadcastSpecialMessage(message, this);
                    }
                }
                // 检查是否是点对点验证请求
                else if (message.startsWith("/p2p_verify|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送点对点验证请求");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送验证请求");
                        continue;
                    }

                    // 解析点对点验证格式: /p2p_verify|targetPassword
                    String[] parts = message.split("\\|", 2);
                    if (parts.length != 2) {
                        server.log("客户端 " + clientId + " 发送的点对点验证格式错误");
                        continue;
                    }
                    String targetPassword = parts[1];
                    
                    // 查找目标用户
                    String targetUser = server.passwordToUser.get(targetPassword);
                    if (targetUser == null) {
                        sendMessage("/p2p_verify_result|error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试验证不存在的密码: " + targetPassword);
                        continue;
                    }
                    
                    // 查找目标用户的处理器
                    ClientHandler targetHandler = server.userHandlers.get(targetUser);
                    if (targetHandler == null) {
                        sendMessage("/p2p_verify_result|error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试验证离线用户: " + targetUser);
                        continue;
                    }
                    
                    // 获取发送者的密码
                    String senderPassword = server.userP2PPasswords.get(nickname);
                    if (senderPassword == null) {
                        sendMessage("/p2p_verify_result|error|系统错误，发送者密码未生成");
                        server.log("发送者密码未找到: " + nickname);
                        continue;
                    }
                    
                    // 向目标用户发送通知，格式: /p2p_notification|sender|senderPassword
                    targetHandler.sendMessage("/p2p_notification|" + nickname + "|" + senderPassword);
                    
                    // 向发起用户发送验证成功
                    sendMessage("/p2p_verify_result|success");
                    server.log("点对点验证成功，从 " + nickname + " 发送通知给 " + targetUser);
                }
                // 检查是否是点对点消息
                else if (message.startsWith("/p2p|")) {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送点对点消息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    // 解析点对点消息格式: /p2p|targetPassword|message
                    String[] parts = message.split("\\|", 3);
                    if (parts.length != 3) {
                        server.log("客户端 " + clientId + " 发送的点对点消息格式错误");
                        continue;
                    }
                    String targetPassword = parts[1];
                    String content = parts[2];
                    
                    // 查找目标用户
                    String targetUser = server.passwordToUser.get(targetPassword);
                    if (targetUser == null) {
                        sendMessage("/p2p_error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试向不存在的密码发送点对点消息: " + targetPassword);
                        continue;
                    }
                    
                    // 查找目标用户的处理器
                    ClientHandler targetHandler = server.userHandlers.get(targetUser);
                    if (targetHandler == null) {
                        sendMessage("/p2p_error|用户不存在或已离线");
                        server.log("客户端 " + nickname + " 尝试向离线用户发送点对点消息: " + targetUser);
                        continue;
                    }
                    
                    // 获取发送者的密码
                    String senderPassword = server.userP2PPasswords.get(nickname);
                    if (senderPassword == null) {
                        sendMessage("/p2p_error|系统错误，发送者密码未生成");
                        server.log("发送者密码未找到: " + nickname);
                        continue;
                    }
                    
                    // 转发消息给目标用户，格式: /p2p_msg|sender|senderPassword|content
                    targetHandler.sendMessage("/p2p_msg|" + nickname + "|" + senderPassword + "|" + content);
                    server.log("点对点消息从 " + nickname + " 转发给 " + targetUser);
                }
                else if (message.startsWith("/deepseek|")) {
                    // DeepSeek AI问答
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送DeepSeek请求");
                        sendMessage("/version_check|failed");
                        break;
                    }
                    
                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法使用DeepSeek");
                        continue;
                    }
                    
                    // 提取问题内容
                    String question = message.substring(10); // 移除 "/deepseek|"
                    if (question.isEmpty()) {
                        sendMessage("DeepSeek: 问题不能为空");
                        continue;
                    }
                    
                    // 调用DeepSeek服务
                    String answer = server.deepSeekService.ask(nickname, question);
                    // 发送答案给请求用户
                    sendMessage("/deepseek_answer|" + answer);
                    server.log("DeepSeek回答已发送给 " + nickname);
                }
                else {
                    // 检查是否已通过版本验证
                    if (!versionChecked) {
                        server.log("客户端 " + clientId + " 未通过版本验证，拒绝发送消息");
                        sendMessage("/version_check|failed");
                        break;
                    }

                    // 检查用户是否被禁止
                    if (server.bannedUsers.contains(nickname)) {
                        sendMessage("您已被服务器禁止，无法发送消息");
                        continue;
                    }

                    if (group != null) {
                        // 检查消息是否过长
                        if (server.messageGuard.isMessageTooLong(message)) {
                            server.log("客户端 " + clientId + " 发送的消息被拒绝（内容过长）");
                            // 发送警告给客户端
                            sendMessage("服务器拒绝：消息超过" + ChatServer.MAX_MESSAGE_BYTES + "字节限制");
                            continue; // 拒绝过长的消息
                        }

                        // 检查消息是否包含连续5个相同字符
                        if (server.messageGuard.hasTooManyConsecutiveSameChars(message)) {
                            server.log("客户端 " + clientId + " 发送的消息被拒绝（包含连续5个相同字符）: " + message);
                            sendMessage("服务器拒绝：消息包含连续5个相同字符");
                            continue; // 拒绝包含连续5个相同字符的消息
                        }

                        // 检查是否为重复消息（10分钟内）
                        if (server.messageGuard.isDuplicateMessage(group, message)) {
                            server.log("客户端 " + clientId + " 发送的重复消息被拒绝");
                            sendMessage("服务器拒绝：10分钟内不允许发送相同消息");
                            continue; // 拒绝重复的消息
                        }

                        server.log("收到来自 " + nickname + " 的消息: " + message);
                        
                        // 检查是否为公共频道，如果是则进行违禁词检测
                        String messageToBroadcast = message;
                        if (ChatServer.PUBLIC_CHANNEL_GROUP.equals(group)) {
                            // 检查是否包含违禁词
                            if (server.messageGuard.containsForbiddenWords(message)) {
                                server.log("检测到违禁词，消息将被过滤: " + message);
                                messageToBroadcast = server.messageGuard.filterForbiddenWords(message);
                            }
                        }
                        
                        // 保存消息到聊天记录（保存原始消息，但广播过滤后的消息）
                        ChatMessage chatMsg = new ChatMessage(nickname, messageToBroadcast);
                        server.history.saveChatHistory(group, chatMsg);
                        broadcast(messageToBroadcast, this);  // 在群组内广播消息
                    }
                }
            }
        } catch (IOException e) {
            server.log("客户端 " + nickname + " 意外断开连接");
        } finally {
            // 清理客户端资源
            cleanupClient();
            server.log("客户端 " + nickname + " 连接已清理");
        }
    }

    private boolean handleWebControlMessage(String message) throws IOException {
        if (message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0) {
            sendMessage("/web_error|消息不能包含换行符");
            return false;
        }
        if (!webVerified) {
            if (!message.startsWith("/web_verify|")) {
                sendMessage("/web_verify_result|required");
                return false;
            }
            String supplied;
            try {
                supplied = server.http.decodeWebValue(message.substring(12)).trim();
            } catch (IOException e) {
                supplied = "";
            }
            webVerificationAttempts++;
            if (server.http.constantTimeEquals(supplied, server.webVerificationAnswer)) {
                webVerified = true;
                lastWebUserActivity = System.currentTimeMillis();
                sendMessage("/web_verify_result|success");
                sendMessage("/web_minimum_version|" + server.minimumClientVersion);
                server.http.sendWebChannelList(this);
                server.log("网页访问验证通过: " + clientId);
            } else {
                int remaining = Math.max(0, ChatServer.WEB_MAX_VERIFY_ATTEMPTS - webVerificationAttempts);
                sendMessage("/web_verify_result|failure|" + remaining);
                if (remaining == 0) {
                    server.log("网页访问验证失败过多，已断开: " + clientId);
                    closeConnection();
                } else {
                    try {
                        Thread.sleep(Math.min(1000L, webVerificationAttempts * 250L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return false;
        }

        if (message.equals("/web_disconnect")) {
            closeConnection();
            return false;
        }
        if (message.equals("/web_channels_request")) {
            server.http.sendWebChannelList(this);
            return false;
        }
        if (!isAllowedWebClientMessage(message)) {
            sendMessage("/web_error|不支持的网页协议命令");
            return false;
        }
        if (message.startsWith("/login|") && !versionChecked) {
            sendMessage("/login_result|failure: 请先完成版本验证");
            return false;
        }
        if (message.startsWith("/group|")) {
            String requestedGroup = message.substring(7);
            boolean publicGroup = ChatServer.PUBLIC_CHANNEL_GROUP.equals(requestedGroup);
            if (!versionChecked || (!publicGroup && !requestedGroup.equals(webAuthorizedGroup))) {
                sendMessage("/web_login_error|频道未授权，请先验证频道密码");
                return false;
            }
            if (group != null && !group.equals(requestedGroup)) {
                sendMessage("/web_login_error|当前连接已加入频道");
                return false;
            }
        }
        if (message.startsWith("/nickname|")) {
            String requestedNickname = message.substring(10).trim();
            if (requestedNickname.indexOf('|') >= 0 || requestedNickname.indexOf(',') >= 0
                    || requestedNickname.isEmpty()) {
                sendMessage("/web_login_error|昵称包含不允许的字符");
                return false;
            }
            if (group == null) {
                sendMessage("/web_login_error|请先加入频道");
                return false;
            }
        }
        if (!message.startsWith("/") && !server.userHandlers.containsKey(nickname)) {
            sendMessage("/web_error|请先完成频道登录");
            return false;
        }
        return true;
    }

    private boolean isAllowedWebClientMessage(String message) {
        if (!message.startsWith("/")) {
            return true;
        }
        return message.startsWith("/version|")
                || message.startsWith("/login|")
                || message.startsWith("/group|")
                || message.startsWith("/nickname|")
                || message.equals("/ping")
                || message.equals("/live_group_join")
                || message.equals("/live_group_leave")
                || message.startsWith("/live_group_audio|")
                || message.startsWith("/live_p2p_request|")
                || message.startsWith("/live_p2p_accept|")
                || message.startsWith("/live_p2p_reject|")
                || message.startsWith("/live_p2p_audio|")
                || message.equals("/live_p2p_end")
                || message.startsWith("/voice|")
                || message.startsWith("/image_info|")
                || message.startsWith("/image_chunk|")
                || message.startsWith("/p2p_verify|")
                || message.startsWith("/p2p|")
                || message.startsWith("/deepseek|");
    }

    private boolean isWebUserActivity(String message) {
        if (message.startsWith("/live_group_audio|")) {
            return containsAudiblePcm(message.substring(18));
        }
        if (message.startsWith("/live_p2p_audio|")) {
            return containsAudiblePcm(message.substring(16));
        }
        return !message.equals("/ping")
                && !message.startsWith("/web_")
                && !message.startsWith("/version|")
                && !message.startsWith("/login|")
                && !message.startsWith("/group|")
                && !message.startsWith("/nickname|");
    }

    private boolean containsAudiblePcm(String encodedAudio) {
        if (encodedAudio.isEmpty() || encodedAudio.length() > ChatServer.MAX_LIVE_AUDIO_BASE64_LENGTH) {
            return false;
        }
        try {
            byte[] pcm = Base64.getDecoder().decode(encodedAudio);
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                if (Math.abs(sample) >= 820) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private boolean isMutedForSending(String message) {
        if (!server.userManager.isUserMuted(nickname)) {
            return false;
        }
        if (message.startsWith("/version|")
                || message.equals("/ping")
                || message.equals("/live_group_leave")
                || message.equals("/live_p2p_end")
                || message.startsWith("/live_p2p_reject|")) {
            return false;
        }
        return true;
    }
    
    // 清理客户端资源的方法
    void cleanupClient() {
        if (!cleanupStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            handlerActive = false;
            if (liveAudioWriterThread != null) {
                liveAudioWriterThread.interrupt();
            }
            liveAudioOutboundQueue.clear();
            server.voiceManager.leaveVoiceRoom(this, false);
            server.voiceManager.endP2PVoiceCall(nickname);
            server.voiceManager.clearPendingP2PVoiceRequests(nickname);
            server.voiceManager.handleServerP2PClientUnavailable(nickname);

            // 从群组中移除客户端
            if (group != null && server.groups.containsKey(group)) {
                server.groups.get(group).remove(this);
            }
            server.clientNicknames.remove(clientId);
            server.clientGroups.remove(clientId);
            server.clientLastActiveTime.remove(clientId); // 移除客户端活跃时间记录
            
            // 从在线用户列表中移除
            server.userManager.removeOnlineUser(nickname);
            
            // 清理点对点聊天映射
            String password = server.userP2PPasswords.remove(nickname);
            if (password != null) {
                server.passwordToUser.remove(password);
            }
            server.userHandlers.remove(nickname);
            
            if (transport != null) {
                transport.close();
            }
            server.allClientHandlers.remove(this);
            server.webClientHandlers.remove(this);
            server.ui.refreshOnlineUsersPanel();
            server.ui.refreshWebControlState();
        } catch (IOException e) {
            server.log("关闭客户端连接时出错: " + e.getMessage());
        } finally {
            server.allClientHandlers.remove(this);
            server.webClientHandlers.remove(this);
            server.ui.refreshWebControlState();
        }
    }

    // 发送历史聊天记录给客户端
    private void sendChatHistory() {
        List<ChatMessage> history = server.groupChatHistories.get(group);
        if (history != null && !history.isEmpty()) {
            sendMessage("/history|start"); // 开始发送历史记录标记
            for (ChatMessage record : history) {
                sendMessage("/history|" + record.toString()); // 发送每条历史记录
            }
            sendMessage("/history|end"); // 结束发送历史记录标记
        }
    }

    // 发送消息给当前客户端
    public void sendMessage(String msg) {
        if (msg.startsWith("/live_group_audio|") || msg.startsWith("/live_p2p_audio|")) {
            if (!liveAudioOutboundQueue.offer(msg)) {
                liveAudioOutboundQueue.poll();
                liveAudioOutboundQueue.offer(msg);
            }
            return;
        }
        writeMessageDirectly(msg);
    }

    private void startLiveAudioWriter() {
        liveAudioWriterThread = new Thread(() -> {
            while (handlerActive && socket != null && !socket.isClosed()) {
                try {
                    String message = liveAudioOutboundQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        writeMessageDirectly(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "LiveAudioWriter-" + clientId);
        liveAudioWriterThread.setDaemon(true);
        liveAudioWriterThread.start();
    }

    private void writeMessageDirectly(String msg) {
        if (!handlerActive || transport == null) {
            return;
        }
        try {
            transport.sendMessage(msg);
        } catch (IOException e) {
            handlerActive = false;
            server.http.closeQuietly(socket);
        }
    }

    // 获取客户端昵称
    public String getNickname() {
        return nickname;
    }

    // 获取客户端群组
    public String getGroup() {
        return group;
    }

    // 在群组内广播普通消息给其他客户端
    private void broadcast(String msg, ClientHandler exclude) {
        List<ClientHandler> groupClients = server.groups.get(group);
        if (groupClients != null) {
            for (ClientHandler client : groupClients) {
                if (client != exclude) {
                    client.sendMessage("[" + nickname + "] " + msg);
                }
            }
        }
    }

    // 在群组内广播特殊格式消息给其他客户端（如语音、图片等）
    private void broadcastSpecialMessage(String message, ClientHandler exclude) {
        List<ClientHandler> groupClients = server.groups.get(group);
        if (groupClients != null) {
            for (ClientHandler client : groupClients) {
                if (client != exclude) {
                    client.sendMessage(message); // 直接转发消息
                }
            }
        }
    }

    // 在群组内广播语音消息给其他客户端
    private void broadcastVoice(String voiceMessage, ClientHandler exclude) {
        broadcastSpecialMessage(voiceMessage, exclude);
    }

    // 关闭客户端连接
    public void closeConnection() throws IOException {
        handlerActive = false;
        transport.close();
    }
}
