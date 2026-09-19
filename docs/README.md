# 交接文档索引

> 项目：解除 ColorOS「AI 语音摘记」开启字幕的每月 120 分钟限制
> 状态：模块已编译产出可安装 APK，待真机实测

## 文件

| 文件 | 内容 |
|---|---|
| `01-reverse-notes.md` | 逆向结论与证据链（类/方法/状态码） |
| `02-module-design.md` | 模块 hook 设计说明 |
| `03-pitfalls.md` | 踩坑记录与规避 |
| `../AGENTS.md` | 工程约定（构建、目录、约束） |
| `../module/` | LSPosed 模块源码（GitHub Actions 编译） |

## 产物

- 模块源码仓库：https://github.com/LMQ00/coloros-subtitle-unlock （public）
- 已编译 APK：`../coloros-subtitle-unlock-v1.0.apk`（debug 签名，可直接安装）

## 下一步

1. 真机安装 APK，LSPosed 启用模块，作用域勾选「AI 语音摘记」。
2. 强制停止并重启「AI 语音摘记」。
3. 开启字幕后实测是否仍受 120 分钟限制；抓 `logcat -s ColorOSSubtitleUnlock` 看 hook 日志。

## 关键风险

配额由云端判定。若云端在返回 `3000803` 后停止下发识别结果，客户端模块无效。
详见 `01-reverse-notes.md` 末尾「未决问题」。