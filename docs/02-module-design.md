# 模块设计（字幕解锁）

> 本文只覆盖**字幕 120 分钟限制**的 hook 设计。「声音分轨」的设计见 `04-stem-separation.md`。

## 目标

`com.coloros.accessibilityassistant`（AI 语音摘记）的「开启字幕」在收到云端限制状态码后：
- 停止字幕；
- 弹「本月时长已达上限」提示；
- 后续状态码被忽略（`g0#X()` 返回 true）。

模块让这些限制状态码在客户端**不可见**，从而字幕/摘记继续运行。

## Hook 设计（三层防御 + UI 改写）

源码：`app/src/main/java/com/lmq/coloros/subtitle/MainHook.java`

| # | 类 | 方法 | 行为 |
|---|---|---|---|
| 1 | `com.coloros.translate.engine.asr.asrclient.h` | `e(int, String)` | 原始云端码入口；丢弃 `3000801/3000802/3000803` |
| 2 | `com.coloros.translate.engine.asr.s` | `onResultStatus(int,int,String)` | 引擎→监听器总分发；丢弃状态码 `-2017/-2018/-2020` |
| 3 | `com.coloros.accessibilityassistant.subtitle.g0$d` | `onResultStatus(int,int,String)` | 字幕 WorkManager（防御） |
| 3 | `com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrWorkManager$e` | `onResultStatus(int,int,String)` | 摘要 WorkManager（防御） |
| 4 | `com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrDto` | `getMonthlyAvailableDuration()` / `getMonthlyMaxAvailableDuration()` | 返回极大值，UI 显示「剩余充足」 |
| 5 | `com.coloros.accessibilityassistant.subtitle.g0` | `X()` | 强制返回 false，防止「已达上限」标志导致后续状态码被忽略 |
| 6 | `subtitle.g0` / `GlobalAsrWorkManager` | `S()` / `x0()` / `T0()` | 兜底：限制到达时的「停止」动作置空 |

### 诊断日志

`asr.s#onResultStatus` 会打印**每一个**状态码（`status from=.. code=.. msg=..`），
真机可用 `logcat -s ColorOSSubtitleUnlock` 观察：
- 若出现 `drop status code -2020` → hook 命中，限制码被丢弃；
- 若字幕仍停但无后续状态码 → 云端断流，客户端无法恢复。

### 为什么 hook 这两个点

- **`h#e`**：云端码在此处被翻译为内部状态码并通知引擎。在此丢弃，引擎根本不会进入「已达上限」分支。
- **`s#onResultStatus`**：所有引擎状态最终经此转发给 App 侧监听器（`d#o()` 直接调用它）。
  在此兜底，覆盖任何绕过 `h#e` 的路径。
- 具体 WorkManager 监听器作为第三层防御，防止未来版本改动分发链路。

## 常量

```
状态码: -2017 ASR_USE_TIME_TOO_LONG / -2018 ASR_USE_TIME_LIMIT_REACHED / -2020 ASR_MONTHLY_LIMIT_REACHED
原始码: 3000801 ERROR_LEVEL_CLOSE / 3000802 / 3000803 ERROR_LEVEL_MONTHLY_LIMIT
```

## 构建

- 源码：`module/`（Gradle + AGP 8.5.2，Java 17，compileSdk 35）
- CI：`.github/workflows/build.yml`，push 到 `main` 触发 `gradle assembleDebug`
- 依赖：`compileOnly 'de.robv.android.xposed:api:82'`（仓库 `https://api.xposed.info/`）

## 作用域

`app/src/main/res/values/arrays.xml` 的 `xposed_scope`：

- `com.coloros.accessibilityassistant` —— 字幕限制。引擎 `TranslateService` 与该 App 同进程，
  故单进程 hook 足够。
- `com.oplus.atlas` —— 分轨限制（见 `04-stem-separation.md`）。