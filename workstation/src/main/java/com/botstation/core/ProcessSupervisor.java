package com.botstation.core;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class ProcessSupervisor {
    private final BotPaths paths;
    private final LogBus log;
    private final NapCatConfigService napCatConfig;

    public ProcessSupervisor(BotPaths paths, LogBus log) {
        this.paths = paths;
        this.log = log;
        this.napCatConfig = new NapCatConfigService(paths);
    }

    public ServiceState songBotState() {
        boolean port = tcpOpen(8080, 250);
        if (port) return ServiceState.RUNNING;
        boolean process = !songBotProcesses().isEmpty();
        if (process) return ServiceState.DEGRADED;
        return ServiceState.STOPPED;
    }

    public ServiceState napCatState() {
        boolean port = tcpOpen(napCatConfig.oneBotHttpPortOrDefault(), 250);
        if (port) return ServiceState.RUNNING;
        boolean process = findProcesses("napcat.mjs", paths.napCat.toString()).size() > 0;
        if (process) return ServiceState.DEGRADED;
        return ServiceState.STOPPED;
    }

    public void startSongBot() throws Exception {
        if (songBotState() == ServiceState.RUNNING) { log.info("SongBot", "已经在运行，无需重复启动"); return; }
        if (!Files.isDirectory(paths.songBot)) {
            throw new IOException("未找到 SongBot 组件目录：" + paths.songBot
                + "。请复制完整 SongBot 文件夹，或设置 BOT_WORKSTATION_SONGBOT。");
        }
        Files.createDirectories(paths.logs());
        // Never launch the GUI wrapper again for service mode. Launch4j/jpackage
        // wrappers may reset the child working directory to their own location,
        // which makes SongBot open an empty relative song_data.db beside the
        // workstation. Starting the already-loaded classpath through Java keeps
        // ProcessBuilder.directory(songBot) authoritative for every relative asset.
        String classpath = absoluteClasspath(System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(buildSongBotCommand(paths.javaExecutable(), classpath, paths.songBot));
        builder.directory(paths.songBot.toFile());
        // Keep the embedded SongBot aligned with the currently active OneBot HTTP port.
        // Only a loopback URL is passed; no NapCat token is copied or exposed.
        builder.environment().put("NAPCAT_API_URL",
            "http://127.0.0.1:" + napCatConfig.oneBotHttpPortOrDefault());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logs().resolve("songbot-console.log").toFile()));
        builder.start(); log.info("SongBot", "启动命令已提交，正在等待 8080 端口");
        waitForPort(8080, Duration.ofSeconds(25));
    }

    static List<String> buildSongBotCommand(Path javaExecutable, String classpath, Path songBotHome) {
        Path home = songBotHome.toAbsolutePath().normalize();
        return List.of(javaExecutable.toString(),
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
            "-Dsongbot.home=" + home,
            "-Dsongbot.database=" + home.resolve("song_data.db"),
            "-cp", classpath,
            "com.botstation.BotStationApp",
            "--service=songbot");
    }

    private static String absoluteClasspath(String classpath) {
        Path base = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        String[] entries = classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1);
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isBlank()) continue;
            Path entry = Path.of(entries[i]);
            if (!entry.isAbsolute()) entries[i] = base.resolve(entry).normalize().toString();
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    public void stopSongBot() throws Exception {
        stopHandles(songBotProcesses(), "SongBot");
        waitForPortClosed(8080, Duration.ofSeconds(12));
    }

    public void startNapCat() throws Exception {
        if (napCatState() == ServiceState.RUNNING) { log.info("NapCat", "已经在运行，无需重复启动"); return; }
        Path launcher = paths.napCat.resolve("napcat.bat");
        if (!Files.isRegularFile(launcher)) throw new IOException("未找到 " + launcher);
        Files.createDirectories(paths.logs());
        new ProcessBuilder("cmd.exe", "/d", "/c", launcher.toString()).directory(paths.napCat.toFile())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logs().resolve("napcat-console.log").toFile())).start();
        int port = napCatConfig.oneBotHttpPortOrDefault();
        log.info("NapCat", "启动命令已提交，正在等待 OneBot HTTP " + port + " 端口");
        waitForPort(port, Duration.ofSeconds(35));
    }

    public void stopNapCat() throws Exception { stopMatched("napcat", paths.napCat.toString(), "NapCat"); }

    public void startAll() throws Exception { startNapCat(); startSongBot(); }
    public void stopAll() throws Exception { stopSongBot(); stopNapCat(); }

    private void stopMatched(String marker, String requiredPath, String label) throws Exception {
        stopHandles(findProcesses(marker, requiredPath), label);
    }

    private void stopHandles(List<ProcessHandle> handles, String label) throws Exception {
        if (handles.isEmpty()) { log.info(label, "当前没有运行实例"); return; }
        List<ProcessHandle> all = new ArrayList<>();
        for (ProcessHandle handle : handles) { handle.descendants().forEach(all::add); all.add(handle); }
        all.stream().distinct().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline && all.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(150);
        all.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        long forceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < forceDeadline && all.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(100);
        if (all.stream().anyMatch(ProcessHandle::isAlive)) throw new IOException(label + " 进程未能完全退出");
        log.info(label, "已停止 " + handles.size() + " 个主进程");
    }

    private List<ProcessHandle> songBotProcesses() {
        List<ProcessHandle> matches = new ArrayList<>(findProcesses("com.mybot.songbot", null));
        for (ProcessHandle process : findProcesses("--service=songbot", null)) {
            if (matches.stream().noneMatch(existing -> existing.pid() == process.pid())) matches.add(process);
        }
        return matches;
    }

    private List<ProcessHandle> findProcesses(String marker, String requiredPath) {
        List<ProcessHandle> matches = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(process -> {
            String commandLine = process.info().commandLine().orElse("");
            ProcessSnapshot snapshot = new ProcessSnapshot(process.pid(),
                process.info().command().orElse(""), commandLine);
            if (matchesSnapshot(snapshot, marker, requiredPath)) matches.add(process);
        });
        if (!matches.isEmpty() || !isWindows()) return matches;

        // Windows may hide a Java 11 process command line from a Java 21 ProcessHandle.
        // Querying CIM is the narrow fallback that lets us identify an already-running
        // SongBot by its real command line before sending any termination signal.
        for (ProcessSnapshot snapshot : windowsProcessSnapshots()) {
            if (!matchesSnapshot(snapshot, marker, requiredPath)) continue;
            ProcessHandle.of(snapshot.pid).ifPresent(handle -> {
                if (matches.stream().noneMatch(existing -> existing.pid() == handle.pid())) matches.add(handle);
            });
        }
        return matches;
    }

    static final class ProcessSnapshot {
        final long pid;
        final String executablePath;
        final String commandLine;

        ProcessSnapshot(long pid, String executablePath, String commandLine) {
            this.pid = pid;
            this.executablePath = executablePath == null ? "" : executablePath;
            this.commandLine = commandLine == null ? "" : commandLine;
        }
    }

    static boolean matchesSnapshot(ProcessSnapshot snapshot, String marker, String requiredPath) {
        String value = (snapshot.executablePath + " " + snapshot.commandLine).toLowerCase(Locale.ROOT);
        String needle = marker.toLowerCase(Locale.ROOT);
        String pathNeedle = requiredPath == null ? null : requiredPath.toLowerCase(Locale.ROOT);
        return value.contains(needle) && (pathNeedle == null || value.contains(pathNeedle));
    }

    static ProcessSnapshot decodeWindowsProcessLine(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\t", 3);
            if (parts.length != 3) return null;
            return new ProcessSnapshot(Long.parseLong(parts[0]), parts[1], parts[2]);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<ProcessSnapshot> windowsProcessSnapshots() {
        List<ProcessSnapshot> snapshots = new ArrayList<>();
        String script = "$ErrorActionPreference='Stop'; Get-CimInstance Win32_Process | ForEach-Object { "
            + "$v=$_.ProcessId.ToString()+[char]9+($_.ExecutablePath -as [string])+[char]9+($_.CommandLine -as [string]); "
            + "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($v)) }";
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", script).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ProcessSnapshot snapshot = decodeWindowsProcessLine(line);
                    if (snapshot != null) snapshots.add(snapshot);
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception ignored) {
            // State detection can still rely on the service port. Stop will report a
            // still-open port instead of risking termination of an unverified process.
        }
        return snapshots;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean tcpOpen(int port, int timeoutMillis) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMillis); return true; }
        catch (IOException ignored) { return false; }
    }
    private static void waitForPort(int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) { if (tcpOpen(port, 300)) return; Thread.sleep(250); }
        throw new IOException("端口 " + port + " 未在规定时间内开始监听");
    }
    private static void waitForPortClosed(int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) { if (!tcpOpen(port, 200)) return; Thread.sleep(250); }
        throw new IOException("端口 " + port + " 仍被占用");
    }
}
