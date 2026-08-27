package com.botstation.features;

import java.nio.file.Files;
import java.nio.file.Path;

public final class OperationsSettingsRegressionTest {
    private OperationsSettingsRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("botstation-operations-");
        OperationsSettings settings = new OperationsSettings(root);
        require(!settings.dailyAutomationEnabled(), "daily automation must default to disabled");
        settings.setDailyAutomationEnabled(true);
        require(new OperationsSettings(root).dailyAutomationEnabled(), "enabled state was not persisted");
        settings.setDailyAutomationEnabled(false);
        require(!new OperationsSettings(root).dailyAutomationEnabled(), "disabled state was not persisted");
        System.out.println("OPERATIONS_SETTINGS_GREEN");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
