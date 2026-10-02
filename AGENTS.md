# AGENTS

> 本文件只有两块内容：**文档索引**与**开发规范规则**。
> 项目背景、功能状态、工程参数、操作细节都在 `docs/`，本文件只留索引指向。
> 每条规则都是可证伪的约束；`[代码强制]` = 有构建/配置层面兜底，`[提示词]` = 靠代理遵守。

## 文档索引

| 文档 | 何时读 |
|---|---|
| `README.md`（仓库根） | 给外部读者看的导航页：功能现状、安装与使用步骤、构建与签名要点 |
| `docs/交接文档.md` | **接手项目第一步**：一句话现状、任务路由表、环境速览、未做/已知缺口 |
| `docs/README.md` | 要项目总览（两个功能与状态、模块作用域、产物与仓库清单）时 |
| `docs/development.md` | 要工程参数（Gradle/AGP/Java/SDK）、构建与 CI、签名门禁、产物命名、逆向工具链、真机日志过滤时 |
| `docs/testing.md` | 跑验证、判断「算不算完成」、装机与作用域、模块 UI 验证、回滚时 |
| `docs/01-reverse-notes.md` | 改字幕相关 hook、怀疑云端断流、核对状态码/类名时 |
| `docs/02-module-design.md` | 改字幕 hook 点、增删作用域、看三层防御设计时 |
| `docs/03-pitfalls.md` | 开始新一轮逆向/反编译前；遇到「hook 不生效」类问题时；**逆向判定顺序的经验规则也在这里** |
| `docs/04-stem-separation.md` | 改分轨 hook、核对 native 判定链、解释「仅音乐」限制时 |
| `docs/05-stem-any-app.md` | 分轨「任意 App」方案：为什么不能动 `mss_music_only`、白名单怎么写、权限依据 |
| `docs/06-module-ui.md` | 改模块设置页时：UI 结构、prefs 契约、白名单生成规则、生效链路、分层约定 |
| `docs/archive/` | **历史快照，已冻结**：不维护、不删除；只在追溯旧结论时读 |

> 唯一文档源是 `docs/`。`docs/archive/` 不再更新。

## 开发规范规则

### 系统与权限边界

1. **禁止 root 权限操作**：不写 `/system_ext`、`/system`、`/odm`、`/data/oplus` 等系统路径，
   不做 Magisk overlay。`[提示词]`
   —— 唯一例外：`/data/oplus/multimedia/` 下白名单文件的写入由 **system_server 内**的 `MainHook`
   完成（设备策略实测允许，见 `docs/05-stem-any-app.md`）；模块自身进程不得碰它。
2. **禁止 hook 系统框架方法与 native**：不 patch `.so`、不碰 `audioserver` 等系统进程、不引入 Zygisk。
   `[提示词]`
   —— 在 system_server 内只允许「读写白名单文件 + 设置 `ctl.restart` 属性」这类数据操作，
   **不得**用 `XposedHelpers.findAndHookMethod` 去 hook 系统框架方法。
3. **禁止修改目标 App 数据目录**（`/data/data/<目标包名>`）。`[提示词]`
4. **只允许写本目录、上一层目录中的目标 APK 与 `~/tmp/`**，不写系统路径。`[提示词]`
5. **需要真机操作**（装模块、重启、开关 LSPosed、改系统设置）**一律交用户执行**并等待反馈，
   不假设执行结果；请求要给出可照做的具体命令/步骤与期望现象。`[提示词]`

### 作用域与 hook 结构

6. **作用域只声明在 `xposed_scope`**：`app/src/main/res/values/arrays.xml` 是该模块作用域的唯一声明处，
   由 `AndroidManifest.xml` 的 `xposedscope` 引用。`[代码强制]`
7. **新增目标进程必须两处同改**：`xposed_scope` 数组 + `MainHook.handleLoadPackage` 的分发分支；
   只改一处会导致「勾选了作用域但没有任何 hook」或「代码在等一个永远不会来的包名」。`[提示词]`
8. **不改未提及的配置、不顺手重构、不擅自扩展范围**。`[提示词]`

### 模块 UI 的分层（详见 `docs/06-module-ui.md`）

9. **UI 代码与资源的位置**：UI 源码放 `app/src/main/java/com/lmq/coloros/subtitle/ui/`，
   布局/字符串/主题/图标放 `app/src/main/res/` 的对应子目录。`[提示词]`
10. **依赖方向单向（`ui` → 根包）**：hook 侧代码（`MainHook`、`ConfigReader`、白名单生成逻辑）
    **不得引用** `ui` 包、`R` 资源类、androidx/material、`Activity`/`Toast` 等 UI 类型 ——
    模块 APK 会被 LSPosed 注入 system_server 与目标 App 进程，UI 栈在这些进程里没有保证。`[提示词]`
11. **配置只有 UI 进程写**：hook 侧对 prefs **只读不写**（防互相覆盖与变更监听回环）。`[提示词]`
12. **读写通道固定，不得换用**：UI 进程用普通 `SharedPreferences`（`MODE_WORLD_READABLE`），
    hook 侧用 `XSharedPreferences`；两侧换用都会静默失效。`[提示词]`
13. **prefs 文件名与键名是 UI ↔ hook 的接口**：改名必须两侧同改，并在同一轮同步
    `docs/06-module-ui.md`。`[提示词]`

### 依赖与构建

14. **依赖范围**：不引入 Kotlin / Compose；新增依赖仅限 `androidx.appcompat` / `material`
    （`compileOnly 'de.robv.android.xposed:api:82'` 不动）；任何新增依赖必须在 `docs/` 记录理由与
    被否决方案。`[提示词]`
15. **编译走 GitHub Actions**：push `main` 触发 `.github/workflows/build.yml`，本地不跑重编译
    （太吃性能）。产物用 `gh run download` 取回，细节见 `docs/development.md`。`[代码强制]`
16. **编译成功判定**：workflow 绿 + 产出 APK 且含 `assets/xposed_init`、`MainHook`、
    新 UI 的 Activity 与布局资源、`AndroidManifest.xml` 里的 launcher 与 `xposedsharedprefs`。`[提示词]`

### 文档

17. **改代码前先判断本次改动是否让 `docs/*.md` 或本文件过时**：过时文档比没有文档更坏 ——
    同一轮内更新，不留「以后再补」；判断不了时在回答末尾列出「可能已过时」清单
    （文件 + 小节 + 原因）。`[提示词]`
18. **文档用中文，术语保留英文**；不确定的推断标 `[INFERENCE]`，不得混同于观察到的事实。`[提示词]`
19. **给子代理传递信息用绝对路径**，让子代理自行读取原文件，不传摘要。`[提示词]`

### 工作方式

20. **反编译/反汇编产物放 `~/tmp/` 并保留**，不清理；只查单个类用 `--single-class` 避免全量反编译。`[提示词]`
21. **图片优先直接用 `read` 读取**，不要路径依赖 vision；只有「当前模型不支持图片输入」或
    「需要一次与当前对话无关的独立图像判断」才用 `read <path>?q=<问题>`。`[提示词]`
22. **回答以事实和代码为主、结论前置**，避免客套与铺垫；错误分两类给 ——
    可恢复 → 给重试方案与恢复步骤，不可恢复 → 说明阻塞点、原因与已知全部信息。`[提示词]`

### 提交

23. **默认只提供提交文案**，由用户手动执行 `git commit` —— 让用户保留对仓库历史的控制权。`[提示词]`
24. **用户要求代提交时**以 pi 身份提交：
    ```
    git commit --author="pi <pi@local>" --no-gpg-sign -m "<类型>: <文案>"
    ```
    类型限 `feat` / `fix` / `chore` / `docs` / `refactor`。`[提示词]`

### 停止条件

25. 穷尽静态分析仍无法定位限制判定点 → 停止并汇报。`[提示词]`
26. 需要 root / 动 App 数据 / 改系统框架方法 / 改 native → 停止并询问。`[提示词]`
27. 无固定尝试次数上限，但每轮须有新证据；无进展即停。`[提示词]`
