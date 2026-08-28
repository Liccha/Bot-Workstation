package com.botstation.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;

/** Regression seam for Windows processes whose ProcessHandle command line is unavailable. */
public final class ProcessSupervisorRegressionTest {
    private ProcessSupervisorRegressionTest() {}

    public static void main(String[] args) throws Exception {
        String raw = "30008\tC:\\Program Files\\Java\\jdk-11\\bin\\java.exe\t"
            + "java.exe -Dfile.encoding=UTF-8 -classpath C:\\Bot\\classes com.mybot.SongBot";
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        ProcessSupervisor.ProcessSnapshot snapshot = ProcessSupervisor.decodeWindowsProcessLine(encoded);
        require(snapshot != null && snapshot.pid == 30008L, "PID decoded");
        require(ProcessSupervisor.matchesSnapshot(snapshot, "com.mybot.songbot", null),
            "SongBot discovered from Windows fallback snapshot");
        require(!ProcessSupervisor.matchesSnapshot(snapshot, "napcat.mjs", null),
            "unrelated process rejected");
        String napCatRaw = "16796\tC:\\Bot\\NapCat.Shell\\node.exe\t"
            + "node.exe C:\\Bot\\NapCat.Shell\\napcat\\napcat.mjs";
        String napCatEncoded = Base64.getEncoder().encodeToString(napCatRaw.getBytes(StandardCharsets.UTF_8));
        ProcessSupervisor.ProcessSnapshot napCat = ProcessSupervisor.decodeWindowsProcessLine(napCatEncoded);
        require(ProcessSupervisor.matchesSnapshot(napCat, "napcat.mjs", "C:\\Bot\\NapCat.Shell"),
            "NapCat discovered from Windows fallback snapshot");
        require(!ProcessSupervisor.matchesSnapshot(napCat, "napcat.mjs", "C:\\Other\\NapCat.Shell"),
            "wrong NapCat installation rejected");
        verifySongBotUsesDirectJavaLaunch();
        verifyMutableClasspathIsSnapshotted();
        System.out.println("PROCESS_SUPERVISOR_GREEN");
    }

    private static void verifyMutableClasspathIsSnapshotted() throws Exception {
        Path workstation = Files.createTempDirectory("bot-workstation-runtime-");
        Path source = workstation.resolve("Bot工作站.exe");
        Path snapshots = workstation.resolve("config").resolve("service-runtime");
        Files.writeString(source, "version-one", StandardCharsets.UTF_8);

        String firstClasspath = ProcessSupervisor.prepareImmutableClasspath(
            source.toString(), workstation, snapshots);
        Path first = Path.of(firstClasspath);
        require(!first.equals(source), "mutable packaged classpath is replaced with a runtime snapshot");
        require(Files.readString(first, StandardCharsets.UTF_8).equals("version-one"),
            "runtime snapshot preserves the launched version");

        Files.writeString(source, "version-two", StandardCharsets.UTF_8);
        require(Files.readString(first, StandardCharsets.UTF_8).equals("version-one"),
            "updating the workstation cannot mutate an already launched runtime");
        Path second = Path.of(ProcessSupervisor.prepareImmutableClasspath(
            source.toString(), workstation, snapshots));
        require(!second.equals(first), "a changed workstation gets a new immutable runtime");
        require(Files.readString(second, StandardCharsets.UTF_8).equals("version-two"),
            "new launches use the updated workstation version");

        Path installedRoot = Files.createTempDirectory("installed-bot-workstation-");
        Path installedJar = installedRoot.resolve("app").resolve("Bot工作站-core.jar");
        Files.createDirectories(installedJar.getParent());
        Files.writeString(installedJar, "installed-version", StandardCharsets.UTF_8);
        Path installedSnapshot = Path.of(ProcessSupervisor.prepareImmutableClasspath(
            installedJar.toString(), workstation, snapshots));
        require(!installedSnapshot.equals(installedJar),
            "the jpackage application JAR is snapshotted outside the data directory");
        require(Files.readString(installedSnapshot, StandardCharsets.UTF_8).equals("installed-version"),
            "the installed application snapshot preserves its launched version");
    }

    @SuppressWarnings("unchecked")
    private static void verifySongBotUsesDirectJavaLaunch() {
        try {
            java.lang.reflect.Method method = ProcessSupervisor.class.getDeclaredMethod(
                "buildSongBotCommand", Path.class, String.class, Path.class);
            method.setAccessible(true);
            Path java = Path.of("C:\\Runtime\\bin\\javaw.exe");
            String classpath = "C:\\Bot Workstation\\BotWorkstation.jar";
            Path songBotHome = Path.of(System.getProperty("java.io.tmpdir"), "SongBot Data")
                .toAbsolutePath().normalize();
            List<String> command = (List<String>) method.invoke(null, java, classpath, songBotHome);
            require(command.get(0).equals(java.toString()), "SongBot starts through the current Java runtime");
            require(command.contains("-cp") && command.contains(classpath), "current classpath is reused");
            require(command.contains("-Dsongbot.home=" + songBotHome), "absolute SongBot home is explicit");
            require(command.contains("-Dsongbot.database=" + songBotHome.resolve("song_data.db")),
                "absolute song database is explicit");
            require(command.contains("com.botstation.BotStationApp"), "embedded service main class is explicit");
            require(command.contains("--service=songbot"), "SongBot service mode is preserved");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("direct SongBot launch seam is missing", error);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
