package com.vsp.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 音视频封装工具。
 *
 * 用系统自带的 MediaExtractor + MediaMuxer 做「无损封装」：
 * 把两条轨道原样搬进同一个 mp4，不解码也不重编码，
 * 等价于 ffmpeg 的 -c copy，但不用把几十 MB 的 ffmpeg 打进安装包。
 */
public final class MediaUtil {

    private MediaUtil() {
    }

    private static final int BUF_SIZE = 4 * 1024 * 1024;

    /**
     * 合并画面轨与音轨。
     *
     * @param videoPath 含画面（可能没有声音）的文件
     * @param audioPath 含音轨的文件
     * @param outPath   输出的 mp4
     * @return 成功返回 true；轨道不兼容（例如 opus 装不进 mp4）时返回 false
     */
    public static boolean merge(String videoPath, String audioPath, String outPath) {
        MediaExtractor vEx = null, aEx = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            vEx = new MediaExtractor();
            vEx.setDataSource(videoPath);
            aEx = new MediaExtractor();
            aEx.setDataSource(audioPath);

            int vTrack = -1, aTrack = -1;
            for (int i = 0; i < vEx.getTrackCount(); i++) {
                String mime = mimeOf(vEx.getTrackFormat(i));
                if (mime != null && mime.startsWith("video/")) {
                    vTrack = i;
                    break;
                }
            }
            for (int i = 0; i < aEx.getTrackCount(); i++) {
                String mime = mimeOf(aEx.getTrackFormat(i));
                if (mime != null && mime.startsWith("audio/")) {
                    aTrack = i;
                    break;
                }
            }
            if (vTrack < 0 || aTrack < 0) return false;

            vEx.selectTrack(vTrack);
            aEx.selectTrack(aTrack);

            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = muxer.addTrack(vEx.getTrackFormat(vTrack));
            int aOut = muxer.addTrack(aEx.getTrackFormat(aTrack));
            muxer.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(BUF_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            copyTrack(vEx, vOut, buf, info, muxer, true);
            copyTrack(aEx, aOut, buf, info, muxer, false);

            muxer.stop();
            started = false;
            aEx.release();
            aEx = null;
            vEx.release();
            vEx = null;
            muxer.release();
            muxer = null;
            return new File(outPath).length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Throwable ignored) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (aEx != null) {
                try {
                    aEx.release();
                } catch (Throwable ignored) {
                }
            }
            if (vEx != null) {
                try {
                    vEx.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 截取 [startUs, endUs] 片段：关键帧对齐，按时间戳偏移后重新封装，不重编码。
     */
    public static boolean clip(Context ctx, String source, String outPath, long startUs, long endUs) {
        MediaExtractor ex = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            ex = new MediaExtractor();
            if (source != null && source.startsWith("content://")) {
                ex.setDataSource(ctx, Uri.parse(source), null);
            } else {
                ex.setDataSource(source);
            }
            int video = -1, audio = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = mimeOf(ex.getTrackFormat(i));
                if (video < 0 && mime != null && mime.startsWith("video/")) video = i;
                if (audio < 0 && mime != null && mime.startsWith("audio/")) audio = i;
            }
            if (video < 0 && audio < 0) return false;

            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = -1, aOut = -1;
            if (video >= 0) vOut = muxer.addTrack(ex.getTrackFormat(video));
            if (audio >= 0) aOut = muxer.addTrack(ex.getTrackFormat(audio));
            muxer.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(BUF_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            if (video >= 0) {
                ex.selectTrack(video);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                copyRange(ex, vOut, buf, info, muxer, startUs, endUs, true);
                ex.unselectTrack(video);
            }
            if (audio >= 0) {
                ex.selectTrack(audio);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                copyRange(ex, aOut, buf, info, muxer, startUs, endUs, false);
            }
            muxer.stop();
            started = false;
            return new File(outPath).length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Throwable ignored) { }
                try { muxer.release(); } catch (Throwable ignored) { }
            }
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void copyRange(MediaExtractor ex, int outTrack, ByteBuffer buf,
                                  MediaCodec.BufferInfo info, MediaMuxer muxer,
                                  long startUs, long endUs, boolean skipBeforeKey) throws Exception {
        boolean first = true;
        while (true) {
            long t = ex.getSampleTime();
            if (t < 0 || t > endUs) break;
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            int flags = ex.getSampleFlags();
            if (t < startUs) { ex.advance(); continue; }
            if (first && skipBeforeKey && (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                ex.advance();
                continue;
            }
            first = false;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = t - startUs;
            info.flags = flags;
            muxer.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    private static String mimeOf(MediaFormat f) {
        try {
            return f.getString(MediaFormat.KEY_MIME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void copyTrack(MediaExtractor ex, int outTrack, ByteBuffer buf,
                                  MediaCodec.BufferInfo info, MediaMuxer muxer,
                                  boolean skipUntilKeyFrame) throws Exception {
        boolean first = true;
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            int flags = ex.getSampleFlags();
            // mp4 要求画面第一帧是关键帧，否则部分播放器会黑屏
            if (first && skipUntilKeyFrame && (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                ex.advance();
                continue;
            }
            first = false;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = flags;
            muxer.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    /**
     * 提取音轨：把视频里的音频单独导出成 m4a（同样是 -c copy）。
     *
     * @param source 文件路径或 content:// Uri
     */
    public static boolean extractAudio(Context ctx, String source, String outPath) {
        MediaExtractor ex = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            ex = new MediaExtractor();
            if (source != null && source.startsWith("content://")) {
                ex.setDataSource(ctx, Uri.parse(source), null);
            } else {
                ex.setDataSource(source);
            }
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = mimeOf(ex.getTrackFormat(i));
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) return false;
            ex.selectTrack(track);

            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(ex.getTrackFormat(track));
            muxer.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(BUF_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            copyTrack(ex, outTrack, buf, info, muxer, false);

            muxer.stop();
            started = false;
            return new File(outPath).length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Throwable ignored) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (ex != null) {
                try {
                    ex.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
