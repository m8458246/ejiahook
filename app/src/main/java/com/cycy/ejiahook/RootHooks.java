package com.cycy.ejiahook;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * root 检测兜底。
 *
 * <p>ejia 里现有的 root 判断都很弱，而且结果会通过 {@code CCUtils#getDeviceInfo}
 * 上报给 H5（字段 {@code isRoot}）。目前它在 ejia 进程里读到的是 false，
 * 但一旦 KernelSU 的挂载策略变化（比如 /system/bin/su 对该应用可见），
 * 这个值就会翻成 true，可能触发服务端策略。这里统一固定成 false。</p>
 *
 * <p>涉及的判断点（反编译确认）：</p>
 * <ul>
 *   <li>com.dcits.ehome.fingerprint.FingerPrintUtils#isRoot —— 查 5 个路径下有没有 su</li>
 *   <li>com.cloudcore.fpaas.common.utils.DeviceUtil#isRoot —— Runtime.exec("su")</li>
 *   <li>com.cloudcore.venus.utils.DeviceUtils#isRoot —— 同上</li>
 *   <li>com.cloudcore.fpaas.common.utils.ShellUtil#checkRootPermission —— 真执行 su</li>
 *   <li>com.cloudcore.venus.utils.ShellUtils#checkRootPermission</li>
 *   <li>cn.cloudcore.iprotect.plugin.CKbdJniLib#isRoot —— 安全键盘的 native 判断</li>
 * </ul>
 */
final class RootHooks {

    private static final String TAG = "EjiaHook";

    private static final String[][] TARGETS = {
            {"com.dcits.ehome.fingerprint.FingerPrintUtils", "isRoot"},
            {"com.cloudcore.fpaas.common.utils.DeviceUtil", "isRoot"},
            {"com.cloudcore.venus.utils.DeviceUtils", "isRoot"},
            {"com.cloudcore.fpaas.common.utils.ShellUtil", "checkRootPermission"},
            {"com.cloudcore.venus.utils.ShellUtils", "checkRootPermission"},
            {"cn.cloudcore.iprotect.plugin.CKbdJniLib", "isRoot"},
    };

    private RootHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        int count = 0;
        for (String[] target : TARGETS) {
            count += hookFalse(module, cl, target[0], target[1]) ? 1 : 0;
        }
        module.log(Log.INFO, TAG, "root 检测 hook 安装完成，共 " + count + " 个");
    }

    private static boolean hookFalse(XposedModule module, ClassLoader cl, String className, String methodName) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = cls.getDeclaredMethod(methodName);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> Boolean.FALSE);
            return true;
        } catch (Throwable t) {
            // 类或方法不存在很正常（版本差异），不当作错误
            return false;
        }
    }
}
