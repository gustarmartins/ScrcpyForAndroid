package com.mekki.scrcpy;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.mekki.scrcpy.utils.AdbHelper;
import com.mekki.scrcpy.utils.ThreadUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SendCommands {

    public final static int WAIT_TIME = 15000;

    public enum CmdStatus {
        SUCCESS,
        RUNNING,
        ERROR
    }

    public SendCommands() {

    }

    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, String localip, int bitrate, int size, boolean enableAudio) {
        Options options = new Options();
        options.setIp(localip);
        options.setMaxSize(size);
        options.setBitRate(bitrate);
        options.setTunnelForward(true);
        options.setEnableAudioForward(enableAudio);
        return SendAdbCommands(context, ip, port, forwardport, options);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip, int bitrate, int size, boolean enableAudio) {
        return SendAdbCommands(context, ip, port, forwardport, localip, bitrate, size, enableAudio);
    }

    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, Options options) {
        com.mekki.scrcpy.usb.UsbAdb link = null;
        try {
            AdbHelper.writeAssetsJarServer(context);
            link = com.mekki.scrcpy.usb.UsbAdb.connectTcp(context, ip, port);
            link.startServer(new File(context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar"), options);
            App.transport = link;
            return CmdStatus.SUCCESS;
        } catch (Exception e) {
            if (link != null) link.close();
            Log.e("Scrcpy", "Connection failed", e);
            return CmdStatus.ERROR;
        }
    }

    private CmdStatus startPortForward(Context context, String ip, int port, int serverport) {
        Log.i("Scrcpy", "try connect to ip: " + ip);
        AdbHelper.adbCmd(App.mContext, "connect", ip + ":" + port);
        // 复制server端到可执行目录
        String pushRet = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "push", new File(
                context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar"
        ).getAbsolutePath(), "/data/local/tmp/scrcpy-server.jar");

        Log.i("Scrcpy", "pushRet: " + pushRet);

        String adbTextCmd = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
        if (TextUtils.isEmpty(adbTextCmd)) {
            return CmdStatus.ERROR;
        }
        // 开启本地端口 forward 转发
        Log.i("Scrcpy", "开启本地端口转发");
        AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "forward", "tcp:" + serverport, "tcp:" + 7007);
        return CmdStatus.SUCCESS;
    }

    private void newAdbServerStart(String[] command) {
        // 执行启动命令
        AdbHelper.adbCmd(App.mContext, command);
    }

}
