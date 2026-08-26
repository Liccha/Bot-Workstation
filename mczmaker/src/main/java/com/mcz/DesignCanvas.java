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
import java.util.Properties;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import java.awt.dnd.*;
import java.util.List;
import java.awt.datatransfer.DataFlavor;
import java.util.zip.ZipFile;

/**
 * 设计画布 — 专辑封面排版引擎，支持模板切换、毛玻璃背景、渐变染色、蒙版与导出
 */
public class DesignCanvas extends JPanel {
    private final MczTool parent;

    private final int LOGICAL_WIDTH = 1920;
    private final int LOGICAL_HEIGHT = 817;

    private int bgOffsetX = 0;
    private int bgOffsetY = 0;
    private java.awt.Point lastDragPoint = null;

    // 【新增】图片自定义缩放倍率（1.0就是刚好填满不留黑边）
    private double customZoom = 1.0;

    private BufferedImage cachedBlurredBg = null;
    private int lastBlurLevel = -1;

    // 【新增】第三层：固定的顶部 Banner 原图
    private BufferedImage topBannerImage = null;
    // 【新增】第四层：顶部居中的灯光特效图
    private BufferedImage topLightImage = null;
    // 【新增】左上角的 Logo 与 Teacharm
    private BufferedImage logoImage = null;
    private BufferedImage teacharmImage = null;
    // 【新增】专辑封面底图
    private BufferedImage albumBaseImage = null;
    // 【新增】单首歌曲的底色板图
    private BufferedImage songBaseImage = null;

    // 【新增】控制大标题水平拖拽的核心变量
    private int titleCenterX = 745;
    private boolean isDraggingTitle = false;
    private Rectangle rectAlbumTitle = new Rectangle(); // 【新增】大标题的隐形碰撞箱

    // 🎯【新增】白色副标题的控制变量、坐标与碰撞箱
    private String albumSubTitleText = "茶韵新专辑上架";
    private int albumSubTitleSize = 60;
    private int subTitleX = 843;
    private int subTitleY = 307;
    private boolean isDraggingSubTitle = false;
    private Rectangle rectAlbumSubTitle = new Rectangle();

    // 【新增】记录当前是 5 首歌还是 6 首歌模板
    private int currentTemplateMode = 5;

    // 【决战：四边形蒙版固定物理坐标】保留基础参数，取消 final 允许动态右移
    private final int[] BASE_MASK_X = {1454, 1848, 1699, 1304};
    private final int[] BASE_MASK_Y = {65, 153, 756, 667};
    private int[] maskXPoints = BASE_MASK_X.clone();
    private int[] maskYPoints = BASE_MASK_Y.clone();
    private Polygon maskPolygon = new Polygon(maskXPoints, maskYPoints, 4);

    // 【决战：蒙版内部图片拖拽与缩放引擎】
    private BufferedImage maskCustomImage = null;
    private double maskImgScale = 1.0;
    private int maskImgX = 0;
    private int maskImgY = 0;
    private boolean isDraggingMaskImg = false;
    private BufferedImage cachedHDMaskImage = null; // 🎯 新增：蒙版大图的阶梯降采样缓存
    private double lastHDMaskScale = -1.0;

    // ================= [决战：组件化歌曲模块引擎 (支持无限复制)] =================
    private class SongModule {
        int id;
        double baseX;
        java.awt.geom.RoundRectangle2D.Double shape;

        BufferedImage customImage = null;
        double imgScale = 1.0;
        double imgX = 0, imgY = 0;
        boolean isDraggingImg = false;

        String titleText;
        int titleSize; // 取消硬编码
        double TITLE_Y_DEFAULT; // 取消 final，允许动态跟班计算
        double titleY;
        boolean isDraggingTitle = false;

        String authorText;
        int authorSize; // 取消硬编码
        double AUTHOR_Y_DEFAULT; // 取消 final
        double authorY;
        boolean isDraggingAuthor = false;
        double authorXOffset = -0.0;

        Rectangle rectTitle = new Rectangle();
        Rectangle rectAuthor = new Rectangle();

        // 🎯 【新增】专属底色渐变与边框颜色属性
        boolean isTinted = false; // 默认不开启染色，使用原版棕底
        Color topColor = new Color(25, 107, 200);     // 🎯 默认顶部 196BC8
        Color bottomColor = new Color(113, 200, 209); // 🎯 默认底部 71C8D1
        Color borderColor = new Color(255, 231, 26);  // 🎯 恢复原版金边
        Color lineColor = new Color(255, 255, 255);   // 🎯 内部线条 FFFFFF
        BufferedImage cachedTintedBase = null; // 染色图像缓存
        BufferedImage cachedEffectBase = null; // 🎯【新增】包含阴影与描边的最终复合图像缓存

        // 🎯 【新增】专属高清缩放缓存，拦截 Java 底层劣质采样
        BufferedImage cachedHDImage = null;
        double lastHDScale = -1.0;

        public SongModule(int id, double baseX) {
            this.id = id;
            this.baseX = baseX;

            // 【一键全局缩放引擎】1.0 为原始大小，缩小 10% 即为 0.9
            double scale = 1;

            double origW = 222.0;
            double origH = 222.0;
            double origY = 388.0; // 原版金框的顶部绝对物理坐标

            // 1. 【物理金框和图片的等比缩放】
            double newW = origW * scale;
            double newH = origH * scale;
            double offsetX = (origW - newW) / 2.0;
            double offsetY = (origH - newH) / 2.0;
            this.shape = new java.awt.geom.RoundRectangle2D.Double(baseX + offsetX, origY + offsetY, newW, newH, 8, 8);

            // 2. 【核心修复：文字体系的严格等比缩放】
            this.titleText = "歌曲" + id;
            this.titleSize = (int)Math.round(34 * scale); // 字体大小按比例缩小
            // 算法：新文字Y坐标 = 缩小后的金框顶部Y + (原本文字距离顶部的相对落差 * 缩放率)
            this.TITLE_Y_DEFAULT = this.shape.y + (664.0 - origY) * scale;
            this.titleY = this.TITLE_Y_DEFAULT;

            this.authorText = "作者" + id;
            this.authorSize = (int)Math.round(19 * scale); // 作者字号同步缩小
            this.AUTHOR_Y_DEFAULT = this.shape.y + (735.0 - origY) * scale;
            this.authorY = this.AUTHOR_Y_DEFAULT;
        }

        public void enforceBounds() {
            if (customImage == null) return;
            double targetSide = shape.width;
            double minScale = Math.max(targetSide / customImage.getWidth(), targetSide / customImage.getHeight());
            if (imgScale < minScale) imgScale = minScale;

            double drawW = customImage.getWidth() * imgScale;
            double drawH = customImage.getHeight() * imgScale;

            if (imgX > shape.x) imgX = shape.x;
            if (imgY > shape.y) imgY = shape.y;
            if (imgX + drawW < shape.x + targetSide) imgX = shape.x + targetSide - drawW;
            if (imgY + drawH < shape.y + targetSide) imgY = shape.y + targetSide - drawH;
        }
    }

    // 【一键影分身中心】声明数组，由 switchTemplate 动态初始化
    private SongModule[] songModules;

    // 【新增】动态模板切换引擎
    public void switchTemplate(int mode) {
        int oldMode = this.currentTemplateMode;
        this.currentTemplateMode = mode;

        // 【核心：右移专辑蒙版】如果是 6 首歌，蒙版物理坐标整体右移 30 像素
        int offsetX = (mode == 6) ? 30 : 0;
        for (int i = 0; i < 4; i++) {
            maskXPoints[i] = BASE_MASK_X[i] + offsetX;
        }
        maskPolygon = new Polygon(maskXPoints, maskYPoints, 4);

        // 如果用户已经拖了封面进去，让它跟着蒙版一起平移，避免错位
        if (maskCustomImage != null && oldMode != mode) {
            int deltaX = (mode == 6) ? 30 : -30;
            maskImgX += deltaX;
        }

        if (mode == 5) {
            songModules = new SongModule[] {
                    new SongModule(1, 60),
                    new SongModule(2, 320),
                    new SongModule(3, 580),
                    new SongModule(4, 840),
                    new SongModule(5, 1100)
            };
        } else if (mode == 6) {
            // 6首歌模板：完全照搬素材，利用更紧凑的横向坐标塞下 6 首歌
            // 为了节省空间，每首歌只留了极细的 1 像素物理缝隙 (231 宽 + 1 = 232 间距)
            songModules = new SongModule[] {
                    new SongModule(1, 30),
                    new SongModule(2, 280),
                    new SongModule(3, 530),
                    new SongModule(4, 780),
                    new SongModule(5, 1030),
                    new SongModule(6, 1280)
            };
        }
        repaint(); // 重绘画布，瞬间完成影分身变形
    }

    private Font sourceHanSerifFont = null;
    private boolean hasMovedDuringClick = false;

    public DesignCanvas(MczTool parent) {
        this.parent = parent;
        setOpaque(false);
        switchTemplate(5); // 画布启动时，默认加载为 5 首歌模式
// 【终极方案】修复开发文件夹层级，统一加载所有静态素材
        try {
            // 1. 加载上 Banner
            String bannerLocalPath = "素材/5首歌模板/上Banner.png";
            String bannerInternalPath = "/素材/5首歌模板/上Banner.png";
            File localBanner = new File(bannerLocalPath);

            if (localBanner.exists()) {
                topBannerImage = ImageIO.read(localBanner);
            } else {
                java.net.URL imgUrl = MczTool.class.getResource(bannerInternalPath);
                if (imgUrl != null) {
                    topBannerImage = ImageIO.read(imgUrl);
                } else {
                    JOptionPane.showMessageDialog(null,
                            "上Banner 加载失败！\n请检查 素材/5首歌模板/ 文件夹内是否存在该图片。",
                            "资源缺失", JOptionPane.WARNING_MESSAGE);
                }
            }

            // 2. 加载顶部灯光特效
            String lightLocalPath = "素材/5首歌模板/灯光.png";
            String lightInternalPath = "/素材/5首歌模板/灯光.png";
            File localLight = new File(lightLocalPath);

            if (localLight.exists()) {
                topLightImage = ImageIO.read(localLight);
            } else {
                java.net.URL lightUrl = MczTool.class.getResource(lightInternalPath);
                if (lightUrl != null) topLightImage = ImageIO.read(lightUrl);
            }

            // 3. 加载左上角 Logo
            File localLogo = new File("素材/5首歌模板/logo.png");
            if (localLogo.exists()) logoImage = ImageIO.read(localLogo);
            else {
                java.net.URL logoUrl = MczTool.class.getResource("/素材/5首歌模板/logo.png");
                if (logoUrl != null) logoImage = ImageIO.read(logoUrl);
            }

// 4. 加载左上角 Teacharm
            File localTeacharm = new File("素材/5首歌模板/teacharm.png");
            if (localTeacharm.exists()) teacharmImage = ImageIO.read(localTeacharm);
            else {
                java.net.URL teacharmUrl = MczTool.class.getResource("/素材/5首歌模板/teacharm.png");
                if (teacharmUrl != null) teacharmImage = ImageIO.read(teacharmUrl);
            }

// 5. 加载右侧专辑封面底图 (自动兼容 .png 和 .jpg)
            File localBasePng = new File("素材/5首歌模板/专辑封面底.png");
            File localBaseJpg = new File("素材/5首歌模板/专辑封面底.jpg");
            if (localBasePng.exists()) albumBaseImage = ImageIO.read(localBasePng);
            else if (localBaseJpg.exists()) albumBaseImage = ImageIO.read(localBaseJpg);
            else {
                java.net.URL baseUrlPng = MczTool.class.getResource("/素材/5首歌模板/专辑封面底.png");
                java.net.URL baseUrlJpg = MczTool.class.getResource("/素材/5首歌模板/专辑封面底.jpg");
                if (baseUrlPng != null) albumBaseImage = ImageIO.read(baseUrlPng);
                else if (baseUrlJpg != null) albumBaseImage = ImageIO.read(baseUrlJpg);
            }

// 6. 【新增】加载歌曲底色
            File localSongBase = new File("素材/5首歌模板/歌曲底色.png");
            if (localSongBase.exists()) songBaseImage = ImageIO.read(localSongBase);
            else {
                java.net.URL songBaseUrl = MczTool.class.getResource("/素材/5首歌模板/歌曲底色.png");
                if (songBaseUrl != null) songBaseImage = ImageIO.read(songBaseUrl);
            }

// 7. 【新增】加载思源宋体 (修复流读取)
            File localSourceHan = new File("素材/5首歌模板/思源宋体Bold.otf");
            if (localSourceHan.exists()) {
                sourceHanSerifFont = Font.createFont(Font.TRUETYPE_FONT, localSourceHan);
            } else {
                java.io.InputStream sourceHanStream = MczTool.class.getResourceAsStream("/素材/5首歌模板/思源宋体Bold.otf");
                if (sourceHanStream != null) {
                    sourceHanSerifFont = Font.createFont(Font.TRUETYPE_FONT, sourceHanStream);
                    sourceHanStream.close(); // 养成好习惯，读取完关闭流
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastDragPoint = e.getPoint();
                hasMovedDuringClick = false;

                double scale = Math.min((double)getWidth() / LOGICAL_WIDTH, (double)getHeight() / LOGICAL_HEIGHT);
                int ox = (getWidth() - (int)(LOGICAL_WIDTH * scale)) / 2;
                int oy = (getHeight() - (int)(LOGICAL_HEIGHT * scale)) / 2;
                int logicalX = (int)((e.getX() - ox) / scale);
                int logicalY = (int)((e.getY() - oy) / scale);

                // 【轮询检测所有歌曲套组】
                for (SongModule song : songModules) {
                    if (song.rectTitle.contains(logicalX, logicalY)) { song.isDraggingTitle = true; return; }
                    if (song.rectAuthor.contains(logicalX, logicalY)) { song.isDraggingAuthor = true; return; }
                    if (song.shape.contains(logicalX, logicalY) && song.customImage != null) { song.isDraggingImg = true; return; }
                }

                if (maskPolygon.contains(logicalX, logicalY) && maskCustomImage != null) { isDraggingMaskImg = true; return; }

// 【极简】由于在画图时已经追踪了坐标，这里只需要一句极其优雅的 contains 判断
                if (rectAlbumTitle.contains(logicalX, logicalY)) { isDraggingTitle = true; return; }
                if (rectAlbumSubTitle.contains(logicalX, logicalY)) { isDraggingSubTitle = true; return; }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!hasMovedDuringClick) {
                    double scale = Math.min((double)getWidth() / LOGICAL_WIDTH, (double)getHeight() / LOGICAL_HEIGHT);
                    int ox = (getWidth() - (int)(LOGICAL_WIDTH * scale)) / 2;
                    int oy = (getHeight() - (int)(LOGICAL_HEIGHT * scale)) / 2;
                    int logicalX = (int)((e.getX() - ox) / scale);
                    int logicalY = (int)((e.getY() - oy) / scale);

// 【计算封面图物理范围，用于触发点击调色】
                    int baseImgX = (currentTemplateMode == 6) ? 1280 : 1250;
                    int baseImgY = 40;
                    int baseImgW = albumBaseImage != null ? albumBaseImage.getWidth() : 600;
                    int baseImgH = albumBaseImage != null ? albumBaseImage.getHeight() : 700;
                    Rectangle rectAlbumBase = new Rectangle(baseImgX, baseImgY, baseImgW, baseImgH);

                    // 【新增】优先检测是否点中了专辑大标题
                    if (rectAlbumTitle.contains(logicalX, logicalY)) {
                        showAlbumTitleEditDialog();
                    } else if (rectAlbumSubTitle.contains(logicalX, logicalY)) {
                        showAlbumSubTitleEditDialog(); // 🎯 增加这3行：触发副标题弹窗
                    } else {
                        // 检测是否点中了底下那 5/6 首歌
                        boolean clickedSong = false;
                        for (SongModule song : songModules) {
                            if (song.rectTitle.contains(logicalX, logicalY)) { showTextEditDialog(song, true); clickedSong = true; break; }
                            else if (song.rectAuthor.contains(logicalX, logicalY)) { showTextEditDialog(song, false); clickedSong = true; break; }
                            else if (song.shape.contains(logicalX, logicalY)) {
                                showSongBaseColorDialog(song);
                                clickedSong = true;
                                break;
                            }
                        }

                        // 🎯 【新增】如果没有点中具体歌曲，但是点中了右侧“专辑封面底图”那一大片区域，弹出大封面调色盘！
                        if (!clickedSong && rectAlbumBase.contains(logicalX, logicalY)) {
                            showAlbumBaseColorDialog();
                        }
                    }
                }

                isDraggingTitle = false;
                isDraggingSubTitle = false;
                isDraggingMaskImg = false;
                for (SongModule song : songModules) {
                    song.isDraggingTitle = false;
                    song.isDraggingAuthor = false;
                    song.isDraggingImg = false;
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragPoint == null) return;
                hasMovedDuringClick = true;

                double scale = Math.min((double)getWidth() / LOGICAL_WIDTH, (double)getHeight() / LOGICAL_HEIGHT);
                int dx = (int)((e.getX() - lastDragPoint.x) / scale);
                int dy = (int)((e.getY() - lastDragPoint.y) / scale);

                boolean handledSongDrag = false;
                for (SongModule song : songModules) {
                    if (song.isDraggingTitle) {
                        song.titleY += dy;
                        song.titleY = Math.max(20, Math.min(LOGICAL_HEIGHT - 20, song.titleY));
                        lastDragPoint = e.getPoint(); repaint(); handledSongDrag = true; break;
                    } else if (song.isDraggingAuthor) {
                        song.authorY += dy;
                        song.authorY = Math.max(20, Math.min(LOGICAL_HEIGHT - 20, song.authorY));
                        lastDragPoint = e.getPoint(); repaint(); handledSongDrag = true; break;
                    } else if (song.isDraggingImg) {
                        song.imgX += dx; song.imgY += dy;
                        song.enforceBounds();
                        lastDragPoint = e.getPoint(); repaint(); handledSongDrag = true; break;
                    }
                }
                if (handledSongDrag) return;

                if (isDraggingMaskImg) {
                    maskImgX += dx; maskImgY += dy;
                    enforceMaskBounds();
                    lastDragPoint = e.getPoint(); repaint();
                } else if (isDraggingTitle) {
                    titleCenterX += dx;
                    lastDragPoint = e.getPoint(); repaint();
                } else if (isDraggingSubTitle) {
                    subTitleX += dx; subTitleY += dy; // 🎯 增加这 4 行：支持副标题自由拖拽
                    lastDragPoint = e.getPoint(); repaint();
                } else if (parent.designBgImage != null) {
                    bgOffsetX += dx; bgOffsetY += dy;
                    double baseCoverScale = Math.max((double)LOGICAL_WIDTH / parent.designBgImage.getWidth(), (double)LOGICAL_HEIGHT / parent.designBgImage.getHeight());
                    int drawW = (int)(parent.designBgImage.getWidth() * baseCoverScale * customZoom);
                    int drawH = (int)(parent.designBgImage.getHeight() * baseCoverScale * customZoom);
                    bgOffsetX = Math.max(LOGICAL_WIDTH - drawW, Math.min(0, bgOffsetX));
                    bgOffsetY = Math.max(LOGICAL_HEIGHT - drawH, Math.min(0, bgOffsetY));
                    lastDragPoint = e.getPoint(); repaint();
                }
            }
        });

        addMouseWheelListener(e -> {
            double scale = Math.min((double)getWidth() / LOGICAL_WIDTH, (double)getHeight() / LOGICAL_HEIGHT);
            int ox = (getWidth() - (int)(LOGICAL_WIDTH * scale)) / 2;
            int oy = (getHeight() - (int)(LOGICAL_HEIGHT * scale)) / 2;
            int logicalX = (int)((e.getX() - ox) / scale);
            int logicalY = (int)((e.getY() - oy) / scale);

            if (e.isControlDown()) {
                boolean handledSongWheel = false;
                for (SongModule song : songModules) {
                    if (song.shape.contains(logicalX, logicalY) && song.customImage != null) {
                        double oldZoom = song.imgScale;
                        if (e.getWheelRotation() < 0) song.imgScale *= 1.05; else song.imgScale /= 1.05;
                        song.imgScale = Math.max(0.01, Math.min(5.0, song.imgScale));

                        int oldW = (int)(song.customImage.getWidth() * oldZoom);
                        int oldH = (int)(song.customImage.getHeight() * oldZoom);
                        int newW = (int)(song.customImage.getWidth() * song.imgScale);
                        int newH = (int)(song.customImage.getHeight() * song.imgScale);
                        double mouseRatioX = (double)(logicalX - song.imgX) / oldW;
                        double mouseRatioY = (double)(logicalY - song.imgY) / oldH;

                        song.imgX -= (newW - oldW) * mouseRatioX;
                        song.imgY -= (newH - oldH) * mouseRatioY;
                        song.enforceBounds(); repaint();
                        handledSongWheel = true; break;
                    }
                }
                if (handledSongWheel) return;

                if (maskPolygon.contains(logicalX, logicalY) && maskCustomImage != null) {
                    double oldZoom = maskImgScale;
                    if (e.getWheelRotation() < 0) maskImgScale *= 1.05; else maskImgScale /= 1.05;
                    maskImgScale = Math.max(0.01, Math.min(5.0, maskImgScale));

                    int oldW = (int)(maskCustomImage.getWidth() * oldZoom);
                    int oldH = (int)(maskCustomImage.getHeight() * oldZoom);
                    int newW = (int)(maskCustomImage.getWidth() * maskImgScale);
                    int newH = (int)(maskCustomImage.getHeight() * maskImgScale);
                    double mouseRatioX = (double)(logicalX - maskImgX) / oldW;
                    double mouseRatioY = (double)(logicalY - maskImgY) / oldH;

                    maskImgX -= (newW - oldW) * mouseRatioX;
                    maskImgY -= (newH - oldH) * mouseRatioY;
                    enforceMaskBounds(); repaint();
                }
                else if (parent.designBgImage != null && cachedBlurredBg != null) {
                    double oldZoom = customZoom;
                    if (e.getWheelRotation() < 0) customZoom *= 1.1; else customZoom /= 1.1;
                    customZoom = Math.max(1.0, Math.min(5.0, customZoom));

                    double baseCoverScale = Math.max((double)LOGICAL_WIDTH / cachedBlurredBg.getWidth(), (double)LOGICAL_HEIGHT / cachedBlurredBg.getHeight());
                    int oldW = (int)(cachedBlurredBg.getWidth() * baseCoverScale * oldZoom);
                    int oldH = (int)(cachedBlurredBg.getHeight() * baseCoverScale * oldZoom);
                    int newW = (int)(cachedBlurredBg.getWidth() * baseCoverScale * customZoom);
                    int newH = (int)(cachedBlurredBg.getHeight() * baseCoverScale * customZoom);

                    bgOffsetX -= (newW - oldW) / 2; bgOffsetY -= (newH - oldH) / 2;
                    bgOffsetX = Math.max(LOGICAL_WIDTH - newW, Math.min(0, bgOffsetX));
                    bgOffsetY = Math.max(LOGICAL_HEIGHT - newH, Math.min(0, bgOffsetY));
                    repaint();
                }
            }
        });

        new DropTarget(this, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) return;
                    File droppedFile = files.get(0);
                    dtde.dropComplete(true);

// 【新增引擎：检测到专属工程文件，直接进入还原模式】
                    if (droppedFile.getName().toLowerCase().endsWith(".tea")) {
                        loadProject(droppedFile);
                        return;
                    }

                    java.awt.Point dropPoint = dtde.getLocation();
                    double scale = Math.min((double)getWidth() / LOGICAL_WIDTH, (double)getHeight() / LOGICAL_HEIGHT);
                    int ox = (getWidth() - (int)(LOGICAL_WIDTH * scale)) / 2;
                    int oy = (getHeight() - (int)(LOGICAL_HEIGHT * scale)) / 2;
                    int logicalX = (int)((dropPoint.x - ox) / scale);
                    int logicalY = (int)((dropPoint.y - oy) / scale);

                    SongModule targetDropSong = null;
                    for (SongModule song : songModules) {
                        if (song.shape.contains(logicalX, logicalY)) {
                            targetDropSong = song; break;
                        }
                    }
                    final SongModule finalTargetDropSong = targetDropSong;
                    boolean isDropInMask = maskPolygon.contains(logicalX, logicalY);

                    parent.taskQueue.submit(() -> {
                        try {
                            BufferedImage tempImg = ImageIO.read(droppedFile);
                            if (tempImg == null) return;

                            if (finalTargetDropSong != null) {
                                SwingUtilities.invokeLater(() -> {
                                    finalTargetDropSong.customImage = tempImg;
                                    double side = finalTargetDropSong.shape.width;
                                    finalTargetDropSong.imgScale = Math.max(side / tempImg.getWidth(), side / tempImg.getHeight());
                                    finalTargetDropSong.imgX = finalTargetDropSong.shape.x + (side - tempImg.getWidth() * finalTargetDropSong.imgScale) / 2.0;
                                    finalTargetDropSong.imgY = finalTargetDropSong.shape.y + (side - tempImg.getHeight() * finalTargetDropSong.imgScale) / 2.0;
                                    finalTargetDropSong.enforceBounds();
                                    repaint();
                                });
                            }
                            else if (isDropInMask) {
                                SwingUtilities.invokeLater(() -> {
                                    maskCustomImage = tempImg;
                                    double angleRad = Math.toRadians(13.0);
                                    double cosA = Math.cos(angleRad); double sinA = Math.sin(angleRad);
                                    double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
                                    double minW = Double.MAX_VALUE, maxW = -Double.MAX_VALUE;
                                    for (int i = 0; i < 4; i++) {
                                        double u = maskXPoints[i] * cosA + maskYPoints[i] * sinA;
                                        double w = -maskXPoints[i] * sinA + maskYPoints[i] * cosA;
                                        minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                                        minW = Math.min(minW, w); maxW = Math.max(maxW, w);
                                    }
                                    maskImgScale = Math.max((maxU - minU) / tempImg.getWidth(), (maxW - minW) / tempImg.getHeight());
                                    double cRotX = (minU + maxU) / 2.0; double cRotY = (minW + maxW) / 2.0;
                                    double cx = cRotX * cosA - cRotY * sinA; double cy = cRotX * sinA + cRotY * cosA;
                                    maskImgX = (int)Math.round(cx - (tempImg.getWidth() * maskImgScale) / 2.0);
                                    maskImgY = (int)Math.round(cy - (tempImg.getHeight() * maskImgScale) / 2.0);
                                    enforceMaskBounds(); repaint();
                                });
                            }
                            else {
                                BufferedImage tempBlurred = applyTrueBlur(tempImg, parent.blurLevel);
                                SwingUtilities.invokeLater(() -> {
                                    parent.designBgImage = tempImg; cachedBlurredBg = tempBlurred; lastBlurLevel = parent.blurLevel; customZoom = 1.0;
                                    double coverScale = Math.max((double)LOGICAL_WIDTH / parent.designBgImage.getWidth(), (double)LOGICAL_HEIGHT / parent.designBgImage.getHeight());
                                    bgOffsetX = (LOGICAL_WIDTH - (int)(parent.designBgImage.getWidth() * coverScale)) / 2;
                                    bgOffsetY = (LOGICAL_HEIGHT - (int)(parent.designBgImage.getHeight() * coverScale)) / 2;
                                    repaint();
                                });
                            }
                        } catch (Exception ex) { ex.printStackTrace(); }
                    });
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        });


    }

    // ================= [全局背景毛玻璃：纯正高斯模糊引擎] =================
    // 算法：三次级联盒式模糊，完美逼近 PS 级高斯模糊，且运算时间极短
    private void fastBoxBlurH(int[] src, int[] dst, int w, int h, int r) {
        float invR = 1.0f / (r * 2 + 1);
        for (int y = 0; y < h; y++) {
            int outIndex = y * w, inIndex = y * w;
            int rSum = 0, gSum = 0, bSum = 0;
            for (int i = -r; i <= r; i++) {
                int px = src[inIndex + Math.min(Math.max(i, 0), w - 1)];
                rSum += (px >> 16) & 0xff; gSum += (px >> 8) & 0xff; bSum += px & 0xff;
            }
            for (int x = 0; x < w; x++) {
                // 🎯 修复黑屏 Bug：强制注入 255 的 Alpha 不透明通道保护 (0xFF000000)
                dst[outIndex++] = 0xFF000000 | ((int)(rSum * invR) << 16) | ((int)(gSum * invR) << 8) | (int)(bSum * invR);
                int rightPx = src[inIndex + Math.min(x + r + 1, w - 1)];
                int leftPx = src[inIndex + Math.max(x - r, 0)];
                rSum += ((rightPx >> 16) & 0xff) - ((leftPx >> 16) & 0xff);
                gSum += ((rightPx >> 8) & 0xff) - ((leftPx >> 8) & 0xff);
                bSum += (rightPx & 0xff) - (leftPx & 0xff);
            }
        }
    }

    private void fastBoxBlurV(int[] src, int[] dst, int w, int h, int r) {
        float invR = 1.0f / (r * 2 + 1);
        for (int x = 0; x < w; x++) {
            int outIndex = x, inIndex = x;
            int rSum = 0, gSum = 0, bSum = 0;
            for (int i = -r; i <= r; i++) {
                int py = src[inIndex + Math.min(Math.max(i, 0), h - 1) * w];
                rSum += (py >> 16) & 0xff; gSum += (py >> 8) & 0xff; bSum += py & 0xff;
            }
            for (int y = 0; y < h; y++) {
                // 🎯 修复黑屏 Bug：强制注入 255 的 Alpha 不透明通道保护 (0xFF000000)
                dst[outIndex] = 0xFF000000 | ((int)(rSum * invR) << 16) | ((int)(gSum * invR) << 8) | (int)(bSum * invR);
                outIndex += w;
                int bottomPx = src[inIndex + Math.min(y + r + 1, h - 1) * w];
                int topPx = src[inIndex + Math.max(y - r, 0) * w];
                rSum += ((bottomPx >> 16) & 0xff) - ((topPx >> 16) & 0xff);
                gSum += ((bottomPx >> 8) & 0xff) - ((topPx >> 8) & 0xff);
                bSum += (bottomPx & 0xff) - (topPx & 0xff);
            }
        }
    }

    private BufferedImage applyFastGaussianBlur(BufferedImage src, int radius) {
        if (radius < 1) return src;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] srcPix = src.getRGB(0, 0, w, h, null, 0, w);
        int[] tmpPix = new int[w * h];
        int[] dstPix = new int[w * h];
        for(int i=0; i<3; i++) {
            fastBoxBlurH(srcPix, tmpPix, w, h, radius);
            fastBoxBlurV(tmpPix, dstPix, w, h, radius);
            System.arraycopy(dstPix, 0, srcPix, 0, dstPix.length);
        }
        dst.setRGB(0, 0, w, h, dstPix, 0, w);
        return dst;
    }

    private BufferedImage applyTrueBlur(BufferedImage original, int level) {
        if (level <= 0) return original;
        int w = original.getWidth() / 2;
        int h = original.getHeight() / 2;
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, w, h, null);
        g.dispose();

        // 🚀 彻底剥离表面模糊！调用专属的正宗高斯模糊算法
        int radius = Math.max(1, level / 2);
        BufferedImage blurred = applyFastGaussianBlur(scaled, radius);

        BufferedImage result = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.drawImage(blurred, 0, 0, original.getWidth(), original.getHeight(), null);
        g2.dispose();
        return result;
    }
    // ================= [专辑底图像素级调色引擎] =================
    private BufferedImage cachedTintedBase = null;

    public void clearTintCache() {
        cachedTintedBase = null; // 用户选了新颜色后清空缓存，触发重新渲染
    }

    private BufferedImage applyGradientTint(BufferedImage src, Color topColor, Color bottomColor) {
        if (src == null) return null;
        BufferedImage dest = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int w = src.getWidth();
        int h = src.getHeight();

        double angleRad = Math.toRadians(102.0);
        double cosA = Math.cos(angleRad);
        double sinA = Math.sin(angleRad);

        double length = w * Math.abs(cosA) + h * Math.abs(sinA);
        double cx = w / 2.0;
        double cy = h / 2.0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double proj = (x - cx) * cosA + (y - cy) * sinA;
                double t = (proj + length / 2.0) / length;
                t = Math.max(0.0, Math.min(1.0, t));

                // 1. 🚀【核心升级：集中渐变区间】
                // 将 0.0~1.0 的轴线强制映射，使色彩突变集中在 60% ~ 80% 区域
                double mappedT = (t - 0.6) / 0.2;
                mappedT = Math.max(0.0, Math.min(1.0, mappedT));
                // 利用平滑曲线让这 20% 的突变过渡依然柔和，杜绝锯齿色带
                double curveT = mappedT * mappedT * (3.0 - 2.0 * mappedT);

                int gradR = (int) (topColor.getRed() + curveT * (bottomColor.getRed() - topColor.getRed()));
                int gradG = (int) (topColor.getGreen() + curveT * (bottomColor.getGreen() - topColor.getGreen()));
                int gradB = (int) (topColor.getBlue() + curveT * (bottomColor.getBlue() - topColor.getBlue()));

                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xff;
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;

                // 线性加深基础计算
                int tintedR = Math.max(0, r + gradR - 255);
                int tintedG = Math.max(0, g + gradG - 255);
                int tintedB = Math.max(0, b + gradB - 255);

                // 2. 🛡️【核心保护：阴影与扩散光不被变色】
                // 利用透明度(Alpha)作为遮罩。由于阴影和发光通常处于半透明状态(a < 255)
                // 我们使用 Alpha 的三次幂急剧降低其染色权重，实现“只染实心主体，坚决不碰阴影”！
                float alphaRatio = a / 255.0f;
                float tintWeight = alphaRatio * alphaRatio * alphaRatio;

                int finalR = (int) (r * (1.0f - tintWeight) + tintedR * tintWeight);
                int finalG = (int) (g * (1.0f - tintWeight) + tintedG * tintWeight);
                int finalB = (int) (b * (1.0f - tintWeight) + tintedB * tintWeight);

                dest.setRGB(x, y, (a << 24) | (finalR << 16) | (finalG << 8) | finalB);
            }
        }
        return dest;
    }

    // ================= [新增引擎：纯净底图渐变染色 (支持黑线分离与 70% 边界)] =================
    private BufferedImage applyPureGradientTint(BufferedImage src, Color topColor, Color bottomColor, Color lineColor) {
        if (src == null) return null;
        BufferedImage dest = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int w = src.getWidth();
        int h = src.getHeight();

        for (int y = 0; y < h; y++) {
            // 🎯 需求：摒弃斜角，采用绝对垂直的从上到下渐变
            double t = (double) y / (h - 1);
            t = Math.max(0.0, Math.min(1.0, t));

            // 强制 0%~70% 为纯色，70%~100% 才发生渐变过渡！
            double curveT = 0.0;
            if (t > 0.7) {
                double mappedT = (t - 0.7) / 0.3; // 把剩下的 0.3 重新拉伸为 0~1 的轴线
                mappedT = Math.max(0.0, Math.min(1.0, mappedT));
                curveT = mappedT * mappedT * (3.0 - 2.0 * mappedT); // 平滑过渡杜绝色带
            }

            int gradR = (int) (topColor.getRed() + curveT * (bottomColor.getRed() - topColor.getRed()));
            int gradG = (int) (topColor.getGreen() + curveT * (bottomColor.getGreen() - topColor.getGreen()));
            int gradB = (int) (topColor.getBlue() + curveT * (bottomColor.getBlue() - topColor.getBlue()));

            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xff;
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;

                // 智能检测底层黑线 (#3f1f16 -> RGB: 63,31,22)
                int dist = Math.abs(r - 63) + Math.abs(g - 31) + Math.abs(b - 22);

                // 设定平滑宽容度 (Tolerance = 35) 实现完美柔和抗锯齿边缘混色
                if (dist < 35 && a > 50) {
                    float lineWeight = 1.0f - (dist / 35.0f);
                    int finalR = (int)(gradR * (1 - lineWeight) + lineColor.getRed() * lineWeight);
                    int finalG = (int)(gradG * (1 - lineWeight) + lineColor.getGreen() * lineWeight);
                    int finalB = (int)(gradB * (1 - lineWeight) + lineColor.getBlue() * lineWeight);
                    dest.setRGB(x, y, (a << 24) | (finalR << 16) | (finalG << 8) | finalB);
                } else {
                    // 覆盖主色
                    dest.setRGB(x, y, (a << 24) | (gradR << 16) | (gradG << 8) | gradB);
                }
            }
        }
        return dest;
    }
    // ================= [新增引擎：PS级图像特效合成 (描边与投影)] =================
    private BufferedImage generateEffectImage(BufferedImage src) {
        if (src == null) return null;
        int pad = 20; // 为特效留出绝对安全的透明扩展边界
        int w = src.getWidth();
        int h = src.getHeight();
        int newW = w + pad * 2;
        int newH = h + pad * 2;
        BufferedImage dest = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 提取原生图像的 Alpha 通道，生成一张纯黑色的实体遮罩
        BufferedImage alphaMask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for(int y=0; y<h; y++) {
            for(int x=0; x<w; x++) {
                int a = (src.getRGB(x, y) >> 24) & 0xff;
                alphaMask.setRGB(x, y, (a << 24) | 0x000000);
            }
        }

        // 1. 投影 (Drop Shadow): 颜色 #0a0a0a, 角度 90° (Y偏移10), 扩展 8%, 大小 6px
        BufferedImage shadowLayer = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = shadowLayer.createGraphics();

        // 十字偏移法完美模拟 PS 的“扩展 8%” (使阴影本身加粗)
        sg.drawImage(alphaMask, pad, pad + 10, null);
        sg.drawImage(alphaMask, pad + 1, pad + 10, null);
        sg.drawImage(alphaMask, pad - 1, pad + 10, null);
        sg.drawImage(alphaMask, pad, pad + 10 + 1, null);
        sg.drawImage(alphaMask, pad, pad + 10 - 1, null);
        sg.dispose();

        // PS里的 Size 6px 约等于高斯模糊的 3 像素半径
        shadowLayer = fastAlphaBlur(shadowLayer, 3);

        // 强制将模糊后的像素染回 #0a0a0a，避免半透明区域变灰
        for(int y=0; y<newH; y++) {
            for(int x=0; x<newW; x++) {
                int a = (shadowLayer.getRGB(x, y) >> 24) & 0xff;
                shadowLayer.setRGB(x, y, (a << 24) | 0x0a0a0a);
            }
        }
        g.drawImage(shadowLayer, 0, 0, null); // 铺上最底层

        // 2. 外围描边 (Stroke): 颜色 #2d2d2d, 尺寸 4px
        BufferedImage strokeLayer = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D strkG = strokeLayer.createGraphics();
        BufferedImage colorMask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for(int y=0; y<h; y++) {
            for(int x=0; x<w; x++) {
                int a = (src.getRGB(x, y) >> 24) & 0xff;
                colorMask.setRGB(x, y, (a << 24) | 0x2d2d2d);
            }
        }

        // 极其暴力的像素级圆形卷积描边：确保完美的 4 像素纯色平滑外框
        for(int dy=-4; dy<=4; dy++) {
            for(int dx=-4; dx<=4; dx++) {
                if (dx*dx + dy*dy <= 16) {
                    strkG.drawImage(colorMask, pad + dx, pad + dy, null);
                }
            }
        }
        strkG.dispose();
        g.drawImage(strokeLayer, 0, 0, null); // 盖在投影之上

        // 3. 将原图置于最顶层
        g.drawImage(src, pad, pad, null);
        g.dispose();
        return dest;
    }

    // Alpha 专用高斯平滑（复用自日历的高精度遮罩渲染）
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
    // ================= [核心引擎：带旋转角的智能边界锁定] =================
    private void enforceMaskBounds() {
        if (maskCustomImage == null) return;

        // 必须与画图渲染时的倾斜角度严格保持一致！
        double angleRad = Math.toRadians(13.0);
        double cosA = Math.cos(angleRad);
        double sinA = Math.sin(angleRad);

        // 1. 将四边形的 4 个点映射到带倾角的虚拟坐标系下，求出物理极限边界
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minW = Double.MAX_VALUE, maxW = -Double.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            double u = maskXPoints[i] * cosA + maskYPoints[i] * sinA;
            double w = -maskXPoints[i] * sinA + maskYPoints[i] * cosA;
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (w < minW) minW = w;
            if (w > maxW) maxW = w;
        }

        double reqW = maxU - minU;
        double reqH = maxW - minW;

        // 2. 强制最低缩放比例保护（就算你拼命往下滚滚轮，图片也绝对不会缩得比蒙版还小）
        double minScaleX = reqW / maskCustomImage.getWidth();
        double minScaleY = reqH / maskCustomImage.getHeight();
        double minScale = Math.max(minScaleX, minScaleY);
        if (maskImgScale < minScale) maskImgScale = minScale;

        // 3. 强制拖拽碰撞检测与回弹
        double drawW = maskCustomImage.getWidth() * maskImgScale;
        double drawH = maskCustomImage.getHeight() * maskImgScale;

        // 图片当前的绝对中心点
        double cx = maskImgX + drawW / 2.0;
        double cy = maskImgY + drawH / 2.0;

        // 将中心点拉入旋转空间进行碰撞比对
        double cRotX = cx * cosA + cy * sinA;
        double cRotY = -cx * sinA + cy * cosA;

        double clampMinX = maxU - drawW / 2.0;
        double clampMaxX = minU + drawW / 2.0;
        double clampMinY = maxW - drawH / 2.0;
        double clampMaxY = minW + drawH / 2.0;

        // 无情夹断：如果越界，强行赋值为极限边界坐标
        if (clampMinX > clampMaxX) cRotX = (clampMinX + clampMaxX) / 2.0;
        else cRotX = Math.max(clampMinX, Math.min(clampMaxX, cRotX));

        if (clampMinY > clampMaxY) cRotY = (clampMinY + clampMaxY) / 2.0;
        else cRotY = Math.max(clampMinY, Math.min(clampMaxY, cRotY));

        // 将回弹后的安全中心点逆向投射回真实的 UI 画板坐标系
        cx = cRotX * cosA - cRotY * sinA;
        cy = cRotX * sinA + cRotY * cosA;

        maskImgX = (int)Math.round(cx - drawW / 2.0);
        maskImgY = (int)Math.round(cy - drawH / 2.0);
    }
    // ================= [组件化引擎：原位点击修改文字与字号 (支持无限套组)] =================
    private void showTextEditDialog(SongModule song, boolean isTitle) {
        String currentText = isTitle ? song.titleText : song.authorText;
        int currentSize = isTitle ? song.titleSize : song.authorSize;

        JTextArea textArea = new JTextArea(currentText, 4, 15);
        textArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        JScrollPane scrollPane = new JScrollPane(textArea);

        JSpinner spinnerSize = new JSpinner(new SpinnerNumberModel(currentSize, 10, 200, 1));
        // 【核心修正】潜入 Spinner 内部，将数字输入区强制靠左对齐
        JComponent editor1 = spinnerSize.getEditor();
        if (editor1 instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) editor1).getTextField().setHorizontalAlignment(JTextField.LEFT);
        }

        JButton resetPosBtn = new JButton("恢复预设位置");
        resetPosBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        resetPosBtn.addActionListener(e -> {
            if (isTitle) song.titleY = song.TITLE_Y_DEFAULT;
            else song.authorY = song.AUTHOR_Y_DEFAULT;
            repaint();
            JOptionPane.showMessageDialog(null, "文字已恢复到初始纵轴位置。");
        });

        JPanel panel = new JPanel(new BorderLayout(5, 10));
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        bottomPanel.add(new JLabel("字体大小: "));
        bottomPanel.add(spinnerSize);
        bottomPanel.add(Box.createHorizontalStrut(20));
        bottomPanel.add(resetPosBtn);

        panel.add(new JLabel("文字内容 (Enter键可换行):"), BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        int res = JOptionPane.showConfirmDialog(parent, panel, "编辑" + song.titleText + "信息", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            if (isTitle) {
                song.titleText = textArea.getText();
                song.titleSize = (int)spinnerSize.getValue();
            } else {
                song.authorText = textArea.getText();
                song.authorSize = (int)spinnerSize.getValue();
            }
            repaint();
        }
    }

    private Color albumTitleColor = new Color(255, 214, 72); // 🎯 大标题全局基础色 (默认金黄)

    // ================= [新增引擎：专辑大标题原位点击修改 (带调色盘)] =================
    private void showAlbumTitleEditDialog() {
        JTextField fieldText = new JTextField(parent.albumTitleText, 15);
        fieldText.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        JSpinner spinnerSize = new JSpinner(new SpinnerNumberModel(parent.albumTitleSize, 10, 500, 1));
        JComponent editor2 = spinnerSize.getEditor();
        if (editor2 instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) editor2).getTextField().setHorizontalAlignment(JTextField.LEFT);
        }

        // 🎯 【新增】加粗控制复选框
        JCheckBox boldBox = new JCheckBox("加粗文字边缘", parent.albumTitleBold);

        JPanel panel = new JPanel(new GridLayout(4, 2, 10, 10)); // 🎯 改为 4行
        panel.add(new JLabel("专辑名称:"));
        panel.add(fieldText);
        panel.add(new JLabel("字体大小:"));
        panel.add(spinnerSize);
        panel.add(new JLabel("是否加粗:"));
        panel.add(boldBox);
        panel.add(new JLabel("标题主色调:"));

        // 简化版颜色选择按钮块
        JPanel colorBlock = new JPanel();
        colorBlock.setBackground(albumTitleColor);
        colorBlock.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        Color originalColor = albumTitleColor;

        JButton colorBtn = new JButton("选择颜色");
        colorBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        colorBtn.addActionListener(e -> {
            JColorChooser chooser = new JColorChooser(albumTitleColor);
            chooser.setPreviewPanel(new JPanel()); // 隐藏丑陋的文本预览
            for (javax.swing.colorchooser.AbstractColorChooserPanel p : chooser.getChooserPanels()) {
                String nameInfo = p.getClass().getSimpleName().toUpperCase() + p.getDisplayName().toUpperCase();
                if (!nameInfo.contains("HSB") && !nameInfo.contains("HSV")) chooser.removeChooserPanel(p);
            }

            // 实时预览监听！
            chooser.getSelectionModel().addChangeListener(evt -> {
                Color c = chooser.getColor();
                if (c != null) {
                    colorBlock.setBackground(c);
                    albumTitleColor = c;
                    repaint();
                }
            });

            int r = JOptionPane.showConfirmDialog(parent, chooser, "选择大标题主色调 (实时预览)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                albumTitleColor = originalColor; // 取消就回滚
                colorBlock.setBackground(originalColor);
                repaint();
            }
        });

        JPanel cp = new JPanel(new BorderLayout(5, 0));
        cp.add(colorBlock, BorderLayout.CENTER);
        cp.add(colorBtn, BorderLayout.EAST);
        panel.add(cp);

        int res = JOptionPane.showConfirmDialog(parent, panel, "编辑大标题与颜色", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            parent.albumTitleText = fieldText.getText();
            parent.albumTitleSize = (int)spinnerSize.getValue();
            parent.albumTitleBold = boldBox.isSelected(); // 🎯 保存加粗状态
            repaint();
        } else {
            albumTitleColor = originalColor; // 全局取消则重置所有操作
            repaint();
        }
    }
    // ================= [新增引擎：白色副标题原位点击修改] =================
    private void showAlbumSubTitleEditDialog() {
        JTextField fieldText = new JTextField(albumSubTitleText, 15);
        fieldText.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        JSpinner spinnerSize = new JSpinner(new SpinnerNumberModel(albumSubTitleSize, 10, 200, 1));
        JComponent editor = spinnerSize.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) editor).getTextField().setHorizontalAlignment(JTextField.LEFT);
        }

        JButton resetPosBtn = new JButton("恢复预设位置");
        resetPosBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        resetPosBtn.addActionListener(e -> {
            subTitleX = 843; subTitleY = 307;
            repaint();
            JOptionPane.showMessageDialog(parent, "副标题已恢复到初始坐标。");
        });

        JPanel panel = new JPanel(new GridLayout(3, 2, 10, 10));
        panel.add(new JLabel("副标题内容:"));
        panel.add(fieldText);
        panel.add(new JLabel("字体大小:"));
        panel.add(spinnerSize);
        panel.add(new JLabel("坐标复位:"));
        panel.add(resetPosBtn);

        int res = JOptionPane.showConfirmDialog(parent, panel, "编辑副标题", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            albumSubTitleText = fieldText.getText();
            albumSubTitleSize = (int)spinnerSize.getValue();
            repaint();
        }
    }
    // ================= [新增引擎：专辑封面底板全屏实时调色盘] =================
    private void showAlbumBaseColorDialog() {
        // 备份初始状态用于“取消”回滚
        Color origTop = parent.topBaseColor;
        Color origBot = parent.bottomBaseColor;
        boolean origTint = parent.isTintingEnabled;

        JPanel panel = new JPanel(new GridLayout(3, 1, 0, 5));
        JCheckBox enableTintBox = new JCheckBox("启用封面渐变染色", parent.isTintingEnabled);

        // 🎯 核心闭包：利用我们已有的基础设施，执行极速回绘
        Runnable applyToCanvas = () -> {
            parent.isTintingEnabled = enableTintBox.isSelected();
            if (parent.designCanvas != null) {
                clearTintCache();
                repaint();
            }
        };

        enableTintBox.addActionListener(e -> applyToCanvas.run());

        // 完美复用刚才为单首歌曲打造的 createLiveColorPickerRow 引擎，实现双系统零延迟顺滑预览
        panel.add(createLiveColorPickerRow("封面顶部颜色:", parent.topBaseColor, c -> { parent.topBaseColor = c; applyToCanvas.run(); }));
        panel.add(createLiveColorPickerRow("封面底部颜色:", parent.bottomBaseColor, c -> { parent.bottomBaseColor = c; applyToCanvas.run(); }));
        panel.add(enableTintBox);

        int res = JOptionPane.showConfirmDialog(parent, panel, "设置专辑大封面底色", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            // 如果用户觉得乱调的颜色不好看，点“取消”，瞬间复原
            parent.topBaseColor = origTop;
            parent.bottomBaseColor = origBot;
            parent.isTintingEnabled = origTint;
            applyToCanvas.run();
        }
    }
    // ================= [新增引擎：歌曲底板与边框颜色原位修改 (修复同步回滚与实时预览逻辑)] =================
    private void showSongBaseColorDialog(SongModule targetSong) {
        // 🛡️ 记录打开面板前的所有歌曲状态，以便在“取消同步”或直接“取消”时完美回滚！
        class SongState {
            boolean isTinted; Color top, bottom, border, line;
            SongState(SongModule s) { isTinted=s.isTinted; top=s.topColor; bottom=s.bottomColor; border=s.borderColor; line=s.lineColor; }
        }
        Map<SongModule, SongState> originalStates = new HashMap<>();
        for (SongModule sm : songModules) originalStates.put(sm, new SongState(sm));

        JPanel panel = new JPanel(new GridLayout(6, 1, 0, 5));

        JCheckBox enableTintBox = new JCheckBox("启用底板渐变染色", targetSong.isTinted);
        JCheckBox syncBox = new JCheckBox("同步生效 (应用到所有歌曲)", true); // 默认开启同步

        // 🎯 【核心闭包引擎】：所有组件的变化，全部统一经过此引擎派发，确保逻辑绝对严密！
        Runnable applyToCanvas = () -> {
            boolean sync = syncBox.isSelected();
            boolean tint = enableTintBox.isSelected();
            for (SongModule sm : songModules) {
                if (sync || sm == targetSong) {
                    // 覆盖目标色
                    sm.isTinted = tint;
                    sm.topColor = targetSong.topColor;
                    sm.bottomColor = targetSong.bottomColor;
                    sm.borderColor = targetSong.borderColor;
                    sm.lineColor = targetSong.lineColor;
                } else {
                    // 若未选中同步，非目标歌曲立刻恢复至开窗前的原状
                    SongState orig = originalStates.get(sm);
                    sm.isTinted = orig.isTinted;
                    sm.topColor = orig.top;
                    sm.bottomColor = orig.bottom;
                    sm.borderColor = orig.border;
                    sm.lineColor = orig.line;
                }
                sm.cachedTintedBase = null; // 清空缓存，强制触发重绘
                sm.cachedEffectBase = null; // 🎯 强制清空复合特效图缓存，否则颜色变化不会生效
            }
            repaint();
        };

        // 实时监听开关
        enableTintBox.addActionListener(e -> applyToCanvas.run());
        syncBox.addActionListener(e -> applyToCanvas.run());

        // 注入调色盘
        panel.add(createLiveColorPickerRow("底板顶部颜色:", targetSong.topColor, c -> { targetSong.topColor = c; applyToCanvas.run(); }));
        panel.add(createLiveColorPickerRow("底板底部颜色:", targetSong.bottomColor, c -> { targetSong.bottomColor = c; applyToCanvas.run(); }));
        panel.add(createLiveColorPickerRow("外框线条颜色:", targetSong.borderColor, c -> { targetSong.borderColor = c; applyToCanvas.run(); }));
        // 🎯 修改 UI 文本为“内部线条颜色”
        panel.add(createLiveColorPickerRow("内部线条颜色:", targetSong.lineColor, c -> { targetSong.lineColor = c; applyToCanvas.run(); }));

        panel.add(enableTintBox);
        panel.add(syncBox);

        // 恢复使用 OK_CANCEL_OPTION，监听关闭/取消事件
        int res = JOptionPane.showConfirmDialog(parent, panel, "设置歌曲 " + targetSong.id + " 的底板与边框", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            // 🛡️ 如果用户点击取消或直接关闭窗口，彻底回滚一切实时改动！
            for (SongModule sm : songModules) {
                SongState orig = originalStates.get(sm);
                sm.isTinted = orig.isTinted;
                sm.topColor = orig.top;
                sm.bottomColor = orig.bottom;
                sm.borderColor = orig.border;
                sm.lineColor = orig.line;
                sm.cachedTintedBase = null;
                sm.cachedEffectBase = null; // 🎯 取消时同样强制清空复合特效图缓存
            }
            repaint();
        }
    }

    // 辅助组件：高度解耦的独立回调调色盘
    private JPanel createLiveColorPickerRow(String labelText, Color initColor, java.util.function.Consumer<Color> onColorChanged) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(90, 20));

        JPanel colorBlock = new JPanel();
        colorBlock.setPreferredSize(new Dimension(30, 20));
        colorBlock.setBackground(initColor);
        colorBlock.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        JButton btn = new JButton("修改");
        btn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        btn.addActionListener(e -> {
            Color originalColor = colorBlock.getBackground();
            JColorChooser chooser = new JColorChooser(originalColor);
            chooser.setPreviewPanel(new JPanel()); // 隐藏默认的文本预览框

            for (javax.swing.colorchooser.AbstractColorChooserPanel p : chooser.getChooserPanels()) {
                String nameInfo = p.getClass().getSimpleName().toUpperCase() + p.getDisplayName().toUpperCase();
                if (!nameInfo.contains("HSB") && !nameInfo.contains("HSV")) chooser.removeChooserPanel(p);
            }

            JPanel hexPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            hexPanel.add(new JLabel("色值: #"));
            JTextField hexField = new JTextField(String.format("%06X", (0xFFFFFF & originalColor.getRGB())), 6);
            hexField.setFont(new Font("Consolas", Font.BOLD, 15));
            hexPanel.add(hexField);

            // 🌟 【核心：监听选择器变化，执行回调函数！】
            chooser.getSelectionModel().addChangeListener(evt -> {
                Color c = chooser.getColor();
                if (c != null) {
                    if (!hexField.hasFocus()) hexField.setText(String.format("%06X", (0xFFFFFF & c.getRGB())));
                    colorBlock.setBackground(c);
                    onColorChanged.accept(c); // 触发外部引擎回调
                }
            });

            hexField.addKeyListener(new KeyAdapter() {
                public void keyReleased(KeyEvent evt) {
                    String text = hexField.getText().trim();
                    if (text.startsWith("#")) text = text.substring(1);
                    if (text.length() == 6 || text.length() == 3) {
                        try { chooser.setColor(Color.decode("#" + text)); } catch (Exception ex) {
                            // 无效的十六进制颜色值，忽略
                        }
                    }
                }
            });

            JPanel container = new JPanel(new BorderLayout());
            container.add(chooser, BorderLayout.CENTER);
            container.add(hexPanel, BorderLayout.SOUTH);

            int res = JOptionPane.showConfirmDialog(parent, container, labelText + " (支持实时预览)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) {
                colorBlock.setBackground(originalColor);
                onColorChanged.accept(originalColor); // 取消则瞬间回滚
            }
        });

        row.add(label); row.add(colorBlock); row.add(btn);
        return row;
    }
    // ================= [新增引擎：阶梯式渐进降采样 (PS级无损缩放)] =================
    private BufferedImage getHighQualityScaledInstance(BufferedImage img, int targetW, int targetH) {
        if (img == null || targetW <= 0 || targetH <= 0) return img;
        int type = (img.getTransparency() == Transparency.OPAQUE) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage ret = img;
        int w = img.getWidth();
        int h = img.getHeight();

        // 如果是放大，直接用双三次插值一次到位，画质最好
        if (targetW >= w || targetH >= h) {
            BufferedImage tmp = new BufferedImage(targetW, targetH, type);
            Graphics2D g2 = tmp.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.drawImage(ret, 0, 0, targetW, targetH, null);
            g2.dispose();
            return tmp;
        }

        // 🎯【核心魔法：多级阶梯缩小】
        // 只要图片宽或高是目标尺寸的 2 倍以上，就每次只缩小一半，利用 BILINEAR 完美融合周围像素
        while (w > targetW * 2 && h > targetH * 2) {
            w /= 2; h /= 2;
            BufferedImage tmp = new BufferedImage(w, h, type);
            Graphics2D g2 = tmp.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(ret, 0, 0, w, h, null);
            g2.dispose();
            ret = tmp;
        }

        // 最后一层细微缩放：使用最高质量的 BICUBIC 进行锐利化收尾
        BufferedImage tmp = new BufferedImage(targetW, targetH, type);
        Graphics2D g2 = tmp.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(ret, 0, 0, targetW, targetH, null);
        g2.dispose();

        return tmp;
    }
    // ================= [提取独立绘图逻辑，用于预览与导出] =================
    private void drawCanvasContent(Graphics2D bg) {
        // 🎯 【核心画质修复】：强制全局画笔使用最高级别的双三次插值算法！
        // 彻底解决高清大图缩小到歌曲框时产生的严重锯齿、像素化和“渣画质”错觉。
        bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        bg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        bg.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

        // === 第 1 层：底层可拖拽/缩放/模糊的背景 ===
        if (parent.designBgImage != null) {
            if (cachedBlurredBg == null || lastBlurLevel != parent.blurLevel) {
                cachedBlurredBg = applyTrueBlur(parent.designBgImage, parent.blurLevel);
                lastBlurLevel = parent.blurLevel;
            }
            double coverScale = Math.max((double)LOGICAL_WIDTH / cachedBlurredBg.getWidth(),
                    (double)LOGICAL_HEIGHT / cachedBlurredBg.getHeight());
            int actualDrawW = (int)(cachedBlurredBg.getWidth() * coverScale * customZoom);
            int actualDrawH = (int)(cachedBlurredBg.getHeight() * coverScale * customZoom);
            bg.drawImage(cachedBlurredBg, bgOffsetX, bgOffsetY, actualDrawW, actualDrawH, null);
        } else {
            bg.setColor(new Color(40, 40, 40));
            bg.fillRect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT);
            bg.setColor(new Color(255, 255, 255, 100));
            bg.setFont(new Font("Microsoft YaHei", Font.BOLD, 50));
            bg.drawString("请将背景图片拖拽至此", LOGICAL_WIDTH / 2 - 250, LOGICAL_HEIGHT / 2 - 45);
        }

// === 第 2 层：固定 50% 不透明度的黑色全局遮罩 ===
        bg.setColor(new Color(0, 0, 0, 127));
        bg.fillRect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT);

// === 第 3 层：固定上方的 Banner ===
        if (topBannerImage != null) {
            // 【坐标微调区】
            int bannerOffsetX = -24;
            int bannerOffsetY = -4;
            bg.drawImage(topBannerImage, bannerOffsetX, bannerOffsetY, null);
        }

// === 第 4 层：右侧固定专辑封面底图 (动态渐变映射 + 叠加混合) ===
        if (albumBaseImage != null) {
            // 【动态偏移】6首歌模板下，底图向右平移 15 像素 (1250 -> 1265)
            int baseImgX = (currentTemplateMode == 6) ? 1280 : 1250;
            int baseImgY = 40;

            if (!parent.isTintingEnabled) {
                bg.drawImage(albumBaseImage, baseImgX, baseImgY, null);
            } else {
                if (cachedTintedBase == null) {
                    cachedTintedBase = applyGradientTint(albumBaseImage, parent.topBaseColor, parent.bottomBaseColor);
                }
                bg.drawImage(cachedTintedBase, baseImgX, baseImgY, null);
            }

// ==========================================
            // === 第 4.5 层：【硬核】智能剪贴蒙版与内部自由图片 ===
            // ==========================================
            // 【渲染素质升级】开启最高精度的亚像素定位和分数度量，这是实现“浑厚”效果的前提
            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            bg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            bg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 保存画笔原本的剪贴区域
            Shape oldClip = bg.getClip();

            // 1. 【发动剪贴蒙版】接下来的所有画笔行为，都只能显示在这个多边形内！
            bg.setClip(maskPolygon);

            // 2. 变成深灰不透明底色
            bg.setColor(new Color(45, 45, 45));
            bg.fill(maskPolygon);

            // 【设定全局倾斜角度】你可以在这里微调图片的倾斜度
            double imageAngle = 13.0;
            double rad = Math.toRadians(imageAngle);

// 3. 如果用户拖入了图片，就应用旋转并画出来
            if (maskCustomImage != null) {
                int drawW = (int)(maskCustomImage.getWidth() * maskImgScale);
                int drawH = (int)(maskCustomImage.getHeight() * maskImgScale);

                // 🎯 【触发阶梯式降采样】：拦截锯齿，生成无损缩小的高清图像缓存
                if (cachedHDMaskImage == null || Math.abs(lastHDMaskScale - maskImgScale) > 0.001) {
                    cachedHDMaskImage = getHighQualityScaledInstance(maskCustomImage, drawW, drawH);
                    lastHDMaskScale = maskImgScale;
                }

                // 计算旋转中心 (以图片自身的中心为轴，这样拖拽和缩放时才不会乱飘)
                double centerX = maskImgX + drawW / 2.0;
                double centerY = maskImgY + drawH / 2.0;

                // 保存当前的矩阵变换状态
                java.awt.geom.AffineTransform oldTransform = bg.getTransform();

                // 施加旋转魔法
                bg.rotate(rad, centerX, centerY);

                // 绘制经过 PS 级缩小算法处理的高清缓存图片！不传宽高，直接画！
                bg.drawImage(cachedHDMaskImage, maskImgX, maskImgY, null);

                // 恢复矩阵变换，防止影响后续的其他图层
                bg.setTransform(oldTransform);

            } else {
                // 4. 如果没有图片，就画出倾斜的提示文字
                bg.setColor(new Color(150, 150, 150)); // 浅灰色文字
                bg.setFont(new Font("Microsoft YaHei", Font.BOLD, 34)); // 使用微软雅黑

                String tipText = "请将封面图片拖拽至此";
                FontMetrics fmTip = bg.getFontMetrics();
                int tipW = fmTip.stringWidth(tipText);

                // 获取多边形的边界框，用来计算文字的绝对居中位置
                Rectangle bounds = maskPolygon.getBounds();
                int tipX = bounds.x + (bounds.width - tipW) / 2;
                int tipY = bounds.y + bounds.height / 2;

                // 让文字也跟着一起倾斜 12度，看起来更贴合 3D 透视
                java.awt.geom.AffineTransform oldTransform = bg.getTransform();
                bg.rotate(rad, bounds.x + bounds.width / 2.0, bounds.y + bounds.height / 2.0);

                bg.drawString(tipText, tipX, tipY);

                bg.setTransform(oldTransform);
            }

            // 5. 【解除蒙版】把画笔权还给外部世界
            bg.setClip(oldClip);
        }

// === 第 5 层：上方的灯光特效 (现在它在底图后面画，所以能盖住底图) ===
        if (topLightImage != null) {
            // 【严格保留你的坐标】
            int lightOffsetX = 26;
            int lightOffsetY = 0;
            bg.drawImage(topLightImage, lightOffsetX, lightOffsetY, null);
        }

// === 第 5 层：左上角图标排版 (Logo + Teacharm) ===

        // 【1. 独立控制 Logo 的位置】
        if (logoImage != null) {
            int logoX = -10;  // 改这个：Logo 距离左边缘多远
            int logoY = -3;  // 改这个：Logo 距离上边缘多远
            bg.drawImage(logoImage, logoX, logoY, null);
        }

        // 【2. 独立控制 Teacharm 的位置】
        if (teacharmImage != null) {
            int teacharmX = 128;
            int teacharmY = 30;
            bg.drawImage(teacharmImage, teacharmX, teacharmY, null);
        }

// === 第 6 层：金碧辉煌的渐变大标题 ===
        if (parent.albumTitleText != null && !parent.albumTitleText.isEmpty()) {
            // 严格锁定 Y 轴 230
            int titleY = 230;

            Font baseFont;
            if (parent.customTitleFont != null) {
                baseFont = parent.customTitleFont.deriveFont(Font.PLAIN, (float)parent.albumTitleSize);
            } else {
                baseFont = new Font("Arial", Font.BOLD | Font.ITALIC, parent.albumTitleSize);
            }

            // 【字距紧缩】PS 的 -30 = -0.03f
            Map<java.awt.font.TextAttribute, Object> attributes = new HashMap<>();
            attributes.put(java.awt.font.TextAttribute.TRACKING, -0.03f);
            Font trackedFont = baseFont.deriveFont(attributes);
            bg.setFont(trackedFont);

            FontMetrics fm = bg.getFontMetrics();
            int textWidth = fm.stringWidth(parent.albumTitleText);
            int titleX = titleCenterX - (textWidth / 2);
            int pad = 50;

            BufferedImage textBuffer = new BufferedImage(Math.max(1, textWidth + pad * 2), Math.max(1, fm.getHeight() + pad * 2), BufferedImage.TYPE_INT_ARGB);
            Graphics2D tg = textBuffer.createGraphics();
            tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            tg.setFont(trackedFont);

            int drawX = pad;
            int drawY = fm.getAscent() + pad;

// 1. 矢量轮廓加粗 (浑厚效果)
            java.awt.font.FontRenderContext frc = tg.getFontRenderContext();
            java.awt.font.TextLayout tl = new java.awt.font.TextLayout(parent.albumTitleText, trackedFont, frc);
            Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(drawX, drawY));

            tg.setColor(Color.WHITE);
            tg.fill(textShape);
            // 🎯 根据全局开关决定是否应用扩边加粗特效
            if (parent.albumTitleBold) {
                tg.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                tg.draw(textShape);
            }

// 2. 核心：【广域高光 7 段渐变算法】(锁定 -50 度角)
            tg.setComposite(AlphaComposite.SrcIn);

            Color colYellow = albumTitleColor; // 🎯 接入全局配置颜色
            Color colWhite  = Color.WHITE;

            // 🎯 动态推演暗部色彩：不管用户选什么色，自动向红端稍偏色相，增加饱和度并压低亮度，形成完美的 3D 暗部！
            float[] hsb = Color.RGBtoHSB(colYellow.getRed(), colYellow.getGreen(), colYellow.getBlue(), null);
            hsb[0] = (hsb[0] - 0.03f < 0) ? hsb[0] - 0.03f + 1.0f : hsb[0] - 0.03f;
            hsb[1] = Math.min(1.0f, hsb[1] + 0.3f);
            hsb[2] = Math.max(0.0f, hsb[2] - 0.2f);
            Color colOrange = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);

            float cx = drawX + textWidth / 2.0f;
            float cy = drawY - fm.getAscent() / 2.0f;
            float w = (float)textWidth;
            float h = (float)fm.getAscent();

            double angleRad = Math.toRadians(-50.0);
            double cosA = Math.abs(Math.cos(angleRad));
            double sinA = Math.abs(Math.sin(angleRad));
            double length = w * cosA + h * sinA;

            float startX = (float) (cx - length / 2.0 * Math.cos(angleRad));
            float startY = (float) (cy + length / 2.0 * Math.sin(angleRad));
            float endX = (float) (cx + length / 2.0 * Math.cos(angleRad));
            float endY = (float) (cy - length / 2.0 * Math.sin(angleRad));

            // 【算法优化】
            float visibleRatio = (float)((w * cosA) / length);
            // 大幅增加 offset 基础值，迫使黄色区域向两极退缩，把舞台让给白色过渡区
            float offset = Math.max(0.18f, visibleRatio * 0.48f);

            // 让黄色更早结束，让白色高光覆盖更广的物理面积
            float stopYellowInner = 0.5f - offset;
            float stopWhiteLeft   = 0.5f - offset * 0.5f;
            float stopWhiteRight  = 0.5f + offset * 0.5f;
            float stopYellowOuter = 0.5f + offset;

            stopYellowInner = Math.max(0.01f, stopYellowInner);
            stopYellowOuter = Math.min(0.99f, stopYellowOuter);

            LinearGradientPaint psGradient = new LinearGradientPaint(
                    startX, startY, endX, endY,
                    new float[]{0.0f, stopYellowInner, stopWhiteLeft, 0.5f, stopWhiteRight, stopYellowOuter, 1.0f},
                    new Color[]{ colYellow, colYellow, colWhite, colOrange, colWhite, colYellow, colYellow }
            );
            tg.setPaint(psGradient);
            tg.fillRect(0, 0, textBuffer.getWidth(), textBuffer.getHeight());
            tg.dispose();

// 4. 盖章输出
            bg.drawImage(textBuffer, titleX - pad, titleY - fm.getAscent() - pad, null);

            // 【核心：动态更新隐形碰撞箱】不管字怎么变，碰撞箱永远死死框住它
            rectAlbumTitle.setBounds(titleX, titleY - fm.getAscent(), textWidth, fm.getHeight());
        } else {
            rectAlbumTitle.setBounds(0, 0, 0, 0);
        }
// ==========================================
        // === 第 7 层：可编辑拖拽的白色副标题 ===
        // ==========================================
        if (albumSubTitleText != null && !albumSubTitleText.isEmpty()) {
            Font baseSubFont = (parent.customTitleFont != null) ?
                    parent.customTitleFont.deriveFont(Font.PLAIN, (float)albumSubTitleSize) :
                    new Font("Arial", Font.BOLD | Font.ITALIC, albumSubTitleSize);

            // 稍微应用一点字距紧缩 (-0.02f)，保持高级排版感
            Map<java.awt.font.TextAttribute, Object> subAttr = new HashMap<>();
            subAttr.put(java.awt.font.TextAttribute.TRACKING, -0.02f);
            Font finalSubFont = baseSubFont.deriveFont(subAttr);
            bg.setFont(finalSubFont);

            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            bg.setColor(Color.WHITE);
            bg.drawString(albumSubTitleText, subTitleX, subTitleY);

            // 🎯【核心：动态更新隐形碰撞箱】追踪用户拖拽的位置
            FontMetrics fmSub = bg.getFontMetrics(finalSubFont);
            rectAlbumSubTitle.setBounds(subTitleX, subTitleY - fmSub.getAscent(), fmSub.stringWidth(albumSubTitleText), fmSub.getHeight());
        } else {
            rectAlbumSubTitle.setBounds(0, 0, 0, 0); // 没字则禁用碰撞箱
        }

// ==========================================
        // === 第 8 层：模块化歌曲信息框、蒙版与交互文字 ===
        // ==========================================
        for (SongModule song : songModules) {
            // 1. 【画大底座：复合渲染特效层】
            if (songBaseImage != null) {
                // 🎯 应用专属染色引擎与 PS 特效包装
                if (song.cachedEffectBase == null) {
                    BufferedImage baseImg = songBaseImage;
                    if (song.isTinted) {
                        if (song.cachedTintedBase == null) {
                            song.cachedTintedBase = applyPureGradientTint(songBaseImage, song.topColor, song.bottomColor, song.lineColor);
                        }
                        baseImg = song.cachedTintedBase;
                    }
                    // 给最终选择的底板加上 4px黑边和 10px向下投影
                    song.cachedEffectBase = generateEffectImage(baseImg);
                }

                // 因为 generateEffectImage 强制在四周加上了 20px 的透明边界来装下阴影，
                // 所以绘制时坐标必须反向抵消缩放后的 20px，保证视觉中心死死卡在原位！
                double moduleScale = song.shape.width / 222.0;
                int drawW = (int)(song.cachedEffectBase.getWidth() * moduleScale);
                int drawH = (int)(song.cachedEffectBase.getHeight() * moduleScale);
                int offsetX = (int)(20 * moduleScale);
                int offsetY = (int)(20 * moduleScale);

                bg.drawImage(song.cachedEffectBase, (int)song.shape.x - offsetX, (int)song.shape.y - offsetY, drawW, drawH, null);
            }

            // 2. 【发动蒙版】限制绘图区域在金框内
            Shape oldSongClip = bg.getClip();
            bg.setClip(song.shape);

            // 3. 【内部保底】只有当底图缺失时，才用深灰色保底。拒绝遮挡原有的渐变棕色底图！
            if (songBaseImage == null) {
                bg.setColor(new Color(65, 65, 65));
                bg.fill(song.shape);
            }

// 4. 【放置图片】
            if (song.customImage != null) {
                int drawW = (int)(song.customImage.getWidth() * song.imgScale);
                int drawH = (int)(song.customImage.getHeight() * song.imgScale);

                // 🎯 【触发阶梯式降采样】：拦截锯齿，生成无损缩小的高清图像缓存
                if (song.cachedHDImage == null || Math.abs(song.lastHDScale - song.imgScale) > 0.001) {
                    song.cachedHDImage = getHighQualityScaledInstance(song.customImage, drawW, drawH);
                    song.lastHDScale = song.imgScale;
                }

                // 绘制经过 PS 级缩小算法处理的高清缓存图片！不传宽高，直接画！
                bg.drawImage(song.cachedHDImage, (int)song.imgX, (int)song.imgY, null);
            } else {
                // 仅当图片为空时，画出占位字样 (将字调亮一点，在棕色底板上更好看)
                bg.setColor(new Color(200, 200, 200));
                bg.setFont(new Font("Microsoft YaHei", Font.BOLD, 28));
                FontMetrics fm = bg.getFontMetrics();
                String placeholder = "歌曲" + song.id;
                int tx = (int)(song.shape.x + (song.shape.width - fm.stringWidth(placeholder)) / 2.0);
                int ty = (int)(song.shape.y + song.shape.height / 2.0 + fm.getAscent() / 2.0 - 4); // 修复 y 轴居中
                bg.drawString(placeholder, tx, ty);
            }

// 5. 【解除蒙版】
            bg.setClip(oldSongClip);

            // 3. 撤销蒙版，画自定义边框
            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bg.setColor(song.borderColor); // 🎯 使用模块专属边框颜色
            bg.setStroke(new BasicStroke(3.0f));
            bg.draw(song.shape);

            // 4. 绘制底部的动态排版文字 (高挑平滑版)
            Font baseSyFont = (sourceHanSerifFont != null) ? sourceHanSerifFont : new Font("Microsoft YaHei", Font.BOLD, 12);
            double centerX = song.shape.x + song.shape.width / 2.0;
            java.awt.font.FontRenderContext frc = bg.getFontRenderContext();
            java.awt.geom.AffineTransform fontStretch = java.awt.geom.AffineTransform.getScaleInstance(1.0, 1.04);

            Map<java.awt.font.TextAttribute, Object> songAttrs = new HashMap<>();
            songAttrs.put(java.awt.font.TextAttribute.TRACKING, -0.03f);

            // --- 歌曲名 ---
            Font titleFont = baseSyFont.deriveFont(Font.PLAIN, (float)song.titleSize).deriveFont(songAttrs).deriveFont(fontStretch);
            bg.setFont(titleFont);
            FontMetrics fmTitle = bg.getFontMetrics();
            String[] titleLines = song.titleText.split("\n", -1);

            int tLineHeight = fmTitle.getHeight() - 15;
            int tMaxWidth = 0;
            int tCurrentY = (int)song.titleY;
            bg.setColor(Color.WHITE);

            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            Font fallbackPlain = new Font("Times New Roman", Font.PLAIN, titleFont.getSize());
            for (String line : titleLines) {
                if (line.isEmpty()) { tCurrentY += tLineHeight; continue; }
                java.awt.font.TextLayout tl;
                if (titleFont.canDisplayUpTo(line) == -1) {
                    tl = new java.awt.font.TextLayout(line, titleFont, frc);
                } else {
                    java.text.AttributedString as = new java.text.AttributedString(line);
                    as.addAttribute(java.awt.font.TextAttribute.FONT, titleFont);
                    for (int ci = 0; ci < line.length(); ci++) {
                        if (!titleFont.canDisplay(line.charAt(ci)))
                            as.addAttribute(java.awt.font.TextAttribute.FONT, fallbackPlain, ci, ci + 1);
                    }
                    tl = new java.awt.font.TextLayout(as.getIterator(), frc);
                }
                int lw = (int) tl.getBounds().getWidth();
                if (lw > tMaxWidth) tMaxWidth = lw;

                float lx = (float)(centerX - lw / 2.0);
                Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(lx, tCurrentY));

                bg.setStroke(new BasicStroke(0.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                bg.draw(textShape);
                bg.fill(textShape);

                tCurrentY += tLineHeight;
            }
            song.rectTitle.setBounds((int)(centerX - tMaxWidth / 2.0), (int)song.titleY - fmTitle.getAscent(), tMaxWidth, Math.max(tLineHeight, tLineHeight * titleLines.length));

            // --- 作者名 ---
            Font authorFont = baseSyFont.deriveFont(Font.PLAIN, (float)song.authorSize).deriveFont(songAttrs).deriveFont(fontStretch);
            bg.setFont(authorFont);
            FontMetrics fmAuth = bg.getFontMetrics();
            String[] authLines = song.authorText.split("\n", -1);

            int aLineHeight = fmAuth.getHeight() - 8;
            int aMaxWidth = 0;
            int aCurrentY = (int)song.authorY;

            Font fallbackAuth = new Font("Times New Roman", Font.PLAIN, authorFont.getSize());
            for (String line : authLines) {
                if (line.isEmpty()) { aCurrentY += aLineHeight; continue; }
                java.awt.font.TextLayout tl;
                if (authorFont.canDisplayUpTo(line) == -1) {
                    tl = new java.awt.font.TextLayout(line, authorFont, frc);
                } else {
                    java.text.AttributedString as = new java.text.AttributedString(line);
                    as.addAttribute(java.awt.font.TextAttribute.FONT, authorFont);
                    for (int ci = 0; ci < line.length(); ci++) {
                        if (!authorFont.canDisplay(line.charAt(ci)))
                            as.addAttribute(java.awt.font.TextAttribute.FONT, fallbackAuth, ci, ci + 1);
                    }
                    tl = new java.awt.font.TextLayout(as.getIterator(), frc);
                }
                int lw = (int) tl.getBounds().getWidth();
                if (lw > aMaxWidth) aMaxWidth = lw;

                float lx = (float)(centerX - lw / 2.0 + song.authorXOffset);
                Shape textShape = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(lx, aCurrentY));

                bg.setStroke(new BasicStroke(0.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                bg.draw(textShape);
                bg.fill(textShape);

                aCurrentY += aLineHeight;
            }
            song.rectAuthor.setBounds((int)(centerX - aMaxWidth / 2.0), (int)song.authorY - fmAuth.getAscent(), aMaxWidth, Math.max(aLineHeight, aLineHeight * authLines.length));
        }
    } // <--- 【核心修复】必须加上这个括号

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 【彻底恢复】回归自动适配窗口居中的大逻辑
        double scale = Math.min((double)getWidth() / LOGICAL_WIDTH, (double)getHeight() / LOGICAL_HEIGHT);

        int drawW = (int)(LOGICAL_WIDTH * scale);
        int drawH = (int)(LOGICAL_HEIGHT * scale);
        int ox = (getWidth() - drawW) / 2;
        int oy = (getHeight() - drawH) / 2;

        BufferedImage buffer = new BufferedImage(LOGICAL_WIDTH, LOGICAL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = buffer.createGraphics();
        drawCanvasContent(bg);
        bg.dispose();

        g2d.drawImage(buffer, ox, oy, drawW, drawH, null);
        g2d.setColor(new Color(0, 0, 0, 50));
        g2d.drawRect(ox - 1, oy - 1, drawW + 1, drawH + 1);
    }

    // ================= [新增：导出功能] =================
    public void exportImage() {
        BufferedImage exportBuffer = new BufferedImage(LOGICAL_WIDTH, LOGICAL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D exportG2d = exportBuffer.createGraphics();
        exportG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawCanvasContent(exportG2d);
        exportG2d.dispose();

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存专辑图片");
        chooser.setSelectedFile(new File("专辑设计_导出.png"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIO.write(exportBuffer, "png", chooser.getSelectedFile());
                JOptionPane.showMessageDialog(this, "导出成功！图片尺寸: 1920 x 817");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage());
            }
        }
    }
    // ================= [新增引擎：导出为 .tea 独立工程文件] =================
    public void saveProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存专辑排版工程");
        // 默认保存的文件名变为 .tea
        chooser.setSelectedFile(new File("未命名排版.tea"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File outFile = chooser.getSelectedFile();
        // 如果用户手动改名时忘了加后缀，系统自动帮他补上 .tea
        if (!outFile.getName().toLowerCase().endsWith(".tea")) {
            outFile = new File(outFile.getAbsolutePath() + ".tea");
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {
            Properties props = new Properties();
            // 1. 保存全局状态参数
            props.setProperty("templateMode", String.valueOf(currentTemplateMode));
            props.setProperty("blurLevel", String.valueOf(parent.blurLevel));
            props.setProperty("topColor", String.valueOf(parent.topBaseColor.getRGB()));
            props.setProperty("bottomColor", String.valueOf(parent.bottomBaseColor.getRGB()));
            props.setProperty("tintEnabled", String.valueOf(parent.isTintingEnabled));
            props.setProperty("albumTitleText", parent.albumTitleText.replace("\n", "<br>")); // 保护换行符
            props.setProperty("albumTitleSize", String.valueOf(parent.albumTitleSize));
            props.setProperty("albumTitleBold", String.valueOf(parent.albumTitleBold)); // 🎯 保存加粗状态
            props.setProperty("albumTitleColor", String.valueOf(albumTitleColor.getRGB()));
            props.setProperty("titleCenterX", String.valueOf(titleCenterX));
            props.setProperty("albumSubTitleText", albumSubTitleText.replace("\n", "<br>"));
            props.setProperty("albumSubTitleSize", String.valueOf(albumSubTitleSize));
            props.setProperty("subTitleX", String.valueOf(subTitleX));
            props.setProperty("subTitleY", String.valueOf(subTitleY));

            props.setProperty("maskImgScale", String.valueOf(maskImgScale));
            props.setProperty("maskImgX", String.valueOf(maskImgX));
            props.setProperty("maskImgY", String.valueOf(maskImgY));
            props.setProperty("customZoom", String.valueOf(customZoom));
            props.setProperty("bgOffsetX", String.valueOf(bgOffsetX));
            props.setProperty("bgOffsetY", String.valueOf(bgOffsetY));

            // 2. 遍历保存每首歌曲的配置与文本
            for (int i = 0; i < songModules.length; i++) {
                SongModule sm = songModules[i];
                String pfx = "song." + i + ".";
                props.setProperty(pfx + "titleText", sm.titleText.replace("\n", "<br>"));
                props.setProperty(pfx + "titleSize", String.valueOf(sm.titleSize));
                props.setProperty(pfx + "titleY", String.valueOf(sm.titleY));
                props.setProperty(pfx + "authorText", sm.authorText.replace("\n", "<br>"));
                props.setProperty(pfx + "authorSize", String.valueOf(sm.authorSize));
                props.setProperty(pfx + "authorY", String.valueOf(sm.authorY));
                props.setProperty(pfx + "imgScale", String.valueOf(sm.imgScale));
                props.setProperty(pfx + "imgX", String.valueOf(sm.imgX));
                props.setProperty(pfx + "imgY", String.valueOf(sm.imgY));

                // 🎯 写入底色与边框颜色
                props.setProperty(pfx + "isTinted", String.valueOf(sm.isTinted));
                props.setProperty(pfx + "topColor", String.valueOf(sm.topColor.getRGB()));
                props.setProperty(pfx + "bottomColor", String.valueOf(sm.bottomColor.getRGB()));
                props.setProperty(pfx + "borderColor", String.valueOf(sm.borderColor.getRGB()));

                // 将该格子的图片转换并封入压缩包
                if (sm.customImage != null) {
                    zos.putNextEntry(new ZipEntry("song_" + i + ".png"));
                    ImageIO.write(sm.customImage, "png", zos);
                    zos.closeEntry();
                }
            }

            // 3. 保存蒙版封面与全屏背景原图
            if (parent.designBgImage != null) {
                zos.putNextEntry(new ZipEntry("bg.png"));
                ImageIO.write(parent.designBgImage, "png", zos);
                zos.closeEntry();
            }
            if (maskCustomImage != null) {
                zos.putNextEntry(new ZipEntry("mask.png"));
                ImageIO.write(maskCustomImage, "png", zos);
                zos.closeEntry();
            }

            // 4. 将配置文件写入压缩包
            zos.putNextEntry(new ZipEntry("config.properties"));
            props.store(zos, "MczTool Project Config");
            zos.closeEntry();

            // 提示语也同步更新
            JOptionPane.showMessageDialog(this, "工程保存成功！\n日后可直接将此 .tea 文件拖入画板继续编辑。");
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "保存工程失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ================= [新增引擎：解析还原 .mczp 独立工程文件] =================
    public void loadProject(File prjFile) {
        try (ZipFile zf = new ZipFile(prjFile)) {
            ZipEntry configEntry = zf.getEntry("config.properties");
            if (configEntry == null) throw new Exception("无效的工程文件格式：缺失配置文件。");

            Properties props = new Properties();
            try (InputStream is = zf.getInputStream(configEntry)) {
                props.load(is);
            }

            // 1. 还原全局状态并执行动态变身 (5 或 6 首歌)
            int mode = Integer.parseInt(props.getProperty("templateMode", "5"));
            switchTemplate(mode);

            // 还原界面组件与颜色
            parent.blurLevel = Integer.parseInt(props.getProperty("blurLevel", "0"));
            if (parent.blurSlider != null) parent.blurSlider.setValue(parent.blurLevel);

            parent.topBaseColor = new Color(Integer.parseInt(props.getProperty("topColor", String.valueOf(parent.topBaseColor.getRGB()))));
            parent.bottomBaseColor = new Color(Integer.parseInt(props.getProperty("bottomColor", String.valueOf(parent.bottomBaseColor.getRGB()))));
            parent.isTintingEnabled = Boolean.parseBoolean(props.getProperty("tintEnabled", "true"));
            clearTintCache();

            parent.albumTitleText = props.getProperty("albumTitleText", parent.albumTitleText).replace("<br>", "\n");
            parent.albumTitleSize = Integer.parseInt(props.getProperty("albumTitleSize", String.valueOf(parent.albumTitleSize)));
            parent.albumTitleBold = Boolean.parseBoolean(props.getProperty("albumTitleBold", "true")); // 🎯 读取加粗状态
            albumTitleColor = new Color(Integer.parseInt(props.getProperty("albumTitleColor", String.valueOf(new Color(255, 214, 72).getRGB()))));
            titleCenterX = Integer.parseInt(props.getProperty("titleCenterX", "745"));
            albumSubTitleText = props.getProperty("albumSubTitleText", albumSubTitleText).replace("<br>", "\n");
            albumSubTitleSize = Integer.parseInt(props.getProperty("albumSubTitleSize", String.valueOf(albumSubTitleSize)));
            subTitleX = Integer.parseInt(props.getProperty("subTitleX", "843"));
            subTitleY = Integer.parseInt(props.getProperty("subTitleY", "307"));

            maskImgScale = Double.parseDouble(props.getProperty("maskImgScale", "1.0"));
            maskImgX = (int) Double.parseDouble(props.getProperty("maskImgX", "0"));
            maskImgY = (int) Double.parseDouble(props.getProperty("maskImgY", "0"));
            customZoom = Double.parseDouble(props.getProperty("customZoom", "1.0"));
            bgOffsetX = (int) Double.parseDouble(props.getProperty("bgOffsetX", "0"));
            bgOffsetY = (int) Double.parseDouble(props.getProperty("bgOffsetY", "0"));

            // 2. 读取底层背景与蒙版封面图
            ZipEntry bgEntry = zf.getEntry("bg.png");
            if (bgEntry != null) {
                parent.designBgImage = ImageIO.read(zf.getInputStream(bgEntry));
                cachedBlurredBg = applyTrueBlur(parent.designBgImage, parent.blurLevel);
                lastBlurLevel = parent.blurLevel;
            } else {
                parent.designBgImage = null; cachedBlurredBg = null;
            }

            ZipEntry maskEntry = zf.getEntry("mask.png");
            if (maskEntry != null) maskCustomImage = ImageIO.read(zf.getInputStream(maskEntry));
            else maskCustomImage = null;

            // 3. 精准覆盖每首歌曲的数据
            for (int i = 0; i < songModules.length; i++) {
                SongModule sm = songModules[i];
                String pfx = "song." + i + ".";

                sm.titleText = props.getProperty(pfx + "titleText", sm.titleText).replace("<br>", "\n");
                sm.titleSize = Integer.parseInt(props.getProperty(pfx + "titleSize", String.valueOf(sm.titleSize)));
                sm.titleY = Double.parseDouble(props.getProperty(pfx + "titleY", String.valueOf(sm.titleY)));
                sm.authorText = props.getProperty(pfx + "authorText", sm.authorText).replace("<br>", "\n");
                sm.authorSize = Integer.parseInt(props.getProperty(pfx + "authorSize", String.valueOf(sm.authorSize)));
                sm.authorY = Double.parseDouble(props.getProperty(pfx + "authorY", String.valueOf(sm.authorY)));
                sm.imgScale = Double.parseDouble(props.getProperty(pfx + "imgScale", "1.0"));
                sm.imgX = Double.parseDouble(props.getProperty(pfx + "imgX", "0"));
                sm.imgY = Double.parseDouble(props.getProperty(pfx + "imgY", "0"));

                // 🎯 读取底色与边框颜色
                sm.isTinted = Boolean.parseBoolean(props.getProperty(pfx + "isTinted", "false"));
                sm.topColor = new Color(Integer.parseInt(props.getProperty(pfx + "topColor", String.valueOf(new Color(185, 17, 132).getRGB()))));
                sm.bottomColor = new Color(Integer.parseInt(props.getProperty(pfx + "bottomColor", String.valueOf(new Color(236, 205, 217).getRGB()))));
                sm.borderColor = new Color(Integer.parseInt(props.getProperty(pfx + "borderColor", String.valueOf(new Color(255, 231, 26).getRGB()))));
                sm.cachedTintedBase = null; // 清除旧缓存以确保重绘时应用新颜色

                ZipEntry songImgEntry = zf.getEntry("song_" + i + ".png");
                if (songImgEntry != null) sm.customImage = ImageIO.read(zf.getInputStream(songImgEntry));
                else sm.customImage = null;
            }

            repaint(); // 所有配置就绪，一键重绘画布！
            JOptionPane.showMessageDialog(this, "工程还原成功！");

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "读取工程失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
}
