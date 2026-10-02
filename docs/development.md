# 工程与工具链（development）

> 本文件承接原 `AGENTS.md` 的工程参数与操作细节。硬性约束见 `../AGENTS.md`（只讲规则，不复述参数）。

## 目录约定

- **`module/`（本仓库）= 项目本体**：源码、构建配置、文档、产物都在这里。
- **上一层目录只放被逆向的目标 APK**：`../AI 语音摘记_16.3.12.apk`、`../声音分轨_16.1.20.apk`。
- `~/tmp/`：反编译/反汇编产物、下载的构建工具、临时文件。**产物保留，不清理**。
- `artifacts/`：已编译 APK（debug 签名，已被 `.gitignore` 忽略）。

## 工程参数

| 项 | 值 | 出处 |
|---|---|---|
| 构建 | Gradle 8.7 + AGP 8.5.2 | `.github/workflows/build.yml`、`build.gradle` |
| Java | 17（源/目标兼容级别 17） | `app/build.gradle` |
| SDK | `compileSdk 35` / `minSdk 26` / `targetSdk 35` | `app/build.gradle` |
| 包名 | `applicationId` = `namespace` = `com.lmq.coloros.subtitle` | `app/build.gradle` |
| Xposed API | `compileOnly 'de.robv.android.xposed:api:82'`，仓库 `https://api.xposed.info/` | `app/build.gradle`、`settings.gradle` |
| 运行期依赖 | 仅 `androidx.appcompat` + `material`（UI 用，见 `06-module-ui.md`） | `app/build.gradle` |
| 构建特性 | `buildFeatures.buildConfig = true`（UI 与 hook 侧都用 `BuildConfig.APPLICATION_ID`） | `app/build.gradle` |
| 依赖仓库 | `google()` / `mavenCentral()` / `api.xposed.info`，`RepositoriesMode.PREFER_SETTINGS` | `settings.gradle` |
| Gradle JVM | `-Xmx2048m -Dfile.encoding=UTF-8` | `gradle.properties` |

模块入口：`app/src/main/assets/xposed_init`（内容为实现类全名）；作用域见 `02-module-design.md`。

## 构建与 CI

**编译走 GitHub Actions（本地太吃性能，本机无 Android SDK）**：

1. push `main` → `.github/workflows/build.yml`（也可 `workflow_dispatch` 手动触发）。
2. 流程：`setup-java@17` → `gradle/actions/setup-gradle`（Gradle 8.7）→ 从 secrets 还原 keystore
   （`KEYSTORE_BASE64`）→ `gradle assembleDebug --no-daemon --stacktrace` → `unzip -l` → `apksigner`
   校验证书 sha256 → 上传 artifact。
3. **签名门禁**：`EXPECTED_CERT_SHA256 = 57df9c0d999ea701131b4c1b3c9565c545102c030cea1db59645e4e47002f0bd`，
   不匹配直接 fail。密钥固定在仓库 secrets（`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
   因此 CI 产物可直接覆盖安装。本地无这些环境变量时回退 AGP 默认 debug 签名（证书不同，不能与 CI 产物互相覆盖）。
4. 仓库：<https://github.com/LMQ00/coloros-subtitle-unlock>（public）。

取回产物：

```sh
gh run list --limit 5
gh run download <run-id> -n coloros-subtitle-unlock-apk -D ~/tmp/apk-artifact
```

产物命名：`artifacts/coloros-subtitle-unlock-v<主>.<次>.apk`（人工命名，沿用 v1.2–v1.11 序列；
gradle 侧 `versionCode 1` / `versionName "1.0"` 长期未随产物名改动）。

**编译成功判定**：workflow 绿 + 产出 APK 且含 `assets/xposed_init`、`MainHook`、UI 的 Activity 与布局资源、
`AndroidManifest.xml` 里的 launcher 与 `xposedsharedprefs`（见 `AGENTS.md` 规则 16）。

## 本地类型检查（可选，CI 之前的第一道闸）

本机没有 Android SDK / Gradle，跑不了 `assembleDebug`，但可以用 `javac` 对 Java 源码做**类型检查**
（不产出 APK，能挡住绝大多数编译错误）：

```sh
# 依赖（一次性，放 ~/tmp/sdkcheck，产物保留）
#   platform-35_r01.zip → android-35/android.jar（https://dl.google.com/android/repository/）
#   api-82.jar          → https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
#   androidx/material   → ~/tmp/sdkcheck/fetch_aar.py（解出 classes.jar）
cd ~/tmp/sdkcheck
python3 check_res.py     # 交叉检查 Java 里的 R.* 引用是否都在 res/ 里，并生成 stub R.java
python3 check_xml.py     # XML 语法 + @string/@drawable/@mipmap/@style/@array 引用是否都有定义
javac -nowarn -cp "android-35/android.jar:$(ls jars/*.jar | tr '\n' ':')" -d out2 \
  src/com/lmq/coloros/subtitle/{R,BuildConfig}.java \
  <模块>/app/src/main/java/com/lmq/coloros/subtitle/{MainHook,Prefs,ConfigReader}.java
```

限制：`javac` 只覆盖 Java 层，**资源与 manifest 仍由 CI 的 aapt2 验证**（本机无 `aapt` / `apkanalyzer`）。

## 逆向工具链

- **反编译**：`jadx`（Termux 自带）。大 APK 必须 `JAVA_OPTS="-Xmx8g"` + `--no-res`；
  只查单个类用 `--single-class <FQCN>`，避免全量反编译大 jar。
- **读 manifest**：本机无 `aapt` / `apkanalyzer`，用 Python 手写 AXML 解析（UTF-16 字符串池 + 属性表）。
- **native 分析**：`llvm-objdump -d` / `nm -D --defined-only` / `readelf -sW`；
  字符串线索用 `grep -aoE "[ -~]{6,}" <so>`。
- 产物统一放 `~/tmp/` 并保留。

## 真机日志与探针

```sh
# 模块自身日志（字幕 hook、白名单写入、restart）
logcat -s ColorOSSubtitleUnlock

# LSPosed 侧日志（模块是否被加载进各进程）
su -c "grep -a ColorOSSubtitleUnlock /data/adb/lspd/log/modules_*.log | tail -20"

# 分轨准入探针（0 = 放行，ffffffff = 被拒）
su -c "service call SpecailizerPLService 42 s16 <pkg> i32 1"
```

状态判据与完整验证步骤见 `testing.md`。
