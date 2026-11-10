package com.mekki.scrcpy.server;


import android.util.Log;
import android.net.LocalSocket;
import android.net.LocalServerSocket;

import com.mekki.scrcpy.server.model.ByteUtils;
import com.mekki.scrcpy.server.model.CommandPacket;
import com.mekki.scrcpy.server.model.ControlPacket;
import com.mekki.scrcpy.server.model.MediaPacket;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public final class DroidConnection implements Closeable {


    private Closeable socket;
    private OutputStream outputStream;
    private DataInputStream inputStream;

    private DroidConnection(Socket socket) throws IOException {
        this.socket = socket;

        inputStream = new DataInputStream(socket.getInputStream());
        outputStream = socket.getOutputStream();
    }

    private DroidConnection(LocalSocket socket) throws IOException {
        this.socket = socket;
        inputStream = new DataInputStream(socket.getInputStream());
        outputStream = socket.getOutputStream();
    }

    public static DroidConnection open(String ip, String name) throws IOException {
        if (name == null || name.isEmpty()) return open(ip);
        if (!name.matches("mekki-mirror-[a-f0-9]+")) throw new IOException("Invalid session socket");
        LocalServerSocket server = new LocalServerSocket(name);
        try {
            android.system.StructPollfd fd = new android.system.StructPollfd();
            fd.fd = server.getFileDescriptor();
            fd.events = (short) android.system.OsConstants.POLLIN;
            if (android.system.Os.poll(new android.system.StructPollfd[]{fd}, 15000) == 0) {
                throw new java.net.SocketTimeoutException("Client did not open the mirroring channel");
            }
            return new DroidConnection(server.accept());
        } catch (android.system.ErrnoException e) { throw new IOException("Unable to accept mirroring channel", e); }
        finally { server.close(); }
    }


    private static Socket listenAndAccept() throws IOException {
        ServerSocket serverSocket = new ServerSocket(7007);
        Socket sock = null;
        try {
            sock = serverSocket.accept();
        } finally {
            serverSocket.close();
        }
        return sock;
    }

    public static DroidConnection open(String ip) throws IOException {

        Socket socket = listenAndAccept();
        DroidConnection connection = null;
//        if (socket.getInetAddress().toString().equals(ip)) {
//            connection = new DroidConnection(socket);
//        }
        if (!socket.getInetAddress().toString().equals(ip)) {
            Ln.w("socket connect address != " + ip);
        }
        // 判断 socket 有一个正确的地址
        if (!socket.getInetAddress().toString().isEmpty()) {
            connection = new DroidConnection(socket);
        }
        return connection;
    }

    public void close() throws IOException {
        socket.close();
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }


    /**
     *
     * @return
     * @throws IOException
     */
    public MediaPacket NewReceiveEvent() throws IOException {

        byte[] packetSize = new byte[4];
        inputStream.readFully(packetSize, 0, packetSize.length);

        int size = ByteUtils.bytesToInt(packetSize);

        if (size > 4 * 1024 * 1024) {  // 如果单个数据包大于 4m ，直接断开连接
            throw new EOFException("Event controller socket closed");
        }
        byte[] packet = new byte[size];
        inputStream.readFully(packet, 0, size);

        MediaPacket.Type type = MediaPacket.Type.getType(packet[0]);
        switch (type) {
            case CONTROL:
                return new ControlPacket().fromArray(packet);
            case COMMAND:
                return new CommandPacket().fromArray(packet);
        }


//        byte[] buf = new byte[20];
//        int n = inputStream.read(buf, 0, 20);
//        if (n == -1) {
//            throw new EOFException("Event controller socket closed");
//        }
//
//        final int[] array = new int[buf.length / 4];
//        for (int i = 0; i < array.length; i++)
//            array[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
//                    (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
//                    (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
//                    ((int) (buf[i * 4 + 3]) & 0xFF);
//        return array;


        return null;

    }

}

