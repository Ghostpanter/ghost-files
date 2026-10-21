package com.ghostpanter.scrcpy;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// MP4 file for StreamRecorder: MediaMuxer passthrough for video / Opus, AAC-LC encoder for PCM.
// Output goes to the configured folder, or by default Movies/双栏文件管理器 (MediaStore on
// API 29+, a plain file on API 28).
public final class MediaMuxerSink implements Mp4Sink {

    public static final String DEFAULT_FOLDER = "双栏文件管理器";

    private final Context ctx;
    private final MediaMuxer muxer;
    private final File file;              // file output, or null
    private final Uri uri;                // MediaStore output, or null
    private final ParcelFileDescriptor pfd;
    private boolean started;
    private long samples;

    // AAC encoder for PCM input
    private MediaCodec aac;
    private int aacTrack = -1;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private MediaMuxerSink(Context ctx, File file, Uri uri, ParcelFileDescriptor pfd) throws IOException {
        this.ctx = ctx;
        this.file = file;
        this.uri = uri;
        this.pfd = pfd;
        this.muxer = file != null
                ? new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                : new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** Factory writing "<base>[-n].mp4" into [customDir] (absolute path) or the default folder. */
    public static Mp4Sink.Factory factory(Context ctx, String baseName, String customDir) {
        final Context app = ctx.getApplicationContext();
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new Mp4Sink.Factory() {
            @Override public Mp4Sink open(int segment) throws IOException {
                String name = baseName + "-" + stamp + (segment > 1 ? "-" + segment : "") + ".mp4";
                if (customDir != null && !customDir.trim().isEmpty()) {
                    File dir = new File(customDir.trim());
                    if (dir.isDirectory() || dir.mkdirs()) return new MediaMuxerSink(app, new File(dir, name), null, null);
                    Log.w("recorder: custom folder %s not writable; using default", customDir);
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + DEFAULT_FOLDER);
                    v.put(MediaStore.Video.Media.IS_PENDING, 1);
                    ContentResolver cr = app.getContentResolver();
                    Uri u = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                    if (u == null) throw new IOException("MediaStore refused a new video");
                    ParcelFileDescriptor fd = cr.openFileDescriptor(u, "rw");
                    if (fd == null) { cr.delete(u, null, null); throw new IOException("cannot open " + u); }
                    try {
                        return new MediaMuxerSink(app, null, u, fd);
                    } catch (IOException e) {
                        fd.close();
                        cr.delete(u, null, null);
                        throw e;
                    }
                }
                @SuppressWarnings("deprecation")
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), DEFAULT_FOLDER);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    if (dir == null) throw new IOException("no writable Movies folder");
                    dir.mkdirs();
                }
                return new MediaMuxerSink(app, new File(dir, name), null, null);
            }

            @Override public boolean supportsVideo(int fourcc) {
                if (fourcc == Wire.CODEC_H264 || fourcc == Wire.CODEC_H265) return true;
                // MPEG4Writer gained av01 in Android 14.
                return fourcc == Wire.CODEC_AV1 && Build.VERSION.SDK_INT >= 34;
            }

            @Override public boolean supportsOpusPassthrough() {
                return Build.VERSION.SDK_INT >= 29;
            }
        };
    }

    /** Human location for toasts: path, or Movies/双栏文件管理器/<name>. */
    public static String defaultLocationLabel() {
        return Environment.DIRECTORY_MOVIES + "/" + DEFAULT_FOLDER;
    }

    private static String mimeFor(int fourcc) {
        if (fourcc == Wire.CODEC_H264) return MediaFormat.MIMETYPE_VIDEO_AVC;
        if (fourcc == Wire.CODEC_H265) return MediaFormat.MIMETYPE_VIDEO_HEVC;
        if (fourcc == Wire.CODEC_AV1) return "video/av01";
        return null;
    }

    @Override
    public int addVideo(int fourcc, int width, int height, byte[][] csd) throws IOException {
        String mime = mimeFor(fourcc);
        if (mime == null) throw new StreamRecorder.UnsupportedCodecException(fourcc);
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        for (int i = 0; i < csd.length; i++) f.setByteBuffer("csd-" + i, ByteBuffer.wrap(csd[i]));
        try {
            return muxer.addTrack(f);
        } catch (RuntimeException e) {
            throw (IOException) new StreamRecorder.UnsupportedCodecException(fourcc).initCause(e);
        }
    }

    @Override
    public int addOpus(byte[] head) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 2);
        int preSkip = head.length >= 12 ? (head[10] & 0xff) | ((head[11] & 0xff) << 8) : 312;
        f.setByteBuffer("csd-0", ByteBuffer.wrap(head));
        f.setByteBuffer("csd-1", le64(preSkip * 1_000_000_000L / 48_000));
        f.setByteBuffer("csd-2", le64(80_000_000L));
        try {
            return muxer.addTrack(f);
        } catch (RuntimeException e) {
            throw new IOException("opus track rejected", e);
        }
    }

    private static ByteBuffer le64(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(v).flip();
        return b;
    }

    @Override
    public int addPcm(int sampleRate, int channels) throws IOException {
        MediaFormat enc = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        enc.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        enc.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        enc.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
        aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aac.configure(enc, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aac.start();
        // The muxer needs the track before start(): AudioSpecificConfig for AAC-LC 48 kHz stereo.
        MediaFormat track = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        track.setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig(sampleRate, channels)));
        aacTrack = muxer.addTrack(track);
        return aacTrack;
    }

    /** ISO 14496-3 AudioSpecificConfig, AAC-LC. */
    static byte[] audioSpecificConfig(int rate, int channels) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        int idx = 3;
        for (int i = 0; i < rates.length; i++) if (rates[i] == rate) idx = i;
        int v = (2 << 11) | (idx << 7) | (channels << 3);
        return new byte[] { (byte) (v >> 8), (byte) v };
    }

    @Override public void start() { muxer.start(); started = true; }

    @Override
    public void writeVideo(int track, byte[] data, int off, int len, long ptsUs, boolean keyframe) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, len, ptsUs, keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        muxer.writeSampleData(track, ByteBuffer.wrap(data, off, len).slice(), bi);
        samples++;
    }

    @Override
    public void writeOpus(int track, byte[] data, int off, int len, long ptsUs) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, len, ptsUs, 0);
        muxer.writeSampleData(track, ByteBuffer.wrap(data, off, len).slice(), bi);
    }

    @Override
    public void writePcm(int track, byte[] data, int off, int len, long ptsUs) {
        MediaCodec c = aac;
        if (c == null) return;
        int end = off + len;
        long pts = ptsUs;
        while (off < end) {
            int in = c.dequeueInputBuffer(10_000);
            if (in < 0) { drainAac(false); continue; }
            ByteBuffer b = c.getInputBuffer(in);
            int n = Math.min(b.remaining(), end - off);
            b.put(data, off, n);
            c.queueInputBuffer(in, 0, n, pts, 0);
            off += n;
            pts += n / 4L * 1_000_000L / 48_000; // s16 stereo frames
            drainAac(false);
        }
    }

    private void drainAac(boolean eos) {
        MediaCodec c = aac;
        if (c == null) return;
        while (true) {
            int out = c.dequeueOutputBuffer(info, eos ? 10_000 : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return; else return; }
            if (out < 0) continue; // format / buffers changed
            ByteBuffer b = c.getOutputBuffer(out);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (b != null && info.size > 0 && !config && started) {
                b.position(info.offset).limit(info.offset + info.size);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                bi.set(0, info.size, info.presentationTimeUs, 0);
                muxer.writeSampleData(aacTrack, b.slice(), bi);
            }
            c.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    @Override
    public String finish() throws IOException {
        MediaCodec c = aac;
        if (c != null) {
            try {
                int in = c.dequeueInputBuffer(10_000);
                if (in >= 0) c.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drainAac(true);
            } catch (RuntimeException ignored) {
            }
            try { c.stop(); } catch (RuntimeException ignored) {}
            c.release();
            aac = null;
        }
        boolean ok = false;
        try {
            if (started && samples > 0) { muxer.stop(); ok = true; }
        } catch (RuntimeException e) {
            Log.w("recorder: muxer stop failed: %s", e);
        } finally {
            try { muxer.release(); } catch (RuntimeException ignored) {}
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
        }
        if (file != null) {
            if (!ok) { file.delete(); return null; }
            MediaScannerConnection.scanFile(ctx, new String[] { file.getPath() }, null, null);
            return file.getPath();
        }
        ContentResolver cr = ctx.getContentResolver();
        if (!ok) { cr.delete(uri, null, null); return null; }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return defaultLocationLabel() + "/" + displayName(cr, uri);
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (android.database.Cursor c = cr.query(u, new String[] { MediaStore.Video.Media.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return u.getLastPathSegment();
    }
}
