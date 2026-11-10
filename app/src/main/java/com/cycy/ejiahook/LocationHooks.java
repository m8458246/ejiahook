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
 *   BDLocation.d()  -> String 地址
 *   BDLocation.i()  -> String 城市
 * </pre>
 * <p>这是从 ejia 的 com.dcits.ehome.plugin.Geolocation 与 BaiduMapUtils 的调用点
 * 反推出来的：插件里就是 K()/Q()/i()/d() 组装成 {lat,lng,city,address} 回给 H5。</p>
 *
 * <p>做法：读纬度/经度时算出“目标点 + 随机漂移”，顺手用 setter 写回对象本身，
 * 这样同一对象上其它读取方（地图 SDK 等）看到的也是伪造坐标。</p>
 */
final class LocationHooks {

    private static final String TAG = "EjiaHook";
    private static final String BD_LOCATION = "com.baidu.location.BDLocation";
    private static final AtomicInteger sTrace = new AtomicInteger();

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

        // 地址/城市：可选覆盖，填了才改
        Method getAddr = find(cls, "d");
        if (getAddr != null) {
            hookText(module, getAddr, true);
        } else {
            module.log(Log.WARN, TAG, "BDLocation.d() 未找到，地址覆盖不可用");
        }
        Method getCity = find(cls, "i");
        if (getCity != null) {
            hookText(module, getCity, false);
        } else {
            module.log(Log.WARN, TAG, "BDLocation.i() 未找到，城市覆盖不可用");
        }

        module.log(Log.INFO, TAG, "定位 hook 安装完成（writeBack=" + canWriteBack + "）");
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
                            "定位替换 %s: 原值=%s -> %.6f,%.6f (半径 %.1fm)",
                            getter.getName(), raw, target[0], target[1], c.radius));
                }
                return isLat ? target[0] : target[1];
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook BDLocation." + getter.getName() + " 失败: " + t);
        }
    }

    private static void hookText(XposedModule module, Method getter, final boolean isAddr) {
        try {
            module.hook(getter).intercept(chain -> {
                Object result = chain.proceed();
                Config c = Config.get();
                if (result instanceof String) {
                    if (isAddr) {
                        sRealAddr = (String) result;
                    } else {
                        sRealCity = (String) result;
                    }
                    maybeCapture(module, c);
                }
                if (!c.enabled) {
                    return result;
                }
                String override = isAddr ? c.address : c.city;
                if (override.isEmpty()) {
                    return result;
                }
                if (c.debug && sTrace.getAndIncrement() < 40) {
                    module.log(Log.INFO, TAG, "文本覆盖 " + getter.getName()
                            + ": 原值=" + result + " -> " + override);
                }
                // 覆盖值非空就返回它：真机没信号、地址为空时也能给出一个“成功”的定位
                return override;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook BDLocation." + getter.getName() + " 失败: " + t);
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
