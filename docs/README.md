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
| `05-stem-any-app.md` | 让任意 App 可分轨：**追加式白名单** + `ctl.restart mmlistservice`；含「不要动 `mss_music_only`」的实测教训 |
| `../AGENTS.md` | 工程约定（硬性约束、构建、作用域、文档同步） |
| `archive/` | 历史快照（旧版本文档），已冻结，不维护 |
| `../` | 模块源码（GitHub Actions 编译） |

## 两个功能与状态

**当前状态、验证步骤与回滚方式统一见 `testing.md`**（本页不复述，避免两处漂移）。

## 模块作用域

`app/src/main/res/values/arrays.xml` 的 `xposed_scope`：

- `com.coloros.accessibilityassistant` —— 字幕限制
- `android`（System Framework）—— 在 system_server 内追加式扩展分轨白名单并让 init 重启 `mmlistservice`

> v1.9 起删除了 `com.oplus.atlas` 与 `com.oplus.smartmediacontroller` 两项作用域：
> 它们注入 `mss_music_only=0`，会破坏分轨分离通路（见 `05-stem-any-app.md`）。

## 产物

- 模块源码仓库：https://github.com/LMQ00/coloros-subtitle-unlock （public）
- 已编译 APK：`../artifacts/coloros-subtitle-unlock-v1.10.apk`（debug 签名；含字幕解锁 + 分轨**追加式白名单**
  扩展（含 bit4 清零）；证书 sha256 `57df9c0d…`，固定签名）
- 上一版：`../artifacts/coloros-subtitle-unlock-v1.4.apk`（分轨仅 Atlas 内两条路径，需 Atlas 进程重建）
- 更早：`../artifacts/coloros-subtitle-unlock-v1.3.apk`（分轨仅主路径）
- 历史版本：`../artifacts/coloros-subtitle-unlock-v1.2.apk`（仅字幕，**旧签名**，与 v1.3/v1.4 不能互相覆盖）

## 下一步

两个功能均已在真机验证（见 `../docs/交接文档.md`）。日常维护：

1. 改代码 → push `main` → GitHub Actions 自动编译（产物用 `gh run download` 取回）。
2. 装了新 App 后无需操作：模块每次开机重写白名单并重启 `mmlistservice`。
3. 真机日志：`logcat -s ColorOSSubtitleUnlock`（模块自身）与
   `grep -a ColorOSSubtitleUnlock /data/adb/lspd/log/modules_*.log`（LSPosed 侧）。