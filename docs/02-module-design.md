# 模块设计

## 目标

`com.coloros.accessibilityassistant`（AI 语音摘记）的「开启字幕」在收到云端限制状态码后：
- 停止字幕；
- 弹「本月时长已达上限」提示；
- 后续状态码被忽略（`g0#X()` 返回 true）。

模块让这些限制状态码在客户端**不可见**，从而字幕/摘记继续运行。

## Hook 设计（三层防御 + UI 改写）

源码：`module/app/src/main/java/com/lmq/coloros/subtitle/MainHook.java`

| # | 类 | 方法 | 行为 |
|---|---|---|---|
| 1 | `com.coloros.translate.engine.asr.asrclient.h` | `e(int, String)` | 原始云端码入口；丢弃 `3000801/3000802/3000803` |
| 2 | `com.coloros.translate.engine.asr.s` | `onResultStatus(int,int,String)` | 引擎→监听器总分发；丢弃状态码 `-2017/-2018/-2020` |
| 3 | `com.coloros.accessibilityassistant.subtitle.g0$d` | `onResultStatus(int,int,String)` | 字幕 WorkManager（防御） |
| 3 | `com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrWorkManager$e` | `onResultStatus(int,int,String)` | 摘要 WorkManager（防御） |
| 4 | `com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrDto` | `getMonthlyAvailableDuration()` / `getMonthlyMaxAvailableDuration()` | 返回极大值，UI 显示「剩余充足」 |

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

`AndroidManifest.xml` 的 `xposedscope` 数组仅含 `com.coloros.accessibilityassistant`。
引擎 `TranslateService` 与该 App 同进程，故单进程 hook 足够。