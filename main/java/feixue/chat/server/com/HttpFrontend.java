package feixue.chat.server.com;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

// 网页前端功能：TCP 连接分流、HTTP/HTTPS 响应、WebSocket 握手与升级、SSL 监听、网页会话超时
class HttpFrontend {
    private final ChatServer server;

    HttpFrontend(ChatServer server) {
        this.server = server;
    }

    SSLServerSocket createSSLServerSocket(int port) throws Exception {
        Path certificatePath = server.config.resolveConfigPath(server.sslCertificateFile);
        Path privateKeyPath = server.config.resolveConfigPath(server.sslPrivateKeyFile);
        if (!Files.exists(certificatePath) || !Files.exists(privateKeyPath)) {
            throw new FileNotFoundException("证书或私钥不存在: " + certificatePath + ", " + privateKeyPath);
        }
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(certificatePath)) {
            certificate = (X509Certificate) certificateFactory.generateCertificate(input);
        }
        PrivateKey privateKey = readPrivateKey(privateKeyPath);
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("cloudflare-origin", privateKey, new char[0],
                new java.security.cert.Certificate[] { certificate });
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        server.sslContext = context;
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port);
        socket.setNeedClientAuth(false);
        socket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        return socket;
    }

    PrivateKey readPrivateKey(Path path) throws Exception {
        String pem = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    void routeIncomingConnection(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            input.mark(8);
            byte[] prefix = new byte[4];
            int prefixLength = 0;
            while (prefixLength < prefix.length) {
                int count = input.read(prefix, prefixLength, prefix.length - prefixLength);
                if (count < 0) {
                    socket.close();
                    return;
                }
                prefixLength += count;
            }
            input.reset();
            if (prefix[0] == 'G' && prefix[1] == 'E' && prefix[2] == 'T' && prefix[3] == ' ') {
                handleHttpConnection(socket, input);
                return;
            }
            socket.setSoTimeout(0);
            new ClientHandler(server, socket, new RawLineTransport(socket, input), false);
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
        } catch (IOException e) {
            closeQuietly(socket);
            if (server.isRunning) {
                server.log("连接分流失败: " + e.getMessage());
            }
        }
    }

    void handleHttpConnection(Socket socket, BufferedInputStream input) throws IOException {
        String requestLine = readHttpLine(input);
        if (requestLine == null) {
            socket.close();
            return;
        }
        String[] requestParts = requestLine.split(" ", 3);
        if (requestParts.length != 3 || !"GET".equals(requestParts[0])) {
            sendHttpResponse(socket, "405 Method Not Allowed", "text/plain; charset=utf-8",
                    "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; count < 100; count++) {
            String line = readHttpLine(input);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        if (!server.webAccessEnabled) {
            sendHttpResponse(socket, "503 Service Unavailable", "text/html; charset=utf-8",
                    ("<!doctype html><meta charset=\"utf-8\"><title>网页端已关闭</title>"
                            + "<body style=\"font-family:sans-serif;padding:40px\"><h1>网页端已关闭</h1>"
                            + "<p>请联系服务器管理员在服务器的“网页端”页面中启动。</p></body>")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }

        String target = requestParts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        boolean wantsWebSocket = "websocket".equalsIgnoreCase(headers.get("upgrade"))
                && headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade");
        if (!(socket instanceof SSLSocket)) {
            String host = headers.getOrDefault("host", "fangfang.dpdns.org");
            String redirect = "https://" + host.split(":", 2)[0] + ":" + server.sslPort + path;
            sendHttpRedirect(socket, redirect);
            return;
        }
        if ("/ws".equals(path) && wantsWebSocket) {
            if (!isAllowedWebSocketOrigin(headers)) {
                sendHttpResponse(socket, "403 Forbidden", "text/plain; charset=utf-8",
                        "Forbidden origin".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String key = headers.get("sec-websocket-key");
            if (key == null || key.trim().isEmpty() || !"13".equals(headers.get("sec-websocket-version"))) {
                sendHttpResponse(socket, "400 Bad Request", "text/plain; charset=utf-8",
                        "Invalid WebSocket handshake".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String accept = createWebSocketAccept(key.trim());
            OutputStream output = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            socket.setSoTimeout(0);
            new ClientHandler(server, socket, new WebSocketTransport(socket, input, output), true);
            return;
        }

        if ("/favicon.ico".equals(path)) {
            sendHttpResponse(socket, "204 No Content", "image/x-icon", new byte[0]);
            return;
        }
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendHttpResponse(socket, "404 Not Found", "text/plain; charset=utf-8",
                    "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendHttpResponse(socket, "200 OK", "text/html; charset=utf-8", loadWebClientPage());
    }

    boolean isAllowedWebSocketOrigin(Map<String, String> headers) {
        String origin = headers.get("origin");
        String host = headers.get("host");
        if (origin == null) {
            return true;
        }
        if (host == null) {
            return false;
        }
        return origin.equalsIgnoreCase("http://" + host) || origin.equalsIgnoreCase("https://" + host);
    }

    String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawCarriageReturn = false;
        while (line.size() <= ChatServer.MAX_HTTP_LINE_BYTES) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (sawCarriageReturn) {
                if (value == '\n') {
                    return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                line.write('\r');
                sawCarriageReturn = false;
            }
            if (value == '\r') {
                sawCarriageReturn = true;
            } else if (value == '\n') {
                return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            } else {
                line.write(value);
            }
        }
        throw new IOException("HTTP header line is too long");
    }

    String createWebSocketAccept(String key) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] value = digest.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(value);
        } catch (Exception e) {
            throw new IOException("WebSocket handshake failed", e);
        }
    }

    byte[] loadWebClientPage() throws IOException {
        try (InputStream resource = ChatServer.class.getResourceAsStream(ChatServer.WEB_CLIENT_RESOURCE)) {
            if (resource != null) {
                return readAllBytes(resource, 4 * 1024 * 1024);
            }
        }
        Path pagePath = server.config.resolveConfigPath("web-client.html");
        if (Files.exists(pagePath)) {
            return Files.readAllBytes(pagePath);
        }
        return ("<!doctype html><meta charset=\"utf-8\"><title>网页端资源缺失</title>"
                + "<h1>网页端资源缺失</h1>").getBytes(StandardCharsets.UTF_8);
    }

    byte[] readAllBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) {
                throw new IOException("Resource is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    void sendHttpResponse(Socket socket, String status, String contentType, byte[] body) throws IOException {
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Permissions-Policy: microphone=(self)\r\n"
                + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' blob:; worker-src blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self' ws: wss:\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
        socket.close();
    }

    void sendHttpRedirect(Socket socket, String location) throws IOException {
        String response = "HTTP/1.1 308 Permanent Redirect\r\n"
                + "Location: " + location + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        socket.close();
    }

    void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void startWebIdleTimeoutTask() {
        final ServerSocket activeServerSocket = server.serverSocket;
        Thread timeoutThread = new Thread(() -> {
            while (server.isRunning && server.serverSocket == activeServerSocket
                    && activeServerSocket != null && !activeServerSocket.isClosed()) {
                try {
                    Thread.sleep(30000);
                    long now = System.currentTimeMillis();
                    for (ClientHandler client : new ArrayList<>(server.webClientHandlers)) {
                        if (now - client.lastWebUserActivity > ChatServer.WEB_IDLE_TIMEOUT) {
                            client.sendMessage("/web_idle_timeout|已超过一小时无操作，连接已断开");
                            client.closeConnection();
                            server.log("网页用户 " + client.nickname + " 一小时无操作，已断开");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (server.isRunning) {
                        server.log("网页会话超时检查失败: " + e.getMessage());
                    }
                }
            }
        }, "WebIdleTimeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();
    }

    String encodeWebValue(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    String decodeWebValue(String value) throws IOException {
        if (value == null || value.length() > 1024) {
            throw new IOException("Invalid encoded value");
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid encoded value", e);
        }
    }

    void sendWebChannelList(ClientHandler client) {
        client.sendMessage("/web_channel|" + encodeWebValue("公开频道") + "|"
                + encodeWebValue(ChatServer.PUBLIC_CHANNEL_GROUP) + "|0");
        for (Map.Entry<String, String> entry : server.accountPasswords.entrySet()) {
            String group = server.accountGroups.get(entry.getKey());
            if (group != null) {
                client.sendMessage("/web_channel|" + encodeWebValue(entry.getKey()) + "|"
                        + encodeWebValue(group) + "|" + (entry.getValue().isEmpty() ? "0" : "1"));
            }
        }
        client.sendMessage("/web_channels_end");
    }

    boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    void startSslListenerIfConfigured() {
        if (!server.sslEnabled) {
            server.log("HTTPS/WSS 未启用（ssl-config.json enabled=false）");
            return;
        }
        try {
            server.sslServerSocket = createSSLServerSocket(server.sslPort);
            server.log("HTTPS/WSS 已启动，监听端口: " + server.sslPort);
            Thread listener = new Thread(() -> {
                while (server.isRunning && server.sslServerSocket != null && !server.sslServerSocket.isClosed()) {
                    try {
                        Socket clientSocket = server.sslServerSocket.accept();
                        Thread routerThread = new Thread(() -> routeIncomingConnection(clientSocket),
                                "SSLConnectionRouter-" + clientSocket.getRemoteSocketAddress());
                        routerThread.setDaemon(true);
                        routerThread.start();
                    } catch (IOException e) {
                        if (server.isRunning) server.log("HTTPS/WSS 接收连接失败: " + e.getMessage());
                        break;
                    }
                }
            }, "SSLListener-" + server.sslPort);
            listener.setDaemon(true);
            listener.start();
        } catch (Exception e) {
            server.sslEnabled = false;
            server.log("HTTPS/WSS 启动失败，普通客户端仍可使用: " + e.getMessage());
        }
    }
}
