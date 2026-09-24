# 踩坑记录

## 逆向

1. **jadx 默认堆内存不足**：直接 `jadx -d out apk` 在 39MB / 3 dex 上会 OOM 退出（日志停在 `loading ...`）。
   规避：`JAVA_OPTS="-Xmx8g"`，并加 `--no-res` 只反编译代码（资源另跑一次 `--no-src`）。
2. **`android-actions/setup-android@v3` 在新 runner 失败**：它执行 `sdkmanager tools`，而该包已从 SDK 仓库移除，
   报 `Failed to find package 'tools'`。规避：ubuntu-latest 已预装 Android SDK，直接删掉该步骤。
3. **混淆类名验证**：jadx 输出里 `g0`、`h`、`s` 是真实（混淆后）类名，而 `GlobalAsrWorkManager` 是真实未混淆名。
   不能凭文件名假设——用 `strings` 在 dex 里确认描述符 `Lcom/.../g0$d;` 等确实存在，再写 hook。
4. **同名内部类**：`com.coloros.accessibilityassistant.subtitle.globalsummary.g0` 与 `subtitle.g0` 都存在，
   写 hook 必须用全限定名 + `$内部类`，否则匹配错类。

## 机制理解

5. **误判为本地限制**：一开始怀疑 App 本地累计时长并拦截。实际全链路排查（SharedPreferences、计时器、配额字段）
   均无本地判定——限制是**云端状态码** `3000803`，App 仅被动响应。
6. **进程边界**：`TranslateService` 用 AIDL（`IRtasrEngine`）看似跨进程，但其 Intent 的 `setPackage`
   指向 App 自身，`AndroidManifest` 也无 `android:process` → **同进程**，单进程 hook 即可。
7. **配额 API 线索**：`com.oplus.aiunit.speech.asr` 的 `doConsumeCount(duid)` / `getRemainCount(duid)`
   是**文件转写**的配额接口；实时 ASR 的配额通过 ack 的 `monthlyAvailableDuration` 字段下发。两者不要混淆。

## 待验证（真机）

8. **云端是否在 3000803 后继续下发识别结果**——决定本模块是否真正有效。
   若云端直接断流，只能 hook 系统 App `com.oplus.aiunit`（超出当前范围，且用户明确不 hook 系统框架）。
9. **hook 是否命中**：真机抓 `logcat -s ColorOSSubtitleUnlock`，应看到 `hooked ...` 与 `drop ...` 日志。
   若某类找不到会打印 `hook xxx failed`，据此调整类名（版本差异）。