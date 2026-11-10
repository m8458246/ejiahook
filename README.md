# ejiahook

给「神州e家」（`com.dcits.ehome`）用的 LSPosed 模块，思路参考 chuichui，但 hook 点按 ejia 的实际情况重做了。

## 为什么不是照抄 chuichui

| | chuichui | ejia（本模块） |
|---|---|---|
| 定位 SDK | 高德 AMap（`com.amap.api.location.AMapLocationClient`） | 百度（`com.baidu.location.*`，`liblocSDK8b.so`） |
| 入口 | `AMapLocationClient#setLocationListener` 包一层 Proxy | `BDLocation` 的坐标 getter |
| 坐标 getter | `AMapLocation#getLatitude/getLongitude` | 混淆过：`K()` / `Q()`，setter 是 `j1(double)` / `p1(double)` |
| 地址/城市 | `getAddress/getCity` 等一整套 setter | `d()`（地址）/ `i()`（城市） |
| 漂移 | 每次回调在 ±5m 内随机 | 同样随机，但按 BDLocation 实例缓存，纬度经度不会错位 |
| 设备标识 | 未处理 | 可选覆盖，默认透传真实值 |

ejia 的调用点（反编译核实）：

- `com.dcits.ehome.plugin.Geolocation`（Cordova 插件）：
  `K()` 纬度、`Q()` 经度、`i()` 城市、`d()` 地址 → 回给 H5
- `com.dcits.ehome.dsbridge.DsBridgeWebViewActivity#hanndleLocationData`：同上
- `com.dcits.ehome.util.BaiduMapUtils`：`LocationClient#y0(BDLocationListener)` 注册回调

## Hook 了什么

**定位**

- `com.baidu.location.BDLocation#K()` 纬度
- `com.baidu.location.BDLocation#Q()` 经度
- `com.baidu.location.BDLocation#d()` 地址（填了才覆盖）
- `com.baidu.location.BDLocation#i()` 城市（填了才覆盖）

读坐标时算出「目标点 + 半径内随机漂移」，并用 `j1`/`p1` 写回 BDLocation 对象本身，
因此同一对象上的其它读取方看到的也是伪造坐标；同一个对象反复读不会抖。

**设备标识**（默认全部透传真实值，只有开关打开且填了值才替换）

- `TelephonyManager#getDeviceId / getImei / getMeid / getSubscriberId / getSimSerialNumber`（含带 `int` 槽位的重载）
- `Settings.Secure#getString(cr, "android_id")`、`Settings.System#getString`
- `Build#getSerial`
- `WifiInfo#getMacAddress`（仅当填的值是 MAC 格式时才替换）

这几条覆盖了 ejia 里 `PhoneInfoUtils`、`DeviceUtils`、`DeviceUtil`、`DeviceInfo` 的取号路径。

## 配置

模块 App 里填，保存后约 2 秒内生效，不需要重启 ejia：

- 启用定位伪造 + 纬度/经度（GCJ-02）+ 漂移半径（米，默认 5，0 表示不动）
- 城市 / 地址（可选，留空保留真实值；填了以后即使真机没信号也能给出“成功”的定位）
- 启用设备标识覆盖 + 标识值（留空或关闭则用真实值）

配置走模块自己导出的只读 ContentProvider（`com.cycy.ejiahook.config`），
被注入的 ejia 进程 `ContentResolver.call` 读取，2 秒缓存。

## 编译

用 GitHub Actions（`.github/workflows/build.yml`）：push 到 `main` 自动出 debug + release APK，
产物在 Actions 的 `ejiahook-apk` artifact 里。

## 安装

1. 装 APK
2. LSPosed 里启用模块，作用域勾选「神州e家」（`com.dcits.ehome`）
3. 强行停止并重开 ejia
4. 打开「e家定位」填坐标 → 保存

## 注意

- 模块走 libxposed API 102，需要支持新 API 的 LSPosed（本机 v2.2.1 可以）
- 百度 SDK 在本 APK 里被混淆，方法名 `K/Q/j1/p1/d/i` 是这一版 ejia 的名字；ejia 更新后若改了混淆映射，
  日志里会打 `BDLocation.K()/Q() 签名不符`，需要按新 APK 重新对一次
- 定位只改 Java 层，不走 Java 的 native 定位路径不在覆盖范围内
- 设备标识覆盖属于“改机器指纹”，个别服务端做设备校验的场景可能出现登录/风控异常，出问题就关掉
