# Android 版 Token 登录服务（NanoHTTPD + ZXing）

<p align="center">
  <img src="https://img.shields.io/github/v/release/qaz320621/kugou-token-server-android" />
  <img src="https://img.shields.io/github/downloads/qaz320621/kugou-token-server-android/total" />
</p>

酷狗扫码登录 + Token 管理（Android App），供手机版 MusicFree 插件拉取配置。

📥 下载 APK：[app-debug.apk（最新版）](https://github.com/qaz320621/kugou-token-server-android/releases/latest/download/app-debug.apk)（可直接安装）

## 依赖关系

```
父项目：kugou-musicfree-suite（本服务是其子项目）
├── 构建依赖：Android SDK + JDK；Gradle 依赖
│   org.nanohttpd:nanohttpd:2.3.1（HTTP 服务）、com.google.zxing:core:3.5.2（二维码）
└── 被谁依赖：kugou-musicfree-plugin（手机版插件访问 http://127.0.0.1:8765/token）
    —— 与 kugou-token-server-pc 功能等价，二选一
```

## 构建

```bash
# 需要 Android SDK + JDK
./gradlew assembleDebug       # 产出 app/build/outputs/apk/debug/app-debug.apk
```

依赖：`org.nanohttpd:nanohttpd:2.3.1`（HTTP 服务）、`com.google.zxing:core:3.5.2`（二维码生成）。

## 部署

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 启动（免点击，自动起服务）
adb shell am start -n com.chumc.tokenserver/.MainActivity --ez auto_start true
# PC 访问手机服务（可选）
adb forward tcp:8765 tcp:8765
```

## 使用

- **打开 App 即自动启动 Token 服务**（前台 Service），退到后台/桌面仍常驻；点「停止服务」可停
- 手机浏览器打开 `http://127.0.0.1:8765/` → 点「扫码登录」→ 手机酷狗 App 扫码确认
- token 自动存入 SharedPreferences，手机版 MusicFree 插件从 `http://127.0.0.1:8765/token` 拉取
- 包名：`com.chumc.tokenserver`，端口 8765
- **不做开机自启**（按需常驻，非默认自启）

## 接口

与 PC 版一致：`GET /login/qr`、`GET /login/status`、`GET /token`、`POST /save`、`GET /`

## 安全

- token 仅存于 App 本地 SharedPreferences，不入库
- 注意：`local.properties`（SDK 路径）不入库，构建前按本机环境生成
