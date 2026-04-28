# Call Recording (Google Dialer module)

A module to enable the **Record** button in **Google Dialer**.

This APK is an Xposed/libxposed (API 100 + 101) module. It is scoped to `com.google.android.dialer` only. Designed for Android 16 Pixel devices (tested on tegu).

## Table of contents
- [What this APK does](#what-this-apk-does)
- [Silent/covert behavior (important)](#silentcovert-behavior-important)
- [Requirements](#requirements)
- [Install and setup](#install-and-setup)
- [How to use](#how-to-use)
- [Verify it is working](#verify-it-is-working)
- [Troubleshooting](#troubleshooting)
- [More technical docs](#more-technical-docs)

## What this APK does
- Enables Google Dialer call-recording eligibility checks.
- Uses compatibility hooks for current Dialer behavior.
- Includes conservative behavior for Android 16+ environments.
- Uses Dialer-only scope (does not require system-wide scope).

## Silent/covert behavior (important)
Yes. Silent/covert recording behavior is implemented.

Current module logic includes silent disclosure handling in call-recording context:
- Mutes disclosure player volume when applicable.
- Replaces disclosure prompt audio assets with silent audio in fallback paths.
- Uses TextToSpeech silent fallback in related prompt flows.

If your local law requires an audible disclosure, **do not use this behavior**.

For technical validation steps, see [`scripts/verify_silent_mode.sh`](./scripts/verify_silent_mode.sh).

## Requirements
1. Android device with root and an active Xposed-compatible framework (for example, Vector/LSPosed-compatible environments).
2. Google Dialer package: `com.google.android.dialer`.
3. Module scope set to **only** `com.google.android.dialer`.

## Install and setup
1. Install the APK.
2. Enable the module in your Xposed manager.
3. Set module scope to: `com.google.android.dialer`.
4. Reboot device (recommended after first enable) or force stop Google Dialer.
6. Open Google Dialer.

### Scope rule (important)
Use only:
- `com.google.android.dialer`

Do not add scope for unrelated packages (for example `android`, `system_server`, `com.google.android.gms`).

## How to use
1. Start or answer a phone call in Google Dialer.
2. Tap **Record** in the in-call UI when available.
3. Follow local consent/compliance requirements before recording.

## Verify it is working
- In-call UI shows a **Record** option.
- Recording starts without Dialer crash.
- If needed, verify module/scope/logs with the diagnostic steps in [COMPATIBILITY.md](./COMPATIBILITY.md).

> **Safety and legal notice**: Call-recording and disclosure laws differ by country/state. You are responsible for legal compliance and consent requirements.

## Troubleshooting
- Record button not shown:
  - Confirm module is enabled.
  - Confirm scope is exactly `com.google.android.dialer`.
  - Confirm "Call Recording" is enabled in Dialer settings (Call Assist > Call Recording).
  - Reboot and test again.
- Framework/module status checks:
  - Use the command examples in [COMPATIBILITY.md](./COMPATIBILITY.md).
- Silent mode validation (advanced):
  - Use [`scripts/verify_silent_mode.sh`](./scripts/verify_silent_mode.sh).

## More technical docs
- Compatibility details and CLI diagnostics: [COMPATIBILITY.md](./COMPATIBILITY.md)
- Silent-mode verification script: [`scripts/verify_silent_mode.sh`](./scripts/verify_silent_mode.sh)
