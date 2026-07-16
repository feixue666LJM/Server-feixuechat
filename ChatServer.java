import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public class ChatServer extends JFrame {
    private JTextArea logArea;       // 聊天记录显示区域
    private JTextField portField;    // 端口输入框
    private JButton startBtn;        // 启动服务器按钮
    private JButton stopBtn;         // 关闭服务器按钮
    private JTextField serverInputField; // 服务器输入框
    private JTree onlineUsersTree;
    private DefaultMutableTreeNode onlineUsersRoot;
    private DefaultTreeModel onlineUsersTreeModel;
    private JLabel onlineUsersSummaryLabel;
    private JButton privateChatButton;
    private JButton privateVoiceButton;
    private DefaultListModel<String> channelManagementListModel;
    private JList<String> channelManagementList;
    private JTextField channelNameField;
    private JPasswordField channelPasswordField;
    private JCheckBox noChannelPasswordCheckBox;
    private JButton addChannelButton;
    private JButton deleteChannelButton;
    private JButton changeChannelPasswordButton;
    private JLabel channelManagementStatusLabel;
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
    // 用户名 -> 禁言结束时间戳
    private Map<String, Long> mutedUsers = new ConcurrentHashMap<>();
    // 点对点聊天密码映射
    private Map<String, String> userP2PPasswords = new ConcurrentHashMap<>(); // 用户名 -> 密码
    private Map<String, String> passwordToUser = new ConcurrentHashMap<>(); // 密码 -> 用户名
    // 用户名到ClientHandler的映射
    private Map<String, ClientHandler> userHandlers = new ConcurrentHashMap<>();
    // 每个频道的实时语音成员
    private Map<String, Set<ClientHandler>> voiceRooms = new ConcurrentHashMap<>();
    // 每个频道按发送者保存短音频队列，避免网络成批到达时覆盖前一帧
    private Map<String, Map<ClientHandler, BlockingQueue<byte[]>>> voiceRoomAudioQueues = new ConcurrentHashMap<>();
    // 服务器端加入频道时也使用短队列，和客户端帧走同一混音节奏
    private Map<String, BlockingQueue<byte[]>> serverVoiceAudioQueues = new ConcurrentHashMap<>();
    private Map<String, Boolean> voiceChannelEnabled = new ConcurrentHashMap<>();
    // 每个频道独立的实时语音音量倍率，默认保持原声 x1。
    private Map<String, Integer> voiceChannelVolumeGain = new ConcurrentHashMap<>();
    private JPanel voiceChannelsPanel;
    private JLabel voiceOverviewLabel;
    private final Map<String, VoiceChannelRow> voiceChannelRows = new LinkedHashMap<>();
    private final AtomicBoolean voiceChannelRefreshPending = new AtomicBoolean(false);
    private final Object serverVoiceLock = new Object();
    private volatile String serverVoiceGroup;
    private volatile boolean serverVoiceStarting;
    private TargetDataLine serverVoiceTargetLine;
    private SourceDataLine serverVoiceSourceLine;
    private Thread serverVoiceCaptureThread;
    private Thread serverVoicePlaybackThread;
    private final BlockingQueue<byte[]> serverVoicePlaybackQueue = new ArrayBlockingQueue<>(50);
    private static final AudioFormat SERVER_VOICE_FORMAT = new AudioFormat(8000.0f, 16, 1, true, false);
    private static final int SERVER_VOICE_CHUNK_BYTES = 320;
    private final Object serverP2PVoiceLock = new Object();
    private volatile String serverP2PVoicePeer;
    private volatile String serverP2PVoicePendingUser;
    private volatile long serverP2PVoicePendingTime;
    private volatile boolean serverP2PVoiceStarting;
    private TargetDataLine serverP2PVoiceTargetLine;
    private SourceDataLine serverP2PVoiceSourceLine;
    private Thread serverP2PVoiceCaptureThread;
    private Thread serverP2PVoicePlaybackThread;
    private final BlockingQueue<byte[]> serverP2PVoicePlaybackQueue = new ArrayBlockingQueue<>(50);
    // 已建立的一对一语音通话，用户名 -> 对方用户名
    private Map<String, String> activeP2PVoicePeers = new ConcurrentHashMap<>();
    // 待处理的语音申请，接收方用户名 -> 发起方用户名
    private Map<String, String> pendingP2PVoiceRequests = new ConcurrentHashMap<>();
    private Map<String, Long> pendingP2PVoiceRequestTimes = new ConcurrentHashMap<>();
    // DeepSeek AI问答服务
    private DeepSeekService deepSeekService;

    // 私有频道账号、密码和内部群组名均由 onlypd.json 动态加载。
    private volatile Map<String, String> accountPasswords = Collections.emptyMap();
    private volatile Map<String, String> accountGroups = Collections.emptyMap();
    private volatile Set<String> configuredChannelGroups = Collections.emptySet();
    private Path onlyPdConfigPath;
    private long onlyPdLastModified = Long.MIN_VALUE;
    private long onlyPdLastSize = Long.MIN_VALUE;
    private java.util.concurrent.ScheduledExecutorService channelConfigMonitor;
    private volatile boolean serverStarting;
    private static final String SERVER_P2P_NAME = "server";
    private static final String SERVER_P2P_PASSWORD = "00000";
    private static final String MUTED_USERS_FILE = "muted_users.txt";
    private static final String ONLY_PD_CONFIG_FILE = "onlypd.json";
    private static final String PUBLIC_CHANNEL_DISPLAY = "公开频道（内置）";
    private static final String EMPTY_ONLY_PD_CONFIG = "new{\nname@\npassworld@\n};\n";
    private static final Pattern CHANNEL_BLOCK_PATTERN = Pattern.compile(
            "(?is)new\\s*\\{(.*?)\\}\\s*;?");
    private static final Pattern CHANNEL_FIELD_PATTERN = Pattern.compile(
            "(?im)^\\s*(name|passworld|password)\\s*@\\s*(.*?)\\s*$");

    // 消息字节限制（降低为300）
    private static final int MAX_MESSAGE_BYTES = 300;
    // 用户ID字节限制
    private static final int MAX_USER_ID_BYTES = 30;
    // 重复消息检测时间窗口（10分钟）
    private static final long MESSAGE_DUPLICATE_WINDOW = 10 * 60 * 1000; // 10分钟
    // 连续相同字符限制
    private static final int MAX_CONSECUTIVE_SAME_CHARS = 5;
    // 单个实时音频块的Base64长度上限，防止异常客户端占用过多内存和带宽
    private static final int MAX_LIVE_AUDIO_BASE64_LENGTH = 1024;
    private static final long P2P_VOICE_REQUEST_TIMEOUT = 30000;
    private static final int LIVE_AUDIO_CHUNK_BYTES = 320;
    private static final int GROUP_AUDIO_INPUT_QUEUE_CAPACITY = 10;
    private static final int GROUP_AUDIO_TARGET_BUFFER_FRAMES = 6;
    private static final int GROUP_AUDIO_MAX_PLAYBACK_FRAMES = 15;
    private static final int MIN_VOICE_VOLUME_GAIN = 1;
    private static final int MAX_VOICE_VOLUME_GAIN = 6;

    // 允许连接服务器的最低客户端版本号
    private static final String MIN_CLIENT_VERSION = "3.0.0";
    
    // 公共频道群组名
    private static final String PUBLIC_CHANNEL_GROUP = "group_public";
    
    // 违禁词列表（从pbc.txt加载，若文件为空则使用默认值）
    private Set<String> forbiddenWords = new HashSet<>(Arrays.asList(
        "hello"
    ));

    // 图片保存根目录
    private static final String IMAGE_SAVE_DIR = "onlyph";

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
        loadOnlyPdConfiguration(true);
        initUI();
        setTitle("聊天服务器");
        setSize(760, 520);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        loadChatHistory(); // 启动时加载聊天记录
        loadBannedUsers(); // 启动时加载禁止用户列表
        loadMutedUsers(); // 启动时加载禁言用户列表
        loadOnlineUsers(); // 启动时加载在线用户列表
        loadForbiddenWords(); // 启动时加载屏蔽词列表
        deepSeekService = new DeepSeekService(); // 初始化DeepSeek服务
        startChannelConfigMonitor();
    }

    private void startChannelConfigMonitor() {
        channelConfigMonitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ChannelConfigMonitor");
            thread.setDaemon(true);
            return thread;
        });
        channelConfigMonitor.scheduleWithFixedDelay(
                () -> loadOnlyPdConfiguration(false), 1500, 1500, TimeUnit.MILLISECONDS);
    }

    private synchronized void loadOnlyPdConfiguration(boolean force) {
        try {
            if (onlyPdConfigPath == null) {
                onlyPdConfigPath = resolveConfigPath(ONLY_PD_CONFIG_FILE);
            }
            if (!Files.exists(onlyPdConfigPath)) {
                Path parent = onlyPdConfigPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(onlyPdConfigPath, EMPTY_ONLY_PD_CONFIG.getBytes(StandardCharsets.UTF_8));
            }

            long modified = Files.getLastModifiedTime(onlyPdConfigPath).toMillis();
            long size = Files.size(onlyPdConfigPath);
            if (!force && modified == onlyPdLastModified && size == onlyPdLastSize) {
                return;
            }

            String content = new String(Files.readAllBytes(onlyPdConfigPath), StandardCharsets.UTF_8);
            Map<String, String> newPasswords = new LinkedHashMap<>();
            Map<String, String> newGroups = new LinkedHashMap<>();
            Set<String> newConfiguredGroups = new LinkedHashSet<>();
            Matcher blockMatcher = CHANNEL_BLOCK_PATTERN.matcher(content);
            while (blockMatcher.find()) {
                String name = null;
                String password = null;
                Matcher fieldMatcher = CHANNEL_FIELD_PATTERN.matcher(blockMatcher.group(1));
                while (fieldMatcher.find()) {
                    String key = fieldMatcher.group(1).toLowerCase(Locale.ROOT);
                    String value = cleanConfigValue(fieldMatcher.group(2));
                    if ("name".equals(key)) {
                        name = value;
                    } else {
                        password = value;
                    }
                }
                if (name == null || name.isEmpty() || password == null) {
                    continue;
                }
                String group = channelGroupForName(name);
                newPasswords.put(name, password);
                newGroups.put(name, group);
                if (name.toLowerCase(Locale.ROOT).endsWith("chat") && name.length() > 4) {
                    newGroups.putIfAbsent(name.substring(0, name.length() - 4), group);
                }
                newConfiguredGroups.add(group);
            }
            newGroups.put("public", PUBLIC_CHANNEL_GROUP);
            newGroups.put("公共", PUBLIC_CHANNEL_GROUP);

            Set<String> removedGroups = new LinkedHashSet<>(configuredChannelGroups);
            removedGroups.removeAll(newConfiguredGroups);
            accountPasswords = Collections.unmodifiableMap(newPasswords);
            accountGroups = Collections.unmodifiableMap(newGroups);
            configuredChannelGroups = Collections.unmodifiableSet(newConfiguredGroups);
            onlyPdLastModified = modified;
            onlyPdLastSize = size;

            for (String removedGroup : removedGroups) {
                voiceChannelEnabled.put(removedGroup, false);
                Set<ClientHandler> members = voiceRooms.get(removedGroup);
                if (members != null) {
                    for (ClientHandler member : new ArrayList<>(members)) {
                        member.sendMessage("/live_group_disabled|" + removedGroup);
                    }
                }
            }
            if (serverVoiceGroup != null && removedGroups.contains(serverVoiceGroup)) {
                stopServerVoiceSession();
            }
            if (logArea != null) {
                log("频道配置已刷新: " + newConfiguredGroups.size() + " 个私有频道");
                refreshOnlineUsersPanel();
                refreshVoiceChannelPanel();
                refreshChannelManagementPanel();
            }
        } catch (Exception e) {
            if (logArea != null) {
                log("读取 " + ONLY_PD_CONFIG_FILE + " 失败，继续使用上次配置: " + e.getMessage());
            } else {
                System.err.println("读取 " + ONLY_PD_CONFIG_FILE + " 失败: " + e.getMessage());
            }
        }
    }

    private Path resolveConfigPath(String fileName) {
        Path workingPath = Paths.get(System.getProperty("user.dir"), fileName).toAbsolutePath().normalize();
        if (Files.exists(workingPath)) {
            return workingPath;
        }
        try {
            Path codePath = Paths.get(ChatServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path codeDirectory = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            if (codeDirectory != null) {
                Path besideCode = codeDirectory.resolve(fileName);
                if (Files.exists(besideCode)) {
                    return besideCode;
                }
                Path directoryName = codeDirectory.getFileName();
                if (directoryName != null && "target".equalsIgnoreCase(directoryName.toString())
                        && codeDirectory.getParent() != null) {
                    return codeDirectory.getParent().resolve(fileName);
                }
            }
        } catch (Exception ignored) {
            // 回退到服务器启动目录。
        }
        return workingPath;
    }

    private String cleanConfigValue(String value) {
        String cleaned = value == null ? "" : value.trim();
        while (cleaned.endsWith(",") || cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String channelGroupForName(String name) {
        String groupName = name.trim();
        if (groupName.startsWith("group_")) {
            return groupName;
        }
        if (groupName.toLowerCase(Locale.ROOT).endsWith("chat") && groupName.length() > 4) {
            groupName = groupName.substring(0, groupName.length() - 4);
        }
        return "group_" + groupName;
    }

    private synchronized void saveOnlyPdConfiguration(Map<String, String> channels) throws IOException {
        if (onlyPdConfigPath == null) {
            onlyPdConfigPath = resolveConfigPath(ONLY_PD_CONFIG_FILE);
        }
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : channels.entrySet()) {
            content.append("new{\n")
                    .append("name@").append(entry.getKey()).append('\n')
                    .append("passworld@").append(entry.getValue()).append('\n')
                    .append("};\n");
        }
        Path parent = onlyPdConfigPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = onlyPdConfigPath.resolveSibling(onlyPdConfigPath.getFileName() + ".tmp");
        Files.write(temporary, content.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, onlyPdConfigPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, onlyPdConfigPath, StandardCopyOption.REPLACE_EXISTING);
        }
        loadOnlyPdConfiguration(true);
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
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.add(scrollPane, BorderLayout.CENTER);

        // 底部输入栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        serverInputField = new JTextField();
        bottomPanel.add(serverInputField, BorderLayout.CENTER);
        chatPanel.add(bottomPanel, BorderLayout.SOUTH);

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.addTab("服务器日志", chatPanel);
        mainTabs.addTab("在线用户", createOnlineUsersPanel());
        mainTabs.addTab("语音频道", createVoiceChannelsPanel());
        mainTabs.addTab("频道管理", createChannelManagementPanel());
        add(mainTabs, BorderLayout.CENTER);

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

    private JPanel createChannelManagementPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel editor = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        editor.add(new JLabel("频道："), gbc);
        channelNameField = new JTextField(20);
        gbc.gridx = 1;
        gbc.weightx = 1;
        editor.add(channelNameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        editor.add(new JLabel("密码："), gbc);
        channelPasswordField = new JPasswordField(20);
        gbc.gridx = 1;
        gbc.weightx = 1;
        editor.add(channelPasswordField, gbc);

        noChannelPasswordCheckBox = new JCheckBox("无密码");
        noChannelPasswordCheckBox.addActionListener(e -> refreshChannelManagementState());
        gbc.gridx = 1;
        gbc.gridy = 2;
        editor.add(noChannelPasswordCheckBox, gbc);

        addChannelButton = new JButton("添加");
        addChannelButton.addActionListener(e -> addConfiguredChannel());
        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        editor.add(addChannelButton, gbc);
        panel.add(editor, BorderLayout.NORTH);

        channelManagementListModel = new DefaultListModel<>();
        channelManagementList = new JList<>(channelManagementListModel);
        channelManagementList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        channelManagementList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedChannelIntoEditor();
                refreshChannelManagementState();
            }
        });
        panel.add(new JScrollPane(channelManagementList), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        channelManagementStatusLabel = new JLabel();
        bottom.add(channelManagementStatusLabel, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        changeChannelPasswordButton = new JButton("修改密码");
        deleteChannelButton = new JButton("删除频道");
        changeChannelPasswordButton.addActionListener(e -> changeConfiguredChannelPassword());
        deleteChannelButton.addActionListener(e -> deleteConfiguredChannel());
        actions.add(changeChannelPasswordButton);
        actions.add(deleteChannelButton);
        bottom.add(actions, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);

        refreshChannelManagementPanel();
        return panel;
    }

    private void loadSelectedChannelIntoEditor() {
        String selected = channelManagementList == null ? null : channelManagementList.getSelectedValue();
        if (selected == null || PUBLIC_CHANNEL_DISPLAY.equals(selected)) {
            return;
        }
        channelNameField.setText(selected);
        channelPasswordField.setText("");
        noChannelPasswordCheckBox.setSelected(accountPasswords.getOrDefault(selected, "").isEmpty());
    }

    private void refreshChannelManagementPanel() {
        if (channelManagementListModel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            String selected = channelManagementList.getSelectedValue();
            channelManagementListModel.clear();
            channelManagementListModel.addElement(PUBLIC_CHANNEL_DISPLAY);
            for (String channel : accountPasswords.keySet()) {
                channelManagementListModel.addElement(channel);
            }
            if (selected != null) {
                channelManagementList.setSelectedValue(selected, true);
            }
            refreshChannelManagementState();
        });
    }

    private void refreshChannelManagementState() {
        if (addChannelButton == null) {
            return;
        }
        boolean editable = isChannelManagementAllowed();
        String selected = channelManagementList == null ? null : channelManagementList.getSelectedValue();
        boolean privateChannelSelected = selected != null && !PUBLIC_CHANNEL_DISPLAY.equals(selected);
        channelNameField.setEnabled(editable);
        noChannelPasswordCheckBox.setEnabled(editable);
        channelPasswordField.setEnabled(editable && !noChannelPasswordCheckBox.isSelected());
        addChannelButton.setEnabled(editable);
        deleteChannelButton.setEnabled(editable && privateChannelSelected);
        changeChannelPasswordButton.setEnabled(editable && privateChannelSelected);
        channelManagementStatusLabel.setText(editable
                ? "服务器已关闭，可以管理频道"
                : "服务器运行中，频道管理已锁定");
    }

    private boolean isChannelManagementAllowed() {
        return !serverStarting && (serverSocket == null || serverSocket.isClosed());
    }

    private void addConfiguredChannel() {
        if (!ensureChannelManagementAllowed()) {
            return;
        }
        String name = channelNameField.getText().trim();
        String password = noChannelPasswordCheckBox.isSelected()
                ? "" : new String(channelPasswordField.getPassword());
        String validationError = validateChannelInput(name, password, noChannelPasswordCheckBox.isSelected());
        if (validationError != null) {
            JOptionPane.showMessageDialog(this, validationError, "无法添加频道", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(accountPasswords);
        if (updated.containsKey(name)) {
            JOptionPane.showMessageDialog(this, "频道已经存在", "无法添加频道", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String newGroup = channelGroupForName(name);
        for (String existingName : updated.keySet()) {
            if (channelGroupForName(existingName).equals(newGroup)) {
                JOptionPane.showMessageDialog(this, "频道内部名称与 " + existingName + " 冲突",
                        "无法添加频道", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        updated.put(name, password);
        if (writeChannelConfiguration(updated, "频道已添加: " + name)) {
            channelNameField.setText("");
            channelPasswordField.setText("");
            noChannelPasswordCheckBox.setSelected(false);
        }
    }

    private void deleteConfiguredChannel() {
        if (!ensureChannelManagementAllowed()) {
            return;
        }
        String selected = channelManagementList.getSelectedValue();
        if (selected == null || PUBLIC_CHANNEL_DISPLAY.equals(selected)) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "确定删除频道 " + selected + " 吗？",
                "删除频道", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(accountPasswords);
        updated.remove(selected);
        writeChannelConfiguration(updated, "频道已删除: " + selected);
    }

    private void changeConfiguredChannelPassword() {
        if (!ensureChannelManagementAllowed()) {
            return;
        }
        String selected = channelManagementList.getSelectedValue();
        if (selected == null || PUBLIC_CHANNEL_DISPLAY.equals(selected)) {
            return;
        }
        String password = noChannelPasswordCheckBox.isSelected()
                ? "" : new String(channelPasswordField.getPassword());
        String validationError = validateChannelInput(selected, password, noChannelPasswordCheckBox.isSelected());
        if (validationError != null) {
            JOptionPane.showMessageDialog(this, validationError, "无法修改密码", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(accountPasswords);
        updated.put(selected, password);
        if (writeChannelConfiguration(updated, "频道密码已修改: " + selected)) {
            channelPasswordField.setText("");
            noChannelPasswordCheckBox.setSelected(false);
        }
    }

    private boolean ensureChannelManagementAllowed() {
        if (isChannelManagementAllowed()) {
            return true;
        }
        JOptionPane.showMessageDialog(this, "请先关闭服务器，再管理频道", "频道管理已锁定",
                JOptionPane.WARNING_MESSAGE);
        refreshChannelManagementState();
        return false;
    }

    private String validateChannelInput(String name, String password, boolean noPassword) {
        if (name.isEmpty()) {
            return "请输入频道名";
        }
        if ("public".equalsIgnoreCase(name) || "公共".equals(name)
                || PUBLIC_CHANNEL_GROUP.equalsIgnoreCase(name)) {
            return "公开频道是内置频道，不能重复创建";
        }
        if (name.indexOf('|') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                || name.indexOf('{') >= 0 || name.indexOf('}') >= 0) {
            return "频道名不能包含 |、换行或大括号";
        }
        if (!noPassword && password.isEmpty()) {
            return "请输入密码，或勾选无密码";
        }
        if (password.indexOf('|') >= 0 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0
                || password.indexOf('{') >= 0 || password.indexOf('}') >= 0) {
            return "密码不能包含 |、换行或大括号";
        }
        return null;
    }

    private boolean writeChannelConfiguration(Map<String, String> updated, String successMessage) {
        try {
            saveOnlyPdConfiguration(updated);
            log(successMessage);
            refreshChannelManagementPanel();
            return true;
        } catch (IOException e) {
            log("写入 " + ONLY_PD_CONFIG_FILE + " 失败: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "配置文件写入失败: " + e.getMessage(),
                    "频道管理失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private JPanel createOnlineUsersPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        onlineUsersSummaryLabel = new JLabel("在线用户 0 个");
        onlineUsersSummaryLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 3, 8));
        panel.add(onlineUsersSummaryLabel, BorderLayout.NORTH);

        onlineUsersRoot = new DefaultMutableTreeNode("频道");
        onlineUsersTreeModel = new DefaultTreeModel(onlineUsersRoot);
        onlineUsersTree = new JTree(onlineUsersTreeModel);
        onlineUsersTree.setRootVisible(false);
        onlineUsersTree.addTreeSelectionListener(e -> refreshOnlineUserActionState());
        onlineUsersTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String username = getSelectedOnlineUsername();
                    if (username != null) {
                        promptServerPrivateChat(username);
                    }
                }
            }
        });
        panel.add(new JScrollPane(onlineUsersTree), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        privateChatButton = new JButton("私聊");
        privateVoiceButton = new JButton("语音通话");
        privateChatButton.addActionListener(e -> {
            String username = getSelectedOnlineUsername();
            if (username != null) {
                promptServerPrivateChat(username);
            }
        });
        privateVoiceButton.addActionListener(e -> {
            String username = getSelectedOnlineUsername();
            if (username != null) {
                requestServerP2PVoice(username);
            }
        });
        actions.add(privateChatButton);
        actions.add(privateVoiceButton);
        panel.add(actions, BorderLayout.SOUTH);

        refreshOnlineUserActionState();
        refreshOnlineUsersPanel();
        return panel;
    }

    private void refreshOnlineUserActionState() {
        if (privateChatButton == null || privateVoiceButton == null) {
            return;
        }
        boolean hasUser = getSelectedOnlineUsername() != null;
        privateChatButton.setEnabled(hasUser);
        privateVoiceButton.setEnabled(hasUser);
    }

    private String getSelectedOnlineUsername() {
        if (onlineUsersTree == null) {
            return null;
        }
        TreePath path = onlineUsersTree.getSelectionPath();
        if (path == null || path.getPathCount() < 3) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode)) {
            return null;
        }
        Object userObject = ((DefaultMutableTreeNode) last).getUserObject();
        return userObject == null ? null : userObject.toString();
    }

    private void refreshOnlineUsersPanel() {
        if (onlineUsersRoot == null || onlineUsersTreeModel == null) {
            return;
        }
        Map<String, List<String>> snapshot = getOnlineUsersByGroupSnapshot();
        SwingUtilities.invokeLater(() -> {
            onlineUsersRoot.removeAllChildren();
            int total = 0;
            for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
                DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(
                        entry.getKey() + " (" + entry.getValue().size() + ")");
                for (String username : entry.getValue()) {
                    groupNode.add(new DefaultMutableTreeNode(username));
                    total++;
                }
                onlineUsersRoot.add(groupNode);
            }
            onlineUsersTreeModel.reload();
            for (int i = 0; i < onlineUsersTree.getRowCount(); i++) {
                onlineUsersTree.expandRow(i);
            }
            if (onlineUsersSummaryLabel != null) {
                onlineUsersSummaryLabel.setText("在线用户 " + total + " 个 | 频道 "
                        + snapshot.size() + " 个");
            }
            refreshOnlineUserActionState();
        });
    }

    private Map<String, List<String>> getOnlineUsersByGroupSnapshot() {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (String channel : knownVoiceChannels()) {
            grouped.put(channel, new TreeSet<>());
        }
        for (Map.Entry<String, List<ClientHandler>> entry : groups.entrySet()) {
            Set<String> activeUsers = new TreeSet<>();
            for (ClientHandler client : new ArrayList<>(entry.getValue())) {
                String nickname = client.getNickname();
                if (nickname != null && onlineUsers.contains(nickname)) {
                    activeUsers.add(nickname);
                }
            }
            if (!activeUsers.isEmpty()) {
                grouped.computeIfAbsent(entry.getKey(), key -> new TreeSet<>()).addAll(activeUsers);
            }
        }

        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    private JPanel createVoiceChannelsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        voiceOverviewLabel = new JLabel("语音频道服务未启动");
        voiceOverviewLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 3, 8));
        panel.add(voiceOverviewLabel, BorderLayout.NORTH);
        voiceChannelsPanel = new JPanel();
        voiceChannelsPanel.setLayout(new BoxLayout(voiceChannelsPanel, BoxLayout.Y_AXIS));
        panel.add(new JScrollPane(voiceChannelsPanel), BorderLayout.CENTER);
        refreshVoiceChannelPanel();
        return panel;
    }

    private static final class VoiceChannelRow {
        private final JPanel panel;
        private final JLabel statusLabel;
        private final JToggleButton enabledButton;
        private final JButton joinButton;
        private final JComboBox<String> volumeBox;

        private VoiceChannelRow(String group) {
            panel = new JPanel(new BorderLayout(8, 0));
            panel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            JLabel nameLabel = new JLabel(group);
            statusLabel = new JLabel();
            JPanel info = new JPanel(new GridLayout(2, 1));
            info.add(nameLabel);
            info.add(statusLabel);
            enabledButton = new JToggleButton("开启");
            joinButton = new JButton("服务器加入");
            volumeBox = new JComboBox<>(new String[]{"x1", "x2", "x3", "x4", "x5", "x6"});
            volumeBox.setToolTipText("设置该频道实时语音的播放音量倍率");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            actions.add(new JLabel("音量"));
            actions.add(volumeBox);
            actions.add(enabledButton);
            actions.add(joinButton);
            panel.add(info, BorderLayout.CENTER);
            panel.add(actions, BorderLayout.EAST);
        }
    }

    private Set<String> knownVoiceChannels() {
        Set<String> channels = new LinkedHashSet<>();
        channels.add(PUBLIC_CHANNEL_GROUP);
        channels.addAll(configuredChannelGroups);
        return channels;
    }

    private void refreshVoiceChannelPanel() {
        if (voiceChannelsPanel == null) {
            return;
        }
        if (!voiceChannelRefreshPending.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            voiceChannelRefreshPending.set(false);
            Set<String> channels = knownVoiceChannels();
            Iterator<Map.Entry<String, VoiceChannelRow>> rowIterator = voiceChannelRows.entrySet().iterator();
            while (rowIterator.hasNext()) {
                Map.Entry<String, VoiceChannelRow> entry = rowIterator.next();
                if (!channels.contains(entry.getKey())) {
                    voiceChannelsPanel.remove(entry.getValue().panel);
                    rowIterator.remove();
                    voiceChannelEnabled.remove(entry.getKey());
                    voiceChannelVolumeGain.remove(entry.getKey());
                }
            }
            for (String group : channels) {
                voiceChannelEnabled.putIfAbsent(group, true);
                voiceChannelVolumeGain.putIfAbsent(group, MIN_VOICE_VOLUME_GAIN);
                VoiceChannelRow row = voiceChannelRows.get(group);
                if (row == null) {
                    row = new VoiceChannelRow(group);
                    voiceChannelRows.put(group, row);
                    VoiceChannelRow finalRow = row;
                    row.enabledButton.addActionListener(e ->
                            setVoiceChannelEnabled(group, finalRow.enabledButton.isSelected()));
                    row.volumeBox.addActionListener(e ->
                            setVoiceChannelVolumeGain(group, finalRow.volumeBox.getSelectedIndex() + 1));
                    row.joinButton.addActionListener(e -> toggleServerVoiceChannel(group));
                    voiceChannelsPanel.add(row.panel);
                }
                boolean enabled = voiceChannelEnabled.getOrDefault(group, true);
                int volumeGain = voiceChannelVolumeGain.getOrDefault(group, MIN_VOICE_VOLUME_GAIN);
                boolean serverJoined = group.equals(serverVoiceGroup);
                Set<ClientHandler> members = voiceRooms.get(group);
                int memberCount = members == null ? 0 : members.size();
                if (serverJoined) {
                    memberCount++;
                }
                boolean running = serverSocket != null && !serverSocket.isClosed() && isRunning;
                row.enabledButton.setSelected(enabled);
                row.enabledButton.setText(enabled ? "关闭" : "开启");
                row.volumeBox.setSelectedIndex(volumeGain - 1);
                row.statusLabel.setText((enabled ? "已开启" : "已关闭") + " | "
                        + (running ? "运行中" : "未启动") + " | 成员 " + memberCount
                        + " | 音量 x" + volumeGain
                        + (serverJoined ? " | 服务器已加入" : ""));
                row.joinButton.setText(serverJoined ? "服务器退出" : "服务器加入");
                row.joinButton.setEnabled(running && enabled || serverJoined);
            }
            voiceChannelsPanel.revalidate();
            voiceChannelsPanel.repaint();
            if (voiceOverviewLabel != null) {
                voiceOverviewLabel.setText("频道 " + channels.size() + " 个 | 开启 "
                        + channels.stream().filter(g -> voiceChannelEnabled.getOrDefault(g, true)).count()
                        + " 个 | 服务器当前加入: " + (serverVoiceGroup == null ? "无" : serverVoiceGroup));
            }
        });
    }

    private void setVoiceChannelEnabled(String group, boolean enabled) {
        voiceChannelEnabled.put(group, enabled);
        if (!enabled) {
            Set<ClientHandler> members = voiceRooms.get(group);
            if (members != null) {
                for (ClientHandler member : new ArrayList<>(members)) {
                    leaveVoiceRoom(member, true);
                    member.sendMessage("/live_group_disabled|" + group);
                }
            }
            if (group.equals(serverVoiceGroup)) {
                stopServerVoiceSession();
            }
        }
        refreshVoiceChannelPanel();
    }

    private void setVoiceChannelVolumeGain(String group, int volumeGain) {
        int clampedGain = Math.max(MIN_VOICE_VOLUME_GAIN,
                Math.min(MAX_VOICE_VOLUME_GAIN, volumeGain));
        Integer previousGain = voiceChannelVolumeGain.put(group, clampedGain);
        if (previousGain == null || previousGain != clampedGain) {
            log("语音频道音量已设置: " + group + " -> x" + clampedGain);
            refreshVoiceChannelPanel();
        }
    }

    private void toggleServerVoiceChannel(String group) {
        if (group.equals(serverVoiceGroup)) {
            stopServerVoiceSession();
            return;
        }
        if (!voiceChannelEnabled.getOrDefault(group, true)) {
            log("语音频道已关闭，无法加入: " + group);
            return;
        }
        if (serverSocket == null || serverSocket.isClosed() || !isRunning) {
            log("请先启动服务器，再加入语音频道");
            return;
        }
        if (serverP2PVoicePeer != null || serverP2PVoicePendingUser != null || serverP2PVoiceStarting) {
            log("请先结束服务器私聊语音，再加入频道语音");
            return;
        }
        stopServerVoiceSession();
        startServerVoiceSession(group);
    }

    private void startServerVoiceSession(String group) {
        synchronized (serverVoiceLock) {
            if (serverVoiceGroup != null || serverVoiceStarting) {
                return;
            }
            serverVoiceStarting = true;
        }
        new Thread(() -> {
            TargetDataLine target = null;
            SourceDataLine source = null;
            try {
                target = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, SERVER_VOICE_FORMAT));
                source = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, SERVER_VOICE_FORMAT));
                target.open(SERVER_VOICE_FORMAT, SERVER_VOICE_CHUNK_BYTES * 8);
                source.open(SERVER_VOICE_FORMAT, SERVER_VOICE_CHUNK_BYTES * 8);
                target.start();
                source.start();
                synchronized (serverVoiceLock) {
                    if (!serverVoiceStarting) {
                        target.close();
                        source.close();
                        return;
                    }
                    serverVoiceTargetLine = target;
                    serverVoiceSourceLine = source;
                    serverVoiceGroup = group;
                    serverVoiceStarting = false;
                }
                serverVoicePlaybackQueue.clear();
                startServerVoiceCapture(target, group);
                startServerVoicePlayback(source);
                refreshVoiceChannelPanel();
                log("服务器已加入语音频道: " + group);
            } catch (Exception ex) {
                if (target != null) target.close();
                if (source != null) source.close();
                synchronized (serverVoiceLock) {
                    serverVoiceStarting = false;
                    serverVoiceGroup = null;
                }
                refreshVoiceChannelPanel();
                log("服务器加入语音频道失败: " + ex.getMessage());
            }
        }, "ServerVoiceStarter").start();
    }

    private void startServerVoiceCapture(TargetDataLine target, String group) {
        serverVoiceCaptureThread = new Thread(() -> {
            byte[] buffer = new byte[SERVER_VOICE_CHUNK_BYTES];
            while (group.equals(serverVoiceGroup) && !Thread.currentThread().isInterrupted()) {
                int count = target.read(buffer, 0, buffer.length);
                if (count > 0 && group.equals(serverVoiceGroup)) {
                    byte[] frame = new byte[SERVER_VOICE_CHUNK_BYTES];
                    System.arraycopy(buffer, 0, frame, 0, Math.min(count, frame.length));
                    BlockingQueue<byte[]> queue = serverVoiceAudioQueues.computeIfAbsent(group,
                            key -> new ArrayBlockingQueue<>(GROUP_AUDIO_INPUT_QUEUE_CAPACITY));
                    offerAudioFrame(queue, frame, GROUP_AUDIO_INPUT_QUEUE_CAPACITY);
                }
            }
        }, "ServerVoiceCapture");
        serverVoiceCaptureThread.setDaemon(true);
        serverVoiceCaptureThread.start();
    }

    private void startServerVoicePlayback(SourceDataLine source) {
        serverVoicePlaybackThread = new Thread(() -> {
            while (serverVoiceGroup != null && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = serverVoicePlaybackQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        source.write(frame, 0, frame.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ServerVoicePlayback");
        serverVoicePlaybackThread.setDaemon(true);
        serverVoicePlaybackThread.start();
    }

    private void stopServerVoiceSession() {
        TargetDataLine target;
        SourceDataLine source;
        synchronized (serverVoiceLock) {
            serverVoiceStarting = false;
            serverVoiceGroup = null;
            target = serverVoiceTargetLine;
            source = serverVoiceSourceLine;
            serverVoiceTargetLine = null;
            serverVoiceSourceLine = null;
        }
        if (target != null) {
            target.stop();
            target.close();
        }
        if (source != null) {
            source.stop();
            source.flush();
            source.close();
        }
        if (serverVoiceCaptureThread != null) serverVoiceCaptureThread.interrupt();
        if (serverVoicePlaybackThread != null) serverVoicePlaybackThread.interrupt();
        serverVoicePlaybackQueue.clear();
        serverVoiceAudioQueues.clear();
        refreshVoiceChannelPanel();
    }

    private void promptServerPrivateChat(String username) {
        ClientHandler target = findClientHandlerByNickname(username);
        if (target == null) {
            log("服务器私聊失败，用户不在线: " + username);
            refreshOnlineUsersPanel();
            return;
        }
        String message = JOptionPane.showInputDialog(this,
                "发送给 " + username + " 的私聊消息:",
                "服务器私聊", JOptionPane.PLAIN_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        sendServerPrivateMessage(username, message.trim());
    }

    private void sendServerPrivateMessage(String username, String message) {
        if (isMessageTooLong(message)) {
            log("服务器私聊失败，消息超过" + MAX_MESSAGE_BYTES + "字节限制");
            return;
        }
        ClientHandler target = findClientHandlerByNickname(username);
        if (target == null) {
            log("服务器私聊失败，用户不在线: " + username);
            refreshOnlineUsersPanel();
            return;
        }
        target.sendMessage("/p2p_msg|" + SERVER_P2P_NAME + "|" + SERVER_P2P_PASSWORD + "|" + message);
        log("[server -> " + username + "] " + message);
    }

    private void requestServerP2PVoice(String username) {
        if (serverSocket == null || serverSocket.isClosed() || !isRunning) {
            log("请先启动服务器，再发起私聊语音");
            return;
        }
        ClientHandler target = findClientHandlerByNickname(username);
        if (target == null) {
            log("服务器私聊语音失败，用户不在线: " + username);
            refreshOnlineUsersPanel();
            return;
        }
        if (isUserMuted(username)) {
            log("服务器私聊语音失败，用户当前被禁言: " + username);
            return;
        }
        if (isVoiceRoomMember(target) || activeP2PVoicePeers.containsKey(username)
                || pendingP2PVoiceRequests.containsKey(username)
                || pendingP2PVoiceRequests.containsValue(username)) {
            log("服务器私聊语音失败，对方正在语音或有待处理申请: " + username);
            return;
        }
        synchronized (serverP2PVoiceLock) {
            if (serverP2PVoicePeer != null || serverP2PVoicePendingUser != null || serverP2PVoiceStarting) {
                log("服务器已有私聊语音会话或申请");
                return;
            }
            serverP2PVoicePendingUser = username;
            serverP2PVoicePendingTime = System.currentTimeMillis();
        }
        stopServerVoiceSession();
        target.sendMessage("/live_p2p_request|" + SERVER_P2P_NAME + "|" + SERVER_P2P_PASSWORD);
        log("服务器已向 " + username + " 发起私聊语音申请");
    }

    private boolean handleServerP2PVoiceAccept(ClientHandler targetHandler, String requesterPassword) {
        if (!SERVER_P2P_PASSWORD.equals(requesterPassword)) {
            return false;
        }
        String username = targetHandler.getNickname();
        synchronized (serverP2PVoiceLock) {
            if (!username.equals(serverP2PVoicePendingUser)) {
                targetHandler.sendMessage("/live_voice_error|语音申请已失效");
                return true;
            }
            if (isVoiceRoomMember(targetHandler) || activeP2PVoicePeers.containsKey(username)
                    || pendingP2PVoiceRequests.containsKey(username)
                    || pendingP2PVoiceRequests.containsValue(username)) {
                serverP2PVoicePendingUser = null;
                serverP2PVoicePendingTime = 0;
                targetHandler.sendMessage("/live_voice_error|您当前已有语音会话或待处理申请");
                return true;
            }
            if (serverP2PVoicePeer != null || serverP2PVoiceStarting) {
                targetHandler.sendMessage("/live_voice_error|服务器当前已有语音会话");
                return true;
            }
            serverP2PVoicePendingUser = null;
            serverP2PVoicePendingTime = 0;
            serverP2PVoiceStarting = true;
        }
        startServerP2PVoiceSession(targetHandler);
        return true;
    }

    private boolean handleServerP2PVoiceReject(ClientHandler targetHandler, String requesterPassword) {
        if (!SERVER_P2P_PASSWORD.equals(requesterPassword)) {
            return false;
        }
        String username = targetHandler.getNickname();
        synchronized (serverP2PVoiceLock) {
            if (username.equals(serverP2PVoicePendingUser)) {
                serverP2PVoicePendingUser = null;
                serverP2PVoicePendingTime = 0;
                log("用户已拒绝服务器私聊语音申请: " + username);
            }
        }
        return true;
    }

    private void startServerP2PVoiceSession(ClientHandler targetHandler) {
        String peer = targetHandler.getNickname();
        new Thread(() -> {
            TargetDataLine target = null;
            SourceDataLine source = null;
            try {
                target = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, SERVER_VOICE_FORMAT));
                source = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, SERVER_VOICE_FORMAT));
                target.open(SERVER_VOICE_FORMAT, SERVER_VOICE_CHUNK_BYTES * 8);
                source.open(SERVER_VOICE_FORMAT, SERVER_VOICE_CHUNK_BYTES * 8);
                target.start();
                source.start();
                synchronized (serverP2PVoiceLock) {
                    if (!serverP2PVoiceStarting) {
                        target.close();
                        source.close();
                        return;
                    }
                    serverP2PVoiceTargetLine = target;
                    serverP2PVoiceSourceLine = source;
                    serverP2PVoicePeer = peer;
                    serverP2PVoiceStarting = false;
                }
                serverP2PVoicePlaybackQueue.clear();
                targetHandler.sendMessage("/live_p2p_started|" + SERVER_P2P_NAME + "|" + SERVER_P2P_PASSWORD);
                startServerP2PVoiceCapture(target, peer);
                startServerP2PVoicePlayback(source);
                log("服务器私聊语音已建立: " + SERVER_P2P_NAME + " <-> " + peer);
            } catch (Exception ex) {
                if (target != null) target.close();
                if (source != null) source.close();
                synchronized (serverP2PVoiceLock) {
                    serverP2PVoiceStarting = false;
                    serverP2PVoicePeer = null;
                }
                targetHandler.sendMessage("/live_p2p_rejected|" + SERVER_P2P_NAME + "|服务器语音设备启动失败");
                log("服务器私聊语音启动失败: " + ex.getMessage());
            }
        }, "ServerP2PVoiceStarter").start();
    }

    private void startServerP2PVoiceCapture(TargetDataLine target, String peer) {
        serverP2PVoiceCaptureThread = new Thread(() -> {
            byte[] buffer = new byte[SERVER_VOICE_CHUNK_BYTES];
            while (peer.equals(serverP2PVoicePeer) && !Thread.currentThread().isInterrupted()) {
                int count = target.read(buffer, 0, buffer.length);
                if (count <= 0 || !peer.equals(serverP2PVoicePeer)) {
                    continue;
                }
                byte[] frame = new byte[SERVER_VOICE_CHUNK_BYTES];
                System.arraycopy(buffer, 0, frame, 0, Math.min(count, frame.length));
                ClientHandler peerHandler = findClientHandlerByNickname(peer);
                if (peerHandler == null) {
                    stopServerP2PVoiceSession(false);
                    break;
                }
                peerHandler.sendMessage("/live_p2p_audio|" + SERVER_P2P_NAME + "|"
                        + Base64.getEncoder().encodeToString(frame));
            }
        }, "ServerP2PVoiceCapture");
        serverP2PVoiceCaptureThread.setDaemon(true);
        serverP2PVoiceCaptureThread.start();
    }

    private void startServerP2PVoicePlayback(SourceDataLine source) {
        serverP2PVoicePlaybackThread = new Thread(() -> {
            while (serverP2PVoicePeer != null && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = serverP2PVoicePlaybackQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        source.write(frame, 0, frame.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ServerP2PVoicePlayback");
        serverP2PVoicePlaybackThread.setDaemon(true);
        serverP2PVoicePlaybackThread.start();
    }

    private boolean handleServerP2PVoiceAudio(String username, String audioData) {
        if (!username.equals(serverP2PVoicePeer)) {
            return false;
        }
        if (audioData.isEmpty() || audioData.length() > MAX_LIVE_AUDIO_BASE64_LENGTH) {
            return true;
        }
        try {
            byte[] audioFrame = Base64.getDecoder().decode(audioData);
            if (audioFrame.length == 0 || audioFrame.length > LIVE_AUDIO_CHUNK_BYTES) {
                return true;
            }
            if (audioFrame.length != LIVE_AUDIO_CHUNK_BYTES) {
                audioFrame = Arrays.copyOf(audioFrame, LIVE_AUDIO_CHUNK_BYTES);
            }
            offerAudioFrame(serverP2PVoicePlaybackQueue, audioFrame, GROUP_AUDIO_MAX_PLAYBACK_FRAMES);
        } catch (IllegalArgumentException ignored) {
            // 忽略无效Base64音频帧
        }
        return true;
    }

    private void stopServerP2PVoiceSession(boolean notifyPeer) {
        String peer;
        TargetDataLine target;
        SourceDataLine source;
        synchronized (serverP2PVoiceLock) {
            serverP2PVoiceStarting = false;
            peer = serverP2PVoicePeer;
            serverP2PVoicePeer = null;
            target = serverP2PVoiceTargetLine;
            source = serverP2PVoiceSourceLine;
            serverP2PVoiceTargetLine = null;
            serverP2PVoiceSourceLine = null;
        }
        if (target != null) {
            target.stop();
            target.close();
        }
        if (source != null) {
            source.stop();
            source.flush();
            source.close();
        }
        if (serverP2PVoiceCaptureThread != null) serverP2PVoiceCaptureThread.interrupt();
        if (serverP2PVoicePlaybackThread != null) serverP2PVoicePlaybackThread.interrupt();
        serverP2PVoicePlaybackQueue.clear();
        if (notifyPeer && peer != null) {
            ClientHandler peerHandler = findClientHandlerByNickname(peer);
            if (peerHandler != null) {
                peerHandler.sendMessage("/live_p2p_ended|" + SERVER_P2P_NAME);
            }
        }
        if (peer != null) {
            log("服务器私聊语音已结束: " + SERVER_P2P_NAME + " <-> " + peer);
        }
    }

    private void cleanupExpiredServerP2PVoiceRequest() {
        String pendingUser;
        synchronized (serverP2PVoiceLock) {
            if (serverP2PVoicePendingUser == null
                    || System.currentTimeMillis() - serverP2PVoicePendingTime <= P2P_VOICE_REQUEST_TIMEOUT) {
                return;
            }
            pendingUser = serverP2PVoicePendingUser;
            serverP2PVoicePendingUser = null;
            serverP2PVoicePendingTime = 0;
        }
        ClientHandler target = findClientHandlerByNickname(pendingUser);
        if (target != null) {
            target.sendMessage("/live_p2p_cancelled|" + SERVER_P2P_NAME);
        }
        log("服务器私聊语音申请已超时: " + pendingUser);
    }

    private void handleServerP2PClientUnavailable(String username) {
        if (username == null) {
            return;
        }
        if (username.equals(serverP2PVoicePeer)) {
            stopServerP2PVoiceSession(false);
        }
        synchronized (serverP2PVoiceLock) {
            if (username.equals(serverP2PVoicePendingUser)) {
                serverP2PVoicePendingUser = null;
                serverP2PVoicePendingTime = 0;
                log("服务器私聊语音申请已取消，用户离线: " + username);
            }
        }
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
        return compareVersionNumbers(clientVersion, MIN_CLIENT_VERSION) >= 0;
    }

    private int compareVersionNumbers(String left, String right) {
        if (left == null || right == null) {
            return -1;
        }
        String[] leftParts = left.trim().split("\\.");
        String[] rightParts = right.trim().split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            int leftPart;
            int rightPart;
            try {
                leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
                rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            } catch (NumberFormatException e) {
                return -1;
            }
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
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
        if (serverStarting || (serverSocket != null && !serverSocket.isClosed())) {
            return;
        }
        serverStarting = true;
        startBtn.setEnabled(false);
        refreshChannelManagementState();
        new Thread(() -> {
            try {
                isRunning = true;
                int port = Integer.parseInt(portField.getText());
                serverSocket = new ServerSocket(port);
                serverStarting = false;
                log("服务器启动成功，监听端口: " + port);
                refreshVoiceChannelPanel();
                portField.setEnabled(false);
                stopBtn.setEnabled(true); // 启用关闭服务器按钮
                refreshChannelManagementState();
                
                // 清理旧的在线用户列表，确保下次启动后用户可以成功进入聊天
                onlineUsers.clear();
                saveOnlineUsers();
                refreshOnlineUsersPanel();
                log("已清理旧的在线用户列表");
                
                // 启动定时清理任务
                startCleanupTask();

                // 启动频道实时语音混音任务
                startVoiceMixerTask();
                
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
                serverStarting = false;
                if (serverSocket != null && !serverSocket.isClosed()) {
                    log("服务器启动失败: " + ex.getMessage());
                }
                SwingUtilities.invokeLater(() -> {
                    startBtn.setEnabled(true);
                    portField.setEnabled(true);
                    stopBtn.setEnabled(false);
                    refreshChannelManagementState();
                });
            } catch (RuntimeException ex) {
                serverStarting = false;
                log("服务器启动失败: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    startBtn.setEnabled(true);
                    portField.setEnabled(true);
                    stopBtn.setEnabled(false);
                    refreshChannelManagementState();
                });
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
                                                groupClients.remove(handler);
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
        stopServerVoiceSession();
        stopServerP2PVoiceSession(true);
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
        stopServerVoiceSession();
        
        // 关闭服务器socket
        shutdown();
        
        // 清理所有数据
        clearAllServerData();
        
        // 重置UI状态
        SwingUtilities.invokeLater(() -> {
            serverStarting = false;
            startBtn.setEnabled(true);
            portField.setEnabled(true);
            stopBtn.setEnabled(false);
            refreshVoiceChannelPanel();
            refreshChannelManagementState();
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
                        client.sendMessage("/server_shutdown|服务器正在关闭，请重新连接");
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
        voiceRooms.clear();
        voiceRoomAudioQueues.clear();
        activeP2PVoicePeers.clear();
        pendingP2PVoiceRequests.clear();
        pendingP2PVoiceRequestTimes.clear();
        clientLastActiveTime.clear(); // 清理客户端活跃时间记录
        synchronized (serverP2PVoiceLock) {
            serverP2PVoicePendingUser = null;
            serverP2PVoicePendingTime = 0;
        }
        refreshOnlineUsersPanel();
        
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
        voiceRooms.clear();
        voiceRoomAudioQueues.clear();
        activeP2PVoicePeers.clear();
        pendingP2PVoiceRequests.clear();
        pendingP2PVoiceRequestTimes.clear();
        synchronized (serverP2PVoiceLock) {
            serverP2PVoicePendingUser = null;
            serverP2PVoicePendingTime = 0;
        }
        refreshOnlineUsersPanel();
        
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
                    cleanupExpiredP2PVoiceRequests();
                    cleanupExpiredServerP2PVoiceRequest();
                    cleanupExpiredMutedUsers();
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
        } else if (command.startsWith("/kick ")) {
            String username = command.substring(6).trim();
            if (!username.isEmpty()) {
                kickUser(username);
            } else {
                log("用法: /kick 用户名");
            }
        } else if (command.startsWith("/mute ")) {
            String[] parts = command.split("\\s+", 3);
            if (parts.length != 3) {
                log("用法: /mute 用户名 小时数");
                return;
            }
            try {
                double hours = Double.parseDouble(parts[2]);
                if (hours <= 0) {
                    log("禁言时间必须大于0小时");
                    return;
                }
                muteUser(parts[1], hours);
            } catch (NumberFormatException e) {
                log("禁言时间必须是数字，单位为小时");
            }
        } else if (command.equals("/unmute") || command.startsWith("/unmute ")) {
            String username = command.length() > 7 ? command.substring(8).trim() : "";
            if (username.isEmpty()) {
                log("用法: /unmute 用户名");
                return;
            }
            unmuteUser(username);
        } else if (command.startsWith("/chatone ")) {
            String[] parts = command.split("\\s+", 3);
            if (parts.length != 3 || parts[2].trim().isEmpty()) {
                log("用法: /chatone 频道名 消息内容");
                return;
            }
            sendServerMessageToGroup(parts[1], parts[2].trim());
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
                        clients.remove(client);
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

    private void loadMutedUsers() {
        Path mutedPath = Paths.get(MUTED_USERS_FILE);
        if (!Files.exists(mutedPath)) {
            saveMutedUsers();
            return;
        }

        long now = System.currentTimeMillis();
        boolean needsRewrite = false;
        try (BufferedReader reader = Files.newBufferedReader(mutedPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t", 2);
                if (parts.length != 2 || parts[1].trim().isEmpty()) {
                    needsRewrite = true;
                    continue;
                }
                try {
                    long expiresAt = Long.parseLong(parts[0]);
                    if (expiresAt > now) {
                        mutedUsers.put(parts[1], expiresAt);
                    } else {
                        needsRewrite = true;
                    }
                } catch (NumberFormatException e) {
                    needsRewrite = true;
                }
            }
            log("从 " + MUTED_USERS_FILE + " 加载禁言用户，共 " + mutedUsers.size() + " 个");
        } catch (IOException e) {
            log("加载禁言用户列表失败: " + e.getMessage());
            return;
        }

        if (needsRewrite) {
            saveMutedUsers();
        }
    }

    private synchronized void saveMutedUsers() {
        Path mutedPath = Paths.get(MUTED_USERS_FILE);
        Path tempPath = Paths.get(MUTED_USERS_FILE + ".tmp");
        List<String> lines = new ArrayList<>();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(mutedUsers.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Long> entry : entries) {
            lines.add(entry.getValue() + "\t" + entry.getKey());
        }

        try {
            Files.write(tempPath, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, mutedPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempPath, mutedPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log("保存禁言用户列表失败: " + e.getMessage());
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
        refreshOnlineUsersPanel();
    }

    // 添加用户到在线用户列表
    private void addOnlineUser(String username) {
        if (onlineUsers.add(username)) {
            saveOnlineUsers(); // 更新文件
        }
        refreshOnlineUsersPanel();
    }

    private ClientHandler findClientHandlerByNickname(String username) {
        ClientHandler handler = userHandlers.get(username);
        if (handler != null) {
            return handler;
        }
        for (List<ClientHandler> clients : groups.values()) {
            for (ClientHandler client : new ArrayList<>(clients)) {
                if (username.equals(client.getNickname())) {
                    return client;
                }
            }
        }
        return null;
    }

    private boolean isUserMuted(String username) {
        if (username == null) {
            return false;
        }
        Long expiresAt = mutedUsers.get(username);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiresAt) {
            if (mutedUsers.remove(username, expiresAt)) {
                saveMutedUsers();
                log("用户禁言已到期: " + username);
            }
            return false;
        }
        return true;
    }

    private void cleanupExpiredMutedUsers() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, Long> entry : mutedUsers.entrySet()) {
            if (entry.getValue() <= now && mutedUsers.remove(entry.getKey(), entry.getValue())) {
                log("用户禁言已到期: " + entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            saveMutedUsers();
        }
    }

    private String getMuteRemainingText(String username) {
        Long expiresAt = mutedUsers.get(username);
        if (expiresAt == null) {
            return "0分钟";
        }
        long remainingMillis = Math.max(0, expiresAt - System.currentTimeMillis());
        long totalMinutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(remainingMillis));
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0 && minutes > 0) {
            return hours + "小时" + minutes + "分钟";
        }
        if (hours > 0) {
            return hours + "小时";
        }
        return minutes + "分钟";
    }

    private void sendServerMessageToGroup(String channelName, String message) {
        String group = normalizeChannelName(channelName);
        List<ClientHandler> clients = groups.get(group);
        if (clients == null || clients.isEmpty()) {
            log("频道不存在或当前无人在线: " + channelName + " (" + group + ")");
            return;
        }

        String messageToBroadcast = message;
        if (PUBLIC_CHANNEL_GROUP.equals(group) && containsForbiddenWords(message)) {
            log("服务器频道消息检测到违禁词，消息将被过滤: " + message);
            messageToBroadcast = filterForbiddenWords(message);
        }

        for (ClientHandler client : new ArrayList<>(clients)) {
            try {
                client.sendMessage("[server] " + messageToBroadcast);
            } catch (Exception e) {
                clients.remove(client);
            }
        }
        log("[server -> " + group + "] " + messageToBroadcast);
    }

    private String normalizeChannelName(String channelName) {
        String name = channelName.trim();
        String mapped = accountGroups.get(name);
        if (mapped != null) {
            return mapped;
        }
        if (name.startsWith("group_")) {
            return name;
        }
        return "group_" + name;
    }

    private void kickUser(String username) {
        ClientHandler handler = findClientHandlerByNickname(username);
        if (handler == null) {
            log("踢出失败，用户不在线: " + username);
            refreshOnlineUsersPanel();
            return;
        }
        try {
            handler.sendMessage("您已被服务器强制踢出");
            handler.closeConnection();
            log("服务器: 已强制踢出用户 " + username);
        } catch (IOException e) {
            log("踢出用户失败: " + username + "，" + e.getMessage());
        }
    }

    private void muteUser(String username, double hours) {
        long durationMillis = Math.max(1L, Math.round(hours * 60 * 60 * 1000));
        long expiresAt = System.currentTimeMillis() + durationMillis;
        mutedUsers.put(username, expiresAt);
        saveMutedUsers();
        ClientHandler handler = findClientHandlerByNickname(username);
        if (handler != null) {
            leaveVoiceRoom(handler, true);
            endP2PVoiceCall(username);
            clearPendingP2PVoiceRequests(username);
            if (username.equals(serverP2PVoicePeer)) {
                stopServerP2PVoiceSession(true);
            }
            synchronized (serverP2PVoiceLock) {
                if (username.equals(serverP2PVoicePendingUser)) {
                    serverP2PVoicePendingUser = null;
                    serverP2PVoicePendingTime = 0;
                    handler.sendMessage("/live_p2p_cancelled|" + SERVER_P2P_NAME);
                }
            }
            handler.sendMessage("您已被服务器禁言 " + formatHours(hours) + " 小时，只能查看其他用户消息");
        }
        log("服务器: 已禁言用户 " + username + "，时长 " + formatHours(hours) + " 小时");
    }

    private void unmuteUser(String username) {
        Long removed = mutedUsers.remove(username);
        if (removed == null) {
            log("解除禁言失败，未找到禁言用户: " + username);
            return;
        }
        saveMutedUsers();
        ClientHandler handler = findClientHandlerByNickname(username);
        if (handler != null) {
            handler.sendMessage("服务器已解除您的禁言");
        }
        log("服务器: 已解除用户禁言 " + username);
    }

    private boolean isReservedServerUsername(String username) {
        return username != null && SERVER_P2P_NAME.equalsIgnoreCase(username.trim());
    }

    private void notifyMutedStatus(ClientHandler handler) {
        String username = handler.getNickname();
        if (isUserMuted(username)) {
            handler.sendMessage("您当前仍被服务器禁言，剩余" + getMuteRemainingText(username)
                    + "，只能查看其他用户消息");
        }
    }

    private String formatHours(double hours) {
        if (Math.floor(hours) == hours) {
            return String.valueOf((long) hours);
        }
        return String.format(Locale.ROOT, "%.2f", hours);
    }

    private boolean isVoiceRoomMember(ClientHandler client) {
        if (client == null || client.group == null) {
            return false;
        }
        Set<ClientHandler> members = voiceRooms.get(client.group);
        return members != null && members.contains(client);
    }

    private void broadcastVoiceRoomMemberCount(String group) {
        Set<ClientHandler> members = voiceRooms.get(group);
        if (members == null) {
            return;
        }
        String message = "/live_group_members|" + members.size();
        for (ClientHandler member : members) {
            member.sendMessage(message);
        }
        refreshVoiceChannelPanel();
    }

    private void leaveVoiceRoom(ClientHandler client, boolean notifySelf) {
        if (client == null || client.group == null) {
            return;
        }
        Set<ClientHandler> members = voiceRooms.get(client.group);
        if (members == null || !members.remove(client)) {
            return;
        }
        if (members.isEmpty()) {
            voiceRooms.remove(client.group, members);
            voiceRoomAudioQueues.remove(client.group);
        } else {
            Map<ClientHandler, BlockingQueue<byte[]>> queues = voiceRoomAudioQueues.get(client.group);
            if (queues != null) {
                queues.remove(client);
            }
            broadcastVoiceRoomMemberCount(client.group);
        }
        if (notifySelf) {
            client.sendMessage("/live_group_left");
        }
        log("用户 " + client.nickname + " 退出频道语音: " + client.group);
        refreshVoiceChannelPanel();
    }

    private void endP2PVoiceCall(String username) {
        if (username == null) {
            return;
        }
        String peer = activeP2PVoicePeers.remove(username);
        if (peer == null) {
            return;
        }
        activeP2PVoicePeers.remove(peer, username);
        ClientHandler userHandler = userHandlers.get(username);
        ClientHandler peerHandler = userHandlers.get(peer);
        if (userHandler != null) {
            userHandler.sendMessage("/live_p2p_ended|" + peer);
        }
        if (peerHandler != null) {
            peerHandler.sendMessage("/live_p2p_ended|" + username);
        }
        log("私聊语音已结束: " + username + " <-> " + peer);
    }

    private void clearPendingP2PVoiceRequests(String username) {
        if (username == null) {
            return;
        }
        String requester = pendingP2PVoiceRequests.remove(username);
        pendingP2PVoiceRequestTimes.remove(username);
        if (requester != null) {
            ClientHandler requesterHandler = userHandlers.get(requester);
            if (requesterHandler != null) {
                requesterHandler.sendMessage("/live_p2p_rejected|" + username + "|用户已离线");
            }
        }
        for (Map.Entry<String, String> entry : pendingP2PVoiceRequests.entrySet()) {
            if (username.equals(entry.getValue()) && pendingP2PVoiceRequests.remove(entry.getKey(), username)) {
                pendingP2PVoiceRequestTimes.remove(entry.getKey());
                ClientHandler targetHandler = userHandlers.get(entry.getKey());
                if (targetHandler != null) {
                    targetHandler.sendMessage("/live_p2p_cancelled|" + username);
                }
            }
        }
    }

    private void cleanupExpiredP2PVoiceRequests() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : pendingP2PVoiceRequestTimes.entrySet()) {
            String target = entry.getKey();
            Long createdAt = entry.getValue();
            if (createdAt == null || now - createdAt < P2P_VOICE_REQUEST_TIMEOUT) {
                continue;
            }
            if (!pendingP2PVoiceRequestTimes.remove(target, createdAt)) {
                continue;
            }
            String requester = pendingP2PVoiceRequests.remove(target);
            if (requester == null) {
                continue;
            }
            ClientHandler requesterHandler = userHandlers.get(requester);
            ClientHandler targetHandler = userHandlers.get(target);
            if (requesterHandler != null) {
                requesterHandler.sendMessage("/live_p2p_rejected|" + target + "|语音申请已超时");
            }
            if (targetHandler != null) {
                targetHandler.sendMessage("/live_p2p_cancelled|" + requester);
            }
        }
    }

    private void startVoiceMixerTask() {
        final ServerSocket mixerServerSocket = serverSocket;
        Thread mixerThread = new Thread(() -> {
            final long frameNanos = TimeUnit.MILLISECONDS.toNanos(20);
            long nextFrameAt = System.nanoTime();
            while (isRunning && serverSocket == mixerServerSocket
                    && mixerServerSocket != null && !mixerServerSocket.isClosed()) {
                mixVoiceRoomFrames();
                nextFrameAt += frameNanos;
                long sleepNanos = nextFrameAt - System.nanoTime();
                try {
                    if (sleepNanos > 0) {
                        TimeUnit.NANOSECONDS.sleep(sleepNanos);
                    } else if (sleepNanos < -frameNanos * 3) {
                        nextFrameAt = System.nanoTime();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "VoiceRoomMixer");
        mixerThread.setDaemon(true);
        mixerThread.start();
    }

    private void mixVoiceRoomFrames() {
        for (Map.Entry<String, Set<ClientHandler>> roomEntry : voiceRooms.entrySet()) {
            String group = roomEntry.getKey();
            int volumeGain = voiceChannelVolumeGain.getOrDefault(group, MIN_VOICE_VOLUME_GAIN);
            Set<ClientHandler> members = roomEntry.getValue();
            if (members == null || members.isEmpty()) {
                continue;
            }

            Map<ClientHandler, byte[]> frames = new HashMap<>();
            Map<ClientHandler, BlockingQueue<byte[]>> senderQueues = voiceRoomAudioQueues.get(group);
            if (senderQueues != null) {
                for (Map.Entry<ClientHandler, BlockingQueue<byte[]>> entry : senderQueues.entrySet()) {
                    ClientHandler sender = entry.getKey();
                    if (!members.contains(sender)) {
                        senderQueues.remove(sender, entry.getValue());
                        continue;
                    }
                    byte[] frame = pollMixerFrame(entry.getValue());
                    if (frame != null) {
                        frames.put(sender, frame);
                    }
                }
            }

            BlockingQueue<byte[]> serverQueue = serverVoiceAudioQueues.get(group);
            byte[] serverFrame = pollMixerFrame(serverQueue);
            if (frames.isEmpty() && serverFrame == null) {
                continue;
            }
            byte[] roomMix = mixPcmFrames(frames, null, serverFrame, volumeGain);
            String roomMixMessage = roomMix == null ? null : "/live_group_audio|混音|"
                    + Base64.getEncoder().encodeToString(roomMix);
            for (ClientHandler receiver : members) {
                byte[] ownFrame = frames.get(receiver);
                if (ownFrame == null) {
                    if (roomMixMessage != null) {
                        receiver.sendMessage(roomMixMessage);
                    }
                    continue;
                }
                byte[] mixWithoutSelf = mixPcmFrames(frames, receiver, serverFrame, volumeGain);
                if (mixWithoutSelf != null) {
                    receiver.sendMessage("/live_group_audio|混音|"
                            + Base64.getEncoder().encodeToString(mixWithoutSelf));
                }
            }
            if (group.equals(serverVoiceGroup)) {
                byte[] serverPlayback = mixPcmFrames(frames, null, null, volumeGain);
                if (serverPlayback != null) {
                    offerAudioFrame(serverVoicePlaybackQueue, serverPlayback, GROUP_AUDIO_MAX_PLAYBACK_FRAMES);
                }
            }
        }
    }

    private byte[] pollMixerFrame(BlockingQueue<byte[]> queue) {
        if (queue == null) {
            return null;
        }
        while (queue.size() > GROUP_AUDIO_TARGET_BUFFER_FRAMES) {
            queue.poll();
        }
        return queue.poll();
    }

    private void offerAudioFrame(BlockingQueue<byte[]> queue, byte[] frame, int maxFrames) {
        if (queue == null || frame == null) {
            return;
        }
        while (queue.size() >= maxFrames) {
            queue.poll();
        }
        if (!queue.offer(frame)) {
            queue.poll();
            queue.offer(frame);
        }
    }

    private byte[] mixPcmFrames(Map<ClientHandler, byte[]> frames, ClientHandler excludedSender,
                                byte[] additionalFrame, int volumeGain) {
        int[] sums = new int[LIVE_AUDIO_CHUNK_BYTES / 2];
        boolean hasAudio = false;
        for (Map.Entry<ClientHandler, byte[]> entry : frames.entrySet()) {
            if (entry.getKey() == excludedSender) {
                continue;
            }
            byte[] frame = entry.getValue();
            if (frame == null || frame.length < 2) {
                continue;
            }
            hasAudio = true;
            int sampleCount = Math.min(sums.length, frame.length / 2);
            for (int i = 0; i < sampleCount; i++) {
                int low = frame[i * 2] & 0xff;
                int high = frame[i * 2 + 1] << 8;
                sums[i] += (short) (low | high);
            }
        }
        if (additionalFrame != null && additionalFrame.length >= 2) {
            hasAudio = true;
            int sampleCount = Math.min(sums.length, additionalFrame.length / 2);
            for (int i = 0; i < sampleCount; i++) {
                int low = additionalFrame[i * 2] & 0xff;
                int high = additionalFrame[i * 2 + 1] << 8;
                sums[i] += (short) (low | high);
            }
        }
        if (!hasAudio) {
            return null;
        }
        byte[] mixed = new byte[LIVE_AUDIO_CHUNK_BYTES];
        int clampedGain = Math.max(MIN_VOICE_VOLUME_GAIN,
                Math.min(MAX_VOICE_VOLUME_GAIN, volumeGain));
        for (int i = 0; i < sums.length; i++) {
            long amplifiedSample = (long) sums[i] * clampedGain;
            int sample = (int) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, amplifiedSample));
            mixed[i * 2] = (byte) (sample & 0xff);
            mixed[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return mixed;
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
        private final BlockingQueue<String> liveAudioOutboundQueue = new ArrayBlockingQueue<>(12);
        private volatile boolean handlerActive = true;
        private Thread liveAudioWriterThread;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            try {
                socket.setTcpNoDelay(true);
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
                startLiveAudioWriter();
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
                    if (isMutedForSending(message)) {
                        sendMessage("您已被服务器禁言，剩余" + getMuteRemainingText(nickname) + "，暂时无法发送内容");
                        continue;
                    }
                    
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
                            log("客户端 " + clientId + " 发送了格式错误的登录信息");
                            break;
                        }

                        if (isReservedServerUsername(account)) {
                            sendMessage("/login_result|failure: server 为服务器保留ID");
                            log("客户端 " + clientId + " 尝试使用服务器保留账户名: " + account);
                            break;
                        }

                        // 验证账户和密码
                        String correctPassword = accountPasswords.get(account);
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

                        if (isReservedServerUsername(username)) {
                            sendMessage("/login_result|failure: server 为服务器保留ID");
                            log("客户端 " + clientId + " 尝试使用服务器保留ID: " + username);
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
                        groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(this);
                        
                        // 添加到在线用户列表
                        addOnlineUser(nickname);
                        notifyMutedStatus(this);
                        
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
                            groups.computeIfAbsent(group, k -> new CopyOnWriteArrayList<>()).add(this);

                            log("客户端 " + clientId + " 加入群组: " + group);
                            refreshVoiceChannelPanel();

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
                            if (isReservedServerUsername(newNickname)) {
                                sendMessage("昵称 \"server\" 为服务器保留名称，无法使用");
                                log("客户端 " + clientId + " 尝试使用服务器保留名称: " + newNickname + "，连接被拒绝");
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
                            notifyMutedStatus(this);

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
                            sendMessage("/session_ready|success");
                        }
                    }
                    else if (message.equals("/ping")) {
                        // 心跳消息只用于保活，不广播到频道
                        continue;
                    }
                    // 加入当前文字频道对应的实时语音区
                    else if (message.equals("/live_group_join")) {
                        if (!versionChecked || group == null || !userHandlers.containsKey(nickname)) {
                            sendMessage("/live_voice_error|请先完成登录并加入频道");
                            continue;
                        }
                        if (!voiceChannelEnabled.getOrDefault(group, true)) {
                            sendMessage("/live_voice_error|该语音频道当前已关闭");
                            continue;
                        }
                        if (activeP2PVoicePeers.containsKey(nickname)
                                || pendingP2PVoiceRequests.containsKey(nickname)
                                || pendingP2PVoiceRequests.containsValue(nickname)) {
                            sendMessage("/live_voice_error|请先结束或处理私聊语音申请");
                            continue;
                        }
                        Set<ClientHandler> members = voiceRooms.computeIfAbsent(group, key -> ConcurrentHashMap.newKeySet());
                        members.add(this);
                        sendMessage("/live_group_joined|" + group + "|" + members.size());
                        broadcastVoiceRoomMemberCount(group);
                        log("用户 " + nickname + " 加入频道语音: " + group);
                    }
                    // 退出当前频道的实时语音区
                    else if (message.equals("/live_group_leave")) {
                        leaveVoiceRoom(this, true);
                    }
                    // 转发当前频道的实时语音块
                    else if (message.startsWith("/live_group_audio|")) {
                        if (!isVoiceRoomMember(this)) {
                            sendMessage("/live_voice_error|您尚未加入频道语音");
                            continue;
                        }
                        String audioData = message.substring(18);
                        if (audioData.isEmpty() || audioData.length() > MAX_LIVE_AUDIO_BASE64_LENGTH) {
                            continue;
                        }
                        try {
                            byte[] audioFrame = Base64.getDecoder().decode(audioData);
                            if (audioFrame.length == 0 || audioFrame.length > LIVE_AUDIO_CHUNK_BYTES) {
                                continue;
                            }
                            if (audioFrame.length != LIVE_AUDIO_CHUNK_BYTES) {
                                audioFrame = Arrays.copyOf(audioFrame, LIVE_AUDIO_CHUNK_BYTES);
                            }
                            Map<ClientHandler, BlockingQueue<byte[]>> senderQueues = voiceRoomAudioQueues
                                    .computeIfAbsent(group, key -> new ConcurrentHashMap<>());
                            BlockingQueue<byte[]> senderQueue = senderQueues.computeIfAbsent(this,
                                    key -> new ArrayBlockingQueue<>(GROUP_AUDIO_INPUT_QUEUE_CAPACITY));
                            offerAudioFrame(senderQueue, audioFrame, GROUP_AUDIO_INPUT_QUEUE_CAPACITY);
                        } catch (IllegalArgumentException ignored) {
                            // 忽略无效Base64音频帧
                        }
                    }
                    // 向私聊对象申请实时语音
                    else if (message.startsWith("/live_p2p_request|")) {
                        if (!versionChecked || !userHandlers.containsKey(nickname)) {
                            sendMessage("/live_voice_error|请先完成登录");
                            continue;
                        }
                        if (isVoiceRoomMember(this) || activeP2PVoicePeers.containsKey(nickname)
                                || pendingP2PVoiceRequests.containsKey(nickname)
                                || pendingP2PVoiceRequests.containsValue(nickname)) {
                            sendMessage("/live_voice_error|您当前已有语音会话或待处理申请");
                            continue;
                        }
                        String targetPassword = message.substring(18);
                        String targetUser = passwordToUser.get(targetPassword);
                        ClientHandler targetHandler = targetUser == null ? null : userHandlers.get(targetUser);
                        if (targetHandler == null || targetUser.equals(nickname)) {
                            sendMessage("/live_voice_error|用户不存在或已离线");
                            continue;
                        }
                        if (isVoiceRoomMember(targetHandler) || activeP2PVoicePeers.containsKey(targetUser)
                                || pendingP2PVoiceRequests.containsKey(targetUser)
                                || pendingP2PVoiceRequests.containsValue(targetUser)) {
                            sendMessage("/live_voice_error|对方当前正在通话或有待处理申请");
                            continue;
                        }
                        String senderPassword = userP2PPasswords.get(nickname);
                        if (senderPassword == null) {
                            sendMessage("/live_voice_error|系统未分配私聊密码");
                            continue;
                        }
                        pendingP2PVoiceRequests.put(targetUser, nickname);
                        pendingP2PVoiceRequestTimes.put(targetUser, System.currentTimeMillis());
                        targetHandler.sendMessage("/live_p2p_request|" + nickname + "|" + senderPassword);
                        sendMessage("/live_p2p_request_sent|" + targetUser);
                        log("私聊语音申请: " + nickname + " -> " + targetUser);
                    }
                    // 同意私聊语音申请
                    else if (message.startsWith("/live_p2p_accept|")) {
                        String requesterPassword = message.substring(17);
                        if (handleServerP2PVoiceAccept(this, requesterPassword)) {
                            continue;
                        }
                        String requester = passwordToUser.get(requesterPassword);
                        if (requester == null || !requester.equals(pendingP2PVoiceRequests.get(nickname))) {
                            sendMessage("/live_voice_error|语音申请已失效");
                            continue;
                        }
                        ClientHandler requesterHandler = userHandlers.get(requester);
                        if (requesterHandler == null || isVoiceRoomMember(this) || isVoiceRoomMember(requesterHandler)
                                || activeP2PVoicePeers.containsKey(nickname) || activeP2PVoicePeers.containsKey(requester)) {
                            pendingP2PVoiceRequests.remove(nickname, requester);
                            pendingP2PVoiceRequestTimes.remove(nickname);
                            sendMessage("/live_voice_error|双方当前无法建立语音通话");
                            if (requesterHandler != null) {
                                requesterHandler.sendMessage("/live_p2p_rejected|" + nickname + "|对方当前无法接听");
                            }
                            continue;
                        }
                        pendingP2PVoiceRequests.remove(nickname, requester);
                        pendingP2PVoiceRequestTimes.remove(nickname);
                        activeP2PVoicePeers.put(nickname, requester);
                        activeP2PVoicePeers.put(requester, nickname);
                        String myPassword = userP2PPasswords.get(nickname);
                        requesterHandler.sendMessage("/live_p2p_started|" + nickname + "|" + myPassword);
                        sendMessage("/live_p2p_started|" + requester + "|" + requesterPassword);
                        log("私聊语音已建立: " + nickname + " <-> " + requester);
                    }
                    // 拒绝私聊语音申请
                    else if (message.startsWith("/live_p2p_reject|")) {
                        String requesterPassword = message.substring(17);
                        if (handleServerP2PVoiceReject(this, requesterPassword)) {
                            continue;
                        }
                        String requester = passwordToUser.get(requesterPassword);
                        if (requester != null && pendingP2PVoiceRequests.remove(nickname, requester)) {
                            pendingP2PVoiceRequestTimes.remove(nickname);
                            ClientHandler requesterHandler = userHandlers.get(requester);
                            if (requesterHandler != null) {
                                requesterHandler.sendMessage("/live_p2p_rejected|" + nickname + "|对方已拒绝");
                            }
                            sendMessage("/live_p2p_rejected_ack|" + requester);
                        }
                    }
                    // 转发已建立私聊通话的实时语音块
                    else if (message.startsWith("/live_p2p_audio|")) {
                        if (handleServerP2PVoiceAudio(nickname, message.substring(16))) {
                            continue;
                        }
                        String peer = activeP2PVoicePeers.get(nickname);
                        if (peer == null) {
                            continue;
                        }
                        String audioData = message.substring(16);
                        if (audioData.isEmpty() || audioData.length() > MAX_LIVE_AUDIO_BASE64_LENGTH) {
                            continue;
                        }
                        ClientHandler peerHandler = userHandlers.get(peer);
                        if (peerHandler != null) {
                            peerHandler.sendMessage("/live_p2p_audio|" + nickname + "|" + audioData);
                        } else {
                            endP2PVoiceCall(nickname);
                        }
                    }
                    // 挂断私聊语音
                    else if (message.equals("/live_p2p_end")) {
                        if (nickname.equals(serverP2PVoicePeer)) {
                            stopServerP2PVoiceSession(false);
                            continue;
                        }
                        endP2PVoiceCall(nickname);
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

        private boolean isMutedForSending(String message) {
            if (!isUserMuted(nickname)) {
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
        private void cleanupClient() {
            try {
                handlerActive = false;
                if (liveAudioWriterThread != null) {
                    liveAudioWriterThread.interrupt();
                }
                liveAudioOutboundQueue.clear();
                leaveVoiceRoom(this, false);
                endP2PVoiceCall(nickname);
                clearPendingP2PVoiceRequests(nickname);
                handleServerP2PClientUnavailable(nickname);

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
                refreshOnlineUsersPanel();
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
            synchronized (out) {
                out.println(msg);
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
