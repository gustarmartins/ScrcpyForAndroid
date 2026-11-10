package com.mekki.scrcpy.usb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A single logical ADB stream (one opened service such as {@code shell:},
 * {@code sync:} or {@code tcp:7007}). Payloads arriving from the device are
 * buffered and can be read; writes are throttled by the ADB flow control rule
 * that only one unacknowledged WRTE may be outstanding at a time.
 */
public class AdbStream {

    private final AdbConnection connection;
    private final int localId;

    private volatile int remoteId;
    private volatile boolean open;
    private volatile boolean closed;
    private boolean writeReady;

    private final Object openLock = new Object();
    private final LinkedBlockingQueue<byte[]> readQueue = new LinkedBlockingQueue<>();

    /** Sentinel pushed onto the read queue when the stream is closed. */
    private static final byte[] CLOSE_SENTINEL = new byte[0];

    AdbStream(AdbConnection connection, int localId) {
        this.connection = connection;
        this.localId = localId;
    }

    int getLocalId() {
        return localId;
    }

    int getRemoteId() {
        return remoteId;
    }

    /** Called by the connection reader when the device acknowledges OPEN. */
    void onOkay(int remoteId) {
        synchronized (openLock) {
            this.remoteId = remoteId;
            this.open = true;
            openLock.notifyAll();
        }
        synchronized (this) {
            writeReady = true;
            notifyAll();
        }
    }

    /** Called by the connection reader when a WRTE payload arrives. */
    void onPayload(byte[] data) {
        if (data != null && data.length > 0) {
            readQueue.add(data);
        }
    }

    /** Called by the connection reader when the device closes the stream. */
    void onClose() {
        closed = true;
        synchronized (openLock) {
            openLock.notifyAll();
        }
        synchronized (this) {
            notifyAll();
        }
        readQueue.add(CLOSE_SENTINEL);
    }

    /** Wait until the device acknowledges the OPEN (or refuses it). */
    void waitForOpen(long timeoutMs) throws IOException, InterruptedException {
        synchronized (openLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!open && !closed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IOException("Timed out opening adb stream");
                }
                openLock.wait(remaining);
            }
            if (closed) {
                throw new IOException("Stream was closed by the device before opening");
            }
        }
    }

    /**
     * Write a payload to the device, chunking it to the negotiated max size and
     * waiting for an OKAY between chunks.
     */
    public void write(byte[] payload) throws IOException, InterruptedException {
        int maxData = connection.getMaxData();
        int offset = 0;
        do {
            int length = Math.min(maxData, payload.length - offset);
            byte[] chunk;
            if (offset == 0 && length == payload.length) {
                chunk = payload;
            } else {
                chunk = new byte[length];
                System.arraycopy(payload, offset, chunk, 0, length);
            }
            sendChunk(chunk);
            offset += length;
        } while (offset < payload.length);
    }

    private void sendChunk(byte[] chunk) throws IOException, InterruptedException {
        synchronized (this) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!writeReady && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("ADB write stalled");
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            if (closed) {
                throw new IOException("Stream closed");
            }
            writeReady = false;
        }
        connection.sendWrite(this, chunk);
    }

    /** Called by the connection reader when the device OKAYs our WRTE. */
    void onWriteAck() {
        synchronized (this) {
            writeReady = true;
            notifyAll();
        }
    }

    /**
     * Read the next payload from the device, or {@code null} if the stream has
     * been closed and drained.
     */
    public byte[] read() throws InterruptedException, IOException {
        byte[] data = readQueue.take();
        if (data == CLOSE_SENTINEL) {
            // Put it back so subsequent reads also observe the close.
            readQueue.add(CLOSE_SENTINEL);
            return null;
        }
        connection.acknowledge(this);
        return data;
    }

    /** Read with a timeout. Returns null on timeout or close. */
    public byte[] read(long timeoutMs) throws InterruptedException, IOException {
        byte[] data = readQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data == null) {
            return null;
        }
        if (data == CLOSE_SENTINEL) {
            readQueue.add(CLOSE_SENTINEL);
            return null;
        }
        connection.acknowledge(this);
        return data;
    }

    public InputStream inputStream() {
        return new InputStream() {
            byte[] current; int position;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (current == null || position == current.length) {
                    try { current = AdbStream.this.read(); position = 0; }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", e); }
                    if (current == null) return -1;
                }
                int n = Math.min(length, current.length - position);
                System.arraycopy(current, position, buffer, offset, n); position += n;
                return n;
            }
            @Override public int available() {
                int n = current == null ? 0 : current.length - position;
                // Let the consumer observe EOF instead of polling forever.
                return n > 0 ? n : (!readQueue.isEmpty() || closed ? 1 : 0);
            }
            @Override public void close() { AdbStream.this.close(); }
        };
    }

    public OutputStream outputStream() {
        return new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                try { AdbStream.this.write(Arrays.copyOfRange(data, offset, offset + length)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", e); }
            }
            @Override public void close() { AdbStream.this.close(); }
        };
    }

    public boolean isClosed() {
        return closed;
    }

    /** Actively close the stream, notifying the device. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connection.closeStream(this);
        synchronized (openLock) { openLock.notifyAll(); }
        synchronized (this) {
            notifyAll();
        }
        readQueue.add(CLOSE_SENTINEL);
    }
}
