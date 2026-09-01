package com.botstation.features;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Small persisted switches shared with the independently running SongBot process. */
public final class OperationsSettings {
    private static final String DAILY_AUTOMATION = "dailyPushAutomationEnabled";
    private final Path file;

    public OperationsSettings(Path songBotHome) {
        this.file = songBotHome.resolve("data").resolve("operations.properties").toAbsolutePath().normalize();
    }

    public synchronized boolean dailyAutomationEnabled() {
        Properties values = load();
        return Boolean.parseBoolean(values.getProperty(DAILY_AUTOMATION, "false"));
    }

    public synchronized void setDailyAutomationEnabled(boolean enabled) throws IOException {
        Properties values = load();
        values.setProperty(DAILY_AUTOMATION, Boolean.toString(enabled));
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(writer, "Bot Workstation operations settings");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Properties load() {
        Properties values = new Properties();
        if (!Files.isRegularFile(file)) return values;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException ignored) {
            // A missing or unreadable setting is fail-safe: automation remains disabled.
        }
        return values;
    }
}
