package com.botstation.ui;

import com.botstation.core.NapCatConfigService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

/** Local-only editor for NapCat ports and the SongBot callback. Tokens are never rendered. */
final class NapCatConfigDialog extends JDialog {
    private final NapCatConfigService service;
    private final Runnable changed;
    private final JCheckBox webEnabled = new JCheckBox("启用 WebUI");
    private final JSpinner webPort;
    private final JCheckBox httpEnabled = new JCheckBox("启用 OneBot HTTP");
    private final JSpinner httpPort;
    private final JCheckBox callbackEnabled = new JCheckBox("启用 SongBot 回调");
    private final JTextField callbackUrl = UiKit.field(31);

    static void show(Component parent, NapCatConfigService service, Runnable changed) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        try {
            NapCatConfigDialog dialog = new NapCatConfigDialog(owner, service, service.load(), changed);
            dialog.setVisible(true);
        } catch (Exception error) {
            JOptionPane.showMessageDialog(parent, "无法读取 NapCat 配置\n" + error.getMessage(),
                "连接设置", JOptionPane.ERROR_MESSAGE);
        }
    }

    private NapCatConfigDialog(Window owner, NapCatConfigService service, NapCatConfigService.Snapshot value,
                               Runnable changed) {
        super(owner, "NapCat 连接设置", Dialog.ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.changed = changed;
        this.webPort = spinner(value.webUiPort);
        this.httpPort = spinner(value.httpPort);
        webEnabled.setSelected(value.webUiEnabled);
        httpEnabled.setSelected(value.httpEnabled);
        callbackEnabled.setSelected(value.callbackEnabled);
        callbackUrl.setText(value.callbackUrl);
        build(value);
        pack();
        setMinimumSize(new Dimension(620, getHeight()));
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void build(NapCatConfigService.Snapshot value) {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(DesignTokens.PAPER);
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(UiKit.pageHeader("连接参数", "区分管理界面、消息 API 与本机回调；所有监听均限制在本机。", null), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(section("WebUI", "用于打开 NapCat 管理页面，不是机器人消息端口。",
            webEnabled, webPort, tokenStatus(value.webUiTokenConfigured)));
        body.add(Box.createVerticalStrut(10));
        body.add(section("OneBot HTTP", "SongBot 通过此端口调用发送消息等接口。",
            httpEnabled, httpPort, tokenStatus(value.httpTokenConfigured)));
        body.add(Box.createVerticalStrut(10));
        body.add(callbackSection(value.callbackTokenConfigured));
        root.add(body, BorderLayout.CENTER);

        javax.swing.JButton open = UiKit.button("打开 WebUI");
        open.addActionListener(event -> ToolsPanel.browse("http://127.0.0.1:" + number(webPort) + "/"));
        javax.swing.JButton cancel = UiKit.button("取消");
        cancel.addActionListener(event -> dispose());
        javax.swing.JButton save = UiKit.primaryButton("保存设置");
        save.addActionListener(event -> save());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(UiKit.flow(open), BorderLayout.WEST);
        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false); right.add(cancel); right.add(save);
        buttons.add(right, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel section(String title, String detail, JCheckBox enabled, JSpinner port, JLabel token) {
        JPanel card = UiKit.card();
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints c = constraints();
        JLabel heading = new JLabel(title); heading.setFont(DesignTokens.SECTION); heading.setForeground(DesignTokens.INK);
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; content.add(heading, c);
        c.gridy++; c.insets = new Insets(3, 0, 12, 0); content.add(UiKit.muted(detail), c);
        c.gridwidth = 1; c.gridy++; c.insets = new Insets(0, 0, 0, 16); content.add(enabled, c);
        c.gridx = 1; content.add(label("端口"), c);
        c.gridx = 2; c.weightx = 1; content.add(port, c);
        c.gridx = 0; c.gridy++; c.gridwidth = 3; c.insets = new Insets(10, 0, 0, 0); content.add(token, c);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JPanel callbackSection(boolean tokenConfigured) {
        JPanel card = UiKit.card();
        JPanel content = new JPanel(new GridBagLayout()); content.setOpaque(false);
        GridBagConstraints c = constraints();
        JLabel heading = new JLabel("SongBot 回调"); heading.setFont(DesignTokens.SECTION); heading.setForeground(DesignTokens.INK);
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; content.add(heading, c);
        c.gridy++; c.insets = new Insets(3, 0, 12, 0);
        content.add(UiKit.muted("NapCat 将收到的 QQ 事件发送到 SongBot 的 /webhook。"), c);
        c.gridwidth = 1; c.gridy++; c.insets = new Insets(0, 0, 0, 16); content.add(callbackEnabled, c);
        c.gridx = 1; content.add(label("地址"), c);
        c.gridx = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; content.add(callbackUrl, c);
        c.gridx = 0; c.gridy++; c.gridwidth = 3; c.insets = new Insets(10, 0, 0, 0);
        content.add(tokenStatus(tokenConfigured), c);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private void save() {
        try {
            service.save(new NapCatConfigService.Settings(webEnabled.isSelected(), number(webPort),
                httpEnabled.isSelected(), number(httpPort), callbackEnabled.isSelected(), callbackUrl.getText()));
            if (changed != null) changed.run();
            dispose();
            JOptionPane.showMessageDialog(getOwner(),
                "设置已安全写入。已有 Token 未显示、未替换。\n若 NapCat 正在运行，请停用后重新启用使端口设置生效。",
                "保存成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "无法保存", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JSpinner spinner(int value) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, 1024, 65535, 1));
        spinner.setFont(DesignTokens.BODY);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0");
        editor.getTextField().setFont(DesignTokens.BODY);
        spinner.setEditor(editor);
        spinner.setPreferredSize(new Dimension(104, 36));
        return spinner;
    }

    private static int number(JSpinner spinner) { return ((Number) spinner.getValue()).intValue(); }

    private static JLabel tokenStatus(boolean configured) {
        JLabel label = UiKit.muted("Token：" + (configured ? "已配置（为安全不回显）" : "未配置"));
        label.setForeground(configured ? DesignTokens.SUCCESS : DesignTokens.WARNING);
        return label;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text); label.setFont(DesignTokens.BODY_MEDIUM); label.setForeground(DesignTokens.INK); return label;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints value = new GridBagConstraints();
        value.anchor = GridBagConstraints.WEST;
        value.fill = GridBagConstraints.NONE;
        return value;
    }
}
