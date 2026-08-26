package com.mcz;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Run manually: creates, updates and soft-deletes one far-future cloud record. */
public final class CloudAnnouncementStoreSmokeTest {
    public static void main(String[] args) throws Exception {
        File desktop = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        CloudAnnouncementStore store = CloudAnnouncementStore.fromSongBot(new File(desktop, "SongBot"));
        int before = store.load().size();
        File attachment = File.createTempFile("mczmaker-cloud-smoke-", ".txt");
        Map<String, Object> created = null;
        try {
            Files.write(attachment.toPath(), "cloud-smoke".getBytes(StandardCharsets.UTF_8));
            String session = "ann_smoke_" + UUID.randomUUID().toString().replace("-", "");
            String token = store.upload(attachment, session, "attach");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", "2000000004");
            item.put("title", "MczMaker 云端联调");
            item.put("content", "MczMaker 云端联调临时公告");
            item.put("time", "2099-12-31 23:59");
            item.put("pin", "false");
            item.put("confirm", "false");
            item.put("sent", "false");
            item.put("image", "");
            item.put("attach", token);
            created = store.save(item, true);
            created.put("content", "MczMaker 云端联调临时公告（已更新）");
            created = store.save(created, false);
            List<Map<String, Object>> visible = store.load();
            boolean found = false;
            for (Map<String, Object> row : visible) {
                if (String.valueOf(created.get("id")).equals(String.valueOf(row.get("id")))) found = true;
            }
            if (!found) throw new AssertionError("created cloud announcement was not visible");
            store.delete(created);
            if (store.load().size() != before) throw new AssertionError("soft-deleted smoke record remained visible");
            System.out.println("GREEN: MczMaker cloud CRUD and attachment upload passed; visible count restored to " + before + ".");
        } finally {
            if (created != null) {
                try { store.delete(created); } catch (Exception ignored) {}
            }
            Files.deleteIfExists(attachment.toPath());
        }
    }
}
