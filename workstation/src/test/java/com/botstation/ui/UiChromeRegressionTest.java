package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;
import com.botstation.core.TaskRunner;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/** Guards the requested compact chrome and removed placeholder/status content. */
public final class UiChromeRegressionTest {
    private UiChromeRegressionTest() {}

    public static void main(String[] args) throws Exception {
        FlatLightLaf.setup();
        SwingUtilities.invokeAndWait(() -> {
            BotPaths paths = BotPaths.detect();
            LogBus log = new LogBus(paths.logs());
            TaskRunner tasks = new TaskRunner();
            WorkbenchFrame frame = new WorkbenchFrame(paths, log, tasks, new ProcessSupervisor(paths, log));
            try {
                List<String> frameTexts = texts(frame);
                require(frameTexts.contains("Bot 工作站"), "brand is missing");
                require(frameTexts.contains("谱面录入与图片设计"), "renamed module is missing");
                require(frameTexts.contains("公告及网站管理"), "admin module rename is missing");
                require(!frameTexts.contains("运行记录"), "removed log page is still visible");
                require(!frameTexts.contains("一站式机器人中枢"), "removed subtitle is still visible");
                require(frame.getDefaultCloseOperation() == JFrame.DO_NOTHING_ON_CLOSE,
                    "workstation close lifecycle can leak the UI process");
                ToolsPanel tools = new ToolsPanel(paths, log, tasks);
                require(!texts(tools).contains("入口已去重"), "removed placeholder card is still visible");
            } finally {
                frame.dispose();
            }
        });
        System.out.println("UI_CHROME_GREEN");
    }

    private static List<String> texts(Container root) {
        List<String> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Container root, List<String> result) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel && ((JLabel) component).getText() != null) result.add(((JLabel) component).getText());
            if (component instanceof AbstractButton && ((AbstractButton) component).getText() != null) result.add(((AbstractButton) component).getText());
            if (component instanceof Container) collect((Container) component, result);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
