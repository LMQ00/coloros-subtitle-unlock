# 验证与状态（testing）

> 本页是「怎么验证」与「现在算不算完成」的**唯一出处**。
> 机制与逆向证据分别见 `04-stem-separation.md`、`05-stem-any-app.md`、`01-reverse-notes.md`。

## 当前状态（2026-09-28）

| 功能 | 状态 | 验证方式 | 备注 |
|---|---|---|---|
| 字幕每月 120 分钟限制 | **已验证**（用户实测） | 真机开字幕超过限制时长后不中断 | 云端在 `3000803` 后仍继续下发识别结果，客户端 hook 有效 |
| 分轨：白名单内 App（bilibili 等） | **已验证**（用户实测） | 面板可用 + 拖人声/背景有听感变化 | 修复了 `attribute=17` 被 `isMssMusicOnly()` 拒掉的问题 |
| 分轨：白名单外 App（Chrome/微信/优酷…） | **已验证** | 开机后探针返回 `0`，daemon 日志出现 `setMssEnableInt` | 由模块开机自动扩名单实现 |
| 分轨：分离通路本身 | **未被修改** | 开机后 `mss_music_only` 无任何写入 | 模块不触碰该参数（见 `05-stem-any-app.md` §核心教训） |

`[待确认]` 项见 `../docs/交接文档.md` §未做 / 已知缺口。

## 装机与作用域

1. 安装产物：`/system/bin/pm install -r <apk>`（从 v1.2 旧签名升级需先 `pm uninstall com.lmq.coloros.subtitle`）。
2. LSPosed → 模块「ColorOS AI 音频解锁」→ 作用域勾选**两项**：
   `AI 语音摘记`（`com.coloros.accessibilityassistant`）+ `System Framework`（`android`）。
   不要勾 `com.oplus.atlas` / `声音分轨` —— v1.9 起已无它们的代码。
3. 重启设备（或等下次开机）：模块在 system_server 启动后自动扩白名单并重启 `mmlistservice`。

## 验证步骤

### 1. 分轨准入（无需 UI）

```sh
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
# 期望：whitelist: 保留 26 条原有条目（7 条 bit4 清零），追加 N 条（attribute=3, version=20991231）
#       restart: ctl.restart mmlistservice
# 注：首次尝试常报 getInstalledPackages NPE（PackageManager 未就绪），5 秒后重试成功属正常。
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

## 回滚

| 场景 | 操作 |
|---|---|
| 想恢复出厂分轨行为 | 删除 `/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml` 并重启（内置名单重新生效） |
| 想停用模块 | LSPosed 取消作用域（`android` 项）后重启 |
| 分轨面板可用但无分离效果 | 检查是否有人写过 `mss_music_only=0`（`05-stem-any-app.md` §核心教训） |
