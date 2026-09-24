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

1. **只有从旧版（未固定签名的构建）升级时才需要先卸载一次**：
   `/system/bin/pm uninstall com.lmq.coloros.subtitle`
   固定签名之后，后续构建可直接覆盖安装。
2. 安装 `artifacts/` 里的 APK。
3. LSPosed 中启用模块，作用域勾选「AI 语音摘记」与「Atlas」（`com.oplus.atlas`）。
4. 重启设备（或分别强制停止并重启这两个 App）。

## 构建与签名

签名密钥固定：keystore **不进仓库**，以仓库 secrets 保存，CI 构建时还原。

| secret | 用途 |
|---|---|
| `KEYSTORE_BASE64` | keystore（PKCS12）的 base64 |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | `coloros-unlock` |
| `KEY_PASSWORD` | key password |

`app/build.gradle` 由环境变量 `KEYSTORE_PATH` 驱动 `signingConfigs.ci`；无这些变量时
回退到 AGP 默认 debug 签名（本地无密钥也能构建）。

证书 sha256：`57df9c0d999ea701131b4c1b3c9565c545102c030cea1db59645e4e47002f0bd`。
连续两次 CI 构建产出**字节相同**的 APK，可直接覆盖安装。

> keystore 本地备份：`~/tmp/coloros-unlock.keystore`，口令在 `~/tmp/ci-keystore/pw.txt`。
> 密钥只存在仓库 secrets 与本机 `~/tmp`，**丢了就只能换新密钥**（换密钥后需卸载重装一次）。

## 已知限制

- **字幕**：配额由云端 / 系统 AIUnit (`com.oplus.aiunit`) 判定，本模块只解除**客户端对限制的反应**。
  若云端在返回 `3000803` 后彻底停止下发识别结果，仅靠客户端模块无法恢复。
- **声音分轨**：只对 `mss-whitelist` 内、且属性含「非音乐类」位的 App 生效
  （如 bilibili、B站HD、优酷、学习通、百度网盘、网易慕课）。
  **不在白名单内的 App 仍不支持**——那需要改白名单数据（需 root）或 hook native，
  均超出本模块范围。