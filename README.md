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
- 省 / 市 / 区 / 街道（留空 = 保留真实值）
  - 详细地址（可选，拼在街道后面）
  - `d()`（完整地址）现在由「省+市+区+街道+详细地址」自动拼出来，不需要再单独维护一份，避免两处不一致
  - H5 拿地址有两条路：Cordova 插件读 `d()`，dsbridge 读 `c0()+i()+r()+k0()`，同一份输入两边都一致
- 漂移半径默认 10 米，`debug:true` 时日志会打 `偏移 x.xm`，可用来确认每次定位确实不一样
- 启用设备标识覆盖 + 标识值（不勾选覆盖时输入框显示真实值，方便复制；每次回到界面都会重新读取真实值）

读取顺序（先拿到先用）：

1. `/sdcard/Download/ejiahook/config.json`（模块 App 保存时写这里，root 调试也改这个）
2. `/sdcard/ejiahook.json`（纯 root 调试用，优先级低于上一条的备选）
3. LSPosed remote preferences
4. 模块导出的只读 ContentProvider（`com.cycy.ejiahook.config`，部分 ROM 会拦）

为什么优先文件：实测 ejia 进程里 `ContentResolver.call` 会报 `Unknown authority`，
LSPosed 的 remote prefs 读回来也是空的，只有共享存储这条路稳定。

## 获取真实定位（避免改得太离谱）

界面上有「获取 ejia 当前真实定位」：

1. 先**取消勾选**「启用定位伪造」
2. 点按钮 → 自动打开「神州e家」
3. ejia 读取一次定位后，模块把真实经纬度/城市/地址通过广播回传，自动填进输入框
4. 回到模块微调（比如改到考勤点附近）再勾选保存

## 定位日志

每次 ejia 拿到一次新定位，就往 `/sdcard/Download/ejiahook/location.log` 记一行，新的在最上面：

```
11-11 10:23:45  25.064466,102.703055  偏移 6.0m
11-11 10:22:31  25.064571,102.702955  偏移 9.5m
```

用来观察漂移：每行都是独立一次定位，坐标和偏移都应该不一样。

## 调试

写一个 JSON 就能改配置，不用碰 UI：

```bash
cat > /data/local/tmp/ejiahook.json <<'EOF'
{"enabled":true,"lat":37.164507,"lng":119.753018,"radius":5,"debug":true}
EOF
chmod 644 /data/local/tmp/ejiahook.json
```

`debug: true` 时 logcat（tag `EjiaHook`）会输出：

- `配置来源: debug-file ...`
- `定位替换 K: 原值=xx -> 37.xxx,119.xxx`
- `H5 调用定位插件: action=getCurrentPosition`
- `H5 收到 code=0 body={"city":"...","address":"...","lat":..,"lng":..}`

注意 `/data/local/tmp` 属于 `shell_data_file`，普通应用读不到，需要把文件 SELinux 上下文改成目标应用能读的（`chcon u:object_r:app_data_file:s0:cXX,c257,c512,c768`），或者直接放到 `/sdcard`。


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
