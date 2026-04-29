-repackageclasses
-allowaccessmodification

# Legacy Xposed entrypoint – referenced by assets/xposed_init
-keep class io.github.vvb2060.callrecording.xposed.Init {
    <init>();
    void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# Modern libxposed entrypoint – referenced by META-INF/xposed/java_init.list
-keep class io.github.vvb2060.callrecording.xposed.ModernInit {
    <init>(...);
    void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
}

-keepclasseswithmembers class io.github.vvb2060.callrecording.xposed.DexHelper {
    native <methods>;
    long token;
    java.lang.ClassLoader classLoader;
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
