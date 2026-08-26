package com.mcz;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 图片 1:1 裁剪对话框 — 支持拖拽选框、缩放适配
 */
public class ImageCropper {

    public static void open(MczTool parent, File sourceFile) {
        try {
            BufferedImage original = ImageIO.read(sourceFile);
            if (original == null) return;

            double maxDim = 800.0;
            double scale = Math.min(1.0, Math.min(maxDim / original.getWidth(), maxDim / original.getHeight()));
            int displayW = (int) (original.getWidth() * scale);
            int displayH = (int) (original.getHeight() * scale);
            int displaySide = (int) (Math.min(original.getWidth(), original.getHeight()) * scale);

            JDialog cropDialog = new JDialog(parent, "图片 1:1 裁剪 (已自动缩放以适配窗口)", true);
            cropDialog.setLayout(new BorderLayout());

            JPanel canvas = new JPanel() {
                private int cropX = (displayW - displaySide) / 2;
                private int cropY = (displayH - displaySide) / 2;
                private java.awt.Point lastPress = null;

                {
                    addMouseListener(new java.awt.event.MouseAdapter() {
                        public void mousePressed(java.awt.event.MouseEvent e) { lastPress = e.getPoint(); }
                        public void mouseReleased(java.awt.event.MouseEvent e) {
                            if (JOptionPane.showConfirmDialog(cropDialog, "确认裁剪？", "确认", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                                try {
                                    int realX = (int) (cropX / scale);
                                    int realY = (int) (cropY / scale);
                                    int realSide = (int) (displaySide / scale);

                                    realX = Math.max(0, Math.min(original.getWidth() - realSide, realX));
                                    realY = Math.max(0, Math.min(original.getHeight() - realSide, realY));

                                    BufferedImage cropped = original.getSubimage(realX, realY, realSide, realSide);

                                    BufferedImage safeJpg = new BufferedImage(realSide, realSide, BufferedImage.TYPE_INT_RGB);
                                    Graphics2D gSafe = safeJpg.createGraphics();
                                    gSafe.setColor(Color.WHITE);
                                    gSafe.fillRect(0, 0, realSide, realSide);
                                    gSafe.drawImage(cropped, 0, 0, null);
                                    gSafe.dispose();

                                    File outDir = parent.activeTempDir != null ? parent.activeTempDir : new File(System.getProperty("java.io.tmpdir"));
                                    if (!outDir.exists()) outDir.mkdirs();
                                    File tempCrop = new File(outDir, "crop1x1_" + System.currentTimeMillis() + ".jpg");

                                    ImageIO.write(safeJpg, "jpg", tempCrop);

                                    parent.imageCache.put(tempCrop, new ImageIcon(safeJpg.getScaledInstance(108, 108, Image.SCALE_SMOOTH)));
                                    parent.ratioCache.put(tempCrop, MczParser.getRatioString(safeJpg.getWidth(), safeJpg.getHeight()));

                                    parent.customImageFile = tempCrop;
                                    parent.selectedImage = tempCrop;
                                    cropDialog.dispose();
                                    parent.updateResultPanel();
                                } catch (Exception ex) { ex.printStackTrace(); }
                            }
                        }
                    });
                    addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                        public void mouseDragged(java.awt.event.MouseEvent e) {
                            if (lastPress != null) {
                                int dx = e.getX() - lastPress.x;
                                int dy = e.getY() - lastPress.y;
                                cropX = Math.max(0, Math.min(displayW - displaySide, cropX + dx));
                                cropY = Math.max(0, Math.min(displayH - displaySide, cropY + dy));
                                lastPress = e.getPoint();
                                repaint();
                            }
                        }
                    });
                }

                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.drawImage(original, 0, 0, displayW, displayH, null);
                    g.setColor(new Color(0, 0, 0, 120));
                    g.fillRect(0, 0, displayW, cropY);
                    g.fillRect(0, cropY + displaySide, displayW, displayH - (cropY + displaySide));
                    g.fillRect(0, cropY, cropX, displaySide);
                    g.fillRect(cropX + displaySide, cropY, displayW - (cropX + displaySide), displaySide);
                    g.setColor(Color.RED);
                    g.drawRect(cropX, cropY, displaySide, displaySide);
                }
            };

            canvas.setPreferredSize(new Dimension(displayW, displayH));
            JScrollPane sp = new JScrollPane(canvas);
            cropDialog.add(sp, BorderLayout.CENTER);
            cropDialog.pack();
            cropDialog.setLocationRelativeTo(parent);
            cropDialog.setVisible(true);
        } catch (Exception e) { e.printStackTrace(); }
    }
}
