package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;
import com.botstation.core.UpdateService;
import com.botstation.features.MobileDataService;
import com.botstation.features.OperationsSettings;
import com.botstation.features.SongAssetService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Pair-code protected LAN companion. No cloud credential is ever sent to the mobile client. */
public final class MobileControlServer implements AutoCloseable {
    public static final int PORT = 8098;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TOKEN_SECONDS = 30L * 24 * 60 * 60;
    private final BotPaths paths;
    private final LogBus log;
    private final ProcessSupervisor services;
    private final UpdateService updates;
    private final Consumer<String> openModule;
    private final MobileDataService data;
    private final OperationsSettings operations;
    private final SongAssetService songAssets;
    private final CloudMobileRelay relay;
    private final Map<String, Deque<Long>> pairAttempts = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;
    private byte[] secret;
    private String pairCode;
    private final AtomicBoolean updating = new AtomicBoolean();
    private final int port = Integer.getInteger("botstation.mobile.port", PORT);

    public MobileControlServer(BotPaths paths, LogBus log, ProcessSupervisor services, Consumer<String> openModule) {
        this(paths, log, services, new UpdateService(paths, log), openModule);
    }

    public MobileControlServer(BotPaths paths, LogBus log, ProcessSupervisor services,
                               UpdateService updates, Consumer<String> openModule) {
        this.paths = paths; this.log = log; this.services = services; this.openModule = openModule;
        this.updates = updates;
        this.data = new MobileDataService(paths);
        this.operations = new OperationsSettings(paths.songBot);
        this.songAssets = new SongAssetService(paths, log);
        CloudMobileRelay configured = null;
        try {
            configured = CloudMobileRelay.fromSongBot(paths, log);
        } catch (Exception error) {
            log.warn("手机端", "跨网络设备服务配置无效：" + safeMessage(error));
        }
        this.relay = configured;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        secret = loadOrCreateSecret();
        pairCode = String.format("%06d", RANDOM.nextInt(1_000_000));
        String bindAddress = System.getProperty("botstation.mobile.bind", "0.0.0.0");
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 32);
        server.createContext("/api/ping", this::ping);
        server.createContext("/api/pair", this::pair);
        server.createContext("/api/status", this::status);
        server.createContext("/api/update", this::update);
        server.createContext("/api/action", this::action);
        server.createContext("/api/songs", this::songs);
        server.createContext("/api/song", this::song);
        server.createContext("/api/song-asset", this::songAsset);
        server.createContext("/api/stable", this::stable);
        server.createContext("/", this::staticFile);
        executor = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "bot-mobile-control"); thread.setDaemon(true); return thread;
        });
        server.setExecutor(executor); server.start();
        if (relay != null) relay.start(this::executeRelay);
        log.info("手机端", "局域网控制已启用，端口 " + port);
    }

    public synchronized boolean isRunning() { return server != null; }
    public synchronized String pairCode() { return pairCode == null ? "------" : pairCode; }
    public String localUrl() {
        String bindAddress = System.getProperty("botstation.mobile.bind", "0.0.0.0");
        return "http://" + ("127.0.0.1".equals(bindAddress) ? bindAddress : localAddress()) + ":" + port + "/";
    }

    private void ping(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 GET")); return; }
        respond(exchange, 200, new JSONObject().put("ok", true).put("pairing", pairCode != null).toString());
    }

    private void pair(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 POST")); return; }
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!allowPairAttempt(ip)) {
            log.warn("手机端", "已拒绝来自 " + ip + " 的频繁配对请求");
            respond(exchange, 429, jsonError("尝试过于频繁，请十分钟后再试")); return;
        }
        JSONObject input = bodyJson(exchange);
        String supplied = input.optString("code", "").replaceAll("[^0-9]", "");
        if (pairCode == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), pairCode.getBytes(StandardCharsets.UTF_8))) {
            log.warn("手机端", "来自 " + ip + " 的配对码不正确");
            respond(exchange, 401, jsonError("配对码不正确")); return;
        }
        JSONObject response = new JSONObject().put("token", issueToken()).put("expiresIn", TOKEN_SECONDS);
        if (relay != null) {
            try {
                JSONObject device = relay.registerDevice(input.optString("name", "手机设备"));
                response.put("remoteServer", relay.endpoint())
                    .put("remoteToken", device.getString("token"))
                    .put("deviceId", device.getString("id"));
            } catch (Exception error) {
                log.warn("手机端", "设备 " + ip + " 的云端账号创建失败：" + safeMessage(error));
                respond(exchange, 503, jsonError("跨网络设备账号暂时无法创建，请稍后重试")); return;
            }
        }
        log.info("手机端", "设备 " + ip + " 配对成功" + (relay == null ? "（仅局域网）" : "（已创建长期设备账号）"));
        respond(exchange, 200, response.toString());
    }

    public boolean cloudRelayConfigured() { return relay != null; }
    public java.util.List<JSONObject> cloudDevices() throws IOException {
        java.util.List<JSONObject> values = new java.util.ArrayList<>();
        if (relay == null) return values;
        for (CloudMobileRelay.Device device : relay.devices()) {
            values.add(new JSONObject().put("id", device.id).put("name", device.name)
                .put("status", device.status).put("createdAt", device.createdAt));
        }
        return values;
    }
    public void revokeCloudDevice(String id) throws IOException {
        if (relay == null) throw new IOException("跨网络设备服务未配置");
        relay.revoke(id);
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        JSONObject value = new JSONObject()
            .put("songBot", services.songBotState().name().toLowerCase())
            .put("napCat", services.napCatState().name().toLowerCase())
            .put("dailyAutomation", operations.dailyAutomationEnabled())
            .put("workstationOnline", true)
            .put("editor", paths.editorUrl);
        respond(exchange, 200, value.toString());
    }

    private void update(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        if (!"GET".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 GET")); return; }
        try {
            UpdateService.ReleaseInfo release = updates.check();
            respond(exchange, 200, release.toJson(updates.available(release)).put("updating", updating.get()).toString());
        } catch (Exception error) {
            respond(exchange, 503, jsonError("暂时无法检查更新"));
        }
    }

    private void action(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 POST")); return; }
        String action = bodyJson(exchange).optString("action", "");
        try {
            switch (action) {
                case "songbot.start": services.startSongBot(); break;
                case "songbot.stop": services.stopSongBot(); break;
                case "napcat.start": services.startNapCat(); break;
                case "napcat.stop": services.stopNapCat(); break;
                case "daily.automation.enable": operations.setDailyAutomationEnabled(true); break;
                case "daily.automation.disable": operations.setDailyAutomationEnabled(false); break;
                case "open.library": openModule.accept("library"); break;
                case "open.mcz": openModule.accept("mcz"); break;
                case "open.stable": openModule.accept("stable"); break;
                case "open.admin": openModule.accept("announcements"); break;
                case "open.operations": openModule.accept("tools"); break;
                case "update.install": startMobileUpdate(); break;
                default: respond(exchange, 400, jsonError("未知操作")); return;
            }
            log.info("手机端", "已执行 " + action);
            respond(exchange, 200, new JSONObject().put("ok", true).toString());
        } catch (Exception error) {
            log.error("手机端", action + " 失败：" + error.getMessage());
            respond(exchange, 500, jsonError(error.getMessage()));
        }
    }

    private void startMobileUpdate() throws Exception {
        if (!updating.compareAndSet(false, true)) throw new IOException("更新已经在进行中");
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(800);
                UpdateService.ReleaseInfo release = updates.check();
                if (!updates.available(release)) throw new IOException("当前已是最新版");
                updates.downloadAndLaunch(release);
                Thread.sleep(700);
                System.exit(0);
            } catch (Exception error) {
                updating.set(false);
                log.error("自动更新", "手机端更新失败：" + error.getMessage());
            }
        }, "bot-workstation-mobile-update");
        thread.setDaemon(true);
        thread.start();
    }

    private void songs(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        if (!"GET".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 GET")); return; }
        try {
            Map<String, String> query = query(exchange); int limit = parseInt(query.get("limit"), 100);
            int offset = parseInt(query.get("offset"), 0);
            respond(exchange, 200, data.songs(query.getOrDefault("q", ""), offset, limit).toString());
        } catch (Exception error) { respond(exchange, 500, jsonError(error.getMessage())); }
    }

    private void song(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 POST")); return; }
        try {
            JSONObject input = bodyJson(exchange); String id = input.optString("id", "").trim();
            if (id.isEmpty()) { respond(exchange, 400, jsonError("缺少歌曲 ID")); return; }
            data.updateSong(id, input.optJSONObject("values") == null ? new JSONObject() : input.getJSONObject("values"));
            log.info("手机端", "已更新歌曲 ID " + id);
            respond(exchange, 200, new JSONObject().put("ok", true).toString());
        } catch (Exception error) { respond(exchange, 400, jsonError(error.getMessage())); }
    }

    private void stable(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> query = query(exchange);
                respond(exchange, 200, data.stable(query.getOrDefault("q", ""), parseInt(query.get("offset"), 0), parseInt(query.get("limit"), 100)).toString());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                JSONObject input = bodyJson(exchange); String sid = input.optString("sid", "").trim();
                if (sid.isEmpty()) { respond(exchange, 400, jsonError("缺少 Stable SID")); return; }
                data.updateStable(sid, input.optJSONObject("values") == null ? new JSONObject() : input.getJSONObject("values"));
                log.info("手机端", "已更新 Stable SID " + sid);
                respond(exchange, 200, new JSONObject().put("ok", true).toString());
                return;
            }
            respond(exchange, 405, jsonError("仅支持 GET 或 POST"));
        } catch (Exception error) { respond(exchange, 400, jsonError(error.getMessage())); }
    }

    private void songAsset(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) { respond(exchange, 401, jsonError("请先配对")); return; }
        if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, jsonError("仅支持 POST")); return; }
        try {
            JSONObject input = bodyJson(exchange);
            String id = input.optString("id", "").trim(); String type = input.optString("type", "").trim();
            String name = input.optString("name", "").trim(); long size = input.optLong("size", -1);
            URI download = URI.create(input.optString("downloadUrl", ""));
            Path saved = songAssets.downloadAndPublish(id, type, download, name, size);
            respond(exchange, 200, new JSONObject().put("ok", true).put("path", saved.toString()).toString());
        } catch (Exception error) {
            log.error("歌曲资源", "手机端上传失败：" + safeMessage(error));
            respond(exchange, 400, jsonError(safeMessage(error)));
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (!path.matches("/[A-Za-z0-9._/-]+") || path.contains("..")) { respond(exchange, 404, ""); return; }
        String resource = path.equals("/icon.ico") ? "/app/icon.ico"
            : path.equals("/icon.png") ? "/app/icon.png" : "/mobile" + path;
        try (InputStream input = MobileControlServer.class.getResourceAsStream(resource)) {
            if (input == null) { respond(exchange, 404, ""); return; }
            byte[] bytes = readAll(input, 4 * 1024 * 1024);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(path));
            securityHeaders(headers);
            headers.set("Cache-Control", path.equals("/index.html") ? "no-cache" : "public, max-age=86400");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }

    private CloudMobileRelay.RelayResponse executeRelay(JSONObject payload) throws Exception {
        String method = payload.optString("method", "").toUpperCase(java.util.Locale.ROOT);
        String path = payload.optString("path", "");
        String key = method + " " + path;
        Set<String> allowed = Set.of("GET /api/status", "GET /api/update", "GET /api/songs", "GET /api/stable",
            "POST /api/song", "POST /api/stable", "POST /api/action", "POST /api/song-asset");
        if (!allowed.contains(key)) return new CloudMobileRelay.RelayResponse(400, new JSONObject().put("error", "未知操作"));
        JSONObject body = payload.optJSONObject("body");
        if ("POST /api/action".equals(key)) {
            String actionName = body == null ? "" : body.optString("action", "");
            if (!Set.of("songbot.start", "songbot.stop", "napcat.start", "napcat.stop", "update.install",
                "daily.automation.enable", "daily.automation.disable").contains(actionName))
                return new CloudMobileRelay.RelayResponse(400, new JSONObject().put("error", "未知操作"));
        }
        StringBuilder url = new StringBuilder("http://127.0.0.1:").append(port).append(path);
        JSONObject query = payload.optJSONObject("query");
        if (query != null && !query.isEmpty()) {
            boolean first = true;
            for (String name : query.keySet()) {
                String value = query.optString(name, "");
                url.append(first ? '?' : '&'); first = false;
                url.append(URLEncoder.encode(name, StandardCharsets.UTF_8.name())).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
            }
        }
        HttpURLConnection connection = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(3000); connection.setReadTimeout(30 * 60_000);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + issueToken());
        if ("POST".equals(method)) {
            byte[] bytes = (body == null ? new JSONObject() : body).toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : new String(readAll(stream, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        connection.disconnect();
        JSONObject response;
        try { response = text.isBlank() ? new JSONObject() : new JSONObject(text); }
        catch (Exception error) { response = new JSONObject().put("error", "工作站返回了无效数据"); status = 500; }
        return new CloudMobileRelay.RelayResponse(status, response);
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") && verifyToken(header.substring(7));
    }

    private boolean allowPairAttempt(String ip) {
        long now = System.currentTimeMillis(); long cutoff = now - 10 * 60_000L;
        Deque<Long> attempts = pairAttempts.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) attempts.removeFirst();
            if (attempts.size() >= 5) return false;
            attempts.addLast(now); return true;
        }
    }

    private String issueToken() {
        long expiry = Instant.now().getEpochSecond() + TOKEN_SECONDS;
        byte[] nonce = new byte[18]; RANDOM.nextBytes(nonce);
        String payload = expiry + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
    }

    private boolean verifyToken(String token) {
        try {
            String[] parts = token.split("\\."); if (parts.length != 3) return false;
            String payload = parts[0] + "." + parts[1];
            byte[] supplied = Base64.getUrlDecoder().decode(parts[2]);
            return Long.parseLong(parts[0]) >= Instant.now().getEpochSecond() && MessageDigest.isEqual(supplied, hmac(payload));
        } catch (Exception ignored) { return false; }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private byte[] loadOrCreateSecret() throws IOException {
        Path file = paths.config().resolve("mobile-control.key"); Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            byte[] existing = Files.readAllBytes(file); if (existing.length == 32) return existing;
        }
        byte[] value = new byte[32]; RANDOM.nextBytes(value);
        Files.write(file, value); return value;
    }

    private static JSONObject bodyJson(HttpExchange exchange) throws IOException {
        return new JSONObject(new String(readAll(exchange.getRequestBody(), 64 * 1024), StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(HttpExchange exchange) throws IOException {
        Map<String, String> result = new java.util.LinkedHashMap<>(); String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            String[] values = pair.split("=", 2);
            result.put(java.net.URLDecoder.decode(values[0], "UTF-8"),
                java.net.URLDecoder.decode(values.length > 1 ? values[1] : "", "UTF-8"));
        }
        return result;
    }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read; int total = 0;
        while ((read = input.read(buffer)) >= 0) { total += read; if (total > limit) throw new IOException("请求过大"); output.write(buffer, 0, read); }
        return output.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8"); securityHeaders(headers);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String jsonError(String message) { return new JSONObject().put("error", message == null ? "操作失败" : message).toString(); }
    private static String safeMessage(Throwable error) {
        String value = error == null ? "操作失败" : String.valueOf(error.getMessage());
        value = value.replaceAll("[\\r\\n]", " ");
        return value.isBlank() ? "操作失败" : value.substring(0, Math.min(240, value.length()));
    }
    private static void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff"); headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
    }
    private static String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "text/html; charset=utf-8";
    }

    private static String localAddress() {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                String adapter = (network.getName() + " " + network.getDisplayName()).toLowerCase();
                boolean tunnel = network.isVirtual() || adapter.contains("tailscale") || adapter.contains("vpn")
                    || adapter.contains("tunnel") || adapter.contains("virtual") || adapter.contains("loopback");
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    int score = ip.startsWith("192.168.") ? 120 : ip.startsWith("10.") ? 100 : 80;
                    if (tunnel) score -= 90;
                    if (score > bestScore) { best = ip; bestScore = score; }
                }
            }
        } catch (Exception ignored) {}
        return best == null ? "127.0.0.1" : best;
    }

    @Override public synchronized void close() {
        if (relay != null) relay.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        server = null; executor = null; pairCode = null;
        log.info("手机端", "局域网控制已停用");
    }
}
