#!/usr/bin/env bash
set -euo pipefail

MODULE_PKG="io.github.vvb2060.callrecording"
DIALER_PKG="com.google.android.dialer"

adb shell su -c "/data/adb/lspd/cli --json modules ls" | sed -n '1,120p'
adb shell su -c "/data/adb/lspd/cli --json scope ls ${MODULE_PKG}" | sed -n '1,120p'

echo "[1/4] Clear logcat"
adb logcat -c

echo "[2/4] Launch Dialer"
adb shell monkey -p "${DIALER_PKG}" -c android.intent.category.LAUNCHER 1 >/dev/null

echo "[3/4] Place a test call manually, answer it, then tap Record in Dialer UI."
read -r -p "Press Enter after call recording has started..." _

echo "[4/4] Extract module logs"
adb logcat -d | rg "CallRecording|silent fallback|Silent mode active|synthesizeToFile|speak\(CharSequence" || true

echo "Done. Verify that at least one silent fallback log appears and no crash stack traces are present."
