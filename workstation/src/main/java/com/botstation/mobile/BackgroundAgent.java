package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

/** Headless, per-user agent that survives closing the workstation window. */
public final class BackgroundAgent {
    private BackgroundAgent() {}

    public static int run(BotPaths paths) {
        LogBus log = new LogBus(paths.logs());
        try {
            Files.createDirectories(paths.config());
            Path lockPath = paths.config().resolve("background-agent.lock");
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException error) { lock = null; }
            if (lock == null) {
                channel.close();
                return 0;
            }

            ProcessSupervisor services = new ProcessSupervisor(paths, log);
            MobileControlServer control = new MobileControlServer(paths, log, services, ignored -> {});
            CloudLibrarySync libraries = CloudLibrarySync.fromSongBot(paths, log);
            control.startCloudRelay();
            if (libraries != null) libraries.start();
            log.info("后台代理", "已启用；主界面关闭后仍可同步和执行手机端控制");

            CountDownLatch stopped = new CountDownLatch(1);
            FileLock heldLock = lock;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (libraries != null) libraries.close();
                control.close();
                try { heldLock.release(); } catch (Exception ignored) {}
                try { channel.close(); } catch (Exception ignored) {}
                stopped.countDown();
            }, "bot-background-agent-shutdown"));
            stopped.await();
            return 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception error) {
            log.error("后台代理", safeMessage(error));
            return 1;
        }
    }

    public static void ensureRunning(BotPaths paths, LogBus log) {
        Path executable = paths.executable();
        if (!Files.isRegularFile(executable)) {
            log.warn("后台代理", "未找到工作站启动程序，当前开发运行不会注册常驻代理");
            return;
        }
        try {
            new ProcessBuilder(executable.toString(), "--background-agent")
                .directory(paths.workstation.toFile())
                .start();
        } catch (IOException error) {
            log.warn("后台代理", "启动失败：" + safeMessage(error));
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "操作失败" : String.valueOf(error.getMessage());
        value = value.replaceAll("[\\r\\n]", " ");
        return value.isBlank() ? "操作失败" : value.substring(0, Math.min(240, value.length()));
    }
}
