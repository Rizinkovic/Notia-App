#!/usr/bin/env bash
# Notia Mobile - local APK build (no EAS cloud, no Android Studio needed).
# Put this file in the notia-mobile folder (next to package.json) and run:   bash build-apk.sh
#
# Options:
#   --offline    Gradle uses only its local cache (works after one successful online build)
#   --clean      delete ./android and regenerate it
#   --install    install the APK on the running emulator/phone with adb when done
#   --debug      build a debug APK instead of release (bigger, easier to debug)
# Env:
#   ARCHS="arm64-v8a,x86_64"   CPU types to include (default covers phones + the emulator)
#   ANDROID_HOME=/path/to/sdk  Android SDK folder (auto-detected if not set)
#
# One-time needs: Node 20+, JDK 17, Android SDK with platform 36 + build-tools + platform-tools.
# The first build needs internet (Gradle/npm downloads). Later builds can use --offline.
set -euo pipefail

OFFLINE=0; CLEAN=0; INSTALL=0; VARIANT=Release
for a in "$@"; do
  case "$a" in
    --offline) OFFLINE=1 ;; --clean) CLEAN=1 ;; --install) INSTALL=1 ;; --debug) VARIANT=Debug ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "Unknown option: $a"; exit 1 ;;
  esac
done
ARCHS="${ARCHS:-arm64-v8a,x86_64}"
cd "$(dirname "$0")"
say() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
[ -f package.json ] && [ -f app.json ] || die "Run this script from the notia-mobile folder."

# ---------- 1. tools ----------
say "Checking tools"
command -v node >/dev/null || die "Node.js not found (install Node 20+)."
[ "$(node -p 'process.versions.node.split(".")[0]')" -ge 20 ] || die "Node 20+ required (found $(node -v))."
command -v java >/dev/null || die "Java not found. Install JDK 17 (e.g. Temurin 17) and set JAVA_HOME."
JV="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)[.".].*/\1/')"
[ "$JV" = "17" ] || [ "$JV" = "21" ] || die "JDK 17 (or 21) required, found Java $JV."

if [ -z "${ANDROID_HOME:-}" ]; then ANDROID_HOME="${ANDROID_SDK_ROOT:-}"; fi
if [ -z "$ANDROID_HOME" ]; then
  for d in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "${LOCALAPPDATA:-}/Android/Sdk" "/c/Users/${USERNAME:-}/AppData/Local/Android/Sdk" "$HOME/android-sdk"; do
    [ -d "$d/platform-tools" ] || [ -d "$d/cmdline-tools" ] && { ANDROID_HOME="$d"; break; }
  done
fi
[ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ] || die "Android SDK not found. Set ANDROID_HOME to your SDK folder (the one your emulator uses)."
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
echo "SDK: $ANDROID_HOME   Java: $JV   Node: $(node -v)"

# Make sure the SDK pieces Gradle needs are present (needs internet only if something is missing).
SDKM="$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager* 2>/dev/null | head -1 || true)"
need_sdk() { [ -d "$ANDROID_HOME/$1" ]; }
if ! need_sdk "platforms/android-36" || ! ls "$ANDROID_HOME"/build-tools/3* >/dev/null 2>&1; then
  [ -n "$SDKM" ] || die "Missing Android platform 36 / build-tools and no sdkmanager found. In your SDK install: platforms;android-36 and build-tools;36.0.0 (or install the 'cmdline-tools')."
  [ "$OFFLINE" = 1 ] && die "SDK pieces missing and --offline set. Run once online without --offline."
  say "Installing missing SDK packages"
  yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  "$SDKM" --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;36.0.0" "platform-tools"
fi

# ---------- 2. JS dependencies ----------
say "JavaScript dependencies"
if [ ! -d node_modules ]; then
  [ "$OFFLINE" = 1 ] && npm ci --offline --legacy-peer-deps || npm install --legacy-peer-deps --no-audit --no-fund
fi
npx tsc --noEmit || die "TypeScript errors - fix them before building."

# Plain-HTTP Wi-Fi transfer needs cleartext traffic allowed in the built app.
if ! grep -q expo-build-properties app.json; then
  say "Enabling cleartext traffic (Wi-Fi transfer)"
  [ "$OFFLINE" = 1 ] && die "expo-build-properties missing; run once online."
  npx expo install expo-build-properties -- --legacy-peer-deps
  node -e '
    const fs=require("fs"),j=JSON.parse(fs.readFileSync("app.json"));
    j.expo.plugins=(j.expo.plugins||[]).filter(p=>(Array.isArray(p)?p[0]:p)!=="expo-build-properties");
    j.expo.plugins.push(["expo-build-properties",{android:{usesCleartextTraffic:true}}]);
    fs.writeFileSync("app.json",JSON.stringify(j,null,2));'
fi

# ---------- 3. native project ----------
[ "$CLEAN" = 1 ] && { say "Removing ./android"; rm -rf android; }
if [ ! -d android ]; then
  say "Generating the Android project (expo prebuild)"
  npx expo prebuild --platform android --no-install
fi
# local.properties wants forward slashes, also on Windows
SDK_FWD="$(printf '%s' "$ANDROID_HOME" | sed 's#\\#/#g')"
printf 'sdk.dir=%s\n' "$SDK_FWD" > android/local.properties
# Give Gradle enough memory and keep builds fast
grep -q '^org.gradle.jvmargs=-Xmx3g' android/gradle.properties 2>/dev/null || {
  sed -i.bak '/^org.gradle.jvmargs=/d' android/gradle.properties 2>/dev/null || true
  echo 'org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g' >> android/gradle.properties; }

# ---------- 4. build ----------
say "Building $VARIANT APK ($ARCHS)"
cd android
chmod +x gradlew 2>/dev/null || true
GFLAGS=(-PreactNativeArchitectures="$ARCHS" --no-daemon --console=plain)
[ "$OFFLINE" = 1 ] && GFLAGS+=(--offline)
./gradlew "assemble$VARIANT" "${GFLAGS[@]}"
cd ..

# ---------- 5. result ----------
LOWER="$(printf '%s' "$VARIANT" | tr 'A-Z' 'a-z')"
APK="$(ls android/app/build/outputs/apk/"$LOWER"/*.apk 2>/dev/null | head -1 || true)"
[ -n "$APK" ] || die "Build finished but no APK found in android/app/build/outputs/apk/$LOWER/"
OUT="notia-$LOWER.apk"; cp "$APK" "$OUT"
say "Done: $(pwd)/$OUT ($(du -h "$OUT" | cut -f1))"

if [ "$INSTALL" = 1 ]; then
  command -v adb >/dev/null || die "adb not found in $ANDROID_HOME/platform-tools."
  say "Installing on the connected device"
  adb install -r "$OUT"
  adb shell monkey -p com.notia.mobile -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
fi
echo
echo "Install manually with:  adb install -r $OUT   (or drag the APK onto the emulator)"
