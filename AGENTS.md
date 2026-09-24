# AGENTS

## Project

逆向 ColorOS 16 系统 AI 音频功能的**客户端限制**，编写 LSPosed 模块解除。当前两个目标：

| 功能 | 目标 App | 限制 | 状态 |
|---|---|---|---|
| 字幕每月 120 分钟 | `com.coloros.accessibilityassistant`（AI 语音摘记） | 云端状态码 `3000803` → 客户端停字幕 | 模块已实现，待真机确认 |
| 声音分轨限音乐 App | `com.oplus.smartmediacontroller`（声音分轨） | native `mss-whitelist` + `mss_music_only` 参数 | 模块已实现，待真机确认 |

- 被逆向的目标 APK 在**上一层目录**：`../AI 语音摘记_16.3.12.apk`、`../声音分轨_16.1.20.apk`
- 模块形态：LSPosed / Xposed（Java hook），作用域见 `docs/02-module-design.md`
- 运行环境：ColorOS 16，已 root + LSPosed

## 目录约定

- **本目录（`module/`）= 项目本体**：源码、构建配置、文档、产物，全在这里。
- **上一层目录只放被逆向的目标 APK**，不放其他东西（AGENTS、文档、构建产物都在本目录内）。
- `~/tmp/`：反编译/反汇编产物、下载的构建工具、临时文件。产物保留，不清理。

## Non-negotiables

每条均为可证伪约束；`[代码强制]` 表示有构建/配置层面兜底，`[提示词]` 表示靠代理遵守。

1. **禁止 root 权限操作**：不写 `/system_ext`、`/system`、`/odm`、`/data/oplus` 等系统路径，
   不做 Magisk overlay。`[提示词]`
2. **禁止 hook 系统框架 / native**：不碰 audioserver 等系统进程、不 patch `.so`、
   不引入 Zygisk。`[提示词]`
3. **禁止修改目标 App 数据目录**（`/data/data/<目标包名>`）。`[提示词]`
4. **只允许写本目录、上一层目录中的目标 APK 与 `~/tmp/`**，不写系统路径。`[提示词]`
5. **需要真机操作**（安装模块、重启、开关 LSPosed、改系统设置）**一律交用户执行**并等待反馈，
   不假设执行结果。`[提示词]`
6. **作用域只声明在 `xposed_scope`**：`app/src/main/res/values/arrays.xml` 是该模块作用域的
   唯一声明处，由 `AndroidManifest.xml` 的 `xposedscope` 引用。`[代码强制]`
7. **新增目标进程必须两处同改**：`xposed_scope` 数组 + `MainHook.handleLoadPackage` 的分发分支；
   只改一处会导致「勾选了作用域但没有任何 hook」或「代码在等一个永远不会来的包名」。`[提示词]`
8. **不改未提及的配置、不顺手重构、不擅自扩展范围**。`[提示词]`

### 经验规则（本案得出的判定顺序）

9. **判定点若在 native，先找「数据/开关」，再考虑 hook**：按
   `.so` 字符串常量 → XML 配置 / `SystemProperties` / 音频参数 → 调用方 的顺序排查。
   本案的限制既不是硬编码逻辑也不是云端状态码，而是「XML 白名单 + 一个音频参数」。
   `[提示词]`
10. **只 hook 目标 App 之前，先确认判定方是谁**：如果目标 App 只是调用方（判定在别的进程），
    客户端 hook 只改 UI、不产生实际效果。`[提示词]`

## Language & Style

- 与用户交流用中文，术语保留英文（如 AGENTS.md、skill、workflow、hook、LSPosed）。`[提示词]`
- 以事实和代码为主；结论前置，先结果/判断再依据。`[提示词]`
- 不确定的推断标记 `[INFERENCE]`，不得混同于观察到的事实。`[提示词]`
- 给子代理传递信息用**绝对路径**，让子代理自行读取原文件，不传摘要。`[提示词]`
- 文档用中文，术语保留英文。`[提示词]`

## Operational Notes

- **反编译**：`jadx`（Termux 自带）。大 APK 必须 `JAVA_OPTS="-Xmx8g"` + `--no-res`；
  只查单个类用 `--single-class <FQCN>`，避免全量反编译大 jar。`[提示词]`
- **读 manifest**：本机无 `aapt` / `apkanalyzer`，用 Python 手写 AXML 解析
  （UTF-16 字符串池 + 属性表）。`[提示词]`
- **native 分析**：`llvm-objdump -d` / `nm -D --defined-only` / `readelf -sW`；
  字符串线索用 `grep -aoE "[ -~]{6,}" <so>`。`[提示词]`
- **产物目录**：反编译/反汇编产物放 `~/tmp/` 并保留，不清理。`[提示词]`
- **编译走 GitHub Actions**（本地太吃性能）：push `main` 触发
  `.github/workflows/build.yml` → `gradle assembleDebug`。
  下载产物：`gh run download <run-id> -n coloros-subtitle-unlock-apk -D ~/tmp/apk-artifact`。
  仓库：https://github.com/LMQ00/coloros-subtitle-unlock （public）。`[代码强制]`
- **编译成功判定**：workflow 绿 + 产出 APK 且含 `assets/xposed_init` 与 `MainHook`。`[提示词]`
- **工程参数**：Gradle + AGP 8.5.2 / Java 17 / compileSdk 35 /
  `compileOnly 'de.robv.android.xposed:api:82'`（仓库 `https://api.xposed.info/`）。`[代码强制]`
- **已编译产物**：`artifacts/`（debug 签名，不入 git）。`[提示词]`

## Communication

- 结论前置，事实与代码为主，避免客套与铺垫。`[提示词]`
- 错误分两类给：可恢复 → 给重试方案与恢复步骤；不可恢复 → 说明阻塞点、原因与已知全部信息。`[提示词]`
- 真机操作请求要给出**可照做的具体命令/步骤**，并说明期望观察到的现象。`[提示词]`

## Git 提交

默认只提供提交文案，由用户手动执行 `git commit`——让用户保留对仓库历史的控制权。`[提示词]`

用户要求代提交时，以 pi 身份提交：

```
git commit --author="pi <pi@local>" --no-gpg-sign -m "<类型>: <文案>"
```

类型：`feat` / `fix` / `chore` / `docs` / `refactor`

## 图片识别

图片优先直接用 `read` 读取，不要路径依赖 vision。`[提示词]`

只有这两种情况才走 vision：

- 当前模型不支持图片输入（此时 `read` 只返回 MIME/尺寸等元数据，并提示 `?q=`）
- 需要一次与当前对话无关的独立图像描述/判断

需要独立提问时才用 `read <path>?q=<问题>`——它按 `@vision` → `@default` → 当前模型 的顺序解析，
**多一次模型调用和 token**，不能当默认路径。

## 文档索引

| 路径 | 何时读 |
|---|---|
| `docs/README.md` | 接手项目、需要总览两个功能与当前状态时 |
| `docs/01-reverse-notes.md` | 改字幕相关 hook、怀疑云端断流、核对状态码/类名时 |
| `docs/02-module-design.md` | 改字幕 hook 点、增删作用域时 |
| `docs/03-pitfalls.md` | 开始新一轮逆向/反编译前；遇到「hook 不生效」类问题时 |
| `docs/04-stem-separation.md` | 改分轨 hook、核对 native 判定链、解释「仅音乐」限制时 |
| `docs/archive/` | **历史快照，已冻结**：不维护、不删除；只在追溯旧结论时读 |

> 唯一文档源是 `docs/`。`docs/archive/` 不再更新。

## 文档同步

改代码前先判断本次改动是否让 `docs/*.md` 或本文件对应小节过时：

- 过时文档比没有文档更坏 —— 同一轮内更新，不留「以后再补」。
- 判断不了时，在回答末尾列出「可能已过时」清单（文件 + 小节 + 原因）。

## 停止条件

- 穷尽静态分析仍无法定位限制判定点 → 停止并汇报。
- 需要 root / 动 App 数据 / 改系统框架 / 改 native → 停止并询问。
- 无固定尝试次数上限，但每轮须有新证据；无进展即停。