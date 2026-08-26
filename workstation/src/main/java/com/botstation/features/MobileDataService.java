package com.botstation.features;

import com.botstation.core.BotPaths;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Narrow JSON facade for the paired mobile client; database access stays transactional. */
public final class MobileDataService {
    private static final List<String> SONG_COLUMNS = java.util.Arrays.asList(
        "id", "song_name", "author", "charter", "bpm", "duration", "album", "song_nickname");
    private final SongLibraryRepository songs;
    private final StableRepository stable;

    public MobileDataService(BotPaths paths) { songs = new SongLibraryRepository(paths.songDatabase); stable = new StableRepository(paths); }

    public JSONObject songs(String query, int requestedLimit) throws Exception {
        SongLibraryRepository.Snapshot snapshot = songs.load();
        int limit = Math.max(1, Math.min(100, requestedLimit));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONArray result = new JSONArray();
        for (Map<String, String> row : snapshot.rows) {
            boolean matches = needle.isEmpty() || row.values().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
            if (!matches) continue;
            JSONObject item = new JSONObject();
            for (String expected : SONG_COLUMNS) {
                String actual = snapshot.columns.stream().filter(column -> column.equalsIgnoreCase(expected)).findFirst().orElse(null);
                if (actual != null) item.put(actual, row.getOrDefault(actual, ""));
            }
            result.put(item); if (result.length() >= limit) break;
        }
        return new JSONObject().put("items", result).put("limit", limit).put("total", result.length());
    }

    public void updateSong(String id, JSONObject inputValues) throws Exception {
        SongLibraryRepository.Snapshot snapshot = songs.load();
        String idColumn = snapshot.columns.stream().filter(column -> column.equalsIgnoreCase("id")).findFirst()
            .orElseThrow(() -> new IllegalStateException("歌曲表没有 id 字段"));
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : inputValues.keySet()) {
            if (SONG_COLUMNS.stream().noneMatch(allowed -> allowed.equalsIgnoreCase(key))) continue;
            String actual = snapshot.columns.stream().filter(column -> column.equalsIgnoreCase(key)).findFirst().orElse(null);
            if (actual != null && !actual.equalsIgnoreCase(idColumn)) values.put(actual, inputValues.optString(key, ""));
        }
        songs.update(idColumn, id, values);
    }

    public JSONObject stable(String query, int requestedLimit) throws Exception {
        StableRepository.Snapshot snapshot = stable.load(); int limit = Math.max(1, Math.min(200, requestedLimit));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONArray items = new JSONArray();
        for (int row = 0; row < snapshot.rows.size(); row++) {
            if (!needle.isEmpty() && snapshot.rows.get(row).stream()
                .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle))) continue;
            JSONObject item = new JSONObject();
            for (int column = 0; column < snapshot.headers.size(); column++) {
                String header = snapshot.headers.get(column);
                String value = column < snapshot.rows.get(row).size() ? snapshot.rows.get(row).get(column) : "";
                if (header.equalsIgnoreCase("cover") && value.contains("stable_cover") && value.contains(".webp")) value = "AUTO";
                item.put(header, value);
            }
            items.put(item);
            if (items.length() >= limit) break;
        }
        return new JSONObject().put("items", items).put("total", items.length());
    }

    public void updateStable(String sid, JSONObject inputValues) throws Exception {
        StableRepository.Snapshot snapshot = stable.load();
        int sidColumn = indexIgnoreCase(snapshot.headers, "sid");
        if (sidColumn < 0) throw new IllegalStateException("Stable 表没有 SID 字段");
        List<String> target = null;
        for (List<String> row : snapshot.rows) {
            if (sid.equals(value(row, sidColumn).trim())) { target = row; break; }
        }
        if (target == null) throw new IllegalStateException("Stable 记录已不存在");
        for (String key : inputValues.keySet()) {
            if (key.equalsIgnoreCase("sid")) continue;
            int column = indexIgnoreCase(snapshot.headers, key);
            if (column < 0) continue;
            while (target.size() <= column) target.add("");
            target.set(column, inputValues.optString(key, ""));
        }
        stable.save(snapshot.headers, snapshot.rows);
    }

    private static int indexIgnoreCase(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++)
            if (values.get(index).equalsIgnoreCase(expected)) return index;
        return -1;
    }

    private static String value(List<String> row, int index) {
        return index >= 0 && index < row.size() ? String.valueOf(row.get(index)) : "";
    }
}
