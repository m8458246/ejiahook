# LSPosed 新式模块（libxposed API 102）开发

## 1. 新旧两套 API 的区别

老模块（大量教程还在用）是 `de.robv.android.xposed.*`：

```java
public class Main implements IXposedHookLoadPackage {
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("类名", lpparam.classLoader, "方法名",
                XC_MethodReplacement.returnConstant(true));
    }
}
```

新模块（API 101/102）是 `io.github.libxposed.api.*`，特征：

- 模块入口继承 `XposedModule`，不再是 `IXposedHookLoadPackage`
- 用 `hook(Method).intercept(chain -> ...)` 链式 API，拿参数用 `chain.getArg(i)`，调原方法用 `chain.proceed()`
- 入口类不再写在 `assets/xposed_init`，而是 `META-INF/xposed/java_init.list`
- 模块元信息写在 `META-INF/xposed/module.prop`
- **targetApiVersion ≥ 102 时不能再调用老的 `de.robv` API**

判断框架支持哪个版本：`lspctl status`（LSPosed 自带 CLI）会打印 `API: 102`。

## 2. 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/.../ModuleMain.java          ← 入口
└── resources/META-INF/xposed/
    ├── java_init.list                ← 入口类全名，一行一个
    ├── module.prop                   ← 版本/能力声明
    └── scope.list                    ← 默认作用域（包名，一行一个）
```

`module.prop` 示例：

```properties
minApiVersion=101
targetApiVersion=102
autoHotReload=false
```

- `minApiVersion`：框架最低支持到哪个 API 才加载
- `targetApiVersion`：按哪个版本的行为跑（102 会禁用老 API）
- `autoHotReload`：热重载。**不建议随手开**，开了要在 `onHotReloaded` 里手动 unhook 旧句柄，否则重复 hook

`java_init.list`：

```
com.cycy.ejiahook.ModuleMain
```

`scope.list`：

```
com.dcits.ehome
```

`scope.list` 只是"默认建议作用域"，真正生效的是 LSPosed 里用户勾选的作用域（除非 module.prop 里 `staticScope=true` 锁死）。

## 3. Gradle 配置要点

```kotlin
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
}

android {
    packaging {
        resources {
            // 不加这句，META-INF/xposed/* 可能被合并策略吃掉
            merges += "META-INF/xposed/*"
        }
    }
}
```

- 依赖必须是 `compileOnly`：这些类由框架在运行时注入，打进 APK 会和框架自己的类冲突
- `META-INF/xposed/*` 必须原样进 APK，否则 LSPosed 认不出这是模块

## 4. 生命周期

```java
public class ModuleMain extends XposedModule {

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 进程里模块刚加载，此时只有 processName 等信息
        log(Log.INFO, TAG, "process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        // 目标包已就绪，能拿到 classLoader，通常在这里装 hook
        if (!"com.dcits.ehome".equals(param.getPackageName())) return;
        ClassLoader cl = param.getClassLoader();
        installHooks(cl);
    }
}
```

要点：

- `onPackageLoaded` 也有 `getDefaultClassLoader()`，但 `onPackageReady` 的 `getClassLoader()` 更准（考虑自定义 `AppComponentFactory`）
- 一个进程可能加载多个包，回调会多次触发 → 用静态标志位防重复安装
- 应用有多个进程（如 `:remote`）时，每个进程各触发一次，各装各的

## 5. Hook API 速查

```java
// 普通方法
hook(method).intercept(chain -> {
    Object self = chain.getThisObject();     // 静态方法这里是 null
    Object arg0 = chain.getArg(0);
    Object result = chain.proceed();          // 调原方法（或链上的下一个 hook）
    return result;                            // 返回值即最终结果
});

// 改参数再调
hook(method).intercept(chain -> chain.proceed(new Object[]{newArg0, arg1}));

// 换 this
hook(method).intercept(chain -> chain.proceedWith(newThis));

// 直接返回、不调原方法
hook(method).intercept(chain -> Boolean.FALSE);

// 指定优先级 / 异常模式 / 唯一 id
hook(method)
    .setPriority(PRIORITY_HIGHEST)
    .setExceptionMode(ExceptionMode.PASSTHROUGH)
    .setId("my_hook")
    .intercept(...);

// 调用原始实现（跳过所有 hook）
getInvoker(method).setType(Invoker.Type.ORIGIN).invoke(obj, args);
```

限制与注意：

- **接口方法 hook 不到**：`interface.onXxx` 上没有实现，hook 它不会拦截实现类的调用。要么 hook 调用点（`registerListener(...)`），要么 hook 实现类
- **私有方法可以 hook**，需要先 `setAccessible(true)`
- 构造函数用 `hook(constructor)`，返回值忽略
- 同一个方法多次 hook 会串成链，注意别重复安装

## 6. 模块与 App 之间的数据通道

模块跑在目标进程里，读不到自己 App 的私有目录。可选通道，按可靠性排序（本项目的实测结论）：

| 通道 | 说明 | 本项目结论 |
|---|---|---|
| 共享存储文件 | 模块 App 用 MediaStore 写 `/sdcard/Download/xxx/config.json`，目标进程直接读 | ✅ 最稳 |
| 广播 | 目标进程 `sendBroadcast` + `setPackage(模块包名)`，模块 App 收 | ✅ 稳（用于回传数据） |
| `getRemotePreferences(group)` | 框架提供的接口 | ⚠️ 本项目返回空 map，不可靠 |
| 模块 App 导出 ContentProvider | `ContentResolver.call` | ❌ 目标进程报 `Unknown authority` |

Android 11+ 还有包可见性限制：模块 App 要启动目标 App，`getLaunchIntentForPackage` 会返回 null，
必须在 manifest 里声明：

```xml
<queries>
    <package android:name="com.dcits.ehome" />
</queries>
```

## 7. 构建与签名

- **签名必须固定**。CI 每次用新生成的 debug keystore 会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
  而且卸载重装会清掉 LSPosed 里的启用状态和作用域，用户要重新勾一遍。
  做法：仓库里放一个 keystore，`signingConfig` 指向它
- 构建产物建议发到 GitHub Release，`https://github.com/<user>/<repo>/releases/download/latest/app-release.apk`
  可直接匿名下载（artifact 需要登录）
- 注意 Release 资产的 CDN 缓存：短时间内重复下载可能拿到旧包，加个 `?ts=$(date +%s)` 破缓存

## 8. 安装与生效

1. `pm install -r app-release.apk`
2. LSPosed 里启用模块 + 勾选目标应用
3. 强行停止目标应用再打开（配置类改动不需要重启，代码改动需要）

查看模块是否真的加载：

```bash
logcat -s LSPosedFramework:V | grep 模块加载
```

正常会看到类似：

```
(com.dcits.ehome)[com.cycy.ejiahook,EjiaHook,...] 模块加载: process=com.dcits.ehome framework=LSPosed api=102 remote=true
```
