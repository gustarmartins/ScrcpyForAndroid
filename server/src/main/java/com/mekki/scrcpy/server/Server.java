package com.mekki.scrcpy.server;

import com.mekki.scrcpy.server.util.Workarounds;

import java.io.IOException;

public final class Server {

    private static String ip = null;

    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException {
        Workarounds.apply();  // init content

        ip = options.getIp();
        final Device device = new Device(options);
        android.os.PowerManager.WakeLock wake = null;
        try (DroidConnection connection = DroidConnection.open(ip, options.getSocketName())) {
            if (options.getWakeScreen()) {
                try {
                    android.os.PowerManager power = (android.os.PowerManager)
                            com.mekki.scrcpy.server.util.FakeContext.get().getSystemService(android.content.Context.POWER_SERVICE);
                    wake = power.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK
                            | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "MekkiMirror:session");
                    wake.acquire();
                    Ln.i("Session screen wake lock acquired");
                } catch (Exception e) { Ln.e("Unable to keep the screen awake", e); }
            }
            ScreenEncoder screenEncoder = new ScreenEncoder(options);

            // asynchronous
            startEventController(device, connection, screenEncoder);

            try {
                // synchronous
                screenEncoder.streamScreen(options, device, connection.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
                // this is expected on close
                Ln.d("Screen streaming stopped");

            }
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private static void startEventController(final Device device, final DroidConnection connection, ScreenEncoder screenEncoder) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new EventController(device, connection, screenEncoder).control();
                } catch (IOException e) {
                    // this is expected on close
                    Ln.d("Event controller stopped");
                } finally {
                    screenEncoder.stop();
                }
            }
        }).start();
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static Options createOptions(String... args) {
//        Options options = new Options();
//
//        if (args.length < 1) {
//            return options;
//        }
//        ip = String.valueOf(args[0]);
//
//
//        if (args.length < 2) {
//            return options;
//        }
//        int maxSize = Integer.parseInt(args[1]) & ~7; // multiple of 8
//        options.setMaxSize(maxSize);
//
//        if (args.length < 3) {
//            return options;
//        }
//        int bitRate = Integer.parseInt(args[2]);
//        options.setBitRate(bitRate);
//
//        if (args.length < 4) {
//            return options;
//        }
//        // use "adb forward" instead of "adb tunnel"? (so the server must listen)
//        boolean tunnelForward = Boolean.parseBoolean(args[3]);
//        options.setTunnelForward(tunnelForward);
//        return options;

        return Options.createOptions(args);
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Ln.e("Exception on thread " + t, e);
            }
        });

        Options options = createOptions(args);
        if (options.getJarPath().matches("/data/local/tmp/mekki-mirror-[a-f0-9]+\\.jar")) {
            new java.io.File(options.getJarPath()).delete();
        }
        try { scrcpy(options); }
        finally { System.exit(0); }
    }
}

