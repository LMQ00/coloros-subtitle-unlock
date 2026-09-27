# 声音分轨：让任意 App 都能用（纯 LSPosed）

> 前置：`04-stem-separation.md`（判定链、`mss_music_only` 与 atlasservice 缓存机制）

## 判定链（全在 native）

```
SpecailizerPLService#setMssEnable(pkg, 1)          AIDL transaction 42
  → isVocalAdjustSupported(pkg)                    libSpecailizerPLService.so @0x1b0b4，跑在 atlasservice
      → 查 mss-whitelist：attribute bit4 置位的包，再问
      → isMssMusicOnly()                           @0x1ad74
          ← 值 = audioserver 里 AudioFlingerExtImpl[+0x512] 的 mss_music_only 参数（机型默认 1）
          ← **在 atlasservice 进程内缓存**（[this+0x228] 有效标志 / [this+0x229] 值），
            构造函数只清标志，只有**进程重启**才会重新取值
```

两种放行状态（实测）：

| `mss_music_only` | 判定 |
|---|---|
| 1（默认） | 只放行 mss-whitelist 里 attribute bit4=0 的包 ⇒ 「只能在音乐软件用」 |
| 0 | 不再看白名单，**任意 App 放行**（chrome / 微信 均返回 0） |

## 做法

模块作用域加 **System Framework**，在 system_server（uid 1000，持 `MODIFY_AUDIO_SETTINGS`）内：

1. `AudioManager.setParameters("mss_music_only=0")` ⇒ 写进 audioserver；
2. `SystemProperties.set("ctl.restart", "atlasservice")` ⇒ 让 init 重启它，缓存失效 ⇒
   此后首次查询读到 0 ⇒ 任意 App 放行。

顺序不可颠倒：参数必须在 atlasservice **重启之前**写好。

## 权限依据（设备策略实测）

```
(allow system_server ctl_restart_prop (property_service (set)))   ✓  ← 第 2 步能走通的原因
(allow system_server ctl_start_prop   (property_service (set)))   ✓
(allow system_server audioserver      (process (signal)))         ← 只对 audioserver
# atlasservice / mmlistservice：只有 binder/fd/fifo，**没有 process signal** ✗ ⇒ kill 走不通
```

SMC App 里原有的注入（`Application#onCreate` / `MssService#onStartCommand`）保留：它保证
`mss_music_only=0` 在用户打开分轨面板前已写入 audioserver。

## 已放弃：改白名单文件

曾尝试把 `/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml`（`system:system 644`）改成「所有包
attribute=3」。结论：**放弃**。证据：

- 写它需要 uid=1000 + SELinux `write oplus_multimedia_file`，确实只有 system_server 满足；
- 但它**不能可靠触发重读**：`IMMListService` transaction 1 **不是**重载入口（写文件 + `service call
  MMListService 1` 无效 ✗），只有**重启 `mmlistservice`** 才重读（实测 ✓）；而 system_server 对
  `mmlistservice` 没有 `process signal` 权限 ✗（只能用 `ctl.restart`）；
- 更关键的是**有副作用**：把全部条目的 attribute 统一改成 3 之后，真机上「调人声/背景音」直接失效
  （用户实测），说明该字段不只表示白名单准入，还参与分轨功能自身的行为。

⇒ 白名单文件保持**原样不动**（`189573 bytes`、`<version>20260225</version>`、sha256 `285d8964…`）。

## 生效条件与验证

1. 安装模块，LSPosed 作用域勾选 **System Framework** +「AI 语音摘记」+「Atlas」+「声音分轨」。
2. 重启设备（模块在 system_server 启动后自动完成上面两步）。
3. 验证：

```sh
su -c "service call SpecailizerPLService 42 s16 com.android.chrome i32 1"   # 期望 0
su -c "grep -a stem /data/adb/lspd/log/modules_*.log | tail -3"             # 期望「已下发…已重启」
su -c "pidof atlasservice"                                                  # 每次开机应换成新 pid
```

## 局限

- 需要 **System Framework** 作用域；模块在该进程内只跑一个后台线程 + 一次属性写，不做方法 hook。
- 每次开机重启 atlasservice 一次（进程无状态，代价是一次 binder 重连）。
- 白名单文件不再被触碰，因此不会影响分轨本身的行为。
