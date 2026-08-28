package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.features.MobileDataService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pulls cloud edits into the local DB/CSV used by the desktop editor and SongBot. */
final class CloudLibrarySync implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final long POLL_INTERVAL_MS = 30_000L;
    private final URI endpoint;
    private final String desktopToken;
    private final MobileDataService data;
    private final Path stateFile;
    private final LogBus log;
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService worker;
    private volatile String lastError = "";

    private CloudLibrarySync(URI endpoint, String desktopToken, MobileDataService data,
                             Path stateFile, LogBus log) {
        this.endpoint = validateEndpoint(endpoint);
        this.desktopToken = desktopToken;
        this.data = data;
        this.stateFile = stateFile;
        this.log = log;
    }

    static CloudLibrarySync fromSongBot(BotPaths paths, LogBus log) throws IOException {
        Path file = paths.songBot.resolve("data").resolve("cloud-announcement.properties");
        if (!Files.isRegularFile(file)) return null;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        String api = properties.getProperty("api", "").trim();
        String token = properties.getProperty("desktopToken", "").trim();
        if (api.isEmpty() || token.isEmpty()) return null;
        URI announcement = URI.create(api);
        URI endpoint = URI.create(announcement.getScheme() + "://" + announcement.getRawAuthority() + "/api/mobile-data");
        return new CloudLibrarySync(endpoint, token, new MobileDataService(paths),
            paths.config().resolve("mobile-library-sync.json"), log);
    }

    static CloudLibrarySync forTest(URI endpoint, String desktopToken, MobileDataService data,
                                    Path stateFile, LogBus log) {
        return new CloudLibrarySync(endpoint, desktopToken, data, stateFile, log);
    }

    synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "bot-mobile-library-sync");
            thread.setDaemon(true);
            return thread;
        });
        worker.submit(this::loop);
        log.info("云端曲库", "后台同步已启用");
    }

    void syncOnce() throws Exception {
        JSONObject state = readState();
        long songsBefore = state.optLong("songs", 0L);
        long stableBefore = state.optLong("stable", 0L);
        boolean changed = false;
        changed |= syncDataset("songs", state);
        changed |= syncDataset("stable", state);
        if (changed || songsBefore != state.optLong("songs", 0L)
            || stableBefore != state.optLong("stable", 0L)) writeState(state);
    }

    private boolean syncDataset(String dataset, JSONObject state) throws Exception {
        long after = Math.max(0L, state.optLong(dataset, 0L));
        boolean changed = false;
        for (int page = 0; page < 100; page++) {
            JSONObject response = request(dataset, after);
            JSONArray items = response.optJSONArray("items");
            int applied = 0;
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.getJSONObject(index);
                    String id = item.optString("id", "").trim();
                    JSONObject values = item.optJSONObject("values");
                    if (id.isEmpty() || values == null) throw new IOException("云端变更格式无效");
                    if ("songs".equals(dataset)) data.updateSong(id, values);
                    else data.updateStable(id, values);
                    applied++;
                }
            }
            long revision = Math.max(after, response.optLong("nextRevision", response.optLong("revision", after)));
            if (revision == after && response.optBoolean("hasMore", false)) throw new IOException("云端变更游标没有前进");
            after = revision;
            state.put(dataset, after);
            if (applied > 0) {
                changed = true;
                log.info("云端曲库", ("songs".equals(dataset) ? "歌曲" : "Stable")
                    + "已同步 " + applied + " 条，版本 " + after);
            }
            if (!response.optBoolean("hasMore", false)) return changed;
        }
        throw new IOException("云端变更分页异常");
    }

    private void loop() {
        while (running.get()) {
            try {
                syncOnce();
                if (!lastError.isEmpty()) {
                    lastError = "";
                    log.info("云端曲库", "后台同步已恢复");
                }
            } catch (Exception error) {
                String message = safeMessage(error);
                if (!message.equals(lastError)) {
                    lastError = message;
                    log.warn("云端曲库", "暂未同步：" + message);
                }
            }
            pause(POLL_INTERVAL_MS);
        }
    }

    private JSONObject request(String dataset, long after) throws IOException {
        String query = "action=changes&dataset=" + URLEncoder.encode(dataset, StandardCharsets.UTF_8.name())
            + "&after=" + after + "&limit=500";
        URL url = URI.create(endpoint + "?" + query).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Desktop " + desktopToken);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : readLimited(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("云端同步请求失败（HTTP " + status + "）");
        try { return text.isBlank() ? new JSONObject() : new JSONObject(text); }
        catch (Exception error) { throw new IOException("云端同步返回了无效数据", error); }
    }

    private JSONObject readState() {
        try {
            if (!Files.isRegularFile(stateFile)) return new JSONObject();
            return new JSONObject(Files.readString(stateFile, StandardCharsets.UTF_8));
        } catch (Exception error) {
            log.warn("云端曲库", "同步游标损坏，将从安全基线重新核对");
            return new JSONObject();
        }
    }

    private void writeState(JSONObject state) throws IOException {
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        Files.writeString(temporary, state.toString(), StandardCharsets.UTF_8);
        try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int count; (count = source.read(buffer)) >= 0; ) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("云端同步响应过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static URI validateEndpoint(URI value) {
        if (value == null || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null
            || !"/api/mobile-data".equals(value.getPath())) throw new IllegalArgumentException("云端曲库地址无效");
        String scheme = String.valueOf(value.getScheme()).toLowerCase(Locale.ROOT);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        boolean production = "https".equals(scheme)
            && ("editor.teacharm.moe".equals(host) || "bot-editor.vercel.app".equals(host));
        boolean local = "http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host));
        if (!production && !local) throw new IllegalArgumentException("云端曲库必须使用受信任的 HTTPS 域名");
        return value;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "操作失败" : String.valueOf(error.getMessage());
        value = value.replaceAll("[\\r\\n]", " ");
        return value.isBlank() ? "操作失败" : value.substring(0, Math.min(240, value.length()));
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    @Override public synchronized void close() {
        running.set(false);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }
}
