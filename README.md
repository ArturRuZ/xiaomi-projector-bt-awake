# BT Awake — fix for the Bluetooth remote dying in standby on Xiaomi Smart Projector L1 Pro

[Русская версия](README.ru.md)

A tiny Android TV service that stops the Bluetooth remote of the **Xiaomi Smart Projector L1 Pro** from dying after some days in standby. No root required. Two versions, two approaches: pick one (see [The fix](#the-fix)).

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

   All the dead records hang on **one** connection object: the one `BluetoothManagerService` uses to bind `com.android.bluetooth`. When Bluetooth is turned off, the manager unbinds that connection and Android drops all of its records at once (measured: 102 → 0). That only works while the connection is alive. At the limit the rebind after the last crash fails, the manager has no connection left, so turning Bluetooth off is a no-op and turning it on fails again. That is why toggling Bluetooth in the settings did nothing once the remote was dead, and only a reboot helped.

Things that do **not** help: whitelisting `com.android.bluetooth` from Doze, disabling Low Power Standby (`low_power_standby_enabled=0`), re-pairing the remote. The Formovie auto-pair service (`com.fengmi.Autopair`) is not the trigger either — its reconnect attempts happen *after* the stack restarts.

The bug is in the vendor driver/HAL. Bootloader is locked, build is `user`, no root or custom firmware exists for this board, and as of November 2026 Xiaomi has shipped no firmware update since January 2026.

## The fix

There are two working approaches. Both are the same app (`art.applab.btawake`), so only one can be installed at a time. Both are in [Releases](../../releases).

| | v0.1.0: keep the system awake | v0.2.0: clear the leak |
|---|---|---|
| Bluetooth crashes in standby | none | still happen, harmless |
| Projector sleeps in standby | no | yes, as stock |
| CPU in standby | warm, 76–78 °C | cool, 52–56 °C |
| Extra power draw | a few watts | none |
| Remote in standby | always connected | gone for ~3 s right after standby |

### Option 1 — v0.1.0: keep the system awake

The crash needs a suspend/resume cycle of the USB Bluetooth chip. An ordinary app is allowed to hold a `PARTIAL_WAKE_LOCK`, which stops the system from suspending while letting the screen, lamp, GPU and everything else sleep normally. v0.1.0 is a foreground service that:

- acquires a partial wake lock when the screen turns off (`ACTION_SCREEN_OFF`),
- releases it when the screen turns on,
- starts itself on boot.

Measured result: 14 Bluetooth crashes in the hour before installation, **0 crashes and 0 suspend cycles** in the next 5 hours. Side effect: the projector stays reachable over Wi-Fi in standby (ADB works without Wake-on-LAN).

**Cost: the CPU runs a bit warm in standby.** The SoC never suspends and idles at ~1.45 GHz, while the vendor firmware keeps the fan off in standby. Measured on the L1 Pro: CPU 76–78 °C after 5 h of standby, versus 52–56 °C when the projector sleeps normally, plus a few watts of extra draw. The vendor fan thresholds for the CPU are 93/95 °C, so this is within limits. After ~4.5 h the vendor thermal service starts logging `adjustFanRpm error, get fan1 rpm = 0` every second because it wants the fan and can't have it; it is noise in the log, nothing more.

Source: tag [`v0.1.0`](../../tree/v0.1.0).

Check it works. Send the projector to standby and look at the wake lock:

```sh
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys power | grep BtAwake
#  PARTIAL_WAKE_LOCK  'BtAwake:standby' ACQ=-44s780ms (uid=..., pid=...)
adb shell logcat -d | grep -c "Process com.android.bluetooth.*has died"   # should stay at 0 overnight
```

### Option 2 — v0.2.0: clear the leak by cycling Bluetooth

The crash itself is harmless: the stack restarts in a couple of seconds and the remote reconnects. What kills the remote is only the leak (step 4 above). v0.2.0 lets the projector sleep as stock and clears the leak long before the limit by turning Bluetooth off and on again:

- 30 s after the projector goes to standby (screen off), so a quick off/on by the user is left alone;
- every 12 h while it stays in standby (an inexact `setAndAllowWhileIdle` alarm), in case the projector is not used for days;
- it holds a short partial wake lock only for the cycle itself (about 3 s, plus 15 s for the remote to reconnect), then lets the system suspend as usual;
- if the cycle is interrupted with Bluetooth off, the service turns it back on at its next start; if Bluetooth was turned off by the user, it is left off.

The leak grows by up to ~10 records per hour of standby, so between two cycles it never gets anywhere near ~3000. The vendor crashes keep happening in the log; they are no longer fatal.

Measured result, first hour of standby on v0.2.0: 8 suspend cycles (the projector sleeps as stock), 9 vendor crashes, Bluetooth back ON after every one of them with the remote reconnected, 0 dead connections, CPU 52–56 °C. Android logs the cycle as `Disabled/Enabled due to APPLICATION_REQUEST by art.applab.btawake` in `dumpsys bluetooth_manager`.

The app targets SDK 32 on purpose: starting with Android 13, `BluetoothAdapter.enable()/disable()` always fail for ordinary apps that target SDK 33+. On SDK 32 they still work with the `BLUETOOTH_CONNECT` permission, which is why it must be installed with `-g`.

Source: `main`.

Check it works. Trigger a cycle by hand and look at the log:

```sh
adb shell am start-foreground-service -a art.applab.btawake.CYCLE_NOW -n art.applab.btawake/.AwakeService
adb shell logcat -d -s BtAwake:I
#  I BtAwake : manual: turning Bluetooth off
#  I BtAwake : turning Bluetooth on (try 1, accepted=true)
#  I BtAwake : Bluetooth back ON, leaked connections cleared
```

`accepted=false` or `disable() refused` means the permission was not granted: reinstall with `-g`. After a few days of normal use the number of dead connections should stay low, since it is reset at every standby:

```sh
adb shell dumpsys activity services | grep -c "CR.*DEAD com.android.bluetooth"
```

## Install

Download the APK of the option you chose from [Releases](../../releases). Enable ADB on the projector (Settings → System → About → tap *Build* 7 times → Developer options → *Network debugging* / USB debugging), then from a computer on the same network:

```sh
adb connect <projector-ip>
adb install -r -g btawake-v0.X.0.apk    # -g is required for v0.2.0 (BLUETOOTH_CONNECT)
adb shell dumpsys deviceidle whitelist +art.applab.btawake
adb shell am start-foreground-service -n art.applab.btawake/.AwakeService
```

Switching between the options:

- **v0.1.0 → v0.2.0.** Installing over v0.1.0 kills its service and releases the wake lock, so the projector may fall asleep and drop off Wi-Fi in the middle of `adb install`. Wake it first and keep it awake for the install: `adb shell svc power stayon true`, then `false` afterwards.
- **v0.2.0 → v0.1.0.** Android refuses to install an older version over a newer one, so run `adb uninstall art.applab.btawake` first.

Uninstall with `adb uninstall art.applab.btawake`.

Don't force-stop the app or clear its data in the projector settings: Android then treats it as "stopped" and won't start it on boot until it is started by hand again.

## Build from source

No Gradle. Needs the Android SDK (build-tools 37, platform 34) and a JDK (the one bundled with Android Studio works):

```sh
keytool -genkeypair -keystore release.jks -alias btawake -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BT Awake"
BTAWAKE_KS=release.jks BTAWAKE_KS_PASS=<password> sh build.sh
```

`build.sh` runs `javac → d8 → aapt2 → zipalign → apksigner` and writes `out/btawake.apk`. Two source files: `AwakeService.java` (the wake lock in v0.1.0, the Bluetooth cycle in v0.2.0) and `BootReceiver.java` (autostart). To build v0.1.0, check out the `v0.1.0` tag first.

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
