package com.botstation.features;

import com.botstation.core.CloudEndpoints;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Cloud-only library access for installations that do not own SongBot's local database. */
final class CloudLibraryClient {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_COMPRESSED_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final int PAGE_SIZE = 200;
    private static final int UPDATE_ATTEMPTS = 2;
    private static final Set<String> MANAGED_SONG_ASSET_FIELDS = Set.of(
        "album_image_path", "image_path", "audio_path");
    private final URI endpoint;
    private final Path stateDirectory;
    private final Path credentialFile;
    private JSONObject credentials;

    CloudLibraryClient(Path stateDirectory) {
        this(resolveEndpoint(), stateDirectory);
    }

    CloudLibraryClient(URI endpoint, Path stateDirectory) {
        this.endpoint = validateEndpoint(endpoint);
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.credentialFile = this.stateDirectory.resolve("library-editor-device.json");
    }

    SongLibraryRepository.Snapshot loadSongs() throws Exception {
        CloudSnapshot snapshot = load("songs");
        List<Map<String, String>> rows = new ArrayList<>();
        for (JSONObject item : snapshot.items) {
            Map<String, String> row = new LinkedHashMap<>();
            for (String column : snapshot.columns) row.put(column, item.optString(column, ""));
            rows.add(row);
        }
        return new SongLibraryRepository.Snapshot(snapshot.columns, rows);
    }

    StableRepository.Snapshot loadStable() throws Exception {
        CloudSnapshot snapshot = load("stable");
        List<List<String>> rows = new ArrayList<>();
        for (JSONObject item : snapshot.items) {
            List<String> row = new ArrayList<>();
            for (String column : snapshot.columns) row.add(item.optString(column, ""));
            rows.add(row);
        }
        return new StableRepository.Snapshot(snapshot.columns, rows, "云端 Stable");
    }

    void updateSong(String id, Map<String, String> values) throws Exception {
        update("song", "songs", "id", id, values);
    }

    boolean createSong(String id, Map<String, String> values) throws Exception {
        boolean created = true;
        try {
            JSONObject result = authorized("POST", "song-create",
                new JSONObject().put("id", id).put("values", new JSONObject(values)), null);
            created = result.optBoolean("created", true);
        } catch (SocketTimeoutException timeout) {
            if (!songMatches(id, values)) throw timeout;
            created = false;
        } catch (HttpStatusException conflict) {
            boolean recordConflict = "record_exists".equals(conflict.code)
                || (conflict.code.isBlank() && conflict.getMessage().startsWith("record already exists"));
            if (conflict.status != 409 || !recordConflict || !songMatches(id, values)) throw conflict;
            created = false;
        }
        Files.deleteIfExists(cacheFile("songs"));
        return created;
    }

    void deleteSong(String id) throws Exception {
        try {
            authorized("POST", "song-delete", new JSONObject().put("id", id), null);
        } catch (SocketTimeoutException timeout) {
            if (findSong(id) != null) throw timeout;
        }
        Files.deleteIfExists(cacheFile("songs"));
    }

    void updateStable(String sid, Map<String, String> values) throws Exception {
        update("stable", "stable", "sid", sid, values);
    }

    void publishAsset(String id, String type, Path source, String contentType) throws Exception {
        long size = Files.size(source);
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        JSONObject ticket = authorized("POST", "asset-ticket", new JSONObject()
            .put("type", type).put("size", size).put("extension", extension).put("contentType", contentType), null);
        URI upload = validateUploadUri(URI.create(ticket.getString("uploadUrl")));
        HttpURLConnection connection = (HttpURLConnection) upload.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(120_000);
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(size);
        connection.setRequestProperty("Content-Type", contentType);
        try (OutputStream output = connection.getOutputStream(); InputStream input = Files.newInputStream(source)) {
            input.transferTo(output);
        }
        int status = connection.getResponseCode();
        InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (response != null) response.close();
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("云端文件上传失败（HTTP " + status + "）");
        String key = ticket.getString("key");
        String target = assetTargetKey(id, type, key);
        try {
            authorized("POST", "song-asset", new JSONObject().put("id", id).put("type", type).put("key", key), null);
        } catch (IOException uncertain) {
            if (!songAssetMatches(id, type, target)) throw uncertain;
        }
        Files.deleteIfExists(cacheFile("songs"));
    }

    private synchronized CloudSnapshot load(String dataset) throws Exception {
        JSONObject cached = readJson(cacheFile(dataset));
        JSONObject status;
        try {
            status = authorized("GET", "status", null, null);
        } catch (IOException failure) {
            return fallbackSnapshot(dataset, cached, failure);
        }
        JSONObject marker = status.optJSONObject(dataset);
        if (marker == null) throw new IOException("云端曲库尚未初始化");
        long revision = marker.optLong("revision", -1L);
        if (cached != null && cached.optLong("revision", -2L) == revision) return parseSnapshot(cached, dataset);

        try {
            JSONArray all = new JSONArray();
            JSONArray columns = null;
            int offset = 0;
            for (int page = 0; page < 100; page++) {
                String query = "offset=" + offset + "&limit=" + PAGE_SIZE;
                JSONObject response = authorized("GET", dataset, null, query);
                if (columns == null) columns = response.optJSONArray("columns");
                JSONArray items = response.optJSONArray("items");
                if (items != null) for (int index = 0; index < items.length(); index++) all.put(items.getJSONObject(index));
                if (!response.optBoolean("hasMore", false)) {
                    JSONObject document = new JSONObject().put("dataset", dataset).put("revision", response.optLong("revision", revision))
                        .put("columns", columns == null ? new JSONArray() : columns).put("items", all);
                    writeJson(cacheFile(dataset), document);
                    return parseSnapshot(document, dataset);
                }
                int next = response.optInt("nextOffset", offset + (items == null ? 0 : items.length()));
                if (next <= offset) throw new IOException("云端曲库分页游标没有前进");
                offset = next;
            }
            throw new IOException("云端曲库分页异常");
        } catch (IOException failure) {
            return fallbackSnapshot(dataset, cached, failure);
        }
    }

    private CloudSnapshot fallbackSnapshot(String dataset, JSONObject cached, IOException primary) throws Exception {
        try {
            JSONObject document = downloadCompactSnapshot(dataset);
            writeJson(cacheFile(dataset), document);
            return parseSnapshot(document, dataset);
        } catch (Exception fallbackFailure) {
            primary.addSuppressed(fallbackFailure);
            if (cached != null) return parseSnapshot(cached, dataset);
            throw primary;
        }
    }

    private JSONObject downloadCompactSnapshot(String dataset) throws Exception {
        String encoded = URLEncoder.encode(dataset, StandardCharsets.UTF_8.name());
        JSONObject ticket = authorized("GET", "snapshot-ticket", null, "dataset=" + encoded);
        if (!dataset.equals(ticket.optString("dataset", "")) || !"gzip-json".equals(ticket.optString("encoding", "")))
            throw new IOException("云端曲库快照凭证无效");
        URI download = validateSnapshotUri(resolveTicketUri(ticket.getString("url")), dataset);
        HttpURLConnection connection = (HttpURLConnection) download.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/gzip, application/octet-stream");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            InputStream error = connection.getErrorStream();
            if (error != null) error.close();
            connection.disconnect();
            throw new IOException("云端曲库快照下载失败（HTTP " + status + "）");
        }
        JSONObject document;
        try (InputStream raw = new LimitedInputStream(connection.getInputStream(), MAX_COMPRESSED_SNAPSHOT_BYTES);
             GZIPInputStream gzip = new GZIPInputStream(raw)) {
            document = new JSONObject(readLimited(gzip));
        } finally {
            connection.disconnect();
        }
        if (!dataset.equals(document.optString("dataset", dataset))) throw new IOException("云端曲库快照类型不匹配");
        return document;
    }

    private URI resolveTicketUri(String value) {
        URI candidate = URI.create(value);
        if (candidate.isAbsolute()) return candidate;
        return endpoint.resolve(candidate);
    }

    private void update(String action, String dataset, String idField, String id, Map<String, String> values) throws Exception {
        Map<String, String> writable = writableValues(dataset, idField, values);
        if (writable.isEmpty()) return;
        JSONObject body = new JSONObject().put(idField, id).put("values", new JSONObject(writable));
        JSONObject result = null;
        IOException lastFailure = null;
        for (int attempt = 0; attempt < UPDATE_ATTEMPTS; attempt++) {
            try {
                result = authorized("POST", action, body, null);
                lastFailure = null;
                break;
            } catch (IOException failure) {
                if (!"songs".equals(dataset) || !uncertainUpdateFailure(failure)) throw failure;
                lastFailure = failure;
                if (songMatchesAfterUncertainWrite(id, writable)) {
                    result = new JSONObject();
                    lastFailure = null;
                    break;
                }
            }
        }
        if (lastFailure != null) throw lastFailure;
        if (result == null) result = new JSONObject();
        JSONObject cached = readJson(cacheFile(dataset));
        if (cached == null) return;
        JSONArray items = cached.optJSONArray("items");
        boolean found = false;
        if (items != null) for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (!id.equals(item.optString(idField, ""))) continue;
            writable.forEach(item::put); found = true; break;
        }
        if (found) {
            cached.put("revision", result.optLong("revision", cached.optLong("revision", 0L)));
            writeJson(cacheFile(dataset), cached);
        } else Files.deleteIfExists(cacheFile(dataset));
    }

    private boolean songMatches(String id, Map<String, String> values) throws Exception {
        JSONObject item = findSong(id);
        if (item == null) return false;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!String.valueOf(entry.getValue() == null ? "" : entry.getValue())
                .equals(item.optString(entry.getKey(), ""))) return false;
        }
        return true;
    }

    private boolean songMatchesAfterUncertainWrite(String id, Map<String, String> values) {
        try { return songMatches(id, values); }
        catch (Exception ignored) { return false; }
    }

    private static boolean uncertainUpdateFailure(IOException failure) {
        if (failure instanceof SocketTimeoutException) return true;
        if (!(failure instanceof HttpStatusException)) return false;
        HttpStatusException status = (HttpStatusException) failure;
        return (status.status >= 500 && status.status <= 599)
            || "write_busy".equals(status.code) || "store_busy".equals(status.code);
    }

    private static Map<String, String> writableValues(String dataset, String idField, Map<String, String> values) {
        Map<String, String> writable = new LinkedHashMap<>();
        if (values == null) return writable;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.equalsIgnoreCase(idField)) continue;
            if ("songs".equals(dataset) && MANAGED_SONG_ASSET_FIELDS.contains(key.toLowerCase(Locale.ROOT))) continue;
            writable.put(key, entry.getValue() == null ? "" : entry.getValue());
        }
        return writable;
    }

    boolean hasPublishedAsset(String id, String type) throws Exception {
        JSONObject item = findSong(id);
        if (item == null) return false;
        String field = "image".equals(type) ? "image_path" : "audio_path";
        return item.optString(field, "").startsWith("cloud-object:");
    }

    private boolean songAssetMatches(String id, String type, String key) throws Exception {
        JSONObject item = findSong(id);
        if (item == null) return false;
        String field = "image".equals(type) ? "image_path" : "audio_path";
        return ("cloud-object:" + key).equals(item.optString(field, ""));
    }

    private JSONObject findSong(String id) throws Exception {
        String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name());
        try {
            return authorized("GET", "song-item", null, "id=" + encodedId);
        } catch (HttpStatusException failure) {
            if (failure.status == 404) return null;
            if (failure.status != 400 && failure.status != 405) throw failure;
        }
        return findSongFromLegacyList(id, encodedId);
    }

    private JSONObject findSongFromLegacyList(String id, String encodedId) throws Exception {
        int offset = 0;
        for (int page = 0; page < 25; page++) {
            String query = "q=" + encodedId + "&offset=" + offset + "&limit=200";
            JSONObject response = authorized("GET", "songs", null, query);
            JSONArray items = response.optJSONArray("items");
            if (items != null) for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                if (id.equals(item.optString("id", "").trim())) return item;
            }
            if (!response.optBoolean("hasMore", false)) return null;
            int next = response.optInt("nextOffset", offset + (items == null ? 0 : items.length()));
            if (next <= offset) return null;
            offset = next;
        }
        return null;
    }

    private static String assetTargetKey(String id, String type, String sourceKey) {
        int slash = sourceKey.lastIndexOf('/');
        String fileName = slash < 0 ? sourceKey : sourceKey.substring(slash + 1);
        return "mobile-library/assets/" + type + "/" + id + "/" + fileName;
    }

    private JSONObject authorized(String method, String action, JSONObject body, String extraQuery) throws Exception {
        ensureEnrolled();
        JSONObject response = request(method, action, body, extraQuery, credentials.optString("token", ""));
        if (response.optInt("_httpStatus", 200) != 401) return withoutStatus(response);
        enroll(true);
        response = request(method, action, body, extraQuery, credentials.optString("token", ""));
        int status = response.optInt("_httpStatus", 200);
        if (status < 200 || status >= 300) throw responseError(status, response);
        return withoutStatus(response);
    }

    private void ensureEnrolled() throws Exception {
        if (credentials == null) credentials = readJson(credentialFile);
        if (credentials == null) {
            credentials = new JSONObject().put("installationId", UUID.randomUUID().toString())
                .put("secret", randomSecret()).put("name", "Bot工作站");
            writeJson(credentialFile, credentials);
        }
        if (credentials.optString("token", "").isBlank()) enroll(false);
    }

    private void enroll(boolean force) throws Exception {
        if (!force && !credentials.optString("token", "").isBlank()) return;
        JSONObject response = request("POST", "enroll-editor", new JSONObject()
            .put("installationId", credentials.getString("installationId"))
            .put("secret", credentials.getString("secret"))
            .put("name", credentials.optString("name", "Bot工作站")), null, "");
        int status = response.optInt("_httpStatus", 200);
        if (status < 200 || status >= 300) throw responseError(status, response);
        credentials.put("id", response.getString("id")).put("token", response.getString("token"));
        writeJson(credentialFile, credentials);
    }

    private JSONObject request(String method, String action, JSONObject body, String extraQuery, String token) throws Exception {
        StringBuilder query = new StringBuilder("action=").append(URLEncoder.encode(action, StandardCharsets.UTF_8.name()));
        if (extraQuery != null && !extraQuery.isBlank()) query.append('&').append(extraQuery);
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint + "?" + query).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(readTimeoutMillis());
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "BotWorkstation/1");
        if (token != null && !token.isBlank()) connection.setRequestProperty("Authorization", "Device " + token);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : readLimited(stream);
        connection.disconnect();
        JSONObject value;
        try { value = text.isBlank() ? new JSONObject() : new JSONObject(text); }
        catch (Exception error) { throw new IOException("云端曲库返回了无效数据", error); }
        value.put("_httpStatus", status);
        if (status != 401 && (status < 200 || status >= 300)) throw responseError(status, value);
        return value;
    }

    private CloudSnapshot parseSnapshot(JSONObject document, String dataset) throws IOException {
        JSONArray columnValues = document.optJSONArray("columns");
        JSONArray itemValues = document.optJSONArray("items");
        if (columnValues == null || itemValues == null) throw new IOException("云端曲库缓存格式无效");
        List<String> columns = new ArrayList<>();
        for (int index = 0; index < columnValues.length(); index++) columns.add(columnValues.optString(index, ""));
        List<JSONObject> items = new ArrayList<>();
        for (int index = 0; index < itemValues.length(); index++) items.add(itemValues.getJSONObject(index));
        if (columns.isEmpty()) throw new IOException("云端曲库没有表头");
        return new CloudSnapshot(dataset, document.optLong("revision", 0L), columns, items);
    }

    private Path cacheFile(String dataset) { return stateDirectory.resolve("cloud-" + dataset + "-cache.json"); }

    private static JSONObject readJson(Path file) {
        try { return Files.isRegularFile(file) ? new JSONObject(Files.readString(file, StandardCharsets.UTF_8)) : null; }
        catch (Exception ignored) { return null; }
    }

    private static void writeJson(Path file, JSONObject value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0;
            for (int count; (count = source.read(buffer)) >= 0; ) {
                total += count; if (total > MAX_RESPONSE_BYTES) throw new IOException("云端曲库响应过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject withoutStatus(JSONObject value) { value.remove("_httpStatus"); return value; }
    private static IOException responseError(int status, JSONObject value) {
        String error = value.optString("error", "请求失败");
        if (status == 423) error = "云端写入已被管理员紧急暂停";
        else if (status == 429) error = "操作过于频繁，请稍后再试";
        else if (status == 401) error = "设备授权已失效";
        else if (status == 409 && "record_exists".equals(value.optString("code", "")))
            error = "该 ID 已被其他歌曲占用，请刷新曲库后使用最新 ID";
        return new HttpStatusException(status, value.optString("code", ""), error + "（HTTP " + status + "）");
    }

    private static final class HttpStatusException extends IOException {
        final int status;
        final String code;
        HttpStatusException(int status, String code, String message) {
            super(message); this.status = status; this.code = code == null ? "" : code;
        }
    }
    private static String randomSecret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static int readTimeoutMillis() {
        try { return Math.max(100, Math.min(120_000,
            Integer.parseInt(System.getProperty("botstation.cloud.library.readTimeoutMs", "30000")))); }
        catch (NumberFormatException ignored) { return 30_000; }
    }
    private static URI resolveEndpoint() {
        String configured = System.getProperty("botstation.cloud.library.api", "").trim();
        return configured.isEmpty() ? CloudEndpoints.MOBILE_DATA : CloudEndpoints.migrateLegacy(URI.create(configured));
    }
    private static URI validateEndpoint(URI value) {
        if (value == null || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null
            || !"/api/mobile-data".equals(value.getPath())) throw new IllegalArgumentException("云端曲库地址无效");
        String scheme = String.valueOf(value.getScheme()).toLowerCase(Locale.ROOT);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        boolean production = "https".equals(scheme) && CloudEndpoints.isProductionHost(host);
        boolean local = "http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host));
        if (!production && !local) throw new IllegalArgumentException("云端曲库必须使用受信任的 HTTPS 域名");
        return value;
    }
    private static URI validateUploadUri(URI value) {
        String host = value == null || value.getHost() == null ? "" : value.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(value == null ? "" : value.getScheme()) || value.getUserInfo() != null
            || !host.endsWith(".aliyuncs.com") || !String.valueOf(value.getPath()).contains("/mobile-library/uploads/")) {
            throw new IllegalArgumentException("云端上传地址无效");
        }
        return value;
    }

    private URI validateSnapshotUri(URI value, String dataset) {
        String scheme = String.valueOf(value == null ? null : value.getScheme()).toLowerCase(Locale.ROOT);
        String host = value == null || value.getHost() == null ? "" : value.getHost().toLowerCase(Locale.ROOT);
        boolean local = "http".equals(scheme) && endpoint.getHost().equalsIgnoreCase(host)
            && ("127.0.0.1".equals(host) || "localhost".equals(host));
        String expected = "/mobile-library/" + dataset + "/current.json.gz";
        boolean oss = "https".equals(scheme) && host.endsWith(".aliyuncs.com")
            && String.valueOf(value.getPath()).endsWith(expected);
        if (value == null || value.getUserInfo() != null || value.getFragment() != null || (!local && !oss))
            throw new IllegalArgumentException("云端曲库快照地址无效");
        return value;
    }

    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long maximum;
        private long count;
        LimitedInputStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > maximum) throw new IOException("云端曲库压缩快照过大");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int value = super.read(bytes, offset, length);
            if (value > 0 && (count += value) > maximum) throw new IOException("云端曲库压缩快照过大");
            return value;
        }
    }

    private static final class CloudSnapshot {
        final String dataset; final long revision; final List<String> columns; final List<JSONObject> items;
        CloudSnapshot(String dataset, long revision, List<String> columns, List<JSONObject> items) {
            this.dataset = dataset; this.revision = revision; this.columns = columns; this.items = items;
        }
    }
}
