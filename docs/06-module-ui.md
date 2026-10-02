# 模块设置页（UI）

> 前置：`02-module-design.md`（字幕 hook 设计）、`05-stem-any-app.md`（分轨白名单机制与权限依据）。
> 本文件是 UI 的**唯一出处**：结构、prefs 契约、白名单生成规则、生效链路、分层约定。
> 改 prefs 键名或白名单规则必须同一轮改本文件（`AGENTS.md` 规则 13）。

## 状态

| 项 | 状态 | 判据 |
|---|---|---|
| 设计与契约 | **已定**（本文件即出处） | — |
| 代码实现 | **已完成** | 文件清单见 §文件结构（`MainHook`/`Prefs`/`ConfigReader`/`ui/*`/res/manifest） |
| Java 层类型检查 | **已通过**（本机 `javac`） | 对比 android.jar(API 35) + xposed api 82 + appcompat 1.7.0 + material 1.12.0 真实 API 编译无错；手法见 `development.md` §本地类型检查 |
| 资源与 manifest 校验 | **已通过**（CI run `37007793510`） | `Build debug APK` 绿 = aapt2 资源链接与 manifest 合并通过；签名门禁 `Verify signing certificate` 通过 |
| 产物符号核对 | **已通过**（本机核对） | `artifacts/coloros-subtitle-unlock-v1.11.apk`：有 `assets/xposed_init`；4 个 dex 内含 `MainHook`/`SettingsActivity`/`AppPickerDialog`/`ConfigReader` 与 prefs 键名；manifest 含 `xposedsharedprefs` 与 `MAIN`/`LAUNCHER` |
| 真机打开页面、配置持久化 | 待验证（用户执行） | `testing.md` §6 |
| 开关真正改变目标 App 行为 | **未验证** | `testing.md` §1–§3 探针 |

## 目标与范围

页面（模块 APK 内的 `SettingsActivity`，桌面有图标，LSPosed 管理器的「打开」也能进）承载三件事：

1. 两个功能的开关（字幕解锁、分轨解锁）+ 状态；
2. 分轨白名单管理 —— **手动勾选要额外放行的 App**；
3. 状态/诊断信息展示 —— **只显示配置意图，不显示真实生效结果**。

**不做**（明确排除）：

- 实时日志 / logcat 读取：普通 App 无 `READ_LOGS`，`system_server` → 模块 app 数据目录的跨 uid 写受
  SELinux 限制。真机日志仍用 `logcat -s ColorOSSubtitleUnlock`（`testing.md`）。
- 「真实生效白名单」读取：需验证模块进程能否读 `/data/oplus/multimedia/...`，本次不做。
- root 操作、系统路径写入、系统 App 的名单管理。

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/UI | Java + View/XML，`androidx.appcompat` + `material`（Material3 DayNight） | 工程纯 Java；不引 Kotlin/Compose |
| 配置持久化 | 模块进程 `SharedPreferences`（`MODE_WORLD_READABLE`） | hook 侧要能读到 |
| 变更通知 | LSPosed 新版 **XSharedPreferences + `registerOnSharedPreferenceChangeListener`** | 事件驱动、零新增依赖 |
| 白名单写入 | 仍在 `system_server` 的 `MainHook` 内完成 | 只有它有权写 `/data/oplus/multimedia/` |

**已否决**：

- Kotlin / Jetpack Compose：改造工程与 CI 的成本不值。
- LSPosed `RemotePreferences`：需额外依赖、与设备 LSPosed 版本耦合；新版 XSharedPreferences 已够用。
- 轮询 prefs 文件 mtime：用户明确否定（性能开销）。
- 广播 / ContentObserver 自建通道：需自定义权限或额外 authority，XSharedPreferences 已内置文件监听。

**权威依据**（外部）：<https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences>
——要旨：模块侧加 meta-data `xposedsharedprefs`（或把 `xposedminversion` 抬到 93+）后，
`Context.getSharedPreferences(name, MODE_WORLD_READABLE)` 可用（LSPosed hook 了
`ContextImpl.getPreferencesDir()` / `checkMode()`，绕过 targetSdk 24+ 的 SecurityException）；
hook 侧 `new XSharedPreferences(pkg, fileName)` 读取；监听回调里 **key 恒为 null**，必须 `reload()`。

## 文件结构

| 路径 | 职责 | 进程 |
|---|---|---|
| `app/src/main/java/com/lmq/coloros/subtitle/Prefs.java` | **契约常量**（prefs 文件名、键名）+ UI 侧写封装 | 模块进程 +（常量）hook 侧 |
| `app/src/main/java/com/lmq/coloros/subtitle/ConfigReader.java` | hook 侧只读：XSharedPreferences + 变更监听 + `volatile` 缓存 | system_server / 目标 App |
| `app/src/main/java/com/lmq/coloros/subtitle/ui/SettingsActivity.java` | 主界面 | 模块进程 |
| `app/src/main/java/com/lmq/coloros/subtitle/ui/AppPickerDialog.java` | App 选择器（只列有启动图标的用户 App，带搜索） | 模块进程 |
| `app/src/main/res/layout/activity_settings.xml` | 主界面布局 | — |
| `app/src/main/res/layout/dialog_app_picker.xml` | 选择器布局 | — |
| `app/src/main/res/values/{strings,themes}.xml` | 文案与 Material3 主题 | — |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + `res/drawable/ic_launcher_*.xml` | 自适应图标（自绘 vector） | — |

`MainHook.java` 的改动只涉及：字幕 hook 回调内查开关；`extendWhitelist()` 改为按 `stem_whitelist` 生成。

## 配置契约

**prefs 文件名**：`xposed_conf`（`Prefs.FILE_NAME`）

| 键（`Prefs.KEY_*`） | 类型 | 默认值 | 语义 |
|---|---|---|---|
| `subtitle_unlock` | boolean | `true` | 字幕解锁总开关 |
| `stem_unlock` | boolean | `true` | 分轨解锁总开关 |
| `stem_whitelist` | StringSet | 空集 | 需要**额外**放行的包名（不填 = 与出厂一致） |

**通道约定**：

- 写入方**只有 UI 进程**；hook 侧只读（`AGENTS.md` 规则 11）。
- UI 用普通 `SharedPreferences` + `MODE_WORLD_READABLE`；hook 用 `XSharedPreferences`；不得换用
  （`AGENTS.md` 规则 12）。
- 模块包名以 `BuildConfig.APPLICATION_ID` 为准（`app/build.gradle` 开了 `buildFeatures.buildConfig`），
  不写字面量。
- 监听回调跑在 FileObserver 线程：只做「置脏 + 唤醒工作线程」，重活（读内置文件、写在线文件、
  `ctl.restart`）在工作线程做。

## 白名单生成规则（`system_server` 内，`MainHook.extendWhitelist()`）

1. 基础内容 = 内置 `/system_ext/etc/Multimedia_Daemon_List.xml` **原样**（保留全部 `<name>`/`<attribute>`
   与 `<version>` 之外的结构）。
2. `stem_unlock == false` ⇒ 写「基础内容 + `<version>0</version>`」到在线白名单
   （version 低于内置 ⇒ 内置文件胜出 = 出厂行为），再 `ctl.restart mmlistservice`。
3. `stem_unlock == true` ⇒ 对 `stem_whitelist` 里每个包：
   - 内置名单已有该条目 ⇒ 把**该条** `<attribute>` 改写为 `3`（按勾选清零 bit4，
     不再是旧版的无条件全量清零）；
   - 内置名单没有 ⇒ 追加 `<name>pkg</name>` + `<attribute>3</attribute>`；
   然后把 `<version>` 置 `20991231`（必须高于内置），就地截断写在线白名单，再 `ctl.restart`。
4. **未勾选的内置条目保持原样**（增量语义）：出厂就放行的音乐类 App 不受模块影响。

**prefs 不可读时**（`ConfigReader` 不可用）：

- 分轨侧：**不写在线白名单、不 restart**（没有用户意图就不动系统文件），工作线程每 5 秒重试一次，
  直到读到配置后转为纯变更事件驱动（该行日志只打一次）；
- 字幕侧：按默认值 `subtitle_unlock = true` 处理（字幕 hook 不写系统文件，保持模块既有功能）。

**日志口径**（`logcat -s ColorOSSubtitleUnlock`，见 `testing.md` §2）：

```
whitelist: 保留 N 条原有条目（M 条按勾选清零），追加 K 条（attribute=3, version=20991231）
whitelist: 关闭（写内置原样 + version 0）
whitelist: prefs 不可读，跳过写入
```

## 生效链路

```
UI 保存 → SharedPreferences(MODE_WORLD_READABLE) 落盘
        → XSharedPreferences 的 FileObserver 触发回调（key == null）
        → ConfigReader.reload() 刷新 volatile 缓存 + 唤醒工作线程
        → system_server: 按 §白名单生成规则 重写在线白名单 → SystemProperties.set("ctl.restart","mmlistservice")
          （字幕侧：hook 回调读缓存值，立即生效，无需重启目标 App）
```

## 分层约定

- **R1**：hook 侧（`MainHook`/`ConfigReader`/白名单生成）不得引用 `ui` 包、`R` 资源、
  androidx/material、`Activity`/`Toast`；依赖方向单向 `ui` → 根包。（`AGENTS.md` 规则 10）
- **R2**：配置只有 UI 进程写，hook 侧只读不写。（`AGENTS.md` 规则 11）
- **R3**：读写通道固定（UI 普通 prefs / hook XSharedPreferences），不得换用。（`AGENTS.md` 规则 12）
- **R4**：prefs 文件名与键名的字面量只出现在 `Prefs.java`；UI 与 hook 都引用其常量。
- **R5**：hook 侧不用 `R.*`/主题；日志统一走 `MainHook.log()`（TAG `ColorOSSubtitleUnlock`）。

## 失败模式与诊断

| 现象 | 可能原因 | 诊断 |
|---|---|---|
| 状态区提示「配置通道异常」 | `MODE_WORLD_READABLE` 抛 SecurityException：`xposedsharedprefs` 未生效 / 模块未被 LSPosed 注入自身进程 | 确认 LSPosed 里模块已启用、作用域两项已勾；`logcat -s ColorOSSubtitleUnlock` 看模块启动日志 |
| 勾选 App 后分轨仍被拒 | prefs 读不到；或白名单未重写；或 `mmlistservice` 未重启 | `testing.md` §1–§3 |
| 关掉分轨开关仍能分轨 | version 未回落到内置之下 | `testing.md` §3 看 `<version>` |
| 状态区显示的与真机结果不一致 | 状态区只显示配置意图，不代表真实生效结果 | `testing.md` §1 |
