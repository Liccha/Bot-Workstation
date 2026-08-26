package com.mcz;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import java.awt.dnd.*;
import java.awt.datatransfer.DataFlavor;

/**
 * 茶韵日历 — 专属排版与表面模糊系统，支持导出 .ree 工程文件
 */
public class CalendarPanel extends JPanel {
    private final MczTool parent;

    private CalendarCanvas canvas;
    private JSlider radiusSlider;
    private JSlider thresholdSlider;
    private JCheckBox previewBox;
    private JLabel statusLabel;
    private javax.swing.Timer debounceTimer;

    // 【新增】年月选择器组件
    private JComboBox<Integer> yearCombo;
    private JComboBox<Integer> monthCombo;

    // 【新增：底层联网时间引擎】获取真实网络时间，预防本地电脑时间错误
    private Calendar getNetworkTime() {
        try {
            // 读取高可用性网站的 HTTP Date 头获取真实时间
            java.net.URLConnection conn = new java.net.URL("https://www.baidu.com").openConnection();
            conn.setConnectTimeout(2500); conn.setReadTimeout(2500);
            conn.connect();
            long dateL = conn.getDate();
            if (dateL > 0) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(dateL);
                return cal;
            }
        } catch (Exception e) {
            System.err.println("提示：获取网络时间失败，降级使用本地时间");
        }
        // 如果没网，自动降级为读取本机时间
        return Calendar.getInstance();
    }

    public CalendarPanel(MczTool parent) {
        this.parent = parent;
        setLayout(new BorderLayout());
        setBackground(new Color(30, 30, 30));

        // ====== 顶部控制栏 ======
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(new Color(45, 45, 45));
        topBar.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        UiKit.ModernButton backBtn = new UiKit.ModernButton("返回主页");
        backBtn.setPreferredSize(new Dimension(90, 32));
        backBtn.setCustomColors(new Color(245, 245, 245), new Color(255, 80, 80), new Color(200, 40, 40));
        backBtn.addActionListener(e -> parent.cardLayout.show(parent.mainContainer, "MAIN"));

        // 🚀 【应用联网引擎】：自动将初始值推演至“下个月”
        Calendar cal = getNetworkTime();
        cal.add(Calendar.MONTH, 1); // 自动加 1 个月，跨年也会自动计算
        int nextYear = cal.get(Calendar.YEAR);
        int nextMonth = cal.get(Calendar.MONTH) + 1;

        // 初始化年月选择器
        yearCombo = new JComboBox<>();
        for (int i = 2020; i <= 2050; i++) yearCombo.addItem(i);
        yearCombo.setSelectedItem(nextYear);

        monthCombo = new JComboBox<>();
        for (int i = 1; i <= 12; i++) monthCombo.addItem(i);
        monthCombo.setSelectedItem(nextMonth);

        // 组装左侧控制区
        JPanel datePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        datePanel.setOpaque(false);
        JLabel yearLbl = new JLabel("年份:"); yearLbl.setForeground(Color.WHITE);
        JLabel monthLbl = new JLabel("月份:"); monthLbl.setForeground(Color.WHITE);
        datePanel.add(yearLbl); datePanel.add(yearCombo);
        datePanel.add(monthLbl); datePanel.add(monthCombo);

        JPanel westPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        westPanel.setOpaque(false);
        westPanel.add(backBtn);
        westPanel.add(datePanel);

        topBar.add(westPanel, BorderLayout.WEST);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        controlPanel.setOpaque(false);

        JLabel radiusLbl = new JLabel("模糊半径:"); radiusLbl.setForeground(Color.WHITE);
        // 【修改预设】半径上限为 100，预设 30% 处即为 30
        radiusSlider = new JSlider(1, 100, 30); radiusSlider.setOpaque(false); radiusSlider.setPreferredSize(new Dimension(120, 30));

        JLabel threshLbl = new JLabel("阈值(保边):"); threshLbl.setForeground(Color.WHITE);
        // 【修改预设】阈值上限为 255，预设 20% 处即为 51
        thresholdSlider = new JSlider(2, 255, 40); thresholdSlider.setOpaque(false); thresholdSlider.setPreferredSize(new Dimension(120, 30));

        previewBox = new JCheckBox("预览表面模糊", true);
        previewBox.setOpaque(false); previewBox.setForeground(Color.WHITE);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(150, 255, 150));
        statusLabel.setPreferredSize(new Dimension(80, 20));

        controlPanel.add(radiusLbl); controlPanel.add(radiusSlider);
        controlPanel.add(threshLbl); controlPanel.add(thresholdSlider);
        controlPanel.add(previewBox); controlPanel.add(statusLabel);
        topBar.add(controlPanel, BorderLayout.CENTER);

// 【新增】保存日历工程文件按钮
        UiKit.ModernButton saveProjBtn = new UiKit.ModernButton("保存工程");
        saveProjBtn.setPreferredSize(new Dimension(90, 32));
        // 保持与主设计页一致的橘黄色系
        saveProjBtn.setCustomColors(new Color(255, 245, 230), new Color(255, 225, 180), new Color(240, 200, 140));
        saveProjBtn.addActionListener(e -> canvas.saveProject());

        UiKit.ModernButton exportBtn = new UiKit.ModernButton("导出日历");
        exportBtn.setPreferredSize(new Dimension(90, 32));
        exportBtn.setCustomColors(new Color(235, 250, 235), new Color(210, 245, 210), new Color(180, 230, 180));
        exportBtn.addActionListener(e -> canvas.exportImage());

        // 将两个按钮并排包装
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        rightActions.setOpaque(false);
        rightActions.add(saveProjBtn);
        rightActions.add(exportBtn);

        topBar.add(rightActions, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

// ====== 画布区域 (自动等比缩放 1200x800) ======
        canvas = new CalendarCanvas();
        JPanel canvasWrapper = new JPanel(new BorderLayout()); // 抛弃惹祸的 GridBagLayout
        canvasWrapper.setBackground(new Color(25, 25, 25));
        canvasWrapper.add(canvas, BorderLayout.CENTER);
        add(canvasWrapper, BorderLayout.CENTER);
        yearCombo.addActionListener(e -> { canvas.clearTextCache(); canvas.repaint(); });
        monthCombo.addActionListener(e -> { canvas.clearTextCache(); canvas.repaint(); });

// 防抖动：停手 0.5 秒后再开始繁重的像素计算
        debounceTimer = new javax.swing.Timer(500, e -> { // 【核心修复】明确实例化 Swing 的 Timer
            if (previewBox.isSelected()) {
                canvas.triggerSurfaceBlur(radiusSlider.getValue(), thresholdSlider.getValue());
            } else {
                canvas.clearBlur();
            }
        });
        debounceTimer.setRepeats(false);

        javax.swing.event.ChangeListener sliderListener = e -> {
            if (previewBox.isSelected()) debounceTimer.restart();
        };
        radiusSlider.addChangeListener(sliderListener);
        thresholdSlider.addChangeListener(sliderListener);
        previewBox.addActionListener(e -> debounceTimer.restart());
    }

    // --- 内部：日历专属画布 ---
    public class CalendarCanvas extends JPanel {
        private static final int CW = 1200;
        private static final int CH = 800;

        private BufferedImage rawBg;
        private BufferedImage blurBg;
        private boolean isBlurring = false;
        private double imgScale = 1.0, imgX = 0, imgY = 0;
        private java.awt.Point lastDragPoint; // <--- 修复：只保留一行

        // =========================================================
        // 【新增：日历单元格持久化存储数据结构】
        class AlbumData {
            // 【升级】：为每张专辑独立记录下拉框选中的属性
            String type1 = "常规专辑", type2 = "常规专辑";
            String name1 = "", name2 = "";
            int size = 17;
        }
        class SingleData {
            String name;
            int nameSize = 17;
            String removed;
            int removedSize = 11;
            String added;
            int addedSize = 12;
        }
        class DayData {
            java.util.List<AlbumData> albums = new ArrayList<>();
            java.util.List<SingleData> singles = new ArrayList<>();
        }
        // 强大的内存字典：键为 "YYYY-MM-DD"，无论怎么切月份都不会丢失填写过的数据！
        private Map<String, DayData> calendarDataMap = new HashMap<>();

        // 【新增】日历月份文字的渲染缓存
        private BufferedImage cachedMonthText = null;
        // 【新增】日历表格数据与表头的渲染缓存
        private BufferedImage cachedGridDataImg = null;

        public void clearTextCache() {
            cachedMonthText = null;
            cachedGridDataImg = null;
        }

        // 【核心算法：完美复刻 Photoshop 文字原生投影 (自带动态防切割引擎)】
        private BufferedImage generateMonthTextImage(int month) {
            // 【完美格式】英文仅首字母大写
            String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

            // 【完美格式】数字不补0，且横杠前后都预留出标准空格！
            String numStr = month + " - ";
            String engStr = months[month - 1];

            // ⚙️ 【新增：字体排版高级微调区】
            float tracking = -0.005f; // 👈 相当于 PS 里的字间距 -5 (-0.005 = -5 / 1000)
            // 👇 【瘦身滑块】: 0 表示不瘦身，数字越大字体被削得越细！你可以随时微调这个值 (建议 0.8 ~ 2.0)
            float thinStrokeWidth = 0.2f;

            Map<java.awt.font.TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(java.awt.font.TextAttribute.TRACKING, tracking);

            // 指定字体与大小：115点 和 48点，并强制挂载字间距参数
            Font fNum = ((parent.cangErFont != null) ? parent.cangErFont.deriveFont(Font.PLAIN, 115f) : new Font("Microsoft YaHei", Font.BOLD, 115)).deriveFont(attrs);
            Font fEng = ((parent.cangErFont != null) ? parent.cangErFont.deriveFont(Font.PLAIN, 48f) : new Font("Microsoft YaHei", Font.BOLD, 48)).deriveFont(attrs);

            // 计算物理尺寸
            BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gDummy = dummy.createGraphics();
            FontMetrics fmNum = gDummy.getFontMetrics(fNum);
            FontMetrics fmEng = gDummy.getFontMetrics(fEng);
            int wNum = fmNum.stringWidth(numStr);
            int wEng = fmEng.stringWidth(engStr);
            gDummy.dispose();

            // PS 投影参数与安全内边距
            int angle = 102;
            int distance = 4;
            int size = 10;
            double spread = 0.04;
            int pad = size * 2 + distance * 2 + 20;

// =========================================================
            // ⚙️ 【内部距离微调区：数字与英文已锁死联动】
            // =========================================================
            // 1. 【间距微调】英文与 "-" 之间的水平间距 (默认 0，填正数会拉宽)
            int gapX = 0;

            // 2. 【高低微调】英文与数字的高低差 (默认 -26，弥补大小字体的基线落差)
            int engHeightOffset = -26;

            // =========================================================
            // ⚠️ 警告：请不要在这里调整它们在画布中的绝对位置！
            // 如果要把整个 "3 - March" 移到别的角落，
            // 请一直往下滚动到 paintComponent 方法中，修改 textLayerX 和 textLayerY ！！
            // =========================================================

            // 底层引擎：英文的坐标已与数字动态宽度 (wNum) 绝对锁死
            int numOffsetX = 0;
            int numOffsetY = 0;
            int engOffsetX = wNum + gapX;
            int engOffsetY = engHeightOffset;
            // =========================================================

            // 【终极防切割引擎】：计算出绝对安全的最小外接矩形
            int minX = Math.min(numOffsetX, engOffsetX);
            int maxX = Math.max(numOffsetX + wNum, engOffsetX + wEng);
            int minY = Math.min(numOffsetY - fmNum.getAscent(), engOffsetY - fmEng.getAscent());
            int maxY = Math.max(numOffsetY + fmNum.getDescent(), engOffsetY + fmEng.getDescent());

            // 智能撑开画布宽高，不管偏移多少，绝对不切断！
            int imgW = (maxX - minX) + pad * 2;
            int imgH = (maxY - minY) + pad * 2;

            BufferedImage textImg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gText = textImg.createGraphics();
            // ⚙️ 【平滑锐利进化】：全盘接入最高级抗锯齿与分数度量，保证轮廓提取时精度达到亚像素级！
            gText.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gText.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gText.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            gText.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 将原本可能为负数的坐标，强行推正到 pad 的安全距离
            int finalNumX = pad + (numOffsetX - minX);
            int finalNumY = pad + (numOffsetY - minY);
            int finalEngX = pad + (engOffsetX - minX);
            int finalEngY = pad + (engOffsetY - minY);

            // 提取文字矢量路径，绘制到安全坐标
            java.awt.font.TextLayout tlNum = new java.awt.font.TextLayout(numStr, fNum, gText.getFontRenderContext());
            java.awt.font.TextLayout tlEng = new java.awt.font.TextLayout(engStr, fEng, gText.getFontRenderContext());
            Shape shapeNum = tlNum.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(finalNumX, finalNumY));
            Shape shapeEng = tlEng.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(finalEngX, finalEngY));
            java.awt.geom.Area textArea = new java.awt.geom.Area(shapeNum);
            textArea.add(new java.awt.geom.Area(shapeEng));

            // 1. 渲染投影层
            int dx = (int) Math.round(-distance * Math.cos(Math.toRadians(angle)));
            int dy = (int) Math.round(distance * Math.sin(Math.toRadians(angle)));

            BufferedImage shadowImg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gShadow = shadowImg.createGraphics();
            gShadow.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gShadow.translate(dx, dy);

            float strokeWidth = size * (float)spread * 2.0f;
            if (strokeWidth > 0) {
                gShadow.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gShadow.setColor(Color.BLACK);
                gShadow.draw(textArea);
            }
            gShadow.setColor(Color.BLACK);
            gShadow.fill(textArea);
            gShadow.dispose();

            if (size > 0) shadowImg = fastAlphaBlur(shadowImg, size / 2);

            // 2. 将计算好的黑色投影印回主画布
            gText.drawImage(shadowImg, 0, 0, null);

// 3. 【高级字体瘦身引擎 (Alpha 切割)】
            // 为了避免刻刀破坏黑色的投影层，我们必须在一块独立的透明玻璃上画白字
            BufferedImage pureTextImg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gPure = pureTextImg.createGraphics();
            gPure.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gPure.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gPure.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            gPure.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gPure.setColor(Color.WHITE);
            gPure.fill(textArea); // 步骤 A：画出正常粗细的白字

            if (thinStrokeWidth > 0) {
                // 步骤 B (魔法)：将画笔设为“橡皮擦”模式，沿着文字的边缘描边
                // 这会将原本肥胖的边缘无损地“吃”掉一圈，且边缘依旧锐利平滑！
                gPure.setComposite(AlphaComposite.Clear);
                gPure.setStroke(new BasicStroke(thinStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gPure.draw(textArea);
            }
            gPure.dispose();

            // 步骤 C：把瘦身后的完美白字盖在投影图层上方
            gText.drawImage(pureTextImg, 0, 0, null);
            gText.dispose();

            return textImg;
        }

        // Alpha 专用高斯平滑
        private BufferedImage fastAlphaBlur(BufferedImage src, int radius) {
            if (radius < 1) return src;
            int w = src.getWidth(), h = src.getHeight();
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] srcPix = src.getRGB(0, 0, w, h, null, 0, w);
            int[] tempPix = new int[w * h];
            int[] dstPix = new int[w * h];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sumA = 0, count = 0;
                    for (int k = -radius; k <= radius; k++) {
                        int nx = x + k;
                        if (nx >= 0 && nx < w) { sumA += (srcPix[y * w + nx] >>> 24); count++; }
                    }
                    tempPix[y * w + x] = (sumA / count) << 24;
                }
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int sumA = 0, count = 0;
                    for (int k = -radius; k <= radius; k++) {
                        int ny = y + k;
                        if (ny >= 0 && ny < h) { sumA += (tempPix[ny * w + x] >>> 24); count++; }
                    }
                    dstPix[y * w + x] = (sumA / count) << 24;
                }
            }
            dst.setRGB(0, 0, w, h, dstPix, 0, w);
            return dst;
        }
        // =========================================================
        // 【新增引擎：字与字智能混合渲染 (PS级完美无缝字体回退)】
        // =========================================================
        private java.awt.font.TextLayout createMixedTextLayout(String text, Font primaryFont, String fallbackFontName, float size, java.awt.font.FontRenderContext frc) {
            if (text == null || text.isEmpty()) text = " ";
            java.text.AttributedString as = new java.text.AttributedString(text);
            Font pFont = (primaryFont != null) ? primaryFont.deriveFont(Font.PLAIN, size) : new Font(fallbackFontName, Font.PLAIN, (int)size);

// 🎯【修复字重违和：无级字重调节引擎】
            // 抛弃死板的 Font.BOLD (固定权重为 2.0f)。现在你可以自由输入小数来控制生僻字的粗细！
            // 2.0f = Bold(粗体), 2.25f = Heavy(重黑), 2.5f = ExtraBold(特黑), 2.75f = UltraBold(超黑)
            float customBoldWeight = 2.8f; // 👈 修改这里：调大变粗，调小变细。建议在 2.2f ~ 2.6f 之间微调测试！

            Map<java.awt.font.TextAttribute, Object> weightAttr = new HashMap<>();
            weightAttr.put(java.awt.font.TextAttribute.WEIGHT, customBoldWeight);
            Font fFont = new Font(fallbackFontName, Font.PLAIN, (int)size).deriveFont(weightAttr);

            as.addAttribute(java.awt.font.TextAttribute.FONT, fFont, 0, text.length());

            if (primaryFont != null) {
                int start = 0;
                while (start < text.length()) {
                    String remainder = text.substring(start);
                    int failIndex = primaryFont.canDisplayUpTo(remainder);
                    if (failIndex == -1) {
                        // 剩下全认识，全部坚决保留为主字体 (OppoSans)
                        as.addAttribute(java.awt.font.TextAttribute.FONT, pFont, start, text.length());
                        break;
                    } else if (failIndex > 0) {
                        // 认识一部分，把认识的范围赋给主字体
                        as.addAttribute(java.awt.font.TextAttribute.FONT, pFont, start, start + failIndex);
                        start += failIndex;
                    } else {
                        // 当前这 1 个字不认识（保留兜底的 Adobe 黑体），并处理 Emoji 等超长字符
                        int charLen = Character.isHighSurrogate(text.charAt(start)) ? 2 : 1;
                        start += charLen;
                    }
                }
            }
            return new java.awt.font.TextLayout(as.getIterator(), frc);
        }

        // =========================================================
        // 【新增引擎：智能排版与动态星期/日期生成 (防溢出计算)】
        // =========================================================
        private BufferedImage generateGridDataImage(int year, int month) {
            BufferedImage dataImg = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gData = dataImg.createGraphics();

            // 1. 底层日历引擎：推演天数与第一天星期几
            Calendar cal = Calendar.getInstance();
            cal.set(year, month - 1, 1);
            int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
            int startDayOfWeek = cal.get(Calendar.DAY_OF_WEEK); // 1=Sun, 2=Mon...

            // 2. 动态防溢出机制：根据月份天数与起始日，替换表头并调整起始索引
            String[] headers = {"Mon.", "Tue.", "Wed.", "Thu.", "Fri.", "Sat.", "Sun."};
            int startIndex = (startDayOfWeek == Calendar.SUNDAY) ? 6 : startDayOfWeek - 2;

            if (daysInMonth == 30 && startDayOfWeek == Calendar.SUNDAY) {
                headers = new String[]{"Sun.", "Mon.", "Tue.", "Wed.", "Thu.", "Fri.", "Sat."};
                startIndex = 0;
            } else if (daysInMonth == 31) {
                if (startDayOfWeek == Calendar.SUNDAY) {
                    headers = new String[]{"Sun.", "Mon.", "Tue.", "Wed.", "Thu.", "Fri.", "Sat."};
                    startIndex = 0;
                } else if (startDayOfWeek == Calendar.SATURDAY) {
                    headers = new String[]{"Sat.", "Sun.", "Mon.", "Tue.", "Wed.", "Thu.", "Fri."};
                    startIndex = 0;
                }
            }

            // 严格继承固定的网格物理坐标
            int gridX1 = 30;
            int gridY1 = 163;
            int gridX2 = 1170;
            int gridY2 = 693;
            int gridW = gridX2 - gridX1, gridH = gridY2 - gridY1;

            // 3. 配置基础字体 (仓耳玄三M 30pt)
            Font fBase = (parent.cangErFont != null) ? parent.cangErFont.deriveFont(Font.PLAIN, 30f) : new Font("Microsoft YaHei", Font.BOLD, 30);
            Map<java.awt.font.TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(java.awt.font.TextAttribute.TRACKING, -0.005f); // 继承字间距 -5 的手感
            Font fHeader = fBase.deriveFont(attrs);

// =========================================================
            // [图层 A] 绘制星期表头 (自带 PS 原生投影与锐利化瘦身)
            // =========================================================
            java.awt.geom.Area headersArea = new java.awt.geom.Area();
            Graphics2D gDummy = dataImg.createGraphics();
            gDummy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gDummy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gDummy.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            gDummy.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gDummy.setFont(fHeader);
            java.awt.font.FontRenderContext frc = gDummy.getFontRenderContext();

            // 提取 7 个表头的矢量路径，精准定位到每一列的居中上方
            for (int i = 0; i < 7; i++) {
                int cx = gridX1 + (gridW * i / 7) + (gridW / 7) / 2;
                int cy = gridY1 - 10; // 距离表格顶线 10px

                String text = headers[i];
                java.awt.font.TextLayout tl = new java.awt.font.TextLayout(text, fHeader, frc);
                int tw = gDummy.getFontMetrics().stringWidth(text);
                Shape shape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx - tw / 2.0, cy));
                headersArea.add(new java.awt.geom.Area(shape));
            }
            gDummy.dispose();

            // 完全继承大月份文字的特效参数，绝不篡改
            int angle = 102;
            int distance = 4;
            int size = 10;
            double spread = 0.04;
            float thinStrokeWidth = 0.7f; // 继承瘦身刻刀

            // 生成黑色投影
            int dx = (int) Math.round(-distance * Math.cos(Math.toRadians(angle)));
            int dy = (int) Math.round(distance * Math.sin(Math.toRadians(angle)));

            BufferedImage shadowImg = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gShadow = shadowImg.createGraphics();
            gShadow.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gShadow.translate(dx, dy);

            float strokeWidth = size * (float)spread * 2.0f;
            if (strokeWidth > 0) {
                gShadow.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gShadow.setColor(Color.BLACK);
                gShadow.draw(headersArea);
            }
            gShadow.setColor(Color.BLACK);
            gShadow.fill(headersArea);
            gShadow.dispose();

            if (size > 0) shadowImg = fastAlphaBlur(shadowImg, size / 2);
            gData.drawImage(shadowImg, 0, 0, null); // 印上阴影

// 生成纯白瘦身主体
            BufferedImage pureTextImg = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gPure = pureTextImg.createGraphics();
            gPure.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gPure.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gPure.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            gPure.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gPure.setColor(Color.WHITE);
            gPure.fill(headersArea);

            if (thinStrokeWidth > 0) {
                gPure.setComposite(AlphaComposite.Clear);
                gPure.setStroke(new BasicStroke(thinStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gPure.draw(headersArea);
            }
            gPure.dispose();
            gData.drawImage(pureTextImg, 0, 0, null); // 印上白字

// =========================================================
            // [图层 B] 绘制具体日期 (深棕色 #613E00，无投影，锐利抗锯齿 + 独立瘦身)
            // =========================================================
            // ⚙️ 【新增：日期专属瘦身参数】
            float dateThinStrokeWidth = 0.6f; // 👈 修改这里：控制棕色日期的粗细，值越大越细，填 0 则不瘦身

            java.awt.geom.Area datesArea = new java.awt.geom.Area();
            Graphics2D gDummyDate = dataImg.createGraphics();
            gDummyDate.setFont(fHeader);
            java.awt.font.FontRenderContext frcDate = gDummyDate.getFontRenderContext();
            FontMetrics fmDate = gDummyDate.getFontMetrics(fHeader);

            for (int day = 1; day <= daysInMonth; day++) {
                int col = (startIndex + day - 1) % 7;
                int row = (startIndex + day - 1) / 7;

                // 严谨计算等分坐标
                int cellX = gridX1 + (gridW * col / 7);
                int cellW_actual = (gridW * (col + 1) / 7) - (gridW * col / 7);
                int cellY = gridY1 + (gridH * row / 5);

                int cx = cellX + cellW_actual / 2;
                int cy = cellY + 34; // 居中偏上部，保留你的原坐标！

                String dayStr = String.valueOf(day);
                int tw = fmDate.stringWidth(dayStr);

                // 不再直接画文字，而是提取矢量路径以支持高级透明瘦身
                java.awt.font.TextLayout tlDate = new java.awt.font.TextLayout(dayStr, fHeader, frcDate);
                Shape shapeDate = tlDate.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx - tw / 2.0, cy));
                datesArea.add(new java.awt.geom.Area(shapeDate));
            }
            gDummyDate.dispose();

            // 【核心：双图层魔法瘦身算法】
            BufferedImage pureDateImg = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gPureDate = pureDateImg.createGraphics();
            gPureDate.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // ⚙️ 【平滑锐利进化】：抛弃 DEFAULT 的严重锯齿感，换用最高质量的平滑抗锯齿 + 分数级度量，保证圆润且锐利！
            gPureDate.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gPureDate.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            gPureDate.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gPureDate.setColor(new Color(0x613e00)); // 指定的深棕色

            // 步骤 A：画出正常粗细的棕色字
            gPureDate.fill(datesArea);

            if (dateThinStrokeWidth > 0) {
                // 步骤 B：使用 Alpha 透明刻刀，沿着边缘把肥肉切掉！
                gPureDate.setComposite(AlphaComposite.Clear);
                gPureDate.setStroke(new BasicStroke(dateThinStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gPureDate.draw(datesArea);
            }
            gPureDate.dispose();

// 步骤 C：把瘦完身的纯净透明图层盖回日历数据大画布
            gData.drawImage(pureDateImg, 0, 0, null);

            // =========================================================
            // [图层 C] 绘制日程内部文本 (OppoSans，平滑锐利引擎，细分间距 + 独立瘦身 + 胶囊色块)
            // =========================================================

            // ⚙️ 【排版与间距全局控制台 (任你调整！)】
            // 1. 【整体起始高度】：控制文字距离格子顶线的距离 (总纵坐标)。
            int startOffsetY = 45;

            // 2. 【大模块间距】：例如两张专辑之间，或专辑与单曲之间的独立空隙。
            int gapBetweenModules = 1;

            // 3. 🎯 【单曲内部专属独立间距】
            int gapNameToRemoved = 3;  // "单曲主名(轮换)" 到 "下架歌曲" 之间的间距
            int gapRemovedToAdded = 0; // "下架歌曲" 到 "上架歌曲" 之间的间距

            // 4. 【换行间距】：当字数太多内部敲了回车换行时，上下两行字的紧密程度。
            int lineSpacing = 1;

            // 5. 🎯 【正文专属瘦身刻刀】
            float contentThinStrokeWidth = 0.4f; // 👈 修改这里：控制专辑、轮换等文字的粗细。值越大越细，0为不瘦身。

// 6. 🎯 【新增：大字背后的胶囊背景框参数】
            int titleBoxWidth = 153;  // 胶囊背景框的固定长度(宽)
            int titleBoxHeight = 11;  // 胶囊背景框的固定高度
            int titleBoxOffsetX = 1;  // 👈 新增：胶囊背景框的左右微调（正数往右移，负数往左移，用来平衡视觉中心）
            int titleBoxOffsetY = 2; // 胶囊背景框的上下微调（负数往上移，用来对齐文字视觉中心）
            int titleBoxArc = 5;     // 控制圆角弧度！(填与高度相等的23就是完美半圆，填 10 是微圆角，填 0 是绝对的直角矩形)
// 【核心：创建正文专属透明图层】防止瘦身刻刀破坏底层网格和背景！
            BufferedImage contentImg = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gContent = contentImg.createGraphics();
            gContent.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gContent.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gContent.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            gContent.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // ⚙️ 【平滑修复】：为底层的 gData 画笔也开启抗锯齿！
            // 因为彩色胶囊是直接画在 gData 上的，补上这句代码，圆角瞬间恢复 PS 级别的丝滑。
            gData.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            java.awt.font.FontRenderContext frcContent = gContent.getFontRenderContext();

            for (int day = 1; day <= daysInMonth; day++) {
                String dateKey = String.format("%04d-%02d-%02d", year, month, day);
                DayData data = calendarDataMap.get(dateKey);
                if (data == null || (data.albums.isEmpty() && data.singles.isEmpty())) continue;

                int col = (startIndex + day - 1) % 7;
                int row = (startIndex + day - 1) / 7;
                int cellX = gridX1 + (gridW * col / 7);
                int cellW_actual = (gridW * (col + 1) / 7) - (gridW * col / 7);
                int cellY = gridY1 + (gridH * row / 5);
                int cx = cellX + cellW_actual / 2;

                // 接入全局起始高度
                int currentY = cellY + startOffsetY;

// 1. 优先画专辑
                for (AlbumData album : data.albums) {
                    Font primaryF = (parent.oppoSansFont != null) ? parent.oppoSansFont.deriveFont(Font.PLAIN, (float)album.size) : new Font("Adobe Heiti Std B", Font.PLAIN, album.size);
                    gContent.setFont(primaryF);
                    FontMetrics fm = gContent.getFontMetrics(primaryF);

                    String[] names = {album.name1, album.name2};
                    String[] types = {album.type1, album.type2}; // 【精准抓取】匹配当前专辑所属的下拉框类型

                    for (int i = 0; i < names.length; i++) {
                        String n = names[i];
                        String type = types[i];

                        if (n == null || n.trim().isEmpty()) continue;
                        String text = n.trim();
                        if (!text.startsWith("《")) text = "《" + text;
                        if (!text.endsWith("》")) text = text + "》";

                        for (String line : text.split("\n")) {
                            if (line.isEmpty()) continue;

                            // 🌟 核心：使用逐字混排引擎，完美复刻 PS 逻辑，生僻字单独使用 Adobe 黑体 Std
                            java.awt.font.TextLayout tl = createMixedTextLayout(line, parent.oppoSansFont, "Adobe Heiti Std B", (float)album.size, frcContent);
                            int tw = (int) Math.round(tl.getAdvance());
                            currentY += fm.getAscent();

                            // 【新增底层图层穿透】：在大画布(gData)上绘制底色胶囊，保证透明刻刀不被破坏
                            Color boxColor = null;
                            if ("常规专辑".equals(type)) boxColor = new Color(0xddfd00);
                            else if ("主线专辑".equals(type)) boxColor = new Color(0xfdd000);
                            else if ("联动专辑".equals(type)) boxColor = new Color(0x36fd00);

                            if (boxColor != null) {
                                // 接入水平微调开关
                                int bx = cx - titleBoxWidth / 2 + titleBoxOffsetX;
                                int by = currentY - (fm.getAscent() / 2) - (titleBoxHeight / 2) + titleBoxOffsetY;
                                gData.setColor(boxColor);
                                // 接入自定义圆角参数
                                gData.fillRoundRect(bx, by, titleBoxWidth, titleBoxHeight, titleBoxArc, titleBoxArc);
                            }

                            // 提取混排字体的矢量轮廓
                            Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx - tw / 2.0, currentY));

                            gContent.setComposite(AlphaComposite.SrcOver);
                            gContent.setColor(Color.BLACK);
                            gContent.fill(textShape);

                            if (contentThinStrokeWidth > 0) {
                                gContent.setComposite(AlphaComposite.Clear);
                                gContent.setStroke(new BasicStroke(contentThinStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                gContent.draw(textShape);
                            }

                            currentY += fm.getDescent() + lineSpacing; // 接入换行间距
                        }
                        currentY += gapBetweenModules; // 接入模块间距
                    }
                }

                // 2. 画单曲
                for (SingleData single : data.singles) {
                    boolean hasAlbum = !data.albums.isEmpty();

// 单曲主名 (如《吼吼点唱机》轮换) - 只有在没有专辑时才显示
                    if (!hasAlbum && single.name != null && !single.name.trim().isEmpty()) {
                        String sNameText = single.name.trim();
                        Font primaryF = (parent.oppoSansFont != null) ? parent.oppoSansFont.deriveFont(Font.PLAIN, (float)single.nameSize) : new Font("Adobe Heiti Std B", Font.PLAIN, single.nameSize);
                        gContent.setFont(primaryF);
                        FontMetrics fm = gContent.getFontMetrics(primaryF);
                        for (String line : sNameText.split("\n")) {
                            if (line.isEmpty()) continue;

                            java.awt.font.TextLayout tl = createMixedTextLayout(line, parent.oppoSansFont, "Adobe Heiti Std B", (float)single.nameSize, frcContent);
                            int tw = (int) Math.round(tl.getAdvance());
                            currentY += fm.getAscent();

// 【新增底层图层穿透】：绘制点唱机专属粉色固定长宽胶囊
                            // 接入水平微调开关
                            int bx = cx - titleBoxWidth / 2 + titleBoxOffsetX;
                            int by = currentY - (fm.getAscent() / 2) - (titleBoxHeight / 2) + titleBoxOffsetY;
                            gData.setColor(new Color(0xff89d7));
                            // 接入自定义圆角参数
                            gData.fillRoundRect(bx, by, titleBoxWidth, titleBoxHeight, titleBoxArc, titleBoxArc);

                            Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx - tw / 2.0, currentY));

                            gContent.setComposite(AlphaComposite.SrcOver);
                            gContent.setColor(Color.BLACK);
                            gContent.fill(textShape);

                            if (contentThinStrokeWidth > 0) {
                                gContent.setComposite(AlphaComposite.Clear);
                                gContent.setStroke(new BasicStroke(contentThinStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                gContent.draw(textShape);
                            }

                            currentY += fm.getDescent() + lineSpacing;
                        }
                        currentY += gapNameToRemoved; // 🎯 正常情况：主标题和下架之间的间距

                    }else if (hasAlbum && ((single.removed != null && !single.removed.trim().isEmpty()) || (single.added != null && !single.added.trim().isEmpty()))) {
                        // 智能间距接管
                        currentY = currentY - gapBetweenModules + gapNameToRemoved;
                    }

// 下架歌曲 (取消换行支持，强制单行显示)
                    if (single.removed != null && !single.removed.trim().isEmpty()) {
// 【强制单行】：将输入文本里的所有回车换行符替换为空格
                        String singleLineText = single.removed.replace("\n", " ").trim();

                        Font primaryF = (parent.oppoSansFont != null) ? parent.oppoSansFont.deriveFont(Font.PLAIN, (float)single.removedSize) : new Font("Adobe Heiti Std B", Font.PLAIN, single.removedSize);
                        gContent.setFont(primaryF);
                        FontMetrics fm = gContent.getFontMetrics(primaryF);

                        java.awt.font.TextLayout tl = createMixedTextLayout(singleLineText, parent.oppoSansFont, "Adobe Heiti Std B", (float)single.removedSize, frcContent);
                        int tw = (int) Math.round(tl.getAdvance());
                        currentY += fm.getAscent();

                        Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx - tw / 2.0, currentY));

                        gContent.setComposite(AlphaComposite.SrcOver);
                        gContent.setColor(new Color(0x0061bc)); // 深蓝色
                        gContent.fill(textShape); // 无刻刀

                        currentY += fm.getDescent() + lineSpacing;
                        currentY += gapRemovedToAdded; // 🎯 完美生效：下架和上架之间的间距
                    }

// 上架歌曲 (取消换行支持，强制单行显示)
                    if (single.added != null && !single.added.trim().isEmpty()) {
// 【强制单行】：将输入文本里的所有回车换行符替换为空格
                        String singleLineText = single.added.replace("\n", " ").trim();

                        Font primaryF = (parent.oppoSansFont != null) ? parent.oppoSansFont.deriveFont(Font.PLAIN, (float)single.addedSize) : new Font("Adobe Heiti Std B", Font.PLAIN, single.addedSize);
                        gContent.setFont(primaryF);
                        FontMetrics fm = gContent.getFontMetrics(primaryF);

                        java.awt.font.TextLayout tl = createMixedTextLayout(singleLineText, parent.oppoSansFont, "Adobe Heiti Std B", (float)single.addedSize, frcContent);
                        int tw = (int) Math.round(tl.getAdvance());
                        currentY += fm.getAscent();

                        Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx - tw / 2.0, currentY));

                        gContent.setComposite(AlphaComposite.SrcOver);
                        gContent.setColor(new Color(0xf64c01)); // 亮橙色
                        gContent.fill(textShape); // 无刻刀

                        currentY += fm.getDescent() + lineSpacing;
                        currentY += gapBetweenModules; // 模块结束空隙
                    }
                }
            }

            // =========================================================
            // [图层 D] 绘制左下角动态图例 (智能按需扫描与显示)
            // =========================================================
// 1. 局部数据雷达：严格绑定当前显示的年月进行扫描！
            boolean hasMain = false, hasReg = false, hasCollab = false;
            boolean hasRem = false, hasAdd = false;

            // 提取当前正在渲染的年份和月份前缀（格式如 "2026-04"）
            String currentMonthPrefix = String.format("%04d-%02d", year, month);

            for (Map.Entry<String, DayData> entry : calendarDataMap.entrySet()) {
                // 🌟 【核心修复：跨月隔离】：如果这条数据不是当前页面的年月，直接无视！
                if (!entry.getKey().startsWith(currentMonthPrefix)) continue;

                DayData dData = entry.getValue();
                for (AlbumData a : dData.albums) {
                    // 【核心Bug修复】：必须同时验证名字不为空！
                    // 否则下拉框默认停留的“常规专辑”属性，会变成触发幽灵图例的元凶。
                    boolean valid1 = (a.name1 != null && !a.name1.trim().isEmpty());
                    boolean valid2 = (a.name2 != null && !a.name2.trim().isEmpty());

                    if (valid1) {
                        if ("主线专辑".equals(a.type1)) hasMain = true;
                        else if ("常规专辑".equals(a.type1)) hasReg = true;
                        else if ("联动专辑".equals(a.type1)) hasCollab = true;
                    }
                    if (valid2) {
                        if ("主线专辑".equals(a.type2)) hasMain = true;
                        else if ("常规专辑".equals(a.type2)) hasReg = true;
                        else if ("联动专辑".equals(a.type2)) hasCollab = true;
                    }
                }
                for (SingleData s : dData.singles) {
                    if (s.removed != null && !s.removed.trim().isEmpty()) hasRem = true;
                    if (s.added != null && !s.added.trim().isEmpty()) hasAdd = true;
                }
            }

// 2. ⚙️ 【图例排版专属控制台】(📍 你要找的坐标调整区在这里！)
            int legendStartX = gridX1 + 20; // 👈 【左列横坐标】：调整左边“专辑列”距离左侧网格边缘的位置。
            int legendStartY = gridY2 + 15;  // 👈 【总纵轴坐标】：调整整个图例组距离上方表格底线的距离（数字越大越往下沉）。
            int legendRowH = 25;             // 👈 【上下行距】：控制图例中上下两行之间的垂直空隙。
            int legendColSpace = 130;        // 👈 【两列间距】：控制右边“单曲列”距离左边“专辑列”有多远！（改小点这两列就会靠得更紧凑）。

            // 🌟 【致命隐形Bug修复】：强行重置画笔为“正常绘制”模式！
            // 防止画笔残留为“透明刻刀(Clear)”模式，导致图例文字变成隐形的橡皮擦效果。
            gContent.setComposite(AlphaComposite.SrcOver);

            Font legendFont = (parent.oppoSansFont != null) ? parent.oppoSansFont.deriveFont(Font.BOLD, 14f) : new Font("Microsoft YaHei", Font.BOLD, 14);
            gContent.setFont(legendFont);

            int rowCol1 = 0;
            // --- 左列：专辑类 (绘制 26x12 的胶囊) ---
            if (hasMain) {
                Color c = new Color(0xfdd000);
                int y = legendStartY + rowCol1 * legendRowH;
                gData.setColor(c); gData.fillRoundRect(legendStartX, y, 26, 12, 6, 6);
                gContent.setColor(c); gContent.drawString("— 主线专辑", legendStartX + 35, y + 11);
                rowCol1++;
            }
            if (hasReg) {
                Color c = new Color(0xddfd00);
                int y = legendStartY + rowCol1 * legendRowH;
                gData.setColor(c); gData.fillRoundRect(legendStartX, y, 26, 12, 6, 6);
                gContent.setColor(c); gContent.drawString("— 常规专辑", legendStartX + 35, y + 11);
                rowCol1++;
            }
            if (hasCollab) {
                Color c = new Color(0x36fd00);
                int y = legendStartY + rowCol1 * legendRowH;
                gData.setColor(c); gData.fillRoundRect(legendStartX, y, 26, 12, 6, 6);
                gContent.setColor(c); gContent.drawString("— 联动专辑", legendStartX + 35, y + 11);
                rowCol1++;
            }

            int rowCol2 = 0;
            int col2X = legendStartX + legendColSpace;
            // --- 右列：歌曲上下架 (绘制 14x14 的正圆) ---
            if (hasRem) {
                Color c = new Color(0x0061bc);
                int y = legendStartY + rowCol2 * legendRowH;
                gData.setColor(c); gData.fillOval(col2X + 6, y - 1, 14, 14); // 画正圆形
                gContent.setColor(c); gContent.drawString("— 暂时下架", col2X + 35, y + 11);
                rowCol2++;
            }
            if (hasAdd) {
                Color c = new Color(0xf64c01);
                int y = legendStartY + rowCol2 * legendRowH;
                gData.setColor(c); gData.fillOval(col2X + 6, y - 1, 14, 14); // 画正圆形
                gContent.setColor(c); gContent.drawString("— 暂时上架", col2X + 35, y + 11);
                rowCol2++;
            }

            gContent.dispose();

            // 🌟 【核心修复】：将包含所有正文和图例文字的透明图层，狠狠地盖回大画布上！
            gData.drawImage(contentImg, 0, 0, null);

            gData.dispose();
            return dataImg;
        }
        // 【新增：核心坐标系转换】窗口无论多小，鼠标动作完美映射回 1200x800
        private java.awt.Point getLogicalPoint(java.awt.Point p) {
            double scaleX = (double) getWidth() / CW;
            double scaleY = (double) getHeight() / CH;
            double scale = Math.min(scaleX, scaleY);
            int ox = (getWidth() - (int) (CW * scale)) / 2;
            int oy = (getHeight() - (int) (CH * scale)) / 2;
            int lx = (int) ((p.x - ox) / scale);
            int ly = (int) ((p.y - oy) / scale);
            return new java.awt.Point(lx, ly);
        }

        public CalendarCanvas() {
            setBackground(new Color(25, 25, 25));

            new DropTarget(this, new DropTargetAdapter() {
                @Override
                public void drop(DropTargetDropEvent dtde) {
                    try {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        java.util.List<File> files = (java.util.List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) {
                            File f = files.get(0);

                            // 🌟 【新增引擎：检测到日历专属 .ree 工程文件，直接进入还原模式】
                            if (f.getName().toLowerCase().endsWith(".ree")) {
                                loadProject(f);
                                return;
                            }

                            if (f.getName().toLowerCase().matches(".*\\.(png|jpg|jpeg)")) {
                                rawBg = ImageIO.read(f);
                                imgScale = Math.max((double) CW / rawBg.getWidth(), (double) CH / rawBg.getHeight());
                                imgX = (CW - rawBg.getWidth() * imgScale) / 2.0;
                                imgY = (CH - rawBg.getHeight() * imgScale) / 2.0;

                                blurBg = null;
                                debounceTimer.restart();
                                repaint();
                            }
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                }
            });

// =========================================================
            // 鼠标交互与网格雷达：精确捕获用户的每一次点击
            // =========================================================
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    lastDragPoint = getLogicalPoint(e.getPoint());
                }
                public void mouseClicked(MouseEvent e) {
                    // 🌟 【新增拦截边界】：如果还没拖入背景图片，无论点击哪里都被当做无效！
                    if (rawBg == null) return;

                    java.awt.Point lp = getLogicalPoint(e.getPoint());
                    // 获取网格四极坐标
                    int gridX1 = 30, gridY1 = 163, gridX2 = 1170, gridY2 = 693;
                    int gridW = gridX2 - gridX1, gridH = gridY2 - gridY1;

                    // 【雷达锁定】如果点击在网格范围内
                    if (lp.x >= gridX1 && lp.x <= gridX2 && lp.y >= gridY1 && lp.y <= gridY2) {
                        int col = (lp.x - gridX1) * 7 / gridW;
                        int row = (lp.y - gridY1) * 5 / gridH;

                        // 推演该格子属于几号
                        int year = (int)yearCombo.getSelectedItem();
                        int month = (int)monthCombo.getSelectedItem();
                        Calendar cal = Calendar.getInstance();
                        cal.set(year, month - 1, 1);
                        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                        int startDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                        int startIndex = (startDayOfWeek == Calendar.SUNDAY) ? 6 : startDayOfWeek - 2;

                        // 防溢出阵型换算
                        if (daysInMonth == 30 && startDayOfWeek == Calendar.SUNDAY) startIndex = 0;
                        else if (daysInMonth == 31 && (startDayOfWeek == Calendar.SUNDAY || startDayOfWeek == Calendar.SATURDAY)) startIndex = 0;

                        int clickedCellIndex = row * 7 + col;
                        int day = clickedCellIndex - startIndex + 1;

// 只有点中真正存在的日期，才触发填写！
                        if (day >= 1 && day <= daysInMonth) {
                            openDayDataDialog(year, month, day);
                        }
                    }
                }
            });

            // 【核心语法修复】：将日历图片的拖拽和缩放监听器移回构造函数内部！
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (lastDragPoint == null || rawBg == null) return;
                    java.awt.Point lp = getLogicalPoint(e.getPoint());
                    imgX += lp.x - lastDragPoint.x;
                    imgY += lp.y - lastDragPoint.y;
                    lastDragPoint = lp;
                    enforceBounds();
                    repaint();
                }
            });

            addMouseWheelListener(e -> {
                if (rawBg == null) return;
                double oldScale = imgScale;
                imgScale *= (e.getWheelRotation() < 0) ? 1.05 : 0.95;

                double minScale = Math.max((double) CW / rawBg.getWidth(), (double) CH / rawBg.getHeight());
                if (imgScale < minScale) imgScale = minScale;

                java.awt.Point lp = getLogicalPoint(e.getPoint());
                imgX = lp.x - (lp.x - imgX) * (imgScale / oldScale);
                imgY = lp.y - (lp.y - imgY) * (imgScale / oldScale);
                enforceBounds();
                repaint();
            });
        } // <--- 这里才是真实的构造函数结束大括号

        // =========================================================
        // 【高级录入向导：模块化排版选择与填写 UI】
        // =========================================================
        private void openDayDataDialog(int year, int month, int day) {
            String dateKey = String.format("%04d-%02d-%02d", year, month, day);
            DayData data = calendarDataMap.getOrDefault(dateKey, new DayData());

            JDialog dialog = new JDialog((java.awt.Frame) SwingUtilities.getWindowAncestor(this), month + "月" + day + "日 - 日程排版", true);
            dialog.setLayout(new BorderLayout());

            JPanel mainPanel = new JPanel();
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            JCheckBox chkAlbum = new JCheckBox("添加专辑");
            JCheckBox chkSingle = new JCheckBox("添加单曲");
            chkAlbum.setSelected(!data.albums.isEmpty());
            chkSingle.setSelected(!data.singles.isEmpty());

            JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            typePanel.add(new JLabel("请选择本格子包含的模块: "));
            typePanel.add(chkAlbum); typePanel.add(chkSingle);
            mainPanel.add(typePanel);

            // --- 专辑排版面板 (全新极致紧凑版) ---
            JPanel albumPanel = new JPanel();
            albumPanel.setLayout(new BoxLayout(albumPanel, BoxLayout.Y_AXIS));
            albumPanel.setBorder(BorderFactory.createTitledBorder("【专辑排版区】"));

            JSpinner albumSize = new JSpinner(new SpinnerNumberModel(17, 8, 100, 1));
            ((JSpinner.DefaultEditor) albumSize.getEditor()).getTextField().setHorizontalAlignment(JTextField.LEFT);

            // 【专辑1】标题栏横向紧凑布局
            JPanel a1TitlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            a1TitlePanel.add(new JLabel("专辑1 (必填) 类型: "));
            JComboBox<String> type1Combo = new JComboBox<>(new String[]{"常规专辑", "主线专辑", "联动专辑"});
            a1TitlePanel.add(type1Combo);

            JTextArea album1 = new JTextArea(2, 20); album1.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            JPanel a1Wrap = new JPanel(new BorderLayout(0, 2));
            a1Wrap.add(a1TitlePanel, BorderLayout.NORTH);
            a1Wrap.add(new JScrollPane(album1), BorderLayout.CENTER);
            a1Wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

            // 【专辑2】标题栏横向紧凑布局
            JPanel a2TitlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            a2TitlePanel.add(new JLabel("专辑2 (选填) 类型: "));
            JComboBox<String> type2Combo = new JComboBox<>(new String[]{"常规专辑", "主线专辑", "联动专辑"});
            a2TitlePanel.add(type2Combo);

            JTextArea album2 = new JTextArea(2, 20); album2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            JPanel a2Wrap = new JPanel(new BorderLayout(0, 2));
            a2Wrap.add(a2TitlePanel, BorderLayout.NORTH);
            a2Wrap.add(new JScrollPane(album2), BorderLayout.CENTER);
            a2Wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

            JPanel asPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            asPanel.add(new JLabel("两张专辑统一字号: ")); asPanel.add(albumSize);

            albumPanel.add(a1Wrap);
            albumPanel.add(a2Wrap);
            albumPanel.add(asPanel);

            // 回显读取数据
            if (!data.albums.isEmpty()) {
                AlbumData ad = data.albums.get(0);
                album1.setText(ad.name1); type1Combo.setSelectedItem(ad.type1);
                album2.setText(ad.name2); type2Combo.setSelectedItem(ad.type2);
                albumSize.setValue(ad.size);
            }

            // --- 单曲排版面板 (全新极致紧凑版) ---
            JPanel singlePanel = new JPanel();
            singlePanel.setLayout(new BoxLayout(singlePanel, BoxLayout.Y_AXIS));
            singlePanel.setBorder(BorderFactory.createTitledBorder("【单曲排版区】"));

            // 【将字号和标签塞在同一行，节省大量空间】
            JTextArea singleName = new JTextArea("《吼吼点唱机》轮换", 2, 20); singleName.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            JSpinner sNameSize = new JSpinner(new SpinnerNumberModel(17, 8, 100, 1));
            ((JSpinner.DefaultEditor) sNameSize.getEditor()).getTextField().setHorizontalAlignment(JTextField.LEFT);
            JPanel snp = new JPanel(new BorderLayout(0, 2));
            JPanel snpTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            snpTop.add(new JLabel("单曲名 (字号: ")); snpTop.add(sNameSize); snpTop.add(new JLabel("):"));
            snp.add(snpTop, BorderLayout.NORTH);
            snp.add(new JScrollPane(singleName), BorderLayout.CENTER);
            snp.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            singlePanel.add(snp);

            JTextArea singleRem = new JTextArea(2, 20); singleRem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            JSpinner sRemSize = new JSpinner(new SpinnerNumberModel(11, 8, 100, 1));
            ((JSpinner.DefaultEditor) sRemSize.getEditor()).getTextField().setHorizontalAlignment(JTextField.LEFT);
            JPanel srp = new JPanel(new BorderLayout(0, 2));
            JPanel srpTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            srpTop.add(new JLabel("下架歌曲 (字号: ")); srpTop.add(sRemSize); srpTop.add(new JLabel("):"));
            srp.add(srpTop, BorderLayout.NORTH);
            srp.add(new JScrollPane(singleRem), BorderLayout.CENTER);
            srp.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            singlePanel.add(srp);

            JTextArea singleAdd = new JTextArea(2, 20); singleAdd.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            JSpinner sAddSize = new JSpinner(new SpinnerNumberModel(12, 8, 100, 1));
            ((JSpinner.DefaultEditor) sAddSize.getEditor()).getTextField().setHorizontalAlignment(JTextField.LEFT);
            JPanel sap = new JPanel(new BorderLayout(0, 2));
            JPanel sapTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            sapTop.add(new JLabel("上架歌曲 (字号: ")); sapTop.add(sAddSize); sapTop.add(new JLabel("):"));
            sap.add(sapTop, BorderLayout.NORTH);
            sap.add(new JScrollPane(singleAdd), BorderLayout.CENTER);
            sap.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            singlePanel.add(sap);

            if (!data.singles.isEmpty()) {
                SingleData sd = data.singles.get(0);
                singleName.setText(sd.name); sNameSize.setValue(sd.nameSize);
                singleRem.setText(sd.removed); sRemSize.setValue(sd.removedSize);
                singleAdd.setText(sd.added); sAddSize.setValue(sd.addedSize);
            }

            albumPanel.setVisible(chkAlbum.isSelected());
            singlePanel.setVisible(chkSingle.isSelected());

            // 【自适应呼吸魔法】：勾选瞬间触发界面的物理重绘与包裹
            chkAlbum.addActionListener(e -> { albumPanel.setVisible(chkAlbum.isSelected()); dialog.pack(); });
            chkSingle.addActionListener(e -> { singlePanel.setVisible(chkSingle.isSelected()); dialog.pack(); });

            mainPanel.add(albumPanel);
            mainPanel.add(singlePanel);

            // 抛弃之前写死的 600 高度庞大画布，改为绝对贴合内容的空白面板
            JScrollPane mainScroll = new JScrollPane(mainPanel);
            mainScroll.setBorder(null);
            dialog.add(mainScroll, BorderLayout.CENTER);

            JButton btnOk = new JButton("保存并在日历上渲染");
            btnOk.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
            btnOk.addActionListener(e -> {
                // 【🛡️ 核心：表单逻辑校验拦截】
                if (chkAlbum.isSelected() && album1.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(dialog, "请填写【专辑 1】的名称！此为必填项。", "数据验证拦截", JOptionPane.WARNING_MESSAGE);
                    return; // 直接拦截，拒绝退出弹窗
                }

                DayData newData = new DayData();
                if (chkAlbum.isSelected()) {
                    AlbumData ad = new AlbumData();
                    ad.type1 = (String) type1Combo.getSelectedItem();
                    ad.type2 = (String) type2Combo.getSelectedItem();
                    ad.name1 = album1.getText().trim();
                    ad.name2 = album2.getText().trim();
                    ad.size = (int) albumSize.getValue();
                    newData.albums.add(ad);
                }
                if (chkSingle.isSelected()) {
                    SingleData sd = new SingleData();
                    sd.name = singleName.getText().trim(); sd.nameSize = (int) sNameSize.getValue();
                    sd.removed = singleRem.getText().trim(); sd.removedSize = (int) sRemSize.getValue();
                    sd.added = singleAdd.getText().trim(); sd.addedSize = (int) sAddSize.getValue();
                    newData.singles.add(sd);
                }
                calendarDataMap.put(dateKey, newData);
                clearTextCache(); repaint();
                dialog.dispose();
            });
            JPanel bottomPanel = new JPanel(); bottomPanel.add(btnOk);
            dialog.add(bottomPanel, BorderLayout.SOUTH);

            // 【核心】：利用 pack() 让系统瞬间计算最小所需物理空间，消灭白边！
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        }

        private void enforceBounds() {
            if (rawBg == null) return;
            double curW = rawBg.getWidth() * imgScale;
            double curH = rawBg.getHeight() * imgScale;
            if (imgX > 0) imgX = 0;
            if (imgY > 0) imgY = 0;
            if (imgX + curW < CW) imgX = CW - curW;
            if (imgY + curH < CH) imgY = CH - curH;
        }

        public void clearBlur() { blurBg = null; repaint(); }

        public void triggerSurfaceBlur(int radius, int threshold) {
            if (rawBg == null) return;
            statusLabel.setText("渲染中...");
            isBlurring = true; repaint();
            parent.taskQueue.submit(() -> {
                BufferedImage result = MczParser.applySurfaceBlur(rawBg, radius, threshold);
                SwingUtilities.invokeLater(() -> {
                    blurBg = result;
                    isBlurring = false;
                    statusLabel.setText("就绪");
                    repaint();
                });
            });
        }

        // ================= [提取独立绘图逻辑，用于 UI 预览与图片导出] =================
        private void drawCalendarContent(Graphics2D g2d) {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (rawBg != null) {
                // [第 1 层] 绘制底层拖拽的图像
                BufferedImage drawImg = (blurBg != null && previewBox.isSelected()) ? blurBg : rawBg;
                g2d.drawImage(drawImg, (int) imgX, (int) imgY, (int)(rawBg.getWidth() * imgScale), (int)(rawBg.getHeight() * imgScale), null);

                // =========================================================
                // [第 2 层] 日历 5x7 网格绘制（只有拖入图片后才显示！）
                // =========================================================
                int gridX1 = 30;   // 左上角 X 坐标
                int gridY1 = 163;  // 左上角 Y 坐标
                int gridX2 = 1170; // 右下角 X 坐标
                int gridY2 = 693;  // 右下角 Y 坐标
                int gridW = gridX2 - gridX1, gridH = gridY2 - gridY1;

                // 白色透明背景遮罩
                g2d.setColor(new Color(255, 255, 255, 102));
                g2d.fillRect(gridX1, gridY1, gridW, gridH);

// 网格线
                g2d.setColor(new Color(0x02198d));
                g2d.setStroke(new BasicStroke(1.0f));
                for (int i = 0; i <= 5; i++) { int y = gridY1 + (gridH * i / 5); g2d.drawLine(gridX1, y, gridX2, y); }
                for (int j = 0; j <= 7; j++) { int x = gridX1 + (gridW * j / 7); g2d.drawLine(x, gridY1, x, gridY2); }

                // =========================================================
                // [第 2.5 层] 动态日期与星期表头 (防溢出智能排版)
                // =========================================================
                if (cachedGridDataImg == null) {
                    cachedGridDataImg = generateGridDataImage((int)yearCombo.getSelectedItem(), (int)monthCombo.getSelectedItem());
                }
                g2d.drawImage(cachedGridDataImg, 0, 0, null);

                // =========================================================
                // [第 3 层] 左上角年月排版与投影文字
                // =========================================================
                if (cachedMonthText == null) {
                    cachedMonthText = generateMonthTextImage((int)monthCombo.getSelectedItem());
                }

                int textLayerX = 0;
                int textLayerY = -54;
                g2d.drawImage(cachedMonthText, textLayerX, textLayerY, null);

            } else {
                // [无图片时的兜底层]
                g2d.setColor(new Color(65, 65, 65));
                g2d.fillRect(0, 0, CW, CH);
                g2d.setColor(new Color(200, 200, 200));
                g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 28));
                FontMetrics fm = g2d.getFontMetrics();
                String tip = "请将背景图片拖拽至程序内部";
                g2d.drawString(tip, (CW - fm.stringWidth(tip)) / 2, (CH + fm.getAscent()) / 2 - fm.getDescent());
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D gMain = (Graphics2D) g;

            // 1. 在后台渲染 1200x800 原生逻辑画板
            BufferedImage buffer = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = buffer.createGraphics();

            // 调用底层画笔
            drawCalendarContent(g2d);

            // 只有在 UI 界面上才画这层“渲染中...”的黑色蒙版
            if (isBlurring) {
                g2d.setColor(new Color(0, 0, 0, 100)); g2d.fillRect(0, 0, CW, CH);
                g2d.setColor(Color.WHITE); g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
                g2d.drawString("远程处理图片中……", CW/2 - 180, CH/2 + 50);
            }
            g2d.dispose();

            // 2. 将后台画板完美等比缩放至当前界面大小
            double scaleX = (double) getWidth() / CW;
            double scaleY = (double) getHeight() / CH;
            double scale = Math.min(scaleX, scaleY);
            int drawW = (int) (CW * scale);
            int drawH = (int) (CH * scale);
            int ox = (getWidth() - drawW) / 2;
            int oy = (getHeight() - drawH) / 2;

            gMain.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gMain.drawImage(buffer, ox, oy, drawW, drawH, null);

            gMain.setColor(new Color(255, 255, 255, 50));
            gMain.drawRect(ox - 1, oy - 1, drawW + 1, drawH + 1);
        }

        public void exportImage() {
            // 导出功能现在也使用同一套底层画笔！真正做到所见即所得。
            BufferedImage exportBuffer = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = exportBuffer.createGraphics();
            drawCalendarContent(g2d);
            g2d.dispose();

            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("茶韵日历_导出.png"));
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    ImageIO.write(exportBuffer, "png", chooser.getSelectedFile());
                    JOptionPane.showMessageDialog(this, "日历导出成功！");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        // ================= [新增引擎：导出为 .ree 独立工程文件] =================
        public void saveProject() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("保存日历排版工程");
            chooser.setSelectedFile(new File("未命名日历.ree"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

            File outFile = chooser.getSelectedFile();
            if (!outFile.getName().toLowerCase().endsWith(".ree")) {
                outFile = new File(outFile.getAbsolutePath() + ".ree");
            }

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {
                Properties props = new Properties();

                // 1. 全局状态参数
                props.setProperty("year", String.valueOf(yearCombo.getSelectedItem()));
                props.setProperty("month", String.valueOf(monthCombo.getSelectedItem()));
                props.setProperty("radius", String.valueOf(radiusSlider.getValue()));
                props.setProperty("threshold", String.valueOf(thresholdSlider.getValue()));
                props.setProperty("previewSelected", String.valueOf(previewBox.isSelected()));
                props.setProperty("imgScale", String.valueOf(imgScale));
                props.setProperty("imgX", String.valueOf(imgX));
                props.setProperty("imgY", String.valueOf(imgY));

                // 2. 日历单元格数据 (calendarDataMap)
                int mapIndex = 0;
                for (Map.Entry<String, DayData> entry : calendarDataMap.entrySet()) {
                    String dateKey = entry.getKey();
                    DayData data = entry.getValue();
                    if (data.albums.isEmpty() && data.singles.isEmpty()) continue;

                    String pfx = "cell." + mapIndex + ".";
                    props.setProperty(pfx + "date", dateKey);

                    props.setProperty(pfx + "albumCount", String.valueOf(data.albums.size()));
                    for (int i = 0; i < data.albums.size(); i++) {
                        AlbumData ad = data.albums.get(i);
                        String apfx = pfx + "album." + i + ".";
                        props.setProperty(apfx + "type1", ad.type1);
                        props.setProperty(apfx + "type2", ad.type2);
                        props.setProperty(apfx + "name1", ad.name1.replace("\n", "<br>"));
                        props.setProperty(apfx + "name2", ad.name2.replace("\n", "<br>"));
                        props.setProperty(apfx + "size", String.valueOf(ad.size));
                    }

                    props.setProperty(pfx + "singleCount", String.valueOf(data.singles.size()));
                    for (int i = 0; i < data.singles.size(); i++) {
                        SingleData sd = data.singles.get(i);
                        String spfx = pfx + "single." + i + ".";
                        props.setProperty(spfx + "name", (sd.name == null ? "" : sd.name).replace("\n", "<br>"));
                        props.setProperty(spfx + "nameSize", String.valueOf(sd.nameSize));
                        props.setProperty(spfx + "removed", (sd.removed == null ? "" : sd.removed).replace("\n", "<br>"));
                        props.setProperty(spfx + "removedSize", String.valueOf(sd.removedSize));
                        props.setProperty(spfx + "added", (sd.added == null ? "" : sd.added).replace("\n", "<br>"));
                        props.setProperty(spfx + "addedSize", String.valueOf(sd.addedSize));
                    }
                    mapIndex++;
                }
                props.setProperty("cellCount", String.valueOf(mapIndex));

                // 3. 保存背景原图
                if (rawBg != null) {
                    zos.putNextEntry(new ZipEntry("rawBg.png"));
                    ImageIO.write(rawBg, "png", zos);
                    zos.closeEntry();
                }

                // 4. 将配置文件写入压缩包
                zos.putNextEntry(new ZipEntry("calendar_config.properties"));
                props.store(zos, "MczTool Calendar Project Config");
                zos.closeEntry();

                JOptionPane.showMessageDialog(this, "日历工程保存成功！\n日后可直接将此 .ree 文件拖入日历画板继续编辑。");
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "保存日历工程失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }

        // ================= [新增引擎：解析还原 .ree 独立工程文件] =================
        public void loadProject(File prjFile) {
            try (ZipFile zf = new ZipFile(prjFile)) {
                ZipEntry configEntry = zf.getEntry("calendar_config.properties");
                if (configEntry == null) throw new Exception("无效的日历工程文件格式：缺失配置文件。");

                Properties props = new Properties();
                try (InputStream is = zf.getInputStream(configEntry)) {
                    props.load(is);
                }

                // 1. 还原全局状态
                yearCombo.setSelectedItem(Integer.parseInt(props.getProperty("year", String.valueOf(Calendar.getInstance().get(Calendar.YEAR)))));
                monthCombo.setSelectedItem(Integer.parseInt(props.getProperty("month", "1")));

                radiusSlider.setValue(Integer.parseInt(props.getProperty("radius", "30")));
                thresholdSlider.setValue(Integer.parseInt(props.getProperty("threshold", "40")));
                previewBox.setSelected(Boolean.parseBoolean(props.getProperty("previewSelected", "true")));

                imgScale = Double.parseDouble(props.getProperty("imgScale", "1.0"));
                imgX = Double.parseDouble(props.getProperty("imgX", "0"));
                imgY = Double.parseDouble(props.getProperty("imgY", "0"));

                // 2. 读取底层背景图
                ZipEntry bgEntry = zf.getEntry("rawBg.png");
                if (bgEntry != null) {
                    rawBg = ImageIO.read(zf.getInputStream(bgEntry));
                } else {
                    rawBg = null;
                }
                blurBg = null; // 清除模糊缓存，等待重新渲染

                // 3. 还原单元格数据
                calendarDataMap.clear();
                int cellCount = Integer.parseInt(props.getProperty("cellCount", "0"));
                for (int i = 0; i < cellCount; i++) {
                    String pfx = "cell." + i + ".";
                    String dateKey = props.getProperty(pfx + "date");
                    if (dateKey == null) continue;

                    DayData data = new DayData();

                    int albumCount = Integer.parseInt(props.getProperty(pfx + "albumCount", "0"));
                    for (int j = 0; j < albumCount; j++) {
                        String apfx = pfx + "album." + j + ".";
                        AlbumData ad = new AlbumData();
                        ad.type1 = props.getProperty(apfx + "type1", "常规专辑");
                        ad.type2 = props.getProperty(apfx + "type2", "常规专辑");
                        ad.name1 = props.getProperty(apfx + "name1", "").replace("<br>", "\n");
                        ad.name2 = props.getProperty(apfx + "name2", "").replace("<br>", "\n");
                        ad.size = Integer.parseInt(props.getProperty(apfx + "size", "17"));
                        data.albums.add(ad);
                    }

                    int singleCount = Integer.parseInt(props.getProperty(pfx + "singleCount", "0"));
                    for (int j = 0; j < singleCount; j++) {
                        String spfx = pfx + "single." + j + ".";
                        SingleData sd = new SingleData();
                        sd.name = props.getProperty(spfx + "name", "").replace("<br>", "\n");
                        sd.nameSize = Integer.parseInt(props.getProperty(spfx + "nameSize", "17"));
                        sd.removed = props.getProperty(spfx + "removed", "").replace("<br>", "\n");
                        sd.removedSize = Integer.parseInt(props.getProperty(spfx + "removedSize", "11"));
                        sd.added = props.getProperty(spfx + "added", "").replace("<br>", "\n");
                        sd.addedSize = Integer.parseInt(props.getProperty(spfx + "addedSize", "12"));
                        data.singles.add(sd);
                    }

                    calendarDataMap.put(dateKey, data);
                }

                // 4. 触发重绘与重新模糊
                clearTextCache();
                debounceTimer.restart(); // 触发一次重绘与模糊
                repaint();
                JOptionPane.showMessageDialog(this, "日历工程还原成功！");

            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "读取日历工程失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
} // <--- CalendarPanel 结束的大括号
