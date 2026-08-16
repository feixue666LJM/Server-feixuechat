import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public class ChatServer extends JFrame {
    private JTextArea logArea;       // 聊天记录显示区域
    private JTextField portField;    // 端口输入框
    private JButton startBtn;        // 启动服务器按钮
    private JButton stopBtn;         // 关闭服务器按钮
    private JTextField serverInputField; // 服务器输入框
    private ServerSocket serverSocket; // 修改为普通ServerSocket类型，兼容SSL和普通连接
    // 存储每个群组的客户端列表
    private Map<String, List<ClientHandler>> groups = new ConcurrentHashMap<>();
    // 存储客户端ID和昵称的映射
    private ConcurrentHashMap<String, String> clientNicknames = new ConcurrentHashMap<>();
    // 存储客户端ID和群组的映射
    private ConcurrentHashMap<String, String> clientGroups = new ConcurrentHashMap<>();
    // 存储每个群组的聊天记录
    private Map<String, List<ChatMessage>> groupChatHistories = new ConcurrentHashMap<>();
    // 存储每个群组最近10分钟内的消息，用于检测重复消息
    private Map<String, List<RecentMessage>> recentMessages = new ConcurrentHashMap<>();
    // 存储在线用户名
    private Set<String> onlineUsers = ConcurrentHashMap.newKeySet();
    // 存储被禁止的用户
    private Set<String> bannedUsers = ConcurrentHashMap.newKeySet();
    // 点对点聊天密码映射
    private Map<String, String> userP2PPasswords = new ConcurrentHashMap<>(); // 用户名 -> 密码
    private Map<String, String> passwordToUser = new ConcurrentHashMap<>(); // 密码 -> 用户名
    // 用户名到ClientHandler的映射
    private Map<String, ClientHandler> userHandlers = new ConcurrentHashMap<>();
    // DeepSeek AI问答服务
    private DeepSeekService deepSeekService;

    // 账户和密码映射
    private static final Map<String, String> ACCOUNT_PASSWORDS = new HashMap<>();

    static {
        ACCOUNT_PASSWORDS.put("feixuechat", "sbfeixue");
        ACCOUNT_PASSWORDS.put("ash", "niuyouguoguo");
        ACCOUNT_PASSWORDS.put("antiash", "hongyiyi");
        ACCOUNT_PASSWORDS.put("binglin", "yzbb");
        ACCOUNT_PASSWORDS.put("feixuehome", "feixue123456");
        ACCOUNT_PASSWORDS.put("toney", "qunxing");
    }

    // 消息字节限制（降低为300）
    private static final int MAX_MESSAGE_BYTES = 600;
    // 用户ID字节限制
    private static final int MAX_USER_ID_BYTES = 30;
    // 重复消息检测时间窗口（10分钟）
    private static final long MESSAGE_DUPLICATE_WINDOW = 10 * 60 * 1000; // 10分钟
    // 连续相同字符限制
    private static final int MAX_CONSECUTIVE_SAME_CHARS = 5;

    // 服务器版本号
    private static final String SERVER_VERSION = "2.0.0";
    
    // 公共频道群组名
    private static final String PUBLIC_CHANNEL_GROUP = "group_public";
    
    // 违禁词列表（从pbc.txt加载，若文件为空则使用默认值）
    private Set<String> forbiddenWords = new HashSet<>(Arrays.asList(
        "我操你妈", "操你妈", "你妈", "你好", "傻逼", "滚", "政治", "国家", "核弹", "武器", "杀人", "抢银行"
    ));

    // 图片保存根目录
    private static final String IMAGE_SAVE_DIR = "tupianbao";

    // 聊天消息类
    private static class ChatMessage {
        String sender;
        String content;
        long timestamp;

        public ChatMessage(String sender, String content) {
            this.sender = sender;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "[" + sender + "] " + content;
        }
    }

    // 最近消息类，用于重复消息检测
    private static class RecentMessage {
        String content;
        long timestamp;

        public RecentMessage(String content, long timestamp) {
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    // 添加图片块接收器类
    private static class ImageChunkReceiver {
        private String imageId;
        private String fileName;
        private String group;
        private String sender;
        private int totalChunks;
        private Map<Integer, String> receivedChunks;
        private long lastUpdateTime;

        public ImageChunkReceiver(String imageId, String fileName, String group, String sender, int totalChunks) {
            this.imageId = imageId;
            this.fileName = fileName;
            this.group = group;
            this.sender = sender;
            this.totalChunks = totalChunks;
            this.receivedChunks = new HashMap<>();
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public boolean addChunk(int chunkIndex, String chunkData) {
            receivedChunks.put(chunkIndex, chunkData);
            lastUpdateTime = System.currentTimeMillis();
            return receivedChunks.size() == totalChunks;
        }

        public String getCompleteImageData() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < totalChunks; i++) {
                String chunk = receivedChunks.get(i);
                if (chunk != null) {
                    sb.append(chunk);
                }
            }
            return sb.toString();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastUpdateTime > 30000; // 30秒超时
        }

        public String getImageId() {
            return imageId;
        }

        public String getFileName() {
            return fileName;
        }

        public String getGroup() {
            return group;
        }

        public String getSender() {
            return sender;
        }
    }

    // 存储每个群组的图片接收器
    private Map<String, ImageChunkReceiver> imageReceivers = new ConcurrentHashMap<>();

    // 添加客户端连接状态检查相关字段
    private Map<String, Long> clientLastActiveTime = new ConcurrentHashMap<>(); // 存储客户端最后活跃时间
    private static final long CLIENT_TIMEOUT = 100000; // 30秒超时
    private volatile boolean isRunning = true; // 服务器运行状态

    public ChatServer() {
        initUI();
        setTitle("聊天服务器");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        loadChatHistory(); // 启动时加载聊天记录
        loadBannedUsers(); // 启动时加载禁止用户列表
        loadOnlineUsers(); // 启动时加载在线用户列表
        loadForbiddenWords(); // 启动时加载屏蔽词列表
        deepSeekService = new DeepSeekService(); // 初始化DeepSeek服务
    }

    private void initUI() {
        // 布局设置
        setLayout(new BorderLayout());

        // 顶部控制栏
        JPanel topPanel = new JPanel();
        portField = new JTextField("22233", 8);
        startBtn = new JButton("启动服务器");
        stopBtn = new JButton("关闭服务器");
        stopBtn.setEnabled(false); // 初始状态禁用
        topPanel.add(new JLabel("端口:"));
        topPanel.add(portField);
        topPanel.add(startBtn);
        topPanel.add(stopBtn);
        add(topPanel, BorderLayout.NORTH);

        // 中间日志区域
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

        // 底部输入栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        serverInputField = new JTextField();
        bottomPanel.add(serverInputField, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // 按钮事件
        startBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startServer();
            }
        });
        
        stopBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopServer();
            }
        });

        // 服务器输入框事件
        serverInputField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleServerInput();
            }
        });
    }

    // 加载所有群组的聊天记录
    private void loadChatHistory() {
        File historyDir = new File("chat_history");
        if (!historyDir.exists()) {
            return;
        }

        File[] groupFiles = historyDir.listFiles((dir, name) -> name.startsWith("group_") && name.endsWith(".txt"));
        if (groupFiles == null) return;

        for (File file : groupFiles) {
            String groupName = file.getName().substring(0, file.getName().length() - 4); // 移除 .txt 后缀
            List<ChatMessage> history = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 解析存储的消息格式: timestamp|sender|content
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        ChatMessage msg = new ChatMessage(parts[1], parts[2]);
                        msg.timestamp = Long.parseLong(parts[0]);
                        history.add(msg);
                    }
                }
            } catch (IOException | NumberFormatException e) {
                log("加载群组 " + groupName + " 的聊天记录失败: " + e.getMessage());
            }

            groupChatHistories.put(groupName, history);
            log("加载群组 " + groupName + " 的聊天记录，共 " + history.size() + " 条");
        }
    }

    // 保存聊天记录到文件
    private void saveChatHistory(String group, ChatMessage message) {
        // 添加到内存中的历史记录
        groupChatHistories.computeIfAbsent(group, k -> new ArrayList<>()).add(message);

        // 保存到文件
        File historyDir = new File("chat_history");
        if (!historyDir.exists()) {
            historyDir.mkdirs();
        }

        File historyFile = new File(historyDir, group + ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(historyFile, true))) {
            // 保存格式: timestamp|sender|content
            writer.println(message.timestamp + "|" + message.sender + "|" + message.content);
        } catch (IOException e) {
            log("保存群组 " + group + " 的聊天记录失败: " + e.getMessage());
        }
    }

    // 检查消息是否超过字节限制
    private boolean isMessageTooLong(String message) {
        try {
            byte[] messageBytes = message.getBytes("UTF-8");
            return messageBytes.length > MAX_MESSAGE_BYTES;
        } catch (Exception e) {
            return true; // 出现异常时认为消息过长
        }
    }

    // 检查用户ID是否超过字节限制
    private boolean isUserIdTooLong(String userId) {
        try {
            byte[] userIdBytes = userId.getBytes("UTF-8");
            return userIdBytes.length > MAX_USER_ID_BYTES;
        } catch (Exception e) {
            return true; // 出现异常时认为用户ID过长
        }
    }

    // 检查消息中是否包含连续5个相同的字符
    private boolean hasTooManyConsecutiveSameChars(String message) {
        if (message == null || message.length() < MAX_CONSECUTIVE_SAME_CHARS) {
            return false;
        }

        int consecutiveCount = 1;
        char previousChar = message.charAt(0);

        for (int i = 1; i < message.length(); i++) {
            char currentChar = message.charAt(i);
            if (currentChar == previousChar) {
                consecutiveCount++;
                if (consecutiveCount >= MAX_CONSECUTIVE_SAME_CHARS) {
                    return true;
                }
            } else {
                consecutiveCount = 1;
                previousChar = currentChar;
            }
        }

        return false;
    }
    
    // 检查消息是否包含违禁词
    private boolean containsForbiddenWords(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        String lowerMessage = message.toLowerCase();
        for (String forbiddenWord : forbiddenWords) {
            if (lowerMessage.contains(forbiddenWord.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    // 过滤消息中的违禁词（用*替换）
    private String filterForbiddenWords(String message) {
        if (message == null || message.trim().isEmpty()) {
            return message;
        }
        
        String filteredMessage = message;
        for (String forbiddenWord : forbiddenWords) {
            String lowerForbidden = forbiddenWord.toLowerCase();
            String lowerMessage = filteredMessage.toLowerCase();
            int index = lowerMessage.indexOf(lowerForbidden);
            while (index != -1) {
                // 用星号替换违禁词
                StringBuilder sb = new StringBuilder(filteredMessage);
                for (int i = 0; i < forbiddenWord.length(); i++) {
                    sb.setCharAt(index + i, '*');
                }
                filteredMessage = sb.toString();
                lowerMessage = filteredMessage.toLowerCase();
                index = lowerMessage.indexOf(lowerForbidden, index + forbiddenWord.length());
            }
        }
        return filteredMessage;
    }

    // 清理过期的重复消息记录
    private void cleanupRecentMessages(String group) {
        List<RecentMessage> messages = recentMessages.get(group);
        if (messages != null) {
            long currentTime = System.currentTimeMillis();
            messages.removeIf(msg -> (currentTime - msg.timestamp) > MESSAGE_DUPLICATE_WINDOW);
        }
    }

    // 检查消息是否重复
    private boolean isDuplicateMessage(String group, String message) {
        // 语音消息跳过重复检测
        if (message.startsWith("/voice|") || message.startsWith("/voice_with_sender|")) {
            return false;
        }
        
        List<RecentMessage> messages = recentMessages.computeIfAbsent(group, k -> new ArrayList<>());

        // 清理过期消息
        cleanupRecentMessages(group);

        // 检查是否有重复消息
        long currentTime = System.currentTimeMillis();
        for (RecentMessage recentMsg : messages) {
            if (recentMsg.content.equals(message)) {
                return true;
            }
        }

        // 添加新消息到记录中
        messages.add(new RecentMessage(message, currentTime));
        return false;
    }

    // 保存图片到本地文件系统
    private void saveImageToFile(ImageChunkReceiver receiver) {
        try {
            String completeImageData = receiver.getCompleteImageData();
            byte[] imageBytes = Base64.getDecoder().decode(completeImageData);

            // 创建群组目录
            File groupDir = new File(IMAGE_SAVE_DIR + File.separator + receiver.getGroup());
            if (!groupDir.exists()) {
                groupDir.mkdirs();
            }

            // 生成文件名：时间戳_发送者_原文件名
            String fileName = System.currentTimeMillis() + "_" + receiver.getSender() + "_" + receiver.getFileName();
            // 确保文件名安全
            fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

            File imageFile = new File(groupDir, fileName);

            // 写入图片文件
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(imageBytes);
            }

            log("图片已保存: " + imageFile.getAbsolutePath());
        } catch (Exception e) {
            log("保存图片失败: " + e.getMessage());
        }
    }

    // 清理过期的图片接收器
    private void cleanupExpiredImageReceivers() {
        imageReceivers.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired();
            if (expired) {
                log("清理过期图片接收器: " + entry.getValue().getImageId());
            }
            return expired;
        });
    }

    // 验证客户端版本是否兼容
    private boolean isVersionCompatible(String clientVersion) {
        // 简单的版本比较，实际项目中可能需要更复杂的版本比较逻辑
        return clientVersion != null && clientVersion.compareTo(SERVER_VERSION) >= 0;
    }

    private SSLServerSocket createSSLServerSocket(int port) throws Exception {
        try {
            // 创建SSL上下文
            SSLContext sslContext = SSLContext.getInstance("TLS");

            // 创建密钥库
            KeyStore keyStore = KeyStore.getInstance("JKS");

            // 注意：你需要有一个有效的证书文件
            // 这里只是一个示例，实际使用时需要替换为真实的证书路径和密码
            try (FileInputStream fis = new FileInputStream("keystore.jks")) {
                keyStore.load(fis, "password".toCharArray()); // 替换为你的密钥库密码
            }

            // 创建密钥管理器
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            keyManagerFactory.init(keyStore, "password".toCharArray()); // 替换为你的密钥密码

            // 初始化SSL上下文
            sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

            // 创建SSL服务器套接字
            SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
            SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(port);

            // 设置需要客户端认证（可选）
            sslServerSocket.setNeedClientAuth(false);

            return sslServerSocket;
        } catch (Exception e) {
            log("SSL配置失败，使用普通Socket: " + e.getMessage());
            throw e; // 重新抛出异常，让startServer方法处理
        }
    }

    private ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    // 在服务器启动后添加定时清理任务
    private void startServer() {
        new Thread(() -> {
            try {
                int port = Integer.parseInt(portField.getText());
                serverSocket = new ServerSocket(port);
                log("服务器启动成功，监听端口: " + port);
                startBtn.setEnabled(false);
                portField.setEnabled(false);
                stopBtn.setEnabled(true); // 启用关闭服务器按钮
                
                // 清理旧的在线用户列表，确保下次启动后用户可以成功进入聊天
                onlineUsers.clear();
                saveOnlineUsers();
                log("已清理旧的在线用户列表");
                
                // 启动定时清理任务
                startCleanupTask();
                
                // 启动客户端超时检测任务
                startClientTimeoutCheckTask();
                
                // 启动在线用户列表广播任务
                startOnlineUsersBroadcastTask();
                
                while (true) {
                    Socket clientSocket = serverSocket.accept();  // 阻塞等待客户端连接
                    ClientHandler handler = new ClientHandler(clientSocket);
                    // 客户端刚连接时还未分配群组，暂时不加入任何群组列表
                }
            } catch (IOException ex) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    log("服务器启动失败: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        startBtn.setEnabled(true);
                        portField.setEnabled(true);
                        stopBtn.setEnabled(false);
                    });
                }
            }
        }).start();
    }
    
    // 启动客户端超时检测任务
    private void startClientTimeoutCheckTask() {
        Thread timeoutCheckThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(10000); // 每10秒检查一次
                    
                    long currentTime = System.currentTimeMillis();
                    Iterator<Map.Entry<String, Long>> iterator = clientLastActiveTime.entrySet().iterator();
                    
                    while (iterator.hasNext()) {
                        Map.Entry<String, Long> entry = iterator.next();
                        String clientId = entry.getKey();
                        long lastActiveTime = entry.getValue();
                        
                        // 检查客户端是否超时
                        if (currentTime - lastActiveTime > CLIENT_TIMEOUT) {
                            String nickname = clientNicknames.get(clientId);
                            if (nickname != null) {
                                log("客户端 " + nickname + " (" + clientId + ") 连接超时，已断开");
                                
                                // 从群组中移除客户端
                                String group = clientGroups.get(clientId);
                                if (group != null && groups.containsKey(group)) {
                                    List<ClientHandler> groupClients = groups.get(group);
                                    synchronized (groupClients) {
                                        Iterator<ClientHandler> clientIterator = groupClients.iterator();
                                        while (clientIterator.hasNext()) {
                                            ClientHandler handler = clientIterator.next();
                                            if (handler.clientId.equals(clientId)) {
                                                // 清理客户端资源
                                                handler.cleanupClient();
                                                clientIterator.remove();
                                                break;
                                            }
                                        }
                                    }
                                }
                                
                                // 从记录中移除客户端
                                clientNicknames.remove(clientId);
                                clientGroups.remove(clientId);
                                iterator.remove(); // 从clientLastActiveTime中移除
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log("检查客户端超时任务出错: " + e.getMessage());
                }
            }
        });
        timeoutCheckThread.setDaemon(true);
        timeoutCheckThread.start();
    }
    
    // 添加服务器关闭方法
    public void shutdown() {
        isRunning = false; // 设置服务器运行状态为false
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log("关闭服务器Socket时出错: " + e.getMessage());
        }
    }
    
    // 停止服务器并清理所有客户端
    private void stopServer() {
        log("正在停止服务器...");
        
        // 清理所有客户端连接
        disconnectAllClients();
        
        // 关闭服务器socket
        shutdown();
        
        // 清理所有数据
        clearAllServerData();
        
        // 重置UI状态
        SwingUtilities.invokeLater(() -> {
            startBtn.setEnabled(true);
            portField.setEnabled(true);
            stopBtn.setEnabled(false);
        });
        
        log("服务器已停止，所有客户端连接已断开，数据已清理");
    }
    
    // 清理所有客户端连接
    private void disconnectAllClients() {
        int clientCount = 0;
        // 遍历所有群组中的客户端
        for (List<ClientHandler> clients : groups.values()) {
            synchronized (clients) {
                clientCount += clients.size();
                // 向每个客户端发送断开连接消息
                for (ClientHandler client : clients) {
                    try {
                        client.out.println("/server_shutdown|服务器正在关闭，请重新连接");
                        client.socket.close();
                    } catch (IOException e) {
                        // 忽略关闭错误
                    }
                }
                clients.clear();
            }
        }
        
        // 清空所有映射
        groups.clear();
        clientNicknames.clear();
        clientGroups.clear();
        userHandlers.clear();
        userP2PPasswords.clear();
        passwordToUser.clear();
        clientLastActiveTime.clear(); // 清理客户端活跃时间记录
        
        log("已断开 " + clientCount + " 个客户端连接");
    }
    
    // 清理所有服务器数据
    private void clearAllServerData() {
        // 清空在线用户列表
        onlineUsers.clear();
        saveOnlineUsers(); // 更新文件
        
        // 清空被禁止用户列表
        bannedUsers.clear();
        saveBannedUsers();
        
        // 清空最近消息记录
        recentMessages.clear();
        
        // 清空图片接收器
        imageReceivers.clear();
        
        // 清空客户端活跃时间记录（已在disconnectAllClients中清理，这里再次确保）
        clientLastActiveTime.clear();
        
        log("所有服务器数据已清理");
    }

    // 启动定时清理任务
    private void startCleanupTask() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10000); // 每10秒清理一次
                    androidtupian.cleanupExpiredReceivers(new androidtupian.LogCallback() {
                        @Override
                        public void log(String message) {
                            ChatServer.this.log(message);
                        }
                    });
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    // 处理服务器输入
    private void handleServerInput() {
        String input = serverInputField.getText().trim();
        if (input.isEmpty()) return;

        serverInputField.setText("");

        if (input.startsWith("/")) {
            // 处理命令
            handleServerCommand(input);
        } else {
            // 普通消息
            log("[server] " + input);
            // 广播到所有群组
            broadcastFromServer(input);
        }
    }

    // 处理服务器命令
    private void handleServerCommand(String command) {
        if (command.startsWith("/chat ")) {
            // 发送聊天消息
            String message = command.substring(6);
            log("[server] " + message);
            broadcastFromServer(message);
        } else if (command.startsWith("/ban ")) {
            // 禁止用户
            String username = command.substring(5).trim();
            if (!username.isEmpty()) {
                banUser(username);
                log("服务器: 已禁止用户 " + username);
            }
        } else if (command.startsWith("/unban ")) {
            // 解除禁止
            String username = command.substring(7).trim();
            if (!username.isEmpty()) {
                unbanUser(username);
                log("服务器: 已解除禁止用户 " + username);
            }
        } else {
            log("未知命令: " + command);
        }
    }

    // 广播消息来自服务器
    private void broadcastFromServer(String message) {
        // 遍历所有群组
        for (Map.Entry<String, List<ClientHandler>> entry : groups.entrySet()) {
            String group = entry.getKey();
            List<ClientHandler> clients = entry.getValue();

            // 检查是否为公共频道，如果是则进行违禁词检测
            String messageToBroadcast = message;
            if (PUBLIC_CHANNEL_GROUP.equals(group)) {
                // 检查是否包含违禁词
                if (containsForbiddenWords(message)) {
                    log("服务器消息检测到违禁词，消息将被过滤: " + message);
                    messageToBroadcast = filterForbiddenWords(message);
                }
            }

            // 向群组内所有客户端广播消息
            synchronized (clients) {
                Iterator<ClientHandler> iterator = clients.iterator();
                while (iterator.hasNext()) {
                    ClientHandler client = iterator.next();
                    try {
                        client.sendMessage("[server] " + messageToBroadcast);
                    } catch (Exception e) {
                        // 客户端可能已断开连接
                        iterator.remove();
                    }
                }
            }
        }
    }

    // 禁止用户
    private void banUser(String username) {
        bannedUsers.add(username);
        saveBannedUsers(); // 保存到文件

        // 断开被禁止用户的连接
        for (Map.Entry<String, List<ClientHandler>> entry : groups.entrySet()) {
            List<ClientHandler> clients = entry.getValue();
            synchronized (clients) {
                Iterator<ClientHandler> iterator = clients.iterator();
                while (iterator.hasNext()) {
                    ClientHandler client = iterator.next();
                    if (username.equals(client.getNickname())) {
                        try {
                            client.sendMessage("您已被服务器禁止");
                            client.socket.close();
                        } catch (Exception e) {
                            // 忽略异常
                        }
                        iterator.remove();
                    }
                }
            }
        }
    }

    // 解除禁止用户
    private void unbanUser(String username) {
        bannedUsers.remove(username);
        saveBannedUsers(); // 保存到文件
    }

    // 保存被禁止的用户到文件
    private void saveBannedUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter("ban.txt"))) {
            for (String user : bannedUsers) {
                writer.println(user);
            }
        } catch (IOException e) {
            log("保存禁止用户列表失败: " + e.getMessage());
        }
    }

    // 从pbc.txt加载屏蔽词列表，若文件不存在或为空则使用代码中的默认值
    private void loadForbiddenWords() {
        File pbcFile = new File("pbc.txt");
        if (!pbcFile.exists()) {
            log("pbc.txt 不存在，使用代码中的默认屏蔽词列表");
            return;
        }

        Set<String> loadedWords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(pbcFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) {
                    loadedWords.add(word);
                }
            }
        } catch (IOException e) {
            log("加载屏蔽词列表失败: " + e.getMessage());
            return;
        }

        if (loadedWords.isEmpty()) {
            log("pbc.txt 为空，使用代码中的默认屏蔽词列表");
        } else {
            forbiddenWords = loadedWords;
            log("从 pbc.txt 加载屏蔽词列表，共 " + loadedWords.size() + " 个屏蔽词");
        }
    }

    // 加载被禁止的用户列表
    private void loadBannedUsers() {
        File banFile = new File("ban.txt");
        if (!banFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(banFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String user = line.trim();
                if (!user.isEmpty()) {
                    bannedUsers.add(user);
                }
            }
        } catch (IOException e) {
            log("加载禁止用户列表失败: " + e.getMessage());
        }
    }

    // 加载在线用户列表
    private void loadOnlineUsers() {
        File userFile = new File("user.txt");
        if (!userFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(userFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String user = line.trim();
                if (!user.isEmpty()) {
                    onlineUsers.add(user);
                }
            }
        } catch (IOException e) {
            log("加载在线用户列表失败: " + e.getMessage());
        }
    }

    // 保存在线用户列表
    private void saveOnlineUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter("user.txt"))) {
            for (String user : onlineUsers) {
                writer.println(user);
            }
        } catch (IOException e) {
            log("保存在线用户列表失败: " + e.getMessage());
        }
    }

    // 生成随机的5位数字点对点聊天密码
    private String generateP2PPassword() {
        Random rand = new Random();
        String password;
        do {
            password = String.valueOf(10000 + rand.nextInt(90000)); // 10000-99999
        } while (passwordToUser.containsKey(password));
        return password;
    }

    // 广播在线用户列表给所有客户端
    private void broadcastOnlineUsers() {
        if (onlineUsers.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String user : onlineUsers) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(user);
        }
        String userList = sb.toString();
        // 遍历所有群组中的客户端
        for (List<ClientHandler> clients : groups.values()) {
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client.versionChecked) {
                        client.sendMessage("/online_users|" + userList);
                    }
                }
            }
        }
    }
    
    // 定时广播在线用户列表
    private void startOnlineUsersBroadcastTask() {
        Thread broadcastThread = new Thread(() -> {
            // 立即广播一次
            broadcastOnlineUsers();
            while (true) {
                try {
                    Thread.sleep(30000); // 每30秒广播一次
                    broadcastOnlineUsers();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log("广播在线用户列表时出错: " + e.getMessage());
                }
            }
        });
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }
    
    // 检查用户是否在线
    private boolean isUserOnline(String username) {
        return onlineUsers.contains(username);
    }

    // 从在线用户列表中移除用户
    private void removeOnlineUser(String username) {
        if (onlineUsers.remove(username)) {
            saveOnlineUsers(); // 更新文件
        }
    }

    // 添加用户到在线用户列表
    private void addOnlineUser(String username) {
        if (onlineUsers.add(username)) {
            saveOnlineUsers(); // 更新文件
        }
    }

    // 客户端消息处理线程
    private class ClientHandler implements Runnable {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String clientId;
        private String nickname; // 客户端昵称
        private String group;    // 客户端所属群组
        private boolean versionChecked = false; // 版本验证标志

        public ClientHandler(Socket socket) {
            this.socket = socket;
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                this.clientId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();

                // 检查客户端ID是否过长
                if (isUserIdTooLong(clientId)) {
                    log("拒绝客户端连接：客户端ID过长 " + clientId);
                    socket.close();
                    return;
                }

                this.nickname = clientId; // 默认使用客户端ID作为昵称
                clientNicknames.put(clientId, nickname); // 添加到昵称映射
                clientLastActiveTime.put(clientId, System.currentTimeMillis()); // 记录客户端连接时间

                new Thread(this).start(); // 启动处理线程
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                String message;
                while ((message = in.readLine()) != null && isRunning) {
                    // 更新客户端最后活跃时间
                    clientLastActiveTime.put(clientId, System.currentTimeMillis());
                    
                    // 检查是否是版本号信息
                    if (message.startsWith("/version|")) {
                        String clientVersion = message.substring(9); // 提取版本号
                        if (isVersionCompatible(clientVersion)) {
                            versionChecked = true;
                            sendMessage("/version_check|success"); // 发送验证成功消息
                            log("客户端 " + clientId + " 版本验证成功: " + clientVersion);
                        } else {
                            sendMessage("/version_check|failed"); // 发送验证失败消息
                            log("客户端 " + clientId + " 版本验证失败: " + clientVersion);
                            // 关闭连接
                            break;
                        }
                    }
                    // 检查是否是登录验证消息
                    else if (message.startsWith("/login|")) {
                        String[] parts = message.substring(7).split("\\|");
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
                            log("客户端 " + clientId + " 发送了格式错误的登录信息");
                            break;
                        }

                        // 验证账户和密码
                        String correctPassword = ACCOUNT_PASSWORDS.get(account);
                        if (correctPassword != null && correctPassword.equals(password)) {
                            sendMessage("/login_result|success"); // 发送登录成功消息
                            log("客户端 " + clientId + " 登录验证成功: " + account);
                        } else {
                            sendMessage("/login_result|failure"); // 发送登录失败消息
                            log("客户端 " + clientId + " 登录验证失败: " + account + " (密码错误)");
                            break; // 密码错误，断开连接
                        }
                    }
                    // 检查是否是公共频道登录消息
                    else if (message.startsWith("/login_public|")) {
                        String username = message.substring(14); // 提取用户名
                        
                        // 验证用户名是否有效
                        if (username == null || username.trim().isEmpty()) {
                            sendMessage("/login_result|failure: 用户名不能为空");
                            log("客户端 " + clientId + " 发送了空的公共频道用户名");
                            break;
                        }
                        
                        // 检查用户名是否过长
                        if (isUserIdTooLong(username)) {
                            sendMessage("/login_result|failure: 用户名超过" + MAX_USER_ID_BYTES + "字节限制");
                            log("客户端 " + clientId + " 的公共频道用户名过长: " + username);
                            break;
                        }
                        
                        // 公共频道登录成功
                        sendMessage("/login_result|success");
                        log("客户端 " + clientId + " 公共频道登录成功: " + username);
                        
                        // 自动设置昵称和群组
                        this.nickname = username;
                        clientNicknames.put(clientId, nickname);
                        this.group = PUBLIC_CHANNEL_GROUP;
                        clientGroups.put(clientId, group);
                        
                        // 将客户端添加到公共频道群组
                        groups.computeIfAbsent(group, k -> new ArrayList<>()).add(this);
                        
                        // 添加到在线用户列表
                        addOnlineUser(nickname);
                        
                        log("客户端 " + clientId + " 加入公共频道: " + group);
                        
                        // 发送历史聊天记录给新加入的客户端
                        sendChatHistory();
                    }
                    // 检查是否是设置群组的特殊消息
                    else if (message.startsWith("/group|")) {
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝设置群组");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        String newGroup = message.substring(7); // 提取群组部分
                        if (!newGroup.isEmpty()) {
                            this.group = newGroup;
                            clientGroups.put(clientId, group);

                            // 将客户端添加到对应群组
                            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(this);

                            log("客户端 " + clientId + " 加入群组: " + group);

                            // 发送历史聊天记录给新加入的客户端
                            sendChatHistory();
                        }
                    }
                    // 检查是否是设置昵称的特殊消息
                    else if (message.startsWith("/nickname|")) {
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝设置昵称");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        String newNickname = message.substring(10); // 提取昵称部分
                        if (!newNickname.isEmpty()) {
                            // 检查昵称是否为保留的"server"名称
                            if ("server".equals(newNickname)) {
                                sendMessage("昵称 \"server\" 为服务器保留名称，无法使用");
                                log("客户端 " + clientId + " 尝试使用服务器保留名称 \"server\"，连接被拒绝");
                                closeConnection();
                                break;
                            }

                            // 检查昵称是否过长
                            if (isUserIdTooLong(newNickname)) {
                                sendMessage("服务器拒绝：昵称超过" + MAX_USER_ID_BYTES + "字节限制");
                                log("客户端 " + clientId + " 的昵称被拒绝（过长）: " + newNickname);
                                continue;
                            }

                            // 检查用户是否被禁止
                            if (bannedUsers.contains(newNickname)) {
                                sendMessage("您已被服务器禁止，无法加入聊天");
                                log("被禁止的用户试图加入: " + newNickname);
                                closeConnection();
                                break;
                            }

                            // 检查是否已有同名用户在线
                            if (isUserOnline(newNickname)) {
                                sendMessage("同名用户已在线，无法使用该昵称");
                                log("拒绝重复昵称: " + newNickname);
                                closeConnection();
                                break;
                            }

                            // 更新昵称
                            String oldNickname = nickname;
                            nickname = newNickname;
                            clientNicknames.put(clientId, nickname); // 更新昵称映射

                            // 更新在线用户列表
                            removeOnlineUser(oldNickname);
                            addOnlineUser(nickname);

                            log("客户端 " + clientId + " 设置昵称为: " + nickname);
                            
                            // 生成点对点聊天密码
                            String p2pPassword = generateP2PPassword();
                            userP2PPasswords.put(nickname, p2pPassword);
                            passwordToUser.put(p2pPassword, nickname);
                            userHandlers.put(nickname, this);
                            
                            // 发送密码给客户端
                            sendMessage("/p2p_password|" + p2pPassword);
                            
                            // 广播更新后的在线用户列表
                            broadcastOnlineUsers();
                        }
                    }
                    // 检查是否是语音消息
                    else if (message.startsWith("/voice|")) {
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送语音消息");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
                            sendMessage("您已被服务器禁止，无法发送消息");
                            continue;
                        }

                        if (group != null) {
                            // 验证消息格式
                            String[] parts = message.split("\\|", 3);
                            if (parts.length != 3) {
                                log("客户端 " + clientId + " 发送的语音消息格式错误");
                                continue;
                            }
                            String voiceId = parts[1];
                            log("收到来自 " + nickname + " 的语音消息，ID: " + voiceId);
                            // 保存语音消息到聊天记录
                            ChatMessage chatMsg = new ChatMessage(nickname, "[语音消息]");
                            saveChatHistory(group, chatMsg);
                            // 创建包含发送者信息的语音消息
                            String voiceWithSender = "/voice_with_sender|" + nickname + "|" + voiceId + "|" + parts[2];
                            broadcastSpecialMessage(voiceWithSender, this);  // 在群组内广播带发送者信息的语音消息
                        }
                    }
                    // 检查是否是图片信息消息
                    else if (message.startsWith("/image_info|")) {
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送图片信息");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
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
                                    log("客户端 " + clientId + " 发送了无效的图片块数量");
                                    continue;
                                }

                                String fileName = parts[3];
                                log("收到来自 " + nickname + " 的图片信息: " + fileName + " (" + totalChunks + " 块)");

                                // 创建图片接收器
                                ImageChunkReceiver receiver = new ImageChunkReceiver(imageId, fileName, group, nickname, totalChunks);
                                imageReceivers.put(imageId, receiver);

                                // 广播图片信息到群组
                                broadcastSpecialMessage(message, this);
                            }
                        }
                    }
                    // 检查是否是图片块消息
                    else if (message.startsWith("/image_chunk|")) {
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送图片块");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
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
                                    log("客户端 " + clientId + " 发送了无效的图片块索引");
                                    continue;
                                }
                                String chunkData = parts[3];

                                // 查找对应的图片接收器
                                ImageChunkReceiver receiver = imageReceivers.get(imageId);
                                if (receiver != null) {
                                    boolean isComplete = receiver.addChunk(chunkIndex, chunkData);
                                    if (isComplete) {
                                        // 图片接收完成，保存到文件
                                        saveImageToFile(receiver);
                                        imageReceivers.remove(imageId);
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
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送点对点验证请求");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
                            sendMessage("您已被服务器禁止，无法发送验证请求");
                            continue;
                        }

                        // 解析点对点验证格式: /p2p_verify|targetPassword
                        String[] parts = message.split("\\|", 2);
                        if (parts.length != 2) {
                            log("客户端 " + clientId + " 发送的点对点验证格式错误");
                            continue;
                        }
                        String targetPassword = parts[1];
                        
                        // 查找目标用户
                        String targetUser = passwordToUser.get(targetPassword);
                        if (targetUser == null) {
                            sendMessage("/p2p_verify_result|error|用户不存在或已离线");
                            log("客户端 " + nickname + " 尝试验证不存在的密码: " + targetPassword);
                            continue;
                        }
                        
                        // 查找目标用户的处理器
                        ClientHandler targetHandler = userHandlers.get(targetUser);
                        if (targetHandler == null) {
                            sendMessage("/p2p_verify_result|error|用户不存在或已离线");
                            log("客户端 " + nickname + " 尝试验证离线用户: " + targetUser);
                            continue;
                        }
                        
                        // 获取发送者的密码
                        String senderPassword = userP2PPasswords.get(nickname);
                        if (senderPassword == null) {
                            sendMessage("/p2p_verify_result|error|系统错误，发送者密码未生成");
                            log("发送者密码未找到: " + nickname);
                            continue;
                        }
                        
                        // 向目标用户发送通知，格式: /p2p_notification|sender|senderPassword
                        targetHandler.sendMessage("/p2p_notification|" + nickname + "|" + senderPassword);
                        
                        // 向发起用户发送验证成功
                        sendMessage("/p2p_verify_result|success");
                        log("点对点验证成功，从 " + nickname + " 发送通知给 " + targetUser);
                    }
                    // 检查是否是点对点消息
                    else if (message.startsWith("/p2p|")) {
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送点对点消息");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
                            sendMessage("您已被服务器禁止，无法发送消息");
                            continue;
                        }

                        // 解析点对点消息格式: /p2p|targetPassword|message
                        String[] parts = message.split("\\|", 3);
                        if (parts.length != 3) {
                            log("客户端 " + clientId + " 发送的点对点消息格式错误");
                            continue;
                        }
                        String targetPassword = parts[1];
                        String content = parts[2];
                        
                        // 查找目标用户
                        String targetUser = passwordToUser.get(targetPassword);
                        if (targetUser == null) {
                            sendMessage("/p2p_error|用户不存在或已离线");
                            log("客户端 " + nickname + " 尝试向不存在的密码发送点对点消息: " + targetPassword);
                            continue;
                        }
                        
                        // 查找目标用户的处理器
                        ClientHandler targetHandler = userHandlers.get(targetUser);
                        if (targetHandler == null) {
                            sendMessage("/p2p_error|用户不存在或已离线");
                            log("客户端 " + nickname + " 尝试向离线用户发送点对点消息: " + targetUser);
                            continue;
                        }
                        
                        // 获取发送者的密码
                        String senderPassword = userP2PPasswords.get(nickname);
                        if (senderPassword == null) {
                            sendMessage("/p2p_error|系统错误，发送者密码未生成");
                            log("发送者密码未找到: " + nickname);
                            continue;
                        }
                        
                        // 转发消息给目标用户，格式: /p2p_msg|sender|senderPassword|content
                        targetHandler.sendMessage("/p2p_msg|" + nickname + "|" + senderPassword + "|" + content);
                        log("点对点消息从 " + nickname + " 转发给 " + targetUser);
                    }
                    else if (message.startsWith("/deepseek|")) {
                        // DeepSeek AI问答
                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送DeepSeek请求");
                            sendMessage("/version_check|failed");
                            break;
                        }
                        
                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
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
                        String answer = ChatServer.this.deepSeekService.ask(nickname, question);
                        // 发送答案给请求用户
                        sendMessage("/deepseek_answer|" + answer);
                        log("DeepSeek回答已发送给 " + nickname);
                    }
                    else {
                        // 忽略客户端心跳消息
                        if ("/ping".equals(message)) {
                            continue;
                        }

                        // 检查是否已通过版本验证
                        if (!versionChecked) {
                            log("客户端 " + clientId + " 未通过版本验证，拒绝发送消息");
                            sendMessage("/version_check|failed");
                            break;
                        }

                        // 检查用户是否被禁止
                        if (bannedUsers.contains(nickname)) {
                            sendMessage("您已被服务器禁止，无法发送消息");
                            continue;
                        }

                        if (group != null) {
                            // 检查消息是否过长
                            if (isMessageTooLong(message)) {
                                log("客户端 " + clientId + " 发送的消息被拒绝（内容过长）");
                                // 发送警告给客户端
                                sendMessage("服务器拒绝：消息超过" + MAX_MESSAGE_BYTES + "字节限制");
                                continue; // 拒绝过长的消息
                            }

                            // 检查消息是否包含连续5个相同字符
                            if (hasTooManyConsecutiveSameChars(message)) {
                                log("客户端 " + clientId + " 发送的消息被拒绝（包含连续5个相同字符）: " + message);
                                sendMessage("服务器拒绝：消息包含连续5个相同字符");
                                continue; // 拒绝包含连续5个相同字符的消息
                            }

                            // 检查是否为重复消息（10分钟内）
                            if (isDuplicateMessage(group, message)) {
                                log("客户端 " + clientId + " 发送的重复消息被拒绝");
                                sendMessage("服务器拒绝：10分钟内不允许发送相同消息");
                                continue; // 拒绝重复的消息
                            }

                            log("收到来自 " + nickname + " 的消息: " + message);
                            
                            // 检查是否为公共频道，如果是则进行违禁词检测
                            String messageToBroadcast = message;
                            if (PUBLIC_CHANNEL_GROUP.equals(group)) {
                                // 检查是否包含违禁词
                                if (containsForbiddenWords(message)) {
                                    log("检测到违禁词，消息将被过滤: " + message);
                                    messageToBroadcast = filterForbiddenWords(message);
                                }
                            }
                            
                            // 保存消息到聊天记录（保存原始消息，但广播过滤后的消息）
                            ChatMessage chatMsg = new ChatMessage(nickname, messageToBroadcast);
                            saveChatHistory(group, chatMsg);
                            broadcast(messageToBroadcast, this);  // 在群组内广播消息
                        } else {
                            log("客户端 " + nickname + " 消息被忽略：未设置群组（group 为 null）");
                        }
                    }
                }
            } catch (IOException e) {
                log("客户端 " + nickname + " 意外断开连接");
            } finally {
                // 清理客户端资源
                cleanupClient();
                log("客户端 " + nickname + " 连接已清理");
            }
        }
        
        // 清理客户端资源的方法
        private void cleanupClient() {
            try {
                // 从群组中移除客户端
                if (group != null && groups.containsKey(group)) {
                    groups.get(group).remove(this);
                }
                clientNicknames.remove(clientId);
                clientGroups.remove(clientId);
                clientLastActiveTime.remove(clientId); // 移除客户端活跃时间记录
                
                // 从在线用户列表中移除
                removeOnlineUser(nickname);
                
                // 清理点对点聊天映射
                String password = userP2PPasswords.remove(nickname);
                if (password != null) {
                    passwordToUser.remove(password);
                }
                userHandlers.remove(nickname);
                
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                log("关闭客户端连接时出错: " + e.getMessage());
            }
        }

        // 发送历史聊天记录给客户端
        private void sendChatHistory() {
            List<ChatMessage> history = groupChatHistories.get(group);
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
            out.println(msg);
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
            List<ClientHandler> groupClients = groups.get(group);
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
            List<ClientHandler> groupClients = groups.get(group);
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
            socket.close();
        }
    }

    // 日志输出方法
    private void log(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());  // 自动滚动到底部
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatServer().setVisible(true));
    }
}
