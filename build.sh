#!/usr/bin/env bash
# Builds app.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Run from Git Bash: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

# Uses ANDROID_HOME / JAVA_HOME when set; otherwise falls back to the portable
# install under %LOCALAPPDATA%\devtools that this project was first built with.
TOOLS="${LOCALAPPDATA:-}/devtools"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TOOLS/android-sdk}}"
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(ls -d "$TOOLS"/jdk-17* | head -1)"
fi
BT="$SDK/build-tools/36.0.0"

# Windows ships tools as .exe/.bat and wants Windows-style paths for the jar.
if command -v cygpath >/dev/null 2>&1; then
    EXE=.exe BAT=.bat
    ANDROID_JAR="$(cygpath -w "$SDK/platforms/android-36/android.jar")"
    export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
    export JAVA_HOME="$(cygpath -w "$JAVA_HOME")"
else
    EXE= BAT=
    ANDROID_JAR="$SDK/platforms/android-36/android.jar"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

rm -rf build
mkdir -p build/classes build/dex build/gen

echo "[1/6] resources"
"$BT/aapt2$EXE" compile --dir res -o build/res.zip
"$BT/aapt2$EXE" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --java build/gen -o build/unsigned.apk build/res.zip

echo "[2/6] javac"
javac -source 11 -target 11 -Xlint:-options -Xlint:-deprecation -encoding UTF-8 \
    -classpath "$ANDROID_JAR" -d build/classes \
    $(find src build/gen -name '*.java')

echo "[3/6] d8"
"$BT/d8$BAT" --release --min-api 26 --lib "$ANDROID_JAR" \
    --output build/dex $(find build/classes -name '*.class')

echo "[4/6] add classes.dex"
(cd build/dex && "$BT/aapt$EXE" add ../unsigned.apk classes.dex >/dev/null)

echo "[5/6] zipalign"
"$BT/zipalign$EXE" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
if [ ! -f debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Assistant Button" >/dev/null 2>&1
fi
"$BT/apksigner$BAT" sign --ks debug.keystore --ks-pass pass:android \
    --out app.apk build/aligned.apk

echo "OK: $(pwd)/app.apk"
