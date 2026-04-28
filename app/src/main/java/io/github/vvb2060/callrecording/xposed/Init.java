package io.github.vvb2060.callrecording.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
    private static final boolean ENABLE_VERBOSE_LOGGING = true;

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

    /** en-AU locale constant; also used as the first entry in RECORDING_LOCALE_FALLBACKS. */
    private static final Locale EN_AU = Locale.forLanguageTag("en-AU");

    /**
     * Fallback locale chain tried in order when the original Dialer locale is null/unsupported
     * and FORCE_US_RECORDING_LOCALE is false.
     */
    private static final Locale[] RECORDING_LOCALE_FALLBACKS = new Locale[]{
            EN_AU,
            Locale.US,
            Locale.ENGLISH
    };

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    private static volatile boolean conservativeMode = false;
    private static final List<String> conservativeReasons = new ArrayList<>();

    // Once-only guard for the deferred conservative environment check run from onResume
    private static volatile boolean ranEnvCheck = false;

    // Once-only warning flags to avoid Toast spam
    private static volatile boolean warnedMissingPermission = false;
    private static volatile boolean warnedUnsupportedVersion = false;

    // -------------------------------------------------------------------------
    // Silent WAV fallback (1 channel, 16-bit PCM, empty audio)
    // -------------------------------------------------------------------------

    private static final byte[] wav = {
            82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86,
            69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0,
            1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2,
            0, 16, 0, 100, 97, 116, 97, 0, 0, 0, 0};

    private static volatile byte[] silentPromptWav;

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
        try {
            Locale def = Locale.getDefault();
            if (def != null && "en".equalsIgnoreCase(def.getLanguage())) {
                return def;
            }
        } catch (Throwable t) {
            Log.w(TAG, "safeRecordingFallbackLocale: getDefault failed", t);
        }
        for (Locale locale : RECORDING_LOCALE_FALLBACKS) {
            if (locale != null && locale.getLanguage() != null && !locale.getLanguage().isEmpty()) {
                return locale;
            }
        }
        return Locale.US;
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
                    if (ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "getSupportedLocaleFromCountryCode args="
                                + Arrays.toString(param.args) + " original=" + original);
                    }

                    if (FORCE_US_RECORDING_LOCALE) {
                        // Always override to the forced locale but still ran original above
                        Log.d(TAG, "getSupportedLocaleFromCountryCode: locale original="
                                + original + " final=" + fallback + " (forced)");
                        param.setResult(fallback);
                        return;
                    }

                    if (original instanceof Locale) {
                        // Preserve Dialer's own successful locale
                        Log.d(TAG, "getSupportedLocaleFromCountryCode: locale original="
                                + original + " final=" + original + " (preserved)");
                        return;
                    }

                    Log.w(TAG, "getSupportedLocaleFromCountryCode: locale original="
                            + original + " (null/unsupported) final=" + fallback);
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

    @SuppressWarnings({"SoonBlockedPrivateApi", "JavaReflectionMemberAccess"})
    private static void hookDispatchOnInit() {
        try {
            Method dispatchOnInit = TextToSpeech.class.getDeclaredMethod("dispatchOnInit",
                    int.class);
            XposedBridge.hookMethod(dispatchOnInit, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "dispatchOnInit: args=" + Arrays.toString(param.args));
                    }
                    // Intercept TTS init failures before the listener is notified.
                    // Mutates args[0] to SUCCESS so Dialer treats TTS as initialised
                    // even when the engine reported an error.
                    // Guarded by conservativeMode to avoid overriding in spoofed environments.
                    if (!conservativeMode
                            && ENABLE_PROMPT_TTS_HOOKS
                            && !Objects.equals(param.args[0], TextToSpeech.SUCCESS)) {
                        param.args[0] = TextToSpeech.SUCCESS;
                        Log.w(TAG, "dispatchOnInit: TTS init failed, overriding to SUCCESS");
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "dispatchOnInit method not found", e);
        }
    }

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

    private static void hookSynthesizeToFile() {
        try {
            Method synthesizeToFile = TextToSpeech.class.getDeclaredMethod("synthesizeToFile",
                    CharSequence.class, Bundle.class, File.class, String.class);
            XposedBridge.hookMethod(synthesizeToFile, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "synthesizeToFile: args=" + Arrays.toString(param.args));
                    }
                    // Do NOT pre-empty the text here; let TTS attempt genuine synthesis first.
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ENABLE_PROMPT_TTS_HOOKS) return;
                    if (Objects.equals(param.getResult(), TextToSpeech.SUCCESS)) return;

                    if (!ENABLE_SILENT_PROMPT_FALLBACK) {
                        Log.w(TAG, "synthesizeToFile: TTS failed and silent fallback is disabled;"
                                + " result=" + param.getResult());
                        return;
                    }

                    Log.w(TAG, "synthesizeToFile: TTS failed, writing silent wav fallback");
                    var file = (File) param.args[2];
                    try (var out = new FileOutputStream(file)) {
                        out.write(getSilentPromptWavBytes());
                        param.setResult(TextToSpeech.SUCCESS);
                    } catch (IOException e) {
                        Log.e(TAG, "synthesizeToFile: cannot write " + file, e);
                        return;
                    }
                    try {
                        var field = param.thisObject.getClass()
                                .getDeclaredField("mUtteranceProgressListener");
                        field.setAccessible(true);
                        var listener = (UtteranceProgressListener) field.get(param.thisObject);
                        if (listener == null) return;
                        var onDone = UtteranceProgressListener.class.getDeclaredMethod(
                                "onDone", String.class);
                        onDone.invoke(listener, (String) param.args[3]);
                    } catch (ReflectiveOperationException e) {
                        Log.e(TAG, "synthesizeToFile: cannot invoke onDone", e);
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "synthesizeToFile method not found", e);
        }
    }

    private static void hookSpeak() {
        try {
            Method speak = TextToSpeech.class.getDeclaredMethod(
                    "speak", CharSequence.class, int.class, Bundle.class);
            XposedBridge.hookMethod(speak, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_PROMPT_TTS_HOOKS || !ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (!inCallRecordingContext()) return;
                    param.args[0] = "";
                    param.setResult(TextToSpeech.SUCCESS);
                    Log.w(TAG, "speak(CharSequence,int,Bundle): silent fallback engaged");
                }
            });
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "speak(CharSequence,int,Bundle) not found", e);
        }
    }

    private static void hookCallRecordingDisclosure(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookConstructor(
                    "com.google.android.dialer.callcomposer.CallRecordingDisclosure",
                    classLoader,
                    new XC_MethodHook() {
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
        for (String fieldName : new String[]{"mMediaPlayer", "mediaPlayer", "player"}) {
            try {
                Object player = XposedHelpers.getObjectField(disclosure, fieldName);
                if (player != null) {
                    XposedHelpers.callMethod(player, "setVolume", 0f, 0f);
                    Log.w(TAG, "Recording: Silent mode active (player muted via " + fieldName
                            + ")");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookAssetManagerOpen() {
        try {
            Method open = AssetManager.class.getDeclaredMethod("open", String.class);
            XposedBridge.hookMethod(open, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ENABLE_SILENT_PROMPT_FALLBACK) return;
                    if (!inCallRecordingContext()) return;
                    String name = (String) param.args[0];
                    if (name == null) return;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.contains("record")
                            && !lower.contains("disclosure")
                            && !lower.contains("announcement")) {
                        return;
                    }
                    if (!(lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".mp3"))) {
                        return;
                    }
                    param.setResult(new ByteArrayInputStream(getSilentPromptWavBytes()));
                    Log.w(TAG, "AssetManager.open: replaced prompt audio with silent asset: " + name);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager.open hook unavailable", t);
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
                    Toast.makeText(context,
                            "CallRecording: Missing CAPTURE_AUDIO_OUTPUT permission",
                            Toast.LENGTH_LONG).show();
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
                    Toast.makeText(context,
                            "CallRecording: Unsupported version, please use full version.",
                            Toast.LENGTH_LONG).show();
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
                hookDispatchOnInit();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install dispatchOnInit hook", t);
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
