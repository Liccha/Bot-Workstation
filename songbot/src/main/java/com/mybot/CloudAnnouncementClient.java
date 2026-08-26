package com.mybot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/** Pull-only cloud announcement executor. The cloud remains authoritative; this class never edits local announcements.json. */
final class CloudAnnouncementClient {
    private static final DateTimeFormatter DUE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final HttpClient http;
    private final String apiBase;
    private final String botToken;
    private final String botId;
    private final File cacheDir;

    interface Sender {
        AnnouncementStore.SendResult send(JSONObject announcement) throws Exception;
    }

    private CloudAnnouncementClient(String apiBase, String botToken, String botId, File cacheDir) {
        this.apiBase = trimSlash(apiBase);
        this.botToken = botToken;
        this.botId = botId;
        this.cacheDir = cacheDir;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    }

    static CloudAnnouncementClient fromEnvironment(File songBotDir) throws IOException {
        Properties file = new Properties();
        File configFile = new File(new File(songBotDir, "data"), "cloud-announcement.properties");
        if (configFile.isFile()) {
            try (java.io.Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
                file.load(reader);
            }
        }
        String api = firstNonBlank(System.getenv("ANNOUNCEMENT_CLOUD_API"), file.getProperty("api"));
        String token = firstNonBlank(System.getenv("ANNOUNCEMENT_BOT_TOKEN"), file.getProperty("botToken"));
        String id = firstNonBlank(System.getenv("ANNOUNCEMENT_BOT_ID"), file.getProperty("botId"));
        if (id.isEmpty()) {
            try { id = InetAddress.getLocalHost().getHostName(); }
            catch (Exception ignored) { id = "songbot"; }
        }
        if (api.isEmpty() || token.isEmpty()) throw new IOException("cloud announcement api or bot token is missing");
        return new CloudAnnouncementClient(api, token, id, new File(new File(songBotDir, "announce_files"), "cloud-cache"));
    }

    static boolean cloudEnabled(File songBotDir) {
        String mode = firstNonBlank(System.getenv("ANNOUNCEMENT_BACKEND"), readMode(songBotDir));
        return "cloud".equalsIgnoreCase(mode);
    }

    int processDue(LocalDateTime now, Sender sender) throws Exception {
        String before = URLEncoder.encode(now.format(DUE_TIME), StandardCharsets.UTF_8);
        JSONObject due = request("GET", "?action=bot-due&before=" + before, null);
        JSONArray items = due.optJSONArray("items");
        if (items == null || items.length() == 0) return 0;
        int sent = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject summary = items.optJSONObject(i);
            if (summary == null) continue;
            String id = summary.optString("id", "");
            if (id.isEmpty()) continue;
            JSONObject claimed;
            try {
                claimed = request("POST", "?action=bot-claim", new JSONObject().put("id", id).put("botId", botId));
            } catch (ConflictException ignored) {
                continue;
            }
            String claimToken = claimed.optString("claimToken", "");
            try {
                JSONObject journal = readJournal(id);
                if (journal != null && "sent".equals(journal.optString("status"))) {
                    request("POST", "?action=bot-complete", new JSONObject().put("id", id)
                        .put("claimToken", claimToken).put("sentAt", journal.optString("sentAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                        .put("messageId", journal.optString("messageId", "")));
                    deleteJournal(id);
                    System.out.println("[云公告] 检测到本地成功凭据，仅补报云端状态，未重复发送: " + claimed.optString("title", id));
                    continue;
                }
                prepareFiles(claimed);
                writeJournal(id, new JSONObject().put("status", "sending").put("at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
                AnnouncementStore.SendResult result = sender.send(claimed);
                if (result != null && result.success) {
                    String sentAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    writeJournal(id, new JSONObject().put("status", "sent").put("sentAt", sentAt).put("detail", result.detail));
                    request("POST", "?action=bot-complete", new JSONObject().put("id", id)
                        .put("claimToken", claimToken).put("sentAt", sentAt));
                    deleteJournal(id);
                    sent++;
                } else {
                    String detail = result == null ? "empty send result" : result.detail;
                    reportFailure(id, claimToken, detail, isUncertain(detail));
                    deleteJournal(id);
                }
            } catch (Exception ex) {
                String detail = String.valueOf(ex.getMessage());
                try { reportFailure(id, claimToken, detail, isUncertain(detail)); }
                catch (Exception reportError) { System.err.println("[云公告] 发送失败状态回报失败，本地凭据已保留: " + reportError.getMessage()); }
            }
        }
        return sent;
    }

    private void prepareFiles(JSONObject announcement) throws Exception {
        String image = announcement.optString("image", "");
        String imageUrl = announcement.optString("imageUrl", "");
        if (!image.isEmpty() && !imageUrl.isEmpty()) announcement.put("image", download(image, imageUrl));
        String attach = announcement.optString("attach", "");
        JSONObject urls = announcement.optJSONObject("attachmentUrls");
        if (!attach.isEmpty() && urls != null) {
            java.util.List<String> local = new java.util.ArrayList<>();
            JSONArray suppliedNames = attachmentNames(announcement.opt("attachmentNames"));
            JSONArray originalNames = new JSONArray();
            int attachmentIndex = 0;
            for (String key : attach.split("\\|")) {
                if (key.isEmpty()) continue;
                String url = urls.optString(key, "");
                if (url.isEmpty()) throw new IOException("missing signed attachment url: " + key);
                originalNames.put(originalAttachmentName(suppliedNames.optString(attachmentIndex, ""), key));
                local.add(download(key, url));
                attachmentIndex++;
            }
            announcement.put("attach", String.join("|", local));
            announcement.put("attachmentNames", originalNames);
        }
    }

    static JSONArray attachmentNames(Object raw) {
        if (raw instanceof JSONArray) return (JSONArray) raw;
        if (raw instanceof java.util.Collection<?>) return new JSONArray((java.util.Collection<?>) raw);
        String value = raw == null || raw == JSONObject.NULL ? "" : String.valueOf(raw).trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            try { return new JSONArray(value); }
            catch (Exception ignored) {}
        }
        return new JSONArray();
    }

    static String originalAttachmentName(String supplied, String token) {
        return sanitizeAttachmentName(supplied, nameFromToken(token));
    }

    static String sanitizeAttachmentName(String requested, String fallback) {
        String value = requested == null ? "" : Normalizer.normalize(requested, Normalizer.Form.NFC);
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
            .replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        if (value.isEmpty()) {
            value = fallback == null ? "" : Normalizer.normalize(fallback, Normalizer.Form.NFC);
            value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        }
        if (value.isEmpty()) value = "file.bin";
        int count = value.codePointCount(0, value.length());
        if (count > 160) value = value.substring(0, value.offsetByCodePoints(0, 160));
        return value;
    }

    static String nameFromToken(String token) {
        if (token == null || token.trim().isEmpty()) return "file.bin";
        String normalized = token.trim().replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        name = name.replaceFirst("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-", "");
        return name.isEmpty() ? "file.bin" : name;
    }

    private String download(String objectKey, String url) throws Exception {
        cacheDir.mkdirs();
        String extension = safeExtension(objectKey);
        String name = sha256(objectKey) + extension;
        File target = new File(cacheDir, name);
        if (target.isFile() && target.length() > 0) return "cloud-cache/" + name;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("attachment download HTTP " + response.statusCode());
        File temp = File.createTempFile(".cloud-announcement-", ".tmp", cacheDir);
        try {
            Files.write(temp.toPath(), response.body());
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
        return "cloud-cache/" + name;
    }

    private void reportFailure(String id, String claimToken, String detail, boolean uncertain) throws Exception {
        request("POST", "?action=bot-fail", new JSONObject().put("id", id).put("claimToken", claimToken)
            .put("error", detail == null ? "unknown" : detail).put("uncertain", uncertain));
    }

    private File journalFile(String id) throws Exception {
        File dir = new File(cacheDir, "dispatch"); dir.mkdirs();
        return new File(dir, sha256(id) + ".json");
    }
    private JSONObject readJournal(String id) {
        try { File file = journalFile(id); return file.isFile() ? new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8)) : null; }
        catch (Exception ignored) { return null; }
    }
    private void writeJournal(String id, JSONObject value) throws Exception {
        File file = journalFile(id); File temp = File.createTempFile(".dispatch-", ".tmp", file.getParentFile());
        try {
            Files.writeString(temp.toPath(), value.put("announcementId", id).toString(), StandardCharsets.UTF_8);
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private void deleteJournal(String id) { try { Files.deleteIfExists(journalFile(id).toPath()); } catch (Exception ignored) {} }

    private JSONObject request(String method, String suffix, JSONObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + suffix)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bot " + botToken).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json; charset=utf-8")
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 409) throw new ConflictException();
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("cloud api HTTP " + response.statusCode());
        return new JSONObject(response.body());
    }

    private static String readMode(File root) {
        File file = new File(new File(root, "data"), "cloud-announcement.properties");
        if (!file.isFile()) return "local";
        Properties props = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) { props.load(reader); return props.getProperty("backend", "local"); }
        catch (Exception ignored) { return "local"; }
    }
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }
    private static String trimSlash(String value) { while (value.endsWith("/")) value = value.substring(0, value.length() - 1); return value; }
    private static String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b)); return out.toString();
    }
    private static String safeExtension(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1); int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 12) return ".bin";
        String ext = name.substring(dot).replaceAll("[^A-Za-z0-9.]", ""); return ext.isEmpty() ? ".bin" : ext;
    }
    private static boolean isUncertain(String detail) {
        String lower = String.valueOf(detail).toLowerCase(java.util.Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时");
    }
    private static final class ConflictException extends Exception {}
}
