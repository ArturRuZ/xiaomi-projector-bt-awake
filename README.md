# BT Awake — fix for the Bluetooth remote dying in standby on Xiaomi Smart Projector L1 Pro

[Русская версия](README.ru.md)

A 12 KB Android TV service that keeps the Bluetooth stack of the **Xiaomi Smart Projector L1 Pro** alive while the projector is in standby. No root required.

**Device:** Xiaomi Smart Projector L1 Pro (model `C045RGN`, codename `tianshan`, product `Brooke`), made by Formovie (Fengmi), MediaTek MT9630 (T32), Google TV on Android 14, firmware `UKNV.260130.001`. Other projectors on the same Formovie `tianshan` board (e.g. `C025RGN`, Formovie Xming Page One) very likely have the same bug.

## Symptom

After a night (or a few days) in standby the Bluetooth remote stops working. Re-pairing does not help, the Bluetooth menu may show the adapter as "on" while nothing connects. Only a full power-cycle of the projector brings the remote back.

## Root cause

Traced with `adb logcat` / `dumpsys` on the stock firmware, no modifications:

1. In standby the system suspends to RAM and is woken every ~5 minutes (Google Play services heartbeat, HDMI-CEC polling and similar), stays up for about a minute and suspends again.
2. The Bluetooth chip is attached over an internal USB bus (`btmtk_usb` driver). On suspend the MediaTek driver puts the chip into "Wake-on-BLE" mode by sending vendor HCI commands **directly to the chip, bypassing the Android Bluetooth stack** (`0xFD57` APCF filters, `0xFCC9` radio-off).
3. On the next wake-up the driver sends another vendor command, `0xFCCE`. The *COMMAND_STATUS* event for it is delivered to the Android stack, which at that moment is waiting for the reply to its own background `LE_EXTENDED_CREATE_CONNECTION` (reconnect to the bonded BLE remote). The stack hits an assertion and aborts:

   ```
   F bluetooth: assertion 'waiting_command_ == op_code' failed -
     Waiting for 0x2043 (LE_EXTENDED_CREATE_CONNECTION), got 0xfcce (Unknown OpCode: 64718)
   F libc    : Fatal signal 6 (SIGABRT) ... in tid (bt_stack_manage), pid (droid.bluetooth)
   I ActivityManager: Process com.android.bluetooth (pid ...) has died: psvc PER
   ```

   Every crash lands exactly ~60 ms after `btmtk_usb_resume`, i.e. once per wake-up: ~10 crashes per hour of standby, none while the screen is on.
4. The stack restarts after every crash, but `system_server` leaks one dead `ConnectionRecord` to `com.android.bluetooth` per crash. After ~3000 of them (roughly 10–13 days of normal use) `bindService` fails:

   ```
   W ActivityManager: bindService exceeded max service connection number per process,
     callerApp:system intent:Intent { act=android.bluetooth.IBluetooth cmp=com.android.bluetooth/.btservice.AdapterService }
   E BluetoothManagerService: Fail to bind to: Intent { act=android.bluetooth.IBluetooth ... }
   ```

   From this point the adapter can't be started at all (`svc bluetooth enable` reports success, state stays `OFF`) — the remote is dead until reboot.

Things that do **not** help: whitelisting `com.android.bluetooth` from Doze, disabling Low Power Standby (`low_power_standby_enabled=0`), re-pairing the remote. The Formovie auto-pair service (`com.fengmi.Autopair`) is not the trigger either — its reconnect attempts happen *after* the stack restarts.

The bug is in the vendor driver/HAL. Bootloader is locked, build is `user`, no root or custom firmware exists for this board, and as of November 2026 Xiaomi has shipped no firmware update since January 2026.

## The fix

The crash needs a suspend/resume cycle of the USB Bluetooth chip. An ordinary app is allowed to hold a `PARTIAL_WAKE_LOCK`, which stops the system from suspending while letting the screen, lamp, GPU and everything else sleep normally. **BT Awake** is a foreground service that:

- acquires a partial wake lock when the screen turns off (`ACTION_SCREEN_OFF`),
- releases it when the screen turns on,
- starts itself on boot.

Measured result: 14 Bluetooth crashes in the hour before installation, **0 crashes and 0 suspend cycles** in the next 5 hours. Side effect: the projector stays reachable over Wi-Fi in standby (ADB works without Wake-on-LAN).

**Cost — read this before installing.** With the stock firmware the projector really sleeps in standby (SoC suspended ~90 % of the time). With BT Awake the SoC never suspends: it idles at ~1.45 GHz with the fan off (the fan driver is disabled in standby), so it draws a few watts more and runs warmer. Measured on the L1 Pro: CPU 76–78 °C after 5 h of standby with the wake lock vs. 63–66 °C ten minutes after releasing it; the fan thresholds for the CPU in the vendor thermal config are 93/95 °C, so this is well within limits, but after ~4.5 h the vendor thermal service starts logging `adjustFanRpm error, get fan1 rpm = 0` every second because it wants the fan and can't have it. If you prefer, unplug the projector or switch it off at the wall when you won't use it for days.

## Install

Enable ADB on the projector (Settings → System → About → tap *Build* 7 times → Developer options → *Network debugging* / USB debugging), then from a computer on the same network:

```sh
adb connect <projector-ip>
adb install -r -g btawake.apk
adb shell dumpsys deviceidle whitelist +art.applab.btawake
adb shell am start-foreground-service -n art.applab.btawake/.AwakeService
```

Check it works — send the projector to standby and look at the wake lock:

```sh
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys power | grep BtAwake
#  PARTIAL_WAKE_LOCK  'BtAwake:standby' ACQ=-44s780ms (uid=..., pid=...)
adb shell logcat -d -s BtAwake:I
```

Count crashes over a night (should stay at 0):

```sh
adb shell logcat -d | grep -c "Process com.android.bluetooth.*has died"
```

The pre-built APK is in [Releases](../../releases). Uninstall with `adb uninstall art.applab.btawake`.

## Build from source

No Gradle. Needs the Android SDK (build-tools 37, platform 34) and a JDK (the one bundled with Android Studio works):

```sh
keytool -genkeypair -keystore release.jks -alias btawake -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BT Awake"
BTAWAKE_KS=release.jks BTAWAKE_KS_PASS=<password> sh build.sh
```

`build.sh` runs `javac → d8 → aapt2 → zipalign → apksigner` and writes `out/btawake.apk`. Two source files, ~80 lines total: `AwakeService.java` (the wake lock) and `BootReceiver.java` (autostart).

## Diagnosing your own device

If you suspect the same bug on another Android TV with a MediaTek USB Bluetooth chip:

```sh
adb shell logcat -d | grep -E "Fatal signal 6.*droid.bluetooth|has died: psvc"
adb shell logcat -d | grep "Abort message"           # look for 0xfcce / waiting_command_
adb shell dumpsys activity services | grep -c "CR.*DEAD com.android.bluetooth"   # leaked connections
adb shell dumpsys bluetooth_manager | grep -m1 "state"
```

## License

MIT.
