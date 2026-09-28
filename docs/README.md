# 文档与产物索引（README）

> 项目：ColorOS 16 逆向 —— 解除系统 AI 音频功能的客户端限制
> 模块：LSPosed 模块（本仓库），一个 APK 覆盖两个作用域

## 文件

各文档的职责与「何时读」见 **`../AGENTS.md` §文档索引**（唯一出处，本页不复述）。

## 两个功能与状态

**当前状态、验证步骤与回滚方式统一见 `testing.md`**（本页不复述，避免两处漂移）。

## 模块作用域

以源码 `app/src/main/res/values/arrays.xml` 的 `xposed_scope` 为准；
设计说明与「为什么只有这两项」见 `02-module-design.md` §作用域。

## 产物

- 模块源码仓库：https://github.com/LMQ00/coloros-subtitle-unlock （public）
- 已编译 APK：`../artifacts/coloros-subtitle-unlock-v1.10.apk`（debug 签名；含字幕解锁 + 分轨**追加式白名单**
  扩展（含 bit4 清零）；证书 sha256 `57df9c0d…`，固定签名）
- 历史版本：`../artifacts/` 内 v1.2–v1.9（仅追溯用；**v1.2 为旧签名**，与 v1.3+ 不能互相覆盖，
  详见 `03-pitfalls.md` §16）。v1.5–v1.8 曾走「Atlas/SMC 注入 `mss_music_only=0`」路线，已废弃
  （见 `05-stem-any-app.md`）。

## 下一步

两个功能均已在真机验证（见 `交接文档.md`）。日常维护：

1. 改代码 → push `main` → GitHub Actions 自动编译（产物用 `gh run download` 取回）。
2. 装了新 App 后无需操作：模块每次开机重写白名单并重启 `mmlistservice`。
3. 真机日志：`logcat -s ColorOSSubtitleUnlock`（模块自身）与
   `grep -a ColorOSSubtitleUnlock /data/adb/lspd/log/modules_*.log`（LSPosed 侧）。