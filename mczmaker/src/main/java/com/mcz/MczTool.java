package com.mcz;
import javax.swing.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import java.awt.datatransfer.StringSelection;
import javax.swing.border.TitledBorder;
import java.awt.event.*;
import javax.sound.sampled.*;
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class MczTool extends JFrame {
    // ================= [UI 组件与数据源] =================
    ExecutorService taskQueue = Executors.newSingleThreadExecutor(); // 任务排队执行器

    // 【新增：防卡死心跳包记录】
    volatile long lastUIHeartbeat = System.currentTimeMillis();

    // ================= [设计页核心变量定义] =================
    CardLayout cardLayout = new CardLayout();
    JPanel mainContainer;
    JPanel designPanel;
    DesignCanvas designCanvas;

    // 存储设计图片数据
    BufferedImage designBgImage = null;
    BufferedImage[] slotImages = new BufferedImage[5];
    BufferedImage designAlbumImage = null;
    int blurLevel = 0;
    JSlider blurSlider; // 【新增】用于在导入工程时同步更新 UI 滑块
    // 【新增】专辑底图自定义双色调色盘 (支持 Hex 与鼠标选色)
    Color topBaseColor = new Color(185, 17, 132);    // 预设：顶部 #B91184
    Color bottomBaseColor = new Color(236, 205, 217); // 预设：底部 #ECCDD9
    boolean isTintingEnabled = true; // 【新增】染色开关，默认开启
    // 【新增】大标题文字内容与字号控制变量
    String albumTitleText = "BLOOM Vol.1";
    int albumTitleSize = 141;
    boolean albumTitleBold = true; // 🎯【新增】大标题是否加粗的开关
    // 【新增】用于 100% 绝对锁定“演示斜黑体”的物理字体变量
    Font customTitleFont = null;
    // 【新增】用于谱面信息胶囊的方正粗圆字体
    Font fzCyFont = null;
    // 【新增】日历专用的仓耳玄三M字体
    Font cangErFont = null;
    // 【新增】日历排版专属 OppoSans 字体
    Font oppoSansFont = null;

    // 预设坐标插槽 (x, y, w, h)
    final Rectangle[] songSlots = new Rectangle[5];
    Rectangle albumSlot = new Rectangle(1450, 150, 400, 400); // 示例坐标
    // ================= [页面切换控制] =================
    Map<File, ImageIcon> imageCache = new ConcurrentHashMap<>(); // 图片内存缓存
    Map<File, String> ratioCache = new ConcurrentHashMap<>(); // 【新增】真实分辨率文本缓存
    boolean isFadedPlaying = false;
    double autoPreviewStart = 0;
    JPanel resultPanel;
    JPanel rightContainer;

    // ================= [音频剪辑专用变量] =================
    float[] waveformDB = null; // 波形 dB 值数组
    int savedImageScrollX = 0;
    final Map<File, JLabel> imageLabelMap = new HashMap<>();

    double audioStartTime = 0;
    double audioEndTime = 0;
    double totalAudioSeconds = 0;
    javax.sound.sampled.Clip playbackClip = null;
    javax.swing.Timer playbackTimer = null;

    JTextField idField, albumNameField, albumIdField, yearField, monthField, dayField;

    Map<String, String> currentInfo = null;
    List<Map<String, String>> currentCharts = null;
    String currentDuration = "00:00";
    String currentFileName = "";
    List<File> currentImageFiles = new ArrayList<>(); // 改名为 currentImageFiles
    List<File> currentAudios = new ArrayList<>();
    File selectedImage = null;
    File selectedAudio = null;
    File activeTempDir = null;
    String activeBaseName = "";
    File customImageFile = null;
    static String ffmpegCommand = "ffmpeg";
    int currentMaxId = 0;
    String currentLastAlbumId = "";
    JLabel idHintLabel;
    JLabel albumIdHintLabel;
    XSSFWorkbook currentWorkbook;

    public static void main(String[] args) {
        detectFFmpeg();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

            Font modernFont = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
            Enumeration<Object> keys = UIManager.getDefaults().keys();
            while (keys.hasMoreElements()) {
                Object key = keys.nextElement();
                Object value = UIManager.get(key);
                if (value instanceof javax.swing.plaf.FontUIResource) {
                    UIManager.put(key, new javax.swing.plaf.FontUIResource(modernFont));
                }
            }
        } catch (Exception e) {
            System.err.println("警告：UIManager 外观/字体设置失败: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> new MczTool().setVisible(true));
    }

    private static void detectFFmpeg() {
        File localFFmpeg = new File("ffmpeg.exe");
        if (localFFmpeg.exists()) {
            ffmpegCommand = localFFmpeg.getAbsolutePath();
        }
    }

    public MczTool() {
        super("Bot工作站");

// 【核心插槽定义】请按 PSD 里的像素坐标填入：x, y, 宽度, 高度
        songSlots[0] = new Rectangle(100, 500, 250, 250); // 第 1 首歌
        songSlots[1] = new Rectangle(400, 500, 250, 250); // 第 2 首歌
        songSlots[2] = new Rectangle(700, 500, 250, 250); // 第 3 首歌
        songSlots[3] = new Rectangle(1000, 500, 250, 250); // 第 4 首歌
        songSlots[4] = new Rectangle(1300, 500, 250, 250); // 第 5 首歌
        albumSlot = new Rectangle(1580, 100, 300, 300);   // 右侧专辑封面
        setTitle("Bot工作站");
        // 【加大窗口】预留出充足的内部边距和滚动条空间，不再拥挤
        setSize(1180, 770);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

// 1. 加载自定义字体 (强制检查，如果加载失败会弹窗报警，不再让它偷偷用微软雅黑)
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

            // 尝试加载演示斜黑体 (修改为二级目录)
            String[] fontFiles = {"素材/5首歌模板/演示斜黑体.otf", "素材/5首歌模板/演示斜黑体.ttf"};
            for (String path : fontFiles) {
                File f = new File(path);
                if (f.exists()) {
                    customTitleFont = Font.createFont(Font.TRUETYPE_FONT, f);
                    break;
                }
            }

            // 如果外部没找到，尝试从 Jar 包内部加载
            if (customTitleFont == null) {
                java.io.InputStream is = MczTool.class.getResourceAsStream("/素材/5首歌模板/演示斜黑体.otf");
                if (is != null) {
                    customTitleFont = Font.createFont(Font.TRUETYPE_FONT, is);
                    is.close();
                }
            }

            if (customTitleFont != null) {
                ge.registerFont(customTitleFont);
            } else {
                JOptionPane.showMessageDialog(null, "错误：无法加载“演示斜黑体.otf”\n请检查“素材/5首歌模板/”文件夹内是否有该文件！", "字体缺失", JOptionPane.ERROR_MESSAGE);
            }

            // 【新增】加载方正粗圆简体
            try {
                File fzFile = new File("素材/5首歌模板/方正粗圆简体.ttf");
                if (fzFile.exists()) {
                    fzCyFont = Font.createFont(Font.TRUETYPE_FONT, fzFile);
                    ge.registerFont(fzCyFont);
                } else {
                    java.io.InputStream is = MczTool.class.getResourceAsStream("/素材/5首歌模板/方正粗圆简体.ttf");
                    if (is != null) {
                        fzCyFont = Font.createFont(Font.TRUETYPE_FONT, is);
                        ge.registerFont(fzCyFont);
                        is.close();
                    }
                }
            } catch (Exception ex) {
                System.out.println("警告：方正粗圆简体加载失败");
            }

            // 【新增】加载仓耳玄三M字体
            try {
                File ceFile = new File("素材/日历/仓耳玄三M.ttf");
                if (ceFile.exists()) {
                    cangErFont = Font.createFont(Font.TRUETYPE_FONT, ceFile);
                    ge.registerFont(cangErFont);
                } else {
                    java.io.InputStream is = MczTool.class.getResourceAsStream("/素材/日历/仓耳玄三M.ttf");
                    if (is != null) {
                        cangErFont = Font.createFont(Font.TRUETYPE_FONT, is);
                        ge.registerFont(cangErFont);
                        is.close();
                    }
                }
            } catch (Exception ex) {
                System.out.println("警告：仓耳玄三M字体加载失败");
            }
// 【新增】加载 OppoSans 字体
            try {
                File opFile = new File("素材/日历/opposans.ttf");
                if (opFile.exists()) {
                    oppoSansFont = Font.createFont(Font.TRUETYPE_FONT, opFile);
                    ge.registerFont(oppoSansFont);
                } else {
                    java.io.InputStream is = MczTool.class.getResourceAsStream("/素材/日历/opposans.ttf");
                    if (is != null) {
                        oppoSansFont = Font.createFont(Font.TRUETYPE_FONT, is);
                        ge.registerFont(oppoSansFont);
                        is.close();
                    }
                }
            } catch (Exception ex) {
                System.out.println("警告：OppoSans字体加载失败");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }





        // 【终极修复】将图标作为内置资源加载，打包进 jar 后即使外部没有图片也能显示
        try {
            // 使用 getResource 从类路径（也就是 jar 包内部）读取图标资源
            java.net.URL iconUrl = MczTool.class.getResource("/icon.png");
            if (iconUrl != null) {
                BufferedImage originalIcon = ImageIO.read(iconUrl);
                if (originalIcon != null) {
                    List<Image> icons = new ArrayList<>();
                    int[] sizes = {16, 24, 32, 48, 64, 128};
                    for (int size : sizes) {
                        icons.add(originalIcon.getScaledInstance(size, size, Image.SCALE_SMOOTH));
                    }
                    setIconImages(icons);
                }
            } else {
                System.out.println("警告：程序内部未找到 /icon.png 资源");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ================= [左侧：自定义数据录入区] =================
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        // 【边框圆角化】使用自定义圆角边框，色调与背景色呼应
        TitledBorder leftB = BorderFactory.createTitledBorder(new ModernRoundedBorder(new Color(210, 215, 225), 1, 12), "歌曲数据填写区");
        leftB.setTitleFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        leftPanel.setBorder(leftB);
        // 【新增美化】左侧表单区赋予极浅的蓝灰底色，增加专业感
        leftPanel.setBackground(new Color(246, 248, 251));
        leftPanel.setPreferredSize(new Dimension(280, 0));
        leftPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(10, 10, 10, 10),
                leftPanel.getBorder()));

        idField = new JTextField();
        albumNameField = new JTextField();
        albumIdField = new JTextField();
        yearField = new JTextField();
        monthField = new JTextField();
        dayField = new JTextField();

        yearField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        monthField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        dayField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));


        // ID 行（含最新ID实时提示）
        {
            idField.setPreferredSize(new Dimension(0, 35));
            idField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            idField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(200, 200, 200)),
                    BorderFactory.createEmptyBorder(0, 8, 0, 8)));

            JPanel idRow = new JPanel(new BorderLayout(0, 8));
            idRow.setOpaque(false);
            JLabel idLabel = new JLabel("1. 歌曲 ID:");
            idLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
            idLabel.setForeground(new Color(60, 60, 60));
            idRow.add(idLabel, BorderLayout.NORTH);

            JPanel idInputPanel = new JPanel(new BorderLayout(8, 0));
            idInputPanel.setOpaque(false);
            idInputPanel.add(idField, BorderLayout.CENTER);

            idHintLabel = new JLabel(" ");
            idHintLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            idHintLabel.setForeground(new Color(80, 150, 80));
            idInputPanel.add(idHintLabel, BorderLayout.EAST);

            idRow.add(idInputPanel, BorderLayout.CENTER);
            idRow.setBorder(BorderFactory.createEmptyBorder(0, 5, 18, 5));
            idRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));
            leftPanel.add(idRow);
        }
        addInputRow(leftPanel, "2. 专辑名称:", albumNameField);
        // 专辑编号行（提示 + ±1 在右侧，与 1/2 框等高）
        {
            albumIdField.setPreferredSize(new Dimension(0, 35));
            albumIdField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            albumIdField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(200, 200, 200)),
                    BorderFactory.createEmptyBorder(0, 8, 0, 8)));

            JPanel aidRow = new JPanel(new BorderLayout(0, 8));
            aidRow.setOpaque(false);
            JLabel aidLabel = new JLabel("3. 专辑编号:");
            aidLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
            aidLabel.setForeground(new Color(60, 60, 60));
            aidRow.add(aidLabel, BorderLayout.NORTH);

            JPanel aidInputPanel = new JPanel(new BorderLayout(4, 0));
            aidInputPanel.setOpaque(false);
            aidInputPanel.add(albumIdField, BorderLayout.CENTER);

            JPanel aidRightPanel = new JPanel();
            aidRightPanel.setLayout(new BoxLayout(aidRightPanel, BoxLayout.X_AXIS));
            aidRightPanel.setOpaque(false);

            albumIdHintLabel = new JLabel(" ");
            albumIdHintLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            albumIdHintLabel.setForeground(new Color(80, 150, 80));
            albumIdHintLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
            aidRightPanel.add(albumIdHintLabel);

            aidRightPanel.add(Box.createHorizontalStrut(3));

            ModernButton minusOneBtn = new ModernButton("-1");
            minusOneBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 10));
            minusOneBtn.setCustomColors(new Color(255, 225, 225), new Color(245, 195, 195), new Color(235, 170, 170));
            minusOneBtn.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            minusOneBtn.setPreferredSize(new Dimension(40, 20));
            minusOneBtn.setMaximumSize(new Dimension(40, 20));
            minusOneBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
            minusOneBtn.addActionListener(e -> {
                String cur = albumIdField.getText().trim();
                if (!cur.isEmpty()) {
                    try {
                        int val = Integer.parseInt(cur);
                        if (val > 1) albumIdField.setText(String.valueOf(val - 1));
                    } catch (NumberFormatException ignored) {}
                }
            });
            aidRightPanel.add(minusOneBtn);
            aidRightPanel.add(Box.createHorizontalStrut(3));

            ModernButton plusOneBtn = new ModernButton("+1");
            plusOneBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 10));
            plusOneBtn.setCustomColors(new Color(220, 245, 220), new Color(180, 225, 180), new Color(150, 210, 150));
            plusOneBtn.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            plusOneBtn.setPreferredSize(new Dimension(40, 20));
            plusOneBtn.setMaximumSize(new Dimension(40, 20));
            plusOneBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
            plusOneBtn.addActionListener(e -> {
                String cur = albumIdField.getText().trim();
                if (!cur.isEmpty()) {
                    try {
                        int val = Integer.parseInt(cur);
                        albumIdField.setText(String.valueOf(val + 1));
                    } catch (NumberFormatException ignored) {}
                }
            });
            aidRightPanel.add(plusOneBtn);

            aidInputPanel.add(aidRightPanel, BorderLayout.EAST);
            aidRow.add(aidInputPanel, BorderLayout.CENTER);
            aidRow.setBorder(BorderFactory.createEmptyBorder(0, 5, 18, 5));
            aidRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));
            leftPanel.add(aidRow);
        }

        // 日期输入组
        JPanel datePanel = new JPanel(new GridLayout(1, 6, 2, 0));
        datePanel.setOpaque(false); // 【新增美化】变透明以透出背景
        datePanel.setPreferredSize(new Dimension(0, 32));
        datePanel.add(yearField); datePanel.add(new JLabel("年", JLabel.CENTER));
        datePanel.add(monthField); datePanel.add(new JLabel("月", JLabel.CENTER));
        datePanel.add(dayField); datePanel.add(new JLabel("日", JLabel.CENTER));

        JPanel row = new JPanel(new BorderLayout(0, 5));
        row.setOpaque(false); // 【新增美化】变透明以透出背景
        JLabel dateLabel = new JLabel("4. 发布时间:");
        dateLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        row.add(dateLabel, BorderLayout.NORTH);
        row.add(datePanel, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(0, 5, 15, 5));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
        leftPanel.add(row);
// 【重置版主功能入口】明亮清透风、极简无重影 CTA 按钮
        JButton openDesignBtn = new JButton("进行茶韵图片设计") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 35;
                Color bgCol = Color.WHITE;
                if (getModel().isPressed()) bgCol = new Color(230, 230, 230);
                else if (getModel().isRollover()) bgCol = new Color(240, 240, 240);
                g2.setColor(bgCol);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
            @Override
            protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(100, 100, 100));
                int arc = 35;
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.dispose();
            }
        };
        openDesignBtn.setContentAreaFilled(false);
        openDesignBtn.setFocusPainted(false);
        // 使用清晰的亮海军蓝文字，对比度更高，视觉上非常精神
        openDesignBtn.setForeground(Color.BLACK);
        openDesignBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        openDesignBtn.setVerticalTextPosition(SwingConstants.CENTER);
        openDesignBtn.setHorizontalTextPosition(SwingConstants.CENTER);
        openDesignBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        openDesignBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        openDesignBtn.setPreferredSize(new Dimension(220, 42)); // 保持精致小巧
        openDesignBtn.setMaximumSize(new Dimension(220, 42));
        openDesignBtn.addActionListener(e -> {
            // 【修改】点击按钮后，弹出带有“点状单选按钮(Radio Button)”的独立选择窗口
            JPanel panel = new JPanel(new BorderLayout(0, 15));
            JLabel label = new JLabel("请选择您要处理的模板：");
            label.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));

            // 创建三个点状单选按钮
            JRadioButton radio5 = new JRadioButton("5首歌模板");
            JRadioButton radio6 = new JRadioButton("6首歌模板");
            JRadioButton radioCal = new JRadioButton("茶韵日历");
            radio5.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            radio6.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            // 给日历上个特殊的高亮色
            radioCal.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));

            radio5.setSelected(true);

            ButtonGroup group = new ButtonGroup();
            group.add(radio5); group.add(radio6); group.add(radioCal);

            JPanel radioPanel = new JPanel(new GridLayout(3, 1, 0, 8));
            radioPanel.add(radio5); radioPanel.add(radio6); radioPanel.add(radioCal);

            panel.add(label, BorderLayout.NORTH);
            panel.add(radioPanel, BorderLayout.CENTER);

            int res = JOptionPane.showConfirmDialog(MczTool.this, panel, "功能模式选择", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (res == JOptionPane.OK_OPTION) {
                if (radioCal.isSelected()) {
                    // 跳转到日历专属页面
                    cardLayout.show(mainContainer, "CALENDAR");
                } else {
                    if (designCanvas != null) {
                        int mode = radio5.isSelected() ? 5 : 6;
                        designCanvas.switchTemplate(mode);
                    }
                    cardLayout.show(mainContainer, "DESIGN");
                }
            }
        });

        // 两个大按钮紧凑排列，间距由 createVerticalStrut 精确控制
        JPanel bigBtnPanel = new JPanel();
        bigBtnPanel.setLayout(new BoxLayout(bigBtnPanel, BoxLayout.Y_AXIS));
        bigBtnPanel.setOpaque(false);
        openDesignBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        bigBtnPanel.add(openDesignBtn);
        bigBtnPanel.add(Box.createVerticalStrut(10)); // ← 调这个数值改变两按钮间距
        ModernButton dailyBtn = new ModernButton("每日歌曲管理");
        dailyBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        dailyBtn.setCustomColors(Color.WHITE, new Color(240, 240, 240), new Color(230, 230, 230));
        dailyBtn.setForeground(Color.BLACK);
        dailyBtn.setCornerRadius(35);
        dailyBtn.setBorderColor(new Color(100, 100, 100));
        dailyBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        dailyBtn.setPreferredSize(new Dimension(220, 42));
        dailyBtn.setMaximumSize(new Dimension(220, 42));
        dailyBtn.addActionListener(e -> DailySongManager.showManager(this));
        bigBtnPanel.add(dailyBtn);

        JPanel btnOuterWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        btnOuterWrapper.setOpaque(false);
        btnOuterWrapper.setBorder(BorderFactory.createEmptyBorder(15, 0, 0, 0));
        btnOuterWrapper.add(bigBtnPanel);
        leftPanel.add(btnOuterWrapper);

        // 【新增】活动排名管理入口
        {
            ModernButton eventRankBtn = new ModernButton("活动排名管理");
            eventRankBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
            eventRankBtn.setCustomColors(Color.WHITE, new Color(240, 240, 240), new Color(230, 230, 230));
            eventRankBtn.setForeground(Color.BLACK);
            eventRankBtn.setCornerRadius(35);
            eventRankBtn.setBorderColor(new Color(100, 100, 100));
            eventRankBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            eventRankBtn.setPreferredSize(new Dimension(220, 42));
            eventRankBtn.setMaximumSize(new Dimension(220, 42));
            eventRankBtn.addActionListener(e -> EventRankManager.show(this));
            bigBtnPanel.add(Box.createVerticalStrut(10));
            bigBtnPanel.add(eventRankBtn);
        }

        // ================= [主菜单栏] =================
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(new Color(230, 238, 250));
        menuBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(180, 195, 220)));
        menuBar.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JMenu fileMenu = new JMenu("  文件  ");
        fileMenu.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        JMenuItem showExcelItem = new JMenuItem("") {
            { super.setText(""); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                String t = "显示表格文件";
                int x = (getWidth() - fm.stringWidth(t)) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.setColor(getForeground());
                g2.drawString(t, x, y);
                g2.dispose();
            }
        };
        showExcelItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        showExcelItem.addActionListener(e -> ExcelManager.showExcelPreview(this));
        fileMenu.add(showExcelItem);
        // 分割线 — 用禁用的 JMenuItem 画满整个菜单宽度
        fileMenu.add(new JMenuItem() {{
            setEnabled(false);
            setPreferredSize(new Dimension(10, 6));
        }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(new Color(180, 180, 180));
                g.fillRect(0, getHeight() / 2 - 1, getWidth(), 1);
            }
        });

        JMenuItem showDailyItem = new JMenuItem("") {
            { super.setText(""); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                String t = "每日歌曲预览";
                int x = (getWidth() - fm.stringWidth(t)) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.setColor(getForeground());
                g2.drawString(t, x, y);
                g2.dispose();
            }
        };
        showDailyItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        showDailyItem.addActionListener(e -> DailySongManager.showPreview(this));
        fileMenu.add(showDailyItem);
        fileMenu.getPopupMenu().setPreferredSize(new Dimension(120, 60));
        ExcelManager.addPopupFadeIn(this, fileMenu);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        // ================= [右侧：解析结果与确认区] =================
        resultPanel = new JPanel();
        resultPanel.setLayout(new BoxLayout(resultPanel, BoxLayout.Y_AXIS));
        resultPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel wrapperPanel = new JPanel(new BorderLayout());
        wrapperPanel.add(resultPanel, BorderLayout.NORTH);

        rightContainer = new JPanel(new BorderLayout());
        // 【边框圆角化】使用自定义中性灰色圆角边框
        TitledBorder rightB = BorderFactory.createTitledBorder(new ModernRoundedBorder(new Color(200, 200, 200), 1, 12), "谱面解析信息 (全屏任意位置可拖入.mcz 文件)");
        rightB.setTitleFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        rightContainer.setBorder(rightB);

// 【终极解法 1】放开横向强制挤压，允许容器根据组件真实需要分配宽度
        JScrollPane scrollPaneRight = new JScrollPane(wrapperPanel, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPaneRight.getVerticalScrollBar().setUnitIncrement(16);
        rightContainer.add(scrollPaneRight, BorderLayout.CENTER);
        // 【正式挂载】左右两个功能面板现在都初始化完了，现在把它们塞进主工作区
        wrapperPanel.setBackground(Color.WHITE);
        resultPanel.setBackground(Color.WHITE);


        // ================= [全局拖拽支持] =================
        new DropTarget(this, new DropTargetAdapter() {
            @Override
            @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> droppedFiles = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);

                    taskQueue.submit(() -> {
                        // 【改进】在处理一批文件前先清理一次总缓存
                        imageCache.clear();
                        ratioCache.clear(); // 【新增】同步清理比例缓存
                        for (File file : droppedFiles) {
                            if (file.getName().toLowerCase().endsWith(".mcz")) {
                                processMcz(file);
                            }
                        }
                    });
                    dtde.dropComplete(true); // 必须告知系统拖放已完成，否则 OS 会卡住
                } catch (Exception e) { e.printStackTrace(); }
            }
        });
        // ================= [全局快捷键绑定] =================
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        // 1. 空格键：播放/暂停
        im.put(KeyStroke.getKeyStroke("SPACE"), "togglePlay");
        am.put("togglePlay", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                AudioManager.togglePlayPause(MczTool.this);
                updateResultPanel();
            }
        });

        // 2. 方向键右：快进 15 秒
        im.put(KeyStroke.getKeyStroke("RIGHT"), "forward15s");
        am.put("forward15s", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (playbackClip != null && playbackClip.isOpen()) {
                    long currentPos = playbackClip.getMicrosecondPosition();
                    // 15秒 = 15,000,000 微秒
                    long newPos = currentPos + (15L * 1_000_000L);
                    long maxPos = (long)(totalAudioSeconds * 1_000_000L);

                    // 确保不会跳出音频总长度
                    playbackClip.setMicrosecondPosition(Math.min(newPos, maxPos));
                }
            }
        });
// 【强制修复】程序退出时的“绝对清理”逻辑
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // 1. 强制停止所有后台排队任务，防止关闭瞬间还在解压/读取文件
                taskQueue.shutdownNow();

                // 2. 彻底释放音频占用
                if (playbackClip != null) {
                    playbackClip.stop();
                    playbackClip.close();
                    playbackClip = null;
                }

                // 3. 清空缓存并强制请求 JVM 释放所有文件句柄
                imageCache.clear();
                ratioCache.clear();
                System.gc(); // 关键：促使系统立刻回收已关闭文件的句柄

                // 4. 给 Windows 操作系统 300 毫秒的“反应时间”来释放文件夹锁定
                Thread.sleep(300);

                // 5. 执行递归删除
                if (activeTempDir != null && activeTempDir.exists()) {
                    MczParser.deleteDirectory(activeTempDir);
                }
            } catch (Exception e) {
                // 退出时的静默处理，确保不干扰关机进程
            }
        }));
// 1. 初始化唯一的大容器
        mainContainer = new JPanel(cardLayout);
        add(mainContainer);

        // 2. 彻底组装【主站页面】
        JPanel mainWorkPanel = new JPanel(new BorderLayout());
        mainWorkPanel.add(leftPanel, BorderLayout.WEST);
        mainWorkPanel.add(rightContainer, BorderLayout.CENTER);
        mainContainer.add(mainWorkPanel, "MAIN");

// 3. 彻底组装【设计页面】 (补回被你删掉的初始化逻辑)
        designPanel = new JPanel(new BorderLayout());
        designPanel.setBackground(new Color(30, 30, 30)); // 【暗黑模式】最底层的深黑灰背景

// 顶部的返回条
        JPanel designTopBar = new JPanel(new BorderLayout(20, 0));
        designTopBar.setBackground(new Color(45, 45, 45)); // 【暗黑模式】顶栏的深灰色背景
        designTopBar.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        ModernButton backBtn = new ModernButton("返回");
        backBtn.setPreferredSize(new Dimension(80, 32)); // 【小巧胶囊尺寸】
        // 【加深红色反馈】鼠标放上去显示警示红，按下变为较深的砖红
        backBtn.setCustomColors(new Color(245, 245, 245), new Color(255, 150, 150), new Color(220, 90, 90));
        backBtn.addActionListener(e -> cardLayout.show(mainContainer, "MAIN"));

        // 【UI 修复】套上一层防护罩，彻底阻断纵轴拉伸，并保持垂直居中
        JPanel backWrapper = new JPanel(new GridBagLayout());
        backWrapper.setOpaque(false);
        backWrapper.add(backBtn);
        designTopBar.add(backWrapper, BorderLayout.WEST);

// 【全新升级】精简版控制面板（去除了繁杂的封面调色盘，改为点击封面原位弹出）
        JPanel blurControl = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        blurControl.setOpaque(false);
        JLabel blurLbl = new JLabel("背景模糊度:");
        blurLbl.setForeground(Color.WHITE); // 【暗黑模式】文字变白
        blurControl.add(blurLbl);
        blurSlider = new JSlider(0, 100, 0); // 使用全局变量
        blurSlider.setOpaque(false); // 【暗黑模式】确保滑块透明以透出底色
        blurSlider.addChangeListener(e -> {
            blurLevel = blurSlider.getValue();
            if(designCanvas != null) designCanvas.repaint();
        });
        blurControl.add(blurSlider);

        // 直接将高斯模糊滑块放在正中央，大幅释放视觉空间
        designTopBar.add(blurControl, BorderLayout.CENTER);

// 导出图片按钮
        ModernButton exportBtn = new ModernButton("导出成图");
        exportBtn.setPreferredSize(new Dimension(90, 32));
        exportBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        exportBtn.setCustomColors(new Color(235, 250, 235), new Color(210, 245, 210), new Color(180, 230, 180));
        exportBtn.addActionListener(e -> { if (designCanvas != null) designCanvas.exportImage(); });

        // 【新增】保存工程文件按钮
        ModernButton saveProjBtn = new ModernButton("保存工程");
        saveProjBtn.setPreferredSize(new Dimension(90, 32));
        saveProjBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        // 使用代表“存储/档案”的温暖橙黄色系
        saveProjBtn.setCustomColors(new Color(255, 245, 230), new Color(255, 225, 180), new Color(240, 200, 140));
        saveProjBtn.addActionListener(e -> { if (designCanvas != null) designCanvas.saveProject(); });

        // 使用 FlowLayout 将两个按钮并排包装在右侧
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        rightActions.setOpaque(false);
        rightActions.add(saveProjBtn);
        rightActions.add(exportBtn);

        // 套一层 GridBagLayout 实现绝对的垂直居中
        JPanel exportWrapper = new JPanel(new GridBagLayout());
        exportWrapper.setOpaque(false);
        exportWrapper.add(rightActions);
        designTopBar.add(exportWrapper, BorderLayout.EAST);

        designPanel.add(designTopBar, BorderLayout.NORTH);

// 中央预览画布 (允许撑满所有剩余空间)
        JPanel canvasWrapper = new JPanel(new BorderLayout());
        canvasWrapper.setBackground(new Color(25, 25, 25)); // 【暗黑模式】画布外围的极深黑背景
        canvasWrapper.setOpaque(true);
        canvasWrapper.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        designCanvas = new DesignCanvas(this);
        canvasWrapper.add(designCanvas, BorderLayout.CENTER);
        designPanel.add(canvasWrapper, BorderLayout.CENTER);

// 4. 将所有页面注册到大容器
        mainContainer.add(designPanel, "DESIGN");
        mainContainer.add(new CalendarPanel(this), "CALENDAR"); // 【新增：注册日历页面】

        // 5. 启动显示主界面
        cardLayout.show(mainContainer, "MAIN");

        // 【新增】启动独立防卡死”看门狗”
        startAntiFreezeWatchdog();
        // 【新增】公告定时器

        // 【新增】初始化内嵌 Excel 并加载最新 ID
        ExcelManager.initExcel(this);
        ExcelManager.refreshIdHint(this);
        ExcelManager.refreshAlbumIdHint(this);
    }

    // 获取桌面 songs.xlsx 路径（跨用户兼容）

    private void startAntiFreezeWatchdog() {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000); // 狗子每 2 秒醒来巡视一次

                    // 尝试向 UI 线程发送心跳包更新时间戳
                    SwingUtilities.invokeLater(() -> {
                        lastUIHeartbeat = System.currentTimeMillis();
                    });

                    // 【核心判定】如果超过 15 秒 UI 都没有回应，说明彻底卡死了
                    if (System.currentTimeMillis() - lastUIHeartbeat > 15000) {
                        System.err.println("❌ [致命错误] 检测到界面已失去响应超过15秒！");
                        SwingUtilities.invokeLater(() -> {
                            int choice = JOptionPane.showConfirmDialog(MczTool.this,
                                    "检测到界面长时间无响应（>15秒）\n\n可能是正在处理大文件或图片。\n\n是否强制退出程序？\n（未保存的数据将丢失）",
                                    "界面卡死警告", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                            if (choice == JOptionPane.YES_OPTION) {
                                System.exit(1);
                            }
                        });
                        lastUIHeartbeat = System.currentTimeMillis();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        watchdog.setDaemon(true); // 设置为守护线程，不妨碍正常关机
        watchdog.setPriority(Thread.MAX_PRIORITY); // 赋予最高权限，确保即使系统卡顿它也能执行
        watchdog.start();
    }

    private void addInputRow(JPanel parent, String label, JTextField field) {
        field.setPreferredSize(new Dimension(0, 35));
        field.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        // 【设计感】增加文本框内边距，让光标不贴边
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(0, 8, 0, 8)
        ));

        JPanel row = new JPanel(new BorderLayout(0, 8));
        row.setOpaque(false); // 【新增美化】去除白色背景底块，融入蓝灰工作区
        JLabel titleLabel = new JLabel(label);
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        titleLabel.setForeground(new Color(60, 60, 60)); // 深灰色标题
        row.add(titleLabel, BorderLayout.NORTH);
        row.add(field, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(0, 5, 18, 5));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));
        parent.add(row);
    }

    void updateResultPanel() {
        imageLabelMap.clear();
        // 记住封面滚动位置
        savedImageScrollX = 0;
        for (java.awt.Component c : resultPanel.getComponents()) {
            if (c instanceof JScrollPane) {
                savedImageScrollX = ((JScrollPane) c).getHorizontalScrollBar().getValue();
                break;
            }
        }
        resultPanel.removeAll();
        if (currentInfo == null) return;

        // 【混合策略 2】局部文字、音频名、比例文本全部强制指定微软雅黑
        Font yaHeiBold13 = new Font("Microsoft YaHei", Font.BOLD, 13);
        Font yaHeiPlain12 = new Font("Microsoft YaHei", Font.PLAIN, 12);
        Font resFont = new Font("Microsoft YaHei", Font.PLAIN, 11); // 稍微调大一点点分辨率字体
        String songName = currentInfo.get("songName").replaceAll("^【[^】]*】\\s*", "");

// ================= [封面选择区] =================
        if (!currentImageFiles.isEmpty() || customImageFile != null) {
            // 【紧凑化】缩小上下间距为 5，并动态锁死最大高度，彻底消灭垂直方向被拉扯出的大白边
            JPanel imageGallery = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5)) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            imageGallery.setBackground(new Color(255, 252, 242));
            TitledBorder imgB = BorderFactory.createTitledBorder(new ModernRoundedBorder(new Color(235, 220, 200), 1, 12), "封面选择 (红框表示选中，双击裁剪)");
            imgB.setTitleFont(yaHeiPlain12);
            imageGallery.setBorder(imgB);

            List<File> displayImgs = new ArrayList<>(currentImageFiles);
            if (customImageFile != null) displayImgs.add(customImageFile);
            if (selectedImage == null && !displayImgs.isEmpty()) selectedImage = displayImgs.get(0);

            for (File imgFile : displayImgs) {
                // 【核心修复】直接从 imageCache 提取，不再触发磁盘 I/O
                ImageIcon icon = imageCache.get(imgFile);
                if (icon == null) continue;

                JPanel itemPanel = new JPanel();
                itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
                itemPanel.setBackground(Color.WHITE);
                // 【再压缩 20%】宽缩小到 104，高缩小到 120，完美包裹 96x96 的新图片
                itemPanel.setPreferredSize(new Dimension(104, 135));
                itemPanel.setMaximumSize(new Dimension(104, 135));
                itemPanel.setAlignmentY(Component.TOP_ALIGNMENT);

                JLabel picLabel = new JLabel(icon);
                picLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                picLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                picLabel.setBorder(imgFile.equals(selectedImage) ?
                        BorderFactory.createLineBorder(new Color(220, 50, 50), 4) :
                        BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));

                imageLabelMap.put(imgFile, picLabel);
                picLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                    public void mouseClicked(java.awt.event.MouseEvent evt) {
                        if (evt.getClickCount() == 2) { ImageCropper.open(MczTool.this, imgFile); return; }
                        selectedImage = imgFile;
                        // 只更新边框高亮，不重建 UI
                        for (Map.Entry<File, JLabel> en : imageLabelMap.entrySet()) {
                            en.getValue().setBorder(en.getKey().equals(selectedImage) ?
                                    BorderFactory.createLineBorder(new Color(220, 50, 50), 4) :
                                    BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
                        }
                    }
                });

                // 【核心修复】从缓存中获取原图的真实比例，而不是被压缩成 140x140 的缩略图比例
                String ratioStr = ratioCache.getOrDefault(imgFile, "未知比例");
                JLabel resLabel = new JLabel(ratioStr);
                resLabel.setFont(resFont);
                resLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

                itemPanel.add(picLabel);
                itemPanel.add(Box.createVerticalStrut(2));
                itemPanel.add(resLabel);
                imageGallery.add(itemPanel);
            }
            // 【终极修复】使用 HTML 标签单独保护 Emoji，后续中文自然继承微软雅黑
            ModernButton importBtn = new ModernButton("<html><font face='Segoe UI Emoji'>➕</font> 导入外部图片</html>");
            importBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            importBtn.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser();
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    customImageFile = chooser.getSelectedFile();
                    selectedImage = customImageFile;
                    try {
                        BufferedImage bimg = ImageIO.read(customImageFile);
                        if (bimg != null) {
                            imageCache.put(customImageFile, new ImageIcon(bimg.getScaledInstance(108, 108, Image.SCALE_SMOOTH)));
                            ratioCache.put(customImageFile, MczParser.getRatioString(bimg.getWidth(), bimg.getHeight()));
                        }
                    } catch (Exception ex) {
                        System.err.println("警告：无法读取自定义图片: " + ex.getMessage());
                    }
                    updateResultPanel();
                }
            });
            imageGallery.add(importBtn);

            // 强制图库宽度 = 所有图片单行总宽，确保滚动条生效
            int totalW = displayImgs.size() * (104 + 15) + 200;
            imageGallery.setPreferredSize(new Dimension(totalW, imageGallery.getPreferredSize().height));

            JScrollPane imgScroll = new JScrollPane(imageGallery,
                    JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            imgScroll.setBorder(BorderFactory.createEmptyBorder());
            imgScroll.setPreferredSize(new Dimension(0, imageGallery.getPreferredSize().height ));

            resultPanel.add(imgScroll);
            resultPanel.add(Box.createVerticalStrut(10));
            final JScrollPane fs = imgScroll;
            final int sx = savedImageScrollX;
            SwingUtilities.invokeLater(() -> fs.getHorizontalScrollBar().setValue(sx));
        }

        // ================= [音频选择区 + 增强剪辑工具] =================
        if (!currentAudios.isEmpty()) {
            JPanel audioGallery = new JPanel();
            audioGallery.setLayout(new BoxLayout(audioGallery, BoxLayout.Y_AXIS));
            audioGallery.setBackground(new Color(245, 253, 250)); // 【新增美化】赋予清新的浅薄荷绿色调
            // 【边框圆角化】匹配薄荷绿的圆角边框
            TitledBorder audB = BorderFactory.createTitledBorder(new ModernRoundedBorder(new Color(200, 230, 215), 1, 12), "音频预览与剪辑 (红框表示选中)");
            audB.setTitleFont(yaHeiPlain12);
            audioGallery.setBorder(audB);

            // 1. 文件列表（单击切换）
            JPanel listPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
            listPanel.setOpaque(false); // 【新增美化】透明化以透出薄荷绿底色
            if (selectedAudio == null) selectedAudio = currentAudios.get(0);
            for (File audFile : currentAudios) {
                JLabel audLabel = new JLabel(" " + audFile.getName() + " ");
                audLabel.setFont(yaHeiPlain12);
                audLabel.setOpaque(true);
                audLabel.setBackground(Color.WHITE);
                audLabel.setBorder(audFile.equals(selectedAudio) ?
                        BorderFactory.createLineBorder(new Color(220, 50, 50), 3) :
                        BorderFactory.createLineBorder(new Color(230, 230, 230), 1));
                audLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                    public void mouseClicked(java.awt.event.MouseEvent evt) {
                        selectedAudio = audFile;
                        AudioManager.prepareAudioPreview(MczTool.this, audFile);
                        updateResultPanel();
                    }
                });
                listPanel.add(audLabel);
            }
            JScrollPane listScroll = new JScrollPane(listPanel,
                    JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            listScroll.setBorder(BorderFactory.createEmptyBorder());
            listScroll.setPreferredSize(new Dimension(0, listPanel.getPreferredSize().height + 5));
            listScroll.setOpaque(false);
            listScroll.getViewport().setOpaque(false);
            audioGallery.add(listScroll);

            // 2. 带标记的进度条区域
            double startPos = 0;
            if (playbackClip != null && playbackClip.isOpen()) {
                double internalPos = (double)playbackClip.getMicrosecondPosition() / 1_000_000.0;
                startPos = isFadedPlaying ? (audioStartTime + internalPos) : internalPos;
            }

            int sliderInitVal = (totalAudioSeconds > 0) ? (int)(startPos * 1000 / totalAudioSeconds) : 0;
            JSlider progressSlider = new JSlider(0, 1000, sliderInitVal);
            progressSlider.setOpaque(false); // 【新增美化】进度条本体透明

            JPanel sliderContainer = new JPanel() {
                @Override
                public void paint(Graphics g) {
                    super.paint(g);
                    if (totalAudioSeconds > 0) {
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        int w = progressSlider.getWidth() - 20;

                        // 🛡️ 核心修复：如果布局还没完成（宽度没展开），直接跳过绘制，防止闪烁跳动！
                        if (w <= 0) return;

                        int startX = 10 + (int)(audioStartTime * w / totalAudioSeconds);
                        int endX = 10 + (int)(audioEndTime * w / totalAudioSeconds);

                        g2.setColor(new Color(220, 50, 50));
                        g2.fillRect(startX - 2, 0, 4, 8); // 【压缩标记高度】
                        g2.setFont(new Font(Font.DIALOG, Font.BOLD, 10));
                        g2.drawString("入", startX - 5, 17);

                        g2.setColor(new Color(50, 50, 220));
                        g2.fillRect(endX - 2, 0, 4, 8); // 【压缩标记高度】
                        g2.drawString("出", endX - 5, 17);
                    }
                }
            };
            sliderContainer.setLayout(new BorderLayout());
            sliderContainer.setPreferredSize(new Dimension(0, 110));
            sliderContainer.setBackground(new Color(245, 253, 250));

            // 🌟 预判真实格式长度，第一帧就塞入带小数点的占位符
            String initTotalStr = String.format("%02d:%04.1f", (int)totalAudioSeconds / 60, totalAudioSeconds % 60);
            JLabel timeLabel = new JLabel("00:00.0 / " + initTotalStr);

            // 🛡️ 核心终极修复：强行锁死时间标签的物理宽度 (120px绝对够用)！
            // 彻底杜绝文字长短变化导致左侧进度条被来回挤压的“幽灵跳动”
            timeLabel.setPreferredSize(new Dimension(120, 20));
            timeLabel.setHorizontalAlignment(SwingConstants.RIGHT); // 让文字靠右对齐，显得更稳

            // 【核心重构：接管底层鼠标事件，实现真正的“指哪打哪”和“丝滑拖拽”】
            final boolean[] isDraggingSlider = {false};

            progressSlider.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    isDraggingSlider[0] = true;
                    updateSliderFromMouse(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    updateSliderFromMouse(e);
                    isDraggingSlider[0] = false;

                    // 🚀 核心丝滑秘诀：仅在“松开鼠标”的瞬间，才通知底层音频引擎跳转！彻底消灭拖拽卡顿！
                    if (playbackClip != null && playbackClip.isOpen()) {
                        double target = (double) progressSlider.getValue() * totalAudioSeconds / 1000.0;
                        playbackClip.setMicrosecondPosition((long) (target * 1_000_000));
                    }
                }

                private void updateSliderFromMouse(MouseEvent e) {
                    try {
                        // 呼叫 Swing 底层 UI 接口，实现真正的像素级精准定位（无视10%跳跃限制）
                        javax.swing.plaf.basic.BasicSliderUI ui = (javax.swing.plaf.basic.BasicSliderUI) progressSlider.getUI();
                        int val = ui.valueForXPosition(e.getX());
                        progressSlider.setValue(val);
                    } catch (Exception ex) {
                        // 兼容性兜底方案
                        double percent = Math.max(0.0, Math.min(1.0, (double) e.getX() / progressSlider.getWidth()));
                        int val = (int) Math.round(progressSlider.getMinimum() + (progressSlider.getMaximum() - progressSlider.getMinimum()) * percent);
                        progressSlider.setValue(val);
                    }
                }
            });

            progressSlider.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    // 拖拽过程中，只让滑块视觉上丝滑跟随，坚决不碰底层音频！
                    try {
                        javax.swing.plaf.basic.BasicSliderUI ui = (javax.swing.plaf.basic.BasicSliderUI) progressSlider.getUI();
                        progressSlider.setValue(ui.valueForXPosition(e.getX()));
                    } catch (Exception ex) {
                        // 计算失败时回退到默认位置
                    }
                }
            });

            // 3. 控制组
            ModernButton playBtn = new ModernButton(playbackClip != null && playbackClip.isRunning()
                    ? "<html><font face='Segoe UI Emoji'>⏸</font> 暂停</html>"
                    : "<html><font face='Segoe UI Emoji'>▶</font> 播放</html>");
            ModernButton setStartBtn = new ModernButton("设为开头");
            ModernButton setEndBtn = new ModernButton("设为结尾");
            ModernButton forwardBtn = new ModernButton("+15s");
            ModernButton clearBtn = new ModernButton("重置剪辑");
            ModernButton previewClipBtn = new ModernButton("<html><font face='Segoe UI Emoji'>✨</font> 试听带效果片段</html>");
            previewClipBtn.setCustomColors(new Color(230, 255, 230), new Color(210, 245, 210), new Color(190, 235, 190));

            playBtn.addActionListener(e -> { AudioManager.togglePlayPause(this); updateResultPanel(); });
            setStartBtn.addActionListener(e -> { if(playbackClip!=null){audioStartTime=(double)playbackClip.getMicrosecondPosition()/1000000.0; updateResultPanel();} });
            setEndBtn.addActionListener(e -> { if(playbackClip!=null){audioEndTime=(double)playbackClip.getMicrosecondPosition()/1000000.0; updateResultPanel();} });
            forwardBtn.addActionListener(e -> {
                if (playbackClip != null && playbackClip.isOpen()) {
                    long currentPos = playbackClip.getMicrosecondPosition();
                    long newPos = currentPos + (15L * 1_000_000L);
                    long maxPos = (long)(totalAudioSeconds * 1_000_000L);
                    playbackClip.setMicrosecondPosition(Math.min(newPos, maxPos));
                }
            });
            clearBtn.addActionListener(e -> {
                autoPreviewStart = 0; audioStartTime = 0; audioEndTime = totalAudioSeconds;
                isFadedPlaying = false;
                if (selectedAudio != null) { AudioManager.prepareAudioPreview(this, selectedAudio); }
                updateResultPanel();
            });
            previewClipBtn.addActionListener(e -> AudioManager.playFadedPreview(this));

            JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            btnGroup.setOpaque(false);
            btnGroup.add(playBtn); btnGroup.add(setStartBtn); btnGroup.add(setEndBtn);
            btnGroup.add(forwardBtn); btnGroup.add(clearBtn); btnGroup.add(previewClipBtn);

            JPanel toolBar = new JPanel(new BorderLayout(10, 0));
            toolBar.setOpaque(false);
            toolBar.setBorder(BorderFactory.createEmptyBorder(2, 20, 3, 20));
            // 波形与进度条放入同一容器，宽度用进度条的实际值确保对齐
            WaveformPanel wavePanel = new WaveformPanel(this, progressSlider);
            wavePanel.setBackground(new Color(245, 253, 250));
            sliderContainer.add(wavePanel, BorderLayout.NORTH);
            toolBar.add(sliderContainer, BorderLayout.CENTER);
            toolBar.add(timeLabel, BorderLayout.EAST);
            toolBar.add(btnGroup, BorderLayout.SOUTH);

            if (playbackClip != null && playbackClip.isOpen()) {
                if (playbackTimer != null) playbackTimer.stop();
                playbackTimer = new javax.swing.Timer(50, e -> { // 提高刷新率到 50ms 以匹配 0.1s 精度
                    if (playbackClip.isOpen()) {
                        double internalPos = (double)playbackClip.getMicrosecondPosition() / 1_000_000.0;

                        // 【同步核心】试听模式下显示：起始标记 + 内部时间
                        double displaySeconds = isFadedPlaying ? (audioStartTime + internalPos) : internalPos;

// 【状态同步】：如果用户没在拖，系统计时器自动走
                        if (!isDraggingSlider[0]) {
                            progressSlider.setValue((int)(displaySeconds * 1000 / totalAudioSeconds));
                        } else {
                            // 🌟 进阶体验：当用户在拖拽时，强行把“当前时间标签”覆盖为鼠标所指的预览时间
                            displaySeconds = (double) progressSlider.getValue() * totalAudioSeconds / 1000.0;
                        }

                        // 【0.1s 精度显示】格式：分:秒.毫秒
                        String curStr = String.format("%02d:%04.1f", (int)displaySeconds / 60, displaySeconds % 60);
                        String totalStr = String.format("%02d:%04.1f", (int)totalAudioSeconds / 60, totalAudioSeconds % 60);
                        timeLabel.setText(curStr + " / " + totalStr);
                    }
                });
                playbackTimer.start();
            }

            audioGallery.add(toolBar);
            resultPanel.add(audioGallery);
            resultPanel.add(Box.createVerticalStrut(10));
        }

// ================= [主按钮与矩阵] =================
        ModernButton confirmBtn = new ModernButton("读取完成！点击直接写入 Excel 表格");
        // 【已删除软盘图标】
        confirmBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        confirmBtn.setCustomColors(new Color(190, 225, 255), new Color(170, 210, 245), new Color(150, 195, 235));
        confirmBtn.setForeground(new Color(0, 102, 204));

// 【终极解法】把宽度从 500 放宽到 650，给超长文本充足的物理空间，彻底消灭换行！
        confirmBtn.setPreferredSize(new Dimension(650, 45));
        confirmBtn.setMinimumSize(new Dimension(650, 45));
        confirmBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        confirmBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        confirmBtn.addActionListener(e -> generateAndCopyExcelRow(confirmBtn));
        resultPanel.add(confirmBtn);
        resultPanel.add(Box.createVerticalStrut(15));

        Set<String> charterSet = new LinkedHashSet<>();
        String bpmStr = "未知"; // 【新增】预设 BPM 变量
        if (currentCharts != null) {
            for (Map<String, String> c : currentCharts) {
                String ch = c.get("charter"); if (ch != null && !ch.isEmpty()) charterSet.add(ch);
            }
            // 提取 BPM 信息
            if (!currentCharts.isEmpty()) bpmStr = currentCharts.get(0).get("bpm");
        }

// 【紧凑化】强制使用单行布局 (BoxLayout.X_AXIS)，配合左右 Glue 居中，保证所有信息绝对在同一行
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.X_AXIS));
        infoPanel.setOpaque(false);
        infoPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        infoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); // 强行压紧垂直高度

        infoPanel.add(Box.createHorizontalGlue());
        infoPanel.add(new CapsuleLabel("歌曲: ", songName, new Color(225, 240, 255), new Color(0, 80, 160)));
        infoPanel.add(Box.createHorizontalStrut(8));
        infoPanel.add(new CapsuleLabel("作者: ", currentInfo.get("artist"), new Color(230, 250, 230), new Color(0, 100, 0)));
        infoPanel.add(Box.createHorizontalStrut(8));
        infoPanel.add(new CapsuleLabel("谱师: ", String.join("、", charterSet), new Color(255, 240, 225), new Color(150, 70, 0)));
        infoPanel.add(Box.createHorizontalStrut(8));
        infoPanel.add(new CapsuleLabel("BPM: ", bpmStr, new Color(245, 230, 255), new Color(100, 0, 150)));
        infoPanel.add(Box.createHorizontalStrut(8));
        infoPanel.add(new CapsuleLabel("时长: ", currentDuration, new Color(255, 235, 235), new Color(150, 0, 0)));
        infoPanel.add(Box.createHorizontalGlue());

        resultPanel.add(infoPanel);
        resultPanel.add(Box.createVerticalStrut(5)); // 【压缩间距】与下方矩阵的距离从 10 缩小为 5

// 【核心修复】将铺满强制拉伸的 GridLayout 改为居中紧凑的 FlowLayout，消除框内多余空隙
        // 检查是否有 mode3 谱面
        boolean hasMode3 = false;
        if (currentCharts != null) {
            for (Map<String, String> c : currentCharts)
                if ("true".equals(c.get("isMode3"))) { hasMode3 = true; break; }
        }

        JPanel matrixPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        matrixPanel.setBackground(Color.WHITE);
        matrixPanel.setMaximumSize(new Dimension(800, 300));

        if (hasMode3) {
            matrixPanel.add(Mode3PanelFactory.createMode3InfoPanel(this));
        } else {
            matrixPanel.add(Mode3PanelFactory.createKModeColumn(this, "4K"));
            matrixPanel.add(Mode3PanelFactory.createKModeColumn(this, "5K"));
            matrixPanel.add(Mode3PanelFactory.createKModeColumn(this, "6K"));
            for (String k : new String[]{"7K", "8K"}) {
                boolean ex = false; for (Map<String, String> c : currentCharts) if (c.get("kMode").equals(k)) { ex = true; break; }
                if (ex) matrixPanel.add(Mode3PanelFactory.createKModeColumn(this, k));
            }
        }
        resultPanel.add(matrixPanel);
        resultPanel.revalidate();
        resultPanel.repaint();
    }

    // 新增辅助方法：异步加载图片避免主线程卡死
    private void loadImageToGallery(File file, JPanel gallery) {
        try {
            BufferedImage bimg = ImageIO.read(file);
            if (bimg != null) {
                Image scaled = bimg.getScaledInstance(108, 108, Image.SCALE_SMOOTH);
                ImageIcon icon = new ImageIcon(scaled);
                SwingUtilities.invokeLater(() -> {
                    JLabel picLabel = new JLabel(icon);
                    picLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    picLabel.setBorder(file.equals(selectedImage) ?
                            BorderFactory.createLineBorder(new Color(220, 50, 50), 4) :
                            BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
                    picLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                        public void mouseClicked(java.awt.event.MouseEvent evt) {
                            selectedImage = file;
                            updateResultPanel();
                        }
                    });
                    gallery.add(picLabel, 0); // 插入到最前面
                    gallery.revalidate();
                });
            }
        } catch (Exception e) {
            System.err.println("警告：加载图片到画廊失败: " + e.getMessage());
        }
    }

    private void generateAndCopyExcelRow(JButton btn) {
        String id = idField.getText().trim();
        if (id.isEmpty()) {
            JOptionPane.showMessageDialog(this, "提取失败：请先在左侧输入歌曲 ID！", "缺少数据", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 按钮反馈，提示正在处理
        btn.setText("<html><table border='0' cellpadding='0'><tr><td nowrap><font face='Segoe UI Emoji'>⏳</font> 正在处理并校验，请稍候...</td></tr></table></html>");
        btn.setEnabled(false);

        // 提交到 taskQueue 排队执行，防止并发写 Excel
        taskQueue.submit(() -> {
            try {
                // ================= [阶段 0：提前准备所有数据和路径 (不执行破坏性操作)] =================
                File desktopDir = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
                if (desktopDir == null || !desktopDir.exists()) desktopDir = new File(System.getProperty("user.dir"));

                File collectionDir = new File(desktopDir, "合集");
                if (!collectionDir.exists()) collectionDir.mkdirs();
                File destImg = new File(collectionDir, id + ".jpg");

                File previewDir = new File(desktopDir, "preview");
                if (!previewDir.exists()) previewDir.mkdirs();
                File destAudio = new File(previewDir, id + ".mp3");

                // 提前生成 34 列数据
                String[] cols = new String[34];
                Arrays.fill(cols, "");
                String albumName = albumNameField.getText().trim();
                String albumId = albumIdField.getText().trim();

                String dateStr = "";
                try {
                    int y = Integer.parseInt(yearField.getText().trim());
                    int m = Integer.parseInt(monthField.getText().trim());
                    int d = Integer.parseInt(dayField.getText().trim());
                    dateStr = String.format("%04d-%02d-%02d", y, m, d);
                } catch (Exception ex) {
                    dateStr = yearField.getText() + "-" + monthField.getText() + "-" + dayField.getText();
                }

                Set<String> charters = new LinkedHashSet<>();
                if (currentCharts != null) {
                    for (Map<String, String> c : currentCharts) {
                        String ch = c.get("charter");
                        if (ch != null && !ch.isEmpty()) charters.add(ch);
                    }
                }

                String songName = (currentInfo != null) ? currentInfo.get("songName") : "未知";
                if (songName != null) songName = songName.replaceAll("^【[^】]*】\\s*", "");

                cols[0] = id;
                cols[1] = songName;
                cols[2] = (currentInfo != null) ? currentInfo.get("artist") : "未知";
                cols[3] = String.join("、", charters);
                cols[4] = (currentCharts != null && !currentCharts.isEmpty()) ? currentCharts.get(0).get("bpm") : "";
                cols[5] = currentDuration;
                cols[6] = albumName;
                cols[7] = albumId;
                cols[8] = dateStr;
                cols[17] = destImg.getAbsolutePath();
                cols[18] = destAudio.getAbsolutePath();

                // 难度槽位数据填充
                boolean hasMX = false, hasSP = false;
                if (currentCharts != null) {
                    for (Map<String, String> c : currentCharts) {
                        String ver = c.get("version").toLowerCase();
                        if (ver.contains("mx") || ver.contains("ms") || ver.contains("master")) hasMX = true;
                        if (ver.contains("sp") || ver.contains("special")) hasSP = true;
                    }
                }
                int[] basicIndices = {19, 20, 21, 23, 24, 25, 27, 28, 29};
                for (int i : basicIndices) cols[i] = "0-0";
                if (hasMX) { cols[22] = "0-0"; cols[26] = "0-0"; cols[30] = "0-0"; }
                if (hasSP) { cols[31] = "0-0"; cols[32] = "0-0"; cols[33] = "0-0"; }

                // 常规谱面先填充
                if (currentCharts != null) {
                    for (Map<String, String> c : currentCharts) {
                        if ("true".equals(c.get("isMode3"))) continue;
                        String kMode = c.get("kMode");
                        String ver = c.get("version").toLowerCase();
                        String val = c.get("level") + "-" + c.get("combo");
                        int base = -1;
                        if (kMode.equals("4K")) base = 19;
                        else if (kMode.equals("5K")) base = 23;
                        else if (kMode.equals("6K")) base = 27;

                        if (base != -1) {
                            if (ver.contains("sp") || ver.contains("special")) {
                                if (kMode.equals("4K")) cols[31] = val;
                                if (kMode.equals("5K")) cols[32] = val;
                                if (kMode.equals("6K")) cols[33] = val;
                            } else if (ver.contains("mx") || ver.contains("ms") || ver.contains("master")) {
                                cols[base + 3] = val;
                            } else if (ver.contains("hd") || ver.contains("hard")) {
                                cols[base + 2] = val;
                            } else if (ver.contains("nm") || ver.contains("normal")) {
                                cols[base + 1] = val;
                            } else if (ver.contains("ez") || ver.contains("easy")) {
                                cols[base + 0] = val;
                            }
                        }
                    }
                }

                // mode3 后填充：Catch 必须独占一个完整键位块，冲突时整块下移。
                List<Map<String, String>> m3Charts = new ArrayList<>();
                if (currentCharts != null) {
                    for (Map<String, String> c : currentCharts) {
                        if ("true".equals(c.get("isMode3"))) m3Charts.add(c);
                    }
                }
                if (!m3Charts.isEmpty()) {
                    try {
                        CatchChartBlockAllocator.placeIntoNextFreeBlock(cols, m3Charts);
                    } catch (RuntimeException placementError) {
                        final String errorMessage = placementError.getMessage();
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(btn.getParent(), errorMessage,
                                    "Catch 谱面无法安全写入", JOptionPane.ERROR_MESSAGE);
                            btn.setText("写入被拦截，请检查 Catch 谱面");
                            btn.setIcon(new EmojiIcon("❌", 18));
                            btn.setEnabled(true);
                        });
                        return;
                    }
                }

                // ================= [阶段 1：审查门 1 - Excel 查重] =================
                if (currentWorkbook == null) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(btn.getParent(), "表格数据未初始化，请重启程序！", "错误", JOptionPane.ERROR_MESSAGE);
                        btn.setText("写入被拦截，请重试");
                        btn.setEnabled(true);
                    });
                    return;
                }

                boolean idExists = false, nameExists = false;
                String existingIdForName = "";
                try {
                    Sheet sheet = currentWorkbook.getSheetAt(0);
                    DataFormatter dataFormatter = new DataFormatter();
                    for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                        Row r = sheet.getRow(i);
                        if (r == null) continue;
                        String colId = dataFormatter.formatCellValue(r.getCell(0)).trim();
                        String colName = dataFormatter.formatCellValue(r.getCell(1)).trim();

                        if (!colId.isEmpty() && colId.equals(cols[0])) { idExists = true; break; }
                        if (!colName.isEmpty() && colName.equals(cols[1])) { nameExists = true; existingIdForName = colId; }
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(btn.getParent(), "读取表格数据失败！", "错误", JOptionPane.ERROR_MESSAGE);
                        btn.setText("写入被拦截，请重试");
                        btn.setEnabled(true);
                    });
                    return;
                }

                if (idExists) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(btn.getParent(), "存在重复的 ID (" + cols[0] + ")，禁止导入！", "拦截", JOptionPane.ERROR_MESSAGE);
                        btn.setText("写入被拦截，请修改 ID");
                        btn.setEnabled(true);
                    });
                    return;
                }

                if (nameExists) {
                    final int[] mergeChoice = {-1};
                    final String existId = existingIdForName;
                    SwingUtilities.invokeAndWait(() -> {
                        mergeChoice[0] = JOptionPane.showOptionDialog(btn.getParent(),
                                "存在重名歌曲: 《" + cols[1] + "》\n它在表格里对应的 ID 是: " + existId + "\n新歌曲 ID 是: " + cols[0],
                                "发现重名",
                                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                                null,
                                new String[]{"强制添加新ID", "更新歌曲信息", "取消"},
                                "取消");
                    });
                    if (mergeChoice[0] == 2 || mergeChoice[0] == -1) {
                        // 取消
                        SwingUtilities.invokeLater(() -> {
                            btn.setText("读取完成！点击直接写入 Excel 表格");
                            btn.setIcon(null);
                            btn.setEnabled(true);
                        });
                        return;
                    }
                    if (mergeChoice[0] == 1) {
                        // 更新歌曲信息：在原 ID 行上合并，只增不改
                        try {
                            Sheet dupSheet = currentWorkbook.getSheetAt(0);
                            DataFormatter dupFmt = new DataFormatter();
                            int targetRow = -1;
                            for (int i = 0; i <= dupSheet.getLastRowNum(); i++) {
                                Row r = dupSheet.getRow(i);
                                if (r == null) continue;
                                if (dupFmt.formatCellValue(r.getCell(0)).trim().equals(existId)) {
                                    targetRow = i; break;
                                }
                            }
                            if (targetRow >= 0) {
                                Row existingRow = dupSheet.getRow(targetRow);
                                boolean anyChange = false;

                                String[] existingColumns = new String[cols.length];
                                for (int c = 0; c < existingColumns.length; c++) {
                                    Cell cell = existingRow.getCell(c);
                                    existingColumns[c] = cell == null ? "" : dupFmt.formatCellValue(cell).trim();
                                }
                                String[] mergedChartColumns = CatchChartRowMerger.merge(
                                        existingColumns, cols, m3Charts);

                                for (int c = 1; c < cols.length; c++) {
                                    // 图片路径和音频路径含原ID，不能覆盖
                                    if (c == 17 || c == 18) continue;
                                    Cell cell = existingRow.getCell(c);
                                    String oldVal = (cell == null) ? "" : dupFmt.formatCellValue(cell).trim();
                                    String newVal = c >= 19
                                            ? mergedChartColumns[c]
                                            : (cols[c] != null ? cols[c].trim() : "");
                                    if (newVal.isEmpty() || newVal.equals(oldVal)) continue;
                                    if (cell == null) cell = existingRow.createCell(c);
                                    if (c == 6 && !oldVal.isEmpty()) {
                                        cell.setCellValue(oldVal + "|" + newVal);
                                    } else if (c == 7 && !oldVal.isEmpty()) {
                                        cell.setCellValue(oldVal + "," + newVal);
                                    } else if (c == 8 && !oldVal.isEmpty()) {
                                        if (oldVal.compareTo(newVal) < 0) cell.setCellValue(oldVal);
                                        else cell.setCellValue(newVal);
                                    } else {
                                        cell.setCellValue(newVal);
                                    }
                                    anyChange = true;
                                }
                                // 同步写回桌面
                                if (anyChange) {
                                    ExcelManager.normalizeRowHeights(this);
                                    File desktopExcel = ExcelManager.getDesktopExcelFile();
                                    try (FileOutputStream fos = new FileOutputStream(desktopExcel)) {
                                        currentWorkbook.write(fos);
                                    }
                                    SwingUtilities.invokeLater(() -> {
                                        btn.setText("更新完成！已合并至 ID " + existId);
                                        btn.setIcon(null);
                                        btn.setEnabled(false);
                                        ExcelManager.refreshIdHint(this);
                                        ExcelManager.refreshAlbumIdHint(this);
                                        ExcelManager.syncToBot(this);
                                    });
                                } else {
                                    SwingUtilities.invokeLater(() -> {
                                        btn.setText("无变化，所有数据一致");
                                        btn.setIcon(null);
                                        btn.setEnabled(true);
                                    });
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            SwingUtilities.invokeLater(() -> {
                                btn.setText("更新失败：" + ex.getMessage());
                                btn.setEnabled(true);
                            });
                        }
                        return;
                    }
                    // mergeChoice[0] == 0 → 强制添加新ID，继续原流程
                }

                // ================= [阶段 2：审查门 2 - 封面图片覆盖确认] =================
                if (selectedImage != null && destImg.exists()) {
                    final boolean[] proceedImg = {false};
                    SwingUtilities.invokeAndWait(() -> {
                        int choice = JOptionPane.showConfirmDialog(btn.getParent(),
                                "文件夹内已存在 [" + id + ".jpg]，确认要覆盖它吗？",
                                "覆盖确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        proceedImg[0] = (choice == JOptionPane.YES_OPTION);
                    });
                    if (!proceedImg[0]) {
                        SwingUtilities.invokeLater(() -> {
                            btn.setText("读取完成！点击直接写入 Excel 表格");
                            btn.setIcon(null);
                            btn.setEnabled(true);
                        });
                        return; // 用户取消了 JPG 覆盖，直接拦截退出，坚决不碰文件
                    }
                }

                // ================= [阶段 3：执行阶段 - 全部放行，全面开火！] =================

                // 1. 转换并提取用户选中的图片
                if (selectedImage != null) {
                    try {
                        BufferedImage img = ImageIO.read(selectedImage);
                        if (img != null) {
                            BufferedImage jpgImage = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                            jpgImage.createGraphics().drawImage(img, 0, 0, Color.WHITE, null);
                            ImageIO.write(jpgImage, "jpg", destImg);
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                }

                // 2. 剪辑并转换音频
                if (selectedAudio != null) {
                    try {
                        double clipDuration = audioEndTime - audioStartTime;
                        if (clipDuration <= 0) clipDuration = 5.0;
                        double fadeOutStart = Math.max(0, clipDuration - 4.0);

                        ProcessBuilder pb = new ProcessBuilder(
                                ffmpegCommand, "-y",
                                "-ss", String.valueOf(audioStartTime), "-to", String.valueOf(audioEndTime),
                                "-i", selectedAudio.getAbsolutePath(),
                                "-af", String.format("afade=t=in:st=0:d=0.1,afade=t=out:st=%.2f:d=4", fadeOutStart),
                                "-b:a", "192k",
                                destAudio.getAbsolutePath()
                        );
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        try (InputStream is = p.getInputStream()) {
                            byte[] buffer = new byte[1024];
                            while (is.read(buffer) != -1) {} // 消耗流数据防卡死
                        }
                        boolean audioCompleted = p.waitFor(120, TimeUnit.SECONDS);
                        if (!audioCompleted) {
                            p.destroyForcibly();
                            System.err.println("警告：音频导出超时，已强制终止");
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                }

                // 3. 强制释放所有占用并清理垃圾
                if (playbackClip != null) {
                    playbackClip.stop();
                    playbackClip.close();
                    playbackClip = null;
                }
                imageCache.clear();
                System.gc();

                if (activeTempDir != null && activeTempDir.exists()) {
                    try {
                        Thread.sleep(200);
                        MczParser.deleteDirectory(activeTempDir);
                        activeTempDir = null;
                    } catch(Exception ex) {
                        System.err.println("警告：临时目录清理失败: " + ex.getMessage());
                    }
                }

// 4. 追加写入内存工作簿并同步到桌面
                boolean writeSuccess = false;
                try {
                    Sheet sheet = currentWorkbook.getSheetAt(0);

                    // 倒序寻找真正包含数据的最后一行
                    int realLastRow = 0;
                    for (int i = sheet.getLastRowNum(); i >= 0; i--) {
                        Row r = sheet.getRow(i);
                        if (r != null) {
                            Cell cell = r.getCell(0);
                            if (cell != null && !cell.toString().trim().isEmpty()) {
                                realLastRow = i;
                                break;
                            }
                        }
                    }

                    // 紧挨着真正数据的下一行创建新行
                    Row newExcelRow = sheet.createRow(realLastRow + 1);
                    for (int i = 0; i < cols.length; i++) {
                        Cell cell = newExcelRow.createCell(i);
                        cell.setCellValue(cols[i]);
                    }
                    writeSuccess = true;

                    // 同步写入桌面备份
                    try {
                        File desktopExcel = ExcelManager.getDesktopExcelFile();
                        ExcelManager.normalizeRowHeights(this);
                        try (FileOutputStream fos = new FileOutputStream(desktopExcel)) {
                            currentWorkbook.write(fos);
                        }
                    } catch (Exception syncEx) {
                        System.err.println("提示：桌面 Excel 同步失败，数据仅保存在内存中");
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                // === UI 最终状态反馈 ===
                final boolean finalSuccess = writeSuccess;
                SwingUtilities.invokeLater(() -> {
                    if (finalSuccess) {
                        customImageFile = null;
                        btn.setText("全套流程执行成功！Excel已写入，缓存已清理");
                        btn.setIcon(null);
                        btn.setEnabled(false); // 任务彻底完成
                        ExcelManager.syncToBot(MczTool.this);
                    } else {
                        JOptionPane.showMessageDialog(btn.getParent(), "最终写入 Excel 失败，可能是文件被占用！", "错误", JOptionPane.ERROR_MESSAGE);
                        btn.setText("写入失败，请重试");
                        btn.setIcon(new EmojiIcon("❌", 18));
                        btn.setEnabled(true);
                    }
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    btn.setText("<html><table border='0' cellpadding='0'><tr><td nowrap><font face='Segoe UI Emoji'>❌</font> 出错了，请看控制台日志</td></tr></table></html>");
                    btn.setEnabled(true);
                });
            }
        });
    }

    // [新增方法] 创建一行：标签 + 文本框 + 复制按钮
    private void addCopyRow(String labelText, String content) {
        if (content == null) content = "";
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32)); // 限制高度
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        JLabel label = new JLabel(labelText + ":");
        label.setPreferredSize(new Dimension(60, 24)); // 将宽度稍微放宽到 45
        label.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));

        JTextField textField = new JTextField(content);
        textField.setEditable(false); // 只读
        textField.setCaretPosition(0); // 确保显示开头
        textField.setToolTipText(content); // 【新增】鼠标悬停可查看完整超长文本

        ModernButton copyBtn = new ModernButton("复制");
        copyBtn.setFocusable(false);
        copyBtn.setToolTipText("点击复制: " + content);
        final String textToCopy = content;

        copyBtn.addActionListener(e -> {
            try {
                // 执行系统剪贴板复制
                java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(textToCopy);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);

                // 按钮反馈效果
                copyBtn.setText("√");
                copyBtn.setEnabled(false);
                // 1秒后恢复文字
                new javax.swing.Timer(1000, evt -> {
                    copyBtn.setText("复制");
                    copyBtn.setEnabled(true);
                }).start();
            } catch (Exception ex) {
                AudioManager.log(MczTool.this, "复制失败: " + ex.getMessage());
            }
        });

        row.add(label, BorderLayout.WEST);
        row.add(textField, BorderLayout.CENTER);
        row.add(copyBtn, BorderLayout.EAST);

        resultPanel.add(row);
    }

    private void processMcz(File mczFile) {
        File currentWorkDir = new File(System.getProperty("user.dir"));

        // 1. 强制关闭旧音频，并切断底层占用
        if (playbackClip != null) {
            playbackClip.stop();
            playbackClip.close();
            playbackClip = null; // 【核心修复】彻底切断对象引用
        }

        // 【核心修复】强制请求系统回收遗留的文件句柄（针对图片流和音频流）
        System.gc();

        if (activeTempDir != null && activeTempDir.exists()) {
            try {
                Thread.sleep(150); // 给 Windows 操作系统 150ms 的“松手”时间
                MczParser.deleteDirectory(activeTempDir);
                activeTempDir = null;
            } catch (Exception e) {
                System.err.println("警告：processMcz 临时目录清理失败: " + e.getMessage());
            }
        }

        // 2. 重置当前歌曲的数据
        currentImageFiles.clear();
        currentAudios.clear();
        selectedImage = null;
        selectedAudio = null;
        autoPreviewStart = 0;

        activeBaseName = mczFile.getName().substring(0, mczFile.getName().lastIndexOf("."));
        activeTempDir = new File(currentWorkDir, "temp_mcz_extract_" + System.currentTimeMillis());

        Map<String, String> info = new HashMap<>();
        info.put("songName", "未知"); info.put("artist", "未知");
        List<Map<String, String>> charts = new ArrayList<>();
        final String[] duration = {"00:00"};

        try {
            activeTempDir.mkdirs();
            MczParser.unzip(mczFile, activeTempDir);

            Files.walkFileTree(activeTempDir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString().toLowerCase();
                    File sourceFile = file.toFile();

                    if (fileName.endsWith(".mc")) {
                        try {
                            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                            // 歌名提取 (逻辑保持不变)
                            if ("未知".equals(info.get("songName"))) {
                                String tOrg = MczParser.extractJsonValue(content, "titleorg");
                                if (tOrg != null && !tOrg.isEmpty()) info.put("songName", tOrg);
                                else {
                                    String tAlt = MczParser.extractJsonValue(content, "title");
                                    if (tAlt != null && !tAlt.isEmpty()) info.put("songName", tAlt);
                                }
                            }
                            // 作者提取 (逻辑保持不变)
                            if ("未知".equals(info.get("artist"))) {
                                String aOrg = MczParser.extractJsonValue(content, "artistorg");
                                if (aOrg != null && !aOrg.isEmpty()) info.put("artist", aOrg);
                                else {
                                    String aAlt = MczParser.extractJsonValue(content, "artist");
                                    if (aAlt != null && !aAlt.isEmpty()) info.put("artist", aAlt);
                                }
                            }
                            // 预览时间与谱面数据 (逻辑保持不变)
                            String pStr = MczParser.extractJsonValue(content, "preview");
                            if (pStr != null && !pStr.isEmpty()) {
                                try { double pVal = Double.parseDouble(pStr); if (pVal > 0) autoPreviewStart = pVal / 1000.0; } catch (Exception e) {
                            // 非数字的 preview 值忽略
                        }
                            }
                            String version = MczParser.extractJsonValue(content, "version");
                            String level = "0";
                            Matcher lvMatcher = Pattern.compile("(?i)lv\\.?\\s*(\\d+)").matcher(version);
                            if (lvMatcher.find()) level = AudioManager.getConvertedRMLevel(MczTool.this, version, lvMatcher.group(1));

                            // 【核心修复：动态识别 K 数】
                            int parsedKNum = 4;
                            String upperVer = version.toUpperCase();
                            if (upperVer.contains("5K")) parsedKNum = 5;
                            else if (upperVer.contains("6K")) parsedKNum = 6;
                            else if (upperVer.contains("7K")) parsedKNum = 7;
                            else if (upperVer.contains("8K")) parsedKNum = 8;

                            double bpm = MczParser.extractInitialBpm(content);
                            Map<String, String> chartData = new HashMap<>();
                            chartData.put("kMode", parsedKNum + "K");
                            chartData.put("level", level);
                            chartData.put("bpm", String.valueOf(bpm).replace(".0",""));
                            chartData.put("combo", String.valueOf(MczParser.calculateMaxCombo(content, bpm, parsedKNum)));
                            chartData.put("version", version);
                            chartData.put("charter", MczParser.extractJsonValue(content, "creator"));

                            // 检测 mode:3 新模式（Cup/Salad/Platter/Rain/Overdose/Deluge）
                            if (content.contains("\"mode\":3") || content.contains("\"mode\" : 3") || content.contains("\"mode\": 3")) {
                                chartData.put("isMode3", "true");
                                String[] m3Diffs = {"Deluge", "Overdose", "Rain", "Platter", "Salad", "Cup"};
                                for (String d : m3Diffs) {
                                    if (upperVer.contains(d.toUpperCase())) {
                                        chartData.put("diffName", d);
                                        break;
                                    }
                                }
                            }
                            charts.add(chartData);
                        } catch (Exception ex) {
                            System.err.println("警告：解析 .mc 文件失败 (" + fileName + "): " + ex.getMessage());
                        }
                    }
                    else if (MczParser.isImage(fileName)) {
                        currentImageFiles.add(sourceFile);
                        try {
                            BufferedImage bimg = ImageIO.read(sourceFile);
                            if (bimg != null) {
                                imageCache.put(sourceFile, new ImageIcon(bimg.getScaledInstance(108, 108, Image.SCALE_SMOOTH)));
                                ratioCache.put(sourceFile, MczParser.getRatioString(bimg.getWidth(), bimg.getHeight())); // 【新增】缓存解析出的原图真实比例
                            }
                        } catch (Exception e) {
                            System.err.println("警告：无法读取 MCZ 内图片 (" + fileName + "): " + e.getMessage());
                        }
                    }
                    else if (MczParser.isAudio(fileName)) {
                        currentAudios.add(sourceFile);
                        if (duration[0].equals("00:00")) duration[0] = MczParser.getDurationWithFFmpeg(sourceFile, ffmpegCommand);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            this.currentInfo = info;
            this.currentCharts = charts;
            this.currentDuration = duration[0];

            // 【新增】拖入 MCZ 后自动刷新最新 ID 和最新专辑编号
            int freshMaxId = ExcelManager.readMaxIdFromExcel(this);
            String freshAlbumId = ExcelManager.readLastAlbumIdFromExcel(this);

            // 【关键修复】直接在当前 taskQueue 线程中排队执行音频准备，不启动新线程
            if (!currentAudios.isEmpty()) {
                AudioManager.syncPrepareAudioPreview(this, currentAudios.get(0));
            }

            SwingUtilities.invokeLater(() -> {
                if (freshMaxId > 0) {
                    currentMaxId = freshMaxId;
                    idField.setText(String.valueOf(freshMaxId + 1));
                    idHintLabel.setText("最新ID: " + freshMaxId + "  →  新歌ID: " + (freshMaxId + 1));
                }
                if (!freshAlbumId.isEmpty()) {
                    currentLastAlbumId = freshAlbumId;
                    albumIdField.setText(freshAlbumId);
                    albumIdHintLabel.setText("最新: " + freshAlbumId);
                }
                updateResultPanel();
            });
        } catch (Exception e) { e.printStackTrace(); }
    }


    // ==========================================
    // 🧠 核心：完全抛弃正常乐理的 Lua 复刻
    // ==========================================
    @SuppressWarnings("unchecked")

    static class Point extends MczParser.Point { public Point(long t, int x) { super(t,x); } }
    static class SimpleJsonParser extends MczParser.SimpleJsonParser { public SimpleJsonParser(String j) { super(j); } }

    static class ModernButton extends UiKit.ModernButton { public ModernButton(String t) { super(t); } }
    static class ModernRoundedBorder extends UiKit.ModernRoundedBorder { public ModernRoundedBorder(Color c, int t, int r) { super(c,t,r); } }

    // ==========================================
    class EmojiIcon extends UiKit.EmojiIcon { public EmojiIcon(String e, int s) { super(e, s); } }
    class CapsuleLabel extends UiKit.CapsuleLabel {
        public CapsuleLabel(String p, String c, Color bg, Color fg) { super(p, c, bg, fg, false, true, fzCyFont); }
        public CapsuleLabel(String p, String c, Color bg, Color fg, boolean rb, boolean cp) { super(p, c, bg, fg, rb, cp, fzCyFont); }
    }
    // =========================================================================
} // <--- MczTool 主类结束的大括号（文件必须以此行结束！）
