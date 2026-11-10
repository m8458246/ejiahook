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

    /**
     * 返回 {lat, lng}。同一个 key（BDLocation 实例）在同一份配置下返回固定值。
     */
    static double[] targetFor(Object key, Config c) {
        String sig = c.signature();
        synchronized (CACHE) {
            Entry e = CACHE.get(key);
            if (e != null && e.signature.equals(sig)) {
                return new double[]{e.lat, e.lng};
            }
            double[] p = compute(c);
            if (CACHE.size() > MAX_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(key, new Entry(sig, p[0], p[1]));
            return p;
        }
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
