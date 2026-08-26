package com.mcz;

import javax.sound.sampled.*;
import javax.swing.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * 音频处理 — 波形计算、播放试听、淡出预览
 */
public class AudioManager {


// UI 界面切换歌曲时调用的方法
public static void prepareAudioPreview(MczTool parent, File oggFile) {
    parent.taskQueue.submit(() -> syncPrepareAudioPreview(parent, oggFile));
}

// 核心同步转换逻辑：确保同一时间只有一个 FFmpeg 在运行
public static void syncPrepareAudioPreview(MczTool parent, File oggFile) {
    try {
        if (parent.playbackClip != null) { parent.playbackClip.stop(); parent.playbackClip.flush(); parent.playbackClip.close(); }
        if (parent.playbackTimer != null) parent.playbackTimer.stop();

        File tempWav = new File(parent.activeTempDir, "preview_temp.wav");
        ProcessBuilder pb = new ProcessBuilder(
                parent.ffmpegCommand, "-y", "-fflags", "+genpts", "-i", oggFile.getAbsolutePath(),
                "-map", "0:a:0", "-map_metadata", "-1", "-vn", "-sn",
                "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", "-f", "wav",
                tempWav.getAbsolutePath()
        );
        Process p = pb.start();
        boolean completed = p.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            p.destroyForcibly();
            System.err.println("警告：音频转换超时，已强制终止");
            return;
        }

        if (tempWav.exists() && tempWav.length() > 0) {
            AudioInputStream ais = AudioSystem.getAudioInputStream(tempWav);
            try {
                parent.playbackClip = AudioSystem.getClip();
                parent.playbackClip.open(ais);
                parent.totalAudioSeconds = (double)parent.playbackClip.getMicrosecondLength() / 1_000_000.0;
                computeWaveform(parent, tempWav);

                if (parent.autoPreviewStart > 0) {
                    parent.audioStartTime = Math.round(parent.autoPreviewStart * 10.0) / 10.0;
                    parent.audioEndTime = Math.min(parent.totalAudioSeconds, parent.audioStartTime + 15.0);
                    parent.playbackClip.setMicrosecondPosition((long)(parent.audioStartTime * 1_000_000));
                } else {
                    parent.audioStartTime = 0; parent.audioEndTime = parent.totalAudioSeconds;
                }

                SwingUtilities.invokeLater(() -> { parent.isFadedPlaying = false; parent.updateResultPanel(); });
            } finally {
                ais.close();
            }
        }
    } catch (Exception e) { e.printStackTrace(); }
}

// 从 WAV 读取 PCM 采样，计算分窗 dB 波形
public static void computeWaveform(MczTool parent, File wavFile) {
    try {
        if (!wavFile.exists()) return;
        byte[] raw = java.nio.file.Files.readAllBytes(wavFile.toPath());
        // 跳过 44 字节 WAV 头，读取 16-bit PCM
        int headerSize = 44;
        int sampleCount = (raw.length - headerSize) / 2;
        if (sampleCount <= 0) return;
        int bins = Math.min(600, Math.max(200, sampleCount / 512));
        parent.waveformDB = new float[bins];
        int samplesPerBin = sampleCount / bins;
        for (int b = 0; b < bins; b++) {
            double sum = 0;
            int start = b * samplesPerBin;
            int end = Math.min(start + samplesPerBin, sampleCount);
            for (int i = start; i < end; i++) {
                int idx = headerSize + i * 2;
                int lo = raw[idx] & 0xFF;
                int hi = raw[idx + 1];
                short s = (short)((hi << 8) | lo);
                sum += Math.abs(s);
            }
            double avg = sum / (end - start);
            double db = 20.0 * Math.log10(Math.max(avg, 1.0) / 32768.0);
            parent.waveformDB[b] = (float) Math.max(-40.0, db);
        }
    } catch (Exception e) {
        parent.waveformDB = null;
    }
}


public static void togglePlayPause(MczTool parent) {
    // 增加 !parent.playbackClip.isOpen() 判断
    // 防止在点击”重置”或”切换歌曲”重新加载音频的瞬间点击播放导致程序崩溃
    if (parent.playbackClip == null || !parent.playbackClip.isOpen()) return;

    if (parent.playbackClip.isRunning()) parent.playbackClip.stop();
    else parent.playbackClip.start();
}
public static void playFadedPreview(MczTool parent) {
    if (parent.selectedAudio == null) return;
    // 【修复】提交到 taskQueue，防止试听渲染与文件解析冲突
    parent.taskQueue.submit(() -> {
        try {
            File previewClipFile = new File(parent.activeTempDir, "faded_preview.wav");
            double dur = parent.audioEndTime - parent.audioStartTime;
            double fadeOutSt = Math.max(0, dur - 4.0);

            ProcessBuilder pb = new ProcessBuilder(
                    parent.ffmpegCommand, "-y", "-fflags", "+genpts",
                    "-ss", String.valueOf(parent.audioStartTime), "-to", String.valueOf(parent.audioEndTime),
                    "-i", parent.selectedAudio.getAbsolutePath(),
                    "-af", String.format("afade=t=in:st=0:d=0.1,afade=t=out:st=%.2f:d=4", fadeOutSt),
                    "-map", "0:a:0", "-map_metadata", "-1", "-vn", "-sn",
                    "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", "-f", "wav",
                    previewClipFile.getAbsolutePath()
            );
            Process p = pb.start();
            boolean completed = p.waitFor(60, TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                System.err.println("警告：试听渲染超时，已强制终止");
                return;
            }

            if (parent.playbackClip != null) { parent.playbackClip.stop(); parent.playbackClip.flush(); parent.playbackClip.close(); }

            AudioInputStream ais = AudioSystem.getAudioInputStream(previewClipFile);
            try {
                parent.playbackClip = AudioSystem.getClip();
                parent.playbackClip.open(ais);

                SwingUtilities.invokeLater(() -> {
                    parent.isFadedPlaying = true;
                    parent.playbackClip.start();
                    parent.updateResultPanel();
                });
            } finally {
                ais.close();
            }
        } catch (Exception e) { e.printStackTrace(); }
    });
}
/**
 * 根据特定 version 格式执行等级换算规则
 */
public static String getConvertedRMLevel(MczTool parent, String version, String originalLevel) {
    // 1. 识别特定格式：RM xK [难度] Lv.xx
    String regex = "(?i)RM\\s+\\d+K\\s+(Easy|Normal|Hard|Master|Special)\\s+Lv\\.\\d+";
    if (!version.matches(regex)) {
        return originalLevel;
    }

    int lv;
    try {
        lv = Integer.parseInt(originalLevel);
    } catch (Exception e) {
        return originalLevel;
    }

    // 2. 0 及以下保留原等级
    if (lv <= 0) return originalLevel;

    // 3. 执行详细换算区间
    if (lv >= 1 && lv <= 2) return "1";
    if (lv >= 3 && lv <= 4) return "2";
    if (lv >= 5 && lv <= 6) return "3";
    if (lv >= 7 && lv <= 8) return "4";
    if (lv >= 9 && lv <= 10) return "5";
    if (lv >= 11 && lv <= 12) return "6";
    if (lv >= 13 && lv <= 14) return "7";
    if (lv >= 15 && lv <= 16) return "8";
    if (lv >= 17 && lv <= 18) return "9";
    if (lv >= 19 && lv <= 20) return "10";
    if (lv >= 21 && lv <= 23) return "11";
    if (lv >= 24 && lv <= 26) return "12";
    if (lv >= 27 && lv <= 29) return "13";
    if (lv >= 30 && lv <= 32) return "14";
    if (lv >= 33) return "15";

    return originalLevel;
}
// 简易版的后台日志输出（防止找不到符号报错）
public static void log(MczTool parent, String msg) {
    System.out.println(msg);
}
}
