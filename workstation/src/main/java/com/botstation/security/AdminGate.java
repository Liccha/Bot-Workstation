package com.botstation.security;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;

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
import java.util.function.Consumer;

/** Password gate with cloud-side trusted-IP continuity. It never uploads the password or raw IP. */
public final class AdminGate {
    private final Path passwordFile;
    private final LogBus log;
    private final AdminIpTrustClient ipTrust;
    private final AdminPrompt prompt;
    private AdminSession session;

    public AdminGate(BotPaths paths, LogBus log) {
        this.passwordFile = paths.songBot.resolve("admin_password.txt");
        this.log = log;
        AdminIpTrustClient configured = null;
        try { configured = AdminIpTrustClient.fromSongBot(paths.songBot); }
        catch (Exception error) { log.warn("管理员验证", "可信 IP 客户端未启用：" + error.getMessage()); }
        this.ipTrust = configured;
        this.prompt = new SwingAdminPrompt();
    }

    AdminGate(Path passwordFile, LogBus log, AdminIpTrustClient ipTrust, AdminPrompt prompt) {
        this.passwordFile = passwordFile;
        this.log = log;
        this.ipTrust = ipTrust;
        this.prompt = prompt;
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
                log.warn("管理员验证", "可信身份检查暂不可用，改用云端密码验证：" + error.getMessage());
            }
        }
        return ipTrust == null ? authorizeWithLocalPassword(parent) : authorizeWithCloudPassword(parent);
    }

    /**
     * Performs the cloud trust lookup away from Swing's event thread.  Password
     * dialogs and the completion callback always run on the caller/EDT side.
     */
    public void authorizeAsync(Component parent, TaskRunner tasks, Consumer<Boolean> completion) {
        synchronized (this) {
            if (session != null && session.isAuthorized()) {
                completion.accept(true);
                return;
            }
        }
        if (ipTrust == null) {
            completion.accept(authorizeWithLocalPassword(parent));
            return;
        }
        tasks.run(ipTrust::isTrusted, trusted -> {
            if (trusted) {
                synchronized (AdminGate.this) { session = new AdminSession(AdminGate.this); }
                log.info("管理员验证", "当前公网 IP 已受信任，公告及网站管理已直接解锁");
                completion.accept(true);
            } else {
                authorizeWithCloudPasswordAsync(parent, tasks, completion);
            }
        }, error -> {
            log.warn("管理员验证", "可信身份检查暂不可用，尝试云端密码验证：" + error.getMessage());
            authorizeWithCloudPasswordAsync(parent, tasks, completion);
        });
    }

    /** Warms the trusted-IP session once at startup so a later page click is immediate. */
    public void prewarm(TaskRunner tasks) {
        synchronized (this) {
            if (ipTrust == null || (session != null && session.isAuthorized())) return;
        }
        tasks.run(ipTrust::isTrusted, trusted -> {
            if (!trusted) return;
            synchronized (AdminGate.this) {
                if (session == null) session = new AdminSession(AdminGate.this);
            }
            log.info("管理员验证", "可信 IP 已预验证，公告及网站管理可直接打开");
        }, error -> log.warn("管理员验证", "后台预验证暂不可用：" + error.getMessage()));
    }

    private boolean authorizeWithCloudPassword(Component parent) {
        char[] provided = prompt.requestPassword(parent);
        if (provided == null) return false;
        try {
            boolean accepted = ipTrust.grantWithPassword(provided);
            if (!accepted) {
                log.warn("管理员验证", "密码不匹配；未封禁、未进入公告页面");
                prompt.warning(parent, "验证失败", "密码不正确，公告管理仍处于锁定状态。");
                return false;
            }
            session = new AdminSession(this);
            log.info("管理员验证", "云端验证通过；当前设备已加入管理员可信列表");
            return true;
        } catch (Exception error) {
            log.error("管理员验证", "云端验证失败：" + error.getMessage());
            prompt.error(parent, "管理员服务不可用", "暂时无法完成管理员验证，请稍后重试。");
            return false;
        } finally {
            Arrays.fill(provided, '\0');
        }
    }

    private void authorizeWithCloudPasswordAsync(Component parent, TaskRunner tasks, Consumer<Boolean> completion) {
        char[] provided = prompt.requestPassword(parent);
        if (provided == null) {
            completion.accept(false);
            return;
        }
        tasks.run(() -> {
            try { return ipTrust.grantWithPassword(provided); }
            finally { Arrays.fill(provided, '\0'); }
        }, accepted -> {
            if (!accepted) {
                log.warn("管理员验证", "密码不匹配；未封禁、未进入公告页面");
                prompt.warning(parent, "验证失败", "密码不正确，公告管理仍处于锁定状态。");
                completion.accept(false);
                return;
            }
            synchronized (AdminGate.this) { session = new AdminSession(AdminGate.this); }
            log.info("管理员验证", "云端验证通过；当前设备已加入管理员可信列表");
            completion.accept(true);
        }, error -> {
            Arrays.fill(provided, '\0');
            log.error("管理员验证", "云端验证失败：" + error.getMessage());
            prompt.error(parent, "管理员服务不可用", "暂时无法完成管理员验证，请稍后重试。");
            completion.accept(false);
        });
    }

    private boolean authorizeWithLocalPassword(Component parent) {
        if (!Files.isRegularFile(passwordFile)) {
            prompt.error(parent, "管理员验证不可用",
                "管理员密码文件缺失，公告管理保持锁定。\n" + passwordFile);
            log.error("管理员验证", "密码文件缺失");
            return false;
        }
        char[] providedChars = prompt.requestPassword(parent);
        if (providedChars == null) return false;
        byte[] provided = utf8(providedChars);
        Arrays.fill(providedChars, '\0');
        try {
            byte[] expected = readPasswordBytes(passwordFile);
            boolean okay = expected.length > 0 && MessageDigest.isEqual(expected, provided);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(provided, (byte) 0);
            if (okay) {
                synchronized (this) { session = new AdminSession(this); }
                log.info("管理员验证", "本次工作台会话已通过本机兼容密码解锁");
                return true;
            }
            log.warn("管理员验证", "密码不匹配；未封禁、未进入公告页面");
            prompt.warning(parent, "验证失败", "密码不正确，公告管理仍处于锁定状态。");
            return false;
        } catch (Exception error) {
            Arrays.fill(provided, (byte) 0);
            log.error("管理员验证", "读取失败：" + error.getMessage());
            prompt.error(parent, "验证失败", "无法完成管理员验证：\n" + error.getMessage());
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

    interface AdminPrompt {
        char[] requestPassword(Component parent);
        void warning(Component parent, String title, String message);
        void error(Component parent, String title, String message);
    }

    private static final class SwingAdminPrompt implements AdminPrompt {
        @Override
        public char[] requestPassword(Component parent) {
            JPasswordField field = new JPasswordField(24);
            field.putClientProperty("JTextField.placeholderText", "输入管理员密码");
            JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
            form.setBorder(BorderFactory.createEmptyBorder(5, 4, 2, 4));
            form.add(new JLabel("公告、附件与网站内容属于管理员区域。"));
            form.add(field);
            int result = JOptionPane.showConfirmDialog(parent, form, "验证管理员身份",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                field.setText("");
                return null;
            }
            char[] password = field.getPassword();
            field.setText("");
            return password;
        }

        @Override
        public void warning(Component parent, String title, String message) {
            JOptionPane.showMessageDialog(parent, message, title, JOptionPane.WARNING_MESSAGE);
        }

        @Override
        public void error(Component parent, String title, String message) {
            JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
        }
    }
}
