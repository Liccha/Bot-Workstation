package com.botstation;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;
import com.botstation.core.TaskRunner;
import com.botstation.ui.WorkbenchFrame;
import com.formdev.flatlaf.FlatLightLaf;
import com.mybot.SongBot;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.charset.StandardCharsets;

public final class BotStationApp {
    private BotStationApp() {}

    public static void main(String[] args) {
        if (hasArg(args, "--service=songbot")) {
            System.setProperty("file.encoding", StandardCharsets.UTF_8.name());
            try {
                SongBot.main(new String[0]);
            } catch (Exception error) {
                error.printStackTrace();
                System.exit(1);
            }
            return;
        }

        if (hasArg(args, "--self-test")) {
            System.exit(SelfTest.run(BotPaths.detect()));
            return;
        }

        if (hasArg(args, "--mcz-embed-self-test")) {
            System.exit(MczEmbedSelfTest.run(BotPaths.detect()));
            return;
        }

        FlatLightLaf.setup();
        configureLookAndFeel();
        SwingUtilities.invokeLater(() -> {
            BotPaths paths = BotPaths.detect();
            LogBus log = new LogBus(paths.logs());
            TaskRunner tasks = new TaskRunner();
            ProcessSupervisor services = new ProcessSupervisor(paths, log);
            WorkbenchFrame frame = new WorkbenchFrame(paths, log, tasks, services);
            frame.setVisible(true);
        });
    }

    private static void configureLookAndFeel() {
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("ScrollBar.width", 8);
        UIManager.put("ScrollBar.thumbArc", 8);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabType", "card");
    }

    private static boolean hasArg(String[] args, String expected) {
        for (String arg : args) if (expected.equalsIgnoreCase(arg)) return true;
        return false;
    }
}
