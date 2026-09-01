package com.mybot;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
// ... 其他的 import
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.SymbolAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.chart.ChartUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import javax.imageio.ImageIO;
public class DatabaseService {

    // 1. 【新增】定义全局连接变量 (这是缺少的那一行！)
    private java.sql.Connection connection;
    private final String databaseUrl;

    public DatabaseService() {
        this(configuredDatabasePath());
    }

    DatabaseService(Path databasePath) {
        Path absoluteDatabase = databasePath.toAbsolutePath().normalize();
        this.databaseUrl = "jdbc:sqlite:" + absoluteDatabase;
        try {
            Class.forName("org.sqlite.JDBC");

            // 【关键修改】使用 SQLiteConfig 开启高级并发模式
            org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();

            // 1. 开启 WAL 模式 (Write-Ahead Logging)
            // 效果：允许“边写边读”，大大减少锁文件的情况。
            config.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);

            // 2. 增加忙碌等待时间 (30秒)
            // 效果：如果文件被锁，让程序乖乖排队等30秒，而不是立刻报错崩溃。
            config.setBusyTimeout(30000);

            // 应用配置
            connection = DriverManager.getConnection(databaseUrl, config.toProperties());

            createTables();
            createMemberStatsTable();
            initDailyTables();
            createLikesTable();

            // fixDailyStatsTable(); // 这行你之前注释掉了，保持原样即可

            System.out.println("✅ 数据库连接成功 (WAL模式已开启)");

        } catch (Exception e) {
            System.err.println("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Path configuredDatabasePath() {
        String explicit = System.getProperty("songbot.database", "").trim();
        if (!explicit.isEmpty()) return Path.of(explicit);
        String home = System.getProperty("songbot.home", "").trim();
        if (!home.isEmpty()) return Path.of(home).resolve("song_data.db");
        return Path.of("song_data.db");
    }
    // ---------------------------------------------------------
    // 【补丁】这就是你缺失的 createTables 方法
    // ---------------------------------------------------------
    private void createTables() {
        // 使用全局 connection (即构造函数里打开的那个数据库)
        try (Statement stmt = connection.createStatement()) {

            // 1. 创建用户统计表 (user_stats)
            // 这张表用于记录 query_count (查询次数) 和 warning_count (警告次数)
            String sqlUserStats = "CREATE TABLE IF NOT EXISTS user_stats (" +
                    "user_id INTEGER PRIMARY KEY, " +
                    "nickname TEXT, " +
                    "query_count INTEGER DEFAULT 0, " +
                    "warning_count INTEGER DEFAULT 0" +
                    ")";
            stmt.execute(sqlUserStats);

            // 2. 创建歌曲表 (songs)
            // 注意：通常 songs 表是预置好数据的。这里加一个 IF NOT EXISTS 防止空数据库报错。
            // 如果你的数据库里已经有 songs 表，这句会被自动跳过。
            String sqlSongs = "CREATE TABLE IF NOT EXISTS songs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "song_name TEXT, " +
                    "author TEXT, " +
                    "bpm TEXT, " +
                    "duration TEXT, " +
                    "image_path TEXT, " +
                    "audio_path TEXT, " +
                    "album TEXT, " +
                    "album_ids TEXT" +
                    // ... 其他字段如果数据库是新的会自动缺失，但在老库里没事
                    ")";
            stmt.execute(sqlSongs);

            System.out.println("✅ 基础表结构检查完成 (user_stats, songs)");

        } catch (SQLException e) {
            System.err.println("❌ 初始化基础表失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    // ... 下面是你原来的代码 ...
    // --- 1. 用户统计 ---

    public void logUserActivity(long userId, String nickname) {
        String sql = "INSERT INTO user_stats (user_id, nickname, query_count) VALUES (?, ?, 1) " +
                "ON CONFLICT(user_id) DO UPDATE SET " +
                "query_count = query_count + 1, " +
                "nickname = ?";

        // 【修改】直接使用 this.connection，不再创建新连接
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, nickname);
            pstmt.setString(3, nickname);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("统计失败: " + e.getMessage());
        }
    }
    public void logUserWarning(long userId, String nickname) {
        String sql = "INSERT INTO user_stats (user_id, nickname, warning_count) VALUES (?, ?, 1) " +
                "ON CONFLICT(user_id) DO UPDATE SET " +
                "warning_count = warning_count + 1, " +
                "nickname = ?";

        // 【修改】直接使用 this.connection
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, nickname);
            pstmt.setString(3, nickname);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("记录警告失败: " + e.getMessage());
        }
    }
    // --- V98 修复: 彻底解决 SQLITE_BUSY 锁死问题 ---
    // (逻辑：先全部读出来，关掉连接；然后再去联网查和更新)

    // 1. 定义一个临时的小类来存数据 (放在 exportUserStatsToCsv 方法外面，或者直接放在 DatabaseService 类里都可以)
    private static class UserStat {
        long userId;
        String nickname;
        int queryCount;
        int warningCount;

        public UserStat(long userId, String nickname, int queryCount, int warningCount) {
            this.userId = userId;
            this.nickname = nickname;
            this.queryCount = queryCount;
            this.warningCount = warningCount;
        }
    }

    // ★★★ 修改：增加 long adminId 参数，用于获取管理员名字命名文件 ★★★
    public String exportUserStatsToCsv(NapCatClient apiClient, long adminId) {

        // 1. 获取管理员昵称
        String adminName = apiClient.getStrangerInfo(adminId);

        // 2. 清洗非法字符 (Windows文件名不能包含 \ / : * ? " < > | )
        // 如果名字获取失败或含有非法字符，替换为下划线
        String safeName = adminName.replaceAll("[\\\\/:*?\"<>|]", "_");

        // 3. 生成文件名：只给【昵称】悄悄看.csv
        String filename = "只给" + safeName + "悄悄看.csv";

        // ... (下面的 List<UserStat> allStats = ... 代码保持不变，不用动) ...
        List<UserStat> allStats = new ArrayList<>();

        // ==========================================
        // 第一阶段：只读数据 (Read Phase)
        // ==========================================
        String selectSql = "SELECT * FROM user_stats ORDER BY query_count DESC";
        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {

            while (rs.next()) {
                // 把数据从数据库搬到内存 List 里
                allStats.add(new UserStat(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getInt("query_count"),
                        rs.getInt("warning_count")
                ));
            }
        } catch (SQLException e) {
            System.err.println("读取数据失败: " + e.getMessage());
            return null;
        }
        // 到这里，conn 自动关闭，数据库锁释放。现在数据库是自由的了。

        // ==========================================
        // 第二阶段：处理、更新、写入 (Process & Write Phase)
        // ==========================================
        String updateSql = "UPDATE user_stats SET nickname = ? WHERE user_id = ?";

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement pstmt = conn.prepareStatement(updateSql);
             PrintWriter writer = new PrintWriter(new FileWriter(filename, StandardCharsets.UTF_8))) {

            writer.write('\ufeff'); // BOM 头，防乱码
            writer.println("QQ号,最新昵称,查询总次数,警告/屏蔽总次数");

            for (UserStat stat : allStats) {
                String nick = stat.nickname;
                long uid = stat.userId;

                // 检查是否需要联网更新
                if (nick == null || nick.equals("临时会话") || nick.isEmpty()) {
                    System.out.println("🔄 正在补全用户信息: " + uid);
                    String realName = apiClient.getStrangerInfo(uid);

                    if (!"未知用户".equals(realName)) {
                        nick = realName;
                        // 更新数据库 (此时是安全的，因为没有并行的 SELECT)
                        try {
                            pstmt.setString(1, realName);
                            pstmt.setLong(2, uid);
                            pstmt.executeUpdate();
                        } catch (SQLException e) {
                            System.err.println("更新名字失败: " + e.getMessage());
                        }
                    }
                }

                // 处理 CSV 格式
                if (nick != null && nick.contains(",")) nick = "\"" + nick + "\"";

                writer.println(uid + "," + nick + "," + stat.queryCount + "," + stat.warningCount);
            }
            return filename;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // --- 2. 全局扫描 ---

    // 扫描所有专辑
    public Map<Integer, String> getAllUniqueAlbums() {
        Map<Integer, String> albumMap = new TreeMap<>();
        String sql = "SELECT album_ids, album FROM songs WHERE album IS NOT NULL AND album != ''";
        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String idsRaw = rs.getString("album_ids");
                String nameRaw = rs.getString("album");
                if (idsRaw != null && !idsRaw.trim().isEmpty()) {
                    String cleanIds = idsRaw.replaceAll("^,+|,+$", "");
                    String[] ids = cleanIds.split(",");
                    String[] names = nameRaw.split("\\|");
                    for (int i = 0; i < ids.length; i++) {
                        try {
                            String idStr = ids[i].trim();
                            if (idStr.isEmpty()) continue;
                            int id = Integer.parseInt(idStr);
                            String currentName = nameRaw;
                            if (i < names.length) { currentName = names[i].trim(); }
                            albumMap.put(id, currentName);
                        } catch (NumberFormatException e) { }
                    }
                }
            }
        } catch (SQLException e) { System.err.println("扫描专辑列表出错: " + e.getMessage()); }
        return albumMap;
    }

    // --- V90 新增: 获取所有歌曲 (用于难度统计) ---
    public List<Song> getAllSongs() {
        List<Song> allSongs = new ArrayList<>();
        String sql = "SELECT * FROM songs"; // 获取全库数据
        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                allSongs.add(mapRowToSong(rs));
            }
        } catch (SQLException e) {
            System.err.println("获取所有歌曲失败: " + e.getMessage());
        }
        return allSongs;
    }

    // 获取所有非空作者名 (用于曲师统计)
    public List<String> getAllAuthors() {
        List<String> authors = new ArrayList<>();
        String sql = "SELECT author FROM songs WHERE author IS NOT NULL AND author != ''";

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                authors.add(rs.getString("author"));
            }
        } catch (SQLException e) {
            System.err.println("获取作者列表失败: " + e.getMessage());
        }
        return authors;
    }

    // --- 3. 搜索方法 (V75: 支持11个参数匹配) ---

    public List<Song> searchByKeywordExact(String keyword) {
        List<Song> results = new ArrayList<>();
        // 修改 SQL：增加了 OR ... 4, 5, 6 (共 11 个条件)
        String sql = "SELECT * FROM songs WHERE song_name = ? " +
                "OR author = ? OR charter = ? " +
                "OR song_nickname = ? OR artist_nickname = ? " +
                "OR album = ? " +
                "OR song_nickname2 = ? OR song_nickname3 = ? " +
                "OR song_nickname4 = ? OR song_nickname5 = ? OR song_nickname6 = ?";

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 循环填充 11 个问号
            for(int i=1; i<=11; i++) {
                pstmt.setString(i, keyword);
            }

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) { results.add(mapRowToSong(rs)); }
        } catch (SQLException e) { System.err.println("精确搜索出错: " + e.getMessage()); }
        return results;
    }

    public List<Song> searchByKeywordFuzzy(String keyword) {
        List<Song> results = new ArrayList<>();
        String sql = "SELECT * FROM songs WHERE song_name LIKE ? " +
                "OR author LIKE ? OR charter LIKE ? " +
                "OR song_nickname LIKE ? OR artist_nickname LIKE ? " +
                "OR album LIKE ? " +
                "OR song_nickname2 LIKE ? OR song_nickname3 LIKE ? " +
                "OR song_nickname4 LIKE ? OR song_nickname5 LIKE ? OR song_nickname6 LIKE ?";

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String searchTerm = "%" + keyword + "%";
            for(int i=1; i<=11; i++) {
                pstmt.setString(i, searchTerm);
            }

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) { results.add(mapRowToSong(rs)); }
        } catch (SQLException e) { System.err.println("模糊搜索出错: " + e.getMessage()); }
        return results;
    }

    public Song searchById(int songId) {
        String sql = "SELECT * FROM songs WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, songId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) { return mapRowToSong(rs); }
        } catch (SQLException e) { System.err.println("按 ID 搜索出错: " + e.getMessage()); }
        return null;
    }

    public List<Song> searchByAlbumId(String albumId) {
        List<Song> results = new ArrayList<>();
        String searchTerm = "%," + albumId + ",%";
        String sql = "SELECT * FROM songs WHERE ',' || album_ids || ',' LIKE ? ORDER BY id ASC";
        try (Connection conn = DriverManager.getConnection(databaseUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, searchTerm);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) { results.add(mapRowToSong(rs)); }
        } catch (SQLException e) { System.err.println("按专辑 ID 搜索时出错: " + e.getMessage()); }
        return results;
    }

    // --- 4. 核心映射 (V75 Final: 读取所有新字段) ---
    private Song mapRowToSong(ResultSet rs) throws SQLException {
        return new Song(
                rs.getInt("id"), rs.getString("song_name"), rs.getString("author"), rs.getString("charter"),
                rs.getString("bpm"), rs.getString("duration"), rs.getString("album"),
                rs.getString("album_ids"), rs.getString("song_nickname"), rs.getString("artist_nickname"),

                // 扩展的花名列
                rs.getString("song_nickname2"),
                rs.getString("song_nickname3"),
                rs.getString("song_nickname4"),
                rs.getString("song_nickname5"),
                rs.getString("song_nickname6"),

                rs.getString("album_date"),
                rs.getString("album_image_path"),

                rs.getString("4k_ez"), rs.getString("4k_nm"), rs.getString("4k_hd"), rs.getString("4k_mx"), rs.getString("4k_sp"),
                rs.getString("5k_ez"), rs.getString("5k_nm"), rs.getString("5k_hd"), rs.getString("5k_mx"), rs.getString("5k_sp"),
                rs.getString("6k_ez"), rs.getString("6k_nm"), rs.getString("6k_hd"), rs.getString("6k_mx"), rs.getString("6k_sp"),
                rs.getString("7k_ez"), rs.getString("7k_nm"), rs.getString("7k_hd"), rs.getString("7k_mx"), rs.getString("7k_sp"),
                rs.getString("8k_ez"), rs.getString("8k_nm"), rs.getString("8k_hd"), rs.getString("8k_mx"), rs.getString("8k_sp"),

                rs.getString("image_path"), rs.getString("audio_path")
        );
    }
    // --- V93 新增: 成员时长统计表 ---

    // 1. 初始化表 (Bot启动时会自动调用，如果表不存在则创建)
    // 请在 DatabaseService 的构造函数 public DatabaseService() {...} 里
    // 加上一句: createMemberStatsTable();
    public void createMemberStatsTable() {
        String sql = "CREATE TABLE IF NOT EXISTS member_stats (" +
                "group_id INTEGER, " +
                "user_id INTEGER, " +
                "join_time INTEGER, " +
                "PRIMARY KEY (group_id, user_id))";
        // 【修改】直接用 connection
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            System.err.println("创建统计表失败: " + e.getMessage());
        }
    }

    // 【修改】原有方法重载 1：自动用当前时间 (给新人进群用)
    public void recordJoinTime(long groupId, long userId) {
        recordJoinTime(groupId, userId, System.currentTimeMillis());
    }

    public void recordJoinTime(long groupId, long userId, long timeInMillis) {
        String sql = "INSERT OR REPLACE INTO member_stats (group_id, user_id, join_time) VALUES (?, ?, ?)";
        // 【修改】直接用 connection
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, groupId);
            pstmt.setLong(2, userId);
            pstmt.setLong(3, timeInMillis);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("记录入群时间失败: " + e.getMessage());
        }
    }

    public long getAndRemoveJoinTime(long groupId, long userId) {
        long joinTime = 0;
        String selectSql = "SELECT join_time FROM member_stats WHERE group_id = ? AND user_id = ?";
        String deleteSql = "DELETE FROM member_stats WHERE group_id = ? AND user_id = ?";

        // 【修改】直接用 connection
        try (PreparedStatement pstmtSelect = connection.prepareStatement(selectSql)) {
            pstmtSelect.setLong(1, groupId);
            pstmtSelect.setLong(2, userId);
            ResultSet rs = pstmtSelect.executeQuery();
            if (rs.next()) {
                joinTime = rs.getLong("join_time");
            }
            rs.close(); // 记得关闭结果集

            // 获取完后删除记录
            // 【修改】直接用 connection
            try (PreparedStatement pstmtDelete = connection.prepareStatement(deleteSql)) {
                pstmtDelete.setLong(1, groupId);
                pstmtDelete.setLong(2, userId);
                pstmtDelete.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("查询退群时间失败: " + e.getMessage());
        }
        return joinTime;
    }
    public void initDailyTables() {
        // ✅ 修正 1: 表名统一为 daily_songs
        // ✅ 修正 2: 列名统一为 id (因为现在是ID模式)
        String sqlFixed = "CREATE TABLE IF NOT EXISTS daily_songs (" +
                "id INTEGER PRIMARY KEY, " +
                "song_name TEXT, " +
                "audio_path TEXT, " +
                "author TEXT" +
                ");";

        String sqlLogs = "CREATE TABLE IF NOT EXISTS command_logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "action_time INTEGER" +
                ");";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sqlFixed);
            stmt.execute(sqlLogs);
            System.out.println("✅ 每日排期表检查完成 (song_data.db)");
        } catch (SQLException e) {
            System.err.println("❌ 初始化表失败: " + e.getMessage());
        }
    }
    // 【新增】记账方法 (SongBot 调用的就是它！)
    public void logCommand() {
        String sql = "INSERT INTO command_logs(action_time) VALUES(?)";
        // 【修改】使用 this.connection，删掉原来的 connect() 调用
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    public void importDailySchedule(String csvPath) {
        System.out.println("📥 开始导入歌曲库 (ID模式)...");
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            if (br.readLine() == null) throw new IOException("CSV 文件为空");
            String line;
            while ((line = br.readLine()) != null) {
                String[] data = parseCsvRow(line, 3);
                if (data != null && !data[0].isEmpty() && !data[1].isEmpty()) rows.add(data);
            }
        } catch (Exception error) {
            System.err.println("❌ 每日曲库导入已取消，保留现有数据: " + error.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            System.err.println("❌ 每日曲库 CSV 没有可用曲目，保留现有数据。");
            return;
        }

        boolean previousAutoCommit = true;
        String sql = "INSERT INTO daily_songs (id, song_name, author) VALUES (?, ?, ?)";
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement clearStmt = connection.createStatement()) {
                clearStmt.execute("DELETE FROM daily_songs");
            }
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                for (String[] data : rows) {
                    pstmt.setString(1, data[0]);
                    pstmt.setString(2, data[1]);
                    pstmt.setString(3, data[2]);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
            connection.commit();
            System.out.println("✅ 每日曲库导入完成 (ID模式)，共 " + rows.size() + " 首。");
        } catch (Exception error) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            System.err.println("❌ 每日曲库导入失败，已回滚并保留现有数据: " + error.getMessage());
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) {}
        }
    }
    public Connection getConnection() { return this.connection; }
    // 【新增】根据 ID 获取歌曲信息
    public DailySong getSongById(String id) {
        // ID 存在 date 列里
        String sql = "SELECT * FROM daily_schedule WHERE date = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                // 动态构建路径：C:/Users/.../DailySongs/ID.mp3
                // 注意：这里构建的是“原始ID路径”，发完改名是之后的事
                String dynamicPath = "C:\\Users\\12269\\Desktop\\DailySongs\\" + id + ".mp3";
                return new DailySong(
                        rs.getString("date"), // ID
                        rs.getString("song_name"),
                        rs.getString("author"),
                        dynamicPath
                );
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // 【修改】标记今日已发送 (打卡) - 极简版
    public void markPushedToday(String date, String songName) {
        // ✅ 修正：删掉了 winner 和 guess_count，只存日期和歌名
        // 这样数据库里没有那两列也不会报错了
        String sql = "INSERT INTO daily_stats (date, song_name) VALUES (?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, date);
            pstmt.setString(2, songName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            // 忽略主键重复错误 (说明已经打过卡了)
        }
    }
    // 【新增】自动修复数据库表结构 (补全缺失的列)
    private void fixDailyStatsTable() {
        try (Statement stmt = connection.createStatement()) {
            // 尝试添加 winner 列 (如果已存在会报错，catch住忽略即可)
            try {
                stmt.executeUpdate("ALTER TABLE daily_stats ADD COLUMN winner TEXT");
                System.out.println("🔧 已修复 daily_stats 表：添加 winner 列");
            } catch (SQLException e) {
                // 列已存在，忽略
            }

            // 尝试添加 guess_count 列
            try {
                stmt.executeUpdate("ALTER TABLE daily_stats ADD COLUMN guess_count INTEGER DEFAULT 0");
                System.out.println("🔧 已修复 daily_stats 表：添加 guess_count 列");
            } catch (SQLException e) {
                // 列已存在，忽略
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    // --- 获取指定日期的歌曲排期 ---
    public DailySong getDailySongByDate(String date) {
        String sql = "SELECT * FROM daily_schedule WHERE date = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, date);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new DailySong(
                        rs.getString("date"),
                        rs.getString("song_name"),
                        rs.getString("author"),
                        rs.getString("audio_path")
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null; // 当天没有排期
    }

    // 修复版：支持更新 ID (防止重跑程序时无法修正错误的 ID)
    public void logDailyPush(String date, long groupId, int msgId, String songName) {
        // 1. 先尝试更新 (强制把今天的 ID 覆盖成最新的 msgId)
        String updateSql = "UPDATE daily_stats SET message_id = ?, song_name = ? WHERE date = ? AND group_id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(updateSql)) {
            pstmt.setInt(1, msgId);
            pstmt.setString(2, songName);
            pstmt.setString(3, date);
            pstmt.setLong(4, groupId);
            int affectedRows = pstmt.executeUpdate();

            // 2. 如果更新失败 (说明是今天第一次发)，则执行插入
            if (affectedRows == 0) {
                String insertSql = "INSERT INTO daily_stats (date, group_id, message_id, song_name, reaction_count) VALUES (?, ?, ?, ?, 0)";
                try (PreparedStatement insertPstmt = connection.prepareStatement(insertSql)) {
                    insertPstmt.setString(1, date);
                    insertPstmt.setLong(2, groupId);
                    insertPstmt.setInt(3, msgId);
                    insertPstmt.setString(4, songName);
                    insertPstmt.executeUpdate();
                }
            }
            System.out.println("✅ 每日推送记录已同步: " + date + " (MsgID: " + msgId + ")");
        } catch (SQLException e) {
            System.err.println("❌ 记录每日推送失败: " + e.getMessage());
        }
    }
// ============================================================
    // 👇 新增逻辑：存取“下一首预定歌曲”的 ID
    // ============================================================

    /**
     * 获取预定的下一首歌曲 ID
     * @return 歌曲ID，如果没有预定则返回 -1
     */
    public int getNextSongId() {
        String sql = "SELECT value FROM system_vars WHERE key = 'next_song_id'";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Integer.parseInt(rs.getString("value"));
            }
        } catch (Exception e) {
            // 没找到或者出错，都算没有预定
        }
        return -1;
    }

    /**
     * 锁定下一首歌曲 ID
     */
    public void setNextSongId(int id) {
        String sql = "INSERT OR REPLACE INTO system_vars (key, value) VALUES ('next_song_id', ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, String.valueOf(id));
            pstmt.executeUpdate();
            System.out.println("🔒 连锁逻辑：明日歌曲已锁定为 ID: " + id);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    // 【修改】返回值改为 boolean：true=更新成功(是每日消息)，false=没找到(普通消息)
    public boolean incrementReactionCount(int msgId, long groupId) {
        String sql = "UPDATE daily_stats SET reaction_count = reaction_count + 1 WHERE message_id = ? AND group_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, msgId);
            pstmt.setLong(2, groupId);
            int rows = pstmt.executeUpdate();
            return rows > 0; // 如果影响行数大于0，说明这是每日推荐消息
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
// --- V300: 竞猜游戏 - 数据库支持 ---

    // 1. 初始化游戏相关的表
    public void initGameTables() {
        try (Statement stmt = connection.createStatement()) {
            // A. 用户积分表 (周分、月分、上次竞猜日期防止刷分)
            String sqlScores = "CREATE TABLE IF NOT EXISTS game_scores (" +
                    "user_id INTEGER PRIMARY KEY, " +
                    "nickname TEXT, " +
                    "score_weekly INTEGER DEFAULT 0, " +
                    "score_monthly INTEGER DEFAULT 0, " +
                    "last_guess_date TEXT" +
                    ")";
            stmt.execute(sqlScores);
            try {
                stmt.execute("ALTER TABLE game_scores ADD COLUMN last_guess_date TEXT");
            } catch (SQLException e) {
                // 如果列已存在，这行会报错，直接忽略即可
            }
            // B. 系统变量表 (存储赛季天数，防止重启丢失)
            String sqlVars = "CREATE TABLE IF NOT EXISTS system_vars (" +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT" +
                    ")";
            stmt.execute(sqlVars);

            // 初始化天数 (如果不存在，设为 0)
            stmt.execute("INSERT OR IGNORE INTO system_vars (key, value) VALUES ('season_day', '0')");

            System.out.println("✅ 竞猜游戏数据库表已就绪");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 2. 获取已发布过的歌曲名称列表 (黑名单)
    public List<String> getPushedSongHistory() {
        List<String> list = new ArrayList<>();
        // 查询 daily_schedule 表里所有历史日期的歌名
        String sql = "SELECT song_name FROM daily_schedule WHERE date <= date('now')";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(rs.getString("song_name"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // 3. 记录用户得分 (回答正确)
    public void recordUserScore(long userId, String nickname) {
        String today = java.time.LocalDate.now().toString();
        // 如果存在则更新，不存在则插入 (Week+1, Month+1)
        String sql = "INSERT INTO game_scores (user_id, nickname, score_weekly, score_monthly, last_guess_date) " +
                "VALUES (?, ?, 1, 1, ?) " +
                "ON CONFLICT(user_id) DO UPDATE SET " +
                "score_weekly = score_weekly + 1, " +
                "score_monthly = score_monthly + 1, " +
                "nickname = ?, " +
                "last_guess_date = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, nickname);
            pstmt.setString(3, today);
            pstmt.setString(4, nickname);
            pstmt.setString(5, today);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 4. 检查用户今天是否已经猜过 (防刷)
    public boolean hasUserGuessedToday(long userId) {
        String today = java.time.LocalDate.now().toString();
        String sql = "SELECT last_guess_date FROM game_scores WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String lastDate = rs.getString("last_guess_date");
                return today.equals(lastDate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // 5. 赛季系统：获取当前天数
    public int getSeasonDay() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT value FROM system_vars WHERE key='season_day'")) {
            if (rs.next()) return Integer.parseInt(rs.getString("value"));
        } catch (Exception e) { e.printStackTrace(); }
        return 0;
    }

    // 6. 赛季系统：增加天数
    public void incrementSeasonDay() {
        String sql = "UPDATE system_vars SET value = CAST(value AS INTEGER) + 1 WHERE key='season_day'";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // 7. 赛季系统：重置天数
    public void resetSeasonDay() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE system_vars SET value = '0' WHERE key='season_day'");
        } catch (Exception e) { e.printStackTrace(); }
    }

    // 8. 积分榜查询 (type: "weekly" or "monthly")
    public String getTopPlayers(String type) {
        StringBuilder sb = new StringBuilder();
        String col = "weekly".equals(type) ? "score_weekly" : "score_monthly";
        String title = "weekly".equals(type) ? "本周竞猜大神" : "月度竞猜总榜";

        String sql = "SELECT nickname, " + col + " FROM game_scores WHERE " + col + " > 0 ORDER BY " + col + " DESC LIMIT 50";

        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            sb.append("🏆 ").append(title).append(" 🏆\n");

            int displayRank = 1; // 显示的排名 (1, 1, 3...)
            int rowCount = 1;    // 实际行号 (1, 2, 3...)
            int lastScore = -1;  // 上一个人的分数

            boolean hasData = false;

            while (rs.next()) {
                hasData = true;
                String name = rs.getString("nickname");
                int score = rs.getInt(col);

                // 【核心算法】处理排名并列
                // 如果当前分数 != 上一个人分数，则名次 = 当前行号
                // 如果分数相等，则名次保持不变 (实现 1, 1, 3 的效果)
                if (score != lastScore) {
                    displayRank = rowCount;
                }

                // 【关键判断】截断逻辑
                // 只有当名次超过 10 时才停止
                // 妙处：如果有 3 个第 10 名，他们的 displayRank 都是 10，不会触发 break，都会被显示出来
                // 直到出现第 13 名 (displayRank=13)，才会触发 break
                if (displayRank > 10) {
                    break;
                }

                sb.append(displayRank).append(". ").append(name).append(" (").append(score).append("胜)\n");

                lastScore = score;
                rowCount++;
            }

            if (!hasData) sb.append("(暂无上榜数据)");

        } catch (Exception e) { e.printStackTrace(); }
        return sb.toString();
    }

    // 9. 积分重置 (resetWeek 或 resetAll)
    public void resetScores(boolean resetMonthlyAlso) {
        String sql = "UPDATE game_scores SET score_weekly = 0";
        if (resetMonthlyAlso) {
            sql += ", score_monthly = 0";
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
            System.out.println("✅ 积分已重置 (含月榜: " + resetMonthlyAlso + ")");
        } catch (Exception e) { e.printStackTrace(); }
    }
    // 仅记录参与，不加分 (用于答错的情况)
    public void markUserParticipation(long userId, String nickname) {
        String today = java.time.LocalDate.now().toString();
        // 尝试插入，如果冲突则只更新日期
        String sql = "INSERT INTO game_scores (user_id, nickname, score_weekly, score_monthly, last_guess_date) " +
                "VALUES (?, ?, 0, 0, ?) " +
                "ON CONFLICT(user_id) DO UPDATE SET last_guess_date = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, nickname);
            pstmt.setString(3, today);
            pstmt.setString(4, today);
            pstmt.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }
    // --- 导出统计数据 ---
    public String exportDailyStats() {
        // 汇总每一天的总互动数 (跨群合计)
        String filename = "daily_reaction_stats.csv";
        StringBuilder sb = new StringBuilder();
        sb.append("日期,歌曲名,全群表情互动总数\n"); // CSV 表头

        String sql = "SELECT date, song_name, SUM(reaction_count) as total FROM daily_stats GROUP BY date ORDER BY date DESC";

        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                sb.append(rs.getString("date")).append(",")
                        .append(rs.getString("song_name")).append(",")
                        .append(rs.getInt("total")).append("\n");
            }

            // 写文件
            try (FileOutputStream fos = new FileOutputStream(filename);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write('\ufeff'); // BOM 头，防乱码
                osw.write(sb.toString());
            }
            return filename;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    // 在 DatabaseService.java 中添加：
    public void setSeasonDay(int day) {
        String sql = "UPDATE system_vars SET value = '" + day + "' WHERE key='season_day'";
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (Exception e) { e.printStackTrace(); }
    }
    // --- V303: 获取未来排期的所有歌曲 (用于竞猜干扰项，来自小表) ---
    public List<String> getFutureDailySongs(String todayDate) {
        List<String> list = new ArrayList<>();
        // 逻辑：只查日期 > 今天的 (排除了历史已发布的)
        // 这样取出来的全是：明天要发的 + 未来要发的
        String sql = "SELECT song_name FROM daily_schedule WHERE date > ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, todayDate);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString("song_name");
                if (name != null && !name.trim().isEmpty() && !"null".equalsIgnoreCase(name)) {
                    list.add(name);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
    // ==========================================
    // V4.1 最终精修版：水平时间轴 + 00:00 格式
    // ==========================================
    public String generateTrendImage() {
        long now = System.currentTimeMillis();
        long sevenDaysAgo = now - (7L * 24 * 60 * 60 * 1000);

        // 1. 准备时区
        java.util.TimeZone chinaZone = java.util.TimeZone.getTimeZone("Asia/Shanghai");

        // 容器 (0-24点，每2小时一格)
        int[] bucketsWeekly = new int[12];
        int[] bucketsAllTime = new int[12];

        long maxValAllTime = 0;
        long maxValWeekly = 0;

// 2. 查数据库 (逻辑完全保持不变)
        String sql = "SELECT action_time FROM command_logs";
        // 【修改】直接使用全局 connection
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                long time = rs.getLong("action_time");

                Calendar cal = Calendar.getInstance(chinaZone);
                cal.setTimeInMillis(time);
                int h = cal.get(Calendar.HOUR_OF_DAY);
                int slot = h / 2;

                if (slot >= 0 && slot < 12) {
                    bucketsAllTime[slot]++;
                    if (time >= sevenDaysAgo && time <= now) {
                        bucketsWeekly[slot]++;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }

        // 3. Catmull-Rom 样条插值 + 原始点数据集
        String[] timeLabels = {"00:00","02:00","04:00","06:00","08:00","10:00","12:00","14:00","16:00","18:00","20:00","22:00"};
        org.jfree.data.xy.XYSeries sWeekCurve = new org.jfree.data.xy.XYSeries("近7日曲线");
        org.jfree.data.xy.XYSeries sWeekDots = new org.jfree.data.xy.XYSeries("近7日点");
        org.jfree.data.xy.XYSeries sHistCurve = new org.jfree.data.xy.XYSeries("累计曲线");
        org.jfree.data.xy.XYSeries sHistDots = new org.jfree.data.xy.XYSeries("累计点");
        for(int i=0; i<12; i++) {
            if(bucketsWeekly[i] > maxValWeekly) maxValWeekly = bucketsWeekly[i];
            if(bucketsAllTime[i] > maxValAllTime) maxValAllTime = bucketsAllTime[i];
            sWeekDots.add(i, bucketsWeekly[i]);
            sHistDots.add(i, bucketsAllTime[i]);
        }
        addCatmullRomPoints(sWeekCurve, bucketsWeekly, 12);
        addCatmullRomPoints(sHistCurve, bucketsAllTime, 12);
        org.jfree.data.xy.XYSeriesCollection dsWeek = new org.jfree.data.xy.XYSeriesCollection();
        dsWeek.addSeries(sWeekCurve); dsWeek.addSeries(sWeekDots);
        org.jfree.data.xy.XYSeriesCollection dsHist = new org.jfree.data.xy.XYSeriesCollection();
        dsHist.addSeries(sHistCurve); dsHist.addSeries(sHistDots);

        BufferedImage img = null;
        try {
        // 4. 创建图表 A (上图) — XY折线+样条
        org.jfree.chart.JFreeChart chartA = org.jfree.chart.ChartFactory.createXYLineChart(
                "近7日查询次数分布", "", "查询量", dsWeek,
                org.jfree.chart.plot.PlotOrientation.VERTICAL, false, true, false);
        styleXYChart(chartA, maxValWeekly, timeLabels, new Color(59,130,246), new Color(37,99,235));

        // 5. 创建图表 B (下图) — XY折线+样条
        org.jfree.chart.JFreeChart chartB = org.jfree.chart.ChartFactory.createXYLineChart(
                "历史查询总量", "", "总查询量", dsHist,
                org.jfree.chart.plot.PlotOrientation.VERTICAL, false, true, false);
        styleXYChart(chartB, maxValAllTime, timeLabels, new Color(239,68,68), new Color(220,38,38));

        // 6. 拼图：设计感卡片式布局
        int w = 960, topH = 420, botH = 420, pad = 16, gap = 12;
        int totalH = pad*3 + topH + gap + botH;
        img = new BufferedImage(w, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // 整体渐变背景
        GradientPaint bgGrad = new GradientPaint(0, 0, new Color(240,245,252), 0, totalH, new Color(225,235,248));
        g2.setPaint(bgGrad);
        g2.fillRect(0, 0, w, totalH);
        // 卡片阴影
        drawCard(g2, pad, pad, w-pad*2, topH);
        chartA.draw(g2, new java.awt.Rectangle(pad, pad, w-pad*2, topH));
        drawCard(g2, pad, pad*2+topH+gap, w-pad*2, botH);
        chartB.draw(g2, new java.awt.Rectangle(pad, pad*2+topH+gap, w-pad*2, botH));
        g2.dispose();
        } catch(Exception ex) { ex.printStackTrace(); return null; }

        // 7. 保存
        try {
            String path = "data/trend_" + System.currentTimeMillis() + ".png";
            File file = new File(path);
            if(!file.getParentFile().exists()) file.getParentFile().mkdirs();
            ImageIO.write(img, "png", file);
            return file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    // ---------------------------------------------------------
    // 【补丁】检查今日是否已推送 (配合定时任务防重)
    // ---------------------------------------------------------
    public boolean isPushedToday(String date) {
        // 查询 daily_stats 表，看看今天有没有记录
        String sql = "SELECT 1 FROM daily_stats WHERE date = ?";

        // 使用全局连接 (connection)
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, date);
            try (ResultSet rs = pstmt.executeQuery()) {
                // 如果能查到哪怕一行数据 (rs.next() 为 true)，说明今天已经发过了
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("❌ 检查每日打卡状态失败: " + e.getMessage());
            // 如果报错了，为了安全起见，我们假装没发过 (返回 false) 让它重试，
            // 或者返回 true 防止乱发？通常返回 false 让它有机会补发，但可能导致刷屏。
            // 这里我们保守一点，如果报错，先当做没发过。
            return false;
        }
    }
    // ---------------------------------------------------------
    // 【补丁】获取所有歌曲的 ID 列表 (用于随机抽取)
    // ---------------------------------------------------------
    public List<Integer> getAllSongIds() {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT id FROM songs";

        // 使用全局连接
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
        } catch (SQLException e) {
            System.err.println("❌ 获取歌曲ID列表失败: " + e.getMessage());
        }
        return ids;
    }
    // =========================================================
    // 👇 针对 daily_songs 表的专用方法 (V300 修正版)
    // =========================================================

    // 1. 获取所有可用歌曲的 ID 列表 (用于随机池)
    public List<Integer> getAllDailySongIds() {
        List<Integer> ids = new ArrayList<>();
        // 直接查 daily_songs 表的 id 列
        String sql = "SELECT id FROM daily_songs";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
        } catch (SQLException e) {
            System.err.println("❌ 获取 daily_songs ID 列表失败: " + e.getMessage());
        }
        return ids;
    }

    // 2. 根据 ID 获取歌曲详情 (修正版：直接用 ID 拼路径)
    public DailySong getDailySongInfo(int id) {
        // 既然那一列就叫 id，那我们只查 id, song_name, author 就够了
        String sql = "SELECT id, song_name, author FROM daily_songs WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                // ✅ 关键修改：不再查不存在的 "audio_path" 列
                // 直接根据 ID 拼接出文件路径： dailysongs/1.mp3
                String generatedPath = "C:\\Users\\12269\\Desktop\\DailySongs\\" + rs.getInt("id") + ".mp3";

                return new DailySong(
                        String.valueOf(rs.getInt("id")),
                        rs.getString("song_name"),
                        rs.getString("author"),
                        generatedPath // 把拼好的路径传回去
                );
            }
        } catch (SQLException e) {
            System.err.println("❌ 查询 daily_songs 失败: " + e.getMessage());
        }
        return null; // 查不到
    }
    // 【新增】管理员紧急指令：重置今日所有人的竞猜状态
    public int resetTodayGuessStatus() {
        String today = java.time.LocalDate.now().toString();
        // 逻辑：把所有“上次竞猜日期”是“今天”的人，日期改为 NULL
        String sql = "UPDATE game_scores SET last_guess_date = NULL WHERE last_guess_date = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, today);
            return pstmt.executeUpdate(); // 返回受影响的行数（即重置了多少人）
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }
    // 【新增】检查今日是否已经执行过推送 (防止轮询重复触发)
    public boolean hasPushedToday(String date) {
        // 只要 daily_stats 表里有今天的记录，就说明发过了
        String sql = "SELECT 1 FROM daily_stats WHERE date = ? LIMIT 1";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, date);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    // 【新增辅助方法】应用中文字体主题 (防止乱码)
    // Catmull-Rom 样条插值：曲线通过所有原始点，极值只在原始点处
    private void addCatmullRomPoints(org.jfree.data.xy.XYSeries series, int[] data, int n) {
        double[][] pts = new double[n][2];
        for(int i=0; i<n; i++) { pts[i][0]=i; pts[i][1]=data[i]; }
        // 每个区间插入 8 个中间点
        int sub = 8;
        for(int seg=0; seg < n-1; seg++) {
            double x0 = (seg>0) ? pts[seg-1][0] : pts[seg][0]*2 - pts[seg+1][0];
            double y0 = (seg>0) ? pts[seg-1][1] : pts[seg][1]*2 - pts[seg+1][1];
            double x1=pts[seg][0], y1=pts[seg][1];
            double x2=pts[seg+1][0], y2=pts[seg+1][1];
            double x3 = (seg<n-2) ? pts[seg+2][0] : pts[seg+1][0]*2 - pts[seg][0];
            double y3 = (seg<n-2) ? pts[seg+2][1] : pts[seg+1][1]*2 - pts[seg][1];
            for(int s=0; s<=sub; s++) {
                double t = (double)s/sub;
                double t2=t*t, t3=t2*t;
                double cx = 0.5 * ((-t3+2*t2-t)*x0 + (3*t3-5*t2+2)*x1 + (-3*t3+4*t2+t)*x2 + (t3-t2)*x3);
                double cy = 0.5 * ((-t3+2*t2-t)*y0 + (3*t3-5*t2+2)*y1 + (-3*t3+4*t2+t)*y2 + (t3-t2)*y3);
                series.add(cx, Math.max(0, cy));
            }
        }
    }

    private void styleXYChart(JFreeChart chart, long maxVal, String[] labels, Color lineColor, Color dotColor) {
        chart.setBackgroundPaint(new Color(255,255,255,0));
        chart.getTitle().setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        chart.getTitle().setPaint(new Color(30,41,59));
        if(chart.getLegend() != null) chart.getLegend().setItemFont(new Font("Microsoft YaHei", Font.PLAIN, 13));

        org.jfree.chart.plot.XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(255,255,255,0));
        plot.setDomainGridlinePaint(new Color(226,232,240));
        plot.setRangeGridlinePaint(new Color(226,232,240));
        plot.setOutlineVisible(false);

        // 系列0=曲线无线条，系列1=圆点无连线
        org.jfree.chart.renderer.xy.XYLineAndShapeRenderer r = new org.jfree.chart.renderer.xy.XYLineAndShapeRenderer();
        // Series 0 — 插值曲线，无线条(×)
        r.setSeriesStroke(0, new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        r.setSeriesPaint(0, lineColor);
        r.setSeriesShapesVisible(0, false);
        // Series 1 — 原数据圆点，无线条
        r.setSeriesLinesVisible(1, false);
        r.setSeriesShapesVisible(1, true);
        r.setSeriesShape(1, new java.awt.geom.Ellipse2D.Double(-4,-4,8,8));
        r.setSeriesShapesFilled(1, true);
        r.setSeriesFillPaint(1, Color.WHITE);
        r.setSeriesOutlinePaint(1, lineColor);
        r.setSeriesOutlineStroke(1, new BasicStroke(2f));
        r.setUseFillPaint(true);
        r.setUseOutlinePaint(true);
        r.setDrawOutlines(true);
        plot.setRenderer(r);
        plot.setDomainGridlinePaint(new Color(226,232,240,100));
        plot.setRangeGridlinePaint(new Color(226,232,240,100));

        // X 轴 — 用 SymbolAxis 显示时间标签
        org.jfree.chart.axis.SymbolAxis xAxis = new org.jfree.chart.axis.SymbolAxis("", labels);
        xAxis.setRange(-0.5, 11.5);
        xAxis.setTickUnit(new org.jfree.chart.axis.NumberTickUnit(1));
        Font f12 = new Font("Microsoft YaHei", Font.PLAIN, 12);
        xAxis.setTickLabelFont(f12);
        xAxis.setLabelFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        xAxis.setVerticalTickLabels(false);
        plot.setDomainAxis(xAxis);

        // Y 轴
        org.jfree.chart.axis.NumberAxis yAxis = (org.jfree.chart.axis.NumberAxis) plot.getRangeAxis();
        yAxis.setStandardTickUnits(org.jfree.chart.axis.NumberAxis.createIntegerTickUnits());
        yAxis.setAutoRangeIncludesZero(true);
        yAxis.setAutoRangeMinimumSize(5.0);
        yAxis.setLowerBound(0);
        yAxis.setTickLabelFont(f12);
        yAxis.setLabelFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        if(maxVal == 0) yAxis.setRange(0, 5);
    }

    private void drawCard(Graphics2D g2, int x, int y, int w, int h) {
        // 阴影
        g2.setColor(new Color(0,0,0,12));
        g2.fillRoundRect(x+2, y+3, w, h, 14, 14);
        // 卡片背景
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(x, y, w, h, 14, 14);
        // 细边框
        g2.setColor(new Color(226,232,240));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, 14, 14);
    }
    // =========================================================
    // 👇 修复版：全字段智能导入基础曲库 (songs.csv) - 兼容数字列名
    // =========================================================
    public void importCsv(String csvPath) {
        System.out.println("📥 开始自动导入/更新基础曲库 (songs.csv)...");
        final String[] headers;
        final List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            if (headerLine == null) throw new IOException("CSV 文件为空");

            headers = headerLine.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
            int idColumn = -1;
            int nameColumn = -1;
            for (int i = 0; i < headers.length; i++) {
                headers[i] = headers[i].trim().replace("\"", "").replace("\uFEFF", "");
                if (!headers[i].matches("[A-Za-z0-9_]+")) {
                    throw new IOException("无效字段名: " + headers[i]);
                }
                if (headers[i].equalsIgnoreCase("id")) idColumn = i;
                if (headers[i].equalsIgnoreCase("song_name")) nameColumn = i;
            }
            if (idColumn < 0 || nameColumn < 0) throw new IOException("缺少 id 或 song_name 字段");

            String line;
            while ((line = br.readLine()) != null) {
                String[] data = parseCsvRow(line, headers.length);
                // Empty song_name rows are intentional reserved IDs in the current
                // library; only a missing ID makes a CSV record unusable.
                if (data != null && !data[idColumn].isEmpty()) rows.add(data);
            }
        } catch (Exception error) {
            System.err.println("❌ 基础曲库导入已取消，保留现有数据: " + error.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            System.err.println("❌ songs.csv 没有可用歌曲，保留现有数据。");
            return;
        }

        StringBuilder sqlBuilder = new StringBuilder("INSERT INTO songs (");
        StringBuilder placeholders = new StringBuilder("VALUES (");
        for (int i = 0; i < headers.length; i++) {
            sqlBuilder.append("`").append(headers[i]).append("`");
            placeholders.append("?");
            if (i < headers.length - 1) {
                sqlBuilder.append(", ");
                placeholders.append(", ");
            }
        }
        sqlBuilder.append(") ").append(placeholders).append(")");

        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement clearStmt = connection.createStatement()) {
                clearStmt.execute("DELETE FROM songs");
            }
            try (PreparedStatement pstmt = connection.prepareStatement(sqlBuilder.toString())) {
                for (String[] data : rows) {
                    for (int i = 0; i < headers.length; i++) pstmt.setString(i + 1, data[i]);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
            connection.commit();
            System.out.println("✅ 基础曲库更新完成，共 " + rows.size() + " 首；所有字段已同步。");
        } catch (Exception error) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            System.err.println("❌ 基础曲库导入失败，已回滚并保留现有数据: " + error.getMessage());
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) {}
        }
    }

    private static String[] parseCsvRow(String line, int columns) {
        String[] source = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        if (source.length < columns) source = java.util.Arrays.copyOf(source, columns);
        String[] result = new String[columns];
        for (int i = 0; i < columns; i++) {
            String value = source[i] == null ? "" : source[i].trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
            }
            result[i] = value;
        }
        return result;
    }
    // 【新增】严格仅匹配歌名或别名 (用于修复 !数字 搜索越界到专辑的问题)
    public List<Song> searchByExactSongNameOnly(String keyword) {
        List<Song> results = new ArrayList<>();
        // 严格去掉了 OR album = ?
        String sql = "SELECT * FROM songs WHERE song_name = ? OR song_nickname = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, keyword);
            pstmt.setString(2, keyword);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) { results.add(mapRowToSong(rs)); }
        } catch (SQLException e) {
            System.err.println("歌名精准搜索出错: " + e.getMessage());
        }
        return results;
    }

    // =========================================================
    // 曲库网页点赞：按 (ip, 歌曲id, 日期) 去重，同一IP每天每曲一次，跨天可累加
    // =========================================================
    public void createLikesTable() {
        String sql = "CREATE TABLE IF NOT EXISTS song_likes (" +
                "ip TEXT, " +
                "song_id INTEGER, " +
                "day TEXT, " +
                "PRIMARY KEY (ip, song_id, day))";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            System.out.println("✅ 点赞表检查完成 (song_likes)");
        } catch (SQLException e) {
            System.err.println("❌ 创建点赞表失败: " + e.getMessage());
        }
    }

    // 点赞：新插入返回 true；今日已赞(已存在)返回 false
    public synchronized boolean addLike(String ip, int songId, String day) {
        String sql = "INSERT OR IGNORE INTO song_likes (ip, song_id, day) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            pstmt.setInt(2, songId);
            pstmt.setString(3, day);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("点赞失败: " + e.getMessage());
            return false;
        }
    }

    // 取消今日点赞
    public synchronized boolean removeLike(String ip, int songId, String day) {
        String sql = "DELETE FROM song_likes WHERE ip = ? AND song_id = ? AND day = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            pstmt.setInt(2, songId);
            pstmt.setString(3, day);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("取消点赞失败: " + e.getMessage());
            return false;
        }
    }

    // 单曲总点赞数（跨天累加）
    public int getLikeCount(int songId) {
        String sql = "SELECT COUNT(*) FROM song_likes WHERE song_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, songId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("查询点赞数失败: " + e.getMessage());
        }
        return 0;
    }

    // 所有歌曲的点赞总数 {songId: count}
    public Map<Integer, Integer> getAllLikeCounts() {
        Map<Integer, Integer> map = new java.util.HashMap<>();
        String sql = "SELECT song_id, COUNT(*) AS c FROM song_likes GROUP BY song_id";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getInt("song_id"), rs.getInt("c"));
            }
        } catch (SQLException e) {
            System.err.println("查询全部点赞数失败: " + e.getMessage());
        }
        return map;
    }

    // 某 IP 今日已赞的歌曲 id 列表
    public List<Integer> getLikedTodayByIp(String ip, String day) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT song_id FROM song_likes WHERE ip = ? AND day = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            pstmt.setString(2, day);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) ids.add(rs.getInt("song_id"));
        } catch (SQLException e) {
            System.err.println("查询今日点赞失败: " + e.getMessage());
        }
        return ids;
    }
}
