# Compatibility Notes — Android 16

---

## Vector / lspd compatibility note

**Checked:** JingMatrix/Vector commit `b71c33d`, latest release **Vector v2.0 (build 3021,
2026-03-22)**.

### API compatibility — confirmed ✅

Vector ships a complete `legacy/` module that implements the full `de.robv.android.xposed.*`
namespace on top of LSPlant.  All APIs used by this module are confirmed present and correct:

| API | Status |
|-----|--------|
| `IXposedHookLoadPackage` | ✅ confirmed — detected via `assets/xposed_init` |
| `XC_LoadPackage.LoadPackageParam` | ✅ confirmed — populated by `LegacyDelegateImpl.onPackageLoaded` |
| `XC_MethodHook` / `beforeHookedMethod` / `afterHookedMethod` | ✅ confirmed — `legacy/src/main/java/de/robv/android/xposed/XC_MethodHook.java` |
| `XC_MethodReplacement` | ✅ confirmed (we do not use it in this fork) |
| `XposedBridge.hookMethod` | ✅ confirmed — routes through `hook_bridge.cpp` |
| `param.hasThrowable()` | ✅ confirmed — `throwable != null` check |
| `param.getThrowable()` | ✅ confirmed — returns `throwable` field |
| `param.setThrowable(Throwable)` | ✅ confirmed — sets `throwable`, **clears** `result` to `null`, sets `returnEarly=true` |
| `param.getResult()` | ✅ confirmed — returns `result` field |
| `param.setResult(Object)` | ✅ confirmed — sets `result`, **clears** `throwable` to `null`, sets `returnEarly=true` |

**Critical ordering note for `setThrowable(null)` + `setResult(...)`:**

`setThrowable(null)` clears both `throwable` and `result`.  Always call `setResult(Boolean.TRUE)`
*after* `setThrowable(null)` to ensure the result is non-null.  This fork does this correctly.
Alternatively, calling only `setResult(Boolean.TRUE)` is equivalent because `setResult` also clears
the throwable — but the explicit two-call form in this fork is more readable.

### Synchronous hook installation — confirmed safe ✅

The legacy README does not restrict or warn against synchronous hook installation from
`handleLoadPackage`.  Starting background threads from `handleLoadPackage` is also not restricted,
but this fork removed the background thread anyway for determinism (see below).

### Reflective hooks on framework classes — confirmed safe ✅

No Vector documentation or source restricts reflective hooks on framework classes (`TextToSpeech`,
`Activity`) from an app-scoped process.

### Module metadata — confirmed sufficient ✅

Vector reads `assets/xposed_init` for the Java entrypoint and the following `AndroidManifest.xml`
metadata:

- `xposeddescription` — used in the Vector manager UI
- `xposedminversion` — used for `XSharedPreferences` path selection (> 92 triggers safe-zone)
- `xposedscope` — used as the default scope hint

No Vector-specific metadata field is required beyond these legacy fields.  The current
`AndroidManifest.xml` is sufficient.

### Debug builds — recommended ✅

Vector's README explicitly states:

> Debug builds are recommended for users encountering issues or performing troubleshooting.

If you are diagnosing hook failures or crashes, install the **Debug** release of Vector
(`Vector-v2.0-3021-Debug.zip`) rather than the Release build.

### Vector CLI — confirmed commands ✅

CLI source confirmed from `daemon/src/main/kotlin/.../Cli.kt`.  The CLI binary identifies as
`vector-cli`.  All commands require root UID (`su -c`).

The `--json` flag must come **before** the subcommand:

```sh
# Correct
su -c '/data/adb/lspd/cli --json status'

# Wrong
su -c '/data/adb/lspd/cli status --json'
```

**Commands confirmed in source:**

```text
vector-cli [--json]
  status
  modules
    ls [-e | -d]
    enable  <PKG> [...]
    disable <PKG> [...]
  scope
    ls  <MODULE_PKG>
    add <MODULE_PKG> <pkg/user_id> [...]
    set <MODULE_PKG> <pkg/user_id> [...]
    rm  <MODULE_PKG> <pkg/user_id> [...]
  config
    get <KEY>
    set <KEY> <VALUE>
  db
    backup  <PATH>
    restore <PATH>
    reset  [--force | -f]
  log
    cat  [-v]
    tail [-v]
    clear [-v]
```

**Commands that do NOT exist — do not use:**

- `modules list` (it is `modules ls`)
- `scope get` (it is `scope ls`)
- `scope list` (it is `scope ls`)
- Any raw SQLite invocation against `/data/adb/lspd/config/modules_config.db`

### Scope format — confirmed ✅

Vector scope entries use `package_name/user_id`.  For a standard single-user device, the user ID
is `0`.

```text
com.google.android.dialer/0
```

### No incompatibilities found

No part of the current fork contradicts Vector v2.0 API or documented behaviour.  The move from
`XC_MethodReplacement` to `XC_MethodHook.afterHookedMethod` is Vector-safe because Vector's
execution loop always runs the original method before `afterHookedMethod` unless `returnEarly` was
set in `beforeHookedMethod`.

---

## Vector diagnostics quick reference

```sh
# --- Framework and module status ---
su -c '/data/adb/lspd/cli --json status'
su -c '/data/adb/lspd/cli --json modules ls'
su -c '/data/adb/lspd/cli --json scope ls io.github.vvb2060.callrecording'

# --- Set scope (first install / after reboot) ---
su -c '/data/adb/lspd/cli --json scope set io.github.vvb2060.callrecording com.google.android.dialer/0'

# --- Log inspection ---
su -c '/data/adb/lspd/cli log cat -v | grep -i CallRecording'
su -c '/data/adb/lspd/cli log tail -v'

# --- Non-JSON equivalents ---
su -c '/data/adb/lspd/cli status'
su -c '/data/adb/lspd/cli modules ls'
su -c '/data/adb/lspd/cli scope ls io.github.vvb2060.callrecording'
```

Full diagnostic bundle:

```sh
su -c '
echo "=== Vector status ==="
/data/adb/lspd/cli --json status 2>&1 || /data/adb/lspd/cli status 2>&1

echo
echo "=== Vector modules ==="
/data/adb/lspd/cli --json modules ls 2>&1 || /data/adb/lspd/cli modules ls 2>&1

echo
echo "=== CallRecording scope ==="
/data/adb/lspd/cli --json scope ls io.github.vvb2060.callrecording 2>&1 || \
  /data/adb/lspd/cli scope ls io.github.vvb2060.callrecording 2>&1
'
```

Required scope:

```text
io.github.vvb2060.callrecording → com.google.android.dialer/0
```

Do **not** scope to:

```text
android
system_server
com.android.phone
com.android.server.telecom
com.google.android.gms
com.google.android.googlequicksearchbox
```

---

## Final compatibility statement

This fork is:

- **Legacy Xposed API** — uses `de.robv.android.xposed.*` only; no `libxposed` migration.  This is
  intentional: Vector confirms full legacy API consistency with original Xposed.
- **Dialer-only scope** — `com.google.android.dialer` / no system or GMS scopes.
- **Vector-compatible** — all hooks, param methods, and module metadata confirmed correct against
  Vector v2.0 (commit `b71c33d`).
- **Android 16 safe-profile aware** — `isAndroid16()` detects SDK >= 36
  and activates conservative staged behaviour.

---

This document describes the safe-profile behaviour added for Android 16 (SDK 36+) and explains the design rationale for every hook change in this fork.

---

## Xposed scope

The module is **scoped only to `com.google.android.dialer`**.

Do **not** add any of the following to the scope:
- `android` / `system_server`
- `com.android.phone`
- `com.android.server.telecom`
- `com.google.android.gms`
- `com.google.android.googlequicksearchbox`

Adding system scopes to this module can trigger crashes in `CallAudioModeStateMachine` and
`AudioManager` because hooks running in the wrong process may skip setup that those components
depend on.

---

## Basic call recording vs Call Notes

**Basic call recording** is the core feature this module targets.  It is available on:

| Requirement | Value |
|-------------|-------|
| Device | Pixel 6 or newer |
| OS | Android 14 or newer |
| App | Latest Phone by Google (full, not downloadable stub) |
| Region | Supported country/region |

Access path on Pixel: **Phone app → Call Assist → Call Recording**

**Call Notes** is a separate, AI-powered feature with its own availability rules.  It is **not**
available on A-series Pixel devices, and this module makes no attempt to enable it.

Recording **cannot** start:
- before the call is answered,
- while the call is on hold,
- during conference calls.

Recordings are stored inside the Phone app call history.  Deleting a call log entry may also
delete its recording.

---

## Why boolean hooks are now after-hooks instead of replacements

The original code used `XC_MethodReplacement` for `canRecordCall`, `withinCrosbyGeoFence`, and
`isCallRecordingCountry`.  A hard replacement skips the entire original method body, which can
prevent Dialer from completing internal state initialisation (caches, feature-flag evaluation,
side effects) before the call-recording code path proceeds.

This fork replaces them with `XC_MethodHook.afterHookedMethod` so the original method runs first
and the final boolean return value is overridden to `true` only after the original has finished.

If the original method throws (e.g. due to a spoofed/inconsistent environment), the throwable is
swallowed and the result forced to `true` only for the three known eligibility hooks:
`canRecordCall`, `withinCrosbyGeoFence`, `isCallRecordingCountry`.  Unrelated Dialer crashes are
never swallowed.

---

## Why regional / geofence / country eligibility is still forced true

Google Dialer gates call recording behind several checks:

- `canRecordCall` — device + carrier + feature eligibility
- `withinCrosbyGeoFence` — geographic boundary check
- `isCallRecordingCountry` — country/region allow-list

In unsupported regions these return `false` and the recording UI disappears.  Forcing them to
`true` is the core purpose of this module.  The fix makes the forcing safer, not optional.

---

## Why locale fallback still exists

`getSupportedLocaleFromCountryCode` maps a country code to a supported recording locale.  In
unsupported regions it returns `null` or an unsupported locale, which causes the recording path
to abort.

This fork uses an after-hook that:
1. lets the original method run;
2. preserves the result if Dialer returned a valid `Locale`;
3. substitutes a supported fallback locale only when the original returned `null` or a non-Locale
   value.

Default fallback order: `en-AU` → `Locale.US` → `Locale.ENGLISH`.  The device default locale is
preferred when it is an English/AU locale.

If testing shows `en-AU` does not expose the recording path, switch to `FORCE_LEGACY_US` mode
(see below).

---

## Why prompt / TTS hooks are staged instead of removed

Google call recording notifies both parties with a disclosure prompt.  On some builds and
regions, the prompt audio file is synthesised via TTS before recording can start.  Removing TTS
hooks entirely can prevent the record button from appearing.

This fork keeps the TTS hooks but changes their behaviour:

| Hook | Old | New |
|------|-----|-----|
| `dispatchOnInit` | Pre-emptively forces `SUCCESS` before original runs | Logs args; only overrides when original failed **and** conservative mode is off |
| `isLanguageAvailable` | Claims every language is available | Only claims availability for the selected recording locale |
| `synthesizeToFile` | Replaces synthesis text with `""` before original | Lets original synthesise; writes silent WAV fallback only if `ENABLE_SILENT_PROMPT_FALLBACK=true` |

---

## Why silent prompt fallback is disabled by default

Writing a zero-length WAV unconditionally can create a mismatch between the audio state Dialer
expects (a real prompt synthesised successfully) and what was actually produced.  On some Dialer
versions this may cause the recording flow to abort or a crash downstream.

Enable it only if TTS synthesis is confirmed broken on your build:

```java
private static final boolean ENABLE_SILENT_PROMPT_FALLBACK = true;
```

---

## Android 16 safe profile

When the module detects `SDK_INT >= 36`, the safe profile is active.

Logcat marker:

```text
CallRecording: Android 16 safe profile active
```

The `safeProfile` flag in `handleLoadPackage` gates only the logcat marker above.
All other behaviours listed below are controlled by their own compile-time constants in `Init.java`
and apply on **every** Android version unless those constants are changed:

| Behaviour | Controlling constant |
|-----------|---------------------|
| Boolean availability after-hooks | `MODE` |
| Country / geofence result forced `true` | `MODE` |
| Deterministic locale fallback | `FORCE_US_RECORDING_LOCALE` / `RECORDING_LOCALE_FALLBACKS` |
| Prompt / TTS hooks | `ENABLE_PROMPT_TTS_HOOKS` |
| Silent-prompt fallback | `ENABLE_SILENT_PROMPT_FALLBACK` (default `false`) |
| Verbose logging | `ENABLE_VERBOSE_LOGGING` |
| Defensive environment checks | `ENABLE_CONSERVATIVE_ENVIRONMENT_MODE` |

The safe profile does **not** disable the module's main purpose.

---

## Conservative / defensive environment mode

When `ENABLE_CONSERVATIVE_ENVIRONMENT_MODE = true` (default), the module checks for signs of a
spoofed or inconsistent environment inside the Dialer process.  A lightweight locale check runs at
load time; the full check (`detectRiskySpoofedEnvironment`) runs once on the first
`Activity.onResume` when a `Context` is available.  All checks are wrapped in
`try/catch(Throwable)` and never crash Dialer.

Checks performed by `detectRiskySpoofedEnvironment`:
- null / empty default locale;
- `Build.MODEL` null or empty (only on Android 16+, gated by `ENABLE_ANDROID16_SAFE_PROFILE`);
- `getNetworkOperator()` returning `00101`;
- `getNetworkOperatorName()` returning `TestNetwork` / `Android`;
- `getSimState()` reporting absent SIM while active subscriptions exist.

If a risky environment is detected:
- `conservativeMode` is set to `true`;
- a logcat line is emitted: `conservative mode active due to inconsistent environment: [reasons]`;
- `dispatchOnInit` override is disabled (the most aggressive pre-original change);
- all other eligibility overrides remain enabled.

> **Warning for XPL-EX / privacy module users:** if XPL-EX is configured to spoof audio,
> telephony, parcel, locale, Build fields, or SIM state for Dialer / Phone / Google app, the
> module may enter conservative mode.  This will suppress the `dispatchOnInit` override but will
> not disable the core eligibility hooks.  Do not scope privacy modules to `com.google.android.dialer`
> or `com.android.phone` while debugging call-recording crashes.

---

## Compatibility mode switch

Edit `Init.java` and change the `MODE` constant before building:

```java
private static final RecordingCompatibilityMode MODE =
        RecordingCompatibilityMode.SAFE_GLOBAL_ENABLE;   // default
        // RecordingCompatibilityMode.FORCE_LEGACY_US;   // always use Locale.US
        // RecordingCompatibilityMode.OBSERVE_ONLY;      // log only, no overrides
```

### `SAFE_GLOBAL_ENABLE` (default)
- Side-effect-preserving after-hooks.
- Final availability booleans forced `true`.
- Original Dialer locale preserved if valid; fallback used if null/unsupported.
- Prompt/TTS hooks staged.

### `FORCE_LEGACY_US`
- Same as `SAFE_GLOBAL_ENABLE` but the final locale is always `Locale.US`.
- Use if Google Dialer on your build still requires US locale to show recording.

### `OBSERVE_ONLY`
- Hooks are installed but results are never modified.
- All original values are logged at `DEBUG` level.
- Use when investigating a new Dialer version without affecting behaviour.

---

## Recommended LSPosed / Vector-lspd scope

Scope **only** `com.google.android.dialer`.

---

## Manual test plan

1. Install the forked module APK.
2. In LSPosed / Vector, set scope to `com.google.android.dialer` only.
3. Reboot.
4. Clear logs:
   ```sh
   su -c 'logcat -b main -c; logcat -b system -c; logcat -b crash -c; logcat -b events -c'
   ```
5. Start log watch:
   ```sh
   adb logcat -v epoch -s CallRecording AndroidRuntime Telecom CallAudioModeStateMachine AudioManager
   ```
6. Open Google Dialer once and observe:
   - Hook targets are logged with `declaringClass=` / `name=` / `return=` / `params=`.
   - `hook done` is logged.
   - No `FATAL EXCEPTION`.
7. Verify **Call Assist → Call Recording** setting is visible.
8. Make an outgoing call; answer it.
   - Confirm recording can be started.
   - Confirm locale and boolean hooks log `original=` and `final=`.
9. Receive an incoming call; answer it.  Repeat recording test.
10. Trigger a missed call.  Confirm no crash.
11. Verify:
    - No `FATAL EXCEPTION IN SYSTEM PROCESS`.
    - No `DeadSystemException`.
    - No crash in `CallAudioModeStateMachine`.
    - Recording feature remains available.
