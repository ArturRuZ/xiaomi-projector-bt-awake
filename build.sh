#!/bin/sh
# Builds BT Awake without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Usage: BTAWAKE_KS=release.jks BTAWAKE_KS_PASS=secret sh build.sh
# Env (optional): ANDROID_SDK, BUILD_TOOLS, PLATFORM, JAVA_HOME.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-37.0.0}"
AJ="$SDK/platforms/${PLATFORM:-android-34}/android.jar"
if [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
KS="${BTAWAKE_KS:-debug.jks}"
KS_PASS="${BTAWAKE_KS_PASS:-btawake1}"

if [ ! -f "$KS" ]; then
  echo "keystore $KS not found, generating a throwaway one"
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias btawake \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BT Awake" >/dev/null 2>&1
fi

rm -rf out; mkdir out
javac --release 8 -classpath "$AJ" -d out/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --min-api 30 --release --output out $(find out/classes -name '*.class')
"$BT/aapt2" link -o out/unsigned.apk --manifest AndroidManifest.xml -I "$AJ"
(cd out && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out out/btawake.apk out/aligned.apk
"$BT/apksigner" verify out/btawake.apk
echo "OK: out/btawake.apk"

# Install:
#   adb connect <projector-ip>
#   adb install -r -g out/btawake.apk
#   adb shell dumpsys deviceidle whitelist +art.applab.btawake
#   adb shell am start-foreground-service -n art.applab.btawake/.AwakeService
