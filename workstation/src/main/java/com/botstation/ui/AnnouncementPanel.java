package com.botstation.ui;

import com.mcz.AnnouncementEditor;
import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.botstation.security.AdminGate;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

final class AnnouncementPanel extends JPanel {
    AnnouncementPanel(BotPaths paths, LogBus log, TaskRunner tasks, AdminGate.AdminSession session) {
        super(new BorderLayout());
        session.requireAuthorized();
        setBackground(DesignTokens.PAPER);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(DesignTokens.BODY_MEDIUM);
        tabs.addTab("公告与附件", new AnnouncementEditor(() -> { }));
        tabs.addTab("网站文章", new WebsiteContentPanel(paths, log, tasks, session));
        add(tabs, BorderLayout.CENTER);
    }
}
