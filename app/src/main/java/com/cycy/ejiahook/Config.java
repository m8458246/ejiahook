package com.cycy.ejiahook;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 模块配置。
 *
 * <p>模块 App 侧把配置写进 SharedPreferences("config")；
 * 被注入的 ejia 进程通过本模块导出的 ContentProvider（只读 get）读取，
 * 因此改完设置不需要重启 ejia。</p>
 */
public final class Config {

    public static final String AUTHORITY = "com.cycy.ejiahook.config";
    public static final String PREFS = "config";
    public static final String METHOD_GET = "get";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LAT = "lat";
    public static final String KEY_LNG = "lng";
    public static final String KEY_RADIUS = "radius";
    public static final String KEY_ADDRESS = "address";
    public static final String KEY_CITY = "city";
    public static final String KEY_DEVICE_ENABLED = "device_enabled";
    public static final String KEY_DEVICE_ID = "device_id";

    private static final String TAG = "EjiaHook";
    private static final long TTL_MS = 2000L;
    private static final Object LOCK = new Object();

    private static volatile Config sCache;
    private static volatile long sCacheAt;
    private static volatile boolean sProviderWarned;

    /** 定位伪造开关 */
    public boolean enabled;
    /** 目标纬度（GCJ-02） */
    public double lat;
    /** 目标经度（GCJ-02） */
    public double lng;
    /** 漂移半径，单位米 */
    public double radius;
    /** 地址覆盖，空串表示不覆盖 */
    public String address = "";
    /** 城市覆盖，空串表示不覆盖 */
    public String city = "";
    /** 设备标识覆盖开关 */
    public boolean deviceEnabled;
    /** 覆盖后的设备标识 */
    public String deviceId = "";
    /** 是否已有可用的目标坐标 */
    public boolean hasTarget;

    private Config() {
    }

    /** 取当前配置，2 秒内复用缓存，避免每次定位都走一次 IPC。 */
    public static Config get() {
        Config c = sCache;
        if (c != null && SystemClock.elapsedRealtime() - sCacheAt < TTL_MS) {
            return c;
        }
        synchronized (LOCK) {
            c = sCache;
            if (c != null && SystemClock.elapsedRealtime() - sCacheAt < TTL_MS) {
                return c;
            }
            Config fresh = load();
            sCache = fresh;
            sCacheAt = SystemClock.elapsedRealtime();
            return fresh;
        }
    }

    /** 缓存键：配置变化后，同一个 BDLocation 实例的漂移量需要重算。 */
    public String signature() {
        return String.format(Locale.US, "%b|%.7f|%.7f|%.2f|%s|%s",
                enabled, lat, lng, radius, address, city);
    }

    private static Config load() {
        Config fallback = sCache;
        Context ctx = appContext();
        if (ctx == null) {
            if (!sProviderWarned) {
                sProviderWarned = true;
                Log.w(TAG, "拿不到 Application 上下文，暂用默认配置");
            }
            return fallback != null ? fallback : new Config();
        }
        Bundle b = null;
        try {
            b = ctx.getContentResolver().call(
                    Uri.parse("content://" + AUTHORITY), METHOD_GET, null, null);
        } catch (Throwable t) {
            if (!sProviderWarned) {
                sProviderWarned = true;
                Log.w(TAG, "读取模块配置失败（模块 App 是否被强行停止？）: " + t);
            }
        }
        if (b == null) {
            return fallback != null ? fallback : new Config();
        }
        return fromBundle(b);
    }

    private static Config fromBundle(Bundle b) {
        Config c = new Config();
        c.enabled = b.getBoolean(KEY_ENABLED, false);
        c.radius = parseDouble(b.getString(KEY_RADIUS), 5.0);
        if (c.radius < 0) c.radius = 0;
        if (c.radius > 5000) c.radius = 5000;
        c.lat = parseDouble(b.getString(KEY_LAT), 0);
        c.lng = parseDouble(b.getString(KEY_LNG), 0);
        c.address = nullToEmpty(b.getString(KEY_ADDRESS));
        c.city = nullToEmpty(b.getString(KEY_CITY));
        c.deviceEnabled = b.getBoolean(KEY_DEVICE_ENABLED, false);
        c.deviceId = nullToEmpty(b.getString(KEY_DEVICE_ID));
        c.hasTarget = (c.lat != 0 || c.lng != 0)
                && c.lat >= -90 && c.lat <= 90
                && c.lng >= -180 && c.lng <= 180;
        return c;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    static double parseDouble(String s, double def) {
        if (s == null || s.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 在被注入的进程里拿 Application：优先 ActivityThread，兜底 AppGlobals。 */
    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Application app = (Application) m.invoke(null);
            if (app != null) return app;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Method m = ag.getDeclaredMethod("getInitialApplication");
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
