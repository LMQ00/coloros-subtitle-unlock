# 声音分轨：让任意 App 都能用（追加式白名单）

> 前置：`04-stem-separation.md`（判定链、白名单与 `mss_music_only`）

## 判定链（全在 native）

```
SpecailizerPLService#setMssEnable(pkg, 1)         AIDL transaction 42
  → isVocalAdjustSupported(pkg)                  libSpecailizerPLService.so @0x1b0b4（跑在 atlasservice）
      → 查 mss-whitelist（数据由 mmlistservice 从 XML 解析并提供）
          不在名单内 → 拒绝（返回 ffffffff）
          名单内且 attribute bit4 置位 → 再问 isMssMusicOnly()
      → isMssMusicOnly()                         @0x1ad74，值 = audioserver 的 mss_music_only 参数（机型默认 1）
```

## ⚠️ 核心教训：不要碰 `mss_music_only`

把该参数压成 0，**准入**确实会放开（名单外 App 也能开分轨面板，binder 探针返回 0），
但音频策略会因此不再为该 App 强制 MSS 通路：

```
AudioPolicyManagerExtImpl::shouldNotForceMss()      要求 [this+0xfc] == 1（构造函数用 property_get 取值）
AudioPolicyManagerExtImpl::shouldForceMssBySession() → ListWrapperRouter::checkInListByUid("mss-whitelist", uid, ...)
AudioPolicyManagerExtImpl::oplusForceOutputForMss()
```

真机症状与证据（用户实测）：

- 面板能开、滑块能拖，**但听不出任何变化**；
- audioserver 日志：`OplusMssAudio: chooseWhichTrackToProcess_l ... tracks[0|0] voc_adj_on[0]`
  ⇒ 引擎**没找到要处理的音轨**，只做 `OplusMssManager: copyBufferToMix`（原样搬运）；
- 同一时刻命令链其实是通的（`setMssEnableInt --- <pkg>[1]`、`setMssTracksGainInt`、`result:0`）。

另一个坑：`AudioManager.setParameters` 会被 **`IAudioService.cacheParameters` 缓存**，
之后每次 audioserver 重启都由 system_server 重放（实测日志
`KVP received: mss_music_only=0;update_uidmap=...`），所以**只重启 audioserver 清不掉**，
必须重启整机。

## 正确做法：只扩名单

模块作用域加 **System Framework**（`android`），在 system_server（uid 1000）内：

1. 读**内置**白名单 `/system_ext/etc/Multimedia_Daemon_List.xml` 作为底稿
   （它 version 最高、内容最新），**原样保留**其全部 `<name>/<attribute>` 条目 ——
   绝不改写既有 attribute；
2. 把名单内 attribute **bit4 置位**的条目清零为 `3`（bit4 = 「非音乐类」位：机型默认
   `mss_music_only=1` 时这类 App 会被 `isVocalAdjustSupported` 直接拒掉，daemon 日志只打印
   `isVocalAdjustSupported: supportType=17` 而没有随后的 `setMssEnableInt` 行 —— bilibili 就是这样被拒的；
   实测清零后立刻放行，且分离通路不受影响）；
3. 只为「已安装但不在名单内」的包**追加** `<attribute>3</attribute>`
   （bit0 = 支持人声调节，bit4 = 0 不受「仅音乐」判定限制）；
4. 把 `<version>` 提到 `20991231`（必须高于内置文件的 version，否则内置文件胜出），
   写入可写的在线白名单 `/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml`
   （**就地截断写**：SELinux 只允许 `write`，不允许 `rename`）；
5. `SystemProperties.set("ctl.restart", "mmlistservice")` 让解析白名单的原生进程重读。

### 权限依据（设备策略实测）

```
(allow system_server ctl_restart_prop (property_service (set)))   ✓  ← 第 4 步的依据
(allow system_server ctl_start_prop   (property_service (set)))   ✓
# atlasservice / mmlistservice：只有 binder/fd/fifo，没有 process signal ⇒ kill 走不通
```

## 验证

验证步骤与实测结果（含开机自动路径）见 **`testing.md`**。本方案的关键判据：
daemon logcat 必须出现 `isVocalAdjustSupported: supportType=3` **且**紧随
`setMssEnableInt --- <pkg>[1]` —— 只有前者说明被 `isVocalAdjustSupported` 拒掉了。

## 局限

- 需要 **System Framework** 作用域；模块在该进程内只跑一个后台线程 + 一次文件写，不做方法 hook。
- 每次开机重写白名单并重启一次 `mmlistservice`（进程无状态）。
- 不触碰 `mss_music_only`，因此分轨的**分离通路**与出厂行为完全一致。
