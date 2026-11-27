# 安卓版（APK）

直接下载安装：

> https://github.com/xfr1688/pp360-ble/releases/download/latest/pp360-ble.apk

每次推代码到 `android/`，GitHub Actions 会自动重编一版并覆盖这个链接里的文件。

## 安装

1. 把 APK 传到手机（微信 / QQ / 数据线都行）
2. 点击安装，弹“未知来源”提示时先允许
3. 最低 Android 6.0（API 23），Android 12 以上会多要一个“附近设备”权限

## 使用

1. 短按云台电源键，灯亮
2. 打开 App，点「连接云台」，同意蓝牙权限
3. 弹窗里选 **TL-6C**（列表里 TL- 开头的排最前）
4. 「进一步 20°」走一格；「◉ 连续旋转」一直转，再点一次停

## 代码结构

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/xfr1688/pp360/BleLink.java` | 扫设备 / 建 GATT 链路 / 发 17 字节帧，回调统一切主线程 |
| `app/src/main/java/com/xfr1688/pp360/MainActivity.java` | 界面、角度状态、步进节流、连续旋转开关 |

不改协议层只改界面的话，动 `MainActivity` 就够。

## 自己编译

需要 JDK 17 + Android SDK 34：

```
cd android
gradle :app:assembleDebug
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

本机没有 Android SDK 也能出包：推一下代码，去 Actions 看构建结果，Release 里直接拿。

## 和网页版的区别

网页版靠 Web Bluetooth，只有 Chrome / Edge 支持，iOS 和不少国产浏览器直接不可用；APK 走原生 BLE，任何安卓机都能用，也不依赖 GitHub Pages。
