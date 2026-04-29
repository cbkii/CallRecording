package io.github.vvb2060.callrecording.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.vvb2060.callrecording.BuildConfig;

public class Init implements IXposedHookLoadPackage {
    private static final String TAG = "CallRecording";

    // -------------------------------------------------------------------------
    // Configuration constants – change these at compile time to switch behaviour
    // -------------------------------------------------------------------------

    /**
     * Recording compatibility mode.
     *
     * <p>SAFE_GLOBAL_ENABLE – side-effect-preserving after-hooks; final availability forced true;
     *   original locale preserved if valid; fallback locale used when null/unsupported;
     *   prompt/TTS hooks staged.
     *
     * <p>FORCE_LEGACY_US – same as SAFE_GLOBAL_ENABLE but final locale always Locale.US;
     *   use when Google Dialer still requires US locale to show recording.
     *
     * <p>OBSERVE_ONLY – install hooks but do not modify results; logs original values;
     *   useful for debugging new Dialer versions.
     */
    private enum RecordingCompatibilityMode {
        SAFE_GLOBAL_ENABLE,
        FORCE_LEGACY_US,
        OBSERVE_ONLY
    }

    private static final RecordingCompatibilityMode MODE = RecordingCompatibilityMode.SAFE_GLOBAL_ENABLE;

    /** Enable prompt/TTS hooks (staged, after-hook first). */
    private static final boolean ENABLE_PROMPT_TTS_HOOKS = true;

    /**
     * When true, write built-in silent WAV when TTS synthesize fails.
     * Disabled by default to avoid creating inconsistent prompt/audio state.
     */
    private static final boolean ENABLE_SILENT_PROMPT_FALLBACK = true;

    /** Enable extra debug logging. */
    private static final boolean ENABLE_VERBOSE_LOGGING = BuildConfig.DEBUG;

    /** Enable Android 16+ safe-profile behaviour when the runtime API level is detected. */
    private static final boolean ENABLE_ANDROID16_SAFE_PROFILE = true;

    /** Enable defensive environment inconsistency detection. */
    private static final boolean ENABLE_CONSERVATIVE_ENVIRONMENT_MODE = true;

    /**
     * When true, always return Locale.US from the locale hook regardless of the
     * original result. Overrides SAFE_GLOBAL_ENABLE locale logic.
     * Only effective in FORCE_LEGACY_US mode or when set explicitly.
     */
    private static final boolean FORCE_US_RECORDING_LOCALE =
            (MODE == RecordingCompatibilityMode.FORCE_LEGACY_US);

    /** Locale used when FORCE_US_RECORDING_LOCALE is true. */
    private static final Locale FORCED_RECORDING_LOCALE = Locale.US;

    /** en-AU locale constant used by TTS compatibility checks. */
    private static final Locale EN_AU = Locale.forLanguageTag("en-AU");

    /**
     * Countries where Dialer call recording is considered unsupported for locale selection.
     * When the reported country code is in this list, we fall back to a supported locale.
     */
    private static final java.util.Set<String> UNSUPPORTED_RECORDING_COUNTRY_CODES =
            java.util.Set.of(
                    "AE", "AT", "AZ", "BD", "BE", "BG", "BH", "CH", "CI", "CM", "CO",
                    "CY", "CZ", "DE", "DK", "EE", "EG", "ES", "FI", "FR", "GH", "GR", "HR",
                    "HU", "ID", "IE", "IQ", "IR", "IT", "JO", "KW", "LB", "LT", "LU", "LV",
                    "MA", "MT", "MY", "MZ", "NG", "NL", "NP", "OM", "PA", "PH", "PK", "PL",
                    "PT", "PY", "QA", "RO", "RU", "RW", "SA", "SE", "SI", "SK", "SN", "TN",
                    "TR", "UA", "VN", "YE", "ZA", "ZW");

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    private static volatile boolean conservativeMode = false;
    private static final List<String> conservativeReasons = new ArrayList<>();

    // Once-only guard for the deferred conservative environment check run from onResume
    private static volatile boolean ranEnvCheck = false;

    // Once-only warning flags to avoid log spam
    private static volatile boolean warnedMissingPermission = false;
    private static volatile boolean warnedUnsupportedVersion = false;

    // Guard against duplicate hook installation (can happen when both legacy and modern
    // Xposed entrypoints are active simultaneously).
    private static final AtomicBoolean hooksInstalled = new AtomicBoolean(false);

    // Tracks whether a synthesizeToFile call was recently intercepted so that speak() hooks
    // can suppress TTS callbacks that arrive asynchronously (after the recording-context
    // class names have left the call stack).
    private static volatile boolean inCallRecordingSession = false;

    // Runnable used to clear the session flag after a grace period.
    private static final Runnable clearCallRecordingSession = () -> {
        inCallRecordingSession = false;
        Log.d(TAG, "inCallRecordingSession: cleared");
    };

    // How long to keep the session flag set after the last synthesizeToFile interception.
    // 15 seconds is a comfortable upper bound for the TTS→onDone→play sequence in Dialer:
    // the entire disclosure interaction (synthesis + playback) typically completes within
    // 1–3 s, so 15 s provides ample async margin without risk of suppressing unrelated TTS.
    private static final long RECORDING_SESSION_TIMEOUT_MS = 15_000;

    // Cached main-thread Handler. Lazily initialised; null until first use.
    private static volatile Handler mainHandler;

    // -------------------------------------------------------------------------
    // Silent WAV fallback (1 channel, 16-bit PCM, empty audio)
    // -------------------------------------------------------------------------

    private static final byte[] wav = {
            82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86,
            69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0,
            1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2,
            0, 16, 0, 100, 97, 116, 97, 0, 0, 0, 0};

    private static volatile byte[] silentPromptWav;
    // Cached field looked up by type across the TTS class hierarchy (resilient to field renaming).
    private static volatile Field utteranceListenerField;
    // Cached temp file used to back AssetFileDescriptor responses for silent audio.
    private static volatile File silentTempFile;

    // -------------------------------------------------------------------------
    // Device / environment helpers
    // -------------------------------------------------------------------------

    private static boolean isAndroid16() {
        return Build.VERSION.SDK_INT >= 36;
    }

    /**
     * Best-effort check for a spoofed/inconsistent environment inside the Dialer process.
     * All checks are wrapped in try/catch; failures are logged and treated as inconclusive.
     */
    private static boolean detectRiskySpoofedEnvironment(Context context) {
        boolean risky = false;

        // Check default locale
        try {
            Locale def = Locale.getDefault();
            if (def == null || def.getLanguage() == null || def.getLanguage().isEmpty()) {
                conservativeReasons.add("null/empty default locale");
                risky = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "riskyEnv: locale check failed", t);
        }

        // Check Build fields when the Android 16 safe profile is enabled
        try {
            if (ENABLE_ANDROID16_SAFE_PROFILE && isAndroid16()) {
                String model = Build.MODEL;
                if (model == null || model.isEmpty()) {
                    conservativeReasons.add("empty Build.MODEL");
                    risky = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "riskyEnv: Build check failed", t);
        }

        // Check TelephonyManager for obvious spoofed/test values
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                try {
                    String operator = tm.getNetworkOperator();
                    if ("00101".equals(operator)) {
                        conservativeReasons.add("test network operator 00101");
                        risky = true;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "riskyEnv: getNetworkOperator failed", t);
                }
                try {
                    String operatorName = tm.getNetworkOperatorName();
                    if ("TestNetwork".equalsIgnoreCase(operatorName)
                            || "Android".equalsIgnoreCase(operatorName)) {
                        conservativeReasons.add("test network operator name: " + operatorName);
                        risky = true;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "riskyEnv: getNetworkOperatorName failed", t);
                }
                try {
                    int simState = tm.getSimState();
                    if (simState == TelephonyManager.SIM_STATE_ABSENT) {
                        // Only flag if subscriptions claim to be active
                        try {
                            SubscriptionManager sm = (SubscriptionManager)
                                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                            if (sm != null) {
                                List<?> subs = sm.getActiveSubscriptionInfoList();
                                if (subs != null && !subs.isEmpty()) {
                                    conservativeReasons.add("SIM absent but active subscriptions exist");
                                    risky = true;
                                }
                            }
                        } catch (Throwable t2) {
                            Log.w(TAG, "riskyEnv: SubscriptionManager check failed", t2);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "riskyEnv: getSimState failed", t);
                }
                try {
                    String simCountry = tm.getSimCountryIso();
                    String netCountry = tm.getNetworkCountryIso();
                    if (simCountry != null && netCountry != null
                            && !simCountry.isEmpty() && !netCountry.isEmpty()
                            && !simCountry.equalsIgnoreCase(netCountry)) {
                        // Only a soft warning; not enough to flag as risky alone
                        Log.w(TAG, "riskyEnv: simCountry=" + simCountry
                                + " netCountry=" + netCountry + " mismatch (soft)");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "riskyEnv: country ISO check failed", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "riskyEnv: TelephonyManager block failed", t);
        }

        return risky;
    }

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------

    private static void logHookTarget(String label, java.lang.reflect.Member method) {
        if (method instanceof Method) {
            Method m = (Method) method;
            Log.w(TAG, label
                    + " declaringClass=" + m.getDeclaringClass().getName()
                    + " name=" + m.getName()
                    + " return=" + m.getReturnType().getName()
                    + " params=" + Arrays.toString(m.getParameterTypes()));
        } else {
            Log.w(TAG, label + " method=" + method);
        }
    }

    // -------------------------------------------------------------------------
    // Locale fallback
    // -------------------------------------------------------------------------

    private static Locale safeRecordingFallbackLocale() {
        if (FORCE_US_RECORDING_LOCALE) {
            return FORCED_RECORDING_LOCALE;
        }
        // Compatibility fallback for null/unsupported country/locale paths.
        return Locale.US;
    }

    private static boolean isUnsupportedRecordingCountryCode(String countryCode) {
        if (countryCode == null) return true;
        String normalized = countryCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        return UNSUPPORTED_RECORDING_COUNTRY_CODES.contains(normalized);
    }

    private static String extractCountryCodeArg(Object[] args) {
        if (args == null || args.length < 2) return null;
        Object arg = args[1];
        return arg instanceof String ? (String) arg : null;
    }

    private static byte[] getSilentPromptWavBytes() {
        if (silentPromptWav != null) {
            return silentPromptWav;
        }
        synchronized (Init.class) {
            if (silentPromptWav != null) {
                return silentPromptWav;
            }
            String[] candidates = new String[]{
                    "assets/silent_16k.wav",
                    "/assets/silent_16k.wav",
                    "silent_16k.wav"
            };
            for (String name : candidates) {
                try (InputStream in = Init.class.getClassLoader().getResourceAsStream(name)) {
                    if (in == null) continue;
                    byte[] data = in.readAllBytes();
                    if (data.length > 0) {
                        silentPromptWav = data;
                        Log.w(TAG, "Loaded silent prompt wav asset: " + name
                                + " bytes=" + data.length);
                        return silentPromptWav;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed reading silent wav asset: " + name, t);
                }
            }
            silentPromptWav = wav;
            Log.w(TAG, "Using built-in silent wav fallback bytes=" + wav.length);
            return silentPromptWav;
        }
    }

    /**
     * Returns a File pre-written with silent WAV bytes, creating and caching it on first call.
     * Used to back {@link AssetFileDescriptor} responses for silent audio interception.
     * The file persists for the lifetime of the Dialer process; no explicit deletion is needed.
     */
    private static File getSilentTempFile() {
        File f = silentTempFile;
        if (f != null && f.exists()) return f;
        synchronized (Init.class) {
            f = silentTempFile;
            if (f != null && f.exists()) return f;
            try {
                f = File.createTempFile("silent_disclosure_", ".wav");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(getSilentPromptWavBytes());
                }
                silentTempFile = f;
                return f;
            } catch (IOException e) {
                Log.e(TAG, "getSilentTempFile: failed to create temp file", e);
                return null;
            }
        }
    }

    /**
     * Returns true when the asset name looks like a call-recording disclosure audio file.
     * The filename keyword filter is the primary guard; no stack-trace inspection needed.
     */
    private static boolean isDisclosureAudioAsset(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hasKeyword = lower.contains("record")
                || lower.contains("disclosure")
                || lower.contains("announcement");
        boolean hasAudioExt = lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac");
        return hasKeyword && hasAudioExt;
    }

    /**
     * Finds the {@link UtteranceProgressListener} field on the TTS class by type, walking the
     * class hierarchy. Searching by type is resilient to field renaming across Android versions.
     */
    private static Field findUtteranceListenerField(Class<?> ttsClass) {
        Class<?> c = ttsClass;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                // isAssignableFrom(f.getType()) returns true when f.getType() IS
                // UtteranceProgressListener or a subclass of it — the direction we want.
                if (UtteranceProgressListener.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable t) {
                        Log.w(TAG, "findUtteranceListenerField: setAccessible failed for "
                                + f.getName(), t);
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Retrieves the {@link UtteranceProgressListener} from a live TTS instance.
     * Caches the field reference for efficiency; falls back gracefully on reflection failure.
     */
    private static UtteranceProgressListener getUtteranceProgressListener(Object ttsInstance) {
        if (ttsInstance == null) return null;
        Field f = utteranceListenerField;
        if (f == null) {
            f = findUtteranceListenerField(ttsInstance.getClass());
            if (f == null) {
                Log.w(TAG, "getUtteranceProgressListener: field not found on "
                        + ttsInstance.getClass().getName());
                return null;
            }
            utteranceListenerField = f;
        }
        try {
            return (UtteranceProgressListener) f.get(ttsInstance);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "getUtteranceProgressListener: reflection failed", e);
            return null;
        }
    }

    /**
     * Fires the correct TTS lifecycle sequence — {@code onStart} followed by {@code onDone} —
     * both posted to the main thread so callers always see a well-ordered callback pair
     * regardless of which thread triggered the hook.
     */
    private static void fireTtsCallbacks(Object ttsInstance, String utteranceId) {
        if (utteranceId == null) return;
        UtteranceProgressListener listener = getUtteranceProgressListener(ttsInstance);
        if (listener == null) return;
        Handler h = getMainHandler();
        if (h == null) {
            Log.w(TAG, "fireTtsCallbacks: main looper unavailable, cannot dispatch callbacks");
            return;
        }
        h.post(() -> {
            try {
                listener.onStart(utteranceId);
            } catch (Throwable t) {
                Log.w(TAG, "fireTtsCallbacks: onStart failed", t);
            }
            // Post onDone after onStart so ordering is guaranteed even when the listener
            // inspects state synchronously inside onStart.
            h.post(() -> {
                try {
                    listener.onDone(utteranceId);
                } catch (Throwable t) {
                    Log.w(TAG, "fireTtsCallbacks: onDone failed", t);
                }
            });
        });
    }

    /**
     * Returns a cached {@link Handler} bound to the main Looper, or {@code null} if the main
     * Looper is not yet available (e.g. very early in process startup).
     */
    private static Handler getMainHandler() {
        Handler h = mainHandler;
        if (h != null) return h;
        Looper looper = Looper.getMainLooper();
        if (looper == null) return null;
        synchronized (Init.class) {
            // Re-check inside the lock; another thread may have initialised it between the
            // first null check above and the lock acquisition.
            if (mainHandler == null) {
                mainHandler = new Handler(looper);
            }
            return mainHandler;
        }
    }

    /**
     * Marks a call-recording TTS session as active and schedules automatic expiry.
     * Used to extend the suppression window for {@code speak()} calls that arrive
     * asynchronously after the recording-context class names have left the call stack.
     */
    private static void markCallRecordingSessionActive() {
        inCallRecordingSession = true;
        Handler h = getMainHandler();
        if (h != null) {
            h.removeCallbacks(clearCallRecordingSession);
            h.postDelayed(clearCallRecordingSession, RECORDING_SESSION_TIMEOUT_MS);
        }
    }

    private static boolean inCallRecordingContext() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            if (cls == null) continue;
            String lower = cls.toLowerCase(Locale.ROOT);
            if (lower.contains("callrecord")
                    || lower.contains("callassist")
                    || lower.contains("callcomposer")
                    || lower.contains("disclosure")
                    || lower.contains("incall")) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Boolean after-hook helpers
    // -------------------------------------------------------------------------

    private static boolean shouldForceAvailabilityDespiteOriginalThrowable(String label,
            Throwable throwable) {
        // Never swallow serious JVM errors or security exceptions — let those propagate.
        if (throwable instanceof Error || throwable instanceof SecurityException) {
            return false;
        }
        // Only force recovery for the known call-recording eligibility hooks.
        // Never swallow arbitrary Dialer crashes outside this path.
        return "canRecordCall".equals(label)
                || "withinCrosbyGeoFence".equals(label)
                || "isCallRecordingCountry".equals(label);
    }

    /**
     * Installs a side-effect-preserving after-hook that forces the boolean return to true.
     * The original method runs first so any internal cache/state initialisation is preserved.
     */
    private static void hookBooleanReturnTrue(String label, java.lang.reflect.Member method) {
        logHookTarget(label, method);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (MODE == RecordingCompatibilityMode.OBSERVE_ONLY) {
                    Log.d(TAG, label + ": OBSERVE_ONLY original=" + param.getResult());
                    return;
                }
                if (param.hasThrowable()) {
                    Log.w(TAG, label + ": original threw", param.getThrowable());
                    if (shouldForceAvailabilityDespiteOriginalThrowable(label,
                            param.getThrowable())) {
                        param.setThrowable(null);
                        param.setResult(Boolean.TRUE);
                        Log.w(TAG, label + ": forced true after original throwable");
                    }
                    return;
                }
                Object original = param.getResult();
                if (ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, label + ": original=" + original + " -> true");
                }
                if (original instanceof Boolean) {
                    param.setResult(Boolean.TRUE);
                } else {
                    Log.w(TAG, label + ": unexpected non-boolean result=" + original
                            + ", forcing true");
                    param.setResult(Boolean.TRUE);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // DexHelper hooks
    // -------------------------------------------------------------------------

    private static void hookCanRecordCall(DexHelper dex) {
        var canRecordCall = Arrays.stream(
                        dex.findMethodUsingString("canRecordCall",
                                false,
                                -1,
                                (short) 0,
                                "Z",
                                -1,
                                null,
                                null,
                                null,
                                true))
                .mapToObj(dex::decodeMethodIndex)
                .filter(Objects::nonNull)
                .findFirst();
        if (canRecordCall.isPresent()) {
            hookBooleanReturnTrue("canRecordCall", canRecordCall.get());
        } else {
            Log.e(TAG, "canRecordCall method not found");
        }
    }

    private static void hookWithinCrosbyGeoFence(DexHelper dex) {
        var withinCrosbyGeoFence = Arrays.stream(
                        dex.findMethodUsingString("withinCrosbyGeoFence",
                                false,
                                -1,
                                (short) 0,
                                "Z",
                                -1,
                                null,
                                null,
                                null,
                                true))
                .mapToObj(dex::decodeMethodIndex)
                .filter(Objects::nonNull)
                .findFirst();
        if (withinCrosbyGeoFence.isPresent()) {
            hookBooleanReturnTrue("withinCrosbyGeoFence", withinCrosbyGeoFence.get());
        } else {
            Log.w(TAG, "withinCrosbyGeoFence method not found");
        }
    }

    private static void hookGetSupportedLocaleFromCountryCode(DexHelper dex) {
        var localeId = dex.encodeClassIndex(Locale.class);
        var mapId = dex.encodeClassIndex(Map.class);
        var stringId = dex.encodeClassIndex(String.class);
        var getSupportedLocaleFromCountryCode = Arrays.stream(
                        dex.findMethodUsingString("getSupportedLocaleFromCountryCode",
                                false,
                                localeId,
                                (short) 2,
                                null,
                                -1,
                                new long[]{mapId, stringId},
                                null,
                                null,
                                true))
                .mapToObj(dex::decodeMethodIndex)
                .filter(Objects::nonNull)
                .findFirst();
        if (getSupportedLocaleFromCountryCode.isPresent()) {
            var method = getSupportedLocaleFromCountryCode.get();
            logHookTarget("getSupportedLocaleFromCountryCode", method);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Locale fallback = safeRecordingFallbackLocale();

                    if (MODE == RecordingCompatibilityMode.OBSERVE_ONLY) {
                        Log.d(TAG, "getSupportedLocaleFromCountryCode: OBSERVE_ONLY"
                                + " args=" + Arrays.toString(param.args)
                                + " original=" + param.getResult());
                        return;
                    }

                    if (param.hasThrowable()) {
                        Log.w(TAG, "getSupportedLocaleFromCountryCode original threw; fallback="
                                + fallback, param.getThrowable());
                        param.setThrowable(null);
                        param.setResult(fallback);
                        Log.w(TAG, "getSupportedLocaleFromCountryCode: locale original=<threw>"
                                + " final=" + fallback);
                        return;
                    }

                    Object original = param.getResult();
                    String countryCode = extractCountryCodeArg(param.args);
                    boolean unsupportedCountry = isUnsupportedRecordingCountryCode(countryCode);
                    if (ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "getSupportedLocaleFromCountryCode args="
                                + Arrays.toString(param.args) + " original=" + original
                                + " unsupportedCountry=" + unsupportedCountry);
                    }

                    if (FORCE_US_RECORDING_LOCALE) {
                        // Always override to the forced locale but still ran original above
                        Log.d(TAG, "getSupportedLocaleFromCountryCode: locale original="
                                + original + " final=" + fallback + " (forced)");
                        param.setResult(fallback);
                        return;
                    }

                    if (!unsupportedCountry && original instanceof Locale) {
                        // Preserve Dialer's own successful locale only when country is supported.
                        Log.d(TAG, "getSupportedLocaleFromCountryCode: country=" + countryCode
                                + " locale original=" + original + " final=" + original
                                + " (preserved)");
                        return;
                    }

                    Log.w(TAG, "getSupportedLocaleFromCountryCode: country=" + countryCode
                            + " locale original=" + original
                            + " (null/unsupported country) final=" + fallback);
                    param.setResult(fallback);
                }
            });
        } else {
            Log.e(TAG, "getSupportedLocaleFromCountryCode method not found");
        }
    }

    private static void hookIsCallRecordingCountry(DexHelper dex) {
        var isCallRecordingCountry = Arrays.stream(
                        dex.findMethodUsingString("isCallRecordingCountry",
                                false,
                                -1,
                                (short) 0,
                                "Z",
                                -1,
                                null,
                                null,
                                null,
                                true))
                .mapToObj(dex::decodeMethodIndex)
                .filter(Objects::nonNull)
                .findFirst();
        if (isCallRecordingCountry.isPresent()) {
            hookBooleanReturnTrue("isCallRecordingCountry", isCallRecordingCountry.get());
        } else {
            Log.e(TAG, "isCallRecordingCountry method not found");
        }
    }

    // -------------------------------------------------------------------------
    // TTS / prompt hooks (staged, side-effect preserving)
    // -------------------------------------------------------------------------

    private static void hookIsLanguageAvailable() {
        try {
            Method isLanguageAvailable = TextToSpeech.class.getDeclaredMethod(
                    "isLanguageAvailable", Locale.class);
            XposedBridge.hookMethod(isLanguageAvailable, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "isLanguageAvailable: " + Arrays.toString(param.args)
                                + " -> " + param.getResult());
                    }
                    if (!ENABLE_PROMPT_TTS_HOOKS) return;
                    if (param.hasThrowable()) return;

                    Object result = param.getResult();
                    if (!(result instanceof Integer)) return;
                    if ((int) result >= TextToSpeech.LANG_AVAILABLE) return;

                    // Only claim language available for the selected recording locale,
                    // not every locale unconditionally.
                    Locale queried = (Locale) param.args[0];
                    Locale fallback = safeRecordingFallbackLocale();
                    boolean isRecordingLocale = queried != null
                            && (queried.equals(fallback)
                            || queried.equals(Locale.US)
                            || queried.equals(Locale.ENGLISH)
                            || queried.equals(EN_AU));
                    if (isRecordingLocale) {
                        param.setResult(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE);
                        Log.w(TAG, "isLanguageAvailable: " + queried
                                + " not available, overriding for recording locale");
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "isLanguageAvailable method not found", e);
        }
    }

    /**
     * Hooks {@code TextToSpeech.synthesizeToFile()} with a before-hook that pre-writes a silent
     * WAV to the target file and skips the original TTS call entirely.
     *
     * <p>Using a before-hook rather than checking the return value of the original call (which
     * only reflects queue success, not synthesis success) ensures the silent file is always
     * present at the expected path and that no real audio is ever generated.
     *
     * <p>Proper {@code onStart}/{@code onDone} lifecycle callbacks are posted to the main thread
     * so that Dialer state machines that depend on ordered TTS callbacks function correctly.
     *
     * <p>Also marks the call-recording session active so that any subsequent {@code speak()}
     * calls within the session window are also suppressed, even from async threads that no
     * longer carry the recording-context class names in their stack traces.
     */
    private static void hookSynthesizeToFile() {
        try {
            Method synthesizeToFile = TextToSpeech.class.getDeclaredMethod("synthesizeToFile",
                    CharSequence.class, Bundle.class, File.class, String.class);
            XposedBridge.hookMethod(synthesizeToFile, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_PROMPT_TTS_HOOKS || !ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (MODE == RecordingCompatibilityMode.OBSERVE_ONLY) {
                        if (ENABLE_VERBOSE_LOGGING) {
                            Log.d(TAG, "synthesizeToFile: OBSERVE_ONLY, not intercepting; args="
                                    + Arrays.toString(param.args));
                        }
                        return;
                    }
                    if (ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "synthesizeToFile: args=" + Arrays.toString(param.args));
                    }
                    File file = (File) param.args[2];
                    if (file == null) {
                        Log.w(TAG, "synthesizeToFile: file argument is null, skipping silent bypass");
                        return;
                    }
                    // Ensure the target directory exists so FileOutputStream does not throw.
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        if (!parent.mkdirs()) {
                            Log.w(TAG, "synthesizeToFile: mkdirs failed for " + parent
                                    + " — FileOutputStream may still succeed if dir was created"
                                    + " concurrently; will attempt write anyway");
                        }
                    }
                    String utteranceId = (String) param.args[3];
                    // Mark the recording session active so hookSpeak can suppress async callbacks.
                    markCallRecordingSessionActive();
                    // Pre-write the silent WAV before TTS can generate any real audio,
                    // then skip the original call so no audio synthesis occurs at all.
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(getSilentPromptWavBytes());
                    } catch (IOException e) {
                        Log.e(TAG, "synthesizeToFile: cannot write silent wav to " + file, e);
                        return; // Let original proceed if we cannot write the file.
                    }
                    param.setResult(TextToSpeech.SUCCESS);
                    Log.w(TAG, "synthesizeToFile: silent bypass engaged, utteranceId=" + utteranceId);
                    // Fire onStart then onDone on the main thread to reproduce the expected
                    // TTS lifecycle ordering without depending on private API.
                    fireTtsCallbacks(param.thisObject, utteranceId);
                }
            });
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "synthesizeToFile method not found", e);
        }
    }

    /**
     * Hooks {@code TextToSpeech.speak()} with a before-hook that bypasses real TTS when a
     * call-recording disclosure context is detected.
     *
     * <p>Context is determined by two complementary methods:
     * <ol>
     *   <li>{@link #inCallRecordingContext()} — synchronous stack-trace check; reliable when
     *       {@code speak()} is called directly from recording code.</li>
     *   <li>{@link #inCallRecordingSession} — flag set by {@link #hookSynthesizeToFile}; remains
     *       active for {@value #RECORDING_SESSION_TIMEOUT_MS} ms, covering async callbacks that
     *       have lost the recording-context class names from their stack traces.</li>
     * </ol>
     *
     * <p>Setting the result in the before-hook causes Xposed to skip the original method,
     * so no real TTS queue entry is created. Proper {@code onStart}/{@code onDone} callbacks
     * are then posted to the main thread so Dialer state machines advance normally.
     */
    private static void hookSpeak() {
        try {
            Method speak = TextToSpeech.class.getDeclaredMethod(
                    "speak", CharSequence.class, int.class, Bundle.class, String.class);
            XposedBridge.hookMethod(speak, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_PROMPT_TTS_HOOKS || !ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (MODE == RecordingCompatibilityMode.OBSERVE_ONLY) {
                        if (ENABLE_VERBOSE_LOGGING) {
                            Log.d(TAG, "speak: OBSERVE_ONLY, not intercepting");
                        }
                        return;
                    }
                    // Accept suppression if either the live stack trace contains recording class
                    // names OR the session flag was set by a recent synthesizeToFile interception.
                    if (!inCallRecordingSession && !inCallRecordingContext()) return;
                    String utteranceId = (String) param.args[3];
                    // Skip the original speak call so no real TTS queue entry is created.
                    param.setResult(TextToSpeech.SUCCESS);
                    Log.w(TAG, "speak: silent bypass engaged"
                            + " session=" + inCallRecordingSession
                            + " utteranceId=" + utteranceId);
                    // Fire onStart then onDone on the main thread to maintain correct
                    // callback ordering without re-entrancy issues.
                    fireTtsCallbacks(param.thisObject, utteranceId);
                }
            });
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "speak(CharSequence,int,Bundle,String) not found", e);
        }
    }

    private static void hookCallRecordingDisclosure(ClassLoader classLoader) {
        try {
            Class<?> disclosureClass = XposedHelpers.findClass(
                    "com.google.android.dialer.callcomposer.CallRecordingDisclosure",
                    classLoader);
            XposedBridge.hookAllConstructors(disclosureClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    setDisclosurePlayerSilent(param.thisObject);
                }
            });
            Log.w(TAG, "CallRecordingDisclosure constructor hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "CallRecordingDisclosure constructor hook unavailable", t);
        }
    }

    private static void setDisclosurePlayerSilent(Object disclosure) {
        if (!ENABLE_SILENT_PROMPT_FALLBACK || disclosure == null) return;
        // Try well-known field names first (fast path).
        for (String fieldName : new String[]{"mMediaPlayer", "mediaPlayer", "player"}) {
            try {
                Object player = XposedHelpers.getObjectField(disclosure, fieldName);
                if (player != null) {
                    XposedHelpers.callMethod(player, "setVolume", 0f, 0f);
                    Log.w(TAG, "Recording: disclosure player muted via field: " + fieldName);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // Fallback: walk all declared fields in the class hierarchy and mute any
        // android.media.MediaPlayer instance found.  Using type assignability rather than
        // string comparison is robust across ClassLoader contexts.
        Class<?> c = disclosure.getClass();
        while (c != null && !c.equals(Object.class)) {
            for (Field f : c.getDeclaredFields()) {
                if (!MediaPlayer.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object player = f.get(disclosure);
                    if (player != null) {
                        XposedHelpers.callMethod(player, "setVolume", 0f, 0f);
                        Log.w(TAG, "Recording: disclosure player muted via scanned field: "
                                + f.getName());
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        // Player not yet assigned at construction time — the AssetManager hooks will
        // ensure any audio file opened later contains only silence.
        Log.d(TAG, "setDisclosurePlayerSilent: no player found at construction time");
    }

    private static void hookAssetManagerOpen() {
        // Hook open(String) – the most common asset-open path.
        try {
            Method open = AssetManager.class.getDeclaredMethod("open", String.class);
            XposedBridge.hookMethod(open, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (!isDisclosureAudioAsset((String) param.args[0])) return;
                    param.setResult(new ByteArrayInputStream(getSilentPromptWavBytes()));
                    Log.w(TAG, "AssetManager.open: replaced prompt audio: " + param.args[0]);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager.open(String) hook unavailable", t);
        }
        // Hook open(String, int) – access-mode variant used by some callers.
        try {
            Method openMode = AssetManager.class.getDeclaredMethod("open", String.class, int.class);
            XposedBridge.hookMethod(openMode, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (!isDisclosureAudioAsset((String) param.args[0])) return;
                    param.setResult(new ByteArrayInputStream(getSilentPromptWavBytes()));
                    Log.w(TAG, "AssetManager.open(String,int): replaced prompt audio: " + param.args[0]);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager.open(String,int) hook unavailable", t);
        }
    }

    /**
     * Hooks {@code AssetManager.openFd(String)} to intercept disclosure audio assets opened as
     * file descriptors (e.g. when Dialer feeds an {@link AssetFileDescriptor} directly to
     * {@code MediaPlayer.setDataSource}).
     *
     * <p>Each call creates a new {@link ParcelFileDescriptor} opened on the cached silent temp
     * file. Ownership of both the PFD and the returned {@link AssetFileDescriptor} is transferred
     * to the caller; the caller is responsible for closing the {@link AssetFileDescriptor}, which
     * in turn closes the underlying PFD (standard {@link AssetFileDescriptor#close()} contract).
     */
    private static void hookAssetManagerOpenFd() {
        try {
            Method openFd = AssetManager.class.getDeclaredMethod("openFd", String.class);
            XposedBridge.hookMethod(openFd, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (!isDisclosureAudioAsset((String) param.args[0])) return;
                    File tmp = getSilentTempFile();
                    if (tmp == null) return;
                    try {
                        // Ownership of pfd and the returned afd is transferred to the caller.
                        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                                tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                        param.setResult(new AssetFileDescriptor(pfd, 0, tmp.length()));
                        Log.w(TAG, "AssetManager.openFd: replaced prompt audio: " + param.args[0]);
                    } catch (IOException e) {
                        Log.w(TAG, "AssetManager.openFd: silent replacement failed for: "
                                + param.args[0], e);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager.openFd hook unavailable", t);
        }
    }

    // -------------------------------------------------------------------------
    // Activity.onResume version/permission check
    // -------------------------------------------------------------------------

    private static void hookActivityOnResume() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context context = ((Activity) param.thisObject).getApplicationContext();
                        if (context == null) return;
                        if (ENABLE_CONSERVATIVE_ENVIRONMENT_MODE && !ranEnvCheck) {
                            ranEnvCheck = true;
                            boolean risky = detectRiskySpoofedEnvironment(context);
                            if (risky) {
                                conservativeMode = true;
                                Log.w(TAG, "conservative mode active due to inconsistent "
                                        + "environment: " + conservativeReasons);
                            }
                            Log.w(TAG, "envCheck done conservativeMode=" + conservativeMode);
                        }
                        checkUnsupportedVersion(context);
                    } catch (Throwable t) {
                        Log.w(TAG, "onResume check failed safely", t);
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "onResume method not found", e);
        }
    }

    private static void checkUnsupportedVersion(Context context) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                    != PackageManager.PERMISSION_GRANTED) {
                if (!warnedMissingPermission) {
                    warnedMissingPermission = true;
                    Log.w(TAG, "Missing CAPTURE_AUDIO_OUTPUT permission");
                }
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkUnsupportedVersion: permission check failed", t);
            return;
        }
        try {
            var pm = context.getPackageManager();
            if (pm == null) return;
            var info = pm.getPackageInfo(context.getPackageName(), 0);
            var versionName = info.versionName;
            if (ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Dialer versionName=" + versionName
                        + " versionCode=" + info.getLongVersionCode());
            }
            if (versionName != null && versionName.endsWith("downloadable")) {
                if (!warnedUnsupportedVersion) {
                    warnedUnsupportedVersion = true;
                    Log.w(TAG, "Unsupported version detected: " + versionName);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkUnsupportedVersion: package info check failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        handleDialerPackageLoad(lpparam.packageName, lpparam.processName, lpparam.classLoader);
    }

    static void handleDialerPackageLoad(String packageName, String processName, ClassLoader classLoader) {
        if (!"com.google.android.dialer".equals(packageName)) return;

        // Only install hooks in the main Dialer process. Sub-processes (e.g. ":remote", ":ui")
        // may not have the target classes loaded and hooking them causes unnecessary overhead.
        if (!packageName.equals(processName)) {
            Log.w(TAG, "Skipping non-main Dialer process: " + processName);
            return;
        }

        // Guard against duplicate hook installation when both legacy and modern Xposed
        // entrypoints are active at the same time in the same process.
        if (!hooksInstalled.compareAndSet(false, true)) {
            Log.w(TAG, "Hooks already installed; skipping duplicate entry for process="
                    + processName);
            return;
        }

        if (!BuildConfig.DEBUG && !ENABLE_SILENT_PROMPT_FALLBACK) {
            throw new IllegalStateException("Release builds must keep ENABLE_SILENT_PROMPT_FALLBACK=true");
        }

        boolean safeProfile = ENABLE_ANDROID16_SAFE_PROFILE && isAndroid16();

        Log.w(TAG, "handleLoadPackage: " + packageName
                + " process=" + processName
                + " device=" + Build.DEVICE
                + " model=" + Build.MODEL
                + " sdk=" + Build.VERSION.SDK_INT
                + " mode=" + MODE);
        Log.w(TAG, "safeProfile=" + safeProfile
                + " promptTtsHooks=" + ENABLE_PROMPT_TTS_HOOKS
                + " silentPromptFallback=" + ENABLE_SILENT_PROMPT_FALLBACK
                + " forceUSLocale=" + FORCE_US_RECORDING_LOCALE);

        if (safeProfile) {
            Log.w(TAG, "Android 16 safe profile active");
        }

        // Environment consistency check runs synchronously so hook installation
        // decisions below can use the result immediately.
        if (ENABLE_CONSERVATIVE_ENVIRONMENT_MODE) {
            try {
                // Context may not be available at load-package time; defer actual
                // telephony checks to Activity.onResume via the cached flag approach.
                // We do a lightweight pre-check here using only Build fields.
                Locale def = Locale.getDefault();
                if (def == null || def.getLanguage() == null || def.getLanguage().isEmpty()) {
                    conservativeMode = true;
                    conservativeReasons.add("null/empty default locale at load time");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Environment pre-check failed safely", t);
            }
        }

        if (conservativeMode) {
            Log.w(TAG, "conservative mode active due to inconsistent environment: "
                    + conservativeReasons);
        }
        Log.w(TAG, "conservativeMode=" + conservativeMode);

        // Hook installation is synchronous (no background thread) for determinism.
        try (var dex = new DexHelper(classLoader)) {
            hookCanRecordCall(dex);
            hookWithinCrosbyGeoFence(dex);
            hookGetSupportedLocaleFromCountryCode(dex);
            hookIsCallRecordingCountry(dex);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install DexHelper hooks", t);
        }

        if (ENABLE_PROMPT_TTS_HOOKS) {
            try {
                hookSynthesizeToFile();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install synthesizeToFile hook", t);
            }
            try {
                hookSpeak();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install speak hook", t);
            }
            try {
                hookIsLanguageAvailable();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install isLanguageAvailable hook", t);
            }
            try {
                hookAssetManagerOpen();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install AssetManager.open hook", t);
            }
            try {
                hookAssetManagerOpenFd();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install AssetManager.openFd hook", t);
            }
            try {
                hookCallRecordingDisclosure(classLoader);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install CallRecordingDisclosure hook", t);
            }
        } else {
            Log.w(TAG, "Prompt/TTS hooks disabled by configuration");
        }

        try {
            hookActivityOnResume();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install onResume hook", t);
        }

        Log.w(TAG, "hook done");
    }
}
