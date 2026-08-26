package com.mybot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;

public final class AnnouncementStoreRegressionTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("songbot-announcement-test-");
        try {
            Path data = root.resolve("announcements.json");
            Path audit = root.resolve("announcement-audit.jsonl");
            AnnouncementStore store = new AnnouncementStore(data.toFile(), audit.toFile());
            AnnouncementStore.Actor admin = new AnnouncementStore.Actor("admin", "test-device", "127.0.0.1");

            AnnouncementStore.Snapshot initial = store.read();
            JSONArray firstSave = new JSONArray().put(announcement("first", "2099-01-01 10:00", "safe"));
            AnnouncementStore.ReplaceResult first = store.replace(firstSave.toString(), initial.revision, admin);
            require(!first.conflict, "first save unexpectedly conflicted");

            JSONArray secondSave = new JSONArray(first.snapshot.announcements.toString())
                .put(announcement("newer", "2099-01-02 10:00", "new announcement"));
            AnnouncementStore.ReplaceResult second = store.replace(secondSave.toString(), first.snapshot.revision, admin);
            require(!second.conflict, "second save unexpectedly conflicted");

            JSONArray staleSave = new JSONArray(first.snapshot.announcements.toString());
            AnnouncementStore.ReplaceResult stale = store.replace(staleSave.toString(), first.snapshot.revision, admin);
            require(stale.conflict, "stale save was allowed to overwrite newer data");
            require(store.read().announcements.length() == 2, "newer announcement disappeared after stale save");

            JSONArray special = new JSONArray()
                .put(announcement("special", "2000-01-01 00:00", "text },{ with \"quotes\" and\nnewline"))
                .put(announcement("future", "2099-01-01 00:00", "still here"));
            AnnouncementStore.Snapshot beforeSpecial = store.read();
            AnnouncementStore.ReplaceResult specialSaved = store.replace(special.toString(), beforeSpecial.revision, admin);
            require(!specialSaved.conflict, "special-content save conflicted");
            int sent = store.sendDue(LocalDateTime.of(2026, 8, 3, 12, 0),
                item -> AnnouncementStore.SendResult.success("fake success"));
            require(sent == 1, "due announcement was not processed exactly once");
            AnnouncementStore.Snapshot afterSpecial = store.read();
            require(afterSpecial.announcements.length() == 2, "JSON parser lost an announcement");
            require(afterSpecial.announcements.getJSONObject(0).getString("content").contains("},{"),
                "special announcement content was corrupted");

            JSONArray externalRace = new JSONArray()
                .put(announcement("due during desktop save", "2000-01-01 00:00", "send me"));
            AnnouncementStore.Snapshot beforeExternalRace = store.read();
            AnnouncementStore.ReplaceResult externalRaceSaved = store.replace(
                externalRace.toString(), beforeExternalRace.revision, admin);
            require(!externalRaceSaved.conflict, "external-race setup conflicted");
            JSONObject dueWithId = externalRaceSaved.snapshot.announcements.getJSONObject(0);
            int externalRaceSent = store.sendDue(LocalDateTime.of(2026, 8, 3, 12, 5), item -> {
                JSONArray desktopSave = new JSONArray()
                    .put(new JSONObject(dueWithId.toString()))
                    .put(announcement("desktop-added", "2099-02-01 00:00", "must survive scheduler write"));
                Files.write(data, desktopSave.toString().getBytes(StandardCharsets.UTF_8));
                return AnnouncementStore.SendResult.success("fake success while desktop editor saves");
            });
            require(externalRaceSent == 1, "external-race announcement was not sent");
            AnnouncementStore.Snapshot afterExternalRace = store.read();
            require(afterExternalRace.announcements.length() == 2,
                "scheduler overwrote an announcement saved by the desktop editor");
            require("true".equals(afterExternalRace.announcements.getJSONObject(0).getString("sent")),
                "successful send state was lost while merging desktop edit");

            JSONArray observedExternalEdit = new JSONArray(afterExternalRace.announcements.toString())
                .put(announcement("outside-process", "2099-03-01 00:00", "must be audited"));
            Files.write(data, observedExternalEdit.toString().getBytes(StandardCharsets.UTF_8));
            require(store.read().announcements.length() == 3, "external edit was not observed");

            JSONArray failedSend = new JSONArray()
                .put(announcement("failure", "2000-01-01 00:00", "must remain pending"));
            AnnouncementStore.Snapshot beforeFailure = store.read();
            store.replace(failedSend.toString(), beforeFailure.revision, admin);
            int failedCount = store.sendDue(LocalDateTime.of(2026, 8, 3, 12, 10),
                item -> AnnouncementStore.SendResult.failure("fake NapCat outage"));
            require(failedCount == 0, "failed send was counted as success");
            JSONObject failedItem = store.read().announcements.getJSONObject(0);
            require("false".equals(failedItem.getString("sent")), "failed send was marked sent");
            String auditText = new String(Files.readAllBytes(audit), StandardCharsets.UTF_8);
            require(auditText.contains("ANNOUNCEMENT_CREATED"), "create audit event missing");
            require(auditText.contains("SAVE_CONFLICT"), "conflict audit event missing");
            require(auditText.contains("SCHEDULE_SEND_SUCCESS"), "send success audit event missing");
            require(auditText.contains("SCHEDULE_SEND_FAILED"), "send failure audit event missing");
            require(auditText.contains("EXTERNAL_CHANGE_MERGED"), "external-write merge audit event missing");
            require(auditText.contains("EXTERNAL_FILE_CHANGE_DETECTED"), "external edit audit event missing");

            System.out.println("GREEN: announcement store concurrency, external writes, JSON integrity, send state, and audit log");
        } finally {
            if (Files.exists(root)) {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static JSONObject announcement(String title, String time, String content) {
        return new JSONObject()
            .put("title", title).put("content", content).put("time", time)
            .put("groupId", "2000000004").put("pin", "false").put("confirm", "false")
            .put("attach", "").put("sent", "false");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
