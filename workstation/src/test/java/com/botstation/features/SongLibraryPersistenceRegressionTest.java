package com.botstation.features;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** A saved edit must survive SongBot's next CSV-to-SQLite startup import. */
public final class SongLibraryPersistenceRegressionTest {
    private SongLibraryPersistenceRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("song-library-persistence-");
        Path database = directory.resolve("song_data.db");
        Path csv = directory.resolve("songs.csv");
        Files.writeString(csv, "\ufeffid,song_name,author,song_nickname2,image_path\r\n1,旧歌名,作者,,\r\n2,保留歌曲,作者2,,\r\n", StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE songs (id TEXT PRIMARY KEY, song_name TEXT, author TEXT, song_nickname2 TEXT, image_path TEXT)");
            statement.execute("INSERT INTO songs VALUES ('1','旧歌名','作者','','')");
            statement.execute("INSERT INTO songs VALUES ('2','保留歌曲','作者2','','')");
        }
        SongLibraryRepository repository = new SongLibraryRepository(database);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("song_name", "新歌名, 含逗号"); values.put("song_nickname2", "完整昵称"); values.put("image_path", "C:\\资源\\1.png");
        repository.update("id", "1", values);
        SongLibraryRepository.Snapshot snapshot = repository.load();
        require("新歌名, 含逗号".equals(snapshot.rows.get(0).get("song_name")), "SQLite was not updated");
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        require(text.contains("\"新歌名, 含逗号\""), "CSV quoting or persistence failed");
        require(text.contains("完整昵称"), "nickname was not persisted to CSV");
        require(text.contains("2,保留歌曲"), "unrelated CSV rows were damaged");
        Map<String, String> created = new LinkedHashMap<>();
        created.put("song_name", "云端新歌"); created.put("author", "新作者");
        repository.create("id", "1273", created);
        SongLibraryRepository.Snapshot afterCreate = repository.load();
        require(afterCreate.rows.stream().anyMatch(row -> "1273".equals(row.get("id"))
            && "云端新歌".equals(row.get("song_name"))), "new cloud row was not inserted into SQLite");
        require(Files.readString(csv, StandardCharsets.UTF_8).contains("1273,云端新歌,新作者"),
            "new cloud row was not appended to CSV");
        repository.delete("id", "1273");
        require(repository.load().rows.stream().noneMatch(row -> "1273".equals(row.get("id"))),
            "deleted song still exists in SQLite");
        require(!Files.readString(csv, StandardCharsets.UTF_8).contains("1273,云端新歌,新作者"),
            "deleted song still exists in CSV");
        repository.create("id", "1273", Map.of("song_name", "复用已释放 ID", "author", "另一作者"));
        require(repository.load().rows.stream().anyMatch(row -> "1273".equals(row.get("id"))
            && "复用已释放 ID".equals(row.get("song_name"))), "released song ID could not be reused");
        System.out.println("SONG_PERSISTENCE_GREEN");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
