# ColorOS AI 音频功能解锁 (LSPosed 模块)

解除 ColorOS 16 系统 AI 音频功能的客户端限制。当前两个功能：

| 功能 | 目标 App | 原理 |
|---|---|---|
| 字幕每月 120 分钟限制 | `com.coloros.accessibilityassistant`（AI 语音摘记） | 丢弃云端限制状态码 `3000803` → `-2020`，并改写「本月剩余时长」 |
| 声音分轨限音乐 App | `com.oplus.smartmediacontroller`（声音分轨） | 在 `com.oplus.atlas` 进程内让特性 `oplus.software.audio.mss_music_only` 判定为 false，使 Atlas 下发 `mss_music_only=0` |

> 逆向对象（在仓库上一层）：`../AI 语音摘记_16.3.12.apk`（versionCode 1603012）、
> `../声音分轨_16.1.20.apk`（versionCode 16001020）

## 原理

逆向结论见 `docs/`：

- **字幕**（`docs/01-reverse-notes.md`、`docs/02-module-design.md`）：
  云端 ASR 经 AIUnit 下发错误码 `3000803`（"月额度已达限"），
  `com.coloros.translate.engine.asr.asrclient.h#e` 将其映射为
  `e4.c.ASR_MONTHLY_LIMIT_REACHED(-2020)`，引擎分发器
  `com.coloros.translate.engine.asr.s#onResultStatus` 转发给各 WorkManager，
  `GlobalSubtitleWorkManager`（混淆类 `g0`）停止字幕并弹「已达上限」。
  模块在三个层面丢弃限制状态码，并把「本月剩余时长」改写为极大值。
- **声音分轨**（`docs/04-stem-separation.md`）：
  native 系统服务 `SpecailizerPLService` 的 `isVocalAdjustSupported(pkg)` 要求包名在
  `mss-whitelist`（XML）中且不受「仅音乐」开关限制；该开关由音频参数 `mss_music_only` 决定，
  而它是否被置 0 取决于设备特性 `oplus.software.audio.mss_music_only`
  （`OplusAtlasService` 初始化时判断）。模块让该特性判定为 false。

## 构建

本项目通过 **GitHub Actions** 编译（本地不编译）：

- 推送到 `main` 触发 `.github/workflows/build.yml`
- 产物：Actions 页面 → `coloros-subtitle-unlock-apk` 工件（debug 签名，可直接安装）

## 安装

1. **先卸载旧版**（每次 CI 构建的签名不同，不能覆盖安装）：
   `/system/bin/pm uninstall com.lmq.coloros.subtitle`
2. 安装 `artifacts/` 里的 APK。
3. LSPosed 中启用模块，作用域勾选「AI 语音摘记」与「Atlas」（`com.oplus.atlas`）。
4. 重启设备（或分别强制停止并重启这两个 App）。

## 构建与签名

CI（`.github/workflows/build.yml`）用 AGP 默认 debug 签名；runner 每次是全新的，
所以 **每次构建的签名证书都不同**，新旧构建之间无法覆盖安装，升级前必须卸载旧版。

若要免去「每次卸载 + 重选作用域」，需要固定签名密钥（把 debug keystore 放进仓库并在
`app/build.gradle` 里指定 `signingConfig`）——**尚未采用**，属待定事项。

## 已知限制

- **字幕**：配额由云端 / 系统 AIUnit (`com.oplus.aiunit`) 判定，本模块只解除**客户端对限制的反应**。
  若云端在返回 `3000803` 后彻底停止下发识别结果，仅靠客户端模块无法恢复。
- **声音分轨**：只对 `mss-whitelist` 内、且属性含「非音乐类」位的 App 生效
  （如 bilibili、B站HD、优酷、学习通、百度网盘、网易慕课）。
  **不在白名单内的 App 仍不支持**——那需要改白名单数据（需 root）或 hook native，
  均超出本模块范围。