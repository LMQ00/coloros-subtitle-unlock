# 验证与状态（testing）

> 本页是「怎么验证」与「现在算不算完成」的**唯一出处**。
> 机制与逆向证据分别见 `04-stem-separation.md`、`05-stem-any-app.md`、`01-reverse-notes.md`。

## 当前状态（2026-10-02）

| 功能 | 状态 | 验证方式 | 备注 |
|---|---|---|---|
| 字幕每月 120 分钟限制 | **已验证**（用户实测） | 真机开字幕超过限制时长后不中断 | 云端在 `3000803` 后仍继续下发识别结果，客户端 hook 有效 |
| 分轨：内置名单内 App（bilibili 等） | v1.11 **已验证** | 设置页勾选后探针返回 `0` | v1.11 起该条 attribute **只在勾选后**清零（`17 → 3`，2026-10-03 实测） |
| 分轨：名单外 App（微信/Chrome/优酷…） | v1.11 **已验证** | 设置页勾选后探针返回 `0`；未勾选的仍 `ffffffff` | v1.11 = 设置页手动勾选（新装 App 默认不放行）；v1.10 的「自动全量放行」已废弃 |
| 分轨：分离通路本身 | **未被修改** | 开机后 `mss_music_only` 无任何写入 | 模块不触碰该参数（见 `05-stem-any-app.md` §核心教训） |
| 模块设置页（UI） | v1.11 **已验证**（真机） | 页面渲染无崩溃；切开关后目标 App 进程打印 `config: 配置变更已读入`；重启设备后配置保留 | 契约见 `06-module-ui.md`；步骤与现象链见本页 §6 |
| 分轨开关「关」的回落 | **已验证** | 三包探针全部 `ffffffff`、在线白名单 `<version>0</version>` | 「version 低于内置 ⇒ 内置胜出」已由真机确认 |
| 增删白名单是否要重启 | **已验证：不需要** | 保存后秒级生效（重写名单 + `ctl.restart mmlistservice`） | 仅装/更新模块 APK 或改作用域才需重启 |

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

### 6. 模块设置页（UI）

**2026-10-03 真机已验证**（v1.11 装机，Android 16/API 36，ColorOS 16）：

```sh
# 1) 编译产物判据（CI 侧）
gh run view <run-id> --log | tail -30          # workflow 绿
unzip -l coloros-subtitle-unlock-v1.12.apk | grep -E 'classes.*dex|activity_settings|mipmap-anydpi|AndroidManifest'

# 2) 装机（桌面出现「ColorOS AI 音频解锁」图标；LSPosed 管理器模块页也有「打开」）
pm install -r <apk>

# 3) 配置通道（页面状态区应显示「配置通道：正常（hook 侧可读）」）
#    prefs 被 LSPosed 重定向到随机目录，**不在** /data/data/<模块包名>/shared_prefs/
su -c "find /data/misc -name xposed_conf.xml"
su -c "cat <上面找到的路径>"        # 期望 world-readable：-rw-rw-r--
```

**已验证的现象链**（改配置后**无需重启**，几秒内完成）：

```
设置页保存 → system_server 日志：
  config: 配置变更已读入（reload=true）
  whitelist: 保留 26 条原有条目（1 条按勾选清零），追加 1 条（attribute=3, version=20991231）
  restart: ctl.restart mmlistservice
→ 探针：勾选的 bilibili / 微信 = 00000000（放行），未勾选的 chrome = ffffffff（仍出厂拒绝）
```

- 关掉「分轨解锁」开关 ⇒ 写内置原样 + `<version>0</version>`，三个包探针全部回到 `ffffffff`。
- 重启设备后配置仍在：开机日志 `whitelist: 关闭（写内置原样 + version 0）`（当时开关为关）。
- **装/更新模块 APK 后必须重启一次**（LSPosed 仅在进程启动时注入 system_server）；
  但**增删白名单、切开关都不需要重启**。

## 回滚

| 场景 | 操作 |
|---|---|
| 想恢复出厂分轨行为（不需要 root） | 设置页关掉「分轨解锁」开关：写内置原样 + `version 0` ⇒ 内置名单重新生效（见 `06-module-ui.md`） |
| 想恢复出厂分轨行为（连模块一起） | 删除 `/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml`，再 `setprop ctl.restart mmlistservice`（**不必重启设备**，2026-10-03 实测）或重启设备 |
| 想停用模块 | LSPosed 取消作用域（`android` 项）后重启 |
| 分轨面板可用但无分离效果 | 检查是否有人写过 `mss_music_only=0`（`05-stem-any-app.md` §核心教训） |
| 想回到 v1.10 的「全部 App 自动放行」 | 装回 `artifacts/coloros-subtitle-unlock-v1.10.apk`（同签名可覆盖安装）；或按 `06-module-ui.md` 逐个勾选 |
