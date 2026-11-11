package com.cycy.ejiahook;

import android.location.Location;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 系统定位兜底：把 {@code android.location.Location} 的经纬度 getter 也改成伪造值。
 *
 * <p>注意这**不是**注入 system_server。hook 仍然只装在 ejia 自己的进程里，
 * 只是目标类是框架类 android.location.Location，所以 ejia 进程内任何读系统定位的代码
 * （百度 SDK 的 GPS 输入、WebView 定位、第三方 SDK）拿到的都是伪造坐标。
 * 其它应用完全不受影响，LSPosed 作用域也不用勾「系统框架」。</p>
 *
 * <p>风险：百度 SDK 会把这个 GPS 输入和它自己的 WiFi/基站结果做融合，输入被改后
 * 它可能算出更接近目标点的位置（地址反而更一致），也可能判为异常而忽略。真出问题时
 * 把配置里的 {@code system_location} 关掉即可，BDLocation 那层 hook 不受影响。</p>
 */
final class SystemLocationHooks {

    private static final String TAG = "EjiaHook";

    private SystemLocationHooks() {
    }

    static void install(XposedModule module) {
        int count = 0;
        count += hook(module, "getLatitude", true) ? 1 : 0;
        count += hook(module, "getLongitude", false) ? 1 : 0;
        module.log(Log.INFO, TAG, "系统定位 hook 安装完成，共 " + count + " 个");
    }

    private static boolean hook(XposedModule module, String name, final boolean isLat) {
        try {
            Method method = Location.class.getDeclaredMethod(name);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Config c = Config.get();
                if (!c.enabled || !c.hasTarget || !c.systemLocation) {
                    return chain.proceed();
                }
                Object self = chain.getThisObject();
                if (self == null) {
                    return chain.proceed();
                }
                Drift.Target target = Drift.targetFor(self, c);
                return isLat ? target.lat : target.lng;
            });
            return true;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook Location." + name + " 失败: " + t);
            return false;
        }
    }
}
