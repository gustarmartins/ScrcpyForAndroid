package com.mekki.scrcpy;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import com.mekki.scrcpy.usb.*;
import com.mekki.scrcpy.decoder.VideoDecoder;
import com.mekki.scrcpy.model.VideoPacket;
import com.mekki.scrcpy.utils.AdbHelper;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit, bounded debug-only qualification. Never starts without am instrument. */
public final class TransportProbe extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args=args;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        UsbAdb link=null;ImageReader images=null;VideoDecoder decoder=new VideoDecoder();
        HandlerThread handler=new HandlerThread("mirror-probe-images");handler.start();
        AtomicInteger decoded=new AtomicInteger();
        Thread watchdog=null;
        int outcome=0;
        try {
            Endpoint endpoint=Endpoint.parse(args.getString("endpoint"));
            link=UsbAdb.connectTcp(getTargetContext(),endpoint.host,endpoint.port);
            result.putString("identity",link.shell("getprop ro.product.model; cat /proc/sys/kernel/random/boot_id").trim());
            Options options=new Options();options.setIp("127.0.0.1");options.setTunnelForward(true);
            options.setMaxSize(Integer.parseInt(args.getString("maxSize","2340")));
            options.setFrameRate(Integer.parseInt(args.getString("fps","120")));
            options.setVideoCodec(args.getString("codec","h265"));options.setBitRate(16000000);options.setEnableAudioForward(false);
            options.setWakeScreen(Boolean.parseBoolean(args.getString("wake","false")));
            AdbHelper.writeAssetsJarServer(getTargetContext());
            link.startServer(new File(getTargetContext().getExternalFilesDir("scrcpy"),"scrcpy-server.jar"),options);
            final UsbAdb session=link;
            watchdog=new Thread(() -> {try{Thread.sleep(18000);session.close();}catch(InterruptedException ignored){}},"mirror-probe-deadline");
            watchdog.start();
            DataInputStream input=new DataInputStream(link.getVideoStream().inputStream());
            int dw=input.readInt(),dh=input.readInt(),vw=input.readInt(),vh=input.readInt();
            result.putString("device_size",dw+"x"+dh);result.putString("video_size",vw+"x"+vh);
            images=ImageReader.newInstance(vw,vh,ImageFormat.PRIVATE,4);
            images.setOnImageAvailableListener(reader -> {
                try(Image frame=reader.acquireLatestImage()){if(frame!=null)decoded.incrementAndGet();}
            },new Handler(handler.getLooper()));
            decoder.start();
            long start=System.nanoTime(),firstPts=-1,lastPts=-1;int frames=0;boolean configured=false;
            while(System.nanoTime()-start<12_000_000_000L) {
                // A static or sleeping source may emit no frames. The requested
                // capture duration still ends normally without waiting for EOF.
                if(input.available()==0) { Thread.sleep(5);continue; }
                int size=input.readInt();if(size<10||size>4*1024*1024)throw new IOException("Invalid frame length");
                byte[] packet=new byte[size];input.readFully(packet);
                if(packet[0]!=1)continue;
                VideoPacket video=VideoPacket.readHead(packet);
                if(video.flag==VideoPacket.Flag.CONFIG) {
                    VideoPacket.StreamSettings settings=VideoPacket.getStreamSettings(Arrays.copyOfRange(packet,10,packet.length),options.getVideoCodec().equals("h265"));
                    decoder.configure(images.getSurface(),vw,vh,settings.sps,settings.pps,options.getVideoMimeType());configured=true;
                } else if(video.flag==VideoPacket.Flag.FRAME||video.flag==VideoPacket.Flag.KEY_FRAME) {
                    if(!configured)throw new IOException("Frame arrived before decoder configuration");
                    decoder.decodeSample(packet,10,packet.length-10,video.presentationTimeStamp,video.flag.getFlag());
                    frames++;if(firstPts<0)firstPts=video.presentationTimeStamp;lastPts=video.presentationTimeStamp;
                }
            }
            result.putString("server_log",link.getServerLog());
            result.putInt("encoded_frames",frames);result.putInt("decoded_images",decoded.get());
            result.putFloat("source_fps",lastPts>firstPts?(frames-1)*1000000f/(lastPts-firstPts):0);
            result.putString("status",frames>0&&decoded.get()>0?"passed":"failed");
            outcome=frames>0&&decoded.get()>0?-1:0;
        } catch(Exception e) {
            result.putString("status","failed");result.putString("error",e.toString());
            android.util.Log.e("Scrcpy","Transport probe failed",e);
        } finally {
            if(watchdog!=null)watchdog.interrupt();
            if(link!=null) {
                final UsbAdb closing=link;
                runOnMainSync(closing::close);
                result.putString("main_thread_disconnect","passed");
            }
            decoder.stop();if(images!=null)images.close();handler.quitSafely();
        }
        finish(outcome,result);
    }
}
