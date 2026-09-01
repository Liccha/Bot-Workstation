package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A clean external installation must not depend on the developer's Desktop/MczMaker directory. */
public final class PortableMczWorkspaceRegressionTest {
    private PortableMczWorkspaceRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path externalHome = Files.createTempDirectory("external-bot-workstation-");
        System.setProperty("botstation.home", externalHome.resolve("installed-app").toString());
        System.setProperty("botstation.mcz.home", externalHome.resolve("missing-mczmaker").toString());
        TaskRunner tasks = new TaskRunner();
        try {
            MczWorkspacePanel panel = new MczWorkspacePanel(BotPaths.detect(), new LogBus(externalHome.resolve("logs")), tasks);
            List<String> texts = new ArrayList<>(); collect(panel, texts);
            require(texts.stream().noneMatch(text -> text.contains("未找到 MczMaker 组件")),
                "clean external installation still requires a hard-coded MczMaker folder");
            require(texts.stream().noneMatch(text -> text.contains("BOT_WORKSTATION_MCZ")),
                "public UI still asks users to configure a developer-only path");
            System.out.println("PORTABLE_MCZ_WORKSPACE_GREEN");
        } finally {
            tasks.close();
            System.clearProperty("botstation.home");
            System.clearProperty("botstation.mcz.home");
        }
    }

    private static void collect(Component component, List<String> texts) {
        if (component instanceof JLabel) texts.add(((JLabel) component).getText());
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) collect(child, texts);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
