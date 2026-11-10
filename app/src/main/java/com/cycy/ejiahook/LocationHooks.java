package com.cycy.ejiahook;

import android.util.Log;

import java.lang.reflect.Method;

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

    private static volatile boolean sLogged;

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
                Config c = Config.get();
                if (!c.enabled || !c.hasTarget) {
                    return chain.proceed();
                }
                Object self = chain.getThisObject();
                if (self == null) {
                    return chain.proceed();
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
                if (!sLogged) {
                    sLogged = true;
                    module.log(Log.INFO, TAG, String.format(java.util.Locale.US,
                            "定位已替换 -> %.6f,%.6f (半径 %.1fm)", target[0], target[1], c.radius));
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
                if (!c.enabled) {
                    return result;
                }
                String override = isAddr ? c.address : c.city;
                if (override.isEmpty()) {
                    return result;
                }
                // 覆盖值非空就返回它：真机没信号、地址为空时也能给出一个“成功”的定位
                return override;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook BDLocation." + getter.getName() + " 失败: " + t);
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
