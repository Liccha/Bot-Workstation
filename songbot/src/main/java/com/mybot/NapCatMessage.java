package com.mybot;

public class NapCatMessage {
    // 关键字段：手动解析只获取最基本的信息
    public long group_id;
    public String message;

    public String getMessage() { return message; }
    public long getGroupId() { return group_id; }
    // 其他字段如 self_id, user_id, message_type 等需在 SongBot 中手动解析
}