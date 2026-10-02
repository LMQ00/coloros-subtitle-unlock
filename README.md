# ColorOS AI 音频功能解锁 (LSPosed 模块)

解除 ColorOS 16 系统 AI 音频功能的**客户端限制**。两个功能：

| 功能 | 目标 App | 现状 |
|---|---|---|
| 字幕每月 120 分钟限制 | `com.coloros.accessibilityassistant`（AI 语音摘记） | 模块已实现，**真机已验证** |
| 声音分轨限名单 App | `com.oplus.smartmediacontroller`（声音分轨） | 模块已实现（`system_server` 内按设置页勾选扩白名单），**真机已验证**（v1.10 全量路线） |

> 逆向对象在仓库上一层：`../AI 语音摘记_16.3.12.apk`、`../声音分轨_16.1.20.apk`。

**本 README 只做导航**：事实的唯一出处是 `docs/`（见下表），此处不复述机制与参数，避免两处漂移。

## 文档

| 想知道什么 | 读 |
|---|---|
| 接手第一步：现状、任务路由表、环境、未做缺口 | `docs/交接文档.md` |
| 项目总览、模块作用域、产物清单 | `docs/README.md` |
| 工程参数、构建与 CI、签名门禁、逆向工具链、真机日志 | `docs/development.md` |
| 怎么验证、「现在算不算完成」、装机与作用域、回滚 | `docs/testing.md` |
| 模块设置页：UI 结构、配置契约、白名单生成规则 | `docs/06-module-ui.md` |
| 字幕/分轨的逆向证据链与设计 | `docs/01`–`docs/05` |
| 工程硬性约束与开发规范 | `AGENTS.md` |

## 使用

1. **只有从旧版（未固定签名的构建）升级时才需要先卸载一次**：
   `/system/bin/pm uninstall com.lmq.coloros.subtitle`；固定签名之后可直接覆盖安装。
2. 安装 CI 产物（debug 签名；命名与取回方式见 `docs/development.md`）。
3. LSPosed 中启用模块，作用域勾选**两项**：`AI 语音摘记` + `System Framework`（`android`）。
   **不要**勾 `com.oplus.atlas` / `声音分轨` —— v1.9 起已无它们的代码。
4. 打开模块设置页（桌面图标或 LSPosed 管理器的「打开」）：
   - 两个字幕/分轨总开关；
   - **分轨白名单**：v1.11 起不再自动放行全部 App，需要哪个 App 用分轨就在设置页勾选它
     （新装 App 默认不放行）；
   - 保存后**无需重启**，模块会立即重写白名单并让 `mmlistservice` 重读。
5. 两个开关都是**即时生效**：字幕 hook 在每个回调里读配置缓存，分轨侧由配置变更事件触发重写；
   不需要重启目标 App 或整机。

## 构建与签名

走 GitHub Actions（push `main` → `.github/workflows/build.yml`），产物从 Actions 工件取回。

签名密钥固定：keystore **不进仓库**，以仓库 secrets 保存（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD`），CI 还原后签名。CI 最后一步用 `apksigner --print-certs` 校验证书
sha256（`EXPECTED_CERT_SHA256`），**不符即中断构建**，不会静默产出签名不一致的包。

证书 sha256：`57df9c0d999ea701131b4c1b3c9565c545102c030cea1db59645e4e47002f0bd`。
连续两次 CI 构建产出**字节相同**的 APK，可直接覆盖安装。

> keystore 本地备份：`~/tmp/coloros-unlock.keystore`，口令在 `~/tmp/ci-keystore/pw.txt`。
> 密钥只存在仓库 secrets 与本机 `~/tmp`，**丢了就只能换新密钥**（换密钥后需卸载重装一次）。

## 已知限制

见 `docs/交接文档.md` §未做 / 已知缺口。要点：

- **字幕**：配额由云端 / 系统 AIUnit 判定，模块只解除**客户端对限制的反应**；若云端在 `3000803`
  后彻底停止下发识别结果，客户端模块无法恢复。
- **分轨**：`attribute=2`（不支持人声调节）的包即使勾选也不保证可用；其它机型需按
  `docs/05-stem-any-app.md` §权限依据重验。
- **设置页状态区**只显示配置意图，真实生效结果要用 `docs/testing.md` 的探针自查。
