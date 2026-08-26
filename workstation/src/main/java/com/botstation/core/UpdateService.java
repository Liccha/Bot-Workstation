package com.botstation.core;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;

/** Public, signed-by-hash update feed. Cloud credentials are never needed by clients. */
public final class UpdateService {
    public static final String CURRENT_VERSION = "1.1.7";
    public static final String MANIFEST_URL =
        "https://assets.teacharm.moe/bot-workstation/releases/latest.json";
    private static final long MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_INSTALLER_BYTES = 512L * 1024 * 1024;
    private final LogBus log;

    public UpdateService(BotPaths paths, LogBus log) {
        this.log = log;
    }

    public ReleaseInfo check() throws IOException {
        HttpURLConnection connection = open(new URL(MANIFEST_URL), 7_000, 7_000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("版本服务返回 " + connection.getResponseCode());
            JSONObject json;
            try (InputStream input = connection.getInputStream()) {
                json = new JSONObject(new String(readLimited(input, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
            }
            ReleaseInfo release = new ReleaseInfo(
                json.getString("version").trim(), json.getString("url").trim(),
                json.getString("sha256").trim().toLowerCase(Locale.ROOT),
                json.optString("notes", ""), json.optLong("size", -1));
            validate(release);
            return release;
        } finally {
            connection.disconnect();
        }
    }

    public boolean available(ReleaseInfo release) {
        return compareVersions(release.version, CURRENT_VERSION) > 0;
    }

    /** Downloads, verifies and starts the installer. Caller decides when to exit this JVM. */
    public Path downloadAndLaunch(ReleaseInfo release) throws Exception {
        validate(release);
        if (!available(release)) throw new IOException("当前已是最新版");
        Path output = Path.of(System.getProperty("java.io.tmpdir"),
            "BotWorkstation-Setup-" + release.version.replaceAll("[^0-9A-Za-z._-]", "_") + ".exe");
        URL url = URI.create(release.url).toURL();
        HttpURLConnection connection = open(url, 10_000, 30_000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("更新包下载返回 " + connection.getResponseCode());
            long declared = connection.getContentLengthLong();
            if (declared > MAX_INSTALLER_BYTES || (release.size > 0 && declared > 0 && declared != release.size)) {
                throw new IOException("更新包大小与版本清单不一致");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = connection.getInputStream();
                 OutputStream file = Files.newOutputStream(output, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_INSTALLER_BYTES) throw new IOException("更新包超过安全大小限制");
                    digest.update(buffer, 0, read);
                    file.write(buffer, 0, read);
                }
            }
            if (release.size > 0 && total != release.size) throw new IOException("更新包未完整下载");
            String actual = hex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                release.sha256.getBytes(StandardCharsets.US_ASCII))) {
                Files.deleteIfExists(output);
                throw new IOException("更新包校验失败，已拒绝运行");
            }
        } finally {
            connection.disconnect();
        }
        new ProcessBuilder(output.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/CLOSEAPPLICATIONS")
            .directory(output.getParent().toFile()).start();
        log.info("自动更新", "已校验并启动 " + release.version + " 安装程序");
        return output;
    }

    private static HttpURLConnection open(URL url, int connectTimeout, int readTimeout) throws IOException {
        validatePublicUrl(url.toString());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9");
        connection.setRequestProperty("User-Agent", "BotWorkstation/" + CURRENT_VERSION);
        return connection;
    }

    private static void validate(ReleaseInfo release) throws IOException {
        if (!release.version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) throw new IOException("版本号格式无效");
        if (!release.sha256.matches("[0-9a-f]{64}")) throw new IOException("版本清单缺少有效 SHA-256");
        validatePublicUrl(release.url);
        if (!release.url.toLowerCase(Locale.ROOT).endsWith(".exe")) throw new IOException("更新包类型无效");
        if (release.size > MAX_INSTALLER_BYTES) throw new IOException("更新包超过安全大小限制");
    }

    private static void validatePublicUrl(String value) throws IOException {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException error) { throw new IOException("更新地址无效", error); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"assets.teacharm.moe".equalsIgnoreCase(uri.getHost())
            || uri.getRawUserInfo() != null || uri.getPort() != -1
            || !uri.getPath().startsWith("/bot-workstation/releases/")) {
            throw new IOException("更新地址不在受信任的发布域名内");
        }
    }

    static int compareVersions(String left, String right) {
        String[] a = left.split("\\."); String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read; long total = 0;
        while ((read = input.read(buffer)) >= 0) {
            total += read; if (total > limit) throw new IOException("响应过大");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    public static final class ReleaseInfo {
        public final String version;
        public final String url;
        public final String sha256;
        public final String notes;
        public final long size;
        ReleaseInfo(String version, String url, String sha256, String notes, long size) {
            this.version = version; this.url = url; this.sha256 = sha256; this.notes = notes; this.size = size;
        }
        public JSONObject toJson(boolean available) {
            return new JSONObject().put("current", CURRENT_VERSION).put("latest", version)
                .put("available", available).put("notes", notes);
        }
    }
}
