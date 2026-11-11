package com.cycy.ejiahook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块入口。使用 libxposed API 102（新式 Xposed 模块接口），
 * 入口在 META-INF/xposed/java_init.list 里声明。
 */
public class ModuleMain extends XposedModule {

    private static final String TAG = "EjiaHook";
    private static final String TARGET_PACKAGE = "com.dcits.ehome";

    private static volatile boolean sInstalled;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        RemotePrefs.attach(this);
        log(Log.INFO, TAG, "模块加载: process=" + param.getProcessName()
                + " framework=" + getFrameworkName()
                + " api=" + getApiVersion()
                + " remote=" + ((getFrameworkProperties() & PROP_CAP_REMOTE) != 0));
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        synchronized (ModuleMain.class) {
            if (sInstalled) {
                return;
            }
            sInstalled = true;
        }
        log(Log.INFO, TAG, "ejia 就绪，开始安装 hook");
        try {
            ClassLoader cl = param.getClassLoader();
            LocationHooks.install(this, cl);
            DeviceIdHooks.install(this);
            DebugHooks.install(this, cl);
            RootHooks.install(this, cl);
            SystemLocationHooks.install(this);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "安装 hook 异常", t);
        }
    }
}
