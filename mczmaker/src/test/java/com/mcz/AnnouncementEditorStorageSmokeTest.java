package com.mcz;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Read-only smoke test for the SongBot-owned announcement data source. */
public final class AnnouncementEditorStorageSmokeTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AnnouncementEditor editor = new AnnouncementEditor(() -> {});

        Field fileField = AnnouncementEditor.class.getDeclaredField("announceFile");
        fileField.setAccessible(true);
        File file = (File) fileField.get(editor);
        String normalized = file.getCanonicalPath().replace('\\', '/');
        if (!normalized.endsWith("/SongBot/data/announcements.json")) {
            throw new AssertionError("wrong announcement source: " + normalized);
        }

        Field listField = AnnouncementEditor.class.getDeclaredField("announcements");
        listField.setAccessible(true);
        List<?> announcements = (List<?>) listField.get(editor);
        if (announcements.size() != 13) {
            throw new AssertionError("expected 13 announcements, got " + announcements.size());
        }

        Field assetField = AnnouncementEditor.class.getDeclaredField("announceAssetDir");
        assetField.setAccessible(true);
        File assetRoot = (File) assetField.get(editor);
        File source = File.createTempFile("announcement-smoke-", ".txt");
        String session = "smoke_" + UUID.randomUUID().toString().replace("-", "");
        File copied = null;
        try {
            Files.write(source.toPath(), "attachment-smoke".getBytes(StandardCharsets.UTF_8));
            Method store = AnnouncementEditor.class.getDeclaredMethod(
                "storeAsset", File.class, String.class, String.class, String.class);
            store.setAccessible(true);
            String token = (String) store.invoke(editor, source, session, "attach", "");
            copied = new File(assetRoot, token).getCanonicalFile();
            if (!copied.isFile() || !token.startsWith(session + "/attach/")) {
                throw new AssertionError("attachment was not transferred into SongBot: " + token);
            }
        } finally {
            Files.deleteIfExists(source.toPath());
            if (copied != null) Files.deleteIfExists(copied.toPath());
            Files.deleteIfExists(new File(new File(assetRoot, session), "attach").toPath());
            Files.deleteIfExists(new File(assetRoot, session).toPath());
        }

        System.out.println("GREEN: MczMaker reads SongBot data and transfers attachments into SongBot/announce_files.");
    }
}
