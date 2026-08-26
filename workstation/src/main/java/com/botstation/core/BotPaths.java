package com.botstation.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** All production paths live here so UI code never scatters hard-coded locations. */
public final class BotPaths {
    public final Path desktop;
    public final Path workstation;
    public final Path songBot;
    public final Path mczMaker;
    public final Path napCat;
    public final Path stableWorkbook;
    public final Path stableCsv;
    public final Path songDatabase;
    public final Path stableGrabber;
    public final String editorUrl;

    private BotPaths(Path desktop, Path workstation) {
        this.desktop = desktop;
        this.workstation = workstation;
        this.songBot = component("botstation.songbot.home", "BOT_WORKSTATION_SONGBOT",
            workstation.resolve("components").resolve("SongBot"), desktop.resolve("SongBot"));
        this.mczMaker = component("botstation.mcz.home", "BOT_WORKSTATION_MCZ",
            workstation.resolve("components").resolve("MczMaker"), desktop.resolve("MczMaker"));
        this.napCat = component("botstation.napcat.home", "BOT_WORKSTATION_NAPCAT",
            workstation.resolve("components").resolve("NapCat.Shell"), desktop.resolve("NapCat.Shell"));
        this.stableGrabber = componentFile("botstation.stable.exe", "BOT_WORKSTATION_STABLE_EXE",
            workstation.resolve("components").resolve("rm_stable_info").resolve("rm_stable_info.exe"),
            desktop.resolve("rm_stable_info").resolve("rm_stable_info.exe"));
        this.stableWorkbook = componentFile("botstation.stable.workbook", "BOT_WORKSTATION_STABLE_WORKBOOK",
            workstation.resolve("data").resolve("stable_info.xlsx"), desktop.resolve("stable_info.xlsx"));
        this.stableCsv = songBot.resolve("stable_info.csv");
        this.songDatabase = songBot.resolve("song_data.db");
        this.editorUrl = "https://editor.teacharm.moe";
    }

    public static BotPaths detect() {
        Path desktop = Paths.get(System.getProperty("user.home"), "Desktop").toAbsolutePath().normalize();
        Path workstation = explicitPath("botstation.home", "BOT_WORKSTATION_HOME");
        if (workstation == null) {
            String packagedLauncher = System.getProperty("jpackage.app-path", "").trim();
            if (!packagedLauncher.isEmpty()) workstation = Paths.get(packagedLauncher).toAbsolutePath().normalize().getParent();
        }
        if (workstation == null) {
            Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            if (Files.isRegularFile(current.resolve("Bot工作站.exe")) || Files.isDirectory(current.resolve("config"))) {
                workstation = current;
            }
        }
        if (workstation == null) workstation = desktop.resolve("Bot工作站");
        return new BotPaths(desktop, workstation);
    }

    private static Path component(String property, String environment, Path bundled, Path legacy) {
        Path explicit = explicitPath(property, environment);
        if (explicit != null) return explicit;
        return Files.isDirectory(bundled) ? bundled : legacy;
    }

    private static Path componentFile(String property, String environment, Path bundled, Path legacy) {
        Path explicit = explicitPath(property, environment);
        if (explicit != null) return explicit;
        return Files.isRegularFile(bundled) ? bundled : legacy;
    }

    private static Path explicitPath(String property, String environment) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) value = System.getenv(environment);
        if (value == null || value.trim().isEmpty()) return null;
        return Paths.get(value.trim()).toAbsolutePath().normalize();
    }

    public Path executable() { return workstation.resolve("Bot工作站.exe"); }
    public Path javaExecutable() {
        String home = System.getProperty("java.home");
        return Paths.get(home, "bin", isWindows() ? "javaw.exe" : "java");
    }
    public Path logs() { return workstation.resolve("logs"); }
    public Path config() { return workstation.resolve("config"); }
    public static boolean isWindows() { return File.separatorChar == '\\'; }
}
