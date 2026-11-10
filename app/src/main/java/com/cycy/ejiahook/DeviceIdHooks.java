package com.cycy.ejiahook;

import android.net.wifi.WifiInfo;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * 设备唯一标识 hook。
 *
 * <p>ejia 取标识的几条路（都在 APK 里核实过）：</p>
 * <ul>
 *   <li>com.cloudcore.fpaas.security.util.PhoneInfoUtils#getImei：
 *       Android Q 以上直接跳过 getDeviceId，改用 Settings.Secure 的 android_id，
 *       拿不到再退回 SystemProperties(ro.aliyun.clouduuid) 和随机 UUID。</li>
 *   <li>com.cloudcore.venus.utils.DeviceUtils#getDeviceId：Wi-Fi MAC → IMEI → SIM 序列号 → UUID。</li>
 *   <li>com.cloudcore.venus.utils.DeviceUtils / fpaas DeviceUtil：同样读 getDeviceId / getSimSerialNumber。</li>
 * </ul>
 *
 * <p>默认全部原样透传（真实值）；只有用户在模块里填了标识并打开开关才替换。</p>
 */
final class DeviceIdHooks {

    private static final String TAG = "EjiaHook";
    private static final Pattern MAC_PATTERN =
            Pattern.compile("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$");

    private DeviceIdHooks() {
    }

    static void install(XposedModule module) {
        int count = 0;
        count += hookTelephony(module);
        count += hookSettings(module);
        count += hookBuildSerial(module);
        count += hookMac(module);
        module.log(Log.INFO, TAG, "设备标识 hook 安装完成，共 " + count + " 个");
    }

    private static int hookTelephony(XposedModule module) {
        String[] names = {
                "getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber"
        };
        int count = 0;
        for (String name : names) {
            Method noArg = declared(TelephonyManager.class, name);
            if (noArg != null && hookOverride(module, noArg)) count++;

            Method withSlot = declared(TelephonyManager.class, name, int.class);
            if (withSlot != null && hookOverride(module, withSlot)) count++;
        }
        return count;
    }

    private static int hookSettings(XposedModule module) {
        int count = 0;
        Class<?>[] types = {Settings.Secure.class, Settings.System.class};
        for (Class<?> cls : types) {
            Method m = declared(cls, "getString",
                    android.content.ContentResolver.class, String.class);
            if (m == null) continue;
            try {
                module.hook(m).intercept(chain -> {
                    Config c = Config.get();
                    Object key = chain.getArg(1);
                    if (c.deviceEnabled && !c.deviceId.isEmpty()
                            && "android_id".equals(key)) {
                        return c.deviceId;
                    }
                    return chain.proceed();
                });
                count++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + cls.getSimpleName() + ".getString 失败: " + t);
            }
        }
        return count;
    }

    private static int hookBuildSerial(XposedModule module) {
        Method m = declared(Build.class, "getSerial");
        return (m != null && hookOverride(module, m)) ? 1 : 0;
    }

    private static int hookMac(XposedModule module) {
        Method m = declared(WifiInfo.class, "getMacAddress");
        if (m == null) return 0;
        try {
            module.hook(m).intercept(chain -> {
                Config c = Config.get();
                // 只在填的值本身是 MAC 格式时才替换，避免把非法值塞给网络相关逻辑
                if (c.deviceEnabled && MAC_PATTERN.matcher(c.deviceId).matches()) {
                    return c.deviceId;
                }
                return chain.proceed();
            });
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook WifiInfo.getMacAddress 失败: " + t);
            return 0;
        }
    }

    private static boolean hookOverride(XposedModule module, Method m) {
        try {
            module.hook(m).intercept(chain -> {
                Config c = Config.get();
                if (c.deviceEnabled && !c.deviceId.isEmpty()) {
                    return c.deviceId;
                }
                return chain.proceed();
            });
            return true;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + m.getDeclaringClass().getSimpleName()
                    + "." + m.getName() + " 失败: " + t);
            return false;
        }
    }

    private static Method declared(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
