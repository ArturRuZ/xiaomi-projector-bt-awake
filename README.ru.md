# BT Awake — лечим Bluetooth-пульт, умирающий в режиме ожидания на Xiaomi Smart Projector L1 Pro

[English](README.md)

Крошечный сервис для Android TV, который не даёт Bluetooth-пульту **Xiaomi Smart Projector L1 Pro** умереть после нескольких дней в режиме ожидания. Root не нужен. Две версии, два подхода: выберите один (см. [Решение](#решение)).

**Устройство:** Xiaomi Smart Projector L1 Pro (модель `C045RGN`, кодовое имя `tianshan`, продукт `Brooke`), производитель Formovie (Fengmi), чип MediaTek MT9630 (T32), Google TV на Android 14, прошивка `UKNV.260130.001`. Другие проекторы на той же плате Formovie `tianshan` (например, `C025RGN`, Formovie Xming Page One) почти наверняка страдают тем же.

## Симптом

После ночи (или нескольких дней) в режиме ожидания Bluetooth-пульт перестаёт работать. Повторное сопряжение не помогает, в меню Bluetooth адаптер может числиться включённым, но ничего не подключается. Пульт возвращает к жизни только выключение проектора из розетки.

## Причина

Выяснена через `adb logcat` и `dumpsys` на стоковой прошивке, без каких-либо модификаций:

1. В режиме ожидания система уходит в suspend (глубокий сон) и просыпается примерно раз в 5 минут (heartbeat сервисов Google, опрос HDMI-CEC и подобное), бодрствует около минуты и засыпает снова.
2. Bluetooth-чип подключён по внутренней USB-шине (драйвер `btmtk_usb`). При засыпании драйвер MediaTek переводит чип в режим «пробуждение по BLE», посылая вендорные HCI-команды **напрямую чипу, в обход Bluetooth-стека Android** (`0xFD57` — фильтры APCF, `0xFCC9` — выключение радио).
3. При очередном пробуждении драйвер шлёт ещё одну вендорную команду, `0xFCCE`. Событие *COMMAND_STATUS* на неё доставляется в стек Android, который в этот момент ждёт ответа на собственную фоновую команду `LE_EXTENDED_CREATE_CONNECTION` (переподключение к сопряжённому BLE-пульту). Стек натыкается на assert и аварийно завершается:

   ```
   F bluetooth: assertion 'waiting_command_ == op_code' failed -
     Waiting for 0x2043 (LE_EXTENDED_CREATE_CONNECTION), got 0xfcce (Unknown OpCode: 64718)
   F libc    : Fatal signal 6 (SIGABRT) ... in tid (bt_stack_manage), pid (droid.bluetooth)
   I ActivityManager: Process com.android.bluetooth (pid ...) has died: psvc PER
   ```

   Каждое падение случается ровно через ~60 мс после `btmtk_usb_resume`, то есть по одному на каждое пробуждение: около 10 падений в час ожидания, при включённом экране — ни одного.
4. После каждого падения стек перезапускается, но `system_server` оставляет по одному «мёртвому» `ConnectionRecord` к `com.android.bluetooth`. Когда их накапливается около 3000 (примерно 10–13 дней обычного использования), `bindService` отказывает:

   ```
   W ActivityManager: bindService exceeded max service connection number per process,
     callerApp:system intent:Intent { act=android.bluetooth.IBluetooth cmp=com.android.bluetooth/.btservice.AdapterService }
   E BluetoothManagerService: Fail to bind to: Intent { act=android.bluetooth.IBluetooth ... }
   ```

   С этого момента адаптер вообще не запускается (`svc bluetooth enable` отвечает «успех», состояние остаётся `OFF`) — пульт мёртв до перезагрузки.

   Все мёртвые записи висят на **одном** объекте подключения: том, через который `BluetoothManagerService` подключается к `com.android.bluetooth`. При выключении Bluetooth менеджер закрывает это подключение, и Android стирает все его записи разом (замер: 102 → 0). Но это работает, только пока подключение живое. На пределе повторное подключение после очередного падения не удаётся, у менеджера не остаётся подключения, поэтому выключение Bluetooth ничего не делает, а включение снова заканчивается отказом. Именно поэтому, когда пульт уже умер, переключение Bluetooth в настройках не помогало и спасала только перезагрузка.

Что **не** помогает: белый список Doze для `com.android.bluetooth`, отключение Low Power Standby (`low_power_standby_enabled=0`), повторное сопряжение пульта. Сервис автосопряжения Formovie (`com.fengmi.Autopair`) тоже не виноват — его попытки переподключения идут уже *после* рестарта стека.

Баг сидит в вендорном драйвере или HAL. Загрузчик заперт, сборка `user`, root и кастомных прошивок для этой платы не существует, а на ноябрь 2026 года Xiaomi не выпустила ни одного обновления прошивки с января 2026.

## Решение

Есть два рабочих подхода. Это одно и то же приложение (`art.applab.btawake`), поэтому одновременно можно поставить только один. Оба лежат в [Releases](../../releases).

| | v0.1.0: не давать системе спать | v0.2.0: чистить утечку |
|---|---|---|
| Падения Bluetooth в ожидании | нет | продолжаются, безвредны |
| Проектор спит в ожидании | нет | да, как на стоке |
| Процессор в ожидании | тёплый, 76–78 °C | прохладный, 52–56 °C |
| Лишнее потребление | несколько ватт | нет |
| Пульт в ожидании | всегда подключён | пропадает на ~3 с сразу после ухода в ожидание |

### Вариант 1 — v0.1.0: не давать системе спать

Для падения нужен цикл засыпания и пробуждения USB-чипа Bluetooth. Обычному приложению разрешено держать `PARTIAL_WAKE_LOCK`: он не даёт системе уйти в suspend, но экран, лампа, GPU и всё остальное спят как обычно. v0.1.0 — это foreground-сервис, который:

- берёт частичный wakelock при выключении экрана (`ACTION_SCREEN_OFF`),
- отпускает его при включении экрана,
- запускается сам при загрузке.

Результат замера: 14 падений Bluetooth за час до установки, **0 падений и 0 циклов suspend** за следующие 5 часов. Побочный эффект: проектор в ожидании остаётся доступен по Wi-Fi (adb работает без Wake-on-LAN).

**Цена: процессор в ожидании немного греется.** Чип не засыпает и простаивает на ~1,45 ГГц, а вентилятор вендорная прошивка в ожидании держит выключенным. Замер на L1 Pro: процессор 76–78 °C через 5 часов ожидания против 52–56 °C, когда проектор спит как обычно, плюс несколько ватт потребления. Пороги вентилятора по процессору в вендорном конфиге 93/95 °C, так что это в пределах нормы. Примерно через 4,5 часа вендорный thermal-сервис начинает раз в секунду писать в лог `adjustFanRpm error, get fan1 rpm = 0`: он хочет включить вентилятор, а тот отключён. Это просто шум в логе.

Исходники: тег [`v0.1.0`](../../tree/v0.1.0).

Проверка. Отправьте проектор в ожидание и посмотрите на wakelock:

```sh
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys power | grep BtAwake
#  PARTIAL_WAKE_LOCK  'BtAwake:standby' ACQ=-44s780ms (uid=..., pid=...)
adb shell logcat -d | grep -c "Process com.android.bluetooth.*has died"   # за ночь должно остаться 0
```

### Вариант 2 — v0.2.0: чистить утечку переключением Bluetooth

Само падение безвредно: стек поднимается за пару секунд, пульт переподключается. Пульт убивает только утечка (шаг 4 выше). v0.2.0 даёт проектору спать как на стоке и чистит утечку задолго до предела, выключая и снова включая Bluetooth:

- через 30 секунд после ухода проектора в ожидание (выключения экрана), чтобы не мешать, если пользователь быстро выключил и включил проектор;
- каждые 12 часов, пока проектор остаётся в ожидании (неточный будильник `setAndAllowWhileIdle`), на случай, если им не пользуются несколько дней;
- короткий частичный wakelock держится только на время самого цикла (около 3 секунд плюс 15 секунд на переподключение пульта), потом система засыпает как обычно;
- если цикл прервался при выключенном Bluetooth, сервис включит его при следующем старте; если Bluetooth выключил сам пользователь, сервис его не трогает.

Утечка растёт не больше чем на ~10 записей за час ожидания, так что между двумя циклами она и близко не подходит к ~3000. Вендорные падения в логе продолжаются, но больше не смертельны.

Результат замера, первый час ожидания на v0.2.0: 8 циклов suspend (проектор спит как на стоке), 9 вендорных падений, после каждого Bluetooth снова ON и пульт переподключён, 0 мёртвых подключений, процессор 52–56 °C. Android записывает цикл в `dumpsys bluetooth_manager` как `Disabled/Enabled due to APPLICATION_REQUEST by art.applab.btawake`.

Приложение намеренно собрано под SDK 32: начиная с Android 13, `BluetoothAdapter.enable()/disable()` всегда отказывают обычным приложениям с targetSdk 33+. На SDK 32 они работают с разрешением `BLUETOOTH_CONNECT`, поэтому ставить нужно с `-g`.

Исходники: ветка `main`.

Проверка. Запустите цикл вручную и посмотрите лог:

```sh
adb shell am start-foreground-service -a art.applab.btawake.CYCLE_NOW -n art.applab.btawake/.AwakeService
adb shell logcat -d -s BtAwake:I
#  I BtAwake : manual: turning Bluetooth off
#  I BtAwake : turning Bluetooth on (try 1, accepted=true)
#  I BtAwake : Bluetooth back ON, leaked connections cleared
```

`accepted=false` или `disable() refused` значит, что разрешение не выдано: переустановите с `-g`. Через несколько дней обычного использования число мёртвых подключений должно оставаться маленьким, оно обнуляется при каждом уходе в ожидание:

```sh
adb shell dumpsys activity services | grep -c "CR.*DEAD com.android.bluetooth"
```

## Установка

Скачайте APK выбранного варианта из [Releases](../../releases). Включите ADB на проекторе (Настройки → Система → Об устройстве → 7 раз нажать на *Сборка* → Для разработчиков → *Отладка по сети* / отладка по USB), затем с компьютера в той же сети:

```sh
adb connect <ip-проектора>
adb install -r -g btawake-v0.X.0.apk    # -g обязателен для v0.2.0 (BLUETOOTH_CONNECT)
adb shell dumpsys deviceidle whitelist +art.applab.btawake
adb shell am start-foreground-service -n art.applab.btawake/.AwakeService
```

Переход между вариантами:

- **v0.1.0 → v0.2.0.** Установка поверх v0.1.0 убивает её сервис и отпускает wakelock, поэтому проектор может уснуть и пропасть из Wi-Fi посреди `adb install`. Разбудите его и не давайте уснуть на время установки: `adb shell svc power stayon true`, после — `false`.
- **v0.2.0 → v0.1.0.** Android не ставит старую версию поверх новой, поэтому сначала `adb uninstall art.applab.btawake`.

Удаление: `adb uninstall art.applab.btawake`.

Чего не делать: не останавливать приложение принудительно и не очищать его данные в настройках проектора — после этого Android считает приложение «остановленным» и перестаёт запускать его при загрузке, пока его не стартуют вручную.

## Сборка из исходников

Gradle не нужен. Нужны Android SDK (build-tools 37, platform 34) и JDK (подходит тот, что идёт с Android Studio):

```sh
keytool -genkeypair -keystore release.jks -alias btawake -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BT Awake"
BTAWAKE_KS=release.jks BTAWAKE_KS_PASS=<пароль> sh build.sh
```

`build.sh` выполняет цепочку `javac → d8 → aapt2 → zipalign → apksigner` и кладёт результат в `out/btawake.apk`. Два исходных файла: `AwakeService.java` (wakelock в v0.1.0, цикл Bluetooth в v0.2.0) и `BootReceiver.java` (автозапуск). Чтобы собрать v0.1.0, сначала переключитесь на тег `v0.1.0`.

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
