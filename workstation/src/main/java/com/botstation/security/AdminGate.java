package com.botstation.security;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.Component;
import java.awt.GridLayout;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Password gate with cloud-side trusted-IP continuity. It never uploads the password or raw IP. */
public final class AdminGate {
    private final Path passwordFile;
    private final LogBus log;
    private final AdminIpTrustClient ipTrust;
    private AdminSession session;

    public AdminGate(BotPaths paths, LogBus log) {
        this.passwordFile = paths.songBot.resolve("admin_password.txt");
        this.log = log;
        AdminIpTrustClient configured = null;
        try { configured = AdminIpTrustClient.fromSongBot(paths.songBot); }
        catch (Exception error) { log.warn("管理员验证", "可信 IP 客户端未启用：" + error.getMessage()); }
        this.ipTrust = configured;
    }

    public synchronized boolean authorize(Component parent) {
        if (session != null && session.isAuthorized()) return true;
        if (ipTrust != null) {
            try {
                if (ipTrust.isTrusted()) {
                    session = new AdminSession(this);
                    log.info("管理员验证", "当前公网 IP 已受信任，公告及网站管理已直接解锁");
                    return true;
                }
            } catch (Exception error) {
                log.warn("管理员验证", "可信 IP 检查暂不可用，改用本机密码：" + error.getMessage());
            }
        }
        if (!Files.isRegularFile(passwordFile)) {
            JOptionPane.showMessageDialog(parent, "管理员密码文件缺失，公告管理保持锁定。\n" + passwordFile,
                "管理员验证不可用", JOptionPane.ERROR_MESSAGE);
            log.error("管理员验证", "密码文件缺失");
            return false;
        }
        JPasswordField field = new JPasswordField(24);
        field.putClientProperty("JTextField.placeholderText", "输入管理员密码");
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
        form.setBorder(BorderFactory.createEmptyBorder(5, 4, 2, 4));
        form.add(new JLabel("公告、附件与网站内容属于管理员区域。"));
        form.add(field);
        int result = JOptionPane.showConfirmDialog(parent, form, "验证管理员身份",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) { clear(field); return false; }
        char[] providedChars = field.getPassword();
        byte[] provided = utf8(providedChars);
        Arrays.fill(providedChars, '\0');
        clear(field);
        try {
            byte[] expected = readPasswordBytes(passwordFile);
            boolean okay = expected.length > 0 && MessageDigest.isEqual(expected, provided);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(provided, (byte) 0);
            if (okay) {
                session = new AdminSession(this);
                if (ipTrust != null) {
                    try {
                        ipTrust.grant();
                        log.info("管理员验证", "密码正确；当前公网 IP 已永久加入云端可信列表");
                    } catch (Exception error) {
                        log.warn("管理员验证", "本次会话已解锁，但可信 IP 保存失败：" + error.getMessage());
                        JOptionPane.showMessageDialog(parent,
                            "本次会话已解锁，但当前 IP 没有保存到云端。\n下次启动可能仍需输入密码。",
                            "可信 IP 未保存", JOptionPane.WARNING_MESSAGE);
                    }
                } else {
                    log.info("管理员验证", "本次工作台会话已解锁；可信 IP 服务未配置");
                }
                return true;
            }
            log.warn("管理员验证", "密码不匹配；未封禁、未进入公告页面");
            JOptionPane.showMessageDialog(parent, "密码不正确，公告管理仍处于锁定状态。", "验证失败", JOptionPane.WARNING_MESSAGE);
            return false;
        } catch (Exception error) {
            Arrays.fill(provided, (byte) 0);
            log.error("管理员验证", "读取失败：" + error.getMessage());
            JOptionPane.showMessageDialog(parent, "无法完成管理员验证：\n" + error.getMessage(), "验证失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** Returns the capability only after a successful password check in this process. */
    public synchronized AdminSession requireCurrent() {
        if (session == null || !session.isAuthorized()) {
            throw new SecurityException("管理员会话未授权");
        }
        return session;
    }

    public synchronized boolean isAuthorized() {
        return session != null && session.isAuthorized();
    }

    private synchronized boolean owns(AdminSession candidate) {
        return candidate != null && candidate == session;
    }

    private static byte[] utf8(char[] characters) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(characters));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }

    private static byte[] readPasswordBytes(Path path) throws Exception {
        byte[] raw = Files.readAllBytes(path);
        int start = 0;
        int end = raw.length;
        while (start < end && isAsciiWhitespace(raw[start])) start++;
        while (end > start && isAsciiWhitespace(raw[end - 1])) end--;
        byte[] trimmed = Arrays.copyOfRange(raw, start, end);
        Arrays.fill(raw, (byte) 0);
        return trimmed;
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    /**
     * Unforgeable in-process capability for administrator-only pages and clients.
     * Its constructor is private and every use is checked against the issuing gate.
     */
    public static final class AdminSession {
        private final AdminGate owner;

        private AdminSession(AdminGate owner) { this.owner = owner; }

        public boolean isAuthorized() { return owner.owns(this); }

        public void requireAuthorized() {
            if (!isAuthorized()) throw new SecurityException("管理员会话无效");
        }
    }

    private static void clear(JPasswordField field) { field.setText(""); }
}
