# 声音分轨：解除「任意 App」限制（纯 LSPosed）

> 前置：`04-stem-separation.md`（判定链、`mss_music_only` 与 atlasservice 缓存机制）
> 本文记录**不改 native、不动系统库**的方案：让任意 App 都能启用分轨。

## 为什么 Java 只能从「数据」下手

判定链全在 native：

- `SpecailizerPLService`（跑在原生进程 `atlasservice`）负责判定；
- 白名单数据由**另一个原生进程 `mmlistservice`**（`u:r:mmlistservice:s0`）解析并提供；
- App 启用成功后调的 `setMssTracksGain` 对 audioserver 是空操作
  （`HoloAudio: onCallback,default event 21, do noting`），真正入集合的 `setMssEnableInt`
  只能由通过 `isVocalAdjustSupported` 的 `setMssEnable` 触达。

⇒ Java 侧唯一能改的是**白名单数据文件**：

```
/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml   (system:system 644)
```

## 为什么必须挂在 System Framework

写该文件要同时满足：

| 条件 | 依据 |
|---|---|
| DAC：uid 必须是 1000（文件属主 system） | 文件 `system:system 644` |
| SELinux：域必须被允许 `write oplus_multimedia_file` | 设备策略 .cil 实测：`system_server` ✓、`platform_app` ✓、`mmlistservice` ✓、`mediaserver` ✓、`atlasservice` ✓ |

`platform_app` 域虽被允许，但常驻进程（SystemUI、Launcher…）跑的是**普通应用 uid**（`u0_a251` 之类），
DAC 就挡住了；同时满足两条、且能被 LSPosed 注入的 Java 进程只有 **`system_server`**
（作用域里的 **System Framework**，`arrays.xml` 中写 `android`）。

## 实现

模块在 system_server 内（后台线程，最多重试 30×10s 等 PackageManager 就绪）：

1. 读 `Multimedia_Daemon_Online_List.xml`；
2. `PackageManager.getInstalledPackages(0)` 枚举所有已安装应用；
3. 把 `<mss-whitelist>` 段整体替换为「所有包 + `<attribute>3</attribute>`」
   （3 = bit0 置位、bit4 清零 ⇒ 直接跳过 `isMssMusicOnly()`），并把 `<version>` 提到 `20991231`
   （必须高于内置 `/system_ext/etc/Multimedia_Daemon_List.xml` 的 version）；
4. **就地截断写入**（SELinux 允许 `write` 但不允许 `rename`，故不能用「临时文件+改名」）；
5. 调 `IMMListService` 的 **transaction 1** 触发 `mmlistservice` 重载，立即生效。

## 实测证据（2026-09-25，root 手工复现同一步骤）

```
# 1) 只重启 atlasservice 不生效（白名单不由它解析）
# 2) 把 com.android.chrome / com.google.android.youtube 加进 online 文件（attr=3, version=20991231）
# 3) 重启 mmlistservice
service call SpecailizerPLService 42 s16 com.android.chrome i32 1      → 0  ✓（原先 ffffffff）
service call SpecailizerPLService 42 s16 com.google.android.youtube i32 1 → 0 ✓
# 4) 反向验证：从文件里删掉 com.heytap.music 并重载 → 它变成 ffffffff（被拒）✓
# 5) 不重启服务、只调 MMListService transaction 1 → 新加的包立刻放行 ✓（即重载入口）
```

## 生效条件与验证

1. 安装模块，LSPosed 作用域勾选：**System Framework** +「AI 语音摘记」+「Atlas」+「声音分轨」。
2. 重启设备（system_server 重新加载模块后立即重写白名单并重载）。
3. 验证：

```sh
su -c "service call SpecailizerPLService 42 s16 com.android.chrome i32 1"   # 期望 0
su -c "grep -a 'whitelist' /data/adb/lspd/log/modules_*.log | tail -3"      # 期望看到「已写入 N 个包」
```

## 局限

- 需要 **System Framework** 作用域（比 App 作用域重；模块在该进程内只跑一个后台线程 + 文件 IO，
  不做任何方法 hook，异常全部捕获）。
- 每次开机重写 ⇒ 新装应用下次开机自动纳入；OTA/在线更新覆盖后也会被重写回来。
- 白名单彻底失效（所有包 attribute=3），这正是本方案的目的。
