# Compatibility Guide

This document describes what this module supports today, how release APK variants are produced, and where to look when behaviour changes.

## Table of contents

- [1) Scope and baseline](#1-scope-and-baseline)
- [2) Runtime and framework compatibility](#2-runtime-and-framework-compatibility)
- [3) Release APK variants](#3-release-apk-variants)
- [4) Country/locale fallback behaviour](#4-countrylocale-fallback-behaviour)
- [5) What this module does not target](#5-what-this-module-does-not-target)
- [6) Contributor checklist](#6-contributor-checklist)
- [7) Related docs](#7-related-docs)

## 1) Scope and baseline

- Target app scope: **`com.google.android.dialer` only**.
- The module ships both legacy Xposed and modern libxposed entry metadata.
- Current codebase defaults to `SAFE_GLOBAL_ENABLE` compatibility mode for normal builds.

## 2) Runtime and framework compatibility

- Primary hook entrypoint remains `io.github.vvb2060.callrecording.xposed.Init`.
- Modern API entrypoint (`ModernInit`) is included for modern-capable frameworks.
- Android 16+ guardrails are enabled in code (`isAndroid16()` / conservative mode path).

If you need implementation detail, read the source directly instead of duplicating it here.

## 3) Release APK variants

The release workflow now builds multiple signed APK assets from the same commit by changing only:

- `RecordingCompatibilityMode MODE`

### Published assets

For a release tag `vX.Y.Z`, assets are:

- `callrecording-vX.Y.Z.apk` (**primary**) → `SAFE_GLOBAL_ENABLE`
- `callrecording-vX.Y.Z-force-US.apk` → `FORCE_LEGACY_US`
- `callrecording-vX.Y.Z-observe.apk` → `OBSERVE_ONLY`

Each APK also includes a matching `.sha256` file.

### Primary APK rule

The primary release APK must keep silent-mode features enabled:

- `ENABLE_PROMPT_TTS_HOOKS = true`
- `ENABLE_SILENT_PROMPT_FALLBACK = true`

## 4) Country/locale fallback behaviour

The locale hook keeps Dialer's original locale **only when both are true**:

1. Dialer returns a valid `Locale`.
2. The supplied country code is **not** in the strict unsupported-country list.

If the locale is null/invalid, or the country code is unsupported, fallback locale is `Locale.US`.

Unsupported-country list used by the hook:

`AE, AT, AZ, BD, BE, BG, BH, CH, CI, CM, CO, CY, CZ, DE, DK, EE, EG, ES, FI, FR, GH, GR, HR, HU, ID, IE, IQ, IR, IT, JO, KW, LB, LT, LU, LV, MA, MT, MY, MZ, NG, NL, NP, OM, PA, PH, PK, PL, PT, PY, QA, RO, RU, RW, SA, SE, SI, SK, SN, TN, TR, UA, VN, YE, ZA, ZW`

## 5) What this module does not target

- It does not broaden scope to system processes/services.
- It does not claim all Dialer versions/devices behave identically.
- It does not treat `OBSERVE_ONLY` as a feature-unlock mode.

## 6) Contributor checklist

When changing compatibility logic:

1. Update code comments near `RecordingCompatibilityMode` and locale hook logic.
2. Keep release asset names and mode mapping consistent with workflow output.
3. Re-run/adjust checks in `scripts/verify_silent_mode.sh` when silent-mode behaviour changes.
4. Update this file only for behaviour that is already implemented in code/workflow.

## 7) Startup summary log

On every successful hook installation the module emits a structured summary to logcat at `WARN` level under the tag `CallRecording`.  Example:

```
W CallRecording: === CallRecording startup ===
  process=com.google.android.dialer entrypoint=legacy
  sdk=36 device=tegu model=Pixel 9 Pro
  mode=SAFE_GLOBAL_ENABLE safeProfile=true conservative=false
  groups:
    eligibility{canRecordCall=INSTALLED withinCrosbyGeoFence=INSTALLED isCallRecordingCountry=INSTALLED}
    locale{getSupportedLocaleFromCountryCode=INSTALLED}
    tts{synthesizeToFile=INSTALLED speak=INSTALLED isLanguageAvailable=INSTALLED}
    media{assetOpen=INSTALLED assetOpenFd=INSTALLED disclosure=INSTALLED}
    diagnostics{onResume=INSTALLED}
  degraded: none
=== startup complete ===
```

### Status values

| Status | Meaning |
|--------|---------|
| `INSTALLED` | Hook wired and active. |
| `SKIPPED` | Intentionally not installed (configuration flag disabled). |
| `FAILED` | Installation threw an exception; hook is absent. |
| `AMBIGUOUS` | Multiple Dex targets found; first candidate used (logged as warning). |
| `OBSERVE_ONLY` | Hook wired but result modifications suppressed (`OBSERVE_ONLY` mode). |
| `NOT_FOUND` | Target method or class absent in this Dialer build. |

A **degraded** group contains at least one `FAILED`, `NOT_FOUND`, or `AMBIGUOUS` hook.  Degraded eligibility or locale groups are the first thing to check when the Record button is absent.

To capture the summary:
```bash
adb logcat -d | grep "CallRecording"
```

## 8) Related docs

- Project overview and install notes: `README.md`
- Release automation: `.github/workflows/release.yml`
- Core hook logic: `app/src/main/java/io/github/vvb2060/callrecording/xposed/Init.java`
- Silent-mode verification helper: `scripts/verify_silent_mode.sh`
