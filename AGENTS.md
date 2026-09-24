# AGENTS

本目录是 coloros-ai-audio 项目的**模块工程**（LSPosed 模块源码，推送 GitHub 编译）。

## 先读

项目规则（硬性约束、语言风格、反编译/构建操作、沟通方式、文档同步）**以 `../AGENTS.md` 为准**——
开始任何改动前先读它。本文件只列本目录专属的事实，不重复规则。

## 本目录专属

- **入口**：`app/src/main/assets/xposed_init` → `com.lmq.coloros.subtitle.MainHook`
- **作用域**：`app/src/main/res/values/arrays.xml` 的 `xposed_scope`
  （`com.coloros.accessibilityassistant` 字幕；`com.oplus.atlas` 分轨）
- **构建**：Gradle + AGP 8.5.2 / Java 17 / compileSdk 35；
  CI `.github/workflows/build.yml`，push `main` → `gradle assembleDebug`
- **文档**：`docs/`（唯一文档源，索引见 `docs/README.md`）

## 文档索引

| 路径 | 何时读 |
|---|---|
| `docs/README.md` | 接手、总览两个功能与状态 |
| `docs/01-reverse-notes.md` | 字幕逆向结论与证据链 |
| `docs/02-module-design.md` | 字幕 hook 设计与作用域 |
| `docs/03-pitfalls.md` | 踩坑记录；新一轮逆向开始前 |
| `docs/04-stem-separation.md` | 分轨逆向结论与 hook 设计 |