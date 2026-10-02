# 项目总览与产物（README）

> 项目：ColorOS 16 逆向 —— 解除系统 AI 音频功能的**客户端限制**
> 模块：LSPosed 模块（本仓库），一个 APK 覆盖两个作用域

## 项目

| 功能 | 目标 App | 限制 | 状态 |
|---|---|---|---|
| 字幕每月 120 分钟 | `com.coloros.accessibilityassistant`（AI 语音摘记） | 云端状态码 `3000803` → 客户端停字幕 | 模块已实现，**真机已验证** |
| 声音分轨限名单 App | `com.oplus.smartmediacontroller`（声音分轨） | native `mss-whitelist`（`mmlistservice` 解析 XML） | 模块已实现（`system_server` 内按设置页勾选扩名单 + 按勾选清 bit4 + `ctl.restart mmlistservice`），**真机已验证**（v1.10 的全量追加路线） |

- 被逆向的目标 APK 在**上一层目录**：`../AI 语音摘记_16.3.12.apk`、`../声音分轨_16.1.20.apk`
- 模块形态：LSPosed / Xposed（Java hook）；运行环境：ColorOS 16，已 root（KernelSU）+ LSPosed
- **模块设置页（UI）**：设计、prefs 契约与生效链路见 `06-module-ui.md`
- 工程参数、构建与 CI、逆向工具链见 `development.md`
- **当前状态、验证步骤与回滚方式统一见 `testing.md`**（本页不复述，避免两处漂移）

## 模块作用域

以源码 `app/src/main/res/values/arrays.xml` 的 `xposed_scope` 为准；
设计说明与「为什么只有这两项」见 `02-module-design.md` §作用域。

## 产物

- 模块源码仓库：<https://github.com/LMQ00/coloros-subtitle-unlock> （public）
- 已编译 APK：`artifacts/coloros-subtitle-unlock-v1.10.apk`（debug 签名；字幕解锁 + 分轨**全量追加式**
  白名单扩展（含无条件 bit4 清零）；证书 sha256 `57df9c0d…`，固定签名）
- **v1.11（模块设置页 + 手动白名单）**：`artifacts/coloros-subtitle-unlock-v1.11.apk`（debug 签名，证书 sha256 同上；
  体积 5.4 MB —— 引入了 androidx/material，理由与被否决方案见 `06-module-ui.md` §技术选型，
  体积/multidex 影响见 `development.md` §依赖与体积）
- 历史版本：`artifacts/` 内 v1.2–v1.10（仅追溯用；**v1.2 为旧签名**，与 v1.3+ 不能互相覆盖，
  详见 `03-pitfalls.md` §16）。v1.5–v1.8 曾走「Atlas/SMC 注入 `mss_music_only=0`」路线，已废弃
  （见 `05-stem-any-app.md`）。

## 下一步

1. 改代码 → push `main` → GitHub Actions 自动编译（产物用 `gh run download` 取回）。
2. **要放行某个 App 的分轨：在模块设置页勾选它** —— v1.11 起不再自动放行全部 App，
   新装 App 默认不放行（见 `06-module-ui.md`、`05-stem-any-app.md`）。
3. 真机日志：`logcat -s ColorOSSubtitleUnlock`（模块自身）与
   `grep -a ColorOSSubtitleUnlock /data/adb/lspd/log/modules_*.log`（LSPosed 侧）。
