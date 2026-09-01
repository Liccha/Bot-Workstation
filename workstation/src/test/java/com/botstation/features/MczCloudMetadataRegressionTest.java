package com.botstation.features;

import java.util.LinkedHashMap;
import java.util.Map;

/** Local workstation paths must never be copied into the shared cloud library. */
public final class MczCloudMetadataRegressionTest {
    private MczCloudMetadataRegressionTest() {}

    public static void main(String[] args) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("song_name", "I Want You");
        row.put("album_image_path", "C:\\Users\\editor\\Desktop\\album.jpg");
        row.put("IMAGE_PATH", "C:\\Users\\editor\\Desktop\\合集\\1273.jpg");
        row.put("audio_path", "C:\\Users\\editor\\Desktop\\preview\\1273.mp3");
        row.put("4k_hd", "8-1141");

        Map<String, String> cloud = MczCloudSongService.metadataValues(row);
        require("I Want You".equals(cloud.get("song_name")), "business metadata was removed");
        require("8-1141".equals(cloud.get("4k_hd")), "chart metadata was removed");
        require(cloud.keySet().stream().noneMatch(key -> key.toLowerCase().endsWith("_path")),
            "a local asset path leaked into cloud metadata");
        require(row.size() == 5, "filtering cloud metadata mutated the local workbook row");
        System.out.println("MCZ_CLOUD_METADATA_GREEN");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
