package com.botstation.security;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Uses the existing desktop service token to ask the cloud whether Vercel's
 * observed client IP is trusted. The client never sends an IP or password.
 */
final class AdminIpTrustClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final URI api;
    private final String token;
    private final String device;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private AdminIpTrustClient(URI api, String token, String device, int connectTimeoutMs, int readTimeoutMs) {
        this.api = validateEndpoint(api);
        this.token = token;
        this.device = device;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    static AdminIpTrustClient fromSongBot(Path songBotDirectory) throws IOException {
        Path file = songBotDirectory.resolve("data").resolve("cloud-announcement.properties");
        if (!Files.isRegularFile(file)) throw new IOException("未找到云公告配置");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!"cloud".equalsIgnoreCase(properties.getProperty("backend", "").trim())) {
            throw new IOException("云公告模式尚未启用");
        }
        String api = properties.getProperty("api", "").trim();
        String token = properties.getProperty("desktopToken", "").trim();
        if (api.isEmpty() || token.isEmpty()) throw new IOException("云公告地址或桌面令牌缺失");
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { host = "desktop"; }
        String device = "bot-workstation-" + host.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new AdminIpTrustClient(URI.create(api), token, device, 2500, 4000);
    }

    static AdminIpTrustClient forTest(URI api, String token) {
        return new AdminIpTrustClient(api, token, "bot-workstation-test", 1000, 1000);
    }

    boolean isTrusted() throws IOException {
        return request("GET", "desktop-ip-check").optBoolean("trusted", false);
    }

    void grant() throws IOException {
        if (!request("POST", "desktop-ip-grant").optBoolean("trusted", false)) {
            throw new IOException("云端没有确认可信 IP");
        }
    }

    private JSONObject request(String method, String action) throws IOException {
        URL endpoint = URI.create(api.toString() + (api.getRawQuery() == null ? "?" : "&") + "action=" + action).toURL();
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Desktop " + token);
        connection.setRequestProperty("X-Admin-Device", device);
        if ("POST".equals(method)) {
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : readLimited(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("可信 IP 服务请求失败（HTTP " + status + "）");
        try { return new JSONObject(response); }
        catch (Exception error) { throw new IOException("可信 IP 服务返回了无效数据", error); }
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            for (int read; (read = source.read(buffer)) >= 0; ) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("可信 IP 服务响应过大");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static URI validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("云公告地址无效");
        }
        String scheme = String.valueOf(endpoint.getScheme()).toLowerCase(Locale.ROOT);
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        boolean productionHost = "editor.teacharm.moe".equals(host) || "bot-editor.vercel.app".equals(host);
        if (!("https".equals(scheme) && productionHost) && !("http".equals(scheme) && loopback)) {
            throw new IllegalArgumentException("云公告地址必须使用受信任的 HTTPS 域名");
        }
        return endpoint;
    }
}
