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
        hookDsBridgeLocation(module, cl);
        hookLocationPayload(module, cl);
    }

    /**
     * H5 拿到的定位字段就是在这里拼的：DsBridgeWebViewActivity#hanndleLocationData 往
     * 一个 Map（f.a.a.e）里 put address/latitude/longitude，然后序列化回给 JS。
     * 这里把真正塞进去的值打出来，可以和 location.log 对照。
     */
    private static void hookLocationPayload(XposedModule module, ClassLoader cl) {
        try {
            // f.a.a.a#g() 把 Map 序列化成 JSON，hanndleLocationData 就是拿它的结果回给 H5
            Class<?> cls = Class.forName("f.a.a.a", false, cl);
            Method g = cls.getDeclaredMethod("g");
            g.setAccessible(true);
            module.hook(g).intercept(chain -> {
                Object result = chain.proceed();
                if (Config.get().debug && result instanceof String) {
                    String text = (String) result;
                    if (text.contains("latitude")) {
                        module.log(Log.INFO, TAG, "H5 定位 JSON = " + text);
                    }
                }
                return result;
            });
            module.log(Log.INFO, TAG, "调试 hook: H5 定位 JSON");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "调试 hook(H5 定位字段) 失败: " + t);
        }
    }

    /**
     * H5 走 dsbridge 取定位时走这里：DsBridgeWebViewActivity#hanndleLocationData(callback, BDLocation)。
     * 这里打印的经纬度就是最终回给 H5 的值。
     */
    private static void hookDsBridgeLocation(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName("com.dcits.ehome.dsbridge.DsBridgeWebViewActivity", false, cl);
            Method target = null;
            for (Method method : cls.getDeclaredMethods()) {
                if ("hanndleLocationData".equals(method.getName())) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                module.log(Log.WARN, TAG, "没找到 DsBridgeWebViewActivity#hanndleLocationData");
                return;
            }
            target.setAccessible(true);
            final Method m = target;
            module.hook(m).intercept(chain -> {
                if (Config.get().debug) {
                    Object location = chain.getArg(1);
                    String detail = String.valueOf(location);
                    try {
                        Class<?> bd = location.getClass();
                        Method k = bd.getDeclaredMethod("K");
                        Method q = bd.getDeclaredMethod("Q");
                        k.setAccessible(true);
                        q.setAccessible(true);
                        detail = "lat=" + k.invoke(location) + " lng=" + q.invoke(location);
                    } catch (Throwable ignored) {
                    }
                    module.log(Log.INFO, TAG, "dsbridge 定位回调 -> " + detail);
                }
                return chain.proceed();
            });
            module.log(Log.INFO, TAG, "调试 hook: DsBridgeWebViewActivity#hanndleLocationData");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "调试 hook(dsbridge) 失败: " + t);
        }
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
