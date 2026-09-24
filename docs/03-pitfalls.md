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

## 声音分轨（`04-stem-separation.md` 配套）

10. **误判为「App 自己判断是不是音乐软件」**：目标 App `com.oplus.smartmediacontroller` 只是遥控器，
    判定在 native 系统服务里。**只 hook 目标 App 无效**——native 侧 `*ret = -1` 且不执行
    `setMssEnableInt`，分轨根本不会启用（只会改 UI）。
11. **判定点不一定在代码里**：本案的限制是「XML 白名单 + 一个音频参数」。定位到 native 服务后，
    先找**数据与开关**（`.so` 里的字符串常量、XML 配置、`SystemProperties`、音频参数），
    再决定要不要 hook。字符串常量表是最快的线索来源：
    `grep -aoE "[ -~]{6,}" lib.so | grep -i 关键词`。
12. **无 `aapt` / `apkanalyzer`**：Termux 上没有；读 `AndroidManifest.xml` 用 Python 手写
    AXML 解析（UTF-16 字符串池 + 属性表），比装 SDK 快得多。
13. **大 jar 反编译耗时**：`oplus-services.jar` 有 3 个 dex / 28MB，全量 jadx 很慢；
    只查一个类时用 `jadx --single-class <FQCN>`，秒级出结果。
14. **AIDL 客户端 ≠ 判定点**：`AudioEffectCenter`、`OplusGames` 等都引用 `ISpecailizerPLService`，
    但都只是调用方。判断谁是「判定方」要看**谁写返回值**，不是谁引用了接口。
15. **事件回调要看跳表**：`SpatilaizerNativeClient::onCallback` 用字节跳表分发事件，
    `objdump` 出来的 `cmp/b.hi` 只是边界检查；要按表基址 + `表[i]*4` 算出目标地址
    （本案事件 26 → 0x66958），否则会以为「没有处理这个事件」。

## 构建与安装

16. **CI 默认签名每次构建都不同**：workflow 若没有 keystore 步骤，AGP 会在全新 runner 上
    自动生成 `~/.android/debug.keystore`，**每次构建的签名证书都不一样**。
    实测：v1.2 证书 `260919174102Z`（sha256 `cb4997de…`）、v1.3 证书 `260924151753Z`
    （sha256 `20c72f80…`）——同一台设备上两者不能互相覆盖安装
    （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
    → **已修复**：keystore 放仓库 secrets（`KEYSTORE_BASE64` 等），CI 还原后由
    `signingConfigs.ci` 签名；实测两次构建产出字节相同的 APK（证书 sha256 `57df9c0d…`）。
    从旧版（未固定签名的构建）升级仍需先卸载一次。
17. **核对安装身份**：`/system/bin/pm path <pkg>` 给出已装 APK 路径（`/data/app/.../base.apk` 可读），
    `md5sum` 与本地产物比对即可确认设备上跑的是哪个构建；证书可用
    「在 APK 里搜 `30 82 ?? ?? 30 82` 取 DER」的方式提取比对。