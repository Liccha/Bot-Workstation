package com.botstation.core;

import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Keeps filesystem, network and process work off Swing's event thread. */
public final class TaskRunner implements AutoCloseable {
    private final AtomicInteger ids = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "bot-workstation-task-" + ids.incrementAndGet());
        thread.setDaemon(true); return thread;
    });

    public <T> void run(Callable<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        pool.submit(() -> {
            try {
                T value = task.call();
                SwingUtilities.invokeLater(() -> success.accept(value));
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> failure.accept(error));
            }
        });
    }

    public void execute(Runnable task) { pool.submit(task); }
    @Override public void close() { pool.shutdownNow(); }
}
