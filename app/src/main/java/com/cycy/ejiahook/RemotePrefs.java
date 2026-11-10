package com.cycy.ejiahook;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed 的远程 SharedPreferences 通道。
 *
 * <p>被注入进程不能直接读模块 App 的私有目录，而 ContentProvider 在部分 ROM / 被
 * HideMyApplist 之类的工具拦截时会报 {@code Unknown authority}（实测 ejia 进程里就会），
 * 所以优先走框架提供的 remote preferences，拿不到再退回 Provider。</p>
 *
 * <p>这个类只在被注入的进程里加载，模块 App 进程不会碰它（那边没有 libxposed 的类）。</p>
 */
final class RemotePrefs {

    private static volatile XposedModule sModule;

    private RemotePrefs() {
    }

    static void attach(XposedModule module) {
        sModule = module;
    }

    static SharedPreferences get() {
        XposedModule module = sModule;
        if (module == null) {
            return null;
        }
        try {
            return module.getRemotePreferences(Config.PREFS);
        } catch (Throwable t) {
            return null;
        }
    }
}
