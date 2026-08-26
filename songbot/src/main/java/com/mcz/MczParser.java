package com.mcz;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import java.util.concurrent.TimeUnit;

/** MCZ 谱面解析 + Combo 统计算法 + 工具方法 */
public class MczParser {

    private static final int MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_UNZIPPED_BYTES = 512L * 1024L * 1024L;

    // ==================== 数据类 ====================

    public static class Point {
        public long tick; public int x;
        public Point(long tick, int x) { this.tick = tick; this.x = x; }
    }

    public static class SimpleJsonParser {
        private String json; private int pos;
        public SimpleJsonParser(String json) { this.json = json; this.pos = 0; }
        public Object parse() {
            skipWhitespace();
            if (pos >= json.length()) return null;
            char c = peek();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '\"') return parseString();
            if (Character.isDigit(c) || c == '-') return parseNumber();
            if (json.startsWith("true", pos)) { pos+=4; return true; }
            if (json.startsWith("false", pos)) { pos+=5; return false; }
            if (json.startsWith("null", pos)) { pos+=4; return null; }
            return null;
        }
        private Map<String, Object> parseObject() {
            Map<String, Object> map = new HashMap<>();
            consume('{');
            while (peek() != '}') {
                String key = parseString(); skipWhitespace(); consume(':');
                map.put(key, parse()); skipWhitespace();
                if (peek() == ',') consume(',');
            }
            consume('}'); return map;
        }
        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            consume('[');
            while (peek() != ']') {
                list.add(parse()); skipWhitespace();
                if (peek() == ',') consume(',');
            }
            consume(']'); return list;
        }
        private String parseString() {
            consume('\"'); StringBuilder sb = new StringBuilder();
            while (peek() != '\"') {
                char c = next();
                if (c == '\\') sb.append(next()); else sb.append(c);
            }
            consume('\"'); return sb.toString();
        }
        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') next();
            while (Character.isDigit(peek())) next();
            if (peek() == '.') { next(); while (Character.isDigit(peek())) next(); return Double.parseDouble(json.substring(start, pos)); }
            return Long.parseLong(json.substring(start, pos));
        }
        private void skipWhitespace() { while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++; }
        private char peek() { return pos < json.length() ? json.charAt(pos) : 0; }
        private char next() { return json.charAt(pos++); }
        private void consume(char c) { if (peek() == c) pos++; }
    }

    public static class BpmEvent {
        public double absoluteBeat;
        public double bpm;
        public BpmEvent(double absoluteBeat, double bpm) { this.absoluteBeat = absoluteBeat; this.bpm = bpm; }
    }

    // ==================== Combo 计算 ====================

    public static int calculateMaxCombo(String json, double initialBpm, int kNum) {
        int totalCalculatedCombo = 0;
        SimpleJsonParser parser = new SimpleJsonParser(json);
        Object root = parser.parse();
        if (!(root instanceof Map)) return 0;
        Map<String, Object> rootMap = (Map<String, Object>) root;
        List<BpmEvent> bpmList = new ArrayList<>();
        Object timeObj = rootMap.get("time");
        if (timeObj instanceof List) {
            for (Object item : (List<Object>) timeObj) {
                Map<String, Object> t = (Map<String, Object>) item;
                double beat = getAbsoluteBeat(t.get("beat"));
                double bpm = ((Number) t.get("bpm")).doubleValue();
                bpmList.add(new BpmEvent(beat, bpm));
            }
        }
        bpmList.sort(Comparator.comparingDouble(e -> e.absoluteBeat));
        List<Object> notes = (List<Object>) rootMap.get("note");
        if (notes == null) return 0;
        for (Object item : notes) {
            Map<String, Object> note = (Map<String, Object>) item;
            if (!note.containsKey("x") && !note.containsKey("column")) continue;
            if (!note.containsKey("seg")) {
                double startBeat = getAbsoluteBeat(note.get("beat"));
                double endBeat = getAbsoluteBeat(note.get("endbeat"));
                if (endBeat != -1) {
                    double startMs = beatToMs(startBeat, bpmList, initialBpm);
                    double endMs = beatToMs(endBeat, bpmList, initialBpm);
                    totalCalculatedCombo += calcLuaFormula(startMs, endMs, initialBpm);
                } else totalCalculatedCombo += 1;
            } else {
                List<Object> segs = (List<Object>) note.get("seg");
                if (segs == null || segs.isEmpty()) { totalCalculatedCombo += 1; continue; }
                double headStartBeat = getAbsoluteBeat(note.get("beat"));
                double headStartMs = beatToMs(headStartBeat, bpmList, initialBpm);
                double headRawX = getRawX(note, kNum);
                Map<String, Object> seg0 = (Map<String, Object>) segs.get(0);
                double seg0BeatOffset = getAbsoluteBeat(seg0.get("beat"));
                double seg0AbsoluteBeat = headStartBeat + seg0BeatOffset;
                double seg0Ms = beatToMs(seg0AbsoluteBeat, bpmList, initialBpm);
                if (!(seg0AbsoluteBeat == headStartBeat && segs.size() > 1))
                    totalCalculatedCombo += calcLuaFormula(headStartMs, seg0Ms, initialBpm);
                for (int i = 1; i < segs.size(); i++) {
                    double prevBeatOffset = getAbsoluteBeat(((Map<String, Object>) segs.get(i - 1)).get("beat"));
                    double currBeatOffset = getAbsoluteBeat(((Map<String, Object>) segs.get(i)).get("beat"));
                    double prevMs = beatToMs(headStartBeat + prevBeatOffset, bpmList, initialBpm);
                    double currMs = beatToMs(headStartBeat + currBeatOffset, bpmList, initialBpm);
                    totalCalculatedCombo += calcLuaFormula(prevMs, currMs, initialBpm);
                }
                double prevAbsoluteX = headRawX;
                double lastAbsoluteX = headRawX;
                for (int i = 0; i < segs.size(); i++) {
                    Map<String, Object> seg = (Map<String, Object>) segs.get(i);
                    double offsetX = 0.0;
                    if (seg.containsKey("x")) offsetX = ((Number) seg.get("x")).doubleValue();
                    double currentAbsoluteX = headRawX + offsetX;
                    prevAbsoluteX = lastAbsoluteX;
                    lastAbsoluteX = currentAbsoluteX;
                }
                int prevX_c = x2c(prevAbsoluteX, kNum);
                int lastX_c = x2c(lastAbsoluteX, kNum);
                if (prevX_c != lastX_c) totalCalculatedCombo += 1;
            }
        }
        return totalCalculatedCombo;
    }

    public static int calcLuaFormula(double startMs, double endMs, double initialBpm) {
        double durationInMs = endMs - startMs;
        if (durationInMs < 0) durationInMs = 0;
        long dur = (long) Math.floor((durationInMs + 2.0) * initialBpm / 1250.0 + 1e-6);
        return (int) (dur / 12) + 1;
    }

    public static double extractInitialBpm(String json) {
        Pattern p = Pattern.compile("\"bpm\"\\s*:\\s*([0-9.]+)");
        Matcher m = p.matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 150.0;
    }

    public static List<Point> mergeCollinear(List<Point> raw, long tolerance) {
        if (raw.size() < 3) return raw;
        List<Point> result = new ArrayList<>();
        result.add(raw.get(0));
        for (int i = 1; i < raw.size() - 1; i++) {
            Point p1 = result.get(result.size() - 1);
            Point p2 = raw.get(i);
            Point p3 = raw.get(i+1);
            long val1 = (long)(p2.x - p1.x) * (p3.tick - p2.tick);
            long val2 = (long)(p3.x - p2.x) * (p2.tick - p1.tick);
            if (p1.x == p2.x && p2.x == p3.x) continue;
            if (Math.abs(val1 - val2) <= tolerance) continue;
            else result.add(p2);
        }
        result.add(raw.get(raw.size() - 1));
        return result;
    }

    // ==================== 工具方法 ====================

    public static long getTick(Object val) {
        if (val == null) return -1;
        if (val instanceof List) {
            List<?> v = (List<?>) val;
            long bar = ((Number)v.get(0)).longValue();
            long num = ((Number)v.get(1)).longValue();
            long den = ((Number)v.get(2)).longValue();
            if (den == 0) return 0;
            return bar*192 + num*192/den;
        }
        if (val instanceof Number) return Math.round(((Number)val).doubleValue() * 48);
        return -1;
    }

    public static int getInt(Object val, Object fallback) {
        if (val instanceof Number) return ((Number)val).intValue();
        if (fallback instanceof Number) return ((Number)fallback).intValue();
        return -999;
    }

    public static String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\",}]+)\"?");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) return matcher.group(1).trim();
        return "";
    }

    public static String getDurationWithFFmpeg(File audioFile, String ffmpegCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegCommand, "-i", audioFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("Duration:")) {
                        String rawTime = line.trim().split(",")[0].replace("Duration:", "").trim();
                        String[] parts = rawTime.split(":");
                        if (parts.length >= 3) {
                            int h = Integer.parseInt(parts[0]);
                            int m = Integer.parseInt(parts[1]);
                            int s = (int) Double.parseDouble(parts[2]);
                            m += h * 60;
                            return String.format("%02d:%02d", m, s);
                        }
                    }
                }
            }
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("警告：获取音频时长失败: " + e.getMessage());
        }
        return "00:00";
    }

    public static boolean isImage(String name) { return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp"); }
    public static boolean isAudio(String name) { return name.endsWith(".mp3") || name.endsWith(".ogg") || name.endsWith(".wav"); }
    public static String escapeSql(String str) { return str.replace("'", "''"); }

    public static void unzip(File zipFile, File destDir) throws IOException {
        File root = destDir.getCanonicalFile();
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create extraction directory: " + root);
        }
        String rootPath = root.getPath() + File.separator;
        long totalBytes = 0L;
        int entryCount = 0;
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                File entryDestination = new File(root, entry.getName()).getCanonicalFile();
                if (!entryDestination.getPath().startsWith(rootPath)) {
                    throw new IOException("Archive entry escapes extraction directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!entryDestination.mkdirs() && !entryDestination.isDirectory()) {
                        throw new IOException("Cannot create directory: " + entryDestination);
                    }
                }
                else {
                    File parent = entryDestination.getParentFile();
                    if (!parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Cannot create directory: " + parent);
                    }
                    try (InputStream in = zf.getInputStream(entry); OutputStream out = new FileOutputStream(entryDestination)) {
                        byte[] buffer = new byte[8192]; int len;
                        while ((len = in.read(buffer)) > 0) {
                            totalBytes += len;
                            if (totalBytes > MAX_UNZIPPED_BYTES) {
                                throw new IOException("Archive exceeds the extraction size limit");
                            }
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    public static void deleteDirectory(File file) throws IOException {
        if (file.isDirectory()) { File[] entries = file.listFiles(); if (entries != null) for (File entry : entries) deleteDirectory(entry); }
        file.delete();
    }

    public static double getAbsoluteBeat(Object beatObj) {
        if (beatObj == null) return -1;
        List<Object> arr = (List<Object>) beatObj;
        long measure = ((Number) arr.get(0)).longValue();
        long beat = ((Number) arr.get(1)).longValue();
        long divisor = ((Number) arr.get(2)).longValue();
        return measure + (double) beat / divisor;
    }

    public static double beatToMs(double targetBeat, List<BpmEvent> bpmList, double defaultBpm) {
        if (targetBeat <= 0) return 0;
        double ms = 0;
        double currentBeat = 0;
        double currentBpm = bpmList.isEmpty() ? defaultBpm : bpmList.get(0).bpm;
        for (BpmEvent event : bpmList) {
            if (event.absoluteBeat > currentBeat) {
                if (targetBeat <= event.absoluteBeat) {
                    ms += (targetBeat - currentBeat) * (60000.0 / currentBpm);
                    return ms;
                } else {
                    ms += (event.absoluteBeat - currentBeat) * (60000.0 / currentBpm);
                    currentBeat = event.absoluteBeat;
                }
            }
            if (event.absoluteBeat <= targetBeat) currentBpm = event.bpm;
        }
        if (targetBeat > currentBeat) ms += (targetBeat - currentBeat) * (60000.0 / currentBpm);
        return ms;
    }

    public static double getTrackWidth(int kNum) {
        if (kNum == 4) return 64.0;
        if (kNum == 5) return 51.0;
        if (kNum == 6) return 43.0;
        if (kNum == 7) return 36.5;
        if (kNum >= 8) return 32.0;
        return 64.0;
    }

    public static double getRawX(Map<String, Object> map, int kNum) {
        if (map.containsKey("x")) return ((Number) map.get("x")).doubleValue();
        if (map.containsKey("column")) return ((Number) map.get("column")).doubleValue() * getTrackWidth(kNum);
        return 0.0;
    }

    public static int x2c(double x, int kNum) {
        if (kNum == 4) return (int)Math.floor((x + 1e-6) / 64.0) + 1;
        else if (kNum == 5) return (int)Math.floor((x + 1e-6) / 51.0) + 1;
        else if (kNum == 6) return (int)Math.floor((x + 1e-6) / 43.0) + 1;
        else if (kNum == 7) return (int)Math.floor((x + 1e-6) / 36.5) + 1;
        else return (int)Math.floor((x + 1e-6) / 32.0) + 1;
    }

    public static int getGcd(int a, int b) { return b == 0 ? a : getGcd(b, a % b); }

    public static String getRatioString(int w, int h) {
        if (Math.abs((double) w / h - 1.0) < 0.01) return "1:1";
        int gcd = getGcd(w, h);
        return (w / gcd) + ":" + (h / gcd) + " (" + w + "x" + h + ")";
    }

    public static BufferedImage applySurfaceBlur(BufferedImage src, int radius, int threshold) {
        if (radius <= 0 || threshold <= 0) return src;
        int width = src.getWidth(), height = src.getHeight();
        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] srcPix = src.getRGB(0, 0, width, height, null, 0, width);
        int[] dstPix = new int[width * height];

        // 1. 权重衰减表 (完全线性的 PS 官方公式: 1 - diff/threshold)
        int[] wTable = new int[256];
        for (int i = 0; i < 256; i++) {
            wTable[i] = Math.max(0, 256 - (i * 256 / threshold));
        }

        // 2. 预计算圆形内核 (Photoshop 的模糊是正圆形的，之前的方形会导致“块状近视”观感)
        int step = 1;
        if (radius > 10) step = 2;
        if (radius > 25) step = 3;
        if (radius > 45) step = 4;

        int maxK = (radius * 2 + 1) * (radius * 2 + 1);
        int[] kx = new int[maxK];
        int[] ky = new int[maxK];
        int kSize = 0;
        int r2 = radius * radius;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                // 核心约束 1：必须是正圆形
                if (dx * dx + dy * dy <= r2) {
                    // 核心约束 2：使用质数哈希进行“伪随机噪点采样”！
                    // 彻底打散 step 带来的网格马赛克，将其转化为类似胶片的自然颗粒感。
                    boolean keep = true;
                    if (step > 1) {
                        keep = (Math.abs(dx * 31 + dy * 17) % step == 0);
                    }
                    if (keep) {
                        kx[kSize] = dx;
                        ky[kSize] = dy;
                        kSize++;
                    }
                }
            }
        }
        final int finalKSize = kSize;

        // 3. 多核并行 2D 渲染
        java.util.stream.IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                int cCenter = srcPix[y * width + x];
                int rC = (cCenter >> 16) & 0xFF, gC = (cCenter >> 8) & 0xFF, bC = cCenter & 0xFF;

                long sumR = 0, sumG = 0, sumB = 0;
                int totW = 0;

                for (int i = 0; i < finalKSize; i++) {
                    int nx = x + kx[i];
                    int ny = y + ky[i];

                    // 使用高效的边界夹断 (Clamp)，避免图像四周出现黑色暗角
                    if (nx < 0) nx = 0; else if (nx >= width) nx = width - 1;
                    if (ny < 0) ny = 0; else if (ny >= height) ny = height - 1;

                    int cNeigh = srcPix[ny * width + nx];
                    int rN = (cNeigh >> 16) & 0xFF, gN = (cNeigh >> 8) & 0xFF, bN = cNeigh & 0xFF;

                    // 核心约束 3：统一最大色差 (Max RGB Diff)
                    // 绝不分离通道！否则边缘会产生红蓝绿的色散溢出，这是 PS 绝对避免的。
                    int diff = Math.max(Math.abs(rN - rC), Math.max(Math.abs(gN - gC), Math.abs(bN - bC)));
                    int w = wTable[diff];

                    if (w > 0) {
                        sumR += rN * w;
                        sumG += gN * w;
                        sumB += bN * w;
                        totW += w;
                    }
                }

                if (totW == 0) totW = 1;
                dstPix[y * width + x] = (((int)(sumR / totW)) << 16) |
                        (((int)(sumG / totW)) << 8) |
                        ((int)(sumB / totW));
            }
        });

        dest.setRGB(0, 0, width, height, dstPix, 0, width);
        return dest;
    }
}
