# 逆向笔记：ColorOS「AI 语音摘记」字幕时长限制

> 对象：`AI 语音摘记_16.3.12.apk`
> 包名：`com.coloros.accessibilityassistant`
> versionCode 1603012 / versionName 16.3.12 / minSdk 35 / targetSdk 35 / 单进程

反编译命令（产物保留在 `~/tmp/jadx-out`）：

```bash
jadx -d ~/tmp/jadx-out --no-res --threads-count 4 "AI 语音摘记_16.3.12.apk"
```

## 结论（一句话）

「开启字幕」的**每月 120 分钟**限制是**云端 ASR 配额**，通过 AIUnit 下发错误码 `3000803`（"月额度已达限"）；
App 只是被动响应：收到该码后停止字幕并弹「已达上限」。客户端**没有任何本地配额计算**。

## 证据链

### 1. 云端错误码

`com/oplus/aiunit/realtime_asr/client/RealTimeASRClient.java`

```java
ERROR_LEVEL_CLOSE        = (3000801, "使用时间达到最大时间")
ERROR_LEVEL_MONTHLY_LIMIT= (3000803, "月额度已达限")
```

### 2. 原始码 → 内部状态码 映射

`com/coloros/translate/engine/asr/asrclient/h.java`（`AsrGlobalParser`）方法 `e(int, String)` 的三元链：

```
3000800 -> e4.c.ASR_LONG_TIME_NO_CONTENT
3000801 -> e4.c.ASR_USE_TIME_TOO_LONG        (-2017)
3000802 -> e4.c.ASR_USE_TIME_LIMIT_REACHED   (-2018)
3000803 -> e4.c.ASR_MONTHLY_LIMIT_REACHED    (-2020)   // 每月 120 分钟
```

`e4/c.java`：

```java
ASR_USE_TIME_TOO_LONG(-2017, "asr use time too long"),
ASR_USE_TIME_LIMIT_REACHED(-2018, "asr use time limit reached, auto stopped"),
ASR_MONTHLY_LIMIT_REACHED(-2020, "asr monthly cumulative duration limit reached"),
```

`h#e` 末尾对这三个码调用 `f9806a.onStatus(code, msg)`，即通知 ASR 引擎。

### 3. 引擎 → 监听器 分发

`com/coloros/translate/engine/asr/asrclient/d.java`（`AsrForGlobalRecord`）：

```
d#onStatus(int,String) -> o(2, code, msg) -> s.onResultStatus(2, code, msg)
```

`com/coloros/translate/engine/asr/s.java`（`BaseRtAsrWrapperListener`）：

```java
public void onResultStatus(int i9, int i10, String str) { ... 转发给 mIRtAsrListenerMap 内所有 IRtasrListener ... }
```

所有引擎状态都经此转发给 App 侧各监听器。

### 4. App 侧监听器响应

`com/coloros/accessibilityassistant/subtitle/g0.java`（混淆类，日志名 `GlobalSubtitleWorkManager`）
内部类 `g0$d#onResultStatus`：

```java
} else if (i10 == t3.a.ASR_MONTHLY_LIMIT_REACHED.getCode()) {  // -2020
    g0.this.f8007q = true;
    g0.this.S();   // 只停止字幕，保留摘要
}
```

`g0#S()`：停止字幕 → 弹 `R$string.tip_monthly_time_limit_exceeded`。
`g0#X()`：`return f8007q || 当前提示 == tip_monthly_time_limit_exceeded`，为 true 后忽略后续状态码。

`com/coloros/accessibilityassistant/subtitle/globalsummary/GlobalAsrWorkManager.java` 内部类 `e#onResultStatus`
处理 `ASR_USE_TIME_TOO_LONG` / `ASR_USE_TIME_LIMIT_REACHED`（同理停止）。

### 5. UI「本月剩余」来自服务端

`com/coloros/accessibilityassistant/subtitle/globalsummary/GlobalAsrDto.java`

```java
private Long monthlyAvailableDuration;
private Long monthlyMaxAvailableDuration;
```

`h#c()` / `SubtitleDataPool#i()` 从 ASR 结果 JSON 解析这两个字段并显示。
**没有任何基于它们的本地拦截逻辑** —— 限制完全由服务端状态码触发。

## 架构

```
云端 ASR (aip-ws-cn.allawntech.com / AIUnit 云)
   │ 3000803
   ▼
系统 App com.oplus.aiunit（AIUnit SDK 服务，quota 判定/中继）
   │ ack JSON (code=3000803, monthlyAvailableDuration, monthlyMaxAvailableDuration)
   ▼
com.coloros.accessibilityassistant（单进程）
   RealTimeASRClient -> h#e -> e4.c -> d#onStatus -> s#onResultStatus -> g0$d / GlobalAsrWorkManager$e
                                                                          └─ 停止字幕 + 弹提示
```

> ASR 引擎 `TranslateService` 通过 `coloros.intent.action.INNER_TRANSLATE_SERVICE` 绑定，
> 目标包即 `com.coloros.accessibilityassistant` 自身 → **同进程**，可单进程 hook。

## 关键 hook 点（已在模块中实现）

| 类 | 方法 | 作用 |
|---|---|---|
| `com.coloros.translate.engine.asr.s` | `onResultStatus(int,int,String)` | 引擎→监听器总分发，丢弃限制码 |
| `com.coloros.translate.engine.asr.asrclient.h` | `e(int,String)` | 原始云端码入口，丢弃 3000801/2/3 |
| `com.coloros.accessibilityassistant.subtitle.g0$d` | `onResultStatus(int,int,String)` | 字幕 WorkManager（防御） |
| `com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrWorkManager$e` | `onResultStatus(int,int,String)` | 摘要 WorkManager（防御） |
| `com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrDto` | `getMonthlyAvailableDuration()` / `getMonthlyMaxAvailableDuration()` | UI 剩余时长改极大值 |

## 未决问题

- 云端返回 3000803 后是否**继续下发识别结果**？若停止，客户端 hook 无效（需 hook 系统 AIUnit）。
- 配额绑定维度：设备 `duid` / 账号 / 调用方包名？（文件转写有 `doConsumeCount(duid)`/`getRemainCount(duid)` API）
- 是否存在按「场景 sceneType」区分的不同配额（字幕=3，通话摘要=4）。