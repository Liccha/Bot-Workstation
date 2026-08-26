package com.botstation.core;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class LogBus {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path logDirectory;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public LogBus(Path logDirectory) { this.logDirectory = logDirectory; }

    public synchronized void info(String source, String message) { publish("INFO", source, message); }
    public synchronized void warn(String source, String message) { publish("WARN", source, message); }
    public synchronized void error(String source, String message) { publish("ERROR", source, message); }

    private void publish(String level, String source, String message) {
        String clean = String.valueOf(message).replace('\r', ' ').replace('\n', ' ').trim();
        String full = LocalDateTime.now().format(FULL) + " [" + level + "] [" + source + "] " + clean;
        String display = LocalDateTime.now().format(CLOCK) + "  " + source + "  " + clean;
        history.addLast(display); while (history.size() > 600) history.removeFirst();
        try {
            Files.createDirectories(logDirectory);
            Files.writeString(logDirectory.resolve("workstation-" + LocalDate.now() + ".log"), full + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
        for (Consumer<String> listener : listeners) SwingUtilities.invokeLater(() -> listener.accept(display));
    }

    public synchronized List<String> snapshot() { return new ArrayList<>(history); }
    public void subscribe(Consumer<String> listener) { listeners.add(listener); }
}
