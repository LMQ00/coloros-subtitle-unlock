# 验证与状态（testing）

> 本页是「怎么验证」与「现在算不算完成」的**唯一出处**。
> 机制与逆向证据分别见 `04-stem-separation.md`、`05-stem-any-app.md`、`01-reverse-notes.md`。

## 当前状态（2026-10-02）

| 功能 | 状态 | 验证方式 | 备注 |
|---|---|---|---|
| 字幕每月 120 分钟限制 | **已验证**（用户实测） | 真机开字幕超过限制时长后不中断 | 云端在 `3000803` 后仍继续下发识别结果，客户端 hook 有效 |
| 分轨：内置名单内 App（bilibili 等） | v1.10 **已验证**；v1.11 待验证 | 面板可用 + 拖人声/背景有听感变化 | v1.10 修复了 `attribute=17` 被 `isMssMusicOnly()` 拒掉的问题；**v1.11 起该条 attribute 只在设置页勾选后才清零** |
| 分轨：白名单外 App（Chrome/微信/优酷…） | v1.10 **已验证**；v1.11 待验证 | 开机后探针返回 `0`，daemon 日志出现 `setMssEnableInt` | v1.10 = 模块开机自动**全量**扩名单；**v1.11 起改为设置页手动勾选**（新装 App 默认不放行） |
| 分轨：分离通路本身 | **未被修改** | 开机后 `mss_music_only` 无任何写入 | 模块不触碰该参数（见 `05-stem-any-app.md` §核心教训） |
| 模块设置页（UI） | 代码已完成，本地 Java 类型检查通过；CI 与真机待验证 | 打开页面 → 勾选 App → 杀模块进程重开，配置保留 | 设计与契约见 `06-module-ui.md`；真机步骤见本页 §6 |
| 分轨开关「关」的回落 | 待验证 | §3 看 `<version>` 是否变为 0、探针是否重新 `ffffffff` | 依赖「version 低于内置则内置胜出」这一既有结论 |

`[待确认]` 项见 `../docs/交接文档.md` §未做 / 已知缺口。

## 装机与作用域

1. 安装产物：`/system/bin/pm install -r <apk>`（从 v1.2 旧签名升级需先 `pm uninstall com.lmq.coloros.subtitle`）。
2. LSPosed → 模块「ColorOS AI 音频解锁」→ 作用域勾选**两项**：
   `AI 语音摘记`（`com.coloros.accessibilityassistant`）+ `System Framework`（`android`）。
   不要勾 `com.oplus.atlas` / `声音分轨` —— v1.9 起已无它们的代码。
3. 重启设备（或等下次开机）：模块在 system_server 启动后按设置页配置写白名单并重启 `mmlistservice`。
   首次安装（白名单为空）写出的内容与内置一致 ⇒ 分轨行为等同出厂，要放行的 App 需到设置页勾选。

## 验证步骤

### 1. 分轨准入（先在设置页勾选对应 App）

```sh
# v1.11 起默认不放行：先到模块设置页勾选目标 App（等 logcat 出现新的 whitelist: 行）
# 期望 0 = 放行；ffffffff = 被拒
su -c "service call SpecailizerPLService 42 s16 com.android.chrome i32 1"
su -c "service call SpecailizerPLService 42 s16 tv.danmaku.bili i32 1"
su -c "service call SpecailizerPLService 42 s16 com.netease.cloudmusic i32 1"
```

判据细节：daemon 的 logcat 必须同时出现两行才算真正放行（只有第一行 = 被 `isVocalAdjustSupported` 拒）：

```
D/HoloAudio::SpecailizerPLService_Aidl: isVocalAdjustSupported: supportType=3
D/HoloAudio::SpecailizerPLService_Aidl: setMssEnableInt --- tv.danmaku.bili[1]
```

### 2. 模块开机路径

```sh
su -c "grep -a -E 'system_server|whitelist|restart:' /data/adb/lspd/log/modules_*.log | tail -6"
# v1.11 期望（按设置页勾选）：whitelist: 保留 26 条原有条目（M 条按勾选清零），追加 K 条（attribute=3, version=20991231）
#                             restart: ctl.restart mmlistservice
# 开关关闭时：                whitelist: 关闭（写内置原样 + version 0）
# prefs 读不到时：            whitelist: prefs 不可读，跳过写入
# 注：prefs 尚不可读时每 5 秒重试一次（读到配置后转为纯变更事件驱动，不再轮询）；
#     内置白名单文件读/写失败同样按 5 秒节奏重试。只有 prefs 一直读不到才会持续打这行。
```

### 3. 白名单文件

```sh
su -c "grep -a '<version>' /data/oplus/multimedia/Multimedia_Daemon_Online_List.xml | head -1"
# 期望 <version>20991231</version>（高于内置的 20260703 才生效）
su -c "pidof mmlistservice"     # 每次开机应换成新 pid（被 ctl.restart 重启过）
```

### 4. 分轨听感（需真机操作）

任意 App 播放音频 → 打开分轨面板 → 拖人声/背景。若面板可用但无听感变化，按
`05-stem-any-app.md` §核心教训 检查 `mss_music_only` 是否被写入（`dumpsys media.audio_flinger`
的 `KVP received`）。

### 5. 字幕

`logcat -s ColorOSSubtitleUnlock` 应出现 `hooked engine dispatcher s#onResultStatus` 等启动日志；
运行中出现 `drop status code -2020 @ engine dispatcher` 表示限制码被丢弃。

### 6. 模块设置页（UI，v1.11）

```sh
# 1) 编译产物判据（CI 侧）
gh run view <run-id> --log | tail -30          # workflow 绿
unzip -l coloros-subtitle-unlock-v1.11.apk | grep -E 'SettingsActivity|activity_settings|ic_launcher|androidmanifest'

# 2) 装机后（真机操作，用户执行）
#    桌面出现「ColorOS AI 音频解锁」图标；LSPosed 管理器模块页也有「打开」
pm install -r <apk>
# 打开页面 → 勾选一个 App（如 tv.danmaku.bili）→ 返回桌面 → 杀模块进程 → 重开页面，勾选应仍在
am force-stop com.lmq.coloros.subtitle

# 3) 配置通道是否正常（页面状态区应显示「配置通道：正常」）
su -c "ls -l /data/data/com.lmq.coloros.subtitle/shared_prefs/"
#    世界可读模式下该 prefs 文件应允许 other 读；文件不存在 = 从未保存过
su -c "cat /data/data/com.lmq.coloros.subtitle/shared_prefs/xposed_conf.xml"
```

改完配置的期望链路（机制见 `06-module-ui.md` §生效链路）：**无需重启**，几秒内 logcat 出现新的
`whitelist:` 行与 `restart: ctl.restart mmlistservice`，随后 §1 探针对勾选的 App 返回 `0`。

## 回滚

| 场景 | 操作 |
|---|---|
| 想恢复出厂分轨行为（不需要 root） | 设置页关掉「分轨解锁」开关：写内置原样 + `version 0` ⇒ 内置名单重新生效（见 `06-module-ui.md`） |
| 想恢复出厂分轨行为（连模块一起） | 删除 `/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml` 并重启（内置名单重新生效） |
| 想停用模块 | LSPosed 取消作用域（`android` 项）后重启 |
| 分轨面板可用但无分离效果 | 检查是否有人写过 `mss_music_only=0`（`05-stem-any-app.md` §核心教训） |
| 想回到 v1.10 的「全部 App 自动放行」 | 装回 `artifacts/coloros-subtitle-unlock-v1.10.apk`（同签名可覆盖安装）；或按 `06-module-ui.md` 逐个勾选 |
