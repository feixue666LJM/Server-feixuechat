import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class DeepSeekService {
    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String API_KEY = "sk-1v2b1v4114a1wqgf114514qfdklqjg114514dkgwaw";
    private static final String MODEL = "deepseek-v4-flash";
    private static final int MAX_TOKENS = 3500;

    private static final long COOLDOWN_MS = 30_000L; // 30秒冷却
    private static final int MAX_CHARS = 3500; // 最大字符限制
    private static final int MAX_RESPONSE_BYTES = 420; // 回答最大字节限制
    private static final int MAX_LINE_CHARS = 40; // 回答每行最大字符数，超出自动换行
    
    private Map<String, Long> userLastAskTime = new HashMap<>(); // 用户ID -> 最后提问时间
    private List<String> blockedWords = new ArrayList<>();
    
    public DeepSeekService() {
        loadBlockedWords();
    }
    
    /**
     * 提问方法，包含冷却、长度、屏蔽词检查
     * @param userId 用户标识，用于冷却检查
     * @param question 问题内容
     * @return 回答字符串，如果检查失败返回错误信息（以"ERROR:"开头）
     */
    public String ask(String userId, String question) {
        // 检查1: 30秒冷却限制
        long now = System.currentTimeMillis();
        Long lastTime = userLastAskTime.get(userId);
        if (lastTime != null && now - lastTime < COOLDOWN_MS) {
            long remaining = (COOLDOWN_MS - (now - lastTime)) / 1000;
            return "ERROR: 请勿频繁提问，请等待 " + remaining + " 秒后再试。";
        }
        
        // 检查2: 字符长度限制
        if (question.length() > MAX_CHARS) {
            return "ERROR: 输入内容超出 " + MAX_CHARS + " 字符限制，当前 " + question.length() + " 字符，请精简后重试。";
        }
        
        // 检查3: 屏蔽词检查
        String foundBlocked = containsBlockedWord(question);
        if (foundBlocked != null) {
            return "ERROR: 您的问题包含敏感词「" + foundBlocked + "」，请修改后重试。";
        }
        
        // 更新最后提问时间
        userLastAskTime.put(userId, now);
        
        try {
            String reply = callDeepSeekAPI(question);
            reply = wrapText(reply, MAX_LINE_CHARS);
            return truncateToBytes(reply, MAX_RESPONSE_BYTES);
        } catch (Exception e) {
            return "ERROR: 请求失败: " + e.getMessage();
        }
    }
    
    /**
     * 调用DeepSeek API
     */
    private String callDeepSeekAPI(String question) throws Exception {
        URL url = new URL(DEEPSEEK_API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        
        // 构建请求体
        String jsonBody = "{\"model\":\"" + MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" +
                escapeJson(question) + "\"}],\"stream\":false,\"max_tokens\":" + MAX_TOKENS + "}";
        
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            // 读取错误流
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
                throw new IOException("API返回错误码 " + responseCode + ": " + errorResponse.toString());
            }
        }
        
        // 读取响应
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }
        
        // 解析JSON响应，提取content字段
        return extractContentFromResponse(response.toString());
    }
    
    private String extractContentFromResponse(String jsonResponse) {
        try {
            // 查找 "content":" 后面跟的内容
            String contentKey = "\"content\":\"";
            int contentStart = jsonResponse.indexOf(contentKey);
            if (contentStart == -1) {
                return "无法解析AI返回内容";
            }
            contentStart += contentKey.length();
            
            StringBuilder content = new StringBuilder();
            for (int i = contentStart; i < jsonResponse.length(); i++) {
                char c = jsonResponse.charAt(i);
                if (c == '\\' && i + 1 < jsonResponse.length()) {
                    char next = jsonResponse.charAt(i + 1);
                    if (next == 'n') {
                        content.append('\n');
                        i++;
                    } else if (next == 't') {
                        content.append('\t');
                        i++;
                    } else if (next == '\"') {
                        content.append('"');
                        i++;
                    } else if (next == '\\') {
                        content.append('\\');
                        i++;
                    } else {
                        content.append(c);
                    }
                } else if (c == '"') {
                    break;
                } else {
                    content.append(c);
                }
            }
            return content.toString().trim();
        } catch (Exception e) {
            return "解析响应失败: " + e.getMessage();
        }
    }
    
    /**
     * 从 pbc.txt 加载屏蔽词列表
     */
    private void loadBlockedWords() {
        Path banPath = Paths.get("pbc.txt");
        // 也尝试从项目根目录查找
        if (!Files.exists(banPath)) {
            banPath = Paths.get("../pbc.txt");
        }
        if (!Files.exists(banPath)) {
            banPath = Paths.get(System.getProperty("user.dir"), "pbc.txt");
        }
        
        if (Files.exists(banPath)) {
            try {
                List<String> lines = Files.readAllLines(banPath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        blockedWords.add(trimmed);
                    }
                }
                System.out.println("DeepSeekService: 已加载 " + blockedWords.size() + " 个屏蔽词。");
            } catch (IOException e) {
                System.err.println("DeepSeekService: 加载屏蔽词文件失败: " + e.getMessage());
            }
        } else {
            System.out.println("DeepSeekService: 未找到屏蔽词文件 (pbc.txt)，将不进行屏蔽词过滤。");
        }
    }
    
    /**
     * 检查文本是否包含屏蔽词
     * @return 返回第一个匹配到的屏蔽词，无匹配返回null
     */
    private String containsBlockedWord(String text) {
        for (String word : blockedWords) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }
    
    /**
     * 将字符串截断到指定字节数（UTF-8编码）
     */
    private String truncateToBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        // 逐字符往回减，直到字节数符合要求
        char[] chars = text.toCharArray();
        int currentBytes = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : chars) {
            int charBytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + charBytes > maxBytes) {
                break;
            }
            sb.append(c);
            currentBytes += charBytes;
        }
        return sb.toString();
    }

    /**
     * 将文本按指定字符数自动换行（保留原有换行）
     */
    private String wrapText(String text, int maxLineChars) {
        StringBuilder result = new StringBuilder();
        int len = text.length();
        int start = 0;
        while (start < len) {
            // 查找下一个换行符
            int nextNewline = text.indexOf('\n', start);
            if (nextNewline != -1 && nextNewline - start <= maxLineChars) {
                // 如果在限定字符内遇到换行符，直接追加到此换行符
                result.append(text, start, nextNewline + 1);
                start = nextNewline + 1;
            } else {
                // 取最多 maxLineChars 个字符
                int end = Math.min(start + maxLineChars, len);
                // 检查截取范围内是否有换行符
                int newlineInRange = text.indexOf('\n', start);
                if (newlineInRange != -1 && newlineInRange < end) {
                    end = newlineInRange + 1;
                }
                result.append(text, start, end);
                if (end < len && text.charAt(end - 1) != '\n') {
                    result.append('\n');
                }
                start = end;
            }
        }
        return result.toString();
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
