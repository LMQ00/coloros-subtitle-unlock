# 交接文档索引

> 项目：ColorOS 16 逆向 —— 解除系统 AI 音频功能的客户端限制
> 模块：LSPosed 模块（本仓库），一个 APK 覆盖两个作用域

## 文件

| 文件 | 回答什么问题 |
|---|---|
| `01-reverse-notes.md` | 「AI 语音摘记」字幕每月 120 分钟限制在哪判定、证据是什么 |
| `02-module-design.md` | 字幕解锁的 hook 设计（hook 哪些点、为什么） |
| `03-pitfalls.md` | 逆向与构建中踩过的坑、如何规避 |
| `04-stem-separation.md` | 「声音分轨」音乐应用限定的判定链、根因与 hook 设计 |
| `../AGENTS.md` | 工程约定（硬性约束、构建、作用域、文档同步） |
| `archive/` | 历史快照（旧版本文档），已冻结，不维护 |
| `../` | 模块源码（GitHub Actions 编译） |

## 两个功能与状态

| 功能 | 目标 App | 判定点 | 状态 |
|---|---|---|---|
| 字幕 120 分钟限制 | `com.coloros.accessibilityassistant` | 云端状态码 `3000803` → 客户端响应 | 模块已实现，待真机确认 |
| 声音分轨音乐限定 | `com.oplus.smartmediacontroller` | native `mss-whitelist` + `mss_music_only` 参数 | 模块已实现，待真机确认 |

## 模块作用域

`app/src/main/res/values/arrays.xml` 的 `xposed_scope`：

- `com.coloros.accessibilityassistant` —— 字幕限制
- `com.oplus.atlas` —— 分轨限制（`OplusAtlasService` 在此进程决定是否下发 `mss_music_only=0`）

## 产物

- 模块源码仓库：https://github.com/LMQ00/coloros-subtitle-unlock （public）
- 已编译 APK：`../artifacts/coloros-subtitle-unlock-v1.3.apk`（debug 签名，含字幕 + 分轨两个 hook）
- 历史版本：`../artifacts/coloros-subtitle-unlock-v1.2.apk`（仅字幕）

## 下一步

1. ~~实现 `MainHook` 的分轨分支~~ 已完成（`MainHook#hookMssMusicOnlyFeature`）。
2. push `main` 触发 GitHub Actions 编译，下载产物。
3. 真机：LSPosed 勾选两个作用域 → 重启 → bilibili 实测分轨 + 字幕。
4. 抓 `logcat -s ColorOSSubtitleUnlock` 确认 hook 命中。