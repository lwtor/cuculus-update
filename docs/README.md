# cuculus-update 完整知识库 · 索引

本文件是整个 `dev.cuculus:cuculus-update:1.0.0` 知识库的**总索引**。目标：一个大模型（或一名工程师）**只读一遍**就能把该 SDK 独立、一步到位接入到任意 Android 项目，不用来回追问。

- 仓库：`lwtor/cuculus-update`（公开，main 分支），Apache-2.0，源自 Venera Native 的自更新能力（见 `NOTICE`）。
- 定位：Cuculus 工具系列的 **update** 成员（杜鹃巢寄生意象：把新版本这颗「蛋」下到宿主「窝」里，让宿主孵化）。命名与 API 刻意不绑定平台，便于系列日后扩展到 KMP / Flutter。
- 交付物：一个 Android library（AAR），Kotlin 包名 / namespace `dev.cuculus.update`，minSdk 26 / compileSdk 37 / JDK 17。
- 一句话能力：查 GitHub Releases 最新发布 → 选 APK asset → 可断点续传下载 → 落盘 → 通过 FileProvider 交给系统安装器。

> 知识库分两层：**核心 6 篇**（本文件 + `01`~`05`）自成体系，只读它们即可完成接入，不需要读源码、不依赖其它文档；**进阶 6 篇**（`06`~`11`）与根级 `llms.txt` / `AGENTS.md` 是深入与速查，按需查阅。`09` 因是大模型接入的核心速查入口而在 `llms.txt` 中列入优先阅读，编号上仍属于 `06`~`11` 进阶篇，两处口径不矛盾。正文用简体中文，代码 / 标识符 / 类名 / Gradle 命令保持英文原样。

---

## 全局铁律（先记住，细节在各篇展开）

| # | 必须做什么 | 绝对不要做什么 | 违反后果 |
|---|---|---|---|
| 1 | 只加一行 `implementation("dev.cuculus:cuculus-update:1.0.0")` | 不要在 App 的 `AndroidManifest.xml` 里声明权限 / Service / FileProvider | 清单合并冲突；或误以为要自己声明而重复配置 |
| 2 | `fileProviderAuthority` 必须等于 `${applicationId}.updater` | 不要随意改这个字符串 | `install()` 返回 `InstallResult.Failure("unable to expose the APK: ...")` |
| 3 | 在 app 模块开启 `buildFeatures { buildConfig = true }` 后读 `BuildConfig.VERSION_CODE.toLong()` | 不要把 `versionName`（字符串）当作 versionCode 传进去 | AGP 8+ 默认不生成 BuildConfig；类型不符直接编译失败 |
| 4 | 让「发布端 versionCode」与「App 的 `versionCode`」使用**同一套编码**，且发布端严格更大 | 不要只改 tag 文本却不改 versionCode | `check()` 永远返回 `UpToDate`，更新不触发 |
| 5 | 下载用 `downloadInForeground()`（离开页面仍继续），或把 `download()` 放在 `Dispatchers.IO` | 不要在 `Dispatchers.Main` 上直接跑 `download()`，也不要在 `onProgress` 里直接改 View | 阻塞主线程 / ANR；进度回调所在线程不确定 |
| 6 | `install()` 之后由系统弹窗让用户确认 | 不要假设 SDK 会静默安装或回报「安装成功」 | 误判安装结果（SDK 只返回 `Launched` / `Failure`） |
| 7 | 只调用 `03-api-reference.md` 列出的公开类型 | 不要 `import dev.cuculus.update.ApkDownloader` 等 `internal` 类 | 编译失败：它们对消费者不可见 |
| 8 | 把 `Updater.create()` 存成 Application 级单例 | 不要每个页面各建一个 `Updater` | `WorkerHttp` 是进程级客户端，多实例会互相覆盖其 proxy/DNS 配置 |

---

## 一、核心 6 篇 · 各解决什么问题

| 文件 | 标题 | 解决的问题 | 何时读 |
|---|---|---|---|
| `README.md`（本文件） | 索引与阅读路径 | 「我该先读哪篇」 | 第一个读 |
| `01-architecture.md` | 架构与数据流 | 库内部怎么跑、为什么这样设计、能力边界 | 排查问题、理解状态与落盘时 |
| `02-integration.md` | 一页式接入 | 「给我一份能直接复制粘贴跑起来的代码」 | **接入时必读，第一优先** |
| `03-api-reference.md` | API 参考 | 每个 public 类型的签名、参数、默认值、异常、协程/线程约束、状态流转 | 写代码查签名、查异常时 |
| `04-configuration.md` | 配置与版本编码 | `UpdaterConfig` 每个字段、`scope` 隔离、`versionResolver` 选型、versionCode 规则 | 配多更新源、覆盖文案、定版本方案时 |
| `05-release-contract.md` | 发布契约与检查清单 | App 维护者发布 Release 时必须遵守什么 | 打包发版、更新不生效时 |

---

## 二、进阶 6 篇与根级入口 · 全景

| 文件 | 标题 | 解决的问题 | 何时读 |
|---|---|---|---|
| `06-behavior-and-edge-cases.md` | 行为与边界情况 | 异常 / 边界场景下 SDK 的**确定行为**，每条按「现象 / 原因 / SDK 的处理 / 接入方该怎么做」给出 | 遇到诡异现象、需要确认某分支行为时 |
| `07-migration.md` | 从 venera-native 迁移到 cuculus-update | 原项目 `data/update` 自更新代码 → 本 SDK 的一一映射与分步迁移 | 从源项目迁移时 |
| `08-troubleshooting.md` | 常见故障与排查 | 每条给出「检查命令 / 日志线索 / 修复方式」 | 更新不弹、下载失败、安装报错时 |
| `09-llm-playbook.md` | 大模型「一步到位」接入手册 | 决策树、完整可复制代码、逐条可验证的验收清单；仓库根 `README.md` 指定的大模型首选入口 | 大模型生成接入代码、需要速查决策树时 |
| `10-design-rationale.md` | 设计缘由 | 把源项目 SDK 抽取设计结论整理成简体中文，解释「为什么这样设计」 | 想理解架构取舍时 |
| `11-testing-and-build.md` | 测试与构建 | 环境要求、完整命令清单、manifest 合并验证、真机验证、mavenLocal 演练、24 个单测逐项说明、CI 建议 | 构建 / 验证 / 发布 AAR 时 |
| `../llms.txt` | 根级精简摘要 | 面向大模型的仓库级极简说明 | 快速了解仓库全貌时 |
| `../AGENTS.md` | 根级 Agent 入口 | 第一次进入本仓库的 AI / 工程师入口，读完再动手 | 首次接触本仓库时 |

> `09-llm-playbook.md` 是大模型决策树 / 极简接入手册；`01`~`05` 提供它背后的完整事实底座（架构、逐个 API、配置、发布契约、完整接入代码）。两者互为补充，核心 6 篇不依赖进阶篇即可独立完成接入。

---

## 三、按角色推荐阅读路径

### 接入方（要把 SDK 装进 App 的工程师 / 大模型）
`02` → `03` → `04` →（出问题再回看）`01`；需要决策树速查时并行看 `09`。
- `02` 给出**可直接复制粘贴**的完整代码（Application 单例、检查、前台下载 + 进度 + 取消、安装、启动静默预下载 + 红点、Compose / ViewModel），每步附验收方式。
- `03` 用来核对签名与异常；`04` 用来确认 `owner` / `repository` / `fileProviderAuthority` / `versionResolver` 没配错。

### 发布方（维护 Release、打 APK 的 App 维护者）
`05` → `04`（versionCode 段）→ `03`（`UpdateCheck` / `ReleaseAsset`）。
- 重点：`releases/latest` 语义、asset 命名、versionCode 映射、签名一致、多 asset 挑选规则。末尾有**可直接抄的发布检查清单**。

### 排查者（更新不弹、下载失败、安装报错）
`08`（故障清单）→ `01`（数据流与状态机）→ `03`（异常传播矩阵）→ `04`（`scope` 与 authority）→ `05`（发布契约）；边界行为查 `06`。
- 常见根因：Release 是草稿 / 预发布、asset 名尾部没有可解析的 versionCode、versionCode 没有严格递增、APK 签名不一致、`fileProviderAuthority` 不匹配、API 33+ 未授予 `POST_NOTIFICATIONS`。

### 仓库维护者（构建 / 验证 / 发版 AAR）
`11`（命令与验证）→ `01`（清单合并）→ `10`（设计缘由）；本地发布用 `sh gradlew :updater:publishToMavenLocal --console=plain`。

### 从源项目迁移
`07`（一一映射）→ `02`（目标接入代码）→ `03`（签名核对）。

### 大模型（一步到位生成接入代码）
严格按此顺序读一遍：`02`（抄代码骨架）→ `03`（校验每个调用点签名）→ `04`（确定 `UpdaterConfig` 与版本编码）→ `05`（确认发布侧约定）→ `01`（确认没有踩能力边界）。
- 生成代码前先对照 `02` 的「该做什么 / 不该做什么 / 常见误判」自检；需要决策树时用 `09`。
- 所有 `internal` 类型一律不可调用；所有 public 签名以 `03` 为准，不要凭类名猜。
- 不要编造 `03` 之外的 API。`Updater` 实现 `UpdateGateway`，因此 `check` / `cachedApk` / `downloadInForeground` / `observeForegroundProgress` / `cancelForegroundDownload` / `download` / `install` 都能在 `Updater` 实例上直接调用。
- 不确定就按确定规则处理：**宁可显式传参、显式切线程、显式处理异常，也不要写含糊的「一般可以」**。

---

## 四、验证命令（本机，仓库维护者用）

```bash
sh gradlew :updater:testDebugUnitTest :updater:assembleRelease :sample:assembleDebug --console=plain
```

- `gradlew` 无执行位，必须用 `sh gradlew`。
- JDK 17：需自行配置 `JAVA_HOME` 指向本机 JDK 17（例如 Amazon Corretto 17）。
- 单测 4 个类共 **24 例**：`ReleaseSelectionTest` 10 / `ApkDownloaderTest` 7 / `GitHubReleaseApiTest` 3 / `UpdateStateStoreTest` 4；逐项覆盖说明见 `11-testing-and-build.md`。
- 本地发布到 Maven：`sh gradlew :updater:publishToMavenLocal --console=plain`。
