package com.botstation.ui;

import com.botstation.mobile.MobileControlServer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

final class MobilePanel extends JPanel implements AutoCloseable {
    private final MobileControlServer server;
    private final JLabel state = UiKit.muted("未启用");
    private final JLabel address = UiKit.section("—");
    private final JLabel code = UiKit.title("—— —— ——");
    private final JButton start = UiKit.primaryButton("启用手机端");
    private final JButton stop = UiKit.button("停用");
    private final JButton copy = UiKit.button("复制访问地址");

    MobilePanel(MobileControlServer server) {
        super(new BorderLayout(0, 18)); this.server = server;
        setBackground(DesignTokens.PAPER); setBorder(BorderFactory.createEmptyBorder(24, 26, 20, 26));
        add(UiKit.pageHeader("手机端", "同一局域网内配对后，可查看服务状态并操作工作站模块。", null), BorderLayout.NORTH);
        JPanel card = UiKit.card();
        JPanel values = new JPanel(new GridLayout(0, 1, 0, 10)); values.setOpaque(false);
        values.add(UiKit.muted("访问地址"));
        JPanel addressRow = new JPanel(new BorderLayout(12, 0));
        addressRow.setOpaque(false); addressRow.add(address, BorderLayout.CENTER); addressRow.add(copy, BorderLayout.EAST);
        values.add(addressRow);
        values.add(UiKit.muted("一次性配对码")); values.add(code);
        values.add(UiKit.muted("配对码只显示在本机；手机端不保存管理员密码或云端密钥。"));
        card.add(values, BorderLayout.CENTER);
        copy.setEnabled(false);
        copy.addActionListener(event -> copyAddress());
        start.addActionListener(event -> enableServer()); stop.addActionListener(event -> disableServer()); stop.setEnabled(false);
        card.add(UiKit.flow(start, stop, state), BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout()); body.setOpaque(false); body.add(card, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        syncState();
    }

    private void syncState() {
        boolean running = server.isRunning();
        address.setText(running ? server.localUrl() : "—");
        code.setText(running ? format(server.pairCode()) : "—— —— ——");
        state.setText(running ? "已启用" : "未启用");
        start.setEnabled(!running);
        stop.setEnabled(running);
        copy.setEnabled(running);
    }

    private void enableServer() {
        try {
            server.start(); address.setText(server.localUrl()); code.setText(format(server.pairCode()));
            state.setText("已启用"); start.setEnabled(false); stop.setEnabled(true); copy.setEnabled(true);
        } catch (Exception error) {
            state.setText("启用失败");
            javax.swing.JOptionPane.showMessageDialog(this, "手机端启用失败\n" + error.getMessage());
        }
    }
    private void copyAddress() {
        String value = server.localUrl();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
        state.setText("地址已复制");
    }
    private void disableServer() { server.close(); address.setText("—"); code.setText("—— —— ——"); state.setText("未启用"); start.setEnabled(true); stop.setEnabled(false); copy.setEnabled(false); }
    private static String format(String value) { return value.length() == 6 ? value.substring(0, 2) + " " + value.substring(2, 4) + " " + value.substring(4) : value; }
    @Override public void close() { server.close(); }
}
