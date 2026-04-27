# Compatibility Notes — Pixel 9a / Android 16

This document describes the safe-profile behaviour added for Pixel 9a (codename `tegu`) running
Android 16, and explains the design rationale for every hook change in this fork.

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
available on Pixel 9a (A-series), and this module makes no attempt to enable it.

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

## Pixel 9a / Android 16 safe profile

When the module detects `Build.DEVICE == "tegu"` and `SDK_INT >= 36`, the safe profile is active.

Logcat marker:

```
CallRecording: Pixel 9a Android 16 safe profile active
```

Under the safe profile:
- boolean availability overrides remain enabled as after-hooks;
- country / geofence final result still forced `true`;
- locale fallback is deterministic (not unconditional pre-original US);
- prompt/TTS hooks run in staged mode;
- `ENABLE_SILENT_PROMPT_FALLBACK` is `false` unless explicitly enabled;
- extra logging is emitted;
- defensive environment checks run at load time.

The safe profile does **not** disable the module's main purpose.

---

## Conservative / defensive environment mode

When `ENABLE_CONSERVATIVE_ENVIRONMENT_MODE = true` (default), the module checks for signs of a
spoofed or inconsistent environment inside the Dialer process at load time and optionally at
activity resume.  All checks are wrapped in `try/catch(Throwable)` and never crash Dialer.

Checks performed:
- null / empty default locale;
- `Build.MODEL` empty on a known-Pixel device;
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
