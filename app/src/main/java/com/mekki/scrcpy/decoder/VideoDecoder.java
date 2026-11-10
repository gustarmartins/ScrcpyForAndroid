package com.mekki.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Bounded codec waits, hardware decoding and immediate surface presentation. */
public final class VideoDecoder {
    private final Object lock = new Object();
    private MediaCodec codec;
    private Thread drainThread;
    private volatile boolean running;
    private volatile RuntimeException failure;
    private long rendered;

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            failure = null;
            drainThread = new Thread(this::drain, "mirror-video-output");
            drainThread.start();
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            releaseCodec();
        }
        Thread thread = drainThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try { thread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void releaseCodec() {
        if (codec == null) return;
        try { codec.stop(); } catch (RuntimeException ignored) { }
        codec.release();
        codec = null;
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
        configure(surface, width, height, csd0, csd1, "video/avc");
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1, String mime) {
        synchronized (lock) {
            if (!running || surface == null || !surface.isValid()) return;
            releaseCodec();
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            if (csd0 != null) format.setByteBuffer("csd-0", csd0.duplicate());
            if (csd1 != null && "video/avc".equals(mime)) format.setByteBuffer("csd-1", csd1.duplicate());
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setFloat(MediaFormat.KEY_OPERATING_RATE, 120);
            String selected = null;
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated())) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                    if (!caps.getVideoCapabilities().isSizeSupported(width, height)) continue;
                    if (Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    }
                    selected = info.getName(); break;
                } catch (IllegalArgumentException ignored) { }
            }
            try {
                codec = selected == null ? MediaCodec.createDecoderByType(mime) : MediaCodec.createByCodecName(selected);
                com.mekki.scrcpy.utils.SessionLog.i("Decoder=" + codec.getName() + " size=" + width + "x" + height);
                codec.configure(format, surface, null, 0);
                codec.start();
            } catch (IOException | RuntimeException e) {
                releaseCodec();
                throw new IllegalStateException("Cannot decode " + mime + " at " + width + "x" + height, e);
            }
        }
    }

    public void decodeSample(byte[] data, int offset, int size, long pts, int flags) {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (running) {
            if (failure != null) throw failure;
            synchronized (lock) {
                if (codec == null) return;
                int index = codec.dequeueInputBuffer(0);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    if (buffer == null || size > buffer.capacity()) throw new IllegalStateException("Encoded frame exceeds decoder capacity");
                    buffer.clear(); buffer.put(data, offset, size);
                    codec.queueInputBuffer(index, 0, size, pts, flags);
                    return;
                }
            }
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Video decoder stalled; choose a lower resolution or frame rate");
            try { Thread.sleep(1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long started = System.nanoTime(), lastCount = 0;
        try {
            while (running) {
                boolean idle;
                synchronized (lock) {
                    idle = codec == null;
                    if (!idle) {
                        int index = codec.dequeueOutputBuffer(info, 0);
                        if (index >= 0) {
                            codec.releaseOutputBuffer(index, true);
                            rendered++;
                        } else idle = true;
                    }
                }
                // Never hold the codec lifecycle lock while waiting for buffers:
                // the input thread must be able to feed the next 120 Hz frame.
                if (idle) Thread.sleep(1);
                long now = System.nanoTime();
                if (now - started >= 2_000_000_000L) {
                    com.mekki.scrcpy.utils.SessionLog.i("Rendered fps=" + ((rendered - lastCount) * 1_000_000_000L / (now - started)));
                    started = now; lastCount = rendered;
                }
                Thread.yield();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            failure = new IllegalStateException("Video decoder stopped: " + e.getMessage(), e);
            Log.e("Scrcpy", "Decoder output failed", e);
        }
    }
}
