package com.botstation.features;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** The editor must submit only user-visible metadata that actually changed. */
public final class SongLibraryChangeSetRegressionTest {
    private SongLibraryChangeSetRegressionTest() {}

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("id", "1273");
        original.put("song_name", "I Want You");
        original.put("album_ids", "208");
        original.put("album_image_path", "cloud-object:mobile-library/assets/image/1273/current.webp");
        original.put("image_path", "cloud-object:mobile-library/assets/image/1273/current.webp");
        original.put("audio_path", "cloud-object:mobile-library/assets/audio/1273/current.ogg");

        Map<String, String> edited = new LinkedHashMap<>(original);
        edited.put("id", "9999");
        edited.put("album_ids", "209");
        edited.put("album_image_path", "C:\\Users\\someone\\Desktop\\cover.png");
        edited.put("image_path", "");
        edited.put("audio_path", "");

        Method method = SongLibraryPanel.class.getDeclaredMethod(
            "changedValues", Map.class, Map.class, String.class);
        method.setAccessible(true);
        Map<String, String> changes = (Map<String, String>) method.invoke(null, original, edited, "id");
        require(changes.equals(Map.of("album_ids", "209")),
            "editor submitted unchanged, identity, or cloud-managed fields: " + changes.keySet());
        System.out.println("SONG_LIBRARY_CHANGE_SET_GREEN");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
