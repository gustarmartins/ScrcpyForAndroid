package com.mekki.scrcpy.usb;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.Cipher;

public class AdbTransportTest {
    static class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        Socket socket; DataInputStream in; OutputStream out;
        Peer() throws IOException { }
        AdbProtocol.Message read() throws IOException {
            byte[] header = new byte[24]; in.readFully(header);
            AdbProtocol.Message message = AdbProtocol.parseHeader(header);
            message.payload = new byte[message.payloadLength]; in.readFully(message.payload);
            return message;
        }
        void send(int command, int a, int b, byte[] payload) throws IOException {
            byte[] header = AdbProtocol.generateHeader(command,a,b,payload);
            // Deliberately fragmented: TCP never guarantees message boundaries.
            for (byte part : header) out.write(part);
            out.write(payload); out.flush();
        }
        void accept() throws IOException {
            socket = listener.accept(); socket.setSoTimeout(3000);
            in = new DataInputStream(socket.getInputStream()); out = socket.getOutputStream();
        }
        public void close() throws IOException { listener.close(); if (socket != null) socket.close(); }
    }

    @Test public void authenticatedTcpSupportsFragmentedStreamsAndClose() throws Exception {
        Path keys = Files.createTempDirectory("mirror-test-keys");
        AdbCrypto crypto = AdbCrypto.loadOrGenerate(keys.toFile());
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(keys.resolve("adbkey_usb.pub"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Peer peer = new Peer()) {
            Future<?> server = executor.submit(() -> {
                try {
                    peer.accept(); assertEquals(AdbProtocol.A_CNXN, peer.read().command);
                    byte[] challenge = new byte[20]; new SecureRandom().nextBytes(challenge);
                    peer.send(AdbProtocol.A_AUTH,1,0,challenge);
                    AdbProtocol.Message signature = peer.read();
                    assertEquals(2, signature.arg0);
                    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.DECRYPT_MODE, publicKey);
                    byte[] signed = rsa.doFinal(signature.payload);
                    assertArrayEquals(challenge, Arrays.copyOfRange(signed, signed.length-20, signed.length));
                    peer.send(AdbProtocol.A_CNXN,AdbProtocol.CONNECT_VERSION,AdbProtocol.CONNECT_MAXDATA,"device::\0".getBytes());
                    AdbProtocol.Message open = peer.read(); assertEquals(AdbProtocol.A_OPEN,open.command);
                    peer.send(AdbProtocol.A_OKAY,11,open.arg0,new byte[0]);
                    peer.send(AdbProtocol.A_WRTE,11,open.arg0,"hello".getBytes());
                    assertEquals(AdbProtocol.A_OKAY,peer.read().command);
                    peer.send(AdbProtocol.A_WRTE,11,open.arg0," world".getBytes());
                    assertEquals(AdbProtocol.A_OKAY,peer.read().command);
                    peer.send(AdbProtocol.A_CLSE,11,open.arg0,new byte[0]);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            AdbConnection connection = AdbConnection.createTcp("127.0.0.1",peer.listener.getLocalPort(),crypto,1000);
            try {
                connection.connect(3000);
                AdbStream stream = connection.open("shell:echo hello world",2000);
                InputStream input = stream.inputStream();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                int value; while ((value=input.read())!=-1) received.write(value);
                assertEquals("hello world",received.toString("UTF-8"));
                server.get(4,TimeUnit.SECONDS);
            } finally { connection.close(); }
        } finally { executor.shutdownNow(); for(File file:keys.toFile().listFiles())file.delete();Files.delete(keys); }
    }

    @Test public void malformedLengthFailsWithoutAllocatingPayload() throws Exception {
        Path keys=Files.createTempDirectory("mirror-bad-packet");
        AdbCrypto crypto=AdbCrypto.loadOrGenerate(keys.toFile());
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Peer peer=new Peer()) {
            Future<?> server=executor.submit(() -> {
                try {
                    peer.accept();peer.read();
                    byte[] header=AdbProtocol.generateHeader(AdbProtocol.A_CNXN,0,0,new byte[0]);
                    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(12,Integer.MAX_VALUE);
                    peer.out.write(header);peer.out.flush();
                }catch(Exception e){throw new RuntimeException(e);}
            });
            AdbConnection c=AdbConnection.createTcp("127.0.0.1",peer.listener.getLocalPort(),crypto,1000);
            try { c.connect(2000); fail("malformed peer accepted"); }
            catch(IOException expected) { assertTrue(expected.getMessage().contains("Invalid ADB packet")); }
            finally { c.close(); }
            server.get(3,TimeUnit.SECONDS);
        } finally { executor.shutdownNow();for(File file:keys.toFile().listFiles())file.delete();Files.delete(keys); }
    }

    @Test public void endpointsRequirePortAndPreserveIpv6() {
        assertEquals(6666,Endpoint.parse("100.64.0.2:6666").port);
        assertEquals("fd7a::123",Endpoint.parse("[fd7a::123]:6666").host);
        assertEquals(2,Endpoint.candidates("host:6666, 192.168.1.2:6666").size());
        for(String bad:new String[]{"100.64.0.2","host:0","host:65536","host:abc","fd7a::123:6666","host:6666,","a b:6666"}) {
            try { Endpoint.candidates(bad);fail("Accepted "+bad); }catch(IllegalArgumentException expected) { }
        }
    }

    @Test public void localRouteMatchingRespectsActualPrefix() throws Exception {
        byte[] target=InetAddress.getByName("192.168.7.9").getAddress();
        assertTrue(Endpoint.sameSubnet(target,InetAddress.getByName("192.168.7.50").getAddress(),24));
        assertFalse(Endpoint.sameSubnet(target,InetAddress.getByName("192.168.8.50").getAddress(),24));
        assertFalse(Endpoint.sameSubnet(target,InetAddress.getByName("100.64.1.1").getAddress(),32));
        assertFalse(Endpoint.sameSubnet(target,InetAddress.getByName("::1").getAddress(),24));
    }
}
