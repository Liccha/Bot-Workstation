package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Outbound-only relay. The desktop credential never leaves this computer. */
final class CloudMobileRelay implements AutoCloseable {
    interface Handler { RelayResponse execute(JSONObject payload) throws Exception; }
    static final class RelayResponse {
        final int status; final JSONObject body;
        RelayResponse(int status, JSONObject body) { this.status = status; this.body = body == null ? new JSONObject() : body; }
    }
    static final class Device {
        final String id; final String name; final String status; final String createdAt;
        Device(JSONObject value) {
            id = value.optString("id"); name = value.optString("name", "手机设备");
            status = value.optString("status", "active"); createdAt = value.optString("createdAt");
        }
        @Override public String toString() { return name + " · " + (createdAt.length() >= 10 ? createdAt.substring(0, 10) : createdAt); }
    }

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final long HEARTBEAT_INTERVAL_MS = 90_000L;
    private static final long EMPTY_POLL_INTERVAL_MS = 10_000L;
    private final URI endpoint;
    private final String desktopToken;
    private final String workstationName;
    private final LogBus log;
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService worker;
    private Handler handler;
    private volatile String lastError = "";
    private volatile String lastHeartbeatError = "";
    private volatile long nextHeartbeatAt;

    private CloudMobileRelay(URI endpoint, String desktopToken, String workstationName, LogBus log) {
        this.endpoint = validateEndpoint(endpoint); this.desktopToken = desktopToken;
        this.workstationName = workstationName; this.log = log;
    }

    static CloudMobileRelay fromSongBot(BotPaths paths, LogBus log) throws IOException {
        if (Boolean.getBoolean("botstation.mobile.relay.disabled")) return null;
        Path file = paths.songBot.resolve("data").resolve("cloud-announcement.properties");
        if (!Files.isRegularFile(file)) return null;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        String api = properties.getProperty("api", "").trim();
        String token = properties.getProperty("desktopToken", "").trim();
        if (api.isEmpty() || token.isEmpty()) return null;
        URI announcement = URI.create(api);
        URI relay = URI.create(announcement.getScheme() + "://" + announcement.getRawAuthority() + "/api/mobile-relay");
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { host = "desktop"; }
        return new CloudMobileRelay(relay, token, "Bot工作站-" + host.replaceAll("[^A-Za-z0-9_.-]", "_"), log);
    }

    String endpoint() { return endpoint.toString(); }
    boolean isRunning() { return running.get(); }

    JSONObject registerDevice(String name) throws IOException {
        return request("POST", "register-device", new JSONObject().put("name", name == null || name.isBlank() ? "手机设备" : name), true);
    }

    List<Device> devices() throws IOException {
        JSONArray items = request("GET", "devices", null, true).optJSONArray("items");
        List<Device> result = new ArrayList<>();
        if (items != null) for (int index = 0; index < items.length(); index++) result.add(new Device(items.getJSONObject(index)));
        return result;
    }

    void revoke(String id) throws IOException {
        request("POST", "revoke-device", new JSONObject().put("id", id), true);
    }

    synchronized void start(Handler requestHandler) {
        if (running.get() || requestHandler == null) return;
        handler = requestHandler; running.set(true);
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "bot-mobile-cloud-relay"); thread.setDaemon(true); return thread;
        });
        worker.submit(this::pollLoop);
        log.info("手机端", "跨网络设备中继已启用");
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                publishHeartbeatIfDue();
                JSONArray items = request("GET", "desktop-poll", null, true).optJSONArray("items");
                if (!lastError.isEmpty()) { lastError = ""; log.info("手机端", "跨网络设备中继已恢复"); }
                if (items != null) for (int index = 0; index < items.length() && running.get(); index++) execute(items.getJSONObject(index));
                pause(items == null || items.isEmpty() ? EMPTY_POLL_INTERVAL_MS : 150);
            } catch (Exception error) {
                String message = String.valueOf(error.getMessage());
                if (!message.equals(lastError)) { lastError = message; log.warn("手机端", "跨网络中继暂不可用：" + message); }
                pause(5000);
            }
        }
    }

    private void execute(JSONObject item) {
        int status = 500; JSONObject result;
        try {
            RelayResponse response = handler.execute(item.getJSONObject("payload"));
            status = response.status; result = response.body;
        } catch (Exception error) {
            result = new JSONObject().put("error", safeMessage(error));
        }
        try {
            request("POST", "desktop-complete", new JSONObject()
                .put("id", item.optString("id")).put("claimToken", item.optString("claimToken"))
                .put("status", status).put("body", result), true);
        } catch (Exception error) {
            log.warn("手机端", "跨网络操作结果回传失败：" + safeMessage(error));
        }
        nextHeartbeatAt = 0L;
    }

    private void publishHeartbeatIfDue() {
        long current = System.currentTimeMillis();
        if (current < nextHeartbeatAt || handler == null) return;
        nextHeartbeatAt = current + HEARTBEAT_INTERVAL_MS;
        try {
            RelayResponse status = handler.execute(new JSONObject().put("method", "GET").put("path", "/api/status"));
            if (status.status < 200 || status.status >= 300) throw new IOException("本机状态不可用");
            request("POST", "desktop-heartbeat", status.body, true);
            if (!lastHeartbeatError.isEmpty()) {
                lastHeartbeatError = "";
                log.info("手机端", "电脑在线状态已恢复同步");
            }
        } catch (Exception error) {
            String message = safeMessage(error);
            if (!message.equals(lastHeartbeatError)) {
                lastHeartbeatError = message;
                log.warn("手机端", "电脑在线状态暂未同步：" + message);
            }
        }
    }

    private JSONObject request(String method, String action, JSONObject payload, boolean desktop) throws IOException {
        URL url = URI.create(endpoint + "?action=" + action).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
        if (desktop) {
            connection.setRequestProperty("Authorization", "Desktop " + desktopToken);
            connection.setRequestProperty("X-Admin-Device", workstationName);
        }
        if (payload != null) {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : readLimited(stream);
        connection.disconnect();
        JSONObject value;
        try { value = text.isBlank() ? new JSONObject() : new JSONObject(text); }
        catch (Exception error) { throw new IOException("云端中继返回了无效数据", error); }
        if (status < 200 || status >= 300) throw new IOException("云端中继请求失败（HTTP " + status + "）");
        return value;
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0;
            for (int count; (count = source.read(buffer)) >= 0; ) {
                total += count; if (total > MAX_RESPONSE_BYTES) throw new IOException("云端中继响应过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static URI validateEndpoint(URI value) {
        if (value == null || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null
            || !"/api/mobile-relay".equals(value.getPath())) throw new IllegalArgumentException("云端中继地址无效");
        String scheme = String.valueOf(value.getScheme()).toLowerCase(Locale.ROOT);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        boolean production = "editor.teacharm.moe".equals(host) || "bot-editor.vercel.app".equals(host);
        boolean local = "http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host));
        if (!("https".equals(scheme) && production) && !local) throw new IllegalArgumentException("云端中继必须使用受信任的 HTTPS 域名");
        return value;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "操作失败" : String.valueOf(error.getMessage());
        return value.isBlank() ? "操作失败" : value.replaceAll("[\\r\\n]", " ").substring(0, Math.min(240, value.length()));
    }
    private static void pause(long millis) { try { Thread.sleep(millis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }

    @Override public synchronized void close() {
        running.set(false);
        if (worker != null) { worker.shutdownNow(); worker = null; }
    }
}
