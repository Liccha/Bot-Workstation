package com.mybot;
import java.io.*;
import java.util.Comparator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import com.github.houbb.opencc4j.util.ZhConverterUtil; // 引入繁简转换工具
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.LocalTime;

// 文件 6: SongBot.java
public class SongBot {
    private static final Path SONG_BOT_HOME = resolveSongBotHome();
    private static final int MAX_JSON_BODY_BYTES = 1 * 1024 * 1024;
    private static final int MAX_UPLOAD_BODY_BYTES = 50 * 1024 * 1024;
    private static volatile AnnouncementStore announcementStore;
    // 测试群查询模式开关：
    // 1 = 全开放 (SongBot 和 Stable 各自独立运行)
    // 2 = 仅启用 SongBot 茶韵谱面查询
    // 3 = 仅启用 Stable 库查询
    public static int QUERY_MODE = 1;
    public static Stable stableService;
    // --- V300: 竞猜游戏状态 ---
    private static class GameSession implements java.io.Serializable {
        private static final long serialVersionUID = 1L; // 版本号，防止报错

        String correctOption;
        long expireTime;
        boolean isActive;
        List<String> correctNicknames = java.util.Collections.synchronizedList(new ArrayList<>());
        Set<Long> participatedUsers = java.util.Collections.synchronizedSet(new HashSet<>());
        Map<Long, Integer> questionMsgIds = new java.util.concurrent.ConcurrentHashMap<>();

        public GameSession(String correct, long expire) {
            this.correctOption = correct;
            this.expireTime = expire;
            this.isActive = true;
        }
    }
    // 当前游戏会话 (内存中)
    private static GameSession currentGame = null;
    private static final DatabaseService dbService = new DatabaseService();
    private static final NapCatClient apiClient = new NapCatClient();

    private static Path resolveSongBotHome() {
        String configured = System.getProperty("songbot.home", "").trim();
        if (!configured.isEmpty()) return Path.of(configured).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"), "Desktop", "SongBot").toAbsolutePath().normalize();
    }

    private static File songBotFile(String relative) {
        return SONG_BOT_HOME.resolve(relative).normalize().toFile();
    }

    /**
     * Pulls cloud library changes once during startup. Message handlers never
     * contact the cloud: they continue to query the local SQLite database.
     */
    private static void syncMobileCloudToLocal() {
        Path workflow = songBotFile("tools/sync_mobile_cloud_to_local.py").toPath();
        if (!java.nio.file.Files.isRegularFile(workflow)) return;

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("python", workflow.toString());
            builder.directory(SONG_BOT_HOME.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            Thread reader = new Thread(() -> {
                try (InputStream stream = running.getInputStream()) {
                    byte[] buffer = new byte[2048];
                    int read;
                    while ((read = stream.read(buffer)) >= 0 && output.size() < 16 * 1024) {
                        output.write(buffer, 0, Math.min(read, 16 * 1024 - output.size()));
                    }
                } catch (IOException ignored) {
                }
            }, "mobile-cloud-sync-output");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("Cloud library startup sync timed out; local data remains active.");
                return;
            }
            reader.join(1000);
            String message = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0) {
                if (!message.isEmpty()) System.out.println("Cloud library: " + message);
            } else {
                System.err.println("Cloud library sync failed; local data remains active." +
                        (message.isEmpty() ? "" : " " + message));
            }
        } catch (Exception error) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            System.err.println("Cloud library sync could not start; local data remains active: " + error.getMessage());
        }
    }

    // --- 配置常量 ---
    private static final int FUZZY_SEARCH_LIMIT = 600;
    private static final int LARGE_RESULT_THRESHOLD = 60; // 统一使用这个阈值
    private static final List<Long> ADMIN_LIST = java.util.Arrays.asList(
            1000000001L,
            1000000002L,
            1000000003L,
            1000000004L,
            1000000005L
    );
    private static final List<Long> ALLOWED_GROUPS = List.of(
            2000000001L,
            2000000002L,
            2000000003L,
            2000000004L,
            2000000005L,
            2000000006L
    );
    private static final List<Long> EARLY_ACCESS_GROUPS = List.of(
            2000000001L,
            2000000003L,
            2000000002L
    );
    // ... 原有的 ADMIN_LIST 等 ...

    // 【新增】每日推送群白名单 (只有这些群会收到推送并统计)
    private static final List<Long> DAILY_PUSH_GROUPS = List.of(
            2000000004L,
            2000000005L
    );
    // 临时运营开关：关闭时仅暂停上述两个群的每日随机歌曲推送，
    // 以及该流程附带的自动猜歌榜单发布/周期结算；模块和历史数据仍完整保留。
    private static final boolean DAILY_PUSH_AUTOMATION_ENABLED = false;

    // 定时任务调度器
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final Map<Integer, Long> SONG_UNLOCK_TIME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    // --- 冷却系统：时间记录 ---
    // 大结果搜索冷却
    private static final Map<Long, Long> cdLargeSearch = new java.util.concurrent.ConcurrentHashMap<>();
    // 专辑目录冷却
    private static final Map<Long, Long> cdAlbum = new java.util.concurrent.ConcurrentHashMap<>();
    // ... 原有的变量 ...

    // 【新增】记录每日推送的戳一戳历史 (Key: 消息ID, Value: 已经戳过的用户列表)
    private static final Map<Integer, List<Long>> POKE_HISTORY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    // 全部专辑冷却
    private static final Map<Long, Long> cdAllAlbums = new java.util.concurrent.ConcurrentHashMap<>();
    // --- V101: 抢先体验/内测群白名单 (无视发布时间限制) ---

    // ============================================================
    // 👇 【V6.0 最终逻辑】两轮扫描：A/B绝对优先 + 倒序阶梯计算
    // ============================================================
    private static void refreshUnlockTimeCache() {
        SONG_UNLOCK_TIME_CACHE.clear();
        List<Song> allSongs = dbService.getAllSongs();

        // ---------------------------------------------------------
        // 第一轮：处理“单曲配置” (A / B / 自身日期)
        // ---------------------------------------------------------
        for (Song s : allSongs) {
            String raw = s.getAlbumDate();
            if (raw == null || raw.trim().isEmpty()) continue;

            String clean = raw.trim();
            long selfTs = Long.MAX_VALUE;

            if (clean.startsWith("A")) {
                // 规则 A: 立即解锁 (最高优先级)
                selfTs = 0L;
            } else if (clean.startsWith("B")) {
                // 规则 B: 严格按照日期 (不提前)
                String dateStr = clean.replaceAll("^[ABab]\\s*", ""); // 去掉前缀
                try {
                    LocalDate d = parseDate(dateStr);
                    selfTs = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (Exception e) {}
            } else {
                // 普通日期: 默认提前 2 天
                // (注意：如果它属于某个专辑，这可能会在第二轮被更优的阶梯时间覆盖；
                //  但如果它只是单曲，这就作为保底值)
                try {
                    LocalDate d = parseDate(clean);
                    selfTs = d.minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (Exception e) {}
            }

            // 存入缓存
            if (selfTs != Long.MAX_VALUE) {
                SONG_UNLOCK_TIME_CACHE.put(s.getId(), selfTs);
            }
        }

        // ---------------------------------------------------------
        // 第二轮：处理“专辑阶梯” (基于最后一首倒推)
        // ---------------------------------------------------------
        // 1. 分组 (支持多专辑)
        Map<String, List<Song>> albumGroups = new java.util.HashMap<>();
        for (Song s : allSongs) {
            String albumRaw = s.getAlbum();
            if (albumRaw == null || albumRaw.trim().isEmpty()) continue;

            for (String albumName : albumRaw.split("[,，;；|]")) {
                String key = albumName.trim();
                if (!key.isEmpty()) {
                    albumGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
                }
            }
        }

        // 2. 遍历每个专辑计算
        for (Map.Entry<String, List<Song>> entry : albumGroups.entrySet()) {
            List<Song> songs = entry.getValue();
            if (songs.isEmpty()) continue;

            // A. 按 ID 从小到大排序
            songs.sort(Comparator.comparingInt(Song::getId));

            // B. 寻找专辑基准日期 (遍历所有歌，找第一个有效的日期字符串)
            LocalDate baseDate = null;
            for (Song s : songs) {
                String raw = s.getAlbumDate();
                if (raw != null && !raw.trim().isEmpty()) {
                    // 去掉 A/B，只看日期部分
                    String datePart = raw.trim().replaceAll("^[ABab]\\s*", "");
                    try {
                        if (!datePart.isEmpty()) {
                            baseDate = parseDate(datePart);
                            break; // 找到了基准日期，停止
                        }
                    } catch (Exception e) {}
                }
            }

            // 如果整个专辑都没日期，跳过
            if (baseDate == null) continue;

            // C. 倒序阶梯计算
            // 规则：最后一首(Max ID) = BaseDate - 2天
            //      倒数第二首     = BaseDate - 3天
            //      ...
            int maxIndex = songs.size() - 1;

            for (int i = 0; i < songs.size(); i++) {
                Song s = songs.get(i);

                // ⚠️ 保护 A/B 前缀不被覆盖 ⚠️
                // 如果这首歌在第一轮已经标记为 0 (A类) 或 B类 strict，就跳过阶梯计算
                // (注意：我们只检查 raw 是否以 A/B 开头，来决定是否豁免)
                String rawDate = s.getAlbumDate();
                if (rawDate != null) {
                    String r = rawDate.trim();
                    if (r.startsWith("A") || r.startsWith("B")) {
                        continue; // A/B 类完全由第一轮说了算，不参与阶梯
                    }
                }

                // 计算阶梯偏移
                // i 是当前索引 (0, 1, ... max)
                // 最后一首 (i = max) -> 提前 2 天
                // 倒数第二 (i = max-1) -> 提前 3 天
                // 公式: daysEarly = 2 + (max - i)
                int daysEarly = 2 + (maxIndex - i);

                LocalDate unlockDate = baseDate.minusDays(daysEarly);
                long unlockTs = unlockDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

                // D. 存入缓存 (取最优解)
                if (SONG_UNLOCK_TIME_CACHE.containsKey(s.getId())) {
                    long existing = SONG_UNLOCK_TIME_CACHE.get(s.getId());
                    if (unlockTs < existing) {
                        SONG_UNLOCK_TIME_CACHE.put(s.getId(), unlockTs);
                    }
                } else {
                    SONG_UNLOCK_TIME_CACHE.put(s.getId(), unlockTs);
                }
            }
        }

        System.out.println("✅ 解锁时间计算完成 (V6.0: A/B绝对优先 + 倒序阶梯)");
    }

    // 辅助方法：简单检查日期格式是否有效
    private static boolean isValidDate(String dateStr) {
        try {
            parseDate(dateStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static LocalDate parseDate(String dateStr) {
        // 把点和斜杠都换成横杠
        String cleanDate = dateStr.trim().replace(".", "-").replace("/", "-");
        String[] parts = cleanDate.split("-");

        // 情况 A: 用户只输了 "2.20" 或 "2-20" (缺年份)
        if (parts.length == 2) {
            int currentYear = LocalDate.now().getYear(); // 获取今年
            String month = parts[0].length() == 1 ? "0" + parts[0] : parts[0];
            String day = parts[1].length() == 1 ? "0" + parts[1] : parts[1];
            // 补全为 "2026-02-20"
            cleanDate = currentYear + "-" + month + "-" + day;
        }
        // 情况 B: 用户输了 "2026.2.20" (有年份)
        else if (parts.length == 3) {
            if(parts[1].length()==1) parts[1]="0"+parts[1];
            if(parts[2].length()==1) parts[2]="0"+parts[2];
            cleanDate = String.join("-", parts);
        }

        return LocalDate.parse(cleanDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // --- 辅助方法：统一存入缓存 ---
    private static void putUnlockTime(int songId, LocalDate releaseDate, long daysToSubtract) {
        long unlockTimestamp = releaseDate.minusDays(daysToSubtract)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli();
        SONG_UNLOCK_TIME_CACHE.put(songId, unlockTimestamp);
    }

    private static long getUnlockTimestamp(Song song) {
        // 1. 【最高优先级】先查专辑阶梯缓存
        // 如果这首歌属于某个有排期的专辑，缓存里会有它精确的解锁时间
        if (SONG_UNLOCK_TIME_CACHE.containsKey(song.getId())) {
            return SONG_UNLOCK_TIME_CACHE.get(song.getId());
        }

        String rawDate = song.getAlbumDate();

        // 2. 【核心修改点】处理“未设置日期”的情况
        if (rawDate == null || rawDate.trim().isEmpty()) {

            // 检查是否有专辑名
            String album = song.getAlbum();

            // A. 如果连专辑名都没有 (散装单曲) -> 默认直接解锁
            if (album == null || album.trim().isEmpty()) {
                return 0L;
            }

            // B. 如果有专辑名但没填日期 -> 默认解锁 (专辑未排期不应阻碍查看)
            return 0L;
        }

        String cleanDate = rawDate.trim();

        // 3. 处理 A/B 前缀
        if (cleanDate.startsWith("A")) return 0L; // A: 立即解锁
        if (cleanDate.startsWith("B")) return Long.MAX_VALUE; // B: 锁定

        // 4. 解析常规日期
        try {
            LocalDate date = parseDate(cleanDate);
            // 常规逻辑: 提前 2 天解锁
            return date.minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            // 解析失败 (日期格式错误) -> 锁定
            return Long.MAX_VALUE;
        }
    }

    // --- V105: 辅助方法 - 检查歌曲锁定状态 (修正权限优先级) ---
    private static String checkLockStatus(Song song, long groupId, long userId, String messageType) {
        // 1. 【全局豁免】多专辑/老歌检查
        // 逻辑：如果这首歌属于多个专辑（视为旧歌重录），或者已经发售很久，不受任何限制
        if (song.getAlbumIds() != null && song.getAlbumIds().contains(",")) {
            return null;
        }
        if (song.getAlbum() != null && song.getAlbum().contains("|")) {
            return null;
        }

        // 2. 【分场景权限判断】
        if ("private".equals(messageType)) {
            // --- 私聊场景 ---
            // 规则：Admin 可无视时间；普通人必须受限
            if (ADMIN_LIST.contains(userId)) {
                return null; // 管理员私聊直接解锁
            }
            // 普通人私聊 -> 继续向下走去查时间

        } else {
            // --- 群聊场景 (messageType 为 "group") ---
            // 规则：群白名单优先级 > 管理员权限
            // 即：只要群不在白名单里，Admin 也要受时间限制

            if (EARLY_ACCESS_GROUPS.contains(groupId)) {
                return null; // 白名单群 -> 直接解锁 (全员可见)
            }

            // 非白名单群 -> 无论是不是 Admin，都继续向下走去查时间
        }

        // 3. 【时间门槛检查】
        // 只有上面没被放行的（普通人私聊、所有人非白名单群聊），才会走到这里
        long unlockTime = getUnlockTimestamp(song);
        long now = System.currentTimeMillis();

        if (now < unlockTime) {
            LocalDate date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(unlockTime), ZoneId.of("Asia/Shanghai"));
            // 返回等待提示
            return date.format(DateTimeFormatter.ofPattern("MM月dd日")) + "后将显示歌曲信息。";
        }

        // System.out.println("DEBUG: 歌曲 [" + song.getSongName() + "] 时间已到，允许展示");
        return null; // 时间已到，解锁
    }
    // --- 冷却系统：警告状态 ---
    // 大结果警告状态 (true=已警告)
    private static final Map<Long, Boolean> warnLargeSearch = new java.util.concurrent.ConcurrentHashMap<>();
    // 专辑警告状态 (0=无, 1=轻警告, 2=静音)
    private static final Map<Long, Integer> warnAlbum = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, Integer> warnAllAlbums = new java.util.concurrent.ConcurrentHashMap<>();
    public static void main(String[] args) throws IOException {
        System.out.println("--------------------------------------------------");
        System.out.println("✅ V79 Final Bot 已启动 | 数据库: song_data.db");
        // Claim the management port before starting imports, background sync, or schedulers.
        // A duplicate launch must fail without leaving a half-started process behind.
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        } catch (java.net.BindException e) {
            System.err.println("❌ SongBot 已在运行（127.0.0.1:8080 已被占用），本次重复启动已安全退出。");
            return;
        }
// 1. 初始化新表
        dbService.initDailyTables();
        dbService.initGameTables(); // 初始化积分表

        // 2. 启动时低频同步云端曲库，再导入本地数据库；群消息查询不访问云端。
        System.out.println("🔄 正在自动导入最新曲库...");
        syncMobileCloudToLocal();
        dbService.importCsv(songBotFile("songs.csv").getAbsolutePath());
        // 后台自动同步曲库到 GitHub
        new Thread(() -> syncSongLibrary()).start();
        dbService.importDailySchedule(songBotFile("daily_songs.csv").getAbsolutePath());

        loadGameSnapshot();
        refreshUnlockTimeCache();
        stableService = new Stable(dbService.getConnection(), apiClient);
        stableService.initDatabase(songBotFile("stable_info.csv").toPath());
        stableService.startScheduler();
        // 3. 启动每日推送 + 公告定时调度
        if (DAILY_PUSH_AUTOMATION_ENABLED) {
            startDailyScheduler();
        } else {
            System.out.println("⏸️ 两群每日随机歌曲推送与自动猜歌榜单结算已暂停；模块和历史数据均保留。");
        }
        startAnnounceScheduler();
        server.createContext("/", exchange -> {
            String response = "<h1>Bot is Alive</h1>";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });

        // 【新增】公告发送接口 — 供 MczTool 调用，日志统一输出到 SongBot 控制台
        server.createContext("/announce", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    if (!allowLegacyAnnouncementRequest(exchange)) return;
                    if (!requireAdmin(exchange)) return;
                    String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                    org.json.JSONObject request = new org.json.JSONObject(body);
                    org.json.JSONObject announcement = new org.json.JSONObject()
                        .put("id", java.util.UUID.randomUUID().toString())
                        .put("groupId", request.optString("group_id", request.optString("groupId", "")))
                        .put("title", request.optString("title", ""))
                        .put("content", request.optString("content", ""))
                        .put("image", request.optString("image", ""))
                        .put("attach", request.optString("files", request.optString("attach", "")))
                        .put("pin", request.optBoolean("pin", "1".equals(request.optString("pin"))))
                        .put("confirm", request.optBoolean("confirm", "1".equals(request.optString("confirm"))));
                    if (announcement.optString("groupId").isEmpty() || announcement.optString("title").isEmpty()) {
                        sendResponse(exchange, 400, "{\"error\":\"group_id and title are required\"}"); return;
                    }
                    AnnouncementStore store = getAnnouncementStore();
                    AnnouncementStore.Actor actor = announcementActor(exchange);
                    store.auditEvent("IMMEDIATE_SEND_ATTEMPT", actor,
                        new org.json.JSONObject().put("source", "/announce")
                            .put("announcement", new org.json.JSONObject(announcement.toString())));
                    AnnouncementStore.SendResult result = sendAnnouncement(announcement);
                    store.auditEvent(result.success ? "IMMEDIATE_SEND_SUCCESS" : "IMMEDIATE_SEND_FAILED", actor,
                        new org.json.JSONObject().put("source", "/announce")
                            .put("announcement", new org.json.JSONObject(announcement.toString()))
                            .put(result.success ? "detail" : "error", result.detail));
                    if (!result.success) {
                        System.err.println("❌ 公告发送失败: " + result.detail);
                        sendResponse(exchange, 502, "{\"status\":\"error\",\"error\":\"NapCat send failed\"}");
                        return;
                    }
                    sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                } catch (Exception ex) {
                    System.err.println("❌ 公告接口错误: " + ex.getMessage());
                    sendResponse(exchange, 500, "{\"error\":\"internal\"}");
                }
            } else { sendResponse(exchange, 405, "{}"); }
        });

        // --- 公告编辑器 REST API ---
        server.createContext("/api/announcements", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                if (!allowLegacyAnnouncementRequest(exchange)) return;
                AnnouncementStore store = getAnnouncementStore();
                if ("GET".equals(exchange.getRequestMethod())) {
                    AnnouncementStore.Snapshot snapshot = store.read();
                    org.json.JSONArray visible = new org.json.JSONArray(snapshot.announcements.toString());
                    for (int i = 0; i < visible.length(); i++) {
                        org.json.JSONObject item = visible.optJSONObject(i);
                        if (item != null && "2000000001".equals(item.optString("groupId"))) item.put("groupId", "****");
                    }
                    exchange.getResponseHeaders().set("ETag", "\"" + snapshot.revision + "\"");
                    sendResponse(exchange, 200, visible.toString());
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    if (!requireAdmin(exchange)) return;
                    String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                    org.json.JSONArray incoming = new org.json.JSONArray(body);
                    for (int i = 0; i < incoming.length(); i++) {
                        org.json.JSONObject item = incoming.optJSONObject(i);
                        if (item != null && "****".equals(item.optString("groupId"))) item.put("groupId", "2000000001");
                    }
                    String expectedRevision = exchange.getRequestHeaders().getFirst("If-Match");
                    if (expectedRevision == null || expectedRevision.trim().isEmpty()) {
                        AnnouncementStore.Snapshot latest = store.read();
                        exchange.getResponseHeaders().set("ETag", "\"" + latest.revision + "\"");
                        store.auditEvent("SAVE_REJECTED_MISSING_REVISION", announcementActor(exchange),
                            new org.json.JSONObject().put("currentRevision", latest.revision));
                        sendResponse(exchange, 428, "{\"error\":\"reload announcements before saving\"}");
                        return;
                    }
                    AnnouncementStore.ReplaceResult result = store.replace(incoming.toString(),
                        expectedRevision, announcementActor(exchange));
                    exchange.getResponseHeaders().set("ETag", "\"" + result.snapshot.revision + "\"");
                    if (result.conflict) {
                        sendResponse(exchange, 409, "{\"error\":\"announcements changed; reload required\"}");
                        return;
                    }
                    sendResponse(exchange, 200, "{\"ok\":true}");
                } else if ("POST".equals(exchange.getRequestMethod())) {
                    if (!requireAdmin(exchange)) return;
                    String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                    org.json.JSONObject announcement = new org.json.JSONObject(body);
                    if ("****".equals(announcement.optString("groupId"))) announcement.put("groupId", "2000000001");
                    String gid = announcement.optString("groupId", "");
                    String title = announcement.optString("title", "");
                    String content = announcement.optString("content", "");
                    if (gid.isEmpty() || title.isEmpty()) {
                        sendResponse(exchange, 400, "{\"error\":\"groupId and title are required\"}"); return;
                    }
                    if (announcement.optString("id", "").isEmpty()) announcement.put("id", java.util.UUID.randomUUID().toString());
                    AnnouncementStore.Actor actor = announcementActor(exchange);
                    store.auditEvent("IMMEDIATE_SEND_ATTEMPT", actor,
                        new org.json.JSONObject().put("announcement", new org.json.JSONObject(announcement.toString())));
                    AnnouncementStore.SendResult sendResult = sendAnnouncement(announcement);
                    store.auditEvent(sendResult.success ? "IMMEDIATE_SEND_SUCCESS" : "IMMEDIATE_SEND_FAILED", actor,
                        new org.json.JSONObject().put("announcement", new org.json.JSONObject(announcement.toString()))
                            .put(sendResult.success ? "detail" : "error", sendResult.detail));
                    if (!sendResult.success) {
                        sendResponse(exchange, 502, "{\"ok\":false,\"error\":\"NapCat send failed\"}"); return;
                    }
                    // POST is send-only. It must never replace the announcements array with one object.
                    sendResponse(exchange, 200, "{\"ok\":true}");
                } else { sendResponse(exchange, 405, "{}"); }
            } catch (Exception ex) { sendResponse(exchange, 500, "{\"error\":\"internal\"}"); }
        });

        // --- 公告编辑器网页 ---
        // 背景图
        server.createContext("/bg.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
            try {
                byte[] img = loadResourceBytes("bg.png");
                exchange.sendResponseHeaders(200, img.length);
                exchange.getResponseBody().write(img);
            } catch (Exception e) { sendResponse(exchange, 404, ""); }
        });

        // 字体服务：列出可用字体
        server.createContext("/api/fonts/list", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                java.io.File[] fontDirs = configuredFontDirectories();
                for (java.io.File dir : fontDirs) {
                    if (!dir.exists()) continue;
                    java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".ttf") || n.endsWith(".otf"));
                    if (files != null) for (java.io.File f : files) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("name", f.getName().replaceAll("\\.(ttf|otf)$", ""));
                        o.put("file", f.getName());
                        arr.put(o);
                    }
                }
                sendResponse(exchange, 200, arr.toString());
            } catch (Exception e) { sendResponse(exchange, 500, "[]"); }
        });

        // 字体文件服务
        server.createContext("/api/fonts/file", exchange -> {
            addCors(exchange);
            try {
                String name = exchange.getRequestURI().getQuery();
                if (name == null || !name.matches("^[a-zA-Z0-9._-]+\\\\.(ttf|otf)$")) {
                    sendResponse(exchange, 400, "bad name"); return;
                }
                java.io.File[] searchDirs = configuredFontDirectories();
                java.io.File found = null;
                for (java.io.File dir : searchDirs) {
                    java.io.File f = new java.io.File(dir, name);
                    if (f.exists()) { found = f; break; }
                }
                if (found == null) { sendResponse(exchange, 404, ""); return; }
                byte[] fontBytes = java.nio.file.Files.readAllBytes(found.toPath());
                String ct = name.endsWith(".ttf") ? "font/ttf" : "font/otf";
                exchange.getResponseHeaders().set("Content-Type", ct);
                exchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
                exchange.sendResponseHeaders(200, fontBytes.length);
                try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(fontBytes); }
            } catch (Exception e) { sendResponse(exchange, 500, ""); }
        });

        // 卡片设计器：保存 CSS 到独立样式文件，并更新 index.html 中的配置 JSON。
        server.createContext("/api/design/save", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { sendResponse(exchange, 405, "POST only"); return; }
            try {
                if (!requireAdmin(exchange)) return;
                String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                org.json.JSONObject o = new org.json.JSONObject(body);
                String rawCss = o.optString("css", "");
                int maxWidth = o.optInt("maxWidth", 1200);
                File deployDir = getEditorDeployDirectory();
                File idx = new File(deployDir, "index.html");
                File cssFile = new File(deployDir, "assets/css/app.css");
                if (!idx.exists() || !cssFile.exists()) {
                    sendResponse(exchange, 404, "editor files not found"); return;
                }
                String content = new String(java.nio.file.Files.readAllBytes(idx.toPath()), StandardCharsets.UTF_8);
                String cssContent = new String(java.nio.file.Files.readAllBytes(cssFile.toPath()), StandardCharsets.UTF_8);
                // 替换范围：从 /* --- 曲库查询 --- */ 到 /* --- 卡片设计器 --- */（含歌曲详情弹窗区域）
                // rawCss 已包含所有 .lib-detail、.ld-* 规则，不再硬编码
                String pattern = "(\\/\\* --- 曲库查询 --- \\*\\/)[\\s\\S]*?(?=\\/\\* --- 卡片设计器)";
                String replacement = "/* --- 曲库查询 --- */\n"
                    + ".lib-search{width:100%;max-width:"+maxWidth+"px;padding:10px 16px;border:2px solid #cbd5e1;border-radius:12px;font-size:14px;font-family:inherit;outline:none;margin-bottom:16px;display:block;margin-left:auto;margin-right:auto}\n"
                    + ".lib-search:focus{border-color:#3b82f6}\n"
                    + ".lib-grid{display:flex;flex-direction:column;gap:8px}\n"
                    + rawCss + "\n"
                    + ".lib-stats{text-align:center;color:#64748b;font-size:13px;margin-bottom:12px}\n";
                java.util.regex.Pattern cssPattern = java.util.regex.Pattern.compile(pattern);
                if (!cssPattern.matcher(cssContent).find()) {
                    sendResponse(exchange, 500, "{\"error\":\"CSS markers not found\"}"); return;
                }
                cssContent = cssPattern.matcher(cssContent)
                    .replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
                // 同时更新 dsConfig 设计配置 JSON
                String configJson = o.optString("config", "");
                if (!configJson.isEmpty()) {
                    configJson = new org.json.JSONObject(configJson).toString().replace("<", "\\u003c");
                    String cfgPattern = "<script id=\"dsConfig\"[^>]*>[^<]*</script>";
                    String cfgReplacement = "<script id=\"dsConfig\" type=\"application/json\">" + configJson + "</script>";
                    if (content.contains("id=\"dsConfig\"")) {
                        content = content.replaceAll(cfgPattern, java.util.regex.Matcher.quoteReplacement(cfgReplacement));
                    } else {
                        content = content.replace("</body>", cfgReplacement + "\n</body>");
                    }
                }
                writeUtf8Atomically(cssFile, cssContent);
                writeUtf8Atomically(idx, content);
                System.out.println("[设计器] CSS 已更新 (" + rawCss.length() + " chars)");
                sendResponse(exchange, 200, "{\"ok\":true}");
            } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\""+String.valueOf(e.getMessage()).replace("\"","'")+"\"}"); }
        });

        server.createContext("/editor", exchange -> {
            String html = loadEditorHtml();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(html.getBytes(StandardCharsets.UTF_8)); }
        });

        // 独立前端资源。路径会归一化并限制在 deploy/assets 与 deploy/fonts 内。
        server.createContext("/assets/", exchange -> serveEditorStatic(exchange, "assets", "/assets/"));
        server.createContext("/fonts/", exchange -> serveEditorStatic(exchange, "fonts", "/fonts/"));

        server.createContext("/webhook", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    if (!isLoopbackRequest(exchange)) { sendResponse(exchange, 403, "Forbidden"); return; }
                    String requestBody = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                    handleLogic(exchange, requestBody);
                } catch (Exception e) {
                    System.err.println("❌ " + e.getMessage());
                    sendResponse(exchange, 500, "Error");
                }
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        // --- MCZ 谱面解析 API ---
        server.createContext("/api/mcz/parse", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { sendResponse(exchange, 405, "{}"); return; }
            try {
                if (!requireAdmin(exchange)) return;
                // raw body 上传，文件名从 header 取
                String fileName = exchange.getRequestHeaders().getFirst("X-Filename");
                if (fileName == null || fileName.isEmpty()) fileName = "upload.mcz";
                fileName = safeFileName(fileName, "upload.mcz");
                if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".mcz")) {
                    sendResponse(exchange, 400, "{\"error\":\"only .mcz files are accepted\"}"); return;
                }
                byte[] fileData = readRequestBody(exchange, MAX_UPLOAD_BODY_BYTES);

                // 处理 MCZ
                File tempDir = new File(System.getProperty("java.io.tmpdir"), "mcz_parse_" + System.currentTimeMillis());
                tempDir.mkdirs();
                File mczFile = new File(tempDir, fileName);
                java.nio.file.Files.write(mczFile.toPath(), fileData);

                com.mcz.MczParser.unzip(mczFile, tempDir);

                Map<String, String> info = new java.util.HashMap<>();
                info.put("songName", "未知"); info.put("artist", "未知");
                java.util.List<Map<String, String>> charts = new java.util.ArrayList<>();
                String[] duration = {"00:00"};
                java.util.List<String> imgTokens = new java.util.ArrayList<>();
                java.util.List<String> imageRatios = new java.util.ArrayList<>();
                String[] audioToken = {""};
                // 图片+音频存到临时目录，通过流式端点返回
                File serveDir = new File(System.getProperty("java.io.tmpdir"), "mcz_serve");
                serveDir.mkdirs();

                java.nio.file.Files.walkFileTree(tempDir.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        String fname = file.getFileName().toString().toLowerCase();
                        File src = file.toFile();
                        if (fname.endsWith(".mc")) {
                            String content = new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8);
                            if ("未知".equals(info.get("songName"))) {
                                String t = com.mcz.MczParser.extractJsonValue(content, "titleorg");
                                if (t == null || t.isEmpty()) t = com.mcz.MczParser.extractJsonValue(content, "title");
                                if (t != null && !t.isEmpty()) info.put("songName", t);
                            }
                            if ("未知".equals(info.get("artist"))) {
                                String a = com.mcz.MczParser.extractJsonValue(content, "artistorg");
                                if (a == null || a.isEmpty()) a = com.mcz.MczParser.extractJsonValue(content, "artist");
                                if (a != null && !a.isEmpty()) info.put("artist", a);
                            }
                            String version = com.mcz.MczParser.extractJsonValue(content, "version");
                            String level = "0";
                            java.util.regex.Matcher lm = java.util.regex.Pattern.compile("(?i)lv\\.?\\s*(\\d+)").matcher(version);
                            if (lm.find()) level = lm.group(1);
                            int kNum = 4;
                            String uv = version.toUpperCase();
                            if (uv.contains("5K")) kNum = 5; else if (uv.contains("6K")) kNum = 6;
                            else if (uv.contains("7K")) kNum = 7; else if (uv.contains("8K")) kNum = 8;
                            double bpm = com.mcz.MczParser.extractInitialBpm(content);
                            Map<String, String> cd = new java.util.HashMap<>();
                            cd.put("kMode", kNum + "K"); cd.put("level", level);
                            cd.put("bpm", String.valueOf(bpm).replace(".0",""));
                            cd.put("combo", String.valueOf(com.mcz.MczParser.calculateMaxCombo(content, bpm, kNum)));
                            cd.put("version", version);
                            cd.put("charter", com.mcz.MczParser.extractJsonValue(content, "creator"));
                            if (content.contains("\"mode\":3") || content.contains("\"mode\" : 3") || content.contains("\"mode\": 3")) {
                                cd.put("isMode3", "true");
                                String[] m3d = {"Deluge","Overdose","Rain","Platter","Salad","Cup"};
                                for (String d : m3d) if (uv.contains(d.toUpperCase())) { cd.put("diffName", d); break; }
                            }
                            charts.add(cd);
                        } else if (com.mcz.MczParser.isImage(fname)) {
                            try {
                                String token = "img_" + java.util.UUID.randomUUID() + safeMediaExtension(fname);
                                java.nio.file.Files.copy(file, new File(serveDir, token).toPath());
                                imgTokens.add(token);
                                scheduleMczTempDeletion(new File(serveDir, token));
                                try {
                                    java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(src);
                                    if (bi != null) imageRatios.add(bi.getWidth() + "x" + bi.getHeight());
                                    else imageRatios.add("?");
                                } catch (Exception ex2) { imageRatios.add("?"); }
                            } catch (Exception ignored) {}
                        } else if (com.mcz.MczParser.isAudio(fname)) {
                            if (audioToken[0].isEmpty()) {
                                audioToken[0] = "aud_" + java.util.UUID.randomUUID() + safeMediaExtension(fname);
                                java.nio.file.Files.copy(src.toPath(), new File(serveDir, audioToken[0]).toPath());
                                scheduleMczTempDeletion(new File(serveDir, audioToken[0]));
                            }
                            if ("00:00".equals(duration[0])) {
                                try { duration[0] = com.mcz.MczParser.getDurationWithFFmpeg(src, "ffmpeg"); } catch (Exception ignored) {}
                            }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
                org.json.JSONObject root = new org.json.JSONObject();
                root.put("fileName", fileName);
                root.put("songName", info.get("songName"));
                root.put("artist", info.get("artist"));
                root.put("duration", duration[0]);
                org.json.JSONArray chartArr = new org.json.JSONArray();
                for (Map<String, String> c : charts) {
                    org.json.JSONObject co = new org.json.JSONObject();
                    for (Map.Entry<String, String> e : c.entrySet()) co.put(e.getKey(), e.getValue());
                    chartArr.put(co);
                }
                root.put("charts", chartArr);
                org.json.JSONArray imgArr = new org.json.JSONArray();
                for (String t : imgTokens) imgArr.put(t);
                root.put("imgTokens", imgArr);
                org.json.JSONArray ratioArr = new org.json.JSONArray();
                for (String r : imageRatios) ratioArr.put(r);
                root.put("imageRatios", ratioArr);
                root.put("audioToken", audioToken[0]);
                com.mcz.MczParser.deleteDirectory(tempDir);
                sendResponse(exchange, 200, root.toString());
            } catch (Exception ex) {
                ex.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + String.valueOf(ex.getMessage()).replace("\"","\\\"") + "\"}");
            }
        });

        // --- Excel 写入 API ---
        server.createContext("/api/excel/write", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { sendResponse(exchange, 405, "{}"); return; }
            try {
                if (!requireAdmin(exchange)) return;
                String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                // 接收 JSON: {songName,artist,duration,fileName,charts:[...],selectedImage}
                java.util.function.Function<String,String> jv = key -> {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\""+key+"\":\"((?:[^\"\\\\]|\\\\.)*?)\"").matcher(body);
                    return m.find() ? m.group(1).replace("\\\"","\"").replace("\\\\","\\").replace("\\n","\n") : "";
                };
                String songName = jv.apply("songName");
                String artist = jv.apply("artist");
                String duration = jv.apply("duration");
                if (songName.isEmpty()) { sendResponse(exchange, 400, "{\"error\":\"songName required\"}"); return; }
                // 写入 Excel（走 MczTool 的 ExcelManager）
                com.mybot.compat.HeadlessExcelManager.initExcel(null); // 无界面端点占位，不加载 MczMaker GUI
                // 简化版：只返回成功，完整写入逻辑需要更多参数
                sendResponse(exchange, 200, "{\"ok\":true,\"msg\":\"已接收: " + songName.replace("\"","") + "\"}");
            } catch (Exception ex) {
                sendResponse(exchange, 500, "{\"error\":\"" + String.valueOf(ex.getMessage()).replace("\"","") + "\"}");
            }
        });

        // --- 音频流式播放 ---
        server.createContext("/api/mcz/audio", exchange -> {
            addCors(exchange);
            serveMczTempFile(exchange);
        });


        // --- MCZ 静态文件服务 (图片/音频) ---
        server.createContext("/api/mcz/file", exchange -> {
            addCors(exchange);
            serveMczTempFile(exchange);
        });

        // --- 文件上传 (图/附件 → 存本地) ---
        server.createContext("/api/upload", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!allowLegacyAnnouncementRequest(exchange)) return;
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    if (!requireAdmin(exchange)) return;
                    String fname = exchange.getRequestHeaders().getFirst("X-Filename");
                    if (fname == null || fname.isEmpty()) fname = "file.bin";
                    try { fname = java.net.URLDecoder.decode(fname, "UTF-8"); } catch (Exception ignored) {}
                    String session = exchange.getRequestHeaders().getFirst("X-Session");
                    if (session == null || session.isEmpty()) session = "common";
                    String type = exchange.getRequestHeaders().getFirst("X-Type");
                    if (type == null || type.isEmpty()) type = "attach";
                    if (!isSafePathSegment(session) || !("attach".equals(type) || "image".equals(type))) {
                        sendResponse(exchange, 400, "{\"error\":\"invalid upload target\"}"); return;
                    }
                    fname = safeFileName(fname, null);
                    if (fname == null) { sendResponse(exchange, 400, "{\"error\":\"invalid file name\"}"); return; }
                    byte[] data = readRequestBody(exchange, MAX_UPLOAD_BODY_BYTES);
                    File baseDir = new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "announce_files");
                    File sessionDir = resolveChildFile(baseDir, session);
                    File uploadDir = sessionDir == null ? null : resolveChildFile(sessionDir, type);
                    if (uploadDir == null) { sendResponse(exchange, 400, "{\"error\":\"invalid upload target\"}"); return; }
                    uploadDir.mkdirs();
                    File dest = resolveChildFile(uploadDir, fname);
                    if (dest == null) { sendResponse(exchange, 400, "{\"error\":\"invalid file name\"}"); return; }
                    if (dest.exists()) {
                        int dot = fname.lastIndexOf('.');
                        String stem = dot > 0 ? fname.substring(0, dot) : fname;
                        String ext = dot > 0 ? fname.substring(dot) : "";
                        fname = stem + "-" + System.currentTimeMillis() + ext;
                        dest = resolveChildFile(uploadDir, fname);
                        if (dest == null) { sendResponse(exchange, 400, "{\"error\":\"invalid file name\"}"); return; }
                    }
                    java.nio.file.Files.write(dest.toPath(), data);
                    System.out.println("📎 上传: " + fname + " → " + dest.getAbsolutePath() + " (" + data.length + " bytes)");
                    sendResponse(exchange, 200, "{\"token\":\"" + session + "/" + type + "/" + fname.replace("\"","") + "\"}");
                } catch (Exception ex) { System.err.println("❌ 上传失败: " + ex.getMessage()); sendResponse(exchange, 500, "{\"error\":\"\"}"); }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                if (!requireAdmin(exchange)) return;
                String session = exchange.getRequestHeaders().getFirst("X-Session");
                String fname = exchange.getRequestHeaders().getFirst("X-Filename");
                    try { fname = java.net.URLDecoder.decode(fname, "UTF-8"); } catch (Exception ignored) {}
                File baseDir = new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "announce_files");
                if (!isSafePathSegment(session)) { sendResponse(exchange, 400, "{\"error\":\"invalid session\"}"); return; }
                File sessionDir = resolveChildFile(baseDir, session);
                if (fname != null && !fname.isEmpty()) {
                    File f = null;
                    String normalized = fname.replace('\\', '/');
                    java.util.regex.Matcher tokenPath = java.util.regex.Pattern
                        .compile("^(attach|image)/([^/]+)$").matcher(normalized);
                    if (tokenPath.matches()) {
                        String leaf = safeFileName(tokenPath.group(2), null);
                        File typeDir = resolveChildFile(sessionDir, tokenPath.group(1));
                        if (leaf != null && typeDir != null) f = resolveChildFile(typeDir, leaf);
                    } else {
                        String leaf = safeFileName(fname, null);
                        if (leaf != null) {
                            File attachDir = resolveChildFile(sessionDir, "attach");
                            File imageDir = resolveChildFile(sessionDir, "image");
                            File attachFile = attachDir == null ? null : resolveChildFile(attachDir, leaf);
                            File imageFile = imageDir == null ? null : resolveChildFile(imageDir, leaf);
                            if (attachFile != null && attachFile.exists()) f = attachFile;
                            else if (imageFile != null && imageFile.exists()) f = imageFile;
                        }
                    }
                    if (f == null) { sendResponse(exchange, 400, "{\"error\":\"invalid file name\"}"); return; }
                    java.nio.file.Files.deleteIfExists(f.toPath());
                } else {
                    if (sessionDir.exists()) { try { com.mcz.MczParser.deleteDirectory(sessionDir); } catch (Exception ignored) {} }
                }
                sendResponse(exchange, 200, "{\"ok\":true}");
            } else { sendResponse(exchange, 405, "{}"); }
        });

        // --- IP 访问记录 ---
        server.createContext("/api/visit", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                String ip = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
                if (ip == null || ip.isEmpty()) ip = exchange.getRequestHeaders().getFirst("X-Real-IP");
                if (ip == null || ip.isEmpty()) ip = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
                if (ip == null || ip.isEmpty()) ip = exchange.getRemoteAddress().getAddress().getHostAddress();
                // X-Forwarded-For 可能包含逗号分隔的多个IP，取第一个
                if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
                if (ip == null || ip.isEmpty() || ip.startsWith("127.") || ip.startsWith("0:")) ip = "本地测试";
                // 查归属地
                String loc = "未知";
                try {
                    java.net.URL u = new java.net.URL("http://ip-api.com/json/" + ip + "?lang=zh-CN&fields=country,regionName,city");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(3000); c.setReadTimeout(3000);
                    String resp = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    org.json.JSONObject locationJson = new org.json.JSONObject(resp);
                    String country = locationJson.optString("country", "");
                    String region = locationJson.optString("regionName", "");
                    String city = locationJson.optString("city", "");
                    loc = (country.isEmpty() ? "" : country + " ") + (region.isEmpty() ? "" : region + " ") + city;
                    if (loc.trim().isEmpty()) loc = "未知";
                } catch (Exception ignored) {}
                // 写日志
                String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String line = "[" + time + "] IP:" + ip + " 归属:" + loc.trim() + System.lineSeparator();
                File mczDir = new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot");
                mczDir.mkdirs();
                java.nio.file.Files.write(
                    new File(mczDir, "visits.log").toPath(),
                    line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                sendResponse(exchange, 200, "{\"ok\":true}");
            } catch (Exception ignored) { sendResponse(exchange, 200, "{\"ok\":true}}"); }
        });

        // --- 曲库网页：引导数据（点赞数 + 本IP今日已赞 + 谱面清单）---
        server.createContext("/api/meta", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                String ip = getClientIp(exchange);
                String today = java.time.LocalDate.now().toString();
                org.json.JSONObject likes = new org.json.JSONObject();
                for (Map.Entry<Integer,Integer> e : dbService.getAllLikeCounts().entrySet())
                    likes.put(String.valueOf(e.getKey()), e.getValue());
                org.json.JSONArray likedToday = new org.json.JSONArray();
                for (Integer id : dbService.getLikedTodayByIp(ip, today)) likedToday.put(id);
                org.json.JSONObject out = new org.json.JSONObject();
                out.put("likes", likes);
                out.put("likedToday", likedToday);
                sendResponse(exchange, 200, out.toString());
            } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"\"}"); }
        });

        // --- 点赞 / 取消点赞 ---
        server.createContext("/api/like", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                int id = new org.json.JSONObject(body.isEmpty() ? "{}" : body).optInt("id", -1);
                if (id < 0) { sendResponse(exchange, 400, "{\"error\":\"bad id\"}"); return; }
                String ip = getClientIp(exchange);
                String today = java.time.LocalDate.now().toString();
                String m = exchange.getRequestMethod();
                boolean liked;
                if ("POST".equals(m)) { dbService.addLike(ip, id, today); liked = true; }
                else if ("DELETE".equals(m)) { dbService.removeLike(ip, id, today); liked = false; }
                else { sendResponse(exchange, 405, "{}"); return; }
                int count = dbService.getLikeCount(id);
                scheduleLibPush();
                sendResponse(exchange, 200, "{\"id\":" + id + ",\"count\":" + count + ",\"liked\":" + liked + "}");
            } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"\"}"); }
        });

        // --- 管理员校验（设备存服务端允许名单，可手动撤销）---
        server.createContext("/api/admin/check", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                String q = exchange.getRequestURI().getQuery();
                String d = null;
                if (q != null) for (String kv : q.split("&")) if (kv.startsWith("d=")) d = java.net.URLDecoder.decode(kv.substring(2), "UTF-8");
                boolean ok = !isIpBlocked(getClientIp(exchange)) && d != null && isAdminDevice(d);
                sendResponse(exchange, 200, "{\"admin\":" + ok + "}");
            } catch (Exception e) { sendResponse(exchange, 200, "{\"admin\":false}"); }
        });

        // --- 管理员授权（服务端校验暗号，命中则把设备加入名单）---
        server.createContext("/api/admin/grant", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { sendResponse(exchange, 405, "{}"); return; }
            try {
                String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                org.json.JSONObject o = new org.json.JSONObject(body.isEmpty() ? "{}" : body);
                String d = o.optString("d", "");
                String p = o.optString("p", "");
                String ip = getClientIp(exchange);
                if (isIpBlocked(ip)) { sendResponse(exchange, 200, "{\"admin\":false}"); return; }
                if (!isSafePathSegment(d) || !adminPassphraseMatches(p)) { sendResponse(exchange, 200, "{\"admin\":false}"); return; }
                grantAdmin(d, ip);
                sendResponse(exchange, 200, "{\"admin\":true}");
            } catch (Exception e) { sendResponse(exchange, 200, "{\"admin\":false}"); }
        });

        // ========== 博客文章管理 API ==========
        File blogDir = new File("D:/my-blog/source/_posts");
        if (!blogDir.exists()) blogDir.mkdirs();
        final CloudWebsitePostClient websiteCloudClient;
        if (CloudAnnouncementClient.cloudEnabled(SONG_BOT_HOME.toFile())) {
            CloudWebsitePostClient configured = null;
            try { configured = CloudWebsitePostClient.fromEnvironment(SONG_BOT_HOME.toFile()); }
            catch (Exception e) { System.err.println("[网站文章云同步] 未启用: " + e.getMessage()); }
            websiteCloudClient = configured;
        } else websiteCloudClient = null;

        // 列出所有 md 文件
        server.createContext("/api/blog/list", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                if (!requireAdmin(exchange)) return;
                if (websiteCloudClient != null) {
                    sendResponse(exchange, 200, websiteCloudClient.list().toString());
                    return;
                }
                org.json.JSONArray arr = new org.json.JSONArray();
                java.io.File[] files = blogDir.listFiles((d, n) -> n.endsWith(".md"));
                if (files != null) {
                    java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (java.io.File f : files) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("name", f.getName());
                        o.put("size", f.length());
                        o.put("modified", f.lastModified());
                        arr.put(o);
                    }
                }
                sendResponse(exchange, 200, arr.toString());
            } catch (Exception e) { sendResponse(exchange, 500, "[]"); }
        });

        // 读取单个 md 文件
        server.createContext("/api/blog/read", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            try {
                if (!requireAdmin(exchange)) return;
                String name = decodeRawQuery(exchange);
                if (websiteCloudClient != null) {
                    org.json.JSONObject post = websiteCloudClient.read(name);
                    websiteCloudClient.mirrorSavedPost(blogDir, post);
                    sendResponse(exchange, 200, post.toString());
                    return;
                }
                java.io.File f = resolveMarkdownFile(blogDir, name);
                if (f == null) { sendResponse(exchange, 400, ""); return; }
                if (!f.exists()) { sendResponse(exchange, 404, ""); return; }
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", f.getName());
                o.put("content", content);
                o.put("modified", f.lastModified());
                sendResponse(exchange, 200, o.toString());
            } catch (Exception e) { sendResponse(exchange, 500, ""); }
        });

        // 保存/创建 md 文件
        server.createContext("/api/blog/save", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { sendResponse(exchange, 405, ""); return; }
            try {
                if (!requireAdmin(exchange)) return;
                String body = new String(readRequestBody(exchange, MAX_JSON_BODY_BYTES), StandardCharsets.UTF_8);
                org.json.JSONObject o = new org.json.JSONObject(body);
                String name = o.getString("name");
                String content = o.getString("content");
                if (websiteCloudClient != null) {
                    Integer revision = o.has("revision") && !o.isNull("revision") ? o.getInt("revision") : null;
                    org.json.JSONObject saved = websiteCloudClient.save(name, content, revision);
                    websiteCloudClient.mirrorSavedPost(blogDir, saved);
                    System.out.println("[Blog] 已保存到云端并同步本机: " + name);
                    sendResponse(exchange, 200, saved.toString());
                    return;
                }
                java.io.File f = resolveMarkdownFile(blogDir, name);
                if (f == null) { sendResponse(exchange, 400, ""); return; }
                writeUtf8Atomically(f, content);
                System.out.println("[Blog] 已保存: " + name);
                sendResponse(exchange, 200, "{\"ok\":true}");
            } catch (Exception e) { sendResponse(exchange, 500, "{\"ok\":false}"); }
        });

        // 删除 md 文件
        server.createContext("/api/blog/delete", exchange -> {
            addCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendResponse(exchange, 204, ""); return; }
            if (!"POST".equals(exchange.getRequestMethod())) { sendResponse(exchange, 405, ""); return; }
            try {
                if (!requireAdmin(exchange)) return;
                String name = decodeRawQuery(exchange);
                if (websiteCloudClient != null) {
                    org.json.JSONObject current = websiteCloudClient.read(name);
                    websiteCloudClient.delete(name, current.getInt("revision"));
                    websiteCloudClient.archiveLocal(blogDir, name);
                    sendResponse(exchange, 200, "{\"ok\":true}");
                    return;
                }
                java.io.File f = resolveMarkdownFile(blogDir, name);
                if (f == null) {
                    sendResponse(exchange, 400, "{\"ok\":false,\"error\":\"invalid name\"}"); return;
                }
                if (!f.exists()) { sendResponse(exchange, 404, "{\"ok\":false,\"error\":\"not found\"}"); return; }
                if (!java.nio.file.Files.deleteIfExists(f.toPath())) {
                    sendResponse(exchange, 404, "{\"ok\":false,\"error\":\"not found\"}"); return;
                }
                System.out.println("[Blog] 已删除: " + name);
                sendResponse(exchange, 200, "{\"ok\":true}");
            } catch (Exception e) { sendResponse(exchange, 500, "{\"ok\":false}"); }
        });

        // 创建公告附件存储目录
        File announceDir = new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "announce_files");
        announceDir.mkdirs();
        System.out.println("📁 公告附件目录: " + announceDir.getAbsolutePath() + (announceDir.exists() ? " ✓" : " ✗"));

        // Do not use HttpServer's single-thread default executor. A client that
        // leaves an HTTP request incomplete can otherwise stall every endpoint,
        // including the local editor page.
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("🚀 监听端口 8080... 等待消息...");
        if (websiteCloudClient != null) {
            ScheduledExecutorService websiteSyncScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "website-post-cloud-sync"); thread.setDaemon(true); return thread;
            });
            websiteSyncScheduler.scheduleWithFixedDelay(() -> {
                try { websiteCloudClient.syncTo(blogDir); }
                catch (Exception e) { System.err.println("[网站文章云同步] 本轮失败，保留本机文件: " + e.getMessage()); }
            }, 3, 5 * 60, TimeUnit.SECONDS);
        }
    }

    // --- V100: 繁简/异体字 搜索词膨胀工具 ---
    private static Set<String> generateSearchVariants(String input) {
        Set<String> variants = new HashSet<>();
        if (input == null || input.isEmpty()) return variants;

        // 1. 保留原始输入
        variants.add(input);

        try {
            // 2. 转为简体 (处理用户输入繁体的情况)
            String simple = ZhConverterUtil.toSimple(input);
            variants.add(simple);

            // 3. 转为繁体 (标准繁体，覆盖大部分日文汉字/港台字)
            // opencc4j 的 toTraditional 通常能覆盖大多数情况
            String traditional = ZhConverterUtil.toTraditional(simple);
            variants.add(traditional);

            // 4. 如果需要更激进的地区词转换 (如 软件->軟體)，opencc4j 作为一个库
            // 在默认模式下主要处理字形。对于搜歌来说，字形转换通常够用了。
            // (比如 "气" -> "氣", "爱" -> "愛")

        } catch (Exception e) {
            // 发生异常（如库未加载）时降级处理，只查原词
            System.err.println("繁简转换异常: " + e.getMessage());
        }

        return variants;
    }
    private static void handleLogic(HttpExchange exchange, String requestBody) throws IOException {
        // --- V93: 监听群成员变动 (插在方法最前面) ---
        // 在 handleLogic 方法的最前面 (处理 notice 的部分) 添加：

        // ... inside handleLogic ...
        String postType = extractJsonString(requestBody, "post_type");


        if ("notice".equals(postType)) {
            String noticeType = extractJsonString(requestBody, "notice_type");
            long noticeGroupId = extractJsonLong(requestBody, "group_id");
            long noticeUserId = extractJsonLong(requestBody, "user_id");

            // 1. 监听入群 (group_increase) -> 只记录时间，不发通知
            if ("group_increase".equals(noticeType)) {
                dbService.recordJoinTime(noticeGroupId, noticeUserId);
                // System.out.println("记录入群: " + noticeUserId);
            }

            // 2. 监听退群 (group_decrease) -> 计算时长并播报
            else if ("group_decrease".equals(noticeType)) {
                // 获取子类型 (leave=主动退, kick=被踢, kick_me=Bot被踢)
                String subType = extractJsonString(requestBody, "sub_type");

                // 从数据库拿入群时间
                long joinTime = dbService.getAndRemoveJoinTime(noticeGroupId, noticeUserId);
                String timeStr;

                if (joinTime > 0) {
                    long diff = System.currentTimeMillis() - joinTime;
                    long days = diff / (1000 * 60 * 60 * 24);
                    long hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
                    long minutes = (diff % (1000 * 60 * 60)) / (1000 * 60);
                    timeStr = "在群时长: " + days + "天" + hours + "小时" + minutes + "分";
                } else {
                    timeStr = "在群时长: 未知(早期成员)";
                }

                // 根据 subType 决定文案
                String msg;
                if ("kick".equals(subType)) {
                    msg = "成员 [QQ:" + noticeUserId + "] 已被踢出本群！\n" + timeStr;
                } else {
                    msg = "成员 [QQ:" + noticeUserId + "] 已离开本群。\n" + timeStr;
                }

                apiClient.sendGroupMessage(noticeGroupId, msg);
            }

            // ============================================================
            // 3. ★★★ 新增：监听表情互动 & 前三名戳一戳 ★★★
            // ============================================================

            // 尝试提取 message_id (NapCat 表情回应事件通常包含此字段)
            int noticeMsgId = (int) extractJsonLong(requestBody, "message_id");

            if (noticeMsgId != 0 && noticeGroupId != 0) {
                // A. 数据库计数 + 判断是否为每日推送消息
                // (注意：这里调用的是刚才修改过返回 boolean 的 incrementReactionCount)
                boolean isDailyMsg = dbService.incrementReactionCount(noticeMsgId, noticeGroupId);

                // B. 如果是每日消息，且触发者有效
                if (isDailyMsg && noticeUserId != 0) {
                    // C. 获取这条消息已戳过的用户列表 (如果不存在则创建，线程安全)
                    List<Long> pokedUsers = POKE_HISTORY_CACHE.computeIfAbsent(noticeMsgId, k -> java.util.Collections.synchronizedList(new ArrayList<>()));

                    // D. 核心判断：名额未满3人 且 这人没被戳过
                    if (pokedUsers.size() < 3 && !pokedUsers.contains(noticeUserId)) {

// 某些 Bot 框架需要 type=1 参数，或者双发
                        // apiClient.sendGroupMessage(noticeGroupId, "[CQ:poke,qq=" + noticeUserId + ",type=1]");

                        // 记录：把这个人加入已戳列表
                        pokedUsers.add(noticeUserId);

                        System.out.println("👉 已戳每日打卡用户: " + noticeUserId + " (第 " + pokedUsers.size() + "/3 位)");
                    }
                }
            }
            // ============================================================

            // 处理完通知，直接返回
            sendResponse(exchange, 200, "{}");
            return;
        }
        String messageContent = extractJsonString(requestBody, "raw_message");
        String messageType = extractJsonString(requestBody, "message_type");
        long groupId = extractJsonLong(requestBody, "group_id");
        long userId = extractJsonLong(requestBody, "user_id");

        // V79: 提取昵称 (用于统计)
        String nickname = extractNickname(requestBody);

        if (messageContent == null || messageContent.isEmpty()) {
            sendResponse(exchange, 200, "{}");
            return;

        }

        String trimMsg = messageContent.trim();
// ============================================================
        // 🚀 【置顶】管理员私聊预览指令 (确保最先匹配)
        // ============================================================
        if (trimMsg.equals("!预览明天") && ADMIN_LIST.contains(userId)) {
            System.out.println("👀 管理员 " + userId + " 请求预览明日推送...");

            int lockedId = dbService.getNextSongId();
            String replyContent;

            if (lockedId == -1) {
                replyContent = "⚠️ 明日推送尚未锁定。\n(可能今日尚未推送，或数据库无记录)";
            } else {
                DailySong nextSong = dbService.getDailySongInfo(lockedId);
                if (nextSong != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("🔮 【明日推送预览】\n");
                    sb.append("------------------\n");
                    sb.append("🔒 锁定状态：✅ 已闭环\n");
                    sb.append("🆔 歌曲ID：").append(lockedId).append("\n");
                    sb.append("🎵 歌名：").append(nextSong.songName).append("\n");
                    sb.append("👤 作者：").append(nextSong.author).append("\n");
                    sb.append("------------------\n");
                    sb.append("说明：此歌即为昨日竞猜的【正确选项】，明日 20:00 必推。");
                    replyContent = sb.toString();
                } else {
                    replyContent = "❌ 数据库有锁定ID (" + lockedId + ")，但在歌曲表中找不到数据！";
                }
            }

            // 必须使用 sendPrivateMessage
            apiClient.sendPrivateMessage(userId, replyContent);

            sendResponse(exchange, 200, "{}");
            return; // 🔥 处理完直接退出
        }
        // ============================================================
        // 🛠️ 【修改版】管理员强制生成 (只锁数据，不发群广播！)
        // ============================================================
        if (trimMsg.equals("!强制推送") && ADMIN_LIST.contains(userId)) {
            // 1. 获取库里所有歌
            List<Integer> availableIds = dbService.getAllDailySongIds();
            if (availableIds.isEmpty()) {
                apiClient.sendPrivateMessage(userId, "❌ 数据库 daily_songs 为空，无法生成。");
                return;
            }

            // 2. 模拟选歌逻辑 (优先读预定，没有则随机)
            int targetId = -1;
            int reservedId = dbService.getNextSongId();

            // 如果已经有预定，就保持预定；如果没有，就随机选一个
            if (reservedId != -1 && availableIds.contains(reservedId)) {
                targetId = reservedId;
            } else {
                targetId = availableIds.get((int)(Math.random() * availableIds.size()));
            }

            // 3. 【关键修改】只生成“明天的锁定数据”，不调用 sendDailyPush 广播！

            // 模拟 sendDailyPush 的一部分逻辑：选出【明天的歌】并锁定
            // 我们先假装今天推了 targetId，然后立刻为明天选一首
            List<Integer> cleanPool = new ArrayList<>(availableIds);
            cleanPool.remove((Integer)targetId); // 排除今天这首

            if (!cleanPool.isEmpty()) {
                int tomorrowId = cleanPool.get((int)(Math.random() * cleanPool.size()));

                // 🔥🔥🔥 核心动作：写入明天的锁定ID 🔥🔥🔥
                dbService.setNextSongId(tomorrowId);

                // 4. 只私聊通知管理员结果
                DailySong todaySong = dbService.getDailySongInfo(targetId);
                DailySong nextSong = dbService.getDailySongInfo(tomorrowId);

                StringBuilder sb = new StringBuilder();
                sb.append("✅ [测试模式] 数据生成完毕 (未广播)\n");
                sb.append("------------------\n");
                sb.append("假装今日推送: ").append(todaySong.songName).append("\n");
                sb.append("🔒 锁定明日推送: ").append(nextSong.songName).append(" (ID: ").append(tomorrowId).append(")\n");
                sb.append("------------------\n");
                sb.append("现在您可以发送 !预览明天 来验证逻辑了。");

                apiClient.sendPrivateMessage(userId, sb.toString());
            } else {
                apiClient.sendPrivateMessage(userId, "❌ 歌曲池太少，无法生成明日数据。");
            }

            sendResponse(exchange, 200, "{}");
            return;
        }
        // 👉👉👉 【插入点 1：测试指令】 👈👈👈

        // 👉👉👉 【替换这个区域：测试指令 (V304 一步到位版)】 👈👈👈

        // --- V304: 赛季测试指令 (模拟日期，直接结算) ---
        if (ADMIN_LIST.contains(userId) && "group".equals(messageType)) {

            // 模拟周结：假装今天是本月 7 号
            if (trimMsg.equals("!测试周结")) {
                apiClient.sendGroupMessage(groupId, "[测试模式] 模拟执行【Day 7】结算...");
                // 伪造一个 7 号的日期传入
                LocalDate mockDate = LocalDate.now().withDayOfMonth(7);
                // 传入 true，表示这是演习，不要删库！
                manageSeasonCycles(mockDate, true);
                return;
            }

            // 模拟月结：假装今天是本月最后一天
            if (trimMsg.equals("!测试月结")) {
                apiClient.sendGroupMessage(groupId, "[测试模式] 模拟执行【月底】结算...");
                // 伪造一个 月底 的日期传入 (自动识别是 28/29/30/31)
                LocalDate mockDate = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
                // 传入 true，表示这是演习，不要删库！
                manageSeasonCycles(mockDate, true);
                return;
            }
        }
        // ... Inside handleLogic, after trimMsg definition ...

// --- V300: 竞猜游戏回复监听 (30分钟窗口) ---
        if (currentGame != null && currentGame.isActive &&
                System.currentTimeMillis() < currentGame.expireTime &&
                "group".equals(messageType)) {

            // ============================================================
            // 【V306 修改】支持引用回复 (清洗 CQ 码 + 校验回复 ID)
            // ============================================================

            // 1. 提取引用 ID (如果存在)
            int replyId = extractCQValueInt(trimMsg, "reply", "id");

            // 2. 清洗消息：去掉 [CQ:...] 标签和首尾空格，只留纯文本
            // 例如 "[CQ:reply,id=123][CQ:at,qq=456] B " -> "B"
            String cleanText = trimMsg.replaceAll("\\[CQ:.*?\\]", "").trim();

            // 3. 严格匹配 A-D
            // 3. 严格匹配 A-D
            if (cleanText.matches("^[a-dA-D]$")) {
                if (System.currentTimeMillis() > currentGame.expireTime) {
                    System.out.println("⏳ 用户 " + nickname + " 尝试竞猜，但已超时 (30分钟限制)");
                    // 可选：回一句提示
                    // reply(groupId, userId, messageType, "⏳ 今日竞猜已结束 (限时30分钟)，明晚20:00请早！");
                    sendResponse(exchange, 200, "{}");
                    return; // 直接结束，不记录也不判分
                }

                // 【关键校验】如果是引用回复，必须回复的是“题目消息”
                if (replyId != 0) {
                    int correctQuestionId = currentGame.questionMsgIds.getOrDefault(groupId, 0);
                    if (replyId != correctQuestionId) {
                        sendResponse(exchange, 200, "{}");
                        return;
                    }
                }

                String userChoice = cleanText.toUpperCase();

                // ============================================================
                // 【核心修复】：双重校验（数据库 + 内存持久化集合）
                // ============================================================
                // 增加 null 保护，防止旧存档加载后集合为 null
                if (currentGame.participatedUsers == null) {
                    currentGame.participatedUsers = java.util.Collections.synchronizedSet(new HashSet<>());
                }

                // 同时检查数据库记录 和 内存中的参与记录
                if (!dbService.hasUserGuessedToday(userId) && !currentGame.participatedUsers.contains(userId)) {

                    // 只要参与了，无论对错，【第一步】必须先存入内存并同步到硬盘
                    currentGame.participatedUsers.add(userId);
                    saveGameSnapshot(); // 立即存入 data/game_session.dat

                    // 2. 判题逻辑
                    if (userChoice.equals(currentGame.correctOption)) {
                        dbService.recordUserScore(userId, nickname);

                        // 记录昵称到光荣榜
                        if (!currentGame.correctNicknames.contains(nickname)) {
                            currentGame.correctNicknames.add(nickname);
                            saveGameSnapshot(); // 再次保存
                        }
                        System.out.println("✅ 用户 " + nickname + " (" + userId + ") 猜对了: " + userChoice);
                    } else {
                        // 猜错了也要记录，dbService 里会更新 last_guess_date
                        dbService.markUserParticipation(userId, nickname);
                        System.out.println("❌ 用户 " + nickname + " (" + userId + ") 猜错了: " + userChoice);
                    }
                } else {
                    // 已参与拦截
                    System.out.println("🚫 用户 " + nickname + " (" + userId + ") 重复竞猜，已被拦截。");
                }

                sendResponse(exchange, 200, "{}");
                return;
            }
        }
        if (trimMsg.equals("!重置今日") && ADMIN_LIST.contains(userId)) {
            int count = dbService.resetTodayGuessStatus();
            if (count >= 0) {
                // 成功的提示
                apiClient.sendGroupMessage(groupId, "✅ [系统公告] 今日竞猜状态已全员重置！\n刚才因音频故障导致竞猜失败的玩家，现在可以重新竞猜了。\n(共处理 " + count + " 人)");
            } else {
                // 失败的提示
                apiClient.sendGroupMessage(groupId, "❌ 重置失败，数据库执行异常，请查看后台日志。");
            }
            sendResponse(exchange, 200, "{}");
            return;
        }
// --- V94: 管理员手动同步群成员时间 ---
        if (trimMsg.equals("!同步成员") && ADMIN_LIST.contains(userId) && "group".equals(messageType)) {
            apiClient.sendGroupMessage(groupId, "正在同步当前群成员入群时间。");

            // 获取列表
            List<long[]> members = apiClient.getGroupMemberList(groupId);
            int count = 0;

            for (long[] mem : members) {
                long uid = mem[0];
                long joinTime = mem[1];
                // 存入数据库
                dbService.recordJoinTime(groupId, uid, joinTime);
                count++;
            }

            apiClient.sendGroupMessage(groupId, "✅ 同步完成！已更新 " + count + " 名成员的入群记录。");
            sendResponse(exchange, 200, "{}");
            return;
        }
        // 权限校验
        if ("group".equals(messageType)) {
            if (!ALLOWED_GROUPS.contains(groupId)) {
                sendResponse(exchange, 200, "{}");
                return;
            }
        } else if (groupId == 0 && !"private".equals(messageType)) {
            sendResponse(exchange, 200, "{}");
            return;
        }


// ... (在 trimMsg = messageContent.trim(); 之后) ...

        // --- V90 新增: 难度统计与查询 ---
        if (trimMsg.startsWith("!") || trimMsg.startsWith("！")) {
            String content = trimMsg.substring(1).trim();

            // 模式1: !难度
            if (content.equals("难度")) {
                dbService.logUserActivity(userId, nickname); // 记账
                dbService.logCommand();
                handleDifficultyStats(groupId, userId, messageType);
                sendResponse(exchange, 200, "{}");
                return;
            }

            // 模式2: !x级 (例如 !15级, !7级)
            if (content.matches("\\d+级")) {
                String numStr = content.replace("级", "");
                try {
                    int targetLevel = Integer.parseInt(numStr);
                    if (targetLevel == 0) { sendResponse(exchange, 200, "{}"); return; }
                    dbService.logUserActivity(userId, nickname);
                    dbService.logCommand();
                    handleDifficultySearch(groupId, userId, messageType, targetLevel, nickname);
                } catch (NumberFormatException e) {}
                sendResponse(exchange, 200, "{}");
                return;
            }

            // 模式3: !谱师名NN级 (例如 !bassor14级, ！Furina8级)
            if (content.matches(".+\\d+级") && !content.matches("\\d+级")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(.+?)(\\d+)级$").matcher(content);
                if (m.find()) {
                    String charterName = m.group(1).trim();
                    try {
                        int targetLevel = Integer.parseInt(m.group(2));
                        if (targetLevel > 0 && !charterName.isEmpty()) {
                            dbService.logUserActivity(userId, nickname);
                            dbService.logCommand();
                            handleCharterDiffSearch(groupId, userId, messageType, charterName, targetLevel, nickname);
                            sendResponse(exchange, 200, "{}");
                            return;
                        }
                    } catch (NumberFormatException e) {}
                }
            }
        }

        // ... (后面接原有的 processSearchTriggers 和 keyword 判断) ...
        // --- 管理员导出指令 ---
// --- 管理员导出指令 ---
        if ((trimMsg.equals("!导出每日统计") || trimMsg.equals("！导出每日统计")) && ADMIN_LIST.contains(userId)) {
            String fileName = dbService.exportDailyStats();
            if (fileName != null) {
                File file = new File(fileName);
                String absPath = file.getAbsolutePath().replace("\\", "/");
                apiClient.sendPrivateMessage(userId, "📊 每日歌曲互动统计：\n[CQ:file,file=file:///" + absPath + "]");
            } else {
                apiClient.sendPrivateMessage(userId, "❌ 导出失败");
            }
            sendResponse(exchange, 200, "{}");
            return;
        }
        if ((trimMsg.equals("!导出") || trimMsg.equals("！导出") || trimMsg.equals("!export"))
                && ADMIN_LIST.contains(userId)) {

            apiClient.sendPrivateMessage(userId, "🎨 正在为您绘制【活跃度趋势看板】，请稍候...");

            // 1. 先发原来的 CSV (积分表)
            String filename = dbService.exportUserStatsToCsv(apiClient, userId);
            if (filename != null) {
                File file = new File(filename);
                String absPath = file.getAbsolutePath().replace("\\", "/");
                apiClient.sendPrivateMessage(userId, "📊 用户积分数据:\n[CQ:file,file=file:///" + absPath + "]");
            }

            // 2. 再发新的 趋势图片 (JFreeChart)
            // 注意：generateTrendImage 方法需要在 DatabaseService 里实现
            String imgPath = dbService.generateTrendImage();

            if (imgPath != null) {
                String safePath = imgPath.replace("\\", "/");
                // 使用 [CQ:image] 发送图片，让管理员直接看到图
                apiClient.sendPrivateMessage(userId, "📈 活跃度趋势看板 (近7日 + 全时段):\n[CQ:image,file=file:///" + safePath + "]");
            } else {
                apiClient.sendPrivateMessage(userId, "❌ 趋势图生成失败，请检查后台日志。");
            }

            sendResponse(exchange, 200, "{}");
            return;
        }
// ============================================================
        // 🔀 【智能路由】查询模式开关与群聊隔离逻辑
        // ============================================================
        boolean isCommand = trimMsg.startsWith("!") || trimMsg.startsWith("！") || trimMsg.startsWith("#") || trimMsg.startsWith("＃");

        if (isCommand && "group".equals(messageType)) {
            // 1. 判断是否应该让 Stable 处理
            // 规则：只要是模式 1 或 3，或者是正式推广群 2000000006L，都丢给 Stable 查
            if (stableService != null && (QUERY_MODE == 1 || QUERY_MODE == 3 || groupId == 2000000006L)) {
                stableService.handleGroupMessage(groupId, userId, trimMsg);
            }

            // 2. 🧱 【完美物理隔离墙】
            // 如果这是正式推广群 2000000006L，或者当前设为了模式 3，直接把消息拦死！
            // 提前 return，下面的 SongBot 常规查歌代码就绝对不会被执行！
            if (QUERY_MODE == 3 || groupId == 2000000006L) {
                sendResponse(exchange, 200, "{}");
                return;
            }
        }
        // ============================================================
        // ============================================================
        // 业务逻辑分流
        // ============================================================

        // --- 分支 1: 专辑搜索 (# + 数字) ---
        if (trimMsg.startsWith("#") || trimMsg.startsWith("＃")) {
            String content = trimMsg.substring(1).trim();

            if (content.matches("\\d+")) {
                System.out.println("⚡ 专辑指令: [" + content + "]");
                // V79: 记录统计
                dbService.logUserActivity(userId, nickname);
                dbService.logCommand();
                handleAlbumSearch(groupId, userId, messageType, content);
            }
            sendResponse(exchange, 200, "{}");
            return;
        }

        // --- 分支 2: 歌曲搜索 (! + 内容) ---
        if (trimMsg.startsWith("!") || trimMsg.startsWith("！")) {
            String keyword = extractSongQuery(trimMsg); // 获取关键词

            if (keyword != null) {
                System.out.println("⚡ 歌曲指令: [" + keyword + "]");

                // V79: 记录统计
                dbService.logUserActivity(userId, nickname);
                dbService.logCommand();

                keyword = keyword.replace('（', '(').replace('）', ')');

                // 确定记录对象：如果是群聊就锁群号，私聊就锁个人QQ号
                long targetId = (groupId != 0) ? groupId : userId;

                if (keyword.equals("专辑")) {
                    if (checkCooldown(targetId, cdAlbum, warnAlbum, 120, groupId, userId, messageType, nickname)) {
                        // 修改：传入 messageType
                        handleAlbumDirectory(groupId, userId, messageType, false);
                    }
                }
                else if (keyword.equals("全部专辑")) {
                    if (checkCooldown(targetId, cdAllAlbums, warnAllAlbums, 600, groupId, userId, messageType, nickname)) {
                        // 修改：传入 messageType
                        handleAlbumDirectory(groupId, userId, messageType, true);
                    }
                }else if (keyword.equals("曲师")) {
                    handleTopAuthors(groupId, userId, messageType);
                }else if (keyword.matches("\\d+")) {
                    handleDigitalSearch(groupId, userId, messageType, keyword);
                } else {
                    handleKeywordSearch(groupId, userId, messageType, keyword, nickname);
                }
            }
        }

        sendResponse(exchange, 200, "{}");
    }

    // --- 辅助提取器 ---
    private static String extractSongQuery(String trimInput) {
        String content = trimInput.substring(1).trim();
        if (content.isEmpty()) return null;
        if (content.matches("\\d+")) return content;

        // --- V100 修改: 即使是单字，如果转换后能精确匹配，也视为有效查询 ---
        if (content.length() < 2) {
            Set<String> variants = generateSearchVariants(content);
            for (String var : variants) {
                List<Song> exactMatches = dbService.searchByKeywordExact(var);
                if (!exactMatches.isEmpty()) {
                    return content; // 只要有一个变体匹配，就返回原始内容触发后续搜索
                }
            }
            return null; // 所有变体都没精确匹配，则忽略
        }

        if (!content.matches(".*[\\p{L}0-9].*")) {
            // 针对符号的检查，同样可以套用变体（虽然符号一般没繁简）
            List<Song> symbolMatches = dbService.searchByKeywordExact(content);
            return !symbolMatches.isEmpty() ? content : null;
        }
        return content;
    }

    private static String extractNickname(String json) {
        int senderIndex = json.indexOf("\"sender\"");
        if (senderIndex == -1) return "未知用户";
        String key = "\"nickname\":\"";
        int start = json.indexOf(key, senderIndex);
        if (start == -1) return "未知用户";
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "未知用户";
        return json.substring(start, end);
    }

    // --- 业务处理方法 ---

    private static void reply(long groupId, long userId, String messageType, String msg) {
        if ("private".equals(messageType)) {
            apiClient.sendPrivateMessage(userId, msg);
        } else {
            apiClient.sendGroupMessage(groupId, "[CQ:at,qq=" + userId + "]\n" + msg);
        }
    }

    private static void replyRaw(long groupId, long userId, String messageType, String msg) {
        if ("private".equals(messageType)) {
            apiClient.sendPrivateMessage(userId, msg);
        } else {
            apiClient.sendGroupMessage(groupId, msg);
        }
    }

    private static void handleAlbumSearch(long groupId, long userId, String messageType, String albumId) {
        List<Song> albumSongs = dbService.searchByAlbumId(albumId);

        // 1. 如果完全没找到这个专辑的数据
        if (albumSongs.isEmpty()) {
            reply(groupId, userId, messageType, "未找到专辑 #" + albumId + " 的相关曲目。");
            return;
        }

        // =========================================================
        // 【第一步：保留你原有的元数据解析逻辑】(完全不动)
        // =========================================================
        String albumName = "未知专辑";
        String releaseDate = "";
        String albumImgPath = null;

        for (Song s : albumSongs) {
            if (s.getAlbum() != null && !s.getAlbum().isEmpty()) {
                if (s.getAlbumIds() != null) {
                    String rawIds = s.getAlbumIds().replaceAll("^,+|,+$", "");
                    String[] ids = rawIds.split(",");
                    String[] names = s.getAlbum().split("\\|");
                    for(int i=0; i<ids.length; i++) {
                        if(ids[i].trim().equals(albumId) && i < names.length) {
                            albumName = names[i].trim();
                            break;
                        }
                    }
                    if("未知专辑".equals(albumName) && names.length > 0) albumName = names[0].trim();
                } else { albumName = s.getAlbum(); }
            }
            if (s.getAlbumDate() != null && !s.getAlbumDate().isEmpty()) { releaseDate = s.getAlbumDate(); }
            if (s.getAlbumImagePath() != null && !s.getAlbumImagePath().isEmpty()) { albumImgPath = s.getAlbumImagePath(); }
        }

        // =========================================================
        // 【第二步：状态预检 (判断是否全锁)】
        // =========================================================
        boolean hasAnyUnlocked = false; // 是否至少有一首能看
        String firstLockMsg = null;     // 存一个锁定提示备用

        for (Song s : albumSongs) {
            String status = checkLockStatus(s, groupId, userId, messageType);
            if (status == null) {
                hasAnyUnlocked = true;
            } else {
                if (firstLockMsg == null) firstLockMsg = status;
            }
        }

        // =========================================================
        // 【第三步：构建回复】
        // =========================================================
        StringBuilder sb = new StringBuilder();
        sb.append("专辑名称:").append(albumName).append("\n");
        if (!releaseDate.isEmpty()) { sb.append("发布时间:").append(getDisplayDate(releaseDate)).append("\n"); }

        // --- 分支判断 ---
        // 场景A: 整个专辑都没到时间 (全锁) -> 保持你原有的“预告阶段”逻辑，但更新一下文案格式
        // --- 分支 A: 整个专辑都没到时间 (全锁) ---
        if (!hasAnyUnlocked && firstLockMsg != null) {
            String formattedMsg = firstLockMsg.replace("该歌曲尚未公开，请等待至 ", "");
            if (!formattedMsg.contains("后将显示歌曲信息")) {
                formattedMsg = formattedMsg.replace("。", "后将显示歌曲信息。");
            }
            sb.append("\n").append(formattedMsg).append("\n").append("\n");
        }
        // 场景B: 只要有一首解锁了 (混合展示) -> 列出所有歌，未解锁的显示占位符
        else {
            sb.append("歌曲列表:").append("\n");
            // 对所有歌曲排序 (不再是只排 visibleSongs)
            albumSongs.sort(Comparator.comparingInt(Song::getId));

            for (Song song : albumSongs) {
                // 再次检查每首歌的状态
                String status = checkLockStatus(song, groupId, userId, messageType);

                sb.append(song.getId()).append("-");

                if (status == null) {
                    // 已解锁：显示歌名
                    sb.append(song.getSongName());
                } else {
                    // --- 修复重复显示 BUG ---
                    // 1. 先去掉前面的固定前缀
                    String dateMsg = status.replace("该歌曲尚未公开，请等待至 ", "");

                    // 2. 智能判断：如果这句话里还没包含“后将显示...”，再进行替换；如果已经有了，就不动
                    if (!dateMsg.contains("后将显示歌曲信息")) {
                        dateMsg = dateMsg.replace("。", "后将显示歌曲信息。");
                    }

                    sb.append(dateMsg);
                }
                sb.append("\n");
            }
        }

        // 保持原有的页脚 (完全不动)
        sb.append("发送\"！\"+歌曲编号可进行详细查询，更多信息请访问www.teacharm.moe");

        // 保持图片逻辑 (完全不动)
        appendImageCQCode(sb, albumImgPath);

        reply(groupId, userId, messageType, sb.toString().trim());
    }

    private static void handleDigitalSearch(long groupId, long userId, String messageType, String keyword) {
        List<Song> combinedResults = new ArrayList<>();
        Set<Integer> addedIds = new HashSet<>();

        // 1. 尝试 ID 精确匹配
        try {
            int songId = Integer.parseInt(keyword);
            Song idMatch = dbService.searchById(songId);
            if (idMatch != null && idMatch.getSongName() != null && !idMatch.getSongName().trim().isEmpty()) {
                // 检查锁定 (传入 userId 和 messageType)
                String lockMsg = checkLockStatus(idMatch, groupId, userId, messageType);
                if (lockMsg != null) {
                    // 命中了ID但未解锁 -> 直接返回等待提示
                    reply(groupId, userId, messageType, lockMsg);
                    return;
                }
                combinedResults.add(idMatch);
                addedIds.add(idMatch.getId());
            }
        } catch (NumberFormatException e) { }

        // 2. 尝试 关键词匹配 (已修复：只搜歌名，严禁越界搜索专辑名)
        List<Song> keywordMatches = dbService.searchByExactSongNameOnly(keyword);

        // 过滤锁定歌曲
        // 如果搜出来是被锁的，这里先剔除，不加入 combinedResults
        // 这样如果 ID 没搜到，关键词搜到但被锁了，combinedResults 就会是空的，
        // 但我们需要给用户提示。所以这里需要一点小技巧：

        for (Song s : keywordMatches) {
            String lockMsg = checkLockStatus(s, groupId, userId, messageType);
            if (lockMsg == null) {
                if (!addedIds.contains(s.getId())) {
                    combinedResults.add(s);
                    addedIds.add(s.getId());
                }
            }
            // 如果 lockMsg != null，我们暂时不加进去。
            // 如果最后 combinedResults 为空，但其实搜到了被锁的歌，
            // 我们在最后统一判断是否要回“请等待”。
        }

        // 3. 结果处理
        if (combinedResults.isEmpty()) {
            // 补救检查：是不是因为都被锁了才为空？
            // 我们再查一次 keywordMatches 看看有没有被锁的
            for (Song s : keywordMatches) {
                String lockMsg = checkLockStatus(s, groupId, userId, messageType);
                if (lockMsg != null) {
                    // 发现确实有歌，但是被锁了 -> 返回第一首被锁歌曲的提示
                    reply(groupId, userId, messageType, lockMsg);
                    return;
                }
            }

            // 真没找到
            reply(groupId, userId, messageType, "未找到编号或歌曲信息含『 " + keyword + " 』的歌曲。");

        } else if (combinedResults.size() == 1) {
            Song s = combinedResults.get(0);
            reply(groupId, userId, messageType, buildFullSongMessage(s));
            String audio = getAudioCQCode(s);
            if (audio != null) replyRaw(groupId, userId, messageType, audio);
        } else {
            sendSongList(groupId, userId, messageType, combinedResults, "与 " + keyword + " 匹配的歌曲");
        }
    }

    private static void handleKeywordSearch(long groupId, long userId, String messageType, String keyword, String nickname) {
        // --- V100 修改: 获取繁简/异体关键词集合 ---
        Set<String> searchKeywords = generateSearchVariants(keyword);

        // 1. 数据库模糊搜索 (对每个变体都查一遍)
        List<Song> fuzzyResults = new ArrayList<>();
        Set<Integer> addedIds = new HashSet<>();

        System.out.println("⚡ 搜索变体: " + searchKeywords); // 调试日志

        for (String kw : searchKeywords) {
            List<Song> res = dbService.searchByKeywordFuzzy(kw);
            for (Song s : res) {
                if (addedIds.add(s.getId())) { // 利用 Set 去重
                    fuzzyResults.add(s);
                }
            }
        }

        // --- V99: 组合名/特殊分隔符 智能补全 (搜索增强) ---
        // 解决如搜"镜音连"找不到"镜音铃·连"的问题
        List<Song> allSongs = dbService.getAllSongs();

        for (Song song : allSongs) {
            // 如果已经在结果里了，跳过
            if (addedIds.contains(song.getId())) continue;

            String author = song.getAuthor();
            if (author == null || author.isEmpty()) continue;

            // 应用与 handleTopAuthors 相同的拆分清洗逻辑
            String temp = author;

            // A. 特殊组合显式展开
            temp = temp.replace("镜音铃·连", "镜音铃,镜音连");
            temp = temp.replace("镜音铃.连", "镜音铃,镜音连");

            // B. 符号标准化 (将 / & 、 · 等都变成逗号)
            temp = temp.replaceAll("[/&、+()（）\\[\\]【】·]", ",");

            // C. 匹配检查 (V100 修改: 只要包含任意一个变体就算匹配)
            // 例如: 数据库存的是 "鏡音"，用户搜 "镜音" -> 变体包含 "鏡音" -> 匹配成功
            for (String kw : searchKeywords) {
                if (temp.contains(kw)) {
                    fuzzyResults.add(song);
                    addedIds.add(song.getId());
                    break; // 匹配中一个就行，不用继续查其他变体
                }
            }
        }
        // -------------------------------------------------
// =========================================================
        // 【V102 新增: 结果列表过滤与拦截】
        // =========================================================
        List<Song> visibleSongs = new ArrayList<>();
        String firstLockMsg = null; // 用于记录第一条被锁的提示语
        boolean hasLockedSongs = false;

        for (Song s : fuzzyResults) {
            // 注意：这里调用的是带 userId 参数的 checkLockStatus
            String lockMsg = checkLockStatus(s, groupId, userId, messageType);

            if (lockMsg == null) {
                // 没锁 -> 加入可见列表
                visibleSongs.add(s);
            } else {
                // 锁了 -> 标记状态，记录提示语
                hasLockedSongs = true;
                if (firstLockMsg == null) firstLockMsg = lockMsg;
            }
        }

        // 情况 1: 没有任何匹配 (无论是锁了还是没锁) -> 保持沉默 (交给下面的逻辑处理)
        if (visibleSongs.isEmpty() && !hasLockedSongs) {
            return;
        }

        // 情况 2: 搜到了结果，但全是被锁的歌 (例如用户精准搜了未发布的新歌名)
        if (visibleSongs.isEmpty() && hasLockedSongs) {
            reply(groupId, userId, messageType, firstLockMsg);
            return; // 直接返回，不继续执行冷却逻辑
        }

        // 情况 3: 有可见的歌 (可能是混合结果，或者全是可见的)
        // 将主结果集替换为“仅可见”的歌曲列表
        fuzzyResults = visibleSongs;

        // =========================================================

        if (fuzzyResults.isEmpty()) {
            return; // 没找到，保持沉默
        }
        if (fuzzyResults.isEmpty()) {
            return; // 没找到，保持沉默
        }

        // ... (后续的冷却判断、拦截逻辑、结果发送代码保持不变，直接复制原来的即可) ...
        // =========================================================
        // 【V87 逻辑保持不变: 智能冷却逻辑 (150触发, 100拦截)】
        // =========================================================

        int resultSize = fuzzyResults.size();
        long now = System.currentTimeMillis();
        long lastTime = cdLargeSearch.getOrDefault(userId, 0L);
        long diff = (now - lastTime) / 1000;
        boolean inCooldown = diff < 60;
        boolean isNotPrivate = !"private".equals(messageType);
        int limitThreshold = 60;

        // --- A. 拦截逻辑 ---
        if (inCooldown && !ADMIN_LIST.contains(userId) && resultSize > limitThreshold && isNotPrivate) {
            if (!warnLargeSearch.getOrDefault(userId, false)) {
                long remaining = 60 - diff;
                reply(groupId, userId, messageType,
                        "连续搜索结果数量过多，请等待" + remaining + "s后再次发送，或您可以尝试更精确的关键词(计时结束前将不再回复此类请求)。");
                dbService.logUserWarning(userId, nickname);
                warnLargeSearch.put(userId, true);
            }
            return;
        }

        // --- B. 触发逻辑 ---
        if (resultSize > limitThreshold && isNotPrivate && !ADMIN_LIST.contains(userId)) {
            cdLargeSearch.put(userId, now);
            if (!inCooldown) {
                warnLargeSearch.put(userId, false);
            }
        }

        // 3. 发送结果
        if (resultSize == 1) {
            Song s = fuzzyResults.get(0);
            reply(groupId, userId, messageType, buildFullSongMessage(s));
            String audio = getAudioCQCode(s);
            if (audio != null) replyRaw(groupId, userId, messageType, audio);
        } else if (resultSize > FUZZY_SEARCH_LIMIT) {
            reply(groupId, userId, messageType, "搜索到的结果过多(" + resultSize + ")，请输入更精确的关键词。");
        } else {
            // 注意：这里仍然显示用户原始输入的 keyword，体验更好
            sendSongList(groupId, userId, messageType, fuzzyResults, "包含[ " + keyword + " ]的歌曲");
        }
    }

    private static void sendSongList(long groupId, long userId, String messageType, List<Song> songs, String titlePrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(titlePrefix).append("共有 ").append(songs.size()).append(" 首(发送“！”+歌曲编号可进行详细查找)\n");
        int limit = Math.min(songs.size(), FUZZY_SEARCH_LIMIT);
        for (int i = 0; i < limit; i++) {
            Song song = songs.get(i);
            sb.append(song.getId()).append("-").append(song.getSongName()).append("\n");
        }
        if (songs.size() > limit) { sb.append("...等 (共 ").append(songs.size()).append(" 条)\n"); }
        reply(groupId, userId, messageType, sb.toString().trim());
    }

    // --- V80: 专辑目录 (支持分页/全部显示) ---
    private static void handleAlbumDirectory(long groupId, long userId, String messageType, boolean showAll) {
        Map<Integer, String> allAlbums = dbService.getAllUniqueAlbums();
        StringBuilder sb = new StringBuilder();
        sb.append("茶韵谱面团队发布的作品共有(发送“#”+专辑编号可进行详细查询):\n");

        sb.append("★常规专辑系列:\n");
        if (allAlbums.isEmpty()) {
            sb.append("(暂无专辑数据)\n");
        } else {
            // 将 Map 转换为 List 以便截取
            List<Map.Entry<Integer, String>> entryList = new ArrayList<>(allAlbums.entrySet());

            // 计算起始索引
            int startIndex = 0;
            if (!showAll && entryList.size() > 40) {
                // 如果不显示全部，且总数超过50，则从 (总数-30) 开始显示
                startIndex = entryList.size() - 40;
                sb.append("(仅显示最近40张，发送“！全部专辑”查看所有)\n");
            }

            for (int i = startIndex; i < entryList.size(); i++) {
                Map.Entry<Integer, String> entry = entryList.get(i);
                sb.append(entry.getKey()).append("-").append(entry.getValue()).append("\n");
            }
        }

        sb.append("\n" +
                "★★特别宣传系列:\n");
        sb.append("183-绽放于悬崖的彼岸花\n" +
                "189-星海狂想\n" +
                "195-凌云大战被涩\n" +
                "201-Lost & Found");
        reply(groupId, userId, messageType, sb.toString().trim());
    }

    // --- 消息构建 ---
    private static String buildFullSongMessage(Song song) {
        StringBuilder sb = new StringBuilder();
        sb.append("歌曲名称:").append(song.getSongName()).append("\n");
        sb.append("编号:").append(song.getId()).append("\n");
        appendIfPresent(sb, "作者", song.getAuthor());
        appendIfPresent(sb, "谱师", song.getCharter());
        sb.append("BPM:").append(song.getBpm()).append("\n");
        appendIfPresent(sb, "时长", song.getDuration());

        sb.append("谱面信息:\n");

        // 4K: 传入对应颜色的圆点
        String row4k = formatChartCell(song.get_4k_ez(), "🟢") +
                formatChartCell(song.get_4k_nm(), "🟡") +
                formatChartCell(song.get_4k_hd(), "🔴") +
                formatChartCell(song.get_4k_mx(), "🟣") +
                formatChartCell(song.get_4k_sp(), "🟤");
        if (!row4k.isEmpty()) sb.append(row4k).append("\n"); // V92: 去掉行尾竖线

        // 5K
        String row5k = formatChartCell(song.get_5k_ez(), "🟢") +
                formatChartCell(song.get_5k_nm(), "🟡") +
                formatChartCell(song.get_5k_hd(), "🔴") +
                formatChartCell(song.get_5k_mx(), "🟣") +
                formatChartCell(song.get_5k_sp(), "🟤");
        if (!row5k.isEmpty()) sb.append(row5k).append("\n");

        // 6K
        String row6k = formatChartCell(song.get_6k_ez(), "🟢") +
                formatChartCell(song.get_6k_nm(), "🟡") +
                formatChartCell(song.get_6k_hd(), "🔴") +
                formatChartCell(song.get_6k_mx(), "🟣") +
                formatChartCell(song.get_6k_sp(), "🟤");
        if (!row6k.isEmpty()) sb.append(row6k).append("\n");

        // 7K
        String row7k = formatChartCell(song.get_7k_ez(), "🟢") +
                formatChartCell(song.get_7k_nm(), "🟡") +
                formatChartCell(song.get_7k_hd(), "🔴") +
                formatChartCell(song.get_7k_mx(), "🟣") +
                formatChartCell(song.get_7k_sp(), "🟤");
        if (!row7k.isEmpty()) sb.append(row7k).append("\n");

        // 8K
        String row8k = formatChartCell(song.get_8k_ez(), "🟢") +
                formatChartCell(song.get_8k_nm(), "🟡") +
                formatChartCell(song.get_8k_hd(), "🔴") +
                formatChartCell(song.get_8k_mx(), "🟣") +
                formatChartCell(song.get_8k_sp(), "🟤");
        if (!row8k.isEmpty()) sb.append(row8k).append("\n");

        sb.append("收录专辑:"); // 无论有没有专辑，都先写上这就话

        if (song.getAlbum() != null && !song.getAlbum().isEmpty()) {
            // 如果有专辑名，才拼接后面的《...》
            String[] names = song.getAlbum().split("\\|");
            for (String name : names) {
                sb.append("《").append(name.trim()).append("》");
            }
        }
        // 如果没有专辑名，这里什么都不拼，直接换行，效果就是 "收录专辑:\n"
        sb.append("\n");

        // --- 修改 2: 智能页脚 (保持之前的逻辑) ---
        String albumIds = song.getAlbumIds();
        String footerText;

        boolean hasValidIds = false;
        if (albumIds != null) {
            if (!albumIds.replaceAll("[,\\s]", "").isEmpty()) {
                hasValidIds = true;
            }
        }

        if (hasValidIds) {
            // 有专辑ID -> 显示完整提示
            String cleanIds = albumIds.replaceAll("^,+|,+$", "");
            String joinedIds = cleanIds.replace(",", "”或“#");
            footerText = "(发送 “#" + joinedIds + "”即可查询歌曲清单，更多信息请访问www.teacharm.moe)";
        } else {
            // 无专辑ID -> 只显示网址
            footerText = "(更多信息请访问www.teacharm.moe)";
        }
        sb.append(footerText);

        appendImageCQCode(sb, song.getImagePath());
        return sb.toString();
    }

    private static void appendImageCQCode(StringBuilder sb, String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                String absPath = imgFile.getAbsolutePath().replace("\\", "/");

                // 【修复】CQ码转义：必须转义 & [ ] , 这四个特殊字符
                // 尤其是逗号，否则文件名带逗号会被截断
                String safePath = absPath.replace("&", "&amp;")
                        .replace("[", "&#91;")
                        .replace("]", "&#93;")
                        .replace(",", "&#44;");

                sb.append("\n[CQ:image,file=file:///").append(safePath).append("]");
            }
        }
    }
    // --- V81 新增: 统计热门曲师 Top 10 ---
    private static void handleTopAuthors(long groupId, long userId, String messageType) {
        // 1. 获取所有作者原始字符串
        List<String> rawAuthors = dbService.getAllAuthors();

        // 2. 拆分并统计
        // 使用 Map 记录: 名字 -> 次数
        Map<String, Integer> stats = new java.util.HashMap<>();

        for (String raw : rawAuthors) {
            if (raw == null) continue;

            String temp = raw;

            // ★★★ 1. 特殊组合显式展开 (针对缩写型组合) ★★★
            // 防止 "镜音铃·连" 被切分成 "镜音铃" 和 "连"(单字难以统计)
            // 直接替换成两个完整的名字，中间用逗号隔开
            temp = temp.replace("镜音铃·连", "镜音铃,镜音连");
            temp = temp.replace("镜音铃.连", "镜音铃,镜音连"); // 防御性兼容点号

// ★★★ 核心修改 A: 先保护 t+pazolite ★★★
            // 替换成一个绝对不会被切分的临时占位符
            temp = temp.replace("t+pazolite", "##TPZ_PLACEHOLDER##");

            // 2. 符号标准化 (注意: 把 + 号加回来了!)
            // 将 [/ & 、 + ( ) （ ） ·] 统一换成逗号
            temp = temp.replaceAll("[/&、+()（）\\[\\]【】·]", ",");

            // 3. 关键词标准化：将 feat. ft. vs. with cv. 等连接词换成逗号
            temp = temp.replaceAll("(?i)\\b(?:feat|ft|vs|with|cv|vo)\\b\\.?:?", ",");

            // 4. 统一按逗号切分
            String[] names = temp.split("[,，]");

            for (String name : names) {
                String cleanName = name.trim();

                // 5. 二次清洗：去除首尾残留标点
                cleanName = cleanName.replaceAll("^[.\\-:]+|[.\\-:]+$", "");

                // ★★★ 核心修改 B: 还原被保护的名字 ★★★
                if (cleanName.equals("##TPZ_PLACEHOLDER##")) {
                    cleanName = "t+pazolite";
                }

                if (!cleanName.isEmpty() && cleanName.length() > 1) {
                    stats.put(cleanName, stats.getOrDefault(cleanName, 0) + 1);
                }
            }
        }

        // 3. 排序 (按次数从高到低)
        List<Map.Entry<String, Integer>> sortedList = new ArrayList<>(stats.entrySet());
        // 降序排序
        sortedList.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // 4. 构建回复
        StringBuilder sb = new StringBuilder();
        // V74 风格的开头
        if ("group".equals(messageType)) {
            sb.append("[CQ:at,qq=").append(userId).append("]\n");
        }
        sb.append("收录曲目最多的Top 15作者：\n");
        sb.append("------------------\n");

        int limit = Math.min(sortedList.size(), 15);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = sortedList.get(i);
            // 格式: 1. HOYO-MiX (50首)
            sb.append(i + 1).append(". ")
                    .append(entry.getKey())
                    .append(" (").append(entry.getValue()).append("首)\n");
        }

        if (sortedList.isEmpty()) {
            sb.append("(暂无数据)");
        }

        sb.append("------------------\n");
        sb.append("发送“！+作者名称”可查询该作者所有作品");

        // 发送
        if ("private".equals(messageType)) {
            apiClient.sendPrivateMessage(userId, sb.toString());
        } else {
            apiClient.sendGroupMessage(groupId, sb.toString().trim());
        }
    }


    private static void appendIfPresent(StringBuilder sb, String label, String data) {
        if (data != null && !data.isEmpty()) { sb.append(label).append(":").append(data).append("\n"); }
    }
    // 【修改】增加 emoji 参数，不再使用死板的 "║"
    private static String formatChartCell(String data, String emoji) {
        if (data == null || data.isEmpty()) { return ""; }
        // 拼接格式：emoji + 数据 (例如 🟢1-59)
        return emoji + data;
    }
    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return null;
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        while (end > 0 && json.charAt(end - 1) == '\\') { end = json.indexOf("\"", end + 1); }
        if (end == -1) return null;
        return json.substring(start, end);
    }
    private static long extractJsonLong(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start == -1) return 0;
        start += searchKey.length();
        int endComma = json.indexOf(",", start);
        int endBrace = json.indexOf("}", start);
        int end = endComma;
        if (end == -1 || (endBrace != -1 && endBrace < endComma)) { end = endBrace; }
        if (end == -1) return 0;
        try { return Long.parseLong(json.substring(start, end).trim()); } catch (Exception e) { return 0; }
    }
    private static String esc(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r"); }
    private static void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        if (responseText == null) responseText = "";
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
            String trimmed = responseText.trim();
            String type = (trimmed.startsWith("{") || trimmed.startsWith("["))
                ? "application/json; charset=UTF-8"
                : "text/plain; charset=UTF-8";
            exchange.getResponseHeaders().set("Content-Type", type);
        }
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (statusCode == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(statusCode, -1);
        } else {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
    // 【修改】增加 String nickname 参数
    private static boolean checkCooldown(long targetId, Map<Long, Long> timeMap, Map<Long, Integer> warnMap, int limitSeconds, long groupId, long userId, String messageType, String nickname) {
        if (ADMIN_LIST.contains(userId) || "private".equals(messageType)) {
            return true;
        }
        long now = System.currentTimeMillis();
        long lastTime = timeMap.getOrDefault(targetId, 0L);
        long diffSeconds = (now - lastTime) / 1000;

        if (diffSeconds < limitSeconds) {
            // --- 还在冷却中 (被拦截) ---

            // ★★★【新增】只要被拦截(无论是警告还是静音)，都记录一次 ★★★
            dbService.logUserWarning(userId, nickname);

            // ... (原有的 警告/静音 逻辑保持不变) ...
            int warnCount = warnMap.getOrDefault(targetId, 0);
            if (warnCount >= 2) {
                System.out.println("⚪ [冷却拦截] 已彻底静音: " + targetId);
                return false;
            }

            // ... (计算时间、发送回复的代码保持不变) ...
            long remaining = limitSeconds - diffSeconds;
            String timeStr = (remaining < 60) ? remaining + "秒" : (remaining / 60) + "分" + (remaining % 60) + "秒";

            if (warnCount == 0) {
                // ...
                String msg = "为减少刷屏，请等待" + timeStr + "后再次发送。";
                reply(groupId, userId, messageType, msg);
                warnMap.put(targetId, 1);
            } else {
                // ...
                String msg = "为减少刷屏，请等待" + timeStr + "后再次发送。(计时结束前将不再回复此类请求)";
                reply(groupId, userId, messageType, msg);
                warnMap.put(targetId, 2);
            }
            return false; // 拦截
        } else {
            // ... (放行逻辑保持不变) ...
            timeMap.put(targetId, now);
            warnMap.put(targetId, 0);
            return true;
        }
    }
    // 【新增方法】检查大结果冷却
    // 返回 false 表示被拦截（处于冷却中），返回 true 表示未处于冷却
    private static boolean isLargeSearchCoolingDown(long userId, long groupId, String messageType) {
        long now = System.currentTimeMillis();
        long lastTime = cdLargeSearch.getOrDefault(userId, 0L);
        long diff = (now - lastTime) / 1000;

        if (diff < 60) { // 60秒冷却
            // 检查是否已经警告过
            if (warnLargeSearch.getOrDefault(userId, false)) {
                return true; // 已警告过 -> 保持沉默 (拦截)
            }

            // 第一次拦截，发送警告
            long remaining = 60 - diff;
            reply(groupId, userId, messageType, "请等待 " + remaining + " 秒后再试。");

            // 标记为已警告
            warnLargeSearch.put(userId, true);
            return true; // (拦截)
        }
        return false; // 未冷却 (放行)
    }
    // --- V89 新增: 计算单曲最高难度 ---
    private static int calculateMaxDifficulty(Song song) {
        int maxDiff = 0;

        // 1. 把所有 25 个谱面字段放入数组
        String[] charts = {
                song.get_4k_ez(), song.get_4k_nm(), song.get_4k_hd(), song.get_4k_mx(), song.get_4k_sp(),
                song.get_5k_ez(), song.get_5k_nm(), song.get_5k_hd(), song.get_5k_mx(), song.get_5k_sp(),
                song.get_6k_ez(), song.get_6k_nm(), song.get_6k_hd(), song.get_6k_mx(), song.get_6k_sp(),
                song.get_7k_ez(), song.get_7k_nm(), song.get_7k_hd(), song.get_7k_mx(), song.get_7k_sp(),
                song.get_8k_ez(), song.get_8k_nm(), song.get_8k_hd(), song.get_8k_mx(), song.get_8k_sp()
        };

        // 2. 遍历查找最大值
        for (String info : charts) {
            // 过滤空值和无效值
            if (info == null || info.trim().isEmpty() || info.equals("0-0")) {
                continue;
            }

            try {
                // 格式通常是 "难度-键数" (例如 "10-1200")
                // 我们以 "-" 分割
                String[] parts = info.split("-");
                if (parts.length > 0) {
                    // 取左边的部分转为数字
                    int currentDiff = Integer.parseInt(parts[0].trim());
                    // 如果比当前最大值大，就更新
                    if (currentDiff > maxDiff) {
                        maxDiff = currentDiff;
                    }
                }
            } catch (Exception e) {
                // 如果格式不对（比如填了纯文字），忽略该条数据，不报错
            }
        }

        return maxDiff;
    }
    // --- V90: 难度统计 (!难度) ---
    private static void handleDifficultyStats(long groupId, long userId, String messageType) {
        List<Song> allSongs = dbService.getAllSongs();
        int totalSongs = 0;

        // 统计桶：index 0-20 (假设最高难度不超过20)
        int[] counts = new int[21];
        int countLevel7AndBelow = 0; // 7级及以下单独统计

        for (Song song : allSongs) {
            int maxDiff = calculateMaxDifficulty(song);
            if (maxDiff > 0) {
                totalSongs++;
                if (maxDiff <= 7) {
                    countLevel7AndBelow++;
                } else if (maxDiff < counts.length) {
                    counts[maxDiff]++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if ("group".equals(messageType)) {
            sb.append("[CQ:at,qq=").append(userId).append("]\n");
        }
        sb.append("茶韵谱面团队所制作的谱面中，\n");

        // 从 15 级倒序遍历到 8 级
        for (int i = 15; i >= 8; i--) {
            if (counts[i] >= 0) { // 即使是0首也显示，保持格式整齐，或者改为 >0 只显示有的
                double percent = (totalSongs > 0) ? (counts[i] * 100.0 / totalSongs) : 0;
                sb.append(i).append("级的歌曲有 ").append(counts[i])
                        .append(" 首（").append(String.format("%.1f", percent)).append("%）\n");
            }
        }

        // 7级及以下
        double percent7 = (totalSongs > 0) ? (countLevel7AndBelow * 100.0 / totalSongs) : 0;
        sb.append("7级及以下的歌曲有 ").append(countLevel7AndBelow)
                .append(" 首（").append(String.format("%.1f", percent7)).append("%）");
        sb.append("\n发送”！”+对应等级可查询该难度下的所有曲目。");
        // 发送
        if ("private".equals(messageType)) apiClient.sendPrivateMessage(userId, sb.toString());
        else apiClient.sendGroupMessage(groupId, sb.toString().trim());
    }

    // --- V90: 按等级查歌 (!15级) ---
// --- V91: 按等级查歌 (接入大结果冷却) ---
    private static void handleDifficultySearch(long groupId, long userId, String messageType, int targetLevel, String nickname) {
        List<Song> allSongs = dbService.getAllSongs();
        List<Song> matchingSongs = new ArrayList<>();

        // 1. 筛选歌曲
        for (Song song : allSongs) {
            int maxDiff = calculateMaxDifficulty(song);
            // 严格匹配
            if (maxDiff == targetLevel) {
                // 【新增】过滤逻辑：只有未被锁定的歌曲，才允许加入列表
                // checkLockStatus 返回 null 代表已解锁/可见
                if (checkLockStatus(song, groupId, userId, messageType) == null) {
                    matchingSongs.add(song);
                }
            }
        }

        if (matchingSongs.isEmpty()) {
            reply(groupId, userId, messageType, "未找到难度为 " + targetLevel + " 级的歌曲。");
            return;
        }

        // =========================================================
        // 【修改 3: 接入大结果冷却逻辑 (同关键词搜索)】
        // =========================================================

        int resultSize = matchingSongs.size();
        int limitThreshold = 60; // 阈值 60

        long now = System.currentTimeMillis();
        long lastTime = cdLargeSearch.getOrDefault(userId, 0L);
        long diff = (now - lastTime) / 1000;
        boolean inCooldown = diff < 60; // 冷却 60秒
        boolean isNotPrivate = !"private".equals(messageType);
        // --- A. 拦截逻辑 ---
        if (inCooldown && !ADMIN_LIST.contains(userId) && resultSize > limitThreshold && isNotPrivate) {
            if (!warnLargeSearch.getOrDefault(userId, false)) {
                long remaining = 60 - diff;
                reply(groupId, userId, messageType,
                        "为减少刷屏，请等待" + remaining + "s后再次发送。(计时结束前将不再回复此类请求) ");

                // 记账
                dbService.logUserWarning(userId, nickname);
                // 标记
                warnLargeSearch.put(userId, true);
            }
            return; // 拦截
        }

// --- B. 触发逻辑 ---
// 核心修改：在判断条件中增加 !ADMIN_LIST.contains(userId)
        if (resultSize > limitThreshold && isNotPrivate && !ADMIN_LIST.contains(userId)) {
            cdLargeSearch.put(userId, now);
            if (!inCooldown) {
                warnLargeSearch.put(userId, false);
            }
        }
        // =========================================================

        // 2. 发送结果
        String title = "最高难度为 " + targetLevel + " 级 的歌曲";
        sendSongList(groupId, userId, messageType, matchingSongs, title);
    }
    // --- 新增: 谱师+难度组合查询 (!bassor14级) ---
    private static void handleCharterDiffSearch(long groupId, long userId, String messageType,
                                                 String charterName, int targetLevel, String nickname) {
        List<Song> allSongs = dbService.getAllSongs();
        List<Song> results = new ArrayList<>();
        for (Song song : allSongs) {
            String charter = song.getCharter();
            if (charter == null || charter.isEmpty()) continue;
            if (!charter.toLowerCase().contains(charterName.toLowerCase())) continue;
            if (calculateMaxDifficulty(song) != targetLevel) continue;
            String lockMsg = checkLockStatus(song, groupId, userId, messageType);
            if (lockMsg == null) results.add(song);
        }
        if (results.isEmpty()) {
            reply(groupId, userId, messageType, "未找到谱师含『" + charterName + "』且最高难度为 " + targetLevel + " 级的歌曲。");
            return;
        }
        // 接入大结果冷却
        int size = results.size();
        long now = System.currentTimeMillis();
        long last = cdLargeSearch.getOrDefault(userId, 0L);
        long diff = (now - last) / 1000;
        boolean inCd = diff < 60;
        if (inCd && !ADMIN_LIST.contains(userId) && size > 60 && !"private".equals(messageType)) {
            if (!warnLargeSearch.getOrDefault(userId, false)) {
                reply(groupId, userId, messageType, "连续搜索结果过多，请等待" + (60-diff) + "s后再次发送。(计时结束前将不再回复此类请求)");
                dbService.logUserWarning(userId, nickname);
                warnLargeSearch.put(userId, true);
            }
            return;
        }
        if (size > 60 && !"private".equals(messageType) && !ADMIN_LIST.contains(userId)) {
            cdLargeSearch.put(userId, now);
            if (!inCd) warnLargeSearch.put(userId, false);
        }
        if (size == 1) {
            Song s = results.get(0);
            reply(groupId, userId, messageType, buildFullSongMessage(s));
            String audio = getAudioCQCode(s);
            if (audio != null) replyRaw(groupId, userId, messageType, audio);
        } else if (size > FUZZY_SEARCH_LIMIT) {
            reply(groupId, userId, messageType, "搜索到的结果过多(" + size + ")，请输入更精确的关键词。");
        } else {
            sendSongList(groupId, userId, messageType, results,
                    "谱师含『" + charterName + "』且最高" + targetLevel + "级的歌曲");
        }
    }
    // ------------------------------------------------------------------------
    // V201: 严格定时任务 (仅 20:52:00 ~ 20:52:59 触发，过时不补)
    // ------------------------------------------------------------------------
    private static void startDailyScheduler() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // 【设置】推送时间 (请根据需要修改这里)
        int targetHour = 20;   // 目标小时
        int targetMinute = 00; // 目标分钟

        // 【调试开关】true = 强制推送 (无视今日是否已推过) | false = 正常模式
        // ★★★ 测试完记得改回 false ★★★
        boolean FORCE_PUSH_MODE = true;

        System.out.println("⏰ 定时任务已启动: 将在 " + targetHour + ":" + String.format("%02d", targetMinute) + " 触发");
        if (FORCE_PUSH_MODE) {
            System.out.println("⚠️ 注意：当前为【强制推送模式】，将无视数据库重复记录！");
        }

        // 每分钟轮询一次
        scheduler.scheduleAtFixedRate(() -> {
            try {
                LocalDateTime now = LocalDateTime.now();
                int h = now.getHour();
                int m = now.getMinute();

                // 1. 检查时间是否匹配
                if (h == targetHour && m == targetMinute) {

                    // 2. 检查数据库记录 (如果是强制模式，则跳过此检查)
                    String todayDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    if (!FORCE_PUSH_MODE && dbService.isPushedToday(todayDate)) {
                        System.out.println("✅ " + now + " 检测到今日已推送，跳过。");
                        return;
                    }

                    System.out.println("⏰ 时间精确匹配 (" + now + ")，准备推送...");

                    // 3. 准备数据 (这里只声明一次！)
                    List<Integer> availableIds = dbService.getAllDailySongIds();

                    if (availableIds.isEmpty()) {
                        System.err.println("❌ 数据库 daily_songs 为空，无法推送！");
                        return;
                    }

                    // 4. 选歌逻辑 (修改版：支持链式预定)
                    int targetId = -1;

                    // A. 保留原有的日期特判 (如果有的话)
                    if (now.getYear() == 2026 && now.getMonthValue() == 2 && now.getDayOfMonth() == 18) {
                        if (availableIds.contains(1)) targetId = 31;
                    }

                    // B. 尝试读取昨日锁定的“明日歌曲”
                    if (targetId == -1) {
                        int reservedId = dbService.getNextSongId();
                        if (reservedId != -1 && availableIds.contains(reservedId)) {
                            targetId = reservedId;
                            System.out.println("🔗 链式启动：执行昨日预定歌曲 ID " + targetId);
                        }
                    }

                    // C. 兜底：如果是第一次运行或预定失效，则随机
                    if (targetId == -1) {
                        targetId = availableIds.get((int)(Math.random() * availableIds.size()));
                        System.out.println("🎲 随机启动：无预定记录，随机抽取 ID " + targetId);
                    }

                    // 5. 执行推送
                    sendDailyPush(targetId, availableIds, todayDate);

                    // 6. 防止一分钟内重复触发 (休眠60秒)
                    try { TimeUnit.SECONDS.sleep(60); } catch (InterruptedException e) {}
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    // --- 公告定时调度：local 扫描本地 JSON；cloud 主动拉取云端任务。两者永不同时写入。 ---
    private static void startAnnounceScheduler() {
        File songBotDir = SONG_BOT_HOME.toFile();
        final boolean cloud = CloudAnnouncementClient.cloudEnabled(songBotDir);
        final CloudAnnouncementClient cloudClient;
        if (cloud) {
            CloudAnnouncementClient configured = null;
            try { configured = CloudAnnouncementClient.fromEnvironment(songBotDir); }
            catch (Exception ex) { System.err.println("[云公告] 配置无效，调度不会回退本地以避免双数据源: " + ex.getMessage()); }
            cloudClient = configured;
        } else {
            cloudClient = null;
        }
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleWithFixedDelay(() -> {
            try {
                if (cloud && cloudClient == null) return;
                int sent = cloud
                    ? cloudClient.processDue(LocalDateTime.now(), SongBot::sendAnnouncement)
                    : getAnnouncementStore().sendDue(LocalDateTime.now(), SongBot::sendAnnouncement);
                if (sent > 0) System.out.println("[定时公告] 本轮成功发送: " + sent + " 条");
            } catch (Exception ex) {
                System.err.println(cloud ? "[云公告] 拉取或调度失败，将在下轮重试: " + ex.getMessage() : "[定时公告] 调度失败: " + ex.getMessage());
                if (!cloud) try {
                    getAnnouncementStore().auditEvent("SCHEDULER_ERROR",
                        new AnnouncementStore.Actor("system", "scheduler", "local"),
                        new org.json.JSONObject().put("error", String.valueOf(ex.getMessage())));
                } catch (Exception ignored) {}
            }
        }, 2, 30, TimeUnit.SECONDS);
        System.out.println(cloud ? "📢 云公告调度已启动 (每30秒主动拉取)" : "📢 本地公告调度已启动 (每30秒扫描)");
    }

    // 曲库：本地源同步 -> 安全压缩 -> 云端原子发布 -> GitHub 备份
    private static void syncSongLibrary() {
        try {
            File libDir = songBotFile("song-library");
            if (!libDir.exists()) { System.out.println("[曲库] 目录不存在，跳过同步"); return; }
            File workflow = new File(libDir, "tools/sync_build_publish.py");
            if (!workflow.isFile()) { System.err.println("[曲库] 发布流程不存在，跳过同步: " + workflow); return; }
            System.out.println("[曲库] 开始增量构建与发布...");
            ProcessBuilder pb = new ProcessBuilder("python", workflow.getAbsolutePath());
            pb.directory(libDir);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            Process p = pb.start();
            boolean completed = p.waitFor(3600, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                System.err.println("[曲库] 发布超过 60 分钟，已终止；线上旧版本不受影响");
                return;
            }
            if (p.exitValue() == 0) System.out.println("[曲库] 云端发布与 GitHub 备份完成");
            else System.err.println("[曲库] 发布流程失败 (exit=" + p.exitValue() + ")；线上旧版本仍保留");
        } catch (Exception e) { System.err.println("[曲库] 同步失败: " + e.getMessage()); }
    }

    private static int exec(File dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        byte[] bytes = p.getInputStream().readAllBytes();
        int rc = p.waitFor();
        if (bytes.length > 0) {
            String s = new String(bytes).trim();
            if (s.length() > 0) System.out.println("[曲库] " + s.replace("\n", " | "));
        }
        return rc;
    }

    // push；若因远端领先被拒(non-fast-forward)，自动 pull --rebase 后重推一次；rebase 冲突则中止保持干净
    private static int pushWithRebase(File libDir, String tag) throws Exception {
        int rc = exec(libDir, "git", "push", "origin", "master");
        if (rc == 0) return 0;
        System.out.println("[" + tag + "] 推送被拒(远端领先)，尝试 pull --rebase 后重推...");
        int pr = exec(libDir, "git", "pull", "--rebase", "--autostash", "origin", "master");
        if (pr != 0) {
            exec(libDir, "git", "rebase", "--abort");
            System.err.println("[" + tag + "] rebase 冲突，已中止；请手动到 song-library 处理后再同步");
            return pr;
        }
        return exec(libDir, "git", "push", "origin", "master");
    }

    // ========== 点赞/谱面：客户端IP、GitHub 归档 ==========
    private static String getClientIp(HttpExchange exchange) {
        String ip = null;
        // 只有本机反向代理传来的转发头可信；直连 8080 的请求不能伪造来源 IP。
        if (isLoopbackRequest(exchange)) {
            ip = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (ip == null || ip.isEmpty()) ip = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (ip == null || ip.isEmpty()) ip = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
        }
        if (ip == null || ip.isEmpty()) ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        if (ip == null || ip.isEmpty()) ip = "unknown";
        return ip;
    }

    // ========== 管理员名单（存本地 JSON，手动删条目即可撤销）==========
    private static File adminPassphraseFile() {
        return new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "admin_password.txt");
    }
    private static String getAdminPassphrase() {
        String configured = System.getenv("SONGBOT_ADMIN_PASSPHRASE");
        if (configured == null || configured.isEmpty()) configured = System.getProperty("songbot.admin.passphrase");
        if (configured != null && !configured.isEmpty()) return configured;
        try {
            File f = adminPassphraseFile();
            if (f.isFile()) return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "";
    }
    private static boolean adminPassphraseMatches(String candidate) {
        byte[] expected = getAdminPassphrase().getBytes(StandardCharsets.UTF_8);
        byte[] actual = (candidate == null ? "" : candidate).getBytes(StandardCharsets.UTF_8);
        return expected.length > 0 && java.security.MessageDigest.isEqual(expected, actual);
    }
    private static File adminFile() {
        return new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "admin_devices.json");
    }
    // IP 黑名单：把 IP 写进 admin_blocked_ips.json（JSON 字符串数组），该 IP 立即无法校验/授权管理员
    private static File adminBlockFile() {
        return new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "admin_blocked_ips.json");
    }
    private static synchronized boolean isIpBlocked(String ip) {
        try {
            File f = adminBlockFile();
            if (!f.exists()) return false;
            org.json.JSONArray arr = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) if (ip.equals(arr.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }
    private static synchronized boolean isAdminDevice(String id) {
        try {
            File f = adminFile();
            if (!f.exists()) return false;
            org.json.JSONArray arr = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
    private static synchronized void grantAdmin(String id, String ip) {
        try {
            File f = adminFile();
            org.json.JSONArray arr = f.exists()
                ? new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                : new org.json.JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) return; // 已在名单
            }
            org.json.JSONObject e = new org.json.JSONObject();
            e.put("id", id); e.put("ip", ip);
            e.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            arr.put(e);
            writeUtf8Atomically(f, arr.toString(2));
            System.out.println("👑 管理员授权: device=" + id + " ip=" + ip);
        } catch (Exception ex) { System.err.println("授权失败: " + ex.getMessage()); }
    }

    private static final Object LIB_GIT_LOCK = new Object();
    private static volatile boolean libPushScheduled = false;

    // 防抖：60秒内多次点赞只推送一次（推送时读取最新聚合，天然合并）
    private static void scheduleLibPush() {
        synchronized (LIB_GIT_LOCK) {
            if (libPushScheduled) return;
            libPushScheduled = true;
        }
        scheduler.schedule(() -> {
            synchronized (LIB_GIT_LOCK) { libPushScheduled = false; }
            pushLibFiles();
        }, 60, TimeUnit.SECONDS);
    }

    private static void pushLibFiles() {
        synchronized (LIB_GIT_LOCK) {
            try {
                File libDir = songBotFile("song-library");
                if (!libDir.exists()) return;
                // 写入聚合点赞
                Map<Integer,Integer> counts = dbService.getAllLikeCounts();
                org.json.JSONObject o = new org.json.JSONObject();
                for (Map.Entry<Integer,Integer> e : counts.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
                File dataDir = new File(libDir, "data"); dataDir.mkdirs();
                java.nio.file.Files.write(new File(dataDir, "likes.json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
                // 提交 likes.json / charts.json / charts/
                exec(libDir, "git", "add", "data/likes.json");
                int commitRc = exec(libDir, "git", "commit", "-m", "Likes/charts update");
                if (commitRc == 0 || commitRc == 1) {
                    int pushRc = pushWithRebase(libDir, "点赞归档");
                    if (pushRc == 0) System.out.println("[点赞归档] 已推送到 GitHub");
                    else System.err.println("[点赞归档] 推送失败 (exit=" + pushRc + ")");
                }
            } catch (Exception e) { System.err.println("[点赞归档] 失败: " + e.getMessage()); }
        }
    }

    private static boolean isLoopbackRequest(HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
            && exchange.getRemoteAddress().getAddress() != null
            && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    /** Enforces the existing password-unlocked device list on state-changing routes. */
    private static boolean requireAdmin(HttpExchange exchange) throws IOException {
        String deviceId = exchange.getRequestHeaders().getFirst("X-Admin-Device");
        if (!isSafePathSegment(deviceId) || isIpBlocked(getClientIp(exchange)) || !isAdminDevice(deviceId)) {
            sendResponse(exchange, 403, "{\"error\":\"admin authorization required\"}");
            return false;
        }
        return true;
    }

    /**
     * Once cloud announcement mode is enabled, the old announcement storage and
     * immediate-send endpoints remain available only to programs on this PC.
     * A Funnel/reverse-proxy request reaches the loopback listener too, so the
     * presence of forwarding headers must also disqualify it.
     */
    private static boolean allowLegacyAnnouncementRequest(HttpExchange exchange) throws IOException {
        File songBotDir = new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot");
        if (!CloudAnnouncementClient.cloudEnabled(songBotDir)) return true;
        boolean forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For") != null
            || exchange.getRequestHeaders().getFirst("X-Real-IP") != null
            || exchange.getRequestHeaders().getFirst("CF-Connecting-IP") != null;
        if (!isLoopbackRequest(exchange) || forwarded) {
            sendResponse(exchange, 410, "{\"error\":\"legacy announcement endpoint disabled\"}");
            return false;
        }
        return true;
    }

    private static byte[] readRequestBody(HttpExchange exchange, int maxBytes) throws IOException {
        long declaredLength = 0L;
        try { declaredLength = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length")); }
        catch (Exception ignored) {}
        if (declaredLength > maxBytes) throw new IOException("Request body is too large");
        try (InputStream in = exchange.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read, total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Request body is too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean isSafePathSegment(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}")
            && !".".equals(value) && !"..".equals(value);
    }

    private static String safeFileName(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        String name = new File(value).getName();
        if (!name.equals(value) || name.contains("..") || name.length() > 180 || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) return fallback;
        return name;
    }

    private static String decodeRawQuery(HttpExchange exchange) {
        try {
            String query = exchange.getRequestURI().getRawQuery();
            return query == null ? null : java.net.URLDecoder.decode(query, StandardCharsets.UTF_8.name());
        } catch (Exception e) { return null; }
    }

    private static File resolveMarkdownFile(File blogDir, String name) {
        if (name == null || name.length() > 180 || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) return null;
        String safe = safeFileName(name, null);
        return safe == null ? null : resolveChildFile(blogDir, safe);
    }

    private static void writeUtf8Atomically(File file, String content) throws IOException {
        File parent = file.getCanonicalFile().getParentFile();
        if (parent == null) throw new IOException("Missing parent directory");
        parent.mkdirs();
        java.nio.file.Path temp = java.nio.file.Files.createTempFile(parent.toPath(), "." + file.getName(), ".tmp");
        try {
            java.nio.file.Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                java.nio.file.Files.move(temp, file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    private static File resolveChildFile(File parent, String child) {
        try {
            if (parent == null || child == null || child.isEmpty()) return null;
            File root = parent.getCanonicalFile();
            File candidate = new File(root, child).getCanonicalFile();
            return candidate.getPath().startsWith(root.getPath() + File.separator) ? candidate : null;
        } catch (IOException e) { return null; }
    }

    private static File resolveAnnouncementFile(String token) {
        File base = new File(new File(new File(System.getProperty("user.home"), "Desktop"), "SongBot"), "announce_files");
        return resolveChildFile(base, token);
    }

    private static java.io.File[] configuredFontDirectories() {
        String configured = System.getenv("MCZ_ASSET_DIR");
        java.io.File root = configured == null || configured.isBlank()
                ? new java.io.File(System.getProperty("user.home"), "BotWorkstation/assets")
                : new java.io.File(configured);
        return new java.io.File[] {
                new java.io.File(root, "5首歌模板"),
                new java.io.File(root, "日历")
        };
    }

    private static void addCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String configured = System.getenv("BOT_EDITOR_ALLOWED_ORIGINS");
        boolean configuredOrigin = origin != null && configured != null
                && java.util.Arrays.stream(configured.split(","))
                    .map(String::trim).anyMatch(origin::equals);
        if (origin != null && (origin.equals("https://bot-editor.vercel.app")
                || origin.equals("https://editor.teacharm.moe")
                || configuredOrigin
                || origin.matches("https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?"))) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE,OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-Filename, X-Session, X-Type, X-Song-Id, X-Admin-Device, If-Match");
        exchange.getResponseHeaders().add("Access-Control-Expose-Headers", "ETag");
    }

    private static File getAnnounceFile() {
        // 公告数据归 SongBot 管理；MczMaker 只作为编辑客户端，避免迁移时数据源分裂。
        return songBotFile("data/announcements.json");
    }

    private static AnnouncementStore getAnnouncementStore() {
        AnnouncementStore current = announcementStore;
        if (current != null) return current;
        synchronized (SongBot.class) {
            if (announcementStore == null) {
                File logFile = songBotFile("logs/announcement-audit.jsonl");
                announcementStore = new AnnouncementStore(getAnnounceFile(), logFile);
            }
            return announcementStore;
        }
    }

    private static AnnouncementStore.Actor announcementActor(HttpExchange exchange) {
        String device = exchange == null ? "unknown" : exchange.getRequestHeaders().getFirst("X-Admin-Device");
        String ip = exchange == null ? "unknown" : getClientIp(exchange);
        return new AnnouncementStore.Actor("admin", device, ip);
    }

    private static AnnouncementStore.SendResult sendAnnouncement(org.json.JSONObject announcement) {
        try {
            String gid = announcement.optString("groupId", "");
            String title = announcement.optString("title", "");
            if (gid.isEmpty() || title.isEmpty()) return AnnouncementStore.SendResult.failure("missing groupId or title");
            long groupId = Long.parseLong(gid);
            String content = announcement.optString("content", "");
            String image = announcement.optString("image", "");
            boolean pin = "true".equals(announcement.optString("pin", "false")) || announcement.optBoolean("pin", false);
            boolean confirm = "true".equals(announcement.optString("confirm", "false")) || announcement.optBoolean("confirm", false);
            File imageFile = image.isEmpty() ? null : resolveAnnouncementFile(image);
            if (!image.isEmpty() && (imageFile == null || !imageFile.exists())) {
                return AnnouncementStore.SendResult.failure("image file does not exist: " + image);
            }
            String noticeResponse = apiClient.sendGroupNotice(groupId, title, content,
                imageFile == null ? null : imageFile.getAbsolutePath(), pin, confirm);
            if (!isNapCatSuccess(noticeResponse)) {
                return AnnouncementStore.SendResult.failure("NapCat notice response: " + compactResponse(noticeResponse));
            }

            java.util.List<String> attachmentWarnings = new java.util.ArrayList<>();
            String attach = announcement.optString("attach", announcement.optString("files", ""));
            if (!attach.isEmpty()) {
                int attachmentIndex = 0;
                for (String token : attach.split("\\|")) {
                    if (token.isEmpty()) continue;
                    File attachment = resolveAnnouncementFile(token);
                    if (attachment == null || !attachment.exists()) {
                        attachmentWarnings.add("missing " + token);
                        attachmentIndex++;
                        continue;
                    }
                    String uploadName = announcementAttachmentName(announcement, attachmentIndex, token, attachment);
                    String response = apiClient.uploadGroupFile(groupId, attachment.getAbsolutePath(), uploadName);
                    if (!isNapCatSuccess(response)) attachmentWarnings.add("upload failed " + token + ": " + compactResponse(response));
                    attachmentIndex++;
                }
            }
            System.out.println("[定时公告] 已发送: " + title);
            String detail = attachmentWarnings.isEmpty() ? "notice and attachments succeeded"
                : "notice succeeded; " + String.join("; ", attachmentWarnings);
            return AnnouncementStore.SendResult.success(detail);
        } catch (Exception ex) {
            return AnnouncementStore.SendResult.failure(String.valueOf(ex.getMessage()));
        }
    }

    static String announcementAttachmentName(org.json.JSONObject announcement, int index, String token, File attachment) {
        org.json.JSONArray names = CloudAnnouncementClient.attachmentNames(announcement == null ? null : announcement.opt("attachmentNames"));
        String supplied = names.optString(index, "");
        String fallback = CloudAnnouncementClient.nameFromToken(token);
        if ((fallback.isEmpty() || "file.bin".equals(fallback)) && attachment != null) fallback = attachment.getName();
        return CloudAnnouncementClient.sanitizeAttachmentName(supplied, fallback);
    }

    private static boolean isNapCatSuccess(String response) {
        if (response == null || response.trim().isEmpty()) return false;
        try {
            org.json.JSONObject json = new org.json.JSONObject(response);
            if (json.has("retcode")) return json.optInt("retcode", -1) == 0;
            String status = json.optString("status", "");
            return "ok".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String compactResponse(String response) {
        if (response == null) return "null";
        String compact = response.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    private static String loadEditorHtml() {
        // 从 deploy/index.html 读取（与 Vercel 部署共用同一文件）
        try {
            File f = new File(getEditorDeployDirectory(), "index.html");
            if (f.exists()) {
                String html = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                // 本地编辑器始终加载当前磁盘上的拆分资源。
                html = html.replaceAll("([?&]v=)\\d+", "$1" + System.currentTimeMillis());
                return html;
            }
        } catch (Exception ignored) {}
        // 回退到 classpath 资源
        return loadResource("editor.html");
    }

    private static File getEditorDeployDirectory() {
        return songBotFile("deploy");
    }

    private static void serveEditorStatic(HttpExchange exchange, String rootFolder, String contextPrefix) {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                sendResponse(exchange, 405, "GET only"); return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || !path.startsWith(contextPrefix)) {
                sendResponse(exchange, 404, ""); return;
            }
            String relative = path.substring(contextPrefix.length());
            if (relative.isEmpty() || relative.indexOf('\0') >= 0) {
                sendResponse(exchange, 404, ""); return;
            }

            java.nio.file.Path base = new File(getEditorDeployDirectory(), rootFolder)
                .toPath().toAbsolutePath().normalize();
            java.nio.file.Path requested = base.resolve(relative).normalize();
            if (!requested.startsWith(base) || !java.nio.file.Files.isRegularFile(requested)) {
                sendResponse(exchange, 404, ""); return;
            }

            byte[] bytes = java.nio.file.Files.readAllBytes(requested);
            String name = requested.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String contentType;
            if (name.endsWith(".js")) contentType = "text/javascript; charset=UTF-8";
            else if (name.endsWith(".css")) contentType = "text/css; charset=UTF-8";
            else if (name.endsWith(".ttf")) contentType = "font/ttf";
            else if (name.endsWith(".otf")) contentType = "font/otf";
            else if (name.endsWith(".png")) contentType = "image/png";
            else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) contentType = "image/jpeg";
            else if (name.endsWith(".svg")) contentType = "image/svg+xml";
            else contentType = "application/octet-stream";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            try { sendResponse(exchange, 500, ""); } catch (Exception ignored) {}
        }
    }

    private static String safeMediaExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String extension = fileName.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }

    private static String contentTypeForMedia(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".ogg") || name.endsWith(".oga")) return "audio/ogg";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".m4a") || name.endsWith(".mp4")) return "audio/mp4";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static void scheduleMczTempDeletion(File file) {
        scheduler.schedule(() -> {
            try { java.nio.file.Files.deleteIfExists(file.toPath()); }
            catch (Exception ignored) {}
        }, 2, TimeUnit.HOURS);
    }

    private static void serveMczTempFile(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                sendResponse(exchange, 405, "GET only"); return;
            }
            String token = exchange.getRequestURI().getRawQuery();
            if (token == null || !token.matches("(?:img|aud)_[0-9a-fA-F-]{36}\\.[A-Za-z0-9]{1,8}")) {
                sendResponse(exchange, 400, "invalid token"); return;
            }
            File base = new File(System.getProperty("java.io.tmpdir"), "mcz_serve");
            File file = resolveChildFile(base, token);
            if (file == null || !file.isFile()) {
                sendResponse(exchange, 404, ""); return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentTypeForMedia(token));
            exchange.getResponseHeaders().set("Cache-Control", "private, max-age=7200");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(file.length()));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, file.length());
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = fis.read(buf)) != -1) os.write(buf, 0, read);
            }
        } catch (Exception ex) {
            try { sendResponse(exchange, 500, ""); } catch (Exception ignored) {}
        }
    }

    private static String loadResource(String name) {
        try {
            java.io.InputStream is = SongBot.class.getResourceAsStream("/" + name);
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                is.close();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return "<html><body><h1>" + name + " not found</h1></body></html>";
    }

    private static byte[] loadResourceBytes(String name) throws IOException {
        java.io.InputStream is = SongBot.class.getResourceAsStream("/" + name);
        if (is == null) throw new IOException(name + " not found");
        byte[] bytes = is.readAllBytes();
        is.close();
        return bytes;
    }

    private static void sendDailyPush(int targetId, List<Integer> pool, String todayDate) {
        try {
            // 1. 获取今日要发送的歌曲信息 (今天的主角)
            DailySong song = dbService.getDailySongInfo(targetId);
            if (song == null) {
                System.err.println("❌ 数据库 daily_songs 表中找不到 ID=" + targetId + " 的记录");
                return;
            }

            // =======================================================
            // 👇 第一步：【先标记】立即对今日发送的文件进行改名
            // =======================================================
            File audioFile = new File(song.audioPath);
            File renamedFile = new File(audioFile.getParent(), "A" + audioFile.getName());

            if (audioFile.exists()) {
                if (audioFile.renameTo(renamedFile)) {
                    System.out.println("✅ [顺序确认] 已预先将今日歌曲标记为 A (已发布): " + renamedFile.getName());
                    audioFile = renamedFile; // 后续发送逻辑改用改名后的文件对象
                }
            } else if (renamedFile.exists()) {
                audioFile = renamedFile;
            } else {
                System.err.println("❌ 严重错误: 文件丢失！数据库路径: " + song.audioPath);
                return;
            }

            // 每日清理 & 结算
            POKE_HISTORY_CACHE.clear();
            System.out.println("🎵 开始推送今日歌曲: " + song.songName + " (ID: " + targetId + ")");
            manageSeasonCycles();

            StringBuilder winnerMsg = new StringBuilder();
            if (currentGame != null && !currentGame.correctNicknames.isEmpty()) {
                winnerMsg.append("\n\n🏆 昨日预言家榜单：\n");
                List<String> winners = currentGame.correctNicknames;
                int limit = Math.min(winners.size(), 10);
                for (int i = 0; i < limit; i++) {
                    if (i > 0) winnerMsg.append("、");
                    winnerMsg.append(winners.get(i));
                }
                if (winners.size() > 10) winnerMsg.append(" 等").append(winners.size()).append("人");
            }

            // =======================================================
            // 👇 第二步：【选出明日真歌】从剩下的“不带A”的池子里抽一首新歌作为答案
            // =======================================================
            List<Integer> cleanPool = new ArrayList<>();
            for (Integer id : pool) {
                if (id == targetId) continue; // 绝对排除今天这首
                DailySong info = dbService.getDailySongInfo(id);
                if (info == null) continue;
                File f = new File(info.audioPath);
                // 排除：文件名已带 A 的老歌，以及今天刚被打上 A 的那首
                if (!f.getName().startsWith("A") && !new File(f.getParent(), "A" + f.getName()).exists()) {
                    cleanPool.add(id);
                }
            }

            if (cleanPool.isEmpty()) {
                System.err.println("❌ 曲池已空，无法生成竞猜");
                return;
            }

            // 🎲 随机选一首作为【明天的正确答案】
            int tomorrowId = cleanPool.get((int)(Math.random() * cleanPool.size()));
            dbService.setNextSongId(tomorrowId);
            DailySong tomorrowSong = dbService.getDailySongInfo(tomorrowId);
            cleanPool.remove((Integer)tomorrowId); // 答案选出后，从干扰项池中移除

// 👇 第三步：【选干扰项】从剩余池子抽 3 个不重复的歌名 (已修复同名Bug)
            List<String> distractorNames = new ArrayList<>();
            java.util.Collections.shuffle(cleanPool);
            for (int i = 0; i < cleanPool.size(); i++) {
                DailySong dInfo = dbService.getDailySongInfo(cleanPool.get(i));
                if (dInfo != null) {
                    String candidateName = dInfo.songName;
                    // 【关键修复】干扰项不能和正确答案同名，且干扰项之间也不能同名
                    if (!candidateName.equals(tomorrowSong.songName) && !distractorNames.contains(candidateName)) {
                        distractorNames.add(candidateName);
                    }
                }
                if (distractorNames.size() >= 3) break; // 凑齐3个就退出
            }
            while (distractorNames.size() < 3) distractorNames.add("未公开曲目");

            // =======================================================
            // 👇 第四步：选项组装逻辑 (A/B/C/D)
            // =======================================================
            int correctIndex = (int)(Math.random() * 4);
            String correctOptionChar = "";
            List<String> displayNames = new ArrayList<>();

            if (correctIndex == 3) {
                correctOptionChar = "D";
                displayNames.add(distractorNames.get(0));
                displayNames.add(distractorNames.get(1));
                displayNames.add(distractorNames.get(2));
                System.out.println("🎲 本次竞猜答案: D (其他歌曲) | 🤫 明日真歌是: " + tomorrowSong.songName);
            } else {
                String[] slots = new String[3];
                slots[0] = distractorNames.get(0);
                slots[1] = distractorNames.get(1);
                slots[2] = distractorNames.get(2);

                // ✅ 这里填入的是【明天】的歌名，不再是今天的 song.songName
                slots[correctIndex] = tomorrowSong.songName;

                correctOptionChar = (correctIndex == 0) ? "A" : (correctIndex == 1 ? "B" : "C");
                displayNames.add(slots[0]);
                displayNames.add(slots[1]);
                displayNames.add(slots[2]);
                System.out.println("🎲 本次竞猜答案: " + correctOptionChar + " (" + tomorrowSong.songName + ")");
            }

            // 构建消息 (格式保持不变)
            StringBuilder gameMsg = new StringBuilder();
            gameMsg.append("\n\n🎲 每日竞猜 (30分钟限时)\n");
            gameMsg.append("猜猜明天会推送哪首歌？\n");
            gameMsg.append("A. ").append(displayNames.get(0)).append("\n");
            gameMsg.append("B. ").append(displayNames.get(1)).append("\n");
            gameMsg.append("C. ").append(displayNames.get(2)).append("\n");
            gameMsg.append("D. 其他歌曲\n");
            gameMsg.append("回复【对应字母】参与，猜对计入赛季积分榜，月末可能收获神秘小奖励！每名用户每天只记录第一次回答。");

            long expireTime = System.currentTimeMillis() + (30 * 60 * 1000);
            currentGame = new GameSession(correctOptionChar, expireTime);
            currentGame.isActive = true;
            currentGame.questionMsgIds = new java.util.HashMap<>();

            for (Long groupId : DAILY_PUSH_GROUPS) {
                try {
                    String cleanPath = ensureAudioCompatible(audioFile);
                    String safePath = cleanPath.replace("\\", "/");
                    if (!safePath.startsWith("file:///")) safePath = "file:///" + safePath;

                    String audioResp = apiClient.sendGroupMessage(groupId, "[CQ:record,file=" + safePath + "]");
                    int audioMsgId = extractJsonInt(audioResp, "message_id");

                    StringBuilder textMsg = new StringBuilder();
                    textMsg.append("今日推送歌曲：").append(song.songName).append("\n");
                    textMsg.append("作者：").append(song.author).append("\n");
                    textMsg.append("若您喜欢这首歌，可以【长按上方语音条】贴任意一个表情，bot会自动统计歌曲受欢迎程度。");
                    if (winnerMsg.length() > 0) textMsg.append(winnerMsg);
                    textMsg.append(gameMsg.toString());

                    String textResp = apiClient.sendGroupMessage(groupId, textMsg.toString());
                    int textMsgId = extractJsonInt(textResp, "message_id");
                    if (audioMsgId != 0) {
                        dbService.logDailyPush(todayDate, groupId, audioMsgId, song.songName);
                    } else {
                        // 容错：如果音频ID获取失败，才存文字ID (虽然此时贴表情统计会失效，但至少留个记录)
                        dbService.logDailyPush(todayDate, groupId, textMsgId, song.songName);
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    System.err.println("❌ 推送失败 Group: " + groupId);
                }
            }

            // 最终记录状态
            dbService.markPushedToday(todayDate, song.songName);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // --- 补回这个方法，用于日常搜歌 ---
    private static String getAudioCQCode(Song song) {
        if (song.getAudioPath() != null && !song.getAudioPath().isEmpty()) {
            File originalFile = new File(song.getAudioPath());
            if (originalFile.exists()) {
                // 1. 调用新的通用清洗方法 (转成 WAV)
                String finalPath = ensureAudioCompatible(originalFile);

                // 2. 路径转义
                String safePath = finalPath.replace("\\", "/");
                if (!safePath.startsWith("http") && !safePath.startsWith("file:///")) {
                    safePath = "file:///" + safePath;
                }

                return "[CQ:record,file=" + safePath + "]";
            }
        }
        return null;
    }
    // --- V208: 响度增强版 (I=-10 LUFS, 接近最大响度) ---
    // 替换位置：SongBot.java 约 1320 行
    private static String ensureAudioCompatible(File originalFile) {
        try {
            // 1. 准备路径
            String originalPath = originalFile.getAbsolutePath();
            String fileName = originalFile.getName();
            File tempDir = songBotFile("data/temp");
            if (!tempDir.exists()) tempDir.mkdirs();

            // 输出文件名（加个 safe 前缀防止重名）
            String cleanName = "safe_" + fileName.replaceAll("[^a-zA-Z0-9.-]", "_") + ".wav";
            File cleanFile = new File(tempDir, cleanName);

            // 如果已经转过，直接用缓存
            if (cleanFile.exists() && cleanFile.length() > 0) return cleanFile.getAbsolutePath();

            System.out.println("🎵 开始转码: " + fileName);

            // 2. 构建命令
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", originalPath,
                    "-ac", "1", "-ar", "24000", "-f", "wav",  // 降低采样率和声道，提升发送速度
                    "-filter:a", "loudnorm=I=-14:TP=-1",      // 统一响度，防止忽大忽小
                    cleanFile.getAbsolutePath()
            );

            // 【关键点1】把错误输出合并到标准输出，方便一起读取
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 【关键点2】启动一个新线程专门“吸走”FFmpeg的废话（日志）
            // 如果不加这一段，日志塞满缓冲区，FFmpeg就会卡死！
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 把它的废话打印出来，我要看看它到底死在哪了！
                        //System.out.println("[FFmpeg] " + line);
                    }
                } catch (IOException e) { /* 忽略 */ }
            }).start();

            // 3. 等待结果
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                return cleanFile.getAbsolutePath(); // ✅ 成功
            } else {
                // 如果还是失败，强制杀死进程
                if (process.isAlive()) process.destroyForcibly();
                System.err.println("❌ 转码超时/失败，尝试发送原文件...");
            }

        } catch (Exception e) {
            System.err.println("❌ 转码异常: " + e.getMessage());
        }

        // 4. 兜底：如果转码挂了，只能发原文件
        return originalFile.getAbsolutePath();
    }
    // --- V303: 竞猜生成逻辑 (纯净版 - 全程仅使用小表) ---
    private static String generateGuessGame() {
        String today = LocalDate.now().toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();

        // =======================================================
        // 1. 确定“真理” (从小表读取明日排期)
        // =======================================================
        DailySong targetDailySong = dbService.getDailySongByDate(tomorrow);

        if (targetDailySong == null) {
            System.out.println("⚠️ 竞猜取消: 小表中没有明日 (" + tomorrow + ") 的排期。");
            return "";
        }

        String targetName = targetDailySong.songName;
        // 空值熔断
        if (targetName == null || targetName.trim().isEmpty() || "null".equalsIgnoreCase(targetName)) {
            System.err.println("❌ 竞猜取消: 小表中明日歌曲名为 NULL。");
            return "";
        }

        // =======================================================
        // 2. 确定“干扰” (从小表读取未来排期)
        // =======================================================

        // 获取所有日期 > 今天的歌 (包含明天、后天、大后天...)
        List<String> futureSongs = dbService.getFutureDailySongs(today);
        List<String> distractorPool = new ArrayList<>();

        // 洗牌，保证每次抽的干扰项不一样
        java.util.Collections.shuffle(futureSongs);

        for (String candidate : futureSongs) {
            // 排除明日真题本身
            if (!candidate.equals(targetName)) {

                // 去重保护 (防止小表里未来几天填了重复的歌)
                if (!distractorPool.contains(candidate)) {
                    distractorPool.add(candidate);
                }

                // 只要凑齐 3 个就够了
                if (distractorPool.size() >= 3) break;
            }
        }

        // =======================================================
        // 3. 检查数量是否足够
        // =======================================================
        if (distractorPool.size() < 3) {
            System.out.println("⚠️ 竞猜取消: 小表(Daily Table)里未来的库存歌曲不足 3 首，无法凑齐选项。");
            System.out.println("   (当前可用干扰项: " + distractorPool + ")");
            return "";
            // 此时不生成竞猜，避免报错。只要您在 csv 里多填几天的数据（>4天），这里就能通过。
        }

        // =======================================================
        // 4. 组装试卷 (A-D)
        // =======================================================
        int correctPos = new java.util.Random().nextInt(4) + 1;
        String correctChar = "";

        String optA = "", optB = "", optC = "", optD = "其他歌曲";

        if (correctPos == 1) { // A 是真题
            correctChar = "A";
            optA = targetName;
            optB = distractorPool.get(0);
            optC = distractorPool.get(1);
        } else if (correctPos == 2) { // B 是真题
            correctChar = "B";
            optA = distractorPool.get(0);
            optB = targetName;
            optC = distractorPool.get(1);
        } else if (correctPos == 3) { // C 是真题
            correctChar = "C";
            optA = distractorPool.get(0);
            optB = distractorPool.get(1);
            optC = targetName;
        } else { // D 是真题
            correctChar = "D";
            optA = distractorPool.get(0);
            optB = distractorPool.get(1);
            optC = distractorPool.get(2);
        }

        // 5. 开启游戏
        long expireTime = System.currentTimeMillis() + (30 * 60 * 1000);
        currentGame = new GameSession(correctChar, expireTime);

        System.out.println("🎲 竞猜生成完毕 | 答案: " + correctChar + " | 明日: " + targetName);
        saveGameSnapshot();
        return "\n\n🎲 每日竞猜 (30分钟限时)\n" +
                "猜猜明天会推送哪首歌？\n" +
                "A. " + optA + "\n" +
                "B. " + optB + "\n" +
                "C. " + optC + "\n" +
                "D. " + optD + "\n" +
                "回复【对应字母】参与，猜对计入赛季积分榜，月末可能收获神秘小奖励！每名用户每天只记录第一次回答。";
    }

    // --- V304: 赛季周期管理 (自然月自适应版) ---


    // 2. 核心逻辑 (带参数，方便测试指令注入伪造日期)
    private static void manageSeasonCycles(LocalDate date, boolean isTest) {
        int day = date.getDayOfMonth();         // 今天是几号 (1-31)
        int lastDay = date.lengthOfMonth();     // 本月一共多少天 (28/29/30/31 自动识别闰年)
        boolean isMonthEnd = (day == lastDay);  // 今天是不是最后一天

        System.out.println("📅 赛季检查: " + date + " (本月共" + lastDay + "天)");

        String reportMsg = null;
        boolean isWeeklyReset = false;
        boolean isMonthlyReset = false;

        // =========================================================
        // 逻辑 A: 月度总结算 (优先级最高)
        // 触发条件: 本月最后一天 (可能是 28/29/30/31)
        // =========================================================
        if (isMonthEnd) {
            String topList = dbService.getTopPlayers("monthly");
            reportMsg = "🏁 " + date.getMonth().getValue() + "月赛季圆满结束！\n" +
                    topList + "\n\n(竞猜次数已重置，新赛季明日开启)";

            isWeeklyReset = true;  // 清周分
            isMonthlyReset = true; // 清月分
        }

        // =========================================================
        // 逻辑 B: 周常结算 (Day 7, 14, 21)
        // 触发条件: 7号、14号、21号
        // (注: 28号如果是月底，会被上面的逻辑A捕获；如果不是月底，则跳过结算，直接并入最后的大周)
        // =========================================================
        else if (day == 7 || day == 14 || day == 21) {
            String topList = dbService.getTopPlayers("weekly");
            reportMsg = "本周结算完成 (Day " + day + ")！\n" +
                    topList + "\n\n(周积分已重置，月积分继续累积)";

            isWeeklyReset = true; // 只清周分
        }

        // =========================================================
        // 执行结算动作
        // =========================================================
        if (reportMsg != null) {
            // 1. 发送战报 (测试模式下，可以在消息前加个【预览】前缀)
            String prefix = isTest ? "【预览模式】(不清除数据)\n" : "";
            for (Long gid : DAILY_PUSH_GROUPS) {
                apiClient.sendGroupMessage(gid, prefix + reportMsg);
            }

            // 2. 清理数据库 (★★★ 关键修改 ★★★)
            if (!isTest) {
                dbService.resetScores(isMonthlyReset);
                System.out.println("✅ 结算完成: 数据已重置");
            } else {
                System.out.println("🧪 测试结算: 数据库未变动 (Dry Run)");
            }
        }
    }

    // 重载旧方法，保持定时任务的兼容性
    private static void manageSeasonCycles(LocalDate date) {
        manageSeasonCycles(date, false); // 默认不是测试，是真的执行
    }

    // 重载默认入口
    private static void manageSeasonCycles() {
        manageSeasonCycles(LocalDate.now(), false);
    }
    // 【新增】从 CQ 码中提取指定 key 的 int 值
    // 例如从 "[CQ:reply,id=12345]" 中提取 "id" -> 12345
    private static int extractCQValueInt(String msg, String type, String key) {
        try {
            String pattern = "\\[CQ:" + type + ".*?" + key + "=(\\d+)";
            java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = r.matcher(msg);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception e) {
            // 解析失败返回 0
        }
        return 0;
    }
    private static int extractJsonInt(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start == -1) return 0;
        start += searchKey.length();
        int endComma = json.indexOf(",", start);
        int endBrace = json.indexOf("}", start);
        int end = endComma;
        if (end == -1 || (endBrace != -1 && endBrace < endComma)) { end = endBrace; }
        if (end == -1) return 0;
        try { return Integer.parseInt(json.substring(start, end).trim()); } catch (Exception e) { return 0; }
    }
    // --- V400: 数据持久化 (防重启丢失) ---
    private static final String GAME_SESSION_FILE = "data/game_session.dat";

    private static void saveGameSnapshot() {
        if (currentGame == null) return;
        try (ObjectOutputStream oos = new ObjectOutputStream(new java.io.FileOutputStream(GAME_SESSION_FILE))) {
            oos.writeObject(currentGame);
            // System.out.println("💾 游戏状态已保存");
        } catch (IOException e) {
            System.err.println("❌ 保存游戏状态失败: " + e.getMessage());
        }
    }

    // 【修改】同时支持清洗 A 和 B 前缀
    private static String getDisplayDate(String rawDate) {
        if (rawDate == null) return "未知日期";
        String clean = rawDate.trim();

        // 如果是 A 或 B 开头，去掉第一个字符
        if (clean.startsWith("A") || clean.startsWith("B")) {
            return clean.substring(1);
        }
        return rawDate;
    }
    private static void loadGameSnapshot() {
        File file = new File(GAME_SESSION_FILE);
        if (!file.exists()) return;

        try (ObjectInputStream ois = new ObjectInputStream(new java.io.FileInputStream(file))) {
            // The snapshot is local state, but never deserialize arbitrary classes if the file is replaced.
            ois.setObjectInputFilter(java.io.ObjectInputFilter.Config.createFilter(
                    "maxdepth=10;maxrefs=10000;maxbytes=1048576;java.base/*;com.mybot.SongBot$GameSession;!*"));
            currentGame = (GameSession) ois.readObject();
            System.out.println("📂 成功恢复上次的竞猜游戏状态 (榜单人数: " + currentGame.correctNicknames.size() + ")");
        } catch (Exception e) {
            System.err.println("⚠️ 读取游戏存档失败 (可能是旧版本数据): " + e.getMessage());
        }
    }
}
