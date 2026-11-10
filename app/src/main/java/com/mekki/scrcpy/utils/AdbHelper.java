package com.mekki.scrcpy.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import com.mekki.scrcpy.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AdbHelper {

    private static volatile boolean startAdbRun = false;

    private static volatile boolean running = false;

    private static CountDownLatch latch = new CountDownLatch(1);

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * If the mobile device enters a deep sleep state for an extended period,
     * the ADB process will become unresponsive
     * the connection via port 5137 can no longer be re-established,
     * and the process must be terminated.
     * <p>
     * 强杀 adb 进程，长时间休眠后，无法自动唤醒
     */
    public static void killAdbProccess() { /* Direct ADB has no daemon. */ }

    public static void restartAdb() {
        killAdbProccess();
        startAdbServer();
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * 更新状态的方法。
     * 由监控线程或 .so 状态回调调用。
     */
    private static void setRunning(boolean isRunning) {
        running = isRunning;
        if (isRunning && latch.getCount() > 0) {
            latch.countDown();
        } else if (!isRunning) {
            if (latch.getCount() == 0) {
                latch = new CountDownLatch(1);
            }
        }
    }

    public static boolean waitForRunning(long timeoutSeconds) throws InterruptedException {
        if (running) return true;
        // 等待倒计时归零
        return latch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Check if the ADB service is running properly.
     * If the process becomes unresponsive, it will block and time out.
     */
    public static boolean checkAdbServer() {
        return executeWithTimeout(() -> {
            // run devices cmd, check server
            String deviceList = AdbHelper.adbCmd(App.mContext, "devices");
            Log.i("Scrcpy", "adb devices: " + deviceList);
        }, 5, TimeUnit.SECONDS);
    }

    public static boolean executeWithTimeout(Runnable task, long timeout, TimeUnit unit) {
        Future<?> future = executor.submit(task);

        try {
            future.get(timeout, unit);
            return true; // 顺利执行完毕
        } catch (TimeoutException e) {
            future.cancel(true);
            System.err.println("任务执行超时");
            return false;
        } catch (InterruptedException e) {
            // 当前等待的线程被别人中断了
            future.cancel(true);
            Thread.currentThread().interrupt(); // 恢复中断标志位
            return false;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 启动 adb 服务
     */
    public static void startAdbServer() { setRunning(true); }

    /**
     * https://github.com/lzhiyong/android-sdk-tools
     * <p>
     * 尝试自动生成 adb key文件
     */
    /**
     * True if the wireless ADB target is listed as {@code device} (not offline/unauthorized).
     */
    public static boolean isDeviceOnline(Context mContext, String host, int port) {
        if (mContext == null || TextUtils.isEmpty(host)) {
            return false;
        }
        String serial = host + ":" + port;
        if (listedAsOnline(adbCmd(mContext, "devices"), serial)) {
            return true;
        }
        boolean connectFinished = executeWithTimeout(() -> adbCmd(mContext, "connect", serial),
                4, TimeUnit.SECONDS);
        if (!connectFinished) {
            return false;
        }
        return listedAsOnline(adbCmd(mContext, "devices"), serial);
    }

    private static boolean listedAsOnline(String devicesOutput, String serial) {
        if (TextUtils.isEmpty(devicesOutput) || TextUtils.isEmpty(serial)) {
            return false;
        }
        String[] lines = devicesOutput.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(serial)) {
                continue;
            }
            String rest = trimmed.substring(serial.length()).trim();
            if ("device".equals(rest) || rest.startsWith("device ") || rest.startsWith("device\t")) {
                return true;
            }
        }
        return false;
    }

    public static String adbCmd(Context mContext, String... cmd) {
        if (cmd.length == 0) return "";
        int start = cmd[0].equals("-s") ? 2 : 0;
        if (start >= cmd.length) return "";
        String operation = cmd[start];
        if (operation.equals("forward") || operation.equals("connect") || operation.equals("kill-server") || operation.equals("start-server")) return "";
        com.mekki.scrcpy.usb.UsbAdb link = App.transport;
        boolean temporary = false;
        try {
            if (link == null && start == 2) {
                com.mekki.scrcpy.usb.Endpoint ep = com.mekki.scrcpy.usb.Endpoint.candidates(cmd[1]).get(0);
                link = com.mekki.scrcpy.usb.UsbAdb.connectTcp(mContext, ep.host, ep.port);
                temporary = true;
            }
            if (link == null) return "";
            if (operation.equals("devices")) return "active\tdevice\n";
            StringBuilder command = new StringBuilder();
            if (operation.equals("reboot")) command.append("reboot ");
            else if (!operation.equals("shell")) return "";
            for (int i = start + 1; i < cmd.length; i++) command.append(cmd[i]).append(' ');
            return link.shell(command.toString());
        } catch (Exception e) { Log.w("Scrcpy", "Device command failed", e); return ""; }
        finally { if (temporary && link != null) link.close(); }
    }

    public static void writeAssetsJarServer(Context context) {
        AssetManager assetManager = context.getAssets();
        Log.d("Scrcpy", "File scrcpy-server.jar try write");
        try {
            InputStream input_Stream = assetManager.open("scrcpy-server.jar");
            byte[] buffer = new byte[input_Stream.available()];
            input_Stream.read(buffer);
            File scrcpyDir = context.getExternalFilesDir("scrcpy");
            if (!scrcpyDir.exists()) {
                scrcpyDir.mkdirs();
            }
            FileOutputStream outputStream = new FileOutputStream(new File(
                    context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar"
            ));
            outputStream.write(buffer);
            outputStream.flush();
            outputStream.close();

            // fileBase64 = Base64.encode(buffer, 2);
        } catch (IOException e) {
            Log.d("Scrcpy", "File scrcpy-server.jar write faild");
        }
    }

}
