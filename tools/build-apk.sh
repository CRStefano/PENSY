#!/bin/sh
# Build the PENSY APK without Gradle and without Google's Maven:
#   aapt2 (resources) + kotlinc (code) + dx (dex) + zipalign + apksigner.
#
# It is the complete app: the text reader is the prebuilt libpensyocr.so
# in android/app/src/main/jniLibs (see tools/build-ocr.sh) plus the model
# in android/app/src/main/assets. Android Studio builds the same app.
#
# Needed (paths can be overridden with environment variables):
#   ANDROID_JAR      android.jar to compile against (API 35)
#   ANDROID_RES_JAR  android.jar whose resource table aapt2 can read
#   ZXING_JAR        ZXing core 3.5.x
#   KOTLINC, KOTLIN_STDLIB, AAPT2, DX, ZIPALIGN, APKSIGNER
#   python3 and javap (for tools/check-indy.py)
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)

ANDROID_JAR=${ANDROID_JAR:?set ANDROID_JAR to an API 35 android.jar}
ANDROID_RES_JAR=${ANDROID_RES_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
ZXING_JAR=${ZXING_JAR:?set ZXING_JAR to zxing core 3.5.x}
KOTLINC=${KOTLINC:-kotlinc}
KOTLIN_STDLIB=${KOTLIN_STDLIB:-$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar}
AAPT2=${AAPT2:-aapt2}
DX=${DX:-dalvik-exchange}
ZIPALIGN=${ZIPALIGN:-zipalign}
APKSIGNER=${APKSIGNER:-apksigner}
# Signing key (never in the repository): the same key for every build, so new builds install as
# updates. If the file does not exist, a local test key is created.
KEYSTORE=${KEYSTORE:-$ROOT/tools/pensy-test.keystore}
KEYSTORE_PASS=${KEYSTORE_PASS:-android}

APP=android/app/src/main
OUT=build/apk
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

# 1. Manifest for a plain aapt2 build: add the package, drop the Gradle-only
#    "tools:node=remove" lines (there is no library to remove anything from).
sed -e 's|<manifest |<manifest package="app.pensy" android:versionCode="11" android:versionName="0.8" |' \
    -e '/tools:node="remove"/d' "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

# 2. Resources
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_RES_JAR" --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 35 -A "$APP/assets" "$OUT/res.zip"
javac --release 8 -nowarn -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

# 3. Kotlin: core + app. No indy lambdas: dx predates them.
"$KOTLINC" -no-jdk -no-reflect -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
    -cp "$ANDROID_JAR:$ZXING_JAR:$OUT/classes" -d "$OUT/classes" \
    core/src "$APP/java"

# 3b. Android has no LambdaMetafactory and dx does not rewrite invokedynamic:
#     fail now if any app code can reach a library function that uses it.
python3 tools/check-indy.py "$OUT/classes" "$KOTLIN_STDLIB" "$ZXING_JAR"

# 4. Dex (app + Kotlin stdlib + ZXing)
cp "$KOTLIN_STDLIB" "$OUT/stdlib.jar"
zip -q -d "$OUT/stdlib.jar" 'META-INF/versions/*' >/dev/null 2>&1 || true
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes" "$OUT/stdlib.jar" "$ZXING_JAR"

# 5. Package (code, native text reader), align, sign
(cd "$OUT" && zip -q -j base.apk classes.dex)
mkdir -p "$OUT/native/lib" && cp -r "$APP/jniLibs/." "$OUT/native/lib/"
(cd "$OUT/native" && zip -q -r ../base.apk lib)
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" -alias pensy \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PENSY test" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --out "$OUT/PENSY.apk" "$OUT/aligned.apk"
echo "OK: $OUT/PENSY.apk"
