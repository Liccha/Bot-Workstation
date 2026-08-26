package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;

/** Localhost-only visual QA harness; never included in the packaged application. */
public final class MobilePreviewServer {
    public static void main(String[] args) throws Exception {
        System.setProperty("botstation.mobile.bind", "127.0.0.1");
        BotPaths paths = BotPaths.detect(); LogBus log = new LogBus(paths.logs());
        MobileControlServer server = new MobileControlServer(paths, log, new ProcessSupervisor(paths, log), ignored -> {});
        server.start(); Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
