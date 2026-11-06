#!/usr/bin/env bash
# SKIN RUSH -> APK без Gradle: aapt2 -> javac -> d8 -> zip(assets) -> zipalign -> apksigner
#
#   ./tools/build-apk.sh <index.html> [versionName] [versionCode] [out.apk]
#
# Без версии в аргументах берёт её из HTML: const VERSION = '4.4.6'
#   -> versionName 4.4.6, versionCode 4004006 (major*1000000 + minor*1000 + patch)
# Схема специально выше старых 4xxx (4490 у 4.4.9): Android видит обновление.
#
# Окружение:
#   ANDROID_HOME   путь к SDK (на раннерах GitHub уже есть)
#   KEYSTORE, KS_ALIAS (skinrush), KS_PASS или KS_PASS_FILE
#   UNSIGNED=1     собрать без подписи (проверка цепочки)
set -euo pipefail

HTML="${1:?укажи путь к index.html}"
OUT="${4:-$(pwd)/skin-rush.apk}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Не задан ANDROID_HOME"; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "Нет build-tools в $SDK"; exit 1; }
[ -n "$PLATFORM" ] || { echo "Нет platforms в $SDK"; exit 1; }
JAR="$PLATFORM/android.jar"
export PATH="$BT:$SDK/platform-tools:$PATH"

echo "build-tools: $(basename "$BT")   platform: $(basename "$PLATFORM")"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

VER_SRC="$(grep -oE "const VERSION *= *'[0-9]+\.[0-9]+\.[0-9]+'" "$HTML" | head -1 | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" || true)"
NAME="${2:-${VER_SRC:-1.0.0}}"
if [ -n "${3:-}" ]; then VCODE="$3"; else
  IFS=. read -r MA MI PA <<< "$NAME"; PA="${PA:-0}"; MI="${MI:-0}"; MA="${MA:-0}"
  VCODE=$(( MA * 1000000 + MI * 1000 + PA ))
fi
echo "версия: $NAME (versionCode $VCODE)"

mkdir -p "$APP/assets"
cp "$HTML" "$APP/assets/index.html"
cp "$APP/AndroidManifest.xml" "$WORK/AndroidManifest.xml"
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VCODE\"/; s/android:versionName=\"[0-9.]*\"/android:versionName=\"$NAME\"/" "$WORK/AndroidManifest.xml"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"

( cd "$APP" && aapt2 compile --dir res -o "$WORK/res.zip" )
aapt2 link -o "$WORK/base.apk" -I "$JAR" --manifest "$WORK/AndroidManifest.xml" \
  -R "$WORK/res.zip" --java "$WORK/gen" --auto-add-overlay

javac -source 8 -target 8 -bootclasspath "$JAR" -encoding UTF-8 -d "$WORK/classes" \
  $(find "$APP/src" -name '*.java') $(find "$WORK/gen" -name '*.java')

d8 --release --min-api 23 --lib "$JAR" --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

python3 - "$WORK" "$APP" <<'PY'
import sys, zipfile, pathlib
work, app = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
apk = work / "unsigned.apk"
(work / "base.apk").rename(apk)
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(work / "dex" / "classes.dex", "classes.dex")
    for f in sorted((app / "assets").rglob("*")):
        rel = f.relative_to(app / "assets").as_posix()
        if f.is_file() and not any(part.startswith(".") for part in rel.split("/")):
            z.write(f, "assets/" + rel)
PY

zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

if [ "${UNSIGNED:-0}" = "1" ]; then
  cp "$WORK/aligned.apk" "$OUT"; echo "Готово (без подписи): $OUT"; exit 0
fi

[ -n "${KS_PASS:-}" ] || KS_PASS="$(cat "${KS_PASS_FILE:-/dev/null}" 2>/dev/null || true)"
[ -n "${KS_PASS:-}" ] || { echo "Нет пароля keystore: задай KS_PASS или KS_PASS_FILE"; exit 1; }
[ -n "${KEYSTORE:-}" ] || { echo "Нет KEYSTORE"; exit 1; }
ALIAS="${KS_ALIAS:-skinrush}"

apksigner sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$WORK/aligned.apk"

apksigner verify --print-certs "$OUT" | head -6
aapt2 dump badging "$OUT" | grep -E "^package|^application-label" || true
echo "---"
echo "HTML в APK: $(unzip -p "$OUT" assets/index.html | md5sum | cut -d' ' -f1)"
echo "HTML исходный: $(md5sum "$HTML" | cut -d' ' -f1)"
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"

# trigger: 2026-11-05 23:09 UTC
