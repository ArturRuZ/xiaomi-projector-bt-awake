# BT Awake — лечим Bluetooth-пульт, умирающий в режиме ожидания на Xiaomi Smart Projector L1 Pro

[English](README.md)

Сервис для Android TV размером 12 КБ, который не даёт Bluetooth-стеку **Xiaomi Smart Projector L1 Pro** падать, пока проектор в режиме ожидания. Root не нужен.

**Устройство:** Xiaomi Smart Projector L1 Pro (модель `C045RGN`, кодовое имя `tianshan`, продукт `Brooke`), производитель Formovie (Fengmi), чип MediaTek MT9630 (T32), Google TV на Android 14, прошивка `UKNV.260130.001`. Другие проекторы на той же плате Formovie `tianshan` (например, `C025RGN`, Formovie Xming Page One) почти наверняка страдают тем же.

## Симптом

После ночи (или нескольких дней) в режиме ожидания Bluetooth-пульт перестаёт работать. Повторное сопряжение не помогает, в меню Bluetooth адаптер может числиться включённым, но ничего не подключается. Пульт возвращает к жизни только выключение проектора из розетки.

## Причина

Выяснена через `adb logcat` и `dumpsys` на стоковой прошивке, без каких-либо модификаций:

1. В режиме ожидания система каждые 5–9 минут пытается уйти в suspend (глубокий сон).
2. Bluetooth-чип подключён по внутренней USB-шине (драйвер `btmtk_usb`). При засыпании драйвер MediaTek переводит чип в режим «пробуждение по BLE», посылая вендорные HCI-команды **напрямую чипу, в обход Bluetooth-стека Android** (`0xFD57` — фильтры APCF, `0xFCC9` — выключение радио).
3. Примерно через 1,3 секунды система просыпается, и драйвер шлёт ещё одну вендорную команду, `0xFCCE`. Событие *COMMAND_STATUS* на неё доставляется в стек Android, который в этот момент ждёт ответа на собственную фоновую команду `LE_EXTENDED_CREATE_CONNECTION` (переподключение к сопряжённому BLE-пульту). Стек натыкается на assert и аварийно завершается:

   ```
   F bluetooth: assertion 'waiting_command_ == op_code' failed -
     Waiting for 0x2043 (LE_EXTENDED_CREATE_CONNECTION), got 0xfcce (Unknown OpCode: 64718)
   F libc    : Fatal signal 6 (SIGABRT) ... in tid (bt_stack_manage), pid (droid.bluetooth)
   I ActivityManager: Process com.android.bluetooth (pid ...) has died: psvc PER
   ```

   Каждое падение случается ровно через ~60 мс после `btmtk_usb_resume`. Около 10 падений в час ожидания; при включённом экране — ни одного.
4. После каждого падения стек перезапускается, но `system_server` оставляет по одному «мёртвому» `ConnectionRecord` к `com.android.bluetooth`. Когда их накапливается около 3000 (примерно 10–13 дней обычного использования), `bindService` отказывает:

   ```
   W ActivityManager: bindService exceeded max service connection number per process,
     callerApp:system intent:Intent { act=android.bluetooth.IBluetooth cmp=com.android.bluetooth/.btservice.AdapterService }
   E BluetoothManagerService: Fail to bind to: Intent { act=android.bluetooth.IBluetooth ... }
   ```

   С этого момента адаптер вообще не запускается (`svc bluetooth enable` отвечает «успех», состояние остаётся `OFF`) — пульт мёртв до перезагрузки.

Что **не** помогает: белый список Doze для `com.android.bluetooth`, отключение Low Power Standby (`low_power_standby_enabled=0`), повторное сопряжение пульта. Сервис автосопряжения Formovie (`com.fengmi.Autopair`) тоже не виноват — его попытки переподключения идут уже *после* рестарта стека.

Баг сидит в вендорном драйвере или HAL. Загрузчик заперт, сборка `user`, root и кастомных прошивок для этой платы не существует, а на ноябрь 2026 года Xiaomi не выпустила ни одного обновления прошивки с января 2026.

## Решение

Для падения нужен цикл засыпания и пробуждения USB-чипа Bluetooth. Обычному приложению разрешено держать `PARTIAL_WAKE_LOCK`: он не даёт системе уйти в suspend, но экран, лампа, GPU и всё остальное спят как обычно. **BT Awake** — это foreground-сервис, который:

- берёт частичный wakelock при выключении экрана (`ACTION_SCREEN_OFF`),
- отпускает его при включении экрана,
- запускается сам при загрузке.

Результат замера: 14 падений Bluetooth за час до установки, **0 падений и 0 попыток suspend** после. Побочный эффект: проектор в ожидании остаётся доступен по Wi-Fi (adb работает без Wake-on-LAN). Цена: в режиме ожидания процессор не засыпает, это примерно 1–3 Вт сверх обычного потребления в ожидании (батареи у проектора нет, так что это вся цена).

## Установка

Включите ADB на проекторе (Настройки → Система → Об устройстве → 7 раз нажать на *Сборка* → Для разработчиков → *Отладка по сети* / отладка по USB), затем с компьютера в той же сети:

```sh
adb connect <ip-проектора>
adb install -r -g btawake.apk
adb shell dumpsys deviceidle whitelist +art.applab.btawake
adb shell am start-foreground-service -n art.applab.btawake/.AwakeService
```

Проверка — отправьте проектор в ожидание и посмотрите на wakelock:

```sh
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys power | grep BtAwake
#  PARTIAL_WAKE_LOCK  'BtAwake:standby' ACQ=-44s780ms (uid=..., pid=...)
adb shell logcat -d -s BtAwake:I
```

Подсчёт падений за ночь (должен оставаться нулём):

```sh
adb shell logcat -d | grep -c "Process com.android.bluetooth.*has died"
```

Собранный APK лежит в [Releases](../../releases). Удаление: `adb uninstall art.applab.btawake`.

Чего не делать: не останавливать приложение принудительно и не очищать его данные в настройках проектора — после этого Android считает приложение «остановленным» и перестаёт запускать его при загрузке, пока его не стартуют вручную.

## Сборка из исходников

Gradle не нужен. Нужны Android SDK (build-tools 37, platform 34) и JDK (подходит тот, что идёт с Android Studio):

```sh
keytool -genkeypair -keystore release.jks -alias btawake -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BT Awake"
BTAWAKE_KS=release.jks BTAWAKE_KS_PASS=<пароль> sh build.sh
```

`build.sh` выполняет цепочку `javac → d8 → aapt2 → zipalign → apksigner` и кладёт результат в `out/btawake.apk`. Два исходных файла, около 80 строк на всё: `AwakeService.java` (сам wakelock) и `BootReceiver.java` (автозапуск).

## Диагностика на своём устройстве

Если подозреваете тот же баг на другом Android TV с USB-чипом Bluetooth от MediaTek:

```sh
adb shell logcat -d | grep -E "Fatal signal 6.*droid.bluetooth|has died: psvc"
adb shell logcat -d | grep "Abort message"           # ищите 0xfcce / waiting_command_
adb shell dumpsys activity services | grep -c "CR.*DEAD com.android.bluetooth"   # утёкшие подключения
adb shell dumpsys bluetooth_manager | grep -m1 "state"
```

## Лицензия

MIT.
