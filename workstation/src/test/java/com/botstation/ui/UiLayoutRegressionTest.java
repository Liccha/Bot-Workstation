package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;
import com.botstation.core.TaskRunner;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executable regression seam for the dashboard clipping reported on 2026-08-25. */
public final class UiLayoutRegressionTest {
    private UiLayoutRegressionTest() {}

    public static void main(String[] args) throws Exception {
        FlatLightLaf.setup();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            TaskRunner tasks = new TaskRunner();
            try {
                verifyButtonMetrics();
                verifyHeaderActionMetrics();
                verifyDashboardDescriptions(tasks);
                System.out.println("UI_LAYOUT_GREEN");
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                tasks.close();
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.exit(0);
    }

    private static void verifyButtonMetrics() {
        for (String text : new String[]{"全部启用", "打开记录", "打开曲库", "打开 Stable", "进入管理", "进入运营", "连接设置"}) {
            JButton button = UiKit.button(text);
            FontMetrics metrics = button.getFontMetrics(button.getFont());
            Insets insets = button.getInsets();
            int requiredWidth = metrics.stringWidth(text) + insets.left + insets.right + 8;
            int requiredHeight = metrics.getHeight() + insets.top + insets.bottom;
            require(button.getPreferredSize().width >= requiredWidth,
                text + " button width " + button.getPreferredSize().width + " < " + requiredWidth);
            require(button.getPreferredSize().height >= requiredHeight,
                text + " button height " + button.getPreferredSize().height + " < " + requiredHeight);
        }
    }

    private static void verifyHeaderActionMetrics() {
        JButton action = UiKit.primaryButton("全部启用");
        JPanel header = UiKit.pageHeader("运行总览", "查看运行状态，或直接打开所需模块。", action);
        header.setSize(900, 64);
        layoutRecursively(header);
        require(action.getHeight() == action.getPreferredSize().height,
            "header action stretched to " + action.getHeight() + " from " + action.getPreferredSize().height);
    }

    private static void verifyDashboardDescriptions(TaskRunner tasks) {
        BotPaths paths = BotPaths.detect();
        LogBus log = new LogBus(paths.logs());
        ProcessSupervisor services = new ProcessSupervisor(paths, log);
        DashboardPanel dashboard = new DashboardPanel(paths, log, tasks, services,
            () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
        try {
            dashboard.setSize(1140, 720);
            layoutRecursively(dashboard);

            List<JLabel> labels = new ArrayList<>();
            collect(dashboard, JLabel.class, labels);
            for (String expected : new String[]{
                "谱面、Combo、音频、波形、封面与日历模板。",
                "查询和修改 SongBot 歌曲元数据。",
                "维护 XLSX 并同步 CSV 与数据库。",
                "编辑云端公告、图片、附件和网站文章。",
                "管理每日推荐、竞猜、正式站和 Stable 抓取。"}) {
                JLabel label = labels.stream().filter(item -> expected.equals(item.getText())).findFirst()
                    .orElseThrow(() -> new AssertionError("missing dashboard description: " + expected));
                require(label.getHeight() >= label.getPreferredSize().height,
                    expected + " label height " + label.getHeight() + " < " + label.getPreferredSize().height);
            }
        } finally {
            dashboard.stopRefresh();
        }
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutRecursively((Container) child);
        }
    }

    private static <T> void collect(Container root, Class<T> type, List<T> output) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) output.add(type.cast(component));
            if (component instanceof Container) collect((Container) component, type, output);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
