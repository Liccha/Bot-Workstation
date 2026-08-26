package com.botstation.ui;

import com.botstation.mobile.MobileControlServer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.BoxLayout;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class MobilePanel extends JPanel implements AutoCloseable {
    private final MobileControlServer server;
    private final JLabel state = UiKit.muted("未启用");
    private final JLabel address = UiKit.section("—");
    private final JLabel code = UiKit.title("—— —— ——");
    private final JButton start = UiKit.primaryButton("启用手机端");
    private final JButton stop = UiKit.button("停用");
    private final JButton copy = UiKit.button("复制访问地址");
    private final DefaultListModel<String> deviceModel = new DefaultListModel<>();
    private final JList<String> devices = new JList<>(deviceModel);
    private final JButton refreshDevices = UiKit.button("刷新设备");
    private final JButton revokeDevice = UiKit.button("撤销所选设备");
    private List<JSONObject> deviceValues = new ArrayList<>();

    MobilePanel(MobileControlServer server) {
        super(new BorderLayout(0, 18)); this.server = server;
        setBackground(DesignTokens.PAPER); setBorder(BorderFactory.createEmptyBorder(24, 26, 20, 26));
        add(UiKit.pageHeader("手机端", "首次在同一局域网配对；此后设备可跨网络长期使用，直到在这里撤销。", null), BorderLayout.NORTH);
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
        JPanel deviceCard = UiKit.card();
        JPanel deviceHeader = new JPanel(new BorderLayout(10, 0)); deviceHeader.setOpaque(false);
        deviceHeader.add(UiKit.section("已授权的手机设备"), BorderLayout.WEST);
        deviceHeader.add(UiKit.flow(refreshDevices, revokeDevice), BorderLayout.EAST);
        deviceCard.add(deviceHeader, BorderLayout.NORTH);
        devices.setFont(DesignTokens.BODY); devices.setFixedCellHeight(32);
        JScrollPane deviceScroll = new JScrollPane(devices); deviceScroll.setPreferredSize(new Dimension(480, 130));
        deviceCard.add(deviceScroll, BorderLayout.CENTER);
        refreshDevices.addActionListener(event -> refreshCloudDevices());
        revokeDevice.addActionListener(event -> revokeSelectedDevice());
        revokeDevice.setEnabled(false); devices.addListSelectionListener(event -> revokeDevice.setEnabled(!event.getValueIsAdjusting() && devices.getSelectedIndex() >= 0));
        JPanel body = new JPanel(); body.setOpaque(false); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260)); deviceCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        body.add(card); body.add(javax.swing.Box.createVerticalStrut(14)); body.add(deviceCard);
        add(body, BorderLayout.CENTER);
        syncState();
        refreshCloudDevices();
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
    private void refreshCloudDevices() {
        if (!server.cloudRelayConfigured()) {
            deviceModel.clear(); deviceModel.addElement("跨网络设备服务未配置"); refreshDevices.setEnabled(false); revokeDevice.setEnabled(false); return;
        }
        refreshDevices.setEnabled(false); deviceModel.clear(); deviceModel.addElement("正在读取…");
        new SwingWorker<List<JSONObject>, Void>() {
            @Override protected List<JSONObject> doInBackground() throws Exception { return server.cloudDevices(); }
            @Override protected void done() {
                refreshDevices.setEnabled(true); deviceModel.clear();
                try {
                    deviceValues = get();
                    for (JSONObject item : deviceValues) {
                        String date = item.optString("createdAt", ""); if (date.length() >= 10) date = date.substring(0, 10);
                        deviceModel.addElement(item.optString("name", "手机设备") + "  ·  " + date + ("revoked".equals(item.optString("status")) ? "  ·  已撤销" : ""));
                    }
                    if (deviceValues.isEmpty()) deviceModel.addElement("尚无已配对设备");
                } catch (Exception error) { deviceValues = new ArrayList<>(); deviceModel.addElement("读取失败，请稍后重试"); }
            }
        }.execute();
    }
    private void revokeSelectedDevice() {
        int index = devices.getSelectedIndex(); if (index < 0 || index >= deviceValues.size()) return;
        JSONObject selected = deviceValues.get(index);
        if (JOptionPane.showConfirmDialog(this, "撤销后，这台设备将立即失去跨网络访问权。\n确定撤销“" + selected.optString("name", "手机设备") + "”？",
            "撤销设备", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        revokeDevice.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { server.revokeCloudDevice(selected.getString("id")); return null; }
            @Override protected void done() {
                try { get(); refreshCloudDevices(); }
                catch (Exception error) { JOptionPane.showMessageDialog(MobilePanel.this, "撤销失败，请稍后重试", "操作失败", JOptionPane.ERROR_MESSAGE); }
            }
        }.execute();
    }
    private void disableServer() { server.close(); address.setText("—"); code.setText("—— —— ——"); state.setText("未启用"); start.setEnabled(true); stop.setEnabled(false); copy.setEnabled(false); }
    private static String format(String value) { return value.length() == 6 ? value.substring(0, 2) + " " + value.substring(2, 4) + " " + value.substring(4) : value; }
    @Override public void close() { server.close(); }
}
