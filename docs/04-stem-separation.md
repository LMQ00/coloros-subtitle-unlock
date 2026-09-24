# 声音分轨：音乐应用限定解除（逆向 + hook 设计）

> 对象：`声音分轨_16.1.20.apk`
> 包名：`com.oplus.smartmediacontroller`（versionCode 16001020 / minSdk 34 / targetSdk 35）
> 与设备预装 `/my_product/app/SmartMediaController/SmartMediaController.apk` 字节数相同（4555687）

反编译 / 反汇编命令（产物保留在 `~/tmp`）：

```bash
jadx -d ~/tmp/jadx-stem --no-res --threads-count 4 "声音分轨_16.1.20.apk"        # 目标 App
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

## 未决问题（仅剩真机项）

- hook 是否真被 LSPosed 加载进 `com.oplus.atlas`（作用域是否勾选）→ 真机看日志。
- audioserver 是否接受 Atlas 下发的参数（静态上权限与路径都已满足）→ 真机看是否真的放行。