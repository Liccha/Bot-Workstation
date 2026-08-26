package com.mybot; // 请确保和您的 SongBot.java 的包名一致

import java.util.List;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.io.File;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
public class Stable {

    // ==========================================
    // ⚙️ 自定义配置区 (请在此处填入您的专属信息)
    // ==========================================
    private static final long TARGET_GROUP_ID = 2000000006L; // 👈 填入您的特定推广群号 (注意末尾保留 'L')
    private static final String PUSH_TIME = "20:00"; // 👈 定时推送时间 (格式 "HH:mm")
    private static final String CSV_PATH = "stable_info.csv"; // 👈 CSV文件的路径

    private Connection conn;
    private NapCatClient apiClient; // ✅ 真正正确的类名是 NapCatClient
    private ScheduledExecutorService scheduler;
    private String lastPushedSid = "";
    private java.time.LocalDate lastPushedDate = null; // 记录今天是否已经推送过
    public Stable(Connection conn, NapCatClient apiClient) { // ✅ 这里也要改成 NapCatClient
        this.conn = conn;
        this.apiClient = apiClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    // 1. 初始化数据库 (每次启动重新读取 CSV)
    public void initDatabase() {
        initDatabase(Path.of(CSV_PATH));
    }

    /**
     * Imports Stable data atomically. A missing, unreadable or empty CSV must
     * never erase the last usable stable_info table.
     */
    boolean initDatabase(Path csvPath) {
        System.out.println("📥 开始初始化独立推广库 (Stable)...");
        final List<String[]> rows;
        try {
            rows = readStableCsv(csvPath);
            if (rows.isEmpty()) throw new IOException("CSV 中没有可用曲目");
        } catch (Exception error) {
            System.err.println("❌ Stable 推广库初始化已取消，保留现有数据: " + error.getMessage());
            return false;
        }

        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS stable_info_import");
                stmt.execute("CREATE TABLE stable_info_import (" +
                    "sid TEXT PRIMARY KEY, title TEXT, artist TEXT, bpm TEXT, length TEXT, " +
                    "creator TEXT, update_time TEXT, cover TEXT)");
            }

            String insertSql = "INSERT INTO stable_info_import " +
                "(sid, title, artist, bpm, length, creator, update_time, cover) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (String[] data : rows) {
                    for (int column = 0; column < 8; column++) pstmt.setString(column + 1, data[column]);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet result = stmt.executeQuery("SELECT COUNT(*) FROM stable_info_import")) {
                if (!result.next() || result.getInt(1) != rows.size()) {
                    throw new java.sql.SQLException("暂存表行数校验失败");
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS stable_info");
                stmt.execute("ALTER TABLE stable_info_import RENAME TO stable_info");
            }
            conn.commit();
            System.out.println("✅ Stable 推广库导入完成！共导入 " + rows.size() + " 首曲目。");
            return true;
        } catch (Exception error) {
            try { conn.rollback(); } catch (Exception ignored) {}
            System.err.println("❌ Stable 推广库初始化失败，已回滚并保留现有数据: " + error.getMessage());
            return false;
        } finally {
            try { conn.setAutoCommit(previousAutoCommit); } catch (Exception ignored) {}
        }
    }

    private static List<String[]> readStableCsv(Path csvPath) throws IOException {
        if (!Files.isRegularFile(csvPath)) throw new IOException("未找到 " + csvPath.toAbsolutePath());
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            if (reader.readLine() == null) throw new IOException("CSV 文件为空");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] source = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                if (source.length < 8) continue;
                String[] data = new String[8];
                for (int i = 0; i < data.length; i++) {
                    String value = source[i].trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
                    }
                    data[i] = value;
                }
                if (!data[0].isEmpty()) rows.add(data);
            }
        }
        return rows;
    }

    // 2. 启动定时器 (严格踩点推送版)
    public void startScheduler() {
        if (TARGET_GROUP_ID == 0L) {
            System.out.println("⚠️ Stable 推广未设置群号，已跳过定时任务启动。");
            return;
        }

        Runnable pushTask = () -> {
            try {
                LocalTime targetTime = LocalTime.parse(PUSH_TIME);
                LocalDateTime now = LocalDateTime.now();

                // 核心逻辑：当前时间的“小时”和“分钟”，必须与设定时间完全相等！(精确到分钟)
                if (now.getHour() == targetTime.getHour() && now.getMinute() == targetTime.getMinute()) {
                    // 并且今天还没有处理过 (防止在 20:00 的这 60 秒内被多次触发)
                    if (lastPushedDate == null || !lastPushedDate.equals(now.toLocalDate())) {

                        // 【新增判断】：只有在不是星期天 (SUNDAY) 的情况下，才执行推送
                        if (now.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                            doDailyPush();
                        } else {
                            System.out.println("ℹ️ 今天是周日，按设定跳过每日上架谱面推荐。");
                        }

                        // 标记今天已处理完 (无论是推送了还是跳过了，都标记一下防重复)
                        lastPushedDate = now.toLocalDate();
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 每日推荐时钟检测失败: " + e.getMessage());
                e.printStackTrace();
            }
        };

        // 每隔 1 分钟检查一次真实系统时间
        scheduler.scheduleAtFixedRate(pushTask, 0, 1, TimeUnit.MINUTES);
        System.out.println("⏰ 每日谱面推荐守护线程已启动！严格推送时间锁定为每天: " + PUSH_TIME);
    }

    // 3. 抽取并发送的核心逻辑 (每日推送)
    private void doDailyPush() {
        System.out.println("🚀 开始执行每日特定群谱面推荐...");
        String sql = "SELECT * FROM stable_info WHERE sid != ? ORDER BY RANDOM() LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lastPushedSid);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                lastPushedSid = rs.getString("sid"); // 更新防重复缓存
                // 传 0L 代表是推送
                sendStableSearchResult(TARGET_GROUP_ID, 0L, rs.getString("sid"), rs.getString("title"), rs.getString("artist"), rs.getString("bpm"), rs.getString("length"), rs.getString("creator"), rs.getString("update_time"), rs.getString("cover"));
            } else {
                System.err.println("⚠️ Stable 推广库中没有找到可用歌曲！");
            }
        } catch (Exception e) {
            System.err.println("❌ 抽取 Stable 推荐时出错: " + e.getMessage());
        }
    }

    // 4. 【修改】处理群聊的主动查询 (支持多首结果列表菜单)

    public void handleGroupMessage(long groupId, long userId, String message) {
        if (groupId != TARGET_GROUP_ID) return;
        if (!message.startsWith("!") && !message.startsWith("！")) return;

        String keyword = message.substring(1).trim();
        if (keyword.isEmpty()) return;

        boolean isSidSearch = false;

        // 判断是否是精确查询 sid (支持 !1234 或 !s1234)
        if (keyword.matches("^[sS]?\\d+$")) {
            isSidSearch = true;
            if (keyword.toLowerCase().startsWith("s")) {
                keyword = keyword.substring(1);
            }
        }

        try {
            if (isSidSearch) {
                // 【情况A】如果是精准搜 SID
                String sql = "SELECT * FROM stable_info WHERE sid = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, keyword);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        sendStableSearchResult(groupId, userId, rs.getString("sid"), rs.getString("title"), rs.getString("artist"), rs.getString("bpm"), rs.getString("length"), rs.getString("creator"), rs.getString("update_time"), rs.getString("cover"));
                    }
                }
            } else {
                // 【情况B】如果是模糊搜索名称/作者/谱师 (引入 opencc4j 繁简转换)
                Set<String> searchKeywords = new HashSet<>();
                searchKeywords.add(keyword);
                try {
                    searchKeywords.add(ZhConverterUtil.toSimple(keyword));
                    searchKeywords.add(ZhConverterUtil.toTraditional(keyword));
                } catch (Exception e) {
                    System.err.println("⚠️ 繁简转换失败: " + e.getMessage());
                }

                // 动态拼装 SQL (有几个变体就拼几个 OR)
                StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM stable_info WHERE ");
                List<String> conditions = new ArrayList<>();
                for (int i = 0; i < searchKeywords.size(); i++) {
                    conditions.add("(title LIKE ? OR creator LIKE ? OR artist LIKE ?)");
                }
                sqlBuilder.append(String.join(" OR ", conditions));

                try (PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {
                    // 将所有变体关键词依次注入 SQL 参数
                    int index = 1;
                    for (String kw : searchKeywords) {
                        String likeKw = "%" + kw + "%";
                        pstmt.setString(index++, likeKw);
                        pstmt.setString(index++, likeKw);
                        pstmt.setString(index++, likeKw);
                    }

                    ResultSet rs = pstmt.executeQuery();
                    // 用一个列表把搜到的所有结果先存起来
                    List<String[]> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(new String[]{
                                rs.getString("sid"), rs.getString("title"), rs.getString("artist"),
                                rs.getString("bpm"), rs.getString("length"), rs.getString("creator"),
                                rs.getString("update_time"), rs.getString("cover")
                        });
                    }

                    if (results.size() == 1) {
                        // 【情况B-1】刚好只搜到一首，直接发图文详情
                        String[] data = results.get(0);
                        sendStableSearchResult(groupId, userId, data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7]);

                    } else if (results.size() > 1) {
                        // 【情况B-2】搜到多首，发送菜单列表
                        StringBuilder listMsg = new StringBuilder();
                        listMsg.append("[CQ:at,qq=").append(userId).append("]\n");
                        listMsg.append("包含[").append(keyword).append("]的歌曲共有 ").append(results.size()).append(" 首(发送“！”+歌曲编号可进行详细查找)\n");

                        // 防止搜索词太宽泛导致一次发出几百首刷屏，这里限制最多展示前 2000 条
                        int limit = Math.min(results.size(), 2000);
                        for (int i = 0; i < limit; i++) {
                            String[] data = results.get(i);
                            listMsg.append("s").append(data[0]).append("-").append(data[1]).append("\n");
                        }
                        if (results.size() > 2000) {
                            listMsg.append("... (结果过多，请尝试更精确的搜索词)");
                        }

                        // 发送列表消息
                        apiClient.sendGroupMessage(groupId, listMsg.toString().trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Stable 查询出错: " + e.getMessage());
        }
    }

    // 5. 【公共方法】统一组装图文消息并发送
    private void sendStableSearchResult(long groupId, long userId, String sid, String title, String artist, String bpm, String lengthRaw, String creator, String updateTimeRaw, String cover) throws Exception {

        // 转换时长
        String lengthFormatted = "未知";
        try {
            int seconds = Integer.parseInt(lengthRaw.trim());
            lengthFormatted = String.format("%02d:%02d", seconds / 60, seconds % 60);
        } catch (Exception e) {
            lengthFormatted = lengthRaw;
        }

        // 截取日期
        String dateFormatted = updateTimeRaw;
        if (updateTimeRaw != null && updateTimeRaw.contains(" ")) {
            dateFormatted = updateTimeRaw.split(" ")[0];
        }

        // 拼装消息
        StringBuilder finalMsg = new StringBuilder();

        // 根据 userId 区分是主动推送还是被动查询
        if (userId == 0L) {
            finalMsg.append("【每日上架谱面推荐】\n");
        } else {
            finalMsg.append("[CQ:at,qq=").append(userId).append("]\n");
        }

        finalMsg.append("歌曲名称:").append(title).append("\n");
        finalMsg.append("sid:").append(sid).append("\n");
        finalMsg.append("作者:").append(artist).append("\n");
        finalMsg.append("谱师:").append(creator).append("\n");
        finalMsg.append("BPM:").append(bpm).append("\n");
        finalMsg.append("时长:").append(lengthFormatted).append("\n");
        finalMsg.append("上架时间:").append(dateFormatted).append("\n");
        finalMsg.append("【如何下载谱面】↓↓\n");
        finalMsg.append("https://m.mugzone.net/wiki/2438");

        // 拼接图片部分
        if (cover != null && !cover.trim().isEmpty()) {
            java.io.File imgFile = new java.io.File(cover);
            if (imgFile.exists()) {
                String absPath = imgFile.getAbsolutePath().replace("\\", "/");
                String safePath = absPath.replace("&", "&amp;")
                        .replace("[", "&#91;")
                        .replace("]", "&#93;")
                        .replace(",", "&#44;");
                finalMsg.append("\n[CQ:image,file=file:///").append(safePath).append("]");
            }
        }

        // 发送消息
        apiClient.sendGroupMessage(groupId, finalMsg.toString());
        if (userId == 0L) {
            System.out.println("✅ 特定群谱面推荐推送成功！曲目: " + title);
        } else {
            System.out.println("✅ 特定群谱面查询响应成功！曲目: " + title);
        }
    }}
