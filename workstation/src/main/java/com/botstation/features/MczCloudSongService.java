package com.botstation.features;

import com.botstation.core.BotPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Cloud-first song access used by the embedded chart-entry workspace. */
public final class MczCloudSongService {
    private final CloudLibraryClient cloud;

    public MczCloudSongService(BotPaths paths) {
        this.cloud = new CloudLibraryClient(paths.userState().resolve("library"));
    }

    public Snapshot loadLatest() throws Exception {
        SongLibraryRepository.Snapshot source = cloud.loadSongs();
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, String> sourceRow : source.rows) rows.add(new LinkedHashMap<>(sourceRow));
        return new Snapshot(new ArrayList<>(source.columns), rows);
    }

    public void createSong(String id, Map<String, String> values, Path image, Path audio) throws Exception {
        boolean created = cloud.createSong(id, metadataValues(values));
        if (image != null && (created || !cloud.hasPublishedAsset(id, "image")))
            cloud.publishAsset(id, "image", image, contentType(image, true));
        if (audio != null && (created || !cloud.hasPublishedAsset(id, "audio")))
            cloud.publishAsset(id, "audio", audio, contentType(audio, false));
    }

    public void updateSong(String id, Map<String, String> values, Path image, Path audio) throws Exception {
        cloud.updateSong(id, metadataValues(values));
        if (image != null) cloud.publishAsset(id, "image", image, contentType(image, true));
        if (audio != null) cloud.publishAsset(id, "audio", audio, contentType(audio, false));
    }

    static Map<String, String> metadataValues(Map<String, String> values) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (values == null) return result;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String field = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if ("album_image_path".equals(field) || "image_path".equals(field) || "audio_path".equals(field)) continue;
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String contentType(Path file, boolean image) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (image) {
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".webp")) return "image/webp";
            return "image/jpeg";
        }
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".ogg")) return "audio/ogg";
        return "audio/mpeg";
    }

    public static final class Snapshot {
        public final List<String> columns;
        public final List<Map<String, String>> rows;

        Snapshot(List<String> columns, List<Map<String, String>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }
}
