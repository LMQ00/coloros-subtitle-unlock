# 声音分轨：音乐应用限定解除（逆向 + hook 设计）

> 对象（在仓库上一层）：`../声音分轨_16.1.20.apk`
> 包名：`com.oplus.smartmediacontroller`（versionCode 16001020 / minSdk 34 / targetSdk 35）
> 与设备预装 `/product/app/SmartMediaController/SmartMediaController.apk`
> （`/my_product/app/…` 同 inode）**md5 完全一致**（`fedfe3c0cc66681298c65c337a1c2488`，4555687 字节）

反编译 / 反汇编命令（产物保留在 `~/tmp`）：

```bash
jadx -d ~/tmp/jadx-stem --no-res --threads-count 4 "../声音分轨_16.1.20.apk"        # 目标 App
jadx -d ~/tmp/jadx-atlas --no-res --threads-count 4 /system_ext/app/OplusAtlasService/OplusAtlasService.apk
llvm-objdump -d /system_ext/lib64/libSpecailizerPLService.so > ~/tmp/sp.asm
llvm-objdump -d /system_ext/lib64/libaudioflingerextimpl.so  > ~/tmp/af.asm
```

## 结论（一句话）

「只能在音乐软件使用」**不是**硬编码白名单逻辑，而是**两个条件叠加**：

1. `mss-whitelist`（XML 数据）决定包能否分轨；bilibili 已在白名单内，属性值 `17`。
2. `isVocalAdjustSupported(pkg)` 只看属性值的两个 bit：`bit0` 支持人声调节、`bit4` 表示「非音乐类」。
   `bit4` 置位时再查 `isMssMusicOnly()` —— **这就是「仅音乐」限制**。
   `3 & 0x10 == 0` → 音乐 App 永远放行；`17 & 0x10 == 0x10` → bilibili 被拒。

`isMssMusicOnly()` 的值来自音频参数 `mss_music_only`；该参数为 1 的原因是设备声明了
特性 `oplus.software.audio.mss_music_only`，使 `OplusAtlasService` 初始化时**跳过**了
`setParameters("mss_music_only=0")`。

→ 解除方式：在 `com.oplus.atlas` 进程内让该特性判定返回 false，Atlas 自己就会把参数置 0。

## 现象

入口灰掉/不可点，弹 Toast「当前应用暂不支持声音分轨」。
文案资源：`res/values-zh-rCN/strings.xml` 的 `app_not_supported_message`（id `0x7f100020`）。

## 证据链

### 1. App 只是遥控器（客户端判定）

```
MssService.onStartCommand(scene_package, caller_package)
  -> f4.g.a(pkg) / f4.g.d(1)              // 日志 tag: MssSettingController
     -> f4.g.b()                          // "prepare"
        p.b(pkg, true) = w5.a.d(pkg, true)      // binder transact 42 = setMssEnable
        if (结果 == null || 结果 != 0) {
            Log "prepare, app not support mss"
            Toast(R.string.app_not_supported_message)     // 抛 Toast 于 f4/f.java case 0
            return false;
        }
```

- `f4/p.java`（tag `PLManager`）是 binder 封装；`w5/a.java` 是
  `oplus.spservice.ISpecailizerPLService` 的客户端代理：
  - transact 42 `setMssEnable(String pkg, boolean enable)` → int
  - transact 43 `getMssEnable(String pkg)` → boolean
  - transact 44 / 45 `setMssTracksGain` / `getMssTracksGain`
- 另一条判定：App 进入时 `f4.g.a(pkg)` 调 `getMssEnable(pkg)`，
  false → 停止（`d(4)`），true → resume（`d(2)`）
- 入口 action `com.oplus.intent.action.ACTION_MSS_PANEL`，extras `scene_package` / `caller_package`；
  拉起方是 `com.oplus.atlas` 的 `MssPanelHelper.bindService()`
- App **无 activity**，纯后台服务型应用；**跨进程**，App 只设置 enable 与增益，
  实际分轨在系统侧完成

### 2. 判定在 native 系统服务

- 服务名 `SpecailizerPLService`（`ServiceManager.getService`）
- 实现 `/system_ext/lib64/libSpecailizerPLService.so`（`android::SpecailizerPLService`），
  **运行在 audioserver 进程内**（由 `libaudioflingerextimpl.so`、`libaudiopolicyextimpl.so` 加载）
- AIDL stub 在 `/system/framework/oplus-services.jar`；AIDL C++ 运行时 `spservice-aidl-cpp.so`
- 分轨模型 `/odm/etc/oplusmss/double.tflite`（4.9MB）；DSP 侧 `/odm/lib/rfsa/adsp/oplusmss/`
- 设备开关属性 `ro.oplus.audio.support.mss`（bit0 = 支持 MSS）

### 3. 拒绝逻辑

`SpecailizerPLService::setMssEnable(pkg, enable, int* ret)`（0x1b200）：

```c
if (!isAllowedFindSpservice()) return;            // uid/pid 权限检查
if (!this->flag_0x269) return;                    // 设备开关
if (pkg == "mss_clear_pkg_all" && !enable) { ... }
else if (!isVocalAdjustSupported(pkg)) {
    log(5, "... %s not support vocal adjust!", pkg);
    *ret = -1;                                    // ← App 收到非 0 → 弹提示
    return;
}
setMssEnableInt(pkg, enable);
*ret = 0;
```

`SpecailizerPLService::isVocalAdjustSupported(pkg)`（0x1b0b4）：

```c
char buf[256];
if (!ListServiceUtils::getListValueByName("mss-whitelist", pkg, buf, 256)) return false;
if (strlen(buf) == 0) return false;
int v = atoi(buf);                                 // 白名单 attribute
log(3, "%s: supportType=%d", "isVocalAdjustSupported", v);
if (!(v & 1))  return false;                       // bit0: 支持人声调节
if ((v & 0x10) && isMssMusicOnly()) return false;  // bit4: 非音乐类，受「仅音乐」开关限制
return true;
```

全 `.so` 内 `getListValueByName` **仅此一处调用**（0x1b168）。

**反汇编复核（逐条直读，2026-09-24）**：

- `setMssEnable`（0x1b200）实际控制流：`isAllowedFindSpservice()` 0x1b244 → `tbz` 0x1b248；
  设备标志 `[this+0x269]` 0x1b250；`memcmp` 比较 `"mss_clear_pkg_all"`（字符串 0xa876）
  0x1b2b4–0x1b31c；**`isVocalAdjustSupported` 调用点 0x1b374**，`tbz w0,#0` 0x1b378 → 跳 0x1b404
  （拒绝分支，`__android_log_print` + `str w8,[x22]` 写非 0 到 `*ret`）；
  成功分支 0x1b3b4 `setMssEnableInt` → 0x1b3d4 `str wzr,[x22]`（`*ret = 0`）。
  ⇒ 与上文伪代码一致。
- `isMssMusicOnly`（0x1ad74）调用 0x1aef0：`callClient(this, w1=0x1, w2=0x1a /*26*/, &CallbackData(sp+0x8), &out(sp+0xe8))`
  ⇒ **事件 26**，与 audioserver 侧事件 26 处理体（0x66958）对应，链路闭合。
- `getMssEnable`（0x1b7c8）**也走同一 `callClient(…, 26, …)`**，没有独立的第二道门
  ⇒ 只要 `mss_music_only` 为 0，`setMssEnable` 与 `getMssEnable` 两条路径都会放行。
- Atlas 内 `setParameters(` 共 **58** 处调用点（`OplusAtlasFeedbackManager`、`OplusAudioDumpsysLog`、
  `OplusAudioScene`、`KaraokeHelper` …）——若 `hasFeature` 分支万一没走到，
  在 Atlas 进程内给所有 `setParameters` 追加 `mss_music_only=0` 即可兜底
  （**已实施**，见下「Hook 设计」的兜底行）。

**链路交叉验证（关键）**：`AudioManager.setParameters` → audioserver → ext 这一段此前只是推断。
用另一个独立参数证伪/证实：Atlas 的 `OplusFoldingModeAudioChannel` 发 `foldmode=1`，
而 `oplusSetParameters` 内部 0x500b8 确实 `adrp x2,0x30000; add x2,x2,#0xbd2`
引用 `"foldmode = %d, flip_rus_switch = %d"`（0x30bd2，另有裸键串 `"foldmode"` 在 0x335a0）。
⇒ **Java 侧 `setParameters` 的参数确实会进入 `AudioFlingerExtImpl::oplusSetParameters`**，
`mss_music_only=0` 同样会被处理。该函数处理到 0x50944（`wakeClientByUid` 起始）为止。

**拒绝位置的反证**：`setMssEnable` 的调用方权限检查 `isAllowedFindSpservice()`（0x14500）
用 `IPCThreadState::getCallingUid()` 放行 uid 1000（system）与 0x411，否则 `checkPermission` 查
`com.oplus.permission.safe.MEDIA`；**早退分支不写 `*ret`**（AIDL out 参数初值 0 → 调用方会看到「成功」）。
而现象是 App 弹「当前应用暂不支持声音分轨」，即拿到非 0 ——
⇒ 代码必然走到了 0x1b404 那个写非 0 的分支，即 `isAllowedFindSpservice()` 通过、失败点就是
`isVocalAdjustSupported`。因此「把 `mss_music_only` 置 0」是充分条件，无需再怀疑调用方权限。

### 4. 白名单是 XML 数据

- 文件：
  - `/system_ext/etc/Multimedia_Daemon_List.xml`（内置，只读分区）
  - `/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml`（在线更新，`system:system 644`）
- 标签：`<mss-whitelist><name>包名</name><attribute>N</attribute> …</mss-whitelist>`
- 当前内容（两文件该段完全一致）：
  - **attr 3**（音乐类，恒支持）：`com.heytap.music`、`com.kugou.android(.lite)`、
    `com.tencent.qqmusic(pad)`、`cn.wenyu.bodian`、`com.netease.cloudmusic`、
    `cn.kuwo.player(.kwmusichd)`、`com.luna.music`、`com.xs.fm(.lite)`
  - **attr 17**（非音乐类，被「仅音乐」挡住）：`com.heytap.yoli`、`com.chaoxing.mobile`、
    `tv.danmaku.bili`、`tv.danmaku.bilibilihd`、`com.netease.edu.ucmooc`、
    `com.baidu.netdisk`、`com.thinkwu.live`
  - **attr 2**（不支持人声调节）：`com.spotify.music` 等
- 选取规则（`getMMListData` 0x5754）：读两文件的 `<version>`，**版本号大者生效**
  （现 系统 `20260703` > online `20260225`）
- 设备实测复核（2026-09-24，只读）：两文件各 **26** 条，属性分布均为
  `3`×12 / `17`×7 / `2`×7；`tv.danmaku.bili` 与 `tv.danmaku.bilibilihd` 均为 **17** → 确认被
  `bit4` 门控挡住，是本 hook 的直接目标
- 匹配是精确 `strcmp`（`getInfoFromMMList` 0x6570）→ **无通配符**
- 解析链：`libmmlistparser.so` → `libimmlistservice.so`（IMMListService）
  → `ListServiceUtils`（`libListWrapperRouter.so`）→ `SpecailizerPLService`

### 5. 「仅音乐」开关的来源（根因）

1. 特性声明：`/my_product/etc/permissions/oplus.product.feature_multimedia_unique.xml:89`
   `<oplus-feature name="oplus.software.audio.mss_music_only" />`
2. `com.oplus.atlas.OplusAtlasService` **`onCreate()`** 内（`OplusAtlasService.java:286`）：

   ```java
   if (!OplusFeatureConfigManager.getInstance().hasFeature("oplus.software.audio.mss_music_only")
           && (audioManager = this.mAudioManager) != null) {
       audioManager.setParameters("mss_music_only=0");
   }
   ```

   特性存在 ⇒ **不设** `mss_music_only=0` ⇒ 保持「仅音乐」。
   该调用点是 Atlas 内 `hasFeature(String)` 的 10 处调用之一，全部用单参数重载
   （另一重载 `hasFeature(String, FeatureID)` 无人调用）→ hook 单参数版本即可全覆盖。
   `OplusFeatureConfigManager.hasFeature(String)` 是自身 override，实现仅
   `return this.mCache.query(name);`（`oplus-framework.jar`，boot classpath）。

   `onCreate` 内的时序已核对：`this.mAudioManager = audioManager2;` 在 **235 行**，
   早于 286 行的判断 → 条件中的 `mAudioManager != null` 成立；
   264 行按 `ro.oplus.audio.support.mss & 1` 创建 `MssPanelHelper`（本机该属性为 `1`，面板存在）。
3. audioserver 接收参数：`AudioFlingerExtImpl::oplusSetParameters`（0x4e88c）用
   `AudioParameter::getInt` 解析 `mss_music_only`，存到 `[this+0x512]`。

   **默认值来自构造函数**：`AudioFlingerExtImplC2` 在 0x4cdfc `mov w20, #0x1`，
   0x4cf8c `strb w20, [x19, #0x512]` → 对象一建立就是 `1`（即「仅音乐」），
   与「本机 bilibili 被拒」的现象一致。

   **清除条件（0x4f5ac–0x4f5e8）**：

   ```
   mov  w8, #-1                       ; 哨兵：键不存在时保持 -1
   str  w8, [sp, #0xf0]
   getInt(String8("mss_music_only"), [sp,#0xf0])   ; w0 = status_t（0 = 找到）
   ldr  w8, [sp, #0xf0]                            ; 解析出的值
   orr  w22, w0, w8
   cbnz w22, +8
   strb wzr, [x20, #0x512]            ; 仅当「键存在 且 值 == 0」才清零
   ```

   该段位于 `oplusSetParameters` 的**键分发链**上：只有前一个键 `update_hires`
   **不存在**时才落到这里（0x4f5a8 `cbz` 命中 `update_hires` 处理体则跳过本段）。
   Atlas 发的正是单键串 `mss_music_only=0` → 不含 `update_hires` → 必然落到本段 →
   键存在且值为 0 → `[0x512] = 0`。**链路闭合**。

4. `isMssMusicOnly()` 取值路径：向已注册客户端发 `callClient(1, 26, …)`；
   唯一注册的 native 客户端是 audioserver 的
   `AudioFlingerExtImpl::SpatilaizerNativeClient::onCallback`（0x667d0）。
   事件 26 的处理体（0x66958，事件跳表在 0x36a92）：

   ```
   ldrb w8, [x8, #0x512]     ; 该 flag
   strb w8, [sp, #0x118]     ; CallbackData.enable（结构基址 sp+0x78 → 偏移 0xa0 = enable）
   ```

   与 `isMssMusicOnly` 侧读取处（`sp+0xe8` 基址 + `0x188` = 偏移 `0xa0`）一致。
   ⇒ `isMssMusicOnly()` = `CallbackData.enable & 1` = `mss_music_only` 参数值。

## Hook 设计

| 项 | 值 |
|---|---|
| 类 | `com.oplus.content.OplusFeatureConfigManager`（`/system/framework/oplus-framework.jar`，boot classpath） |
| 方法 | `public boolean hasFeature(String name)` |
| 进程 | `com.oplus.atlas` |
| 行为 | `name.equals("oplus.software.audio.mss_music_only")` → 返回 `false` |

**兜底（同一进程）**：

| 项 | 值 |
|---|---|
| 类 | `android.media.AudioManager`（boot classpath） |
| 方法 | `setParameters(String)` |
| 行为 | 若串中不含 `mss_music_only`，则追加 `;mss_music_only=0` |

兜底理由：主路径依赖 `OplusAtlasService.onCreate()` 那一处判断；该分支没走到、或 audioserver
重启把参数重置回构造函数默认值 1 时，兜底会在下一次参数下发（Atlas 内共 58 处）重新置 0。
只作用于 Atlas 进程，日志用 `append mss_music_only=0 -> "..."` 与主路径区分。

**第三条路径：目标 App 自己下发（不需要重启 Atlas）**

| 项 | 值 |
|---|---|
| 进程 | `com.oplus.smartmediacontroller`（声音分轨 App 自身） |
| 依据 | 其 manifest 声明 `android.permission.MODIFY_AUDIO_SETTINGS` |
| 方法 | `android.app.Application#onCreate`（进程建立即注入）＋ `MssService#onStartCommand`（每次面板打开再注入一次） |
| 行为 | 调 `AudioManager.setParameters("mss_music_only=0")` |

为什么需要它：LSPosed 的 hook 只随**目标进程启动**注入。`OplusAtlasService` 由系统在开机时拉起，
若模块是开机之后才装的，Atlas 进程不重建就永远没有 hook——实测本机开机 48.3 小时、
模块中途安装，因此 Atlas 路径全部无效。而 `MssService.onStartCommand` 每次打开分轨面板都会走，
且**早于** App 调 `setMssEnable`，所以参数在 native 判定前已置 0；只需让这个 App 的进程重建一次
（强停或重启该 App）。日志：`inject(Application#onCreate): setParameters(mss_music_only=0)`。

效果：`OplusAtlasService` 的 `setParameters("mss_music_only=0")` 分支被满足
→ audioserver 参数置 0 → `isMssMusicOnly()` 为 false
→ `tv.danmaku.bili`（attr 17）通过 `isVocalAdjustSupported` → `setMssEnable` 成功 → 分轨启用。

只拦这一个特性名，不影响 `hasFeature` 的其他调用（Atlas 内还用它判断
`oplus.software.audio.hearing_health_support`、`oplus.software.game.cold.start.speedup.enable` 等）。

### 为什么不走别的路

| 路线 | 判定 |
|---|---|
| 只 hook 客户端 App（SmartMediaController） | **无效**：native 服务 `*ret = -1` 且不调 `setMssEnableInt`，分轨不会真正启用 |
| 改 `mss-whitelist`（改 `/data/oplus` 或 Magisk overlay） | 可行，但需 root、只覆盖清单内包名、可能被在线更新覆盖；本项目不做 |
| hook/patch native（audioserver 内 `isVocalAdjustSupported` / `setMssEnable`） | 可做到字面「任意 App」，但属 hook 系统框架；本项目不做 |

### 范围限制

本方案只解决**已在白名单内、属性含 `bit4`** 的 App（bilibili、B站HD、优酷、学习通、
百度网盘、网易慕课、thinkwu.live）。**不在白名单的 App**（YouTube、Chrome、本地播放器…）
仍会被 `getListValueByName` 判为不支持——那需要改白名单数据或 hook native，超出本期范围。

## 生效条件与验证

0. 安装 `artifacts/coloros-subtitle-unlock-v1.5.apk`；只有从 v1.2（旧签名）升级才需先
   `/system/bin/pm uninstall com.lmq.coloros.subtitle`，v1.3/v1.4 → v1.5 可直接覆盖。
1. LSPosed 启用模块，作用域勾选「AI 语音摘记」+「Atlas」(`com.oplus.atlas`)。
2. 重启设备，或强停 `com.oplus.atlas` 让其重建（`setParameters` 在 Atlas 初始化时执行一次）。
3. bilibili 播放音频 → 打开「声音分轨」→ 不再弹「当前应用暂不支持声音分轨」，
   人声/伴奏增益滑杆可调且声音有实际分离效果。
4. `logcat -s ColorOSSubtitleUnlock` 可见 hook 命中日志。

## 参数写入者普查（静态）

对整机做了一次穷尽扫描（只读），确认 `mss_music_only` 的**全部**出现位置：

| 范围 | 数量 | 命中 |
|---|---|---|
| 所有 `.so`（`/system`、`/system_ext`、`/vendor`、`/odm`、`/my_product`、`/product`，递归） | 3346 | 仅 `libaudioflingerextimpl.so`（reader + 清零点） |
| 所有 `.apk` / `.jar`（同范围，扫 dex/xml/arsc/assets） | 706 | `oplus-framework.jar`、`Bluetooth.apk`（均只是**特性名字符串表**，即 `FeatureID` 名单，非 setter）；`OplusAtlasService.apk`（**唯一 setter**） |
| `/vendor/etc`、`/system/etc`、`/odm/etc`、`/my_product/etc`、`/product/etc`、`/system_ext/etc` | — | 仅 `oplus.product.feature_multimedia_unique.xml`（特性声明） |

- 全机不存在 `mss_music_only=1` 字面量；`mss_music_only=0` 只出现 1 次（Atlas 的 dex）。
- 706 个归档的 `*.dex` + `lib/*.so` 全量解压扫描**已完成**，命中恰为上表 3 个文件，无第 4 个。
- ⇒ **没有任何其他组件会把参数重置为 1**；唯一的 1 来自 audioserver 构造函数默认值。

权限：`OplusAtlasService.apk` 声明了 `android.permission.MODIFY_AUDIO_SETTINGS`
（系统应用）→ `setParameters` 不会被权限拦下。

## 设备现状（只读探测，无需 root）

| 项 | 值 |
|---|---|
| `ro.oplus.audio.support.mss` | `1`（支持 MSS） |
| `tv.danmaku.bili` | 已安装（`/data/app/…/tv.danmaku.bili-…/base.apk`） |
| `com.oplus.smartmediacontroller` | `/product/app/SmartMediaController/SmartMediaController.apk`（与 `/my_product/app/…` 同 inode 9815556，是同一文件） |
| `com.oplus.atlas` | `/system_ext/app/OplusAtlasService/OplusAtlasService.apk` |
| `com.lmq.coloros.subtitle` | 已安装（旧版本，尚未升级到 v1.3） |
| `dumpsys -l` | `AtlasService`、`MMListService` 均在运行 |

- 运行时可观测性受限：`dumpsys media.audio_flinger` / `dumpsys AtlasService` 对非 shell uid 均拒绝
  （`Permission Denial` / `FAILED_TRANSACTION`）→ **参数值只能靠真机 logcat 观察**，不做 root 探测。

## 已知限制

- **参数不持久**：`mss_music_only` 只是 audioserver 内 `AudioFlingerExtImpl` 对象的成员
  （构造函数默认 1）。若 audioserver 单独重启而 Atlas 不重启，参数会回到 1（仅音乐），
  需要重启 Atlas（或设备）重新下发。设备重启时 audioserver 先起、Atlas 后起 → 正常。
- **仅白名单内**：见上文「范围限制」。

## 为什么 `mss_music_only` 路线在本 ROM 上无效（实测结论）

用 root 直接调 binder + 模块内探针做了完整对照实验（2026-09-25）：

| 实验 | 结果 |
|---|---|
| `service call SpecailizerPLService 42 s16 tv.danmaku.bili i32 1` | `-1`；native 日志 `isVocalAdjustSupported: supportType=17` + `not support vocal adjust!` |
| 同上，`com.heytap.music`（attr 3） | `0`；`supportType=3` → `setMssEnableInt` |
| 同上，`com.spotify.music`（attr 2） | `-1`；`supportType=2`（bit0 未置） |
| 模块在 SMC 进程内发 `setParameters("mss_music_only=0")` | 日志有注入、`dumpsys media.audio_flinger` 有 `KVP received: mss_music_only=0` |
| **对照探针**：发 `setParameters("holoDeviceCompatState=0")` | **ext 日志出现** `AudioFlingerExtImpl: oplusSetParameters mIsUnsupHoloBt = 0, …` |

⇒ App 发的参数**确实**进入了 `AudioFlingerExtImpl::oplusSetParameters`（同一函数），
但 `mss_music_only=0` 之后 gate 依旧拒绝 bilibili。

进一步排查（均已排除）：

- `isMssMusicOnly()` 的客户端就是 audioserver 的 `SpatilaizerNativeClient`：`callClient:clientID:1 exist:1, event:18, isOneWay:0`（同步调用）。
- `onCallback` 里 `[owner+0x512]` 的 owner 确认是 `AudioFlingerExtImpl`（同一函数还用 `[owner+0x163]`，
  而 `0x163` 是 ext 自己的字段）。
- 链式判断两种语义都试过：只发 `mss_music_only=0`（status 语义应落到 mss 分支）、
  以及 `update_hires=1;mss_music_only=0`（value 语义应落到 mss 分支）——**两者都没让 gate 放行**。
- 组合串 `update_hires=1;mss_music_only=0` 甚至没有出现在 AudioFlinger 的 KVP 记录里（疑似被过滤）。

**结论（已被下一节修正）**：当时看起来「参数无效」，实际原因是 `isMssMusicOnly()` 的**缓存**——
详见下一节「真正的机制」。参数本身是有效的，但生效顺序有严格要求。

**仍然可行且不碰 native 的备选路线**：改白名单数据，把目标包名的 attribute 去掉 bit4（17 → 3），
使 `isVocalAdjustSupported` 根本不咨询 `isMssMusicOnly()`。该改动需要写
`/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml`（并把 `<version>` 提到高于内置的 `20260703`），
或对 `/system_ext/etc/Multimedia_Daemon_List.xml` 做 overlay —— 两者都属系统数据/系统分区，**未获授权，未实施**。

## 真正的机制：`isMssMusicOnly()` 有缓存，且缓存宿主是 `atlasservice`

前面「参数无效」的结论只对了一半——参数本身有效，但**读它的一方把结果缓存了**。

1. `SpecailizerPLService` **不在 audioserver 里**，而在原生进程 `/system_ext/bin/atlasservice`（init 服务，ppid=1）。
   它加载 `libSpecailizerPLService.so` + `libimmlistservice.so`，但**不加载** `libaudioflingerextimpl.so`；
   它注册的 clientID 1 是 **audioserver 里那个 `SpatilaizerNativeClient` 的 binder 代理**（跨进程调用）。
2. `isMssMusicOnly()`（0x1ad74）实现：

   ```
   1ada0: ldrb w8, [x0, #0x228]      ; 缓存有效标志
   1ada4: tbz  w8, #0x0, 0x1b014     ; 为 0 → 直接返回缓存
   …（实时向 client 发 event 26）…
   1af98: strb wzr, [x19, #0x228]    ; 用完把标志清零
   1af9c: strb w22, [x19, #0x229]    ; 结果写入缓存
   1b014: ldrb w22, [x19, #0x229]    ; 缓存命中路径
   1b028: and  w0, w22, #0x1
   ```

   构造函数 `mov w21,#1; strh w21,[x19,#0x228]` → 初始 `[0x228]=1, [0x229]=0`：
   **只有 `atlasservice` 进程里 SP 服务对象的第一次调用会真正查询，之后永远用缓存**；
   `[0x228]` 只在构造函数里重置 ⇒ **缓存只在 `atlasservice` 重启时失效**。

3. ⇒ 生效顺序必须是：audioserver 重启（标志回默认 1）→ 在 audioserver 侧把标志置 0 →
   **重启 `atlasservice`**（清缓存）→ 首次查询读到 0 → 放行。
   若顺序反了（先查后置 0），缓存会固化 1，之后无论怎么改参数都不生效——这正是前期多次失败的原因。

**实测验证（2026-09-25）**：

```
# 顺序：重启 audioserver → 模块注入 mss_music_only=0 → 重启 atlasservice
service call SpecailizerPLService 42 s16 tv.danmaku.bili i32 1     → 0  ✓
service call SpecailizerPLService 42 s16 com.baidu.netdisk i32 1   → 0  ✓
service call SpecailizerPLService 43 s16 tv.danmaku.bili           → 1（已启用）✓
native 日志：isVocalAdjustSupported: supportType=17 → setMssEnableInt --- tv.danmaku.bili[1]
```

**对模块的含义**：开机后 `atlasservice` 的缓存是空的（`[0x228]=1`），只要**在第一次 `setMssEnable` 之前**
把 audioserver 的标志置 0 即可。模块在 SMC 进程 `MssService.onStartCommand` 入口注入，正好早于
App 调 `setMssEnable`（面板打开即触发）⇒ 重启一次设备后应能正常生效。

**常态顺序复验（模拟开机，2026-09-25）**：

```
1) 先重启 audioserver + atlasservice（= 开机时两者先起来，缓存为空、标志为 1）
2) 之后才让 SMC App 进程建立 → 模块注入 inject(Application#onCreate): setParameters(mss_music_only=0)
3) 首次 setMssEnable（等价于面板打开时的第一次判定）：
   tv.danmaku.bili → 0 ✓   com.baidu.netdisk → 0 ✓   com.heytap.music → 0 ✓
```

⇒ 重启设备后按正常流程使用即可生效，无需任何手工步骤。

## 未决问题

- 面板里的**实际分离效果**（人声/伴奏是否真的分开）只有人能判断；native 侧已实测放行。
- `atlasservice` 若在运行中被单独重启，缓存会清空并重新查询——此时若 audioserver 的标志已被重置为 1，
  会缓存成 1；需要再触发一次 App 注入（打开面板即可）。