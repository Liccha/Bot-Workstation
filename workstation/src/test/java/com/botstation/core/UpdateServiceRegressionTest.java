package com.botstation.core;

import java.nio.file.Path;
import java.util.List;

public final class UpdateServiceRegressionTest {
    private UpdateServiceRegressionTest() {}
    public static void main(String[] args) {
        require(UpdateService.compareVersions("1.1.1", "1.1.0") > 0, "patch version compare failed");
        require(UpdateService.compareVersions("1.2", "1.1.99") > 0, "minor version compare failed");
        require(UpdateService.compareVersions("1.1.0", "1.1") == 0, "normalized version compare failed");
        List<String> arguments = UpdateService.installerArguments(Path.of("C:/Temp/Bot Workstation/update-install.log"));
        require(arguments.contains("/FORCECLOSEAPPLICATIONS"), "updater must release locked runtime files");
        require(arguments.contains("/LOGCLOSEAPPLICATIONS"), "updater must log lock detection");
        require(arguments.contains("/NORESTARTAPPLICATIONS"), "updater must not restart the old executable");
        require(arguments.contains("/NORESTART"), "silent updater must never restart Windows itself");
        require(arguments.stream().anyMatch(value -> value.startsWith("/LOG=\"")), "updater log path missing");
        require(arguments.stream().noneMatch("/SUPPRESSMSGBOXES"::equals),
            "critical install errors must remain visible instead of flashing a rollback page");
        ProcessBuilder deferred = UpdateService.deferredInstaller(
            Path.of("C:/Temp/BotWorkstation-Setup.exe"), Path.of("C:/Temp/update-install.log"), 1234L);
        require(deferred.command().contains("Hidden"), "deferred helper must not open a console window");
        require(deferred.command().stream().anyMatch(value -> value.contains("Wait-Process")),
            "installer must wait for the old workstation process to exit before copying files");
        System.out.println("UPDATE_SERVICE_GREEN");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
