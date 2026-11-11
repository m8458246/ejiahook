package com.cycy.ejiahook;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * ejia 用的是百度定位 SDK（liblocSDK8b.so + com.baidu.location.*），
 * 不是 chuichui 针对的 AMap，所以 hook 点换成 BDLocation。
 *
 * <p>ejia 的 APK 里百度 SDK 被混淆过，方法名不是 getLatitude/getLongitude：</p>
 * <pre>
 *   BDLocation.K()  -> double 纬度   （字段 e2，setter j1(double)）
 *   BDLocation.Q()  -> double 经度   （字段 f2，setter p1(double)）
 *   BDLocation.d()  -> String 完整地址
 *   BDLocation.i()  -> String 城市
 *   BDLocation.c0() -> String 省
 *   BDLocation.r()  -> String 区
 *   BDLocation.k0() -> String 街道
 * </pre>
 * <p>H5 拿到的地址有两条路：Cordova 插件用 d()，dsbridge 用 c0()+i()+r()+k0() 拼，
 * 所以这几个都要能覆盖。</p>
 */
final class LocationHooks {

    private static final String TAG = "EjiaHook";
    private static final String BD_LOCATION = "com.baidu.location.BDLocation";
    private static final AtomicInteger sTrace = new AtomicInteger();

    /** 回传真实定位用的广播（模块 App 侧接收后填进输入框） */
    private static final long CAPTURE_TTL_MS = 5 * 60 * 1000L;

    private static volatile double sRealLat = Double.NaN;
    private static volatile double sRealLng = Double.NaN;
    private static volatile String sRealCity = "";
    private static volatile String sRealAddr = "";
    private static volatile long sLastSent;

    private LocationHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Class<?> cls;
        try {
            cls = Class.forName(BD_LOCATION, false, cl);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "找不到 " + BD_LOCATION + "，定位 hook 未安装: " + t);
            return;
        }

        Method getLat = find(cls, "K");
        Method getLng = find(cls, "Q");
        if (getLat == null || getLng == null) {
            module.log(Log.ERROR, TAG, "BDLocation.K()/Q() 签名不符（APK 可能已更新），定位 hook 未安装");
            return;
        }

        Method setLat = find(cls, "j1", double.class);
        Method setLng = find(cls, "p1", double.class);
        final boolean canWriteBack = setLat != null && setLng != null;
        if (!canWriteBack) {
            module.log(Log.WARN, TAG, "没找到 BDLocation 的坐标 setter，退化为只改 getter 返回值");
        }

        hookCoord(module, getLat, true, setLat, setLng);
        hookCoord(module, getLng, false, setLat, setLng);

        hookText(module, cls, "d", "地址", LocationHooks::fullAddress);
        hookText(module, cls, "i", "城市", c -> c.city);
        hookText(module, cls, "c0", "省", c -> c.province);
        hookText(module, cls, "r", "区", c -> c.district);
        hookText(module, cls, "k0", "街道", c -> c.street);

        module.log(Log.INFO, TAG, "定位 hook 安装完成（writeBack=" + canWriteBack + "）");
    }

    /** 完整地址 = 省+市+区+街道+详细地址；都为空则不覆盖，保留百度原始值。 */
    private static String fullAddress(Config c) {
        String composed = c.province + c.city + c.district + c.street + c.detail;
        return composed.trim().isEmpty() ? "" : composed;
    }

    private interface Override {
        String pick(Config c);
    }

    private static void hookCoord(XposedModule module, Method getter, final boolean isLat,
                                  final Method setLat, final Method setLng) {
        try {
            module.hook(getter).intercept(chain -> {
                Object raw = chain.proceed();
                Config c = Config.get();
                if (raw instanceof Number) {
                    if (isLat) {
                        sRealLat = ((Number) raw).doubleValue();
                    } else {
                        sRealLng = ((Number) raw).doubleValue();
                    }
                    maybeCapture(module, c);
                }
                if (!c.enabled || !c.hasTarget) {
                    return raw;
                }
                Object self = chain.getThisObject();
                if (self == null) {
                    return raw;
                }
                double[] target = Drift.targetFor(self, c);
                if (setLat != null && setLng != null) {
                    // 幂等写入：即使 SDK 之后又用真实值覆盖了，下次读取也会重新纠正
                    try {
                        setLat.invoke(self, target[0]);
                        setLng.invoke(self, target[1]);
                    } catch (Throwable ignored) {
                    }
                }
                if (c.debug && sTrace.getAndIncrement() < 20) {
                    module.log(Log.INFO, TAG, String.format(Locale.US,
                            "定位替换 %s: 原值=%s -> %.6f,%.6f (偏移 %.1fm)",
                            getter.getName(), raw, target[0], target[1], Drift.meters(c, target)));
                }
                return isLat ? target[0] : target[1];
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook BDLocation." + getter.getName() + " 失败: " + t);
        }
    }

    private static void hookText(XposedModule module, Class<?> cls, String name,
                                 final String label, final Override override) {
        Method getter = find(cls, name);
        if (getter == null) {
            module.log(Log.WARN, TAG, "BDLocation." + name + "() 未找到，" + label + "覆盖不可用");
            return;
        }
        final boolean isAddr = "d".equals(name);
        final boolean isCity = "i".equals(name);
        try {
            module.hook(getter).intercept(chain -> {
                Object result = chain.proceed();
                Config c = Config.get();
                if (result instanceof String) {
                    if (isAddr) {
                        sRealAddr = (String) result;
                    } else if (isCity) {
                        sRealCity = (String) result;
                    }
                    maybeCapture(module, c);
                }
                if (!c.enabled) {
                    return result;
                }
                String value = override.pick(c);
                if (value.isEmpty()) {
                    return result;
                }
                if (c.debug && sTrace.getAndIncrement() < 60) {
                    module.log(Log.INFO, TAG, "文本覆盖 " + name + "(" + label + "): 原值="
                            + result + " -> " + value);
                }
                // 覆盖值非空就返回它：真机没信号、地址为空时也能给出一个“成功”的定位
                return value;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook BDLocation." + name + " 失败: " + t);
        }
    }

    /**
     * 用户点了「获取 ejia 当前定位」时，把真实定位通过广播回传给模块 App。
     * 只在没开启定位伪造时回传，避免把伪造值当成真实值。
     */
    private static void maybeCapture(XposedModule module, Config c) {
        if (!c.capture || c.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (c.captureTs > 0 && now - c.captureTs > CAPTURE_TTL_MS) {
            return;
        }
        if (Double.isNaN(sRealLat) || Double.isNaN(sRealLng)) {
            return;
        }
        if (now - sLastSent < 2000) {
            return;
        }
        sLastSent = now;
        try {
            Context ctx = Config.appContext();
            if (ctx == null) {
                return;
            }
            Intent intent = new Intent(Config.ACTION_CAPTURE);
            intent.setPackage("com.cycy.ejiahook");
            intent.putExtra("lat", sRealLat);
            intent.putExtra("lng", sRealLng);
            intent.putExtra("city", sRealCity);
            intent.putExtra("address", sRealAddr);
            intent.putExtra("ts", now);
            ctx.sendBroadcast(intent);
            module.log(Log.INFO, TAG, "已回传真实定位 " + sRealLat + "," + sRealLng
                    + " city=" + sRealCity + " addr=" + sRealAddr);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "回传真实定位失败: " + t);
        }
    }

    private static Method find(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
