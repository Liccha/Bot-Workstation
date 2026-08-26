package com.mcz;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * NapCat QQ 机器人集成 — 定时公告调度、公告/消息发送
 */
public class NapCatManager {

    public static String esc(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }

public static String sendNotice(long groupId, String title, String content,
                                     String imagePath, boolean pin, boolean confirm) {
        try {
            StringBuilder pl = new StringBuilder("{\"group_id\":").append(groupId)
                .append(",\"title\":\"").append(esc(title)).append("\"")
                .append(",\"content\":\"").append(esc(content)).append("\"");
            if (imagePath != null && !imagePath.isEmpty() && new File(imagePath).exists())
                pl.append(",\"image\":\"").append(esc(imagePath)).append("\"");
            if (confirm) pl.append(",\"confirm_required\":1");
            if (pin) pl.append(",\"pinned\":1");
            pl.append("}");
            URL u = new URL("http://127.0.0.1:3000/_send_group_notice");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(pl.toString().getBytes(StandardCharsets.UTF_8));
            String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.getResponseCode();
            return "已发送(" + (confirm?"需确认,":"") + (pin?"置顶,":"") + "): " + title + " → " + resp;
        } catch (Exception ex) {
            return "发送失败: " + ex.getMessage();
        }
    }

    public static String sendMsg(long groupId, String message) {
        try {
            URL u = new URL("http://127.0.0.1:3000/send_group_msg");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            String payload = "{\"group_id\":" + groupId + ",\"message\":\"" + esc(message) + "\"}";
            conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return "附件发送响应: " + resp;
        } catch (Exception ex) {
            return "附件发送失败: " + ex.getMessage();
        }
    }

}
