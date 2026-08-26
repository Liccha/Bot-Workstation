package com.botstation.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and updates the local NapCat configuration without exposing or replacing secrets.
 * Only the active WebUI, OneBot HTTP server and local SongBot callback are editable.
 */
public final class NapCatConfigService {
    private static final String LOOPBACK = "127.0.0.1";
    private final Path configDirectory;
    private final Path songBotEndpoint;

    public NapCatConfigService(BotPaths paths) {
        this(paths.napCat, paths.songBot);
    }

    NapCatConfigService(Path napCatRoot, Path songBotRoot) {
        this.configDirectory = napCatRoot.resolve("napcat/config");
        this.songBotEndpoint = songBotRoot.resolve("data/napcat.properties");
    }

    public Snapshot load() throws IOException {
        JSONObject web = readObject(webUiConfig());
        Candidate candidate = findActiveOneBot();
        JSONObject server = candidate == null ? null : preferred(candidate.httpServers);
        JSONObject client = candidate == null ? null : preferred(candidate.httpClients);
        return new Snapshot(
            !web.optBoolean("disableWebUI", false),
            normalizeHost(web.optString("host", LOOPBACK)),
            validOrDefault(web.optInt("port", 6099), 6099),
            tokenConfigured(web),
            server != null && server.optBoolean("enable", false),
            server == null ? LOOPBACK : normalizeHost(server.optString("host", LOOPBACK)),
            server == null ? 3000 : validOrDefault(server.optInt("port", 3000), 3000),
            server != null && tokenConfigured(server),
            client != null && client.optBoolean("enable", false),
            client == null ? "http://127.0.0.1:8080/webhook" : client.optString("url", ""),
            client != null && tokenConfigured(client),
            candidate == null ? "" : candidate.path.getFileName().toString());
    }

    public void save(Settings settings) throws IOException {
        validate(settings);

        Path webPath = webUiConfig();
        JSONObject web = readObject(webPath);
        web.put("host", LOOPBACK);
        web.put("port", settings.webUiPort);
        web.put("disableWebUI", !settings.webUiEnabled);

        Candidate candidate = findActiveOneBot();
        if (candidate == null) throw new IOException("未找到可用的 onebot11_*.json 配置");
        JSONObject server = preferred(candidate.httpServers);
        if (server == null) {
            server = new JSONObject();
            candidate.httpServers.put(server);
        }
        server.put("enable", settings.httpEnabled);
        server.put("host", LOOPBACK);
        server.put("port", settings.httpPort);

        JSONObject client = preferred(candidate.httpClients);
        if (client == null) {
            client = new JSONObject();
            candidate.httpClients.put(client);
        }
        client.put("enable", settings.callbackEnabled);
        client.put("url", settings.callbackUrl.trim());

        // Write complete JSON objects so unknown NapCat fields and all existing tokens survive.
        atomicWrite(webPath, web.toString(2) + System.lineSeparator());
        atomicWrite(candidate.path, candidate.root.toString(2) + System.lineSeparator());
        atomicWrite(songBotEndpoint,
            "apiUrl=http://127.0.0.1:" + settings.httpPort + System.lineSeparator());
    }

    public int oneBotHttpPortOrDefault() {
        try { return load().httpPort; }
        catch (Exception ignored) { return 3000; }
    }

    private Path webUiConfig() throws IOException {
        Path path = configDirectory.resolve("webui.json");
        if (!Files.isRegularFile(path)) throw new IOException("未找到 NapCat WebUI 配置：" + path);
        return path;
    }

    private Candidate findActiveOneBot() throws IOException {
        if (!Files.isDirectory(configDirectory)) return null;
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.list(configDirectory)) {
            paths.filter(path -> path.getFileName().toString().startsWith("onebot11_"))
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(files::add);
        }
        Candidate best = null;
        for (Path path : files) {
            JSONObject root = readObject(path);
            JSONObject network = root.optJSONObject("network");
            if (network == null) { network = new JSONObject(); root.put("network", network); }
            JSONArray servers = network.optJSONArray("httpServers");
            if (servers == null) { servers = new JSONArray(); network.put("httpServers", servers); }
            JSONArray clients = network.optJSONArray("httpClients");
            if (clients == null) { clients = new JSONArray(); network.put("httpClients", clients); }
            Candidate current = new Candidate(path, root, servers, clients);
            if (best == null || current.score() > best.score()) best = current;
        }
        return best;
    }

    private static JSONObject preferred(JSONArray values) {
        if (values == null || values.length() == 0) return null;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && value.optBoolean("enable", false)) return value;
        }
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) return value;
        }
        return null;
    }

    private static void validate(Settings value) {
        requirePort(value.webUiPort, "WebUI 端口");
        requirePort(value.httpPort, "OneBot HTTP 端口");
        if (value.webUiPort == value.httpPort) throw new IllegalArgumentException("WebUI 与 OneBot HTTP 不能使用同一端口");
        if (value.callbackEnabled) {
            try {
                URI uri = URI.create(value.callbackUrl.trim());
                String host = uri.getHost();
                boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
                if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || uri.getPort() < 1
                    || !"/webhook".equals(uri.getPath())) {
                    throw new IllegalArgumentException("回调地址必须是本机 http://127.0.0.1:端口/webhook");
                }
            } catch (IllegalArgumentException error) {
                if (error.getMessage() != null && error.getMessage().startsWith("回调地址")) throw error;
                throw new IllegalArgumentException("回调地址格式无效");
            }
        }
    }

    private static void requirePort(int port, String label) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException(label + "必须在 1024–65535 之间");
    }

    private static JSONObject readObject(Path path) throws IOException {
        try { return new JSONObject(Files.readString(path, StandardCharsets.UTF_8)); }
        catch (RuntimeException error) { throw new IOException("NapCat 配置格式无效：" + path.getFileName(), error); }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean tokenConfigured(JSONObject value) {
        return !value.optString("token", "").trim().isEmpty();
    }

    private static int validOrDefault(int value, int fallback) {
        return value >= 1 && value <= 65535 ? value : fallback;
    }

    private static String normalizeHost(String host) {
        return host == null || host.trim().isEmpty() ? LOOPBACK : host.trim();
    }

    private static final class Candidate {
        final Path path;
        final JSONObject root;
        final JSONArray httpServers;
        final JSONArray httpClients;

        Candidate(Path path, JSONObject root, JSONArray httpServers, JSONArray httpClients) {
            this.path = path;
            this.root = root;
            this.httpServers = httpServers;
            this.httpClients = httpClients;
        }

        int score() {
            int score = httpServers.length() + httpClients.length();
            JSONObject server = preferred(httpServers);
            JSONObject client = preferred(httpClients);
            if (server != null && server.optBoolean("enable", false)) score += 10;
            if (client != null && client.optBoolean("enable", false)) score += 5;
            return score;
        }
    }

    public static final class Snapshot {
        public final boolean webUiEnabled;
        public final String webUiHost;
        public final int webUiPort;
        public final boolean webUiTokenConfigured;
        public final boolean httpEnabled;
        public final String httpHost;
        public final int httpPort;
        public final boolean httpTokenConfigured;
        public final boolean callbackEnabled;
        public final String callbackUrl;
        public final boolean callbackTokenConfigured;
        public final String accountConfigName;

        Snapshot(boolean webUiEnabled, String webUiHost, int webUiPort, boolean webUiTokenConfigured,
                 boolean httpEnabled, String httpHost, int httpPort, boolean httpTokenConfigured,
                 boolean callbackEnabled, String callbackUrl, boolean callbackTokenConfigured,
                 String accountConfigName) {
            this.webUiEnabled = webUiEnabled;
            this.webUiHost = webUiHost;
            this.webUiPort = webUiPort;
            this.webUiTokenConfigured = webUiTokenConfigured;
            this.httpEnabled = httpEnabled;
            this.httpHost = httpHost;
            this.httpPort = httpPort;
            this.httpTokenConfigured = httpTokenConfigured;
            this.callbackEnabled = callbackEnabled;
            this.callbackUrl = callbackUrl == null ? "" : callbackUrl;
            this.callbackTokenConfigured = callbackTokenConfigured;
            this.accountConfigName = accountConfigName;
        }

        public String webUiUrl() { return "http://127.0.0.1:" + webUiPort + "/"; }
        public String summary() { return "WebUI " + webUiPort + " · OneBot HTTP " + httpPort; }
    }

    public static final class Settings {
        public final boolean webUiEnabled;
        public final int webUiPort;
        public final boolean httpEnabled;
        public final int httpPort;
        public final boolean callbackEnabled;
        public final String callbackUrl;

        public Settings(boolean webUiEnabled, int webUiPort, boolean httpEnabled, int httpPort,
                        boolean callbackEnabled, String callbackUrl) {
            this.webUiEnabled = webUiEnabled;
            this.webUiPort = webUiPort;
            this.httpEnabled = httpEnabled;
            this.httpPort = httpPort;
            this.callbackEnabled = callbackEnabled;
            this.callbackUrl = callbackUrl == null ? "" : callbackUrl;
        }
    }
}
