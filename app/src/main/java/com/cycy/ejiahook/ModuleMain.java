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

    private static final Object LOCK = new Object();
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
        synchronized (LOCK) {
            if (sInstalled) {
                return;
            }
        }
        log(Log.INFO, TAG, "ejia 就绪，开始安装 hook");

        ClassLoader cl = param.getClassLoader();
        Config c = Config.get();

        // 每一项单独 try/catch：一个 hook 失败不影响其它项，也方便定位是哪一项
        step("定位", () -> LocationHooks.install(this, cl));
        step("设备标识", () -> DeviceIdHooks.install(this));
        step("调试", () -> DebugHooks.install(this, cl));
        if (c.rootHook) {
            step("root 检测", () -> RootHooks.install(this, cl));
        } else {
            log(Log.INFO, TAG, "root 检测 hook 已按配置跳过");
        }
        if (c.systemLocation) {
            step("系统定位", () -> SystemLocationHooks.install(this));
        } else {
            log(Log.INFO, TAG, "系统定位 hook 已按配置跳过");
        }

        // 全部走完才置位：中途异常时下次 onPackageReady 还能重试
        synchronized (LOCK) {
            sInstalled = true;
        }
        log(Log.INFO, TAG, "hook 安装流程结束");
    }

    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, name + " hook 安装异常", t);
        }
    }
}
