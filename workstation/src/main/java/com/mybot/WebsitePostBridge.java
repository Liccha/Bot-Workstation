package com.mybot;

import com.botstation.security.AdminGate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Public, credential-free facade; the token remains inside the existing local client. */
public final class WebsitePostBridge {
    private final CloudWebsitePostClient client;
    private final File mirrorDirectory;
    private final AdminGate.AdminSession session;

    public WebsitePostBridge(Path songBotDirectory, AdminGate.AdminSession session) throws Exception {
        session.requireAuthorized();
        this.session = session;
        this.client = CloudWebsitePostClient.fromEnvironment(songBotDirectory.toFile());
        this.mirrorDirectory = new File("D:/my-blog/source/_posts");
    }

    public List<Summary> list() throws Exception {
        session.requireAuthorized();
        JSONArray array = client.list();
        List<Summary> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value != null) result.add(new Summary(value.optString("name"), value.optInt("revision"),
                value.optLong("modified"), value.optLong("size")));
        }
        return result;
    }

    public Document read(String name) throws Exception { session.requireAuthorized(); return document(client.read(name)); }

    public Document save(String name, String content, Integer revision) throws Exception {
        session.requireAuthorized();
        JSONObject saved = client.save(name, content, revision);
        client.mirrorSavedPost(mirrorDirectory, saved);
        return document(saved);
    }

    public void delete(String name, int revision) throws Exception {
        session.requireAuthorized();
        client.delete(name, revision);
        client.archiveLocal(mirrorDirectory, name);
    }

    public void syncMirror() throws Exception { session.requireAuthorized(); client.syncTo(mirrorDirectory); }
    public Path mirrorDirectory() { session.requireAuthorized(); return mirrorDirectory.toPath(); }

    private static Document document(JSONObject value) {
        return new Document(value.optString("name"), value.optString("content"), value.optInt("revision"),
            value.optLong("modified"), value.optLong("size"));
    }

    public static class Summary {
        public final String name; public final int revision; public final long modified; public final long size;
        Summary(String name, int revision, long modified, long size) { this.name = name; this.revision = revision; this.modified = modified; this.size = size; }
        @Override public String toString() { return name; }
    }

    public static final class Document extends Summary {
        public final String content;
        Document(String name, String content, int revision, long modified, long size) {
            super(name, revision, modified, size); this.content = content;
        }
    }
}
