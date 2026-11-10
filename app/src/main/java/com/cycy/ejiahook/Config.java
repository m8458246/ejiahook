package com.cycy.ejiahook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 模块配置。取配置的顺序（先拿到先用）：
 *
 * <ol>
 *   <li><b>调试文件</b>：/data/local/tmp/ejiahook.json、/sdcard/ejiahook.json、
 *       /sdcard/Download/ejiahook.json。给 root 调试用，改完不用碰 UI。</li>
 *   <li>LSPosed remote preferences（框架直读模块 App 的 prefs）</li>
 *   <li>模块导出的只读 ContentProvider（部分 ROM 会拦，见 RemotePrefs 注释）</li>
 *   <li>都拿不到就沿用上一次的有效配置</li>
 * </ol>
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
    public static final String KEY_DEBUG = "debug";

    private static final String TAG = "EjiaHook";
    private static final long TTL_MS = 2000L;
    private static final Object LOCK = new Object();

    private static final String[] DEBUG_FILES = {
            "/sdcard/Download/ejiahook.json",
            "/sdcard/ejiahook.json",
    };

    private static volatile Config sCache;
    private static volatile long sCacheAt;
    private static volatile String sChannel;
    private static volatile boolean sWarned;

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
    /** 调试日志开关 */
    public boolean debug;
    /** 是否已有可用的目标坐标 */
    public boolean hasTarget;

    private Config() {
    }

    /** 取当前配置，2 秒内复用缓存。 */
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

        // 1) root 调试文件
        Map<String, Object> debugMap = readDebugFile();
        if (debugMap != null) {
            return fromMap(debugMap);
        }

        // 2) 框架 remote prefs
        try {
            SharedPreferences remote = RemotePrefs.get();
            if (remote != null) {
                Map<String, ?> all = remote.getAll();
                noteChannel("remote-prefs(" + all.size() + ")");
                if (!all.isEmpty()) {
                    return fromMap(all);
                }
            }
        } catch (Throwable t) {
            noteChannel("remote-prefs 异常: " + t);
        }

        // 3) 模块自己的 ContentProvider
        Context ctx = appContext();
        if (ctx == null) {
            warnOnce("拿不到 Application 上下文，暂时无法读取配置");
            return fallback != null ? fallback : new Config();
        }
        Bundle bundle = null;
        try {
            bundle = ctx.getContentResolver().call(
                    Uri.parse("content://" + AUTHORITY), METHOD_GET, null, null);
        } catch (Throwable t) {
            warnOnce("Provider 读取配置失败: " + t);
        }
        if (bundle != null && !bundle.isEmpty()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : bundle.keySet()) {
                map.put(key, bundle.get(key));
            }
            noteChannel("provider");
            return fromMap(map);
        }

        return fallback != null ? fallback : new Config();
    }

    private static Map<String, Object> readDebugFile() {
        for (String path : DEBUG_FILES) {
            try {
                File file = new File(path);
                if (!file.isFile() || !file.canRead()) {
                    continue;
                }
                String text = readAll(file);
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                JSONObject json = new JSONObject(text);
                Map<String, Object> map = new HashMap<>();
                for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                    String key = it.next();
                    map.put(key, json.get(key));
                }
                if (!map.isEmpty()) {
                    noteChannel("debug-file " + path);
                    return map;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readAll(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void noteChannel(String channel) {
        if (!channel.equals(sChannel)) {
            sChannel = channel;
            Log.i(TAG, "配置来源: " + channel);
        }
    }

    private static void warnOnce(String message) {
        if (!sWarned) {
            sWarned = true;
            Log.w(TAG, message);
        }
    }

    private static Config fromMap(Map<String, ?> map) {
        Config c = new Config();
        c.enabled = getBool(map, KEY_ENABLED, false);
        c.radius = parseDouble(getStr(map, KEY_RADIUS), 5.0);
        if (c.radius < 0) c.radius = 0;
        if (c.radius > 5000) c.radius = 5000;
        c.lat = parseDouble(getStr(map, KEY_LAT), 0);
        c.lng = parseDouble(getStr(map, KEY_LNG), 0);
        c.address = nullToEmpty(getStr(map, KEY_ADDRESS));
        c.city = nullToEmpty(getStr(map, KEY_CITY));
        c.deviceEnabled = getBool(map, KEY_DEVICE_ENABLED, false);
        c.deviceId = nullToEmpty(getStr(map, KEY_DEVICE_ID));
        c.debug = getBool(map, KEY_DEBUG, false);
        c.hasTarget = (c.lat != 0 || c.lng != 0)
                && c.lat >= -90 && c.lat <= 90
                && c.lng >= -180 && c.lng <= 180;
        return c;
    }

    private static String getStr(Map<String, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean getBool(Map<String, ?> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
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
