# LSPosed 模块开发文档

这套文档是给「神州e家（com.dcits.ehome）定位模块 ejiahook」这个项目做总结时整理的，
内容都来自实际踩过的坑和实测结果，不是照抄官方文档。

## 目录

| 文件 | 内容 |
|---|---|
| `01-LSPosed-API102-模块开发.md` | 新式模块（libxposed API 102）的工程结构、清单文件、Gradle 配置、生命周期、Hook API |
| `02-本项目架构与Hook点.md` | ejiahook 的整体设计：Hook 点怎么选的、配置通道、日志、调试开关 |
| `03-踩坑与解决.md` | 这次真实踩到的坑，以及每个坑的现象、定位过程、最终解法 |
| `04-调试与验证手册.md` | logcat 用法、调试文件、如何证明「上报的就是日志里的值」 |
| `05-逆向速查.md` | apktool / jadx 常用命令，混淆类怎么反推字段语义 |
| `06-维护与升级.md` | 仓库/CI/出包、本地类型检查、App 更新后怎么重新对映射、加新 hook 的模板 |

## 一句话背景

目标 App 是 Cordova + X5 WebView 的混合应用，定位走百度 SDK，考勤页面是远端 H5（喔趣）。
模块要做的两件事：改定位（带随机漂移）、可选改设备标识。最终形态：

- 主 Hook：`com.baidu.location.BDLocation` 的经纬度/地址 getter
- 兜底 Hook：`android.location.Location` 的经纬度 getter（仅本进程）
- 可选：设备标识（IMEI/IMSI/android_id/序列号/MAC）
- 加固：root 检测统一返回 false

## 适用环境

- LSPosed 2.2.1（API 102 已支持），Android 14/15/16
- 模块用 Java + Gradle 8.9 + AGP 8.7.3 + compileSdk 35
- 构建在 GitHub Actions 上跑，本地只做 javac 类型检查
