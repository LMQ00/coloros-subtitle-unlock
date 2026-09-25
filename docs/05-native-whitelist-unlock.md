# 声音分轨：解除「任意 App」限制（native 补丁）

> 前置：`04-stem-separation.md`（判定链、`mss_music_only` 与 atlasservice 缓存机制）
> 本文记录**绕过 `mss-whitelist` 白名单**的方案：让任意 App 都能启用分轨。

## 为什么只能走 native

面板流程里，App 在「启用成功」后调 `setMssTracksGain`（transact 44）——该接口**不查白名单**，
但 audioserver 对它只打一行 `HoloAudio: onCallback,default event 21, do noting`，**不做任何事**。
真正把包加入 MSS 处理集合的是 `setMssEnableInt` → `callClient(event=17)`，
而它**只能**由通过 `isVocalAdjustSupported` 判定的 `setMssEnable` 触达。

⇒ 对不在白名单里的 App，即使客户端「假装成功」，DSP 也不会处理它的音频。
Java hook 到此为止，必须动 native。

## 补丁

`/system_ext/lib64/libSpecailizerPLService.so` 中：

| 项 | 值 |
|---|---|
| 函数 | `SpecailizerPLService::isVocalAdjustSupported(std::string const&)` |
| 偏移 | `0x1b0b4`（该 .so 的 `.text` 段 `addr == file offset`，故文件偏移同为 `0x1b0b4`） |
| 原字节 | `3f2303d5 ff4305d1`（`paciasp; sub sp, sp, #0x150`） |
| 补丁后 | `20008052 c0035fd6`（`mov w0, #1; ret`） |
| 原库 sha256 | `b38a6d3507f5bbbb504b609540df3b458cb28faa24bc4ac67e72ef327113f5ea` |
| 补丁后 sha256 | `9131bd371cd7c63420cbb7365364e553660b0846e7541b2c85ac05979b29e902` |

**影响面**：`isVocalAdjustSupported` 在整个 .so 中**只有 `setMssEnable` 一个调用点**
（0x1b374），因此该补丁只放开 MSS 启用门，不改变其他行为。

效果：`isVocalAdjustSupported` 恒为 true ⇒ 白名单不再参与判定 ⇒ **任意包名**都能
`setMssEnable → setMssEnableInt` ⇒ 任意 App 可启用分轨。

## 交付形态

KernelSU/Magisk 模块 `mss_native_unlock`：

```
mss_native_unlock/
├── module.prop
├── customize.sh                                    # 安装时校验原库 sha256
└── system/system_ext/lib64/libSpecailizerPLService.so   # 补丁后的库（/system_ext 是独立分区，
                                                         #  故经 /system/system_ext 符号链接覆盖）
```

构建产物：`~/tmp/mss_native_unlock.zip`；安装：`ksud module install <zip>`，重启后 overlay 生效。

**回滚**：删除 `/data/adb/modules/mss_native_unlock/` 并重启。

## 验证（重启后）

```sh
# 选一个肯定不在白名单里的包名
su -c "service call SpecailizerPLService 42 s16 com.android.chrome i32 1"
# 期望 Parcel(00000000 00000000)（补丁前是 ffffffff）
```

同时 `logcat` 里应看到 `isVocalAdjustSupported` 不再输出 `not support vocal adjust!`。

## 局限与风险

- **绑定 ROM 版本**：偏移只对 sha256 `b38a6d35…` 的库有效；系统更新后需重新定位
  （`customize.sh` 会在指纹不符时提示）。OTA 会覆盖本模块的 overlay，需重装。
- 这是**系统库静态补丁**，非运行时 hook：与 `AGENTS.md` 原先「禁止 patch `.so`」的约束冲突，
  已按用户 2026-09-25 的明确选择（「B：native hook」）解除该约束。
- 补丁后白名单彻底失效（含 `bit0`/`bit4` 语义），所有 App 一律可启用——这是本方案的目的。
