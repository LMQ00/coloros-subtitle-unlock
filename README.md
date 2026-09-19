# ColorOS AI 语音摘记 · 字幕解锁 (LSPosed 模块)

解除 ColorOS 16「AI 语音摘记」(`com.coloros.accessibilityassistant`) **开启字幕**功能的
**每月 120 分钟**时长限制。

> 逆向对象：`AI 语音摘记_16.3.12.apk`（versionCode 1603012）

## 原理

逆向结论（见 `doc/`）：

1. 云端 ASR 通过 AIUnit 下发错误码 `3000803`（"月额度已达限"）。
2. `com.coloros.translate.engine.asr.asrclient.h#e` 将其映射为 `e4.c.ASR_MONTHLY_LIMIT_REACHED(-2020)`。
3. 引擎分发器 `com.coloros.translate.engine.asr.s#onResultStatus` 把状态转发给各 WorkManager。
4. `GlobalSubtitleWorkManager`（混淆类 `g0`）停止字幕并弹「已达上限」。

本模块在三个层面丢弃限制状态码，并把「本月剩余时长」改写为极大值。

## 构建

本项目通过 **GitHub Actions** 编译（本地不编译）：

- 推送到 `main` 触发 `.github/workflows/build.yml`
- 产物：Actions 页面 → `coloros-subtitle-unlock-apk` 工件（debug 签名，可直接安装）

## 安装

1. 安装 APK。
2. LSPosed 中启用模块，作用域勾选「AI 语音摘记」。
3. 强制停止并重启「AI 语音摘记」。

## 已知限制

配额由**云端 / 系统 AIUnit (`com.oplus.aiunit`)** 判定，本模块只解除**客户端对限制的反应**。
若云端在返回 `3000803` 后彻底停止下发识别结果，则仅靠客户端模块无法恢复——
那需要 hook 系统 AIUnit 应用（超出本模块范围）。