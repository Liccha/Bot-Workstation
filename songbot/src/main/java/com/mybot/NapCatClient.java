package com.mybot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

public class NapCatClient {
    private static final String DEFAULT_NAPCAT_API_URL = "http://127.0.0.1:3000";
    private static final String NAPCAT_API_URL = resolveApiUrl();
    private static final HttpClient client = HttpClient.newBuilder().build();

    // 【修改点 1】返回值从 void 改为 String
    public String sendGroupMessage(long groupId, String message) {
        if (message == null) message = "";
        String jsonPayload = "{\"group_id\": " + groupId + ", \"message\": \"" + escapeJson(message) + "\"}";
        return sendRequest("/send_group_msg", jsonPayload);
    }

    // 【修改点 2】返回值从 void 改为 String
    public String sendPrivateMessage(long userId, String message) {
        if (message == null) message = "";
        String jsonPayload = "{\"user_id\": " + userId + ", \"message\": \"" + escapeJson(message) + "\"}";
        return sendRequest("/send_private_msg", jsonPayload);
    }

    // --- V94: 获取群成员列表 (保持不变) ---
    public List<long[]> getGroupMemberList(long groupId) {
        List<long[]> list = new ArrayList<>();
        String jsonPayload = "{\"group_id\": " + groupId + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NAPCAT_API_URL + "/get_group_member_list"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"user_id\":(\\d+).*?\"join_time\":(\\d+)");
                java.util.regex.Matcher m = p.matcher(body);

                while (m.find()) {
                    long uid = Long.parseLong(m.group(1));
                    long jTime = Long.parseLong(m.group(2));
                    list.add(new long[]{uid, jTime * 1000L});
                }
            }
        } catch (Exception e) {
            System.err.println("获取群成员列表失败: " + e.getMessage());
        }
        return list;
    }

    // --- V92/V97: 获取陌生人信息 (保持不变) ---
    public String getStrangerInfo(long userId) {
        String jsonPayload = "{\"user_id\": " + userId + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NAPCAT_API_URL + "/get_stranger_info"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                int dataIndex = body.indexOf("\"data\"");
                if (dataIndex != -1) {
                    String key = "\"nickname\":\"";
                    int start = body.indexOf(key, dataIndex);
                    if (start != -1) {
                        start += key.length();
                        int end = body.indexOf("\"", start);
                        if (end != -1) {
                            return body.substring(start, end);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("获取用户信息失败: " + e.getMessage());
        }
        return "未知用户";
    }

    // 【修改点 3】通用发送逻辑：返回值从 void 改为 String，并返回 API 响应体
    private String sendRequest(String endpoint, String jsonPayload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NAPCAT_API_URL + endpoint))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // 无论成功失败，都返回 API 的 JSON 响应（里面包含 message_id）
            if (response.statusCode() != 200) {
                System.err.println("NapCat API 警告 (" + endpoint + "): " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            System.err.println("发送消息错误: " + e.getMessage());
            return null;
        }
    }

    // 【新增】发送群公告 (QQ 原生公告功能)
    public String sendGroupNotice(long groupId, String title, String content) {
        String escapedTitle = escapeJson(title != null ? title : "");
        String escapedContent = escapeJson(content != null ? content : "");
        String json = "{\"group_id\":" + groupId + ",\"title\":\"" + escapedTitle + "\",\"content\":\"" + escapedContent + "\"}";
        return sendRequest("/_send_group_notice", json);
    }

    // 【新增】获取 bot 自身在群内的信息 (判断是否管理员)
    public String getBotGroupRole(long groupId, long selfId) {
        String json = "{\"group_id\":" + groupId + ",\"user_id\":" + selfId + "}";
        return sendRequest("/get_group_member_info", json);
    }

    // 【新增】获取群信息
    public String getGroupInfo(long groupId) {
        String json = "{\"group_id\":" + groupId + "}";
        return sendRequest("/get_group_info", json);
    }

    // 【新增】发送群公告（完整版，支持图片/置顶/确认）
    public String sendGroupNotice(long groupId, String title, String content, String imagePath,
                                   boolean pinned, boolean confirmRequired) {
        StringBuilder sb = new StringBuilder("{\"group_id\":").append(groupId)
            .append(",\"title\":\"").append(escapeJson(title)).append("\"")
            .append(",\"content\":\"").append(escapeJson(content)).append("\"");
        if (imagePath != null && !imagePath.isEmpty())
            sb.append(",\"image\":\"").append(escapeJson(imagePath)).append("\"");
        if (pinned) sb.append(",\"pinned\":1");
        sb.append(",\"confirm_required\":").append(confirmRequired ? 1 : 0);
        sb.append("}");
        return sendRequest("/_send_group_notice", sb.toString());
    }

    // 【新增】上传群文件
    public String uploadGroupFile(long groupId, String filePath, String fileName) {
        String json = "{\"group_id\":" + groupId + ",\"file\":\"" + escapeJson(filePath)
            + "\",\"name\":\"" + escapeJson(fileName) + "\"}";
        return sendRequest("/upload_group_file", json);
    }

    // 辅助：转义 JSON 特殊字符 (保持不变)
    private String escapeJson(String message) {
        return message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Resolve the local endpoint once per SongBot start. The workbench writes only this URL;
     * NapCat tokens remain inside NapCat's own protected configuration and are never copied.
     */
    private static String resolveApiUrl() {
        String property = System.getProperty("napcat.api.url", "").trim();
        if (isSafeLocalUrl(property)) return trimTrailingSlash(property);
        String environment = System.getenv("NAPCAT_API_URL");
        if (environment != null && isSafeLocalUrl(environment.trim())) return trimTrailingSlash(environment.trim());

        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(System.getProperty("user.dir"), "data", "napcat.properties"));
        candidates.add(Paths.get(System.getProperty("user.home"), "Desktop", "SongBot", "data", "napcat.properties"));
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try (java.io.Reader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                Properties values = new Properties();
                values.load(reader);
                String configured = values.getProperty("apiUrl", "").trim();
                if (isSafeLocalUrl(configured)) return trimTrailingSlash(configured);
            } catch (Exception error) {
                System.err.println("NapCat 本地端点配置不可读，使用默认端口: " + error.getMessage());
            }
        }
        return DEFAULT_NAPCAT_API_URL;
    }

    private static boolean isSafeLocalUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean local = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
            return "http".equalsIgnoreCase(uri.getScheme()) && local && uri.getPort() >= 1024
                && uri.getPort() <= 65535 && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (RuntimeException ignored) { return false; }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
