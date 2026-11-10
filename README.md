# Mekki Mirror

An Android-to-Android mirror based on [zwc456baby/ScrcpyForAndroid](https://github.com/zwc456baby/ScrcpyForAndroid), maintained in [gustarmartins/ScrcpyForAndroid](https://github.com/gustarmartins/ScrcpyForAndroid).

The fork installs as **Mekki Mirror**, package `com.mekki.scrcpy`, alongside the upstream app. It uses the upstream capture/control protocol with a direct Java ADB transport for network and USB connections. No native ADB executable, local ADB server, or fixed forwarding port is needed.

## Connect

Enter the target's address **including its ADB port**. For multiple routes to the same device, separate addresses with commas:

```text
phone-name:6666, 192.168.1.20:6666
```

Only entered addresses are attempted. A matching local Wi-Fi subnet is preferred and its socket is bound to that Wi-Fi network; other candidates use normal routing, including Tailscale. IPv6 addresses use brackets: `[fd7a::1234]:6666`. A profile remembers the target's reported serial and rejects a different device at a reused address. There is no idle discovery, polling, or reconnection service.

For mobile data or different networks, Tailscale must already be connected on both phones. For direct Wi-Fi, both devices must be on a reachable local network. For USB, attach a data-capable cable with the controller acting as USB host, select **USB** in the device menu, and grant Android's USB-device permission.

**Wake remote screen while connected** is enabled by default under More Settings. Its session wake lock is released on disconnect; it does not change the target's saved screen timeout or unlock it.

The target needs ADB enabled and must trust this app's key. Network and USB share one app-owned identity. A new installation normally requires approval on the target; do not clear app data or regenerate keys when the target's display is inaccessible. This app does not activate root or depend on root on either phone. Classic ADB TCP endpoints are supported; Android's TLS pairing-code workflow is not implemented.

Network loss, authentication failure, and server/encoder startup errors are shown separately. Start makes one bounded attempt per candidate. Reopening or choosing Start retries the saved profile; there is no promise of connectivity through power loss, reboot, a missing VPN, or a missing cable.

## Picture and responsiveness

- Native resolution preserves the target's current dimensions and aspect ratio. Smaller long-edge limits reduce bandwidth and decoding load without stretching.
- H.264 and H.265, including 90/120 fps requests, are available over network **and USB**.
- Hardware encoders are tried first, with the selected name and accepted configuration in the session log. Failed hardware configurations try other hardware candidates before software. At rates above 60 fps, software fallback must be explicitly selected rather than silently replacing hardware.
- Zero B-frames, real-time codec priority, bounded stream buffering, TCP_NODELAY, and supported low-latency decoding reduce queuing. The viewer requests the selected refresh rate for its own window and keeps its screen awake only while mirroring.
- Higher bitrate choices include 16, 32 and 64 Mbps. Compressed screen video is not lossless; HDR and exact color reproduction are not qualified.
- Explicit geometry updates keep rotation and touch coordinates aligned. Secondary-pointer coordinates and cancelled touches are handled without reusing stale pointers.

A 120 fps **request** is not a measurement. Actual delivery depends on the source's animation/refresh rate, encoder, link and decoder. Static screens intentionally generate fewer frames. Logs report encoded and decoded frame rates.

## Validation

On Galaxy S23 Ultra (Android 17) to S23 FE (Android 16), a bounded mobile-data/Tailscale test authenticated with the retained key and decoded hardware H.265 video at both 1088×2336 and native 1440×3088. The Ultra selected `c2.qti.hevc.encoder` and accepted a native-resolution 120 fps request. The native test decoded 361 images from 372 received frames while ordinary source content averaged approximately 32.5 fps; **sustained 120 fps has not been demonstrated**. These tests used an offscreen image consumer and do not substitute for physical touch/display acceptance. Direct LAN and Tailscale with both phones on the same Wi-Fi also passed, including disconnect on the Android UI thread. USB cable transport and live Wi-Fi/mobile handover still require physical qualification.

Unit tests exercise real TCP authentication/signatures, fragmented packet delivery, stream completion, invalid packet lengths, explicit endpoint parsing, and subnet matching.

## Build

Use JDK 17 and the checked-in Gradle wrapper. The project retains compile/target SDK 31 and requires Android 8 or newer. The app and device server are built together.

```sh
./gradlew :app:assembleScrcpyDebug :app:testScrcpyDebugUnitTest
```

The debug APK includes a **manually invoked**, bounded `TransportProbe` instrumentation. It authenticates, receives frames, and decodes them into an offscreen ImageReader without opening an activity or injecting input:

```sh
adb -s CONTROLLER shell am instrument -w \
  -e endpoint TARGET:PORT -e fps 120 -e maxSize 0 -e codec h265 \
  com.mekki.scrcpy/com.mekki.scrcpy.TransportProbe
```

Its identity, frame counts, dimensions and server log are test results, not a general performance benchmark. It is excluded from release builds. Session logs are local and bounded; sharing is an explicit user action. No device addresses, keys or credentials are included in this repository.

## Credits and license

This fork retains the upstream GPL-3.0 license and attribution. Capture and control work builds on [scrcpy](https://github.com/Genymobile/scrcpy) and [scrcpy-android](https://gitlab.com/las2mile/scrcpy-android), as credited upstream.
