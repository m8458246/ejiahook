package com.cycy.ejiahook;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 调试用 hook：把 ejia 递给 H5 的定位结果打出来。
 *
 * <p>只在配置里 {@code "debug": true} 时输出，正式使用不产生日志噪音。</p>
 *
 * <ul>
 *   <li>{@code EncapPluginResult#sendJsonObjectResult} —— Cordova 插件回给 H5 的最终 JSON</li>
 *   <li>{@code Geolocation#execute} —— H5 调用了哪个定位方法</li>
 * </ul>
 */
final class DebugHooks {

    private static final String TAG = "EjiaHook";
    private static final String ENCAP_RESULT = "com.cloudcore.fpaas.h5container.plugin.EncapPluginResult";
    private static final String GEOLOCATION = "com.dcits.ehome.plugin.Geolocation";

    private DebugHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        hookEncapsulatedResult(module, cl);
        hookPluginExecute(module, cl);
    }

    private static void hookEncapsulatedResult(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(ENCAP_RESULT, false, cl);
            for (Method method : cls.getDeclaredMethods()) {
                if (!"sendJsonObjectResult".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 3 || !params[2].equals(Object.class)) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    if (Config.get().debug) {
                        Object code = chain.getArg(1);
                        Object payload = chain.getArg(2);
                        module.log(Log.INFO, TAG, "H5 收到 code=" + code + " body=" + payload);
                    }
                    return chain.proceed();
                });
                module.log(Log.INFO, TAG, "调试 hook: EncapPluginResult#sendJsonObjectResult");
                return;
            }
            module.log(Log.WARN, TAG, "没找到 EncapPluginResult#sendJsonObjectResult");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "调试 hook(EncapPluginResult) 失败: " + t);
        }
    }

    private static void hookPluginExecute(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(GEOLOCATION, false, cl);
            for (Method method : cls.getDeclaredMethods()) {
                if (!"execute".equals(method.getName())) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    if (Config.get().debug) {
                        module.log(Log.INFO, TAG, "H5 调用定位插件: action=" + chain.getArg(0)
                                + " args=" + chain.getArg(1));
                    }
                    return chain.proceed();
                });
                module.log(Log.INFO, TAG, "调试 hook: Geolocation#execute");
                return;
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "调试 hook(Geolocation) 失败: " + t);
        }
    }
}
