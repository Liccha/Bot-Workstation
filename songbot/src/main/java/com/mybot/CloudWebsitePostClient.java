package com.mybot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/** Cloud source-of-truth for the Markdown files used to build teacharm.moe. */
final class CloudWebsitePostClient {
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private final HttpClient http;
    private final String apiBase;
    private final String desktopToken;
    private final File backupRoot;
    private final File syncStateFile;

    private CloudWebsitePostClient(String apiBase, String desktopToken, File backupRoot, File syncStateFile) {
        this.apiBase = trimSlash(apiBase);
        this.desktopToken = desktopToken;
        this.backupRoot = backupRoot;
        this.syncStateFile = syncStateFile;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    }

    static CloudWebsitePostClient fromEnvironment(File songBotDir) throws IOException {
        Properties file = new Properties();
        File configFile = new File(new File(songBotDir, "data"), "cloud-announcement.properties");
        if (configFile.isFile()) {
            try (java.io.Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) { file.load(reader); }
        }
        String api = firstNonBlank(System.getenv("ANNOUNCEMENT_CLOUD_API"), file.getProperty("api"));
        String token = firstNonBlank(System.getenv("ANNOUNCEMENT_DESKTOP_TOKEN"), file.getProperty("desktopToken"));
        if (api.isEmpty() || token.isEmpty()) throw new IOException("website cloud api or desktop token is missing");
        return new CloudWebsitePostClient(api, token,
            new File(new File(songBotDir, "backups"), "website-post-sync"),
            new File(new File(songBotDir, "data"), "website-post-sync-state.json"));
    }

    JSONArray list() throws Exception { return (JSONArray) request("GET", "website-list", null, null, null, null); }

    JSONObject read(String name) throws Exception {
        validateName(name);
        return (JSONObject) request("GET", "website-read", name, null, null, null);
    }

    JSONObject save(String name, String content, Integer revision) throws Exception {
        validateName(name);
        JSONObject body = new JSONObject().put("name", name).put("content", content);
        if (revision != null) body.put("revision", revision);
        return (JSONObject) request("POST", "website-save", null, body, null, null);
    }

    void delete(String name, int revision) throws Exception {
        validateName(name);
        request("DELETE", "website-delete", name, null, revision, null);
    }

    void syncTo(File blogDir) throws Exception {
        long previousRevision = readSyncRevision();
        JSONObject snapshot = (JSONObject) request("GET", "website-sync", null, null, null, previousRevision);
        if (snapshot.optBoolean("unchanged", false)) return;
        JSONArray posts = snapshot.optJSONArray("posts");
        if (posts == null) throw new IOException("website cloud sync returned no posts");
        int mirrored = 0; int skipped = 0;
        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.optJSONObject(i); if (post == null) continue;
            MirrorResult result = mirrorTo(blogDir, post);
            if (result == MirrorResult.UPDATED) mirrored++;
            if (result == MirrorResult.LOCAL_NEWER) skipped++;
        }
        long revision = snapshot.optLong("revision", previousRevision);
        if (revision < previousRevision) throw new IOException("website cloud revision moved backwards");
        writeSyncRevision(revision);
        if (mirrored > 0 || skipped > 0) System.out.println("[网站文章云同步] 更新 " + mirrored + "，保留本机较新文件 " + skipped);
    }

    JSONObject mirrorSavedPost(File blogDir, JSONObject post) throws Exception {
        mirrorTo(blogDir, post); return post;
    }

    void archiveLocal(File blogDir, String name) throws Exception {
        File source = resolve(blogDir, name); if (!source.isFile()) return;
        File destination = backupFile(name); destination.getParentFile().mkdirs();
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[网站文章云同步] 本机文件已归档: " + destination.getAbsolutePath());
    }

    private MirrorResult mirrorTo(File blogDir, JSONObject post) throws Exception {
        String name = post.getString("name"); validateName(name);
        String content = post.optString("content", ""); long cloudModified = post.optLong("modified", 0L);
        File target = resolve(blogDir, name); byte[] next = content.getBytes(StandardCharsets.UTF_8);
        if (target.isFile()) {
            byte[] current = Files.readAllBytes(target.toPath());
            if (MessageDigest.isEqual(sha256(current), sha256(next))) return MirrorResult.UNCHANGED;
            if (cloudModified > 0 && target.lastModified() > cloudModified + 2000L) {
                System.err.println("[网站文章云同步] 跳过本机较新文件，需人工合并: " + target.getAbsolutePath());
                return MirrorResult.LOCAL_NEWER;
            }
            File backup = backupFile(name); backup.getParentFile().mkdirs(); Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        writeAtomically(target, next);
        if (cloudModified > 0) target.setLastModified(cloudModified);
        return MirrorResult.UPDATED;
    }

    private Object request(String method, String action, String name, JSONObject body, Integer revision, Long after) throws Exception {
        StringBuilder url = new StringBuilder(apiBase).append("?action=").append(URLEncoder.encode(action, StandardCharsets.UTF_8));
        if (name != null) url.append("&name=").append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (revision != null) url.append("&revision=").append(revision);
        if (after != null) url.append("&after=").append(Math.max(0L, after));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Desktop " + desktopToken).header("X-Admin-Device", "songbot-website-sync").header("Accept", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json; charset=utf-8")
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("website cloud api HTTP " + response.statusCode());
        return new JSONTokener(response.body()).nextValue();
    }

    private long readSyncRevision() {
        try {
            if (!syncStateFile.isFile()) return 0L;
            return Math.max(0L, new JSONObject(Files.readString(syncStateFile.toPath(), StandardCharsets.UTF_8)).optLong("revision", 0L));
        } catch (Exception ignored) { return 0L; }
    }

    private void writeSyncRevision(long revision) throws IOException {
        File parent = syncStateFile.getParentFile();
        if (parent != null) parent.mkdirs();
        File temp = File.createTempFile(".website-sync-state-", ".tmp", parent);
        try {
            Files.writeString(temp.toPath(), new JSONObject().put("revision", Math.max(0L, revision))
                .put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).toString(), StandardCharsets.UTF_8);
            try { Files.move(temp.toPath(), syncStateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), syncStateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp.toPath()); }
    }

    private File backupFile(String name) {
        return new File(new File(backupRoot, LocalDateTime.now().format(BACKUP_STAMP)), name);
    }
    private static File resolve(File root, String name) throws IOException {
        validateName(name); File file = new File(root, name);
        if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("invalid website post path");
        return file;
    }
    private static void validateName(String name) throws IOException {
        if (name == null || name.isBlank() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".md") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IOException("invalid website post name");
        }
    }
    private static void writeAtomically(File file, byte[] content) throws IOException {
        file.getParentFile().mkdirs(); File temp = File.createTempFile(".website-post-", ".tmp", file.getParentFile());
        try {
            Files.write(temp.toPath(), content);
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private static byte[] sha256(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value); }
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim(); return second == null ? "" : second.trim();
    }
    private static String trimSlash(String value) { while (value.endsWith("/")) value = value.substring(0, value.length() - 1); return value; }
    private enum MirrorResult { UNCHANGED, UPDATED, LOCAL_NEWER }
}
