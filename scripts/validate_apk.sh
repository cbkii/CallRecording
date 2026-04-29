#!/usr/bin/env bash
# validate_apk.sh – verifies that a built release or debug APK contains all expected
# packaging artefacts: Xposed metadata, native libraries, prompt assets, and key classes.
#
# Usage:
#   ./scripts/validate_apk.sh <path-to-apk>
#
# Exit code:
#   0  – all checks passed
#   1  – one or more checks failed (details printed to stdout)

set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" ]]; then
    # Try to auto-locate the most recently built APK under app/build/outputs.
    APK=$(find app/build/outputs -name "*.apk" | head -1 || true)
fi

if [[ -z "$APK" || ! -f "$APK" ]]; then
    echo "ERROR: APK not found. Pass path as first argument or run after ./gradlew assemble."
    exit 1
fi

echo "Validating APK: $APK"
echo "------------------------------------------------------------"

FAIL=0

check_entry() {
    local label="$1"
    local pattern="$2"
    if unzip -l "$APK" | grep -qF "$pattern"; then
        echo "  [OK]   $label ($pattern)"
    else
        echo "  [FAIL] $label – '$pattern' not found in APK"
        FAIL=1
    fi
}

# Xposed metadata ---------------------------------------------------------------
check_entry "legacy xposed_init"             "assets/xposed_init"
check_entry "modern java_init.list"          "META-INF/xposed/java_init.list"

# Entrypoint class names in metadata --------------------------------------------
if unzip -p "$APK" assets/xposed_init 2>/dev/null | grep -q "Init"; then
    echo "  [OK]   legacy xposed_init references Init"
else
    echo "  [FAIL] legacy xposed_init does not reference Init"
    FAIL=1
fi

if unzip -p "$APK" META-INF/xposed/java_init.list 2>/dev/null | grep -q "ModernInit"; then
    echo "  [OK]   java_init.list references ModernInit"
else
    echo "  [FAIL] java_init.list does not reference ModernInit"
    FAIL=1
fi

# Native library ----------------------------------------------------------------
check_entry "native lib arm64-v8a"           "lib/arm64-v8a/libdex_helper.so"

# Prompt asset ------------------------------------------------------------------
check_entry "silent prompt asset"            "assets/silent_16k.wav"

echo "------------------------------------------------------------"
if [[ $FAIL -eq 0 ]]; then
    echo "All APK packaging checks passed."
else
    echo "One or more APK packaging checks FAILED."
    exit 1
fi
