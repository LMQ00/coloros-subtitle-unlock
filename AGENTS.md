# AGENTS

## 项目

逆向 ColorOS 16「AI 语音摘记」（包名 `com.coloros.accessibilityassistant`）中「开启字幕」功能的**每月 120 分钟时长限制**，编写 LSPosed 模块 hook 判定逻辑以解除限制。

- 目标 APK：`AI 语音摘记_16.3.12.apk`（本目录）
- 模块形态：LSPosed / Xposed 模块（Java hook）
- 运行环境：ColorOS 16，已 root + LSPosed

## 目录约定

- `./`（`/data/data/com.termux/files/home/coloros-ai-audio`）：仅存放目标 APK 与源码工程。
- `~/tmp/`：反编译产物、下载的构建工具、临时文件。反编译产物保留（不清理）。
- `doc/`：交接文档、踩坑记录、知识复用笔记。

## 硬性约束

- **禁止 root 权限**操作；**禁止 hook 系统框架**；**禁止修改目标 App 数据目录**。
- 只允许写当前目录与 `~/tmp`。
- 需要真机操作（安装模块、重启、开关 LSPosed）→ 交由用户执行，等待其反馈。
- 不改未提及的配置，不顺手重构，不擅自扩展范围。

## 工作流程

1. 静态分析：反编译 APK 定位限制判定点（包名/类/方法/字段或存储 key），附证据。
2. 写模块：定位 hook 点 → 实现 hook → 编译出可安装 APK。
3. 交付：给用户安装实测；同步更新 `doc/` 文档。

## 构建

- 反编译：`jadx`（Termux 自带；`JAVA_OPTS="-Xmx8g"` + `--no-res`，产物 `~/tmp/jadx-out`）。
- 模块工程：`module/`（Gradle + AGP 8.5.2 / Java 17 / compileSdk 35）。
- **编译走 GitHub Actions**（本地编译太吃性能）：
  - 仓库：https://github.com/LMQ00/coloros-subtitle-unlock （public）
  - push `main` 触发 `.github/workflows/build.yml` → `gradle assembleDebug`
  - 下载产物：`gh run download <run-id> -n coloros-subtitle-unlock-apk -D ~/tmp/apk-artifact`
- 编译成功判定：workflow 绿 + 产出 APK 且含 `assets/xposed_init` 与 `MainHook`。

## 文档规范（doc/）

秉持可持续开发与知识复用：每个阶段产出交接文档，记录
- 已确认的事实（含证据：文件/类/方法/存储 key）
- 踩坑与失败尝试（为什么失败、如何规避）
- 下一步与未决问题

文档用中文，术语保留英文。

## 停止条件

- 穷尽静态分析仍无法定位限制判定代码 → 停止并汇报。
- 需要 root / 动 App 数据 / 改系统框架 → 停止并询问。
- 无固定尝试次数上限，但每轮须有新证据；无进展即停。