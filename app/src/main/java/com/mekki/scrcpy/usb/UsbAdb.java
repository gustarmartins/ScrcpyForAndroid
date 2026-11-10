package com.mekki.scrcpy.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import com.mekki.scrcpy.Options;

/**
 * High level entry point for driving a device over USB using a pure-Java ADB
 * implementation (no root, no native adb binary).
 * <p>
 * The lifecycle mirrors the WiFi flow in {@code SendCommands}: connect, push
 * {@code scrcpy-server.jar}, forward a local TCP port and finally launch the
 * server via a shell command. Once {@link #startServerAndForward} returns
 * successfully, the existing Scrcpy service can connect to
 * {@code 127.0.0.1:localForwardPort} exactly as it does over WiFi.
 */
public class UsbAdb {

    private static final String TAG = "Scrcpy";

    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long STREAM_TIMEOUT_MS = 10_000;
    private static final int SYNC_DATA_MAX = 64 * 1024;
    private static final String REMOTE_JAR_PATH = "/data/local/tmp/scrcpy-server.jar";
    /** Device-side port the scrcpy server listens on (see WiFi flow). */
    private static final int REMOTE_SERVER_PORT = 7007;

    private final AdbConnection connection;
    private AdbStream serverStream;
    private AdbForwardBridge bridge;
    private AdbStream videoStream;
    private String socketName;
    private volatile String serverLog = "";
    private volatile boolean closed;

    private UsbAdb(AdbConnection connection) {
        this.connection = connection;
    }

    public static UsbAdb connectTcp(Context context, String host, int port) throws IOException, InterruptedException {
        AdbCrypto crypto;
        try { crypto = AdbCrypto.loadOrGenerate(new File(context.getFilesDir(), ".android")); }
        catch (Exception e) { throw new IOException("Unable to load the saved debugging identity", e); }
        android.net.Network wifi = Endpoint.localWifi(context, host);
        java.net.Socket socket = wifi == null ? new java.net.Socket() : wifi.getSocketFactory().createSocket();
        AdbConnection connection = AdbConnection.createTcp(host, port, crypto, 4000, socket);
        try { connection.connect(6000); return new UsbAdb(connection); }
        catch (IOException | InterruptedException e) { connection.close(); throw e; }
    }

    public String shell(String command) throws IOException, InterruptedException {
        AdbStream stream = connection.open("shell:" + command, STREAM_TIMEOUT_MS);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + 10_000_000_000L;
        try {
            while (!stream.isClosed() && System.nanoTime() < deadline) {
                byte[] part = stream.read(1000);
                if (part != null) result.write(part);
                if (result.size() > 1024 * 1024) throw new IOException("Shell output exceeded limit");
            }
            // Drain final queued output, even if CLSE followed WRTE immediately.
            while (true) { byte[] part = stream.read(1); if (part == null) break; result.write(part); }
            if (!stream.isClosed()) throw new IOException("Device command timed out");
            return result.toString("UTF-8");
        } finally { stream.close(); }
    }

    public AdbStream openShell(String command) throws IOException, InterruptedException {
        return connection.open("shell:" + command, STREAM_TIMEOUT_MS);
    }

    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    public void startServer(File localFile, Options options) throws IOException, InterruptedException {
        socketName = "mekki-mirror-" + UUID.randomUUID().toString().replace("-", "");
        String remote = "/data/local/tmp/" + socketName + ".jar";
        pushFile(localFile, remote);
        StringBuilder command = new StringBuilder("shell:CLASSPATH=").append(quote(remote))
                .append(" app_process / com.mekki.scrcpy.server.Server");
        for (String arg : options.optionsToArgs()) command.append(' ').append(quote(arg));
        command.append(' ').append(quote(socketName)).append(' ').append(quote(remote));
        command.append(' ').append(options.getWakeScreen());
        serverStream = connection.open(command.toString(), STREAM_TIMEOUT_MS);
        Thread logs = new Thread(() -> {
            try {
                byte[] part;
                while ((part = serverStream.read()) != null) {
                    String line = new String(part, java.nio.charset.StandardCharsets.UTF_8);
                    com.mekki.scrcpy.utils.SessionLog.i("Device: " + line.trim());
                    serverLog = serverLog + line;
                    if (serverLog.length() > 4096) serverLog = serverLog.substring(serverLog.length() - 4096);
                }
            } catch (Exception e) { if (!closed) Log.w(TAG, "Device log ended", e); }
        }, "mirror-device-log");
        logs.setDaemon(true); logs.start();
        long deadline = System.nanoTime() + 8_000_000_000L;
        IOException failure = null;
        while (!closed && System.nanoTime() < deadline) {
            try { videoStream = connection.open("localabstract:" + socketName, 1000); return; }
            catch (IOException e) { failure = e; }
            if (serverStream.isClosed()) break;
            Thread.sleep(100);
        }
        throw new IOException("Mirroring server did not start. " + serverLog, failure);
    }

    public String getServerLog() { return serverLog; }

    public AdbStream getVideoStream() throws IOException {
        if (videoStream == null || closed) throw new IOException("Mirroring session is closed");
        return videoStream;
    }

    /** The first attached USB device exposing an ADB interface, or null. */
    public static UsbDevice findAdbDevice(UsbManager usbManager) {
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (AdbConnection.findAdbInterface(device) != null) {
                return device;
            }
        }
        // Fall back to the first device so we can surface a clearer error if a
        // device is attached but not in ADB mode yet.
        for (UsbDevice device : devices.values()) {
            return device;
        }
        return null;
    }

    /**
     * Open and authenticate a USB ADB connection. The device may show an
     * "Allow USB debugging?" dialog the first time.
     */
    public static UsbAdb connect(Context context, UsbManager usbManager, UsbDevice device)
            throws IOException, InterruptedException {
        UsbDeviceConnection deviceConnection = usbManager.openDevice(device);
        if (deviceConnection == null) {
            throw new IOException("Unable to open the USB device");
        }
        AdbCrypto crypto;
        try {
            File keyDir = new File(context.getFilesDir(), ".android");
            crypto = AdbCrypto.loadOrGenerate(keyDir);
        } catch (Exception e) {
            deviceConnection.close();
            throw new IOException("Failed to prepare ADB key", e);
        }

        AdbConnection connection;
        try {
            connection = AdbConnection.create(deviceConnection, device, crypto);
        } catch (IOException e) {
            deviceConnection.close();
            throw e;
        }
        try {
            connection.connect(CONNECT_TIMEOUT_MS);
        } catch (IOException | InterruptedException e) {
            connection.close();
            throw e;
        }
        Log.i(TAG, "USB ADB connected to " + device.getDeviceName());
        return new UsbAdb(connection);
    }

    /** Push a local file to the device using the ADB sync protocol. */
    public void pushFile(File localFile, String remotePath) throws IOException, InterruptedException {
        AdbStream sync = connection.open("sync:", STREAM_TIMEOUT_MS);
        try {
            String pathAndMode = remotePath + ",33188"; // regular file, 0644
            byte[] pathBytes = pathAndMode.getBytes("UTF-8");
            sync.write(syncRequest("SEND", pathBytes.length, pathBytes));

            byte[] fileBuffer = new byte[SYNC_DATA_MAX];
            FileInputStream in = new FileInputStream(localFile);
            try {
                int read;
                while ((read = in.read(fileBuffer)) != -1) {
                    byte[] header = syncHeader("DATA", read);
                    byte[] packet = new byte[header.length + read];
                    System.arraycopy(header, 0, packet, 0, header.length);
                    System.arraycopy(fileBuffer, 0, packet, header.length, read);
                    sync.write(packet);
                }
            } finally {
                in.close();
            }

            int mtime = (int) (System.currentTimeMillis() / 1000L);
            sync.write(syncHeader("DONE", mtime));

            byte[] response = readSyncResponse(sync);
            String status = new String(response, 0, 4, "UTF-8");
            if (!"OKAY".equals(status)) {
                throw new IOException("Push failed: " + describeSyncFailure(response));
            }
            try {
                sync.write(syncHeader("QUIT", 0));
            } catch (Exception ignore) {
                // Best effort.
            }
        } finally {
            sync.close();
        }
    }

    /**
     * Push the server jar, set up port forwarding and launch the scrcpy server.
     *
     * @param localFile        the local scrcpy-server.jar
     * @param localForwardPort local port the Scrcpy service will connect to
     * @param serverIp         value passed to the server (loopback, as WiFi)
     * @param bitrate          video bitrate
     * @param size             max screen dimension
     * @param enableAudio      whether to forward audio
     */
    public void startServerAndForward(File localFile, int localForwardPort, String serverIp,
                                      int bitrate, int size, boolean enableAudio)
            throws IOException, InterruptedException {
        pushFile(localFile, REMOTE_JAR_PATH);

        // Start forwarding before launching so the accept loop is ready.
        bridge = new AdbForwardBridge(connection, localForwardPort, REMOTE_SERVER_PORT);
        bridge.start();

        String command = "shell:CLASSPATH=" + REMOTE_JAR_PATH
                + " app_process / com.mekki.scrcpy.server.Server"
                + " " + serverIp
                + " " + size
                + " " + bitrate
                + " " + true
                + " " + enableAudio;
        serverStream = connection.open(command, STREAM_TIMEOUT_MS);
        Log.i(TAG, "USB scrcpy server launched: " + command);
    }

    public void close() {
        closed = true;
        // Closing the transport wakes all readers without sending packets from
        // an Activity lifecycle callback on Android's main thread.
        connection.close();
        if (videoStream != null) videoStream.close();
        if (bridge != null) {
            bridge.stop();
        }
        if (serverStream != null) {
            serverStream.close();
        }
    }

    private static byte[] syncRequest(String id, int length, byte[] payload) {
        byte[] header = syncHeader(id, length);
        byte[] request = new byte[header.length + payload.length];
        System.arraycopy(header, 0, request, 0, header.length);
        System.arraycopy(payload, 0, request, header.length, payload.length);
        return request;
    }

    private static byte[] syncHeader(String id, int value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(id.getBytes());
        buffer.putInt(value);
        return buffer.array();
    }

    private static byte[] readSyncResponse(AdbStream sync) throws IOException, InterruptedException {
        // The OKAY/FAIL reply may arrive in more than one payload; collect at
        // least the 8 byte header.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < 8) {
            byte[] data = sync.read();
            if (data == null) {
                break;
            }
            out.write(data);
        }
        byte[] response = out.toByteArray();
        if (response.length < 4) {
            throw new IOException("Truncated sync response");
        }
        return response;
    }

    private static String describeSyncFailure(byte[] response) {
        if (response.length >= 8) {
            ByteBuffer buffer = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.LITTLE_ENDIAN);
            int len = buffer.getInt();
            int available = response.length - 8;
            int msgLen = Math.min(len, available);
            if (msgLen > 0) {
                return new String(response, 8, msgLen);
            }
        }
        return "unknown error";
    }
}
