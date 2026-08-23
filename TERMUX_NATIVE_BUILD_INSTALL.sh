#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/storage/downloads
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_ID="com.fantest.ownerguard"
VERSION="1.0.26"
BUILD="$SCRIPT_DIR/native-build"
SRC="$SCRIPT_DIR/app/src/main"
APK_OUT="$HOME/storage/downloads/OwnerGuard_Android_v${VERSION}_PATTERN_PIN_PASSWORD.apk"

find_android_jar() {
  local candidates=(
    "${ANDROID_HOME:-}/platforms/android-35/android.jar"
    "${ANDROID_HOME:-}/platforms/android-34/android.jar"
    "${ANDROID_HOME:-}/platforms/android-33/android.jar"
    "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar"
    "${ANDROID_SDK_ROOT:-}/platforms/android-34/android.jar"
    "${ANDROID_SDK_ROOT:-}/platforms/android-33/android.jar"
    "$PREFIX/share/android-sdk/platforms/android-35/android.jar"
    "$PREFIX/share/android-sdk/platforms/android-34/android.jar"
    "$PREFIX/share/android-sdk/platforms/android-33/android.jar"
    "$HOME/android-sdk/platforms/android-35/android.jar"
    "$HOME/android-sdk/platforms/android-34/android.jar"
    "$HOME/android-sdk/platforms/android-33/android.jar"
  )
  for f in "${candidates[@]}"; do [[ -f "$f" ]] && { echo "$f"; return 0; }; done
  find "$PREFIX" "$HOME" -path '*/platforms/android-*/android.jar' -type f 2>/dev/null | sort -V | tail -1
}

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 20; }; }
for tool in aapt2 javac jar d8 zipalign apksigner keytool zip; do need "$tool"; done
ANDROID_JAR="$(find_android_jar)"
[[ -f "$ANDROID_JAR" ]] || { echo "android.jar not found. Install an Android platform SDK." >&2; exit 21; }

echo "[OwnerGuard] Android API jar: $ANDROID_JAR"
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

aapt2 compile --dir "$SRC/res" -o "$BUILD/res/resources.zip"
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 28 \
  --target-sdk-version 33 \
  --version-code 10026 \
  --version-name "$VERSION" \
  --auto-add-overlay \
  -o "$BUILD/base.ap_" \
  "$BUILD/res/resources.zip"

mapfile -t SOURCES < <(find "$SRC/java" "$BUILD/gen" -name '*.java' -type f | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -cp "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  "${SOURCES[@]}"

jar --create --file "$BUILD/classes.jar" -C "$BUILD/classes" .
d8 --lib "$ANDROID_JAR" --min-api 28 --output "$BUILD/dex" "$BUILD/classes.jar"
(cd "$BUILD/dex" && zip -q -u "$BUILD/base.ap_" classes.dex)
zipalign -f 4 "$BUILD/base.ap_" "$BUILD/aligned.tmp"
apksigner sign \
  --ks "$SCRIPT_DIR/signing/ownerguard-release.jks" \
  --ks-key-alias ownerguard \
  --ks-pass pass:'OGuardStableKey_v1!' \
  --key-pass pass:'OGuardStableKey_v1!' \
  --out "$APK_OUT" \
  "$BUILD/aligned.tmp"
apksigner verify --verbose "$APK_OUT"

echo "[OwnerGuard] APK ready: $APK_OUT"
echo "[OwnerGuard] Opening Android package installer..."
if command -v termux-open >/dev/null 2>&1; then
  termux-open --content-type application/vnd.android.package-archive "$APK_OUT" 2>/dev/null \
    || termux-open "$APK_OUT" 2>/dev/null \
    || termux-open --view "$APK_OUT" 2>/dev/null \
    || true
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$APK_OUT" || true
fi
