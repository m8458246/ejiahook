package com.cycy.ejiahook;

import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 位置漂移：在目标坐标周围 radius 米内随机取点。
 *
 * <p>与 chuichui 的做法一致（每次回调随机偏移），区别是这里把偏移量
 * 缓存到 BDLocation 实例上，保证同一个定位对象反复读纬度/经度时得到同一结果，
 * 不会出现“纬度取了一次随机、经度又取另一次随机”的错位。</p>
 */
final class Drift {

    private static final double METERS_PER_DEG_LAT = 111320.0;
    private static final int MAX_ENTRIES = 512;

    private static final WeakHashMap<Object, Entry> CACHE = new WeakHashMap<>();

    private static final class Entry {
        final String signature;
        final double lat;
        final double lng;

        Entry(String signature, double lat, double lng) {
            this.signature = signature;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private Drift() {
    }

    /** 一次定位的目标点：fresh 表示这是该 BDLocation 实例第一次取值。 */
    static final class Target {
        final double lat;
        final double lng;
        final boolean fresh;

        Target(double lat, double lng, boolean fresh) {
            this.lat = lat;
            this.lng = lng;
            this.fresh = fresh;
        }
    }

    /**
     * 同一个 key（BDLocation 实例）在同一份配置下返回固定值。
     */
    static Target targetFor(Object key, Config c) {
        String sig = c.signature();
        synchronized (CACHE) {
            Entry e = CACHE.get(key);
            if (e != null && e.signature.equals(sig)) {
                return new Target(e.lat, e.lng, false);
            }
            double[] p = compute(c);
            if (CACHE.size() > MAX_ENTRIES) {
                // 只淘汰一个最老的条目，不能整体 clear：还活着的 BDLocation 对象
                // 如果被清掉，同一次定位里再读经纬度就会拿到新的随机点，坐标会抖
                java.util.Iterator<Object> it = CACHE.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            CACHE.put(key, new Entry(sig, p[0], p[1]));
            return new Target(p[0], p[1], true);
        }
    }

    /** 目标点相对配置中心的距离（米），用于调试日志确认漂移真的在变。 */
    static double meters(Config c, double lat, double lng) {
        double dLat = (lat - c.lat) * METERS_PER_DEG_LAT;
        double dLng = (lng - c.lng) * METERS_PER_DEG_LAT * Math.cos(Math.toRadians(c.lat));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    private static double[] compute(Config c) {
        double radius = c.radius;
        if (radius <= 0) {
            return new double[]{c.lat, c.lng};
        }
        // 圆盘内均匀采样
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double dist = radius * Math.sqrt(ThreadLocalRandom.current().nextDouble());
        double dLat = dist * Math.cos(angle) / METERS_PER_DEG_LAT;
        double cosLat = Math.cos(Math.toRadians(c.lat));
        if (Math.abs(cosLat) < 1e-6) cosLat = 1e-6;
        double dLng = dist * Math.sin(angle) / (METERS_PER_DEG_LAT * cosLat);
        return new double[]{c.lat + dLat, c.lng + dLng};
    }
}
