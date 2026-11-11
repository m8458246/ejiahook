# ejiahook：项目架构与 Hook 点

## 1. 目标 App 的定位链路（反编译 + 日志确认）

```
H5 考勤页（喔趣，远端）
   ├─ Cordova 插件 com.dcits.ehome.plugin.Geolocation#getCurrentPosition
   └─ dsbridge com.dcits.ehome.dsbridge.DsBridgeWebViewActivity#getCurrentLocation
          ↓
   com.dcits.ehome.util.BaiduMapUtils#initOnceSite
          ↓
   com.baidu.location.LocationClient（百度 SDK，liblocSDK8b.so）
          ↓
   BDLocation（混淆过：K()/Q() 经纬度，d()/i()/c0()/r()/k0() 地址各段）
          ↓
   回给 H5：{"address":"省市区街道","latitude":..,"longitude":..}
```

关键结论：**地址和坐标是两个独立字段**。百度按真实位置做了逆地理编码，我们只改了坐标数字，
所以会出现"坐标在青岛、地址显示昆明"的现象——这不是 bug，是必然结果。

## 2. 为什么不能照抄 chuichui

| | chuichui（钉钉用） | ejia（本项目） |
|---|---|---|
| 定位 SDK | 高德 `AMapLocationClient` | 百度 `com.baidu.location.*` |
| 切入点 | `setLocationListener` 包一层 Proxy | `BDLocation` 的 getter |
| 坐标 getter | `getLatitude()/getLongitude()` | 混淆成 `K()/Q()`，setter `j1(double)/p1(double)` |
| 地址 | `getAddress()/getCity()` 等 | `d()` 完整地址、`i()` 市、`c0()` 省、`r()` 区、`k0()` 街道 |
| WiFi/基站 | 伪造扫描列表 | 未做（当前用不上） |

混淆名的推导方法见 `05-逆向速查.md`。

## 3. Hook 清单

### 3.1 定位（主）

```
com.baidu.location.BDLocation#K()   double 纬度
com.baidu.location.BDLocation#Q()   double 经度
com.baidu.location.BDLocation#d()   完整地址（由省市区街道+详细地址自动拼）
com.baidu.location.BDLocation#i()   市
com.baidu.location.BDLocation#c0()  省
com.baidu.location.BDLocation#r()   区
com.baidu.location.BDLocation#k0()  街道
```

做法不是"只改返回值"，而是**改完顺手用 setter 写回对象**：

```java
Drift.Target target = Drift.targetFor(self, c);   // 按实例缓存，保证 K/Q 一致
setLat.invoke(self, target.lat);                  // j1(double)
setLng.invoke(self, target.lng);                  // p1(double)
return isLat ? target.lat : target.lng;
```

这样同一对象上其它读取方（地图 SDK、后续 getter）看到的也是伪造值；即使 SDK 之后又用真实值覆盖，
下次读取也会重新纠正（幂等写回）。

### 3.2 定位（兜底）

```
android.location.Location#getLatitude()
android.location.Location#getLongitude()
```

只装在目标进程内（不是注入 system_server），作用是兜住不走百度的路径：WebView 的
`navigator.geolocation`、第三方 SDK 直接读 `LocationManager`。

可开关：配置项 `system_location`（App 里是「同时伪造系统定位」勾选框），默认开。

### 3.3 设备标识（可选，默认全部透传真实值）

```
TelephonyManager#getDeviceId/getImei/getMeid/getSubscriberId/getSimSerialNumber（含 int 槽位重载）
Settings.Secure#getString(cr,"android_id") / Settings.System#getString
Build#getSerial
WifiInfo#getMacAddress（仅当填的值是 MAC 格式）
```

一共 14 个 hook 点。为什么要覆盖这么多：目标 App 有三套取号逻辑
（`PhoneInfoUtils`、`venus.DeviceUtils`、`fpaas.DeviceUtil`），路径各不相同，且
Android Q 以上 `getImei()` 返回 null，最终都降级到 `android_id`。

### 3.4 加固

```
FingerPrintUtils#isRoot            （查 /system/bin/su 是否存在）
DeviceUtil#isRoot                  （Runtime.exec("su")）
DeviceUtils#isRoot
ShellUtil#checkRootPermission      （真执行 su -c echo root）
ShellUtils#checkRootPermission
CKbdJniLib#isRoot                  （安全键盘 native 判断）
```

统一返回 `false`。目标 App 会把 `isRoot` 通过 `getDeviceInfo` 上报给 H5，目前实测是 false，
但那是"运气好"（KernelSU 的 su 没挂到目标进程可见的位置），加固后不受挂载策略影响。

## 4. 漂移设计

要求：每次打卡坐标不能一样，但同一次定位里读多次不能抖。

```java
// 按 BDLocation 实例缓存偏移（WeakHashMap，自动回收）
static Target targetFor(Object key, Config c) {
    Entry e = cache.get(key);
    if (e != null && e.signature.equals(c.signature())) return new Target(e.lat, e.lng, false);
    double[] p = compute(c);       // 圆盘内均匀采样：r = R*sqrt(random)
    cache.put(key, new Entry(...));
    return new Target(p[0], p[1], true);   // fresh = 首次，用于写日志
}
```

- 同一实例多次读 → 同一个偏移（不会出现纬度一个随机、经度另一个随机）
- 新的一次定位回调 → 新实例 → 新偏移
- 配置变更（signature 变化）→ 缓存失效重算

## 5. 配置与数据通道

```
/sdcard/Download/ejiahook/
├── config.json      ← 配置（模块 App 用 MediaStore 写；root 也能直接改）
└── location.log     ← 定位日志，新的在最上面
```

读取顺序：

1. `/sdcard/Download/ejiahook/config.json`
2. 兼容旧版：`/sdcard/Download/ejiahook*.json`（按修改时间取最新）
3. `/sdcard/ejiahook.json`
4. LSPosed `getRemotePreferences("config")`
5. 模块导出的 ContentProvider

带 2 秒 TTL 缓存，避免每次定位都读文件。

`config.json` 字段：

```json
{
  "enabled": true,              // 启用定位伪造
  "lat": "25.064565",           // 目标纬度（GCJ-02）
  "lng": "102.7029591406657",   // 目标经度
  "radius": "10.0",             // 漂移半径（米）
  "province": "云南省",          // 省（留空 = 保留真实值）
  "city": "昆明市",
  "district": "五华区",
  "street": "教场东路",
  "detail": "",                 // 详细地址，拼在街道后面
  "device_enabled": false,      // 启用设备标识覆盖
  "device_id": "",
  "debug": false,               // logcat 详细日志
  "system_location": true,      // 同时伪造系统定位
  "capture": false,             // 请求回传一次真实定位
  "capture_ts": 0
}
```

## 6. 「获取真实定位」的实现

因为模块跑在目标进程里，能读到真实值，但模块 App 读不到。所以用广播回传：

```
模块 App：置 capture=true → 拉起 ejia
ejia 进程：BDLocation getter 里发现 capture=true 且未开启伪造
          → 把真实 lat/lng/省/市/区/街道/完整地址 打包广播
模块 App：收广播 → 填进输入框 → 把 capture 置回 false
```

只在 `enabled=false` 时回传，避免把伪造值当成真实值。
「详细地址」是拿完整地址减掉省市区街道算出来的，减不干净就留空，不会拼出重复串。

## 7. 日志

`location.log`，每次**新的**定位一行，新的在最上面，最多 300 行：

```
11-11 11:22:37  25.064534767894727,102.70296753156354  偏移 3.5m
11-11 11:14:37  25.064568,102.702976  偏移 1.7m
```

- 坐标用 `Double.toString`，和 H5 收到的 JSON 里数字写法完全一致，可逐字核对
- 只在 `enabled=true` 时记录（不伪造时没有"漂移"可言）
- 写文件走单线程队列，不阻塞定位回调
