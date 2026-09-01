package com.mcz;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Secure native client for the same announcement API used by the website. */
final class CloudAnnouncementStore {
    private final String api;
    private final String token;
    private final String device;

    private CloudAnnouncementStore(String api, String token, String device) {
        this.api = api.endsWith("/") ? api.substring(0, api.length() - 1) : api;
        this.token = token;
        this.device = device;
    }

    static boolean isCloudMode(File songBotDir) {
        File file = new File(new File(songBotDir, "data"), "cloud-announcement.properties");
        if (!file.isFile()) return false;
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            props.load(reader);
            return "cloud".equalsIgnoreCase(props.getProperty("backend", ""));
        } catch (IOException ignored) {
            return false;
        }
    }

    static CloudAnnouncementStore fromSongBot(File songBotDir) throws IOException {
        File file = new File(new File(songBotDir, "data"), "cloud-announcement.properties");
        Properties props = new Properties();
        if (!file.isFile()) throw new IOException("未找到云公告配置");
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        if (!"cloud".equalsIgnoreCase(props.getProperty("backend", ""))) {
            throw new IOException("云公告模式尚未启用");
        }
        String api = props.getProperty("api", "").trim();
        String token = props.getProperty("desktopToken", "").trim();
        if (api.isEmpty() || token.isEmpty()) throw new IOException("云公告地址或桌面令牌缺失");
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { host = "desktop"; }
        return new CloudAnnouncementStore(api, token, "mczmaker-" + host.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    java.util.List<Map<String, Object>> load() throws IOException {
        JSONArray array = new JSONArray(request("GET", "?action=list", null));
        java.util.List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null) result.add(toMap(object));
        }
        return result;
    }

    Map<String, Object> save(Map<String, Object> item, boolean create) throws IOException {
        JSONObject payload = new JSONObject(item);
        String suffix = "?action=announcement";
        if (!create) suffix += "&id=" + enc(String.valueOf(item.get("id")));
        return toMap(new JSONObject(request(create ? "POST" : "PUT", suffix, payload.toString())));
    }

    void delete(Map<String, Object> item) throws IOException {
        String suffix = "?action=announcement&id=" + enc(String.valueOf(item.get("id")))
            + "&revision=" + enc(String.valueOf(item.get("revision")));
        request("DELETE", suffix, null);
    }

    String upload(File file, String session, String type) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("附件不存在");
        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null || contentType.trim().isEmpty()) contentType = "application/octet-stream";
        JSONObject ticketRequest = new JSONObject()
            .put("session", session)
            .put("type", "image".equals(type) ? "image" : "attach")
            .put("name", file.getName())
            .put("size", file.length())
            .put("contentType", contentType);
        JSONObject ticket = new JSONObject(request("POST", "?action=upload-ticket", ticketRequest.toString()));
        HttpURLConnection conn = (HttpURLConnection) new URL(ticket.getString("uploadUrl")).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(180000);
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setFixedLengthStreamingMode(file.length());
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        }
        int status = conn.getResponseCode();
        conn.disconnect();
        if (status < 200 || status >= 300) throw new IOException("附件上传失败（HTTP " + status + "）");
        return ticket.getString("token");
    }

    private String request(String method, String suffix, String body) throws IOException {
        int attempts = "GET".equals(method) ? 3 : 1;
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return requestOnce(method, suffix, body);
            } catch (IOException error) {
                last = error;
                if (attempt >= attempts || !isTransient(error)) throw error;
                try { Thread.sleep(attempt == 1 ? 250L : 800L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("云公告读取已取消", interrupted);
                }
            }
        }
        throw last == null ? new IOException("云公告请求失败") : last;
    }

    private String requestOnce(String method, String suffix, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(api + suffix).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Desktop " + token);
        conn.setRequestProperty("X-Admin-Device", device);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = stream == null ? "" : readAll(stream);
        conn.disconnect();
        if (status == 409) throw new IOException("云端公告已被其他管理员修改，请重新载入后再编辑");
        if (status < 200 || status >= 300) throw new IOException("云公告服务请求失败（HTTP " + status + "）");
        return response;
    }

    private static boolean isTransient(IOException error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("connection reset") || message.contains("connection timed out")
            || message.contains("connect timed out") || message.contains("read timed out")
            || message.contains("unexpected end of file") || message.contains("http 429")
            || message.matches(".*http 5\\d\\d.*");
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            Object value = object.opt(key);
            item.put(key, value == null || value == JSONObject.NULL ? "" : String.valueOf(value));
        }
        return item;
    }
}
