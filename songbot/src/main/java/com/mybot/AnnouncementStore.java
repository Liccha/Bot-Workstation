package com.mybot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Serializes every announcements.json read/modify/write and records a JSONL audit trail. */
final class AnnouncementStore {
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final File dataFile;
    private final File auditFile;
    private final Object lock = new Object();
    private String observedRevision;
    private JSONArray observedData;

    AnnouncementStore(File dataFile, File auditFile) {
        this.dataFile = dataFile;
        this.auditFile = auditFile;
    }

    static final class Snapshot {
        final JSONArray announcements;
        final String revision;
        Snapshot(JSONArray announcements, String revision) {
            this.announcements = announcements;
            this.revision = revision;
        }
    }

    static final class ReplaceResult {
        final boolean conflict;
        final Snapshot snapshot;
        ReplaceResult(boolean conflict, Snapshot snapshot) {
            this.conflict = conflict;
            this.snapshot = snapshot;
        }
    }

    static final class Actor {
        final String kind;
        final String device;
        final String ip;
        Actor(String kind, String device, String ip) {
            this.kind = safe(kind);
            this.device = safe(device);
            this.ip = safe(ip);
        }
        JSONObject toJson() {
            return new JSONObject().put("kind", kind).put("device", device).put("ip", ip);
        }
    }

    interface DueSender {
        SendResult send(JSONObject announcement) throws Exception;
    }

    static final class SendResult {
        final boolean success;
        final String detail;
        SendResult(boolean success, String detail) {
            this.success = success;
            this.detail = safe(detail);
        }
        static SendResult success(String detail) { return new SendResult(true, detail); }
        static SendResult failure(String detail) { return new SendResult(false, detail); }
    }

    Snapshot read() throws IOException {
        synchronized (lock) {
            JSONArray data = readArray();
            observeExternalChange(data);
            boolean migrated = ensureIds(data, null);
            if (migrated) {
                writeArray(data);
                remember(data);
                audit("MIGRATE_IDS", new Actor("system", "scheduler", "local"),
                    new JSONObject().put("count", data.length()));
            }
            return snapshot(data);
        }
    }

    ReplaceResult replace(String json, String expectedRevision, Actor actor) throws IOException {
        synchronized (lock) {
            JSONArray current = readArray();
            observeExternalChange(current);
            boolean currentMigrated = ensureIds(current, null);
            if (currentMigrated) {
                writeArray(current);
                remember(current);
            }
            String currentRevision = revision(current);
            String expected = normalizeRevision(expectedRevision);
            if (!expected.isEmpty() && !"*".equals(expected) && !expected.equals(currentRevision)) {
                audit("SAVE_CONFLICT", actor, new JSONObject()
                    .put("expectedRevision", expected).put("currentRevision", currentRevision)
                    .put("currentCount", current.length()));
                return new ReplaceResult(true, snapshot(current));
            }

            JSONArray incoming;
            try {
                incoming = new JSONArray(json);
            } catch (Exception ex) {
                audit("SAVE_REJECTED_INVALID_JSON", actor, new JSONObject().put("error", safe(ex.getMessage())));
                throw new IOException("Announcements payload must be a JSON array", ex);
            }
            ensureIds(incoming, current);

            // A desktop editor can still write this file outside this JVM. Recheck immediately
            // before committing so an external save is never silently overwritten.
            JSONArray latest = readArray();
            if (!revision(latest).equals(currentRevision)) {
                audit("SAVE_CONFLICT_EXTERNAL_WRITE", actor, new JSONObject()
                    .put("expectedRevision", currentRevision).put("currentRevision", revision(latest))
                    .put("currentCount", latest.length()));
                return new ReplaceResult(true, snapshot(latest));
            }
            writeArray(incoming);
            remember(incoming);
            auditDiff(current, incoming, actor);
            Snapshot saved = snapshot(incoming);
            audit("SAVE_COMMITTED", actor, new JSONObject()
                .put("beforeCount", current.length()).put("afterCount", incoming.length())
                .put("revision", saved.revision)
                .put("legacyWithoutRevision", expected.isEmpty()));
            return new ReplaceResult(false, saved);
        }
    }

    int sendDue(LocalDateTime now, DueSender sender) throws IOException {
        synchronized (lock) {
            JSONArray data = readArray();
            observeExternalChange(data);
            boolean migrated = ensureIds(data, null);
            if (migrated) {
                writeArray(data);
                remember(data);
                audit("MIGRATE_IDS", new Actor("system", "scheduler", "local"),
                    new JSONObject().put("count", data.length()));
            }
            String baseRevision = revision(data);
            boolean changed = false;
            int sentCount = 0;
            DateTimeFormatter scheduleFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null || !"false".equals(item.optString("sent", "false"))) continue;
                String time = item.optString("time", "");
                if (time.length() > 16) time = time.substring(0, 16);
                LocalDateTime scheduled;
                try {
                    scheduled = LocalDateTime.parse(time, scheduleFormat);
                } catch (Exception ex) {
                    continue;
                }
                if (now.isBefore(scheduled)) continue;

                String lastAttempt = item.optString("lastSendAttemptAt", "");
                if (!lastAttempt.isEmpty()) {
                    try {
                        LocalDateTime previous = LocalDateTime.parse(lastAttempt, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        if (previous.plusMinutes(5).isAfter(now)) continue;
                    } catch (Exception ignored) {}
                }

                item.put("lastSendAttemptAt", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                item.put("sendAttempts", item.optInt("sendAttempts", 0) + 1);
                changed = true;
                audit("SCHEDULE_SEND_ATTEMPT", new Actor("system", "scheduler", "local"),
                    new JSONObject().put("announcement", cloneJson(item)));
                try {
                    SendResult result = sender.send(cloneJson(item));
                    if (result != null && result.success) {
                        item.put("sent", "true");
                        item.put("sentAt", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        item.remove("lastSendError");
                        sentCount++;
                        audit("SCHEDULE_SEND_SUCCESS", new Actor("system", "scheduler", "local"),
                            new JSONObject().put("announcement", cloneJson(item)).put("detail", result.detail));
                    } else {
                        String detail = result == null ? "empty send result" : result.detail;
                        item.put("lastSendError", detail);
                        audit("SCHEDULE_SEND_FAILED", new Actor("system", "scheduler", "local"),
                            new JSONObject().put("announcement", cloneJson(item)).put("error", detail));
                    }
                } catch (Exception ex) {
                    String detail = safe(ex.getMessage());
                    item.put("lastSendError", detail);
                    audit("SCHEDULE_SEND_FAILED", new Actor("system", "scheduler", "local"),
                        new JSONObject().put("announcement", cloneJson(item)).put("error", detail));
                }
            }
            if (changed) {
                JSONArray latest = readArray();
                ensureIds(latest, data);
                String latestRevision = revision(latest);
                if (!latestRevision.equals(baseRevision)) {
                    mergeSendState(data, latest);
                    audit("EXTERNAL_CHANGE_MERGED", new Actor("system", "scheduler", "local"),
                        new JSONObject().put("schedulerRevision", baseRevision)
                            .put("externalRevision", latestRevision).put("currentCount", latest.length()));
                    data = latest;
                }
                writeArray(data);
                remember(data);
            }
            return sentCount;
        }
    }

    void auditEvent(String event, Actor actor, JSONObject detail) {
        synchronized (lock) {
            audit(event, actor, detail == null ? new JSONObject() : detail);
        }
    }

    private JSONArray readArray() throws IOException {
        if (!dataFile.isFile()) return new JSONArray();
        String raw = new String(Files.readAllBytes(dataFile.toPath()), StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception ex) {
            audit("READ_INVALID_JSON", new Actor("system", "store", "local"),
                new JSONObject().put("error", safe(ex.getMessage())).put("length", raw.length()));
            throw new IOException("announcements.json is not a valid JSON array; original file was preserved", ex);
        }
    }

    private boolean ensureIds(JSONArray incoming, JSONArray current) {
        Map<String, String> existingIds = new LinkedHashMap<>();
        if (current != null) {
            for (int i = 0; i < current.length(); i++) {
                JSONObject old = current.optJSONObject(i);
                if (old != null && !old.optString("id", "").isEmpty()) {
                    existingIds.put(signature(old), old.optString("id"));
                }
            }
        }
        boolean changed = false;
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.optJSONObject(i);
            if (item == null) continue;
            if (item.optString("id", "").isEmpty()) {
                String id = existingIds.get(signature(item));
                item.put("id", id == null ? UUID.randomUUID().toString() : id);
                changed = true;
            }
        }
        return changed;
    }

    private void auditDiff(JSONArray before, JSONArray after, Actor actor) {
        Map<String, JSONObject> oldById = byId(before);
        Map<String, JSONObject> newById = byId(after);
        for (Map.Entry<String, JSONObject> entry : newById.entrySet()) {
            JSONObject old = oldById.get(entry.getKey());
            if (old == null) {
                audit("ANNOUNCEMENT_CREATED", actor, new JSONObject().put("after", cloneJson(entry.getValue())));
            } else if (!old.similar(entry.getValue())) {
                audit("ANNOUNCEMENT_UPDATED", actor, new JSONObject()
                    .put("before", cloneJson(old)).put("after", cloneJson(entry.getValue())));
            }
        }
        for (Map.Entry<String, JSONObject> entry : oldById.entrySet()) {
            if (!newById.containsKey(entry.getKey())) {
                audit("ANNOUNCEMENT_DELETED", actor, new JSONObject().put("before", cloneJson(entry.getValue())));
            }
        }
    }

    private Map<String, JSONObject> byId(JSONArray array) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) result.put(item.optString("id", "index-" + i), item);
        }
        return result;
    }

    private void mergeSendState(JSONArray schedulerData, JSONArray latest) {
        Map<String, JSONObject> schedulerById = byId(schedulerData);
        String[] stateFields = {"sent", "sentAt", "lastSendAttemptAt", "sendAttempts", "lastSendError"};
        for (int i = 0; i < latest.length(); i++) {
            JSONObject target = latest.optJSONObject(i);
            if (target == null) continue;
            JSONObject source = schedulerById.get(target.optString("id", ""));
            if (source == null) continue;
            for (String field : stateFields) {
                if (source.has(field)) target.put(field, source.get(field));
                else target.remove(field);
            }
        }
    }

    private void observeExternalChange(JSONArray data) {
        String currentRevision = revision(data);
        if (observedRevision != null && !observedRevision.equals(currentRevision)) {
            Actor external = new Actor("external-file", "desktop-editor-or-tool", "local");
            auditDiff(observedData == null ? new JSONArray() : observedData, data, external);
            audit("EXTERNAL_FILE_CHANGE_DETECTED", external, new JSONObject()
                .put("beforeRevision", observedRevision).put("afterRevision", currentRevision)
                .put("beforeCount", observedData == null ? 0 : observedData.length())
                .put("afterCount", data.length()));
        }
        remember(data);
    }

    private void remember(JSONArray data) {
        observedData = new JSONArray(data.toString());
        observedRevision = revision(observedData);
    }

    private Snapshot snapshot(JSONArray data) {
        JSONArray copy = new JSONArray(data.toString());
        return new Snapshot(copy, revision(copy));
    }

    private String revision(JSONArray data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void writeArray(JSONArray data) throws IOException {
        File parent = dataFile.getCanonicalFile().getParentFile();
        if (parent == null) throw new IOException("Missing announcements parent directory");
        parent.mkdirs();
        java.nio.file.Path temp = Files.createTempFile(parent.toPath(), ".announcements", ".tmp");
        try {
            Files.write(temp, data.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void audit(String event, Actor actor, JSONObject detail) {
        try {
            File parent = auditFile.getCanonicalFile().getParentFile();
            if (parent != null) parent.mkdirs();
            JSONObject line = new JSONObject()
                .put("timestamp", LocalDateTime.now().format(AUDIT_TIME))
                .put("event", safe(event))
                .put("actor", actor == null ? new Actor("unknown", "unknown", "unknown").toJson() : actor.toJson())
                .put("detail", detail == null ? new JSONObject() : detail);
            Files.write(auditFile.toPath(), (line.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            System.err.println("[公告审计] 写入失败: " + ex.getMessage());
        }
    }

    private static JSONObject cloneJson(JSONObject value) {
        return new JSONObject(value.toString());
    }

    private static String signature(JSONObject item) {
        return item.optString("title", "") + "\u001f" + item.optString("time", "") + "\u001f"
            + item.optString("groupId", "") + "\u001f" + item.optString("image", "") + "\u001f"
            + item.optString("attach", "") + "\u001f" + String.valueOf(item.opt("attachmentNames"));
    }

    private static String normalizeRevision(String revision) {
        String value = safe(revision).trim();
        if (value.startsWith("W/")) value = value.substring(2).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
