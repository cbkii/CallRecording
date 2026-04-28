package io.github.vvb2060.callrecording.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ModernInit extends XposedModule {
    private static final String TAG = "CallRecording";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            if (!"com.google.android.dialer".equals(param.getPackageName())) return;
            var appInfo = param.getApplicationInfo();
            String processName = appInfo != null ? appInfo.processName : param.getPackageName();
            Init.handleDialerPackageLoad(
                    param.getPackageName(),
                    processName,
                    param.getDefaultClassLoader());
            log(Log.WARN, TAG, "Modern API entry active");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Modern API entry failed", t);
        }
    }
}
