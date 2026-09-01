package com.botstation.features;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Validates, installs and publishes a cover or preview while preserving the ID-based source layout. */
public final class SongAssetService {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".mp3", ".wav", ".flac", ".m4a", ".ogg");
    private final BotPaths paths;
    private final LogBus log;
    private final SongLibraryRepository repository;

    public SongAssetService(BotPaths paths, LogBus log) {
        this(paths, log, new SongLibraryRepository(paths.songDatabase,
            new CloudLibraryClient(paths.userState().resolve("library"))));
    }

    SongAssetService(BotPaths paths, LogBus log, SongLibraryRepository repository) {
        this.paths = paths; this.log = log; this.repository = repository;
    }

    public synchronized Path publish(String id, String type, Path source) throws Exception {
        String safeId = validateId(id); boolean image = "image".equals(type);
        if (!image && !"audio".equals(type)) throw new IllegalArgumentException("资源类型无效");
        if (!Files.isRegularFile(source)) throw new IOException("选择的文件不存在");
        String extension = extension(source.getFileName().toString());
        Set<String> allowed = image ? IMAGE_EXTENSIONS : AUDIO_EXTENSIONS;
        if (!allowed.contains(extension)) throw new IOException(image ? "图片仅支持 JPG、PNG、WebP" : "音频仅支持 MP3、WAV、FLAC、M4A、OGG");
        long size = Files.size(source); long maximum = image ? 20L * 1024 * 1024 : 100L * 1024 * 1024;
        if (size < 1 || size > maximum) throw new IOException(image ? "图片大小必须在 20MB 以内" : "音频大小必须在 100MB 以内");
        if (image && !(".webp".equals(extension) ? validWebp(source) : ImageIO.read(source.toFile()) != null))
            throw new IOException("图片内容无法识别");

        SongLibraryRepository.Snapshot snapshot = repository.load();
        String idColumn = findColumn(snapshot.columns, "id");
        Map<String, String> row = snapshot.rows.stream().filter(value -> safeId.equals(value.getOrDefault(idColumn, "").trim())).findFirst()
            .orElseThrow(() -> new IOException("歌曲 ID 不存在"));
        String pathColumn = findColumn(snapshot.columns, image ? "image_path" : "audio_path");
        if (repository.cloudMode()) {
            repository.cloudClient().publishAsset(safeId, type, source, contentType(extension, image));
            log.info("歌曲资源", "已更新云端 ID " + safeId + (image ? " 的图片" : " 的音频"));
            return source;
        }
        Path targetDirectory = paths.desktop.resolve(image ? "合集" : "preview"); Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(safeId + extension);
        Path stagingRoot = paths.config().resolve("asset-staging"); Files.createDirectories(stagingRoot);
        Path backup = Files.createTempDirectory(stagingRoot, safeId + "-");
        List<Path> previous = variants(targetDirectory, safeId, allowed);
        for (Path old : previous) Files.copy(old, backup.resolve(old.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        String previousPath = row.getOrDefault(pathColumn, "");
        try {
            Path temporary = target.resolveSibling(target.getFileName() + ".uploading");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            for (Path old : previous) if (!old.equals(target)) Files.deleteIfExists(old);
            Map<String, String> update = new LinkedHashMap<>(); update.put(pathColumn, target.toAbsolutePath().toString());
            repository.update(idColumn, safeId, update);
            runPublisher();
            log.info("歌曲资源", "已更新并发布 ID " + safeId + (image ? " 的图片" : " 的音频"));
            return target;
        } catch (Exception error) {
            for (Path current : variants(targetDirectory, safeId, allowed)) Files.deleteIfExists(current);
            try (java.util.stream.Stream<Path> files = Files.list(backup)) {
                for (Path old : (Iterable<Path>) files::iterator) Files.copy(old, targetDirectory.resolve(old.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            try { repository.update(idColumn, safeId, Map.of(pathColumn, previousPath)); }
            catch (Exception restore) { error.addSuppressed(restore); }
            throw error;
        } finally { deleteTree(backup); }
    }

    /** Deletes the library row and every local/remote cover and preview owned by the same ID. */
    public synchronized void deleteSong(String id) throws Exception {
        String safeId = validateId(id);
        SongLibraryRepository.Snapshot snapshot = repository.load();
        String idColumn = findColumn(snapshot.columns, "id");
        if (repository.cloudMode()) {
            repository.delete(idColumn, safeId);
            log.info("歌曲资源", "已彻底删除云端 ID " + safeId);
            return;
        }

        Path stagingRoot = paths.config().resolve("asset-delete-staging");
        Files.createDirectories(stagingRoot);
        Path backup = Files.createTempDirectory(stagingRoot, safeId + "-");
        List<StagedAsset> staged = new ArrayList<>();
        boolean recordDeleted = false;
        try {
            stageVariants(paths.desktop.resolve("合集"), safeId, IMAGE_EXTENSIONS, backup.resolve("image"), staged);
            stageVariants(paths.desktop.resolve("preview"), safeId, AUDIO_EXTENSIONS, backup.resolve("audio"), staged);
            repository.delete(idColumn, safeId);
            recordDeleted = true;
            try { runPublisher(); }
            catch (Exception publishError) {
                log.warn("歌曲资源", "ID " + safeId + " 已删除，发布索引将在下次同步时重试：" + publishError.getMessage());
            }
            log.info("歌曲资源", "已彻底删除 ID " + safeId + " 的记录、图片和音频");
        } catch (Exception error) {
            if (!recordDeleted) restoreStaged(staged, error);
            throw error;
        } finally {
            deleteTree(backup);
        }
    }

    public Path downloadAndPublish(String id, String type, URI download, String originalName, long expectedSize) throws Exception {
        if (download == null || !"https".equalsIgnoreCase(download.getScheme()) || download.getUserInfo() != null
            || download.getHost() == null || !download.getHost().toLowerCase(Locale.ROOT).endsWith(".aliyuncs.com")
            || !download.getPath().contains("/mobile-assets/")) throw new IOException("云端资源地址无效");
        long maximum = "image".equals(type) ? 20L * 1024 * 1024 : 100L * 1024 * 1024;
        if (expectedSize < 1 || expectedSize > maximum) throw new IOException("资源大小无效");
        String suffix = extension(originalName); if (suffix.isEmpty()) throw new IOException("资源扩展名无效");
        Path folder = paths.config().resolve("mobile-uploads"); Files.createDirectories(folder);
        Path temporary = Files.createTempFile(folder, "song-" + validateId(id) + "-", suffix);
        try {
            HttpURLConnection connection = (HttpURLConnection) download.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10_000); connection.setReadTimeout(120_000);
            if (connection.getResponseCode() != 200) throw new IOException("无法读取云端资源（HTTP " + connection.getResponseCode() + "）");
            try (InputStream input = connection.getInputStream(); java.io.OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0;
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    total += count; if (total > maximum || total > expectedSize + 1024) throw new IOException("云端资源大小与上传记录不一致");
                    output.write(buffer, 0, count);
                }
                if (total != expectedSize) throw new IOException("云端资源不完整");
            } finally { connection.disconnect(); }
            return publish(id, type, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void runPublisher() throws Exception {
        Path workflow = paths.songBot.resolve("song-library").resolve("tools").resolve("sync_build_publish.py");
        if (!Files.isRegularFile(workflow)) throw new IOException("歌曲资源发布脚本不存在");
        ProcessBuilder builder = new ProcessBuilder("python", workflow.toString());
        builder.directory(paths.songBot.resolve("song-library").toFile()); builder.redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> { try (InputStream input = process.getInputStream()) { input.transferTo(output); } catch (IOException ignored) {} }, "song-asset-publisher-output");
        reader.setDaemon(true); reader.start();
        if (!process.waitFor(60, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IOException("歌曲资源发布超时"); }
        reader.join(Duration.ofSeconds(5).toMillis());
        if (process.exitValue() != 0) {
            String message = new String(output.toByteArray(), StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ");
            if (message.length() > 600) message = message.substring(message.length() - 600);
            throw new IOException("歌曲资源发布失败：" + message);
        }
    }

    private static List<Path> variants(Path directory, String id, Set<String> allowed) throws IOException {
        List<Path> result = new ArrayList<>(); if (!Files.isDirectory(directory)) return result;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                .filter(path -> baseName(path.getFileName().toString()).equals(id))
                .filter(path -> allowed.contains(extension(path.getFileName().toString())))
                .forEach(result::add);
        }
        result.sort(Comparator.comparing(Path::toString)); return result;
    }
    private static void stageVariants(Path directory, String id, Set<String> allowed, Path backup,
                                      List<StagedAsset> staged) throws IOException {
        for (Path source : variants(directory, id, allowed)) {
            Files.createDirectories(backup);
            Path saved = backup.resolve(source.getFileName().toString());
            moveReplacing(source, saved);
            staged.add(new StagedAsset(source, saved));
        }
    }
    private static void restoreStaged(List<StagedAsset> staged, Exception original) {
        for (int index = staged.size() - 1; index >= 0; index--) {
            StagedAsset asset = staged.get(index);
            try {
                Files.createDirectories(asset.original.getParent());
                moveReplacing(asset.backup, asset.original);
            } catch (Exception restore) { original.addSuppressed(restore); }
        }
    }
    private static void moveReplacing(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static String validateId(String value) {
        String safe = value == null ? "" : value.trim(); if (!safe.matches("[0-9]{1,9}")) throw new IllegalArgumentException("歌曲 ID 无效"); return safe;
    }
    private static String extension(String name) { int dot = name == null ? -1 : name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT); }
    private static String contentType(String extension, boolean image) {
        if (image) {
            if (".png".equals(extension)) return "image/png";
            if (".webp".equals(extension)) return "image/webp";
            return "image/jpeg";
        }
        if (".wav".equals(extension)) return "audio/wav";
        if (".flac".equals(extension)) return "audio/flac";
        if (".m4a".equals(extension)) return "audio/mp4";
        if (".ogg".equals(extension)) return "audio/ogg";
        return "audio/mpeg";
    }
    private static boolean validWebp(Path source) throws IOException {
        byte[] header = new byte[12];
        try (InputStream input = Files.newInputStream(source)) {
            if (input.read(header) != header.length) return false;
        }
        return header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
            && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
    }
    private static String baseName(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(0, dot); }
    private static String findColumn(List<String> columns, String expected) {
        return columns.stream().filter(value -> value.equalsIgnoreCase(expected)).findFirst().orElseThrow(() -> new IllegalStateException("歌曲表缺少 " + expected + " 字段"));
    }
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
    private static final class StagedAsset {
        final Path original; final Path backup;
        StagedAsset(Path original, Path backup) { this.original = original; this.backup = backup; }
    }
}
