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
    /** 模块（ejia 进程内）回传真实定位的广播 */
    public static final String ACTION_CAPTURE = "com.cycy.ejiahook.CAPTURE";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LAT = "lat";
    public static final String KEY_LNG = "lng";
    public static final String KEY_RADIUS = "radius";
    public static final String KEY_ADDRESS = "address";
    public static final String KEY_DETAIL = "detail";
    public static final String KEY_CITY = "city";
    public static final String KEY_PROVINCE = "province";
    public static final String KEY_DISTRICT = "district";
    public static final String KEY_STREET = "street";
    public static final String KEY_DEVICE_ENABLED = "device_enabled";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_DEBUG = "debug";
    public static final String KEY_SYSTEM_LOCATION = "system_location";
    public static final String KEY_ROOT_HOOK = "root_hook";
    public static final String KEY_CAPTURE = "capture";
    public static final String KEY_CAPTURE_TS = "capture_ts";

    private static final String TAG = "EjiaHook";
    private static final long TTL_MS = 2000L;
    private static final Object LOCK = new Object();

    /** 配置目录：/sdcard/Download/ejiahook/ ，配置和定位日志都放这里 */
    public static final String DOWNLOAD_SUBDIR = "ejiahook";
    public static final String CONFIG_FILE_PATH = "/sdcard/Download/ejiahook/config.json";
    private static final String DOWNLOAD_DIR = "/sdcard/Download";
    private static final String FILE_PREFIX = "ejiahook";
    private static final String FILE_SUFFIX = ".json";

    private static volatile Config sCache;
    private static volatile long sCacheAt;
    private static volatile String sChannel;
    private static volatile boolean sWarned;
    private static volatile Context sAppCtx;

    /** 旧版散落文件的目录扫描结果缓存：避免每 2 秒在 hook 线程上枚举整个 Download */
    private static volatile java.util.List<File> sLegacyCache;
    private static volatile long sLegacyAt;
    private static final long LEGACY_SCAN_MS = 30000L;

    /** 定位伪造开关 */
    public boolean enabled;
    /** 目标纬度（GCJ-02） */
    public double lat;
    /** 目标经度（GCJ-02） */
    public double lng;
    /** 漂移半径，单位米 */
    public double radius;
    /** 详细地址（门牌/楼名），拼在省市区街道后面 */
    public String detail = "";
    /** 城市覆盖，空串表示不覆盖 */
    public String city = "";
    /** 省覆盖 */
    public String province = "";
    /** 区覆盖 */
    public String district = "";
    /** 街道覆盖 */
    public String street = "";
    /** 设备标识覆盖开关 */
    public boolean deviceEnabled;
    /** 覆盖后的设备标识 */
    public String deviceId = "";
    /** 调试日志开关 */
    public boolean debug;
    /** 是否同时伪造系统定位（android.location.Location） */
    public boolean systemLocation = true;
    /** 是否把 root 检测固定成 false */
    public boolean rootHook = true;
    /** 是否请求回传一次真实定位 */
    public boolean capture;
    /** 回传请求的时间戳（毫秒），过期作废 */
    public long captureTs;
    /** 是否已有可用的目标坐标 */
    public boolean hasTarget;

    /** signature() 的懒计算结果；Config 构造完成后字段不再变化 */
    private volatile String sig;

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
        // 配置对象创建后不再修改，签名只算一次；这个方法在每次 getter 里都会被调用，
        // 每次都 String.format 会在定位热路径上产生无谓的分配
        String s = sig;
        if (s == null) {
            s = String.format(Locale.US, "%b|%.7f|%.7f|%.2f|%s|%s|%s|%s|%s",
                    enabled, lat, lng, radius, detail, city, province, district, street);
            sig = s;
        }
        return s;
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
        // 正常路径只有 /sdcard/Download/ejiahook/config.json 一个文件。
        // 先直接读它，读不到才去枚举目录找旧版散落文件——枚举在 hook 线程上很贵。
        File primary = new File(CONFIG_FILE_PATH);
        Map<String, Object> map = readJson(primary);
        if (map != null) {
            noteChannel("debug-file " + primary.getAbsolutePath());
            return map;
        }
        for (File file : legacyFiles()) {
            map = readJson(file);
            if (map != null) {
                noteChannel("debug-file " + file.getAbsolutePath());
                return map;
            }
        }
        File sdcard = new File("/sdcard/ejiahook.json");
        map = readJson(sdcard);
        if (map != null) {
            noteChannel("debug-file " + sdcard.getAbsolutePath());
            return map;
        }
        return null;
    }

    /** 读一个 JSON 配置文件；不存在、不可读、空文件、格式错误都返回 null。 */
    private static Map<String, Object> readJson(File file) {
        try {
            if (!file.isFile() || !file.canRead()) {
                return null;
            }
            String text = readAll(file);
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            JSONObject json = new JSONObject(text);
            Map<String, Object> map = new HashMap<>();
            for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                String key = it.next();
                map.put(key, json.get(key));
            }
            return map.isEmpty() ? null : map;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 旧版散落在 Download 根目录的 ejiahook*.json，按修改时间倒序；结果缓存 30 秒。 */
    private static java.util.List<File> legacyFiles() {
        long now = SystemClock.elapsedRealtime();
        java.util.List<File> cached = sLegacyCache;
        if (cached != null && now - sLegacyAt < LEGACY_SCAN_MS) {
            return cached;
        }
        java.util.List<File> legacy = new java.util.ArrayList<>();
        try {
            File dir = new File(DOWNLOAD_DIR);
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    String name = f.getName();
                    if (f.isFile() && name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
                        legacy.add(f);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        legacy.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        sLegacyCache = legacy;
        sLegacyAt = now;
        return legacy;
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
        c.radius = parseDouble(getStr(map, KEY_RADIUS), 10.0);
        if (c.radius < 0) c.radius = 0;
        if (c.radius > 5000) c.radius = 5000;
        String latText = getStr(map, KEY_LAT);
        String lngText = getStr(map, KEY_LNG);
        c.lat = parseDouble(latText, 0);
        c.lng = parseDouble(lngText, 0);
        c.detail = nullToEmpty(getStr(map, KEY_DETAIL));
        c.city = nullToEmpty(getStr(map, KEY_CITY));
        c.province = nullToEmpty(getStr(map, KEY_PROVINCE));
        c.district = nullToEmpty(getStr(map, KEY_DISTRICT));
        c.street = nullToEmpty(getStr(map, KEY_STREET));
        c.deviceEnabled = getBool(map, KEY_DEVICE_ENABLED, false);
        c.deviceId = nullToEmpty(getStr(map, KEY_DEVICE_ID));
        c.debug = getBool(map, KEY_DEBUG, false);
        c.systemLocation = getBool(map, KEY_SYSTEM_LOCATION, true);
        c.rootHook = getBool(map, KEY_ROOT_HOOK, true);
        c.capture = getBool(map, KEY_CAPTURE, false);
        c.captureTs = (long) parseDouble(getStr(map, KEY_CAPTURE_TS), 0);
        // 用“字段是否填写 + 数值是否合法”判断，而不是拿 (0,0) 当“未设置”的哨兵
        // ——(0,0) 本身是合法坐标，不能因为填了赤道/本初子午线就当成没填
        boolean latOk = !isEmpty(latText) && c.lat >= -90 && c.lat <= 90;
        boolean lngOk = !isEmpty(lngText) && c.lng >= -180 && c.lng <= 180;
        c.hasTarget = latOk && lngOk;
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
            double v = Double.parseDouble(s.trim());
            // NaN / Infinity 会让后续比较全部失效，直接当没填
            return Double.isFinite(v) ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 在被注入的进程里拿 Application：优先 ActivityThread，兜底 AppGlobals。 */
    static Context appContext() {
        Context cached = sAppCtx;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Application app = (Application) m.invoke(null);
            if (app != null) {
                sAppCtx = app;
                return app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Method m = ag.getDeclaredMethod("getInitialApplication");
            m.setAccessible(true);
            Application app = (Application) m.invoke(null);
            if (app != null) {
                sAppCtx = app;
            }
            return app;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
