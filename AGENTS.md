# AGENTS.md — cuculus-update 仓库 Agent 入口

> 本文件是给「第一次进入本仓库的 AI / 工程师」看的入口。读完本文件再动手；需要一步到位接入 SDK，请直接跳到 [`docs/09-llm-playbook.md`](docs/09-llm-playbook.md)。

## 1. 项目定位

- **cuculus-update** 是一个**独立的 Android 自更新库（AAR）**：从 GitHub Releases 读取最新发布、挑选 APK、断点续传下载（WorkManager 前台 + 通知）、经 FileProvider 交给系统安装器。
- 它是 **Cuculus 工具系列**的 `update` 成员（命名取自杜鹃巢寄生：把新版本这颗蛋下到宿主窝里让宿主孵化）。命名与 API **刻意不绑定 Android / GitHub 平台**，为后续 KMP、Flutter 扩展留位——因此**不要**把 `dev.cuculus.update` 改成带 `android` 或 `venera` 字样的包名。
- 源项目为 [Venera Native](https://github.com/lwtor/venera-native)（见 `NOTICE`），本仓库是其自更新实现的**可配置、可发布**抽取版；Apache-2.0。
- Maven 坐标：`dev.cuculus:cuculus-update:1.0.0`；Kotlin 包名：`dev.cuculus.update`。
- **消费方零清单负担**：6 个权限、`SystemForegroundService`、`FileProvider` 全部由库 manifest 合并，App 不需要声明任何权限 / 服务 / provider。

## 2. 目录地图

```
cuculus-update/
├── AGENTS.md                    # 本文件：Agent 入口、不变量、命令、提交规范
├── llms.txt                     # 标准 LLM 索引（llmstxt.org），先读这个
├── README.md                    # 面向人的快速开始（勿改；除非明确授权）
├── build.gradle.kts             # 根构建：只声明 AGP 9.3.1 插件（apply false）
├── settings.gradle.kts          # rootProject.name="cuculus-update"；include(":updater", ":sample")
├── gradle.properties           # updaterSdkVersion=1.0.0（SDK 自身版本）
├── gradle/
│   ├── libs.versions.toml       # 版本目录（AGP 之外的依赖版本都在这里）
│   └── wrapper/gradle-wrapper.properties   # Gradle 9.5.0
├── gradlew / gradlew.bat        # wrapper；本机 gradlew 无执行位，用 `sh gradlew`
├── LICENSE / NOTICE            # Apache-2.0 与来源声明
├── updater/                     # ★ 库模块（:updater），产出 AAR
│   ├── build.gradle.kts         # com.android.library + maven-publish；singleVariant("release")
│   ├── consumer-rules.pro       # ★ keep worker 两参构造（R8 必需，见 §4）
│   └── src/main/
│       ├── AndroidManifest.xml  # ★ 合并进所有消费者的 6 权限 / service / provider
│       ├── res/xml/updater_file_paths.xml   # ★ 暴露 cacheDir/updater/
│       └── kotlin/dev/cuculus/update/*.kt  # 全部公开 API 与实现，见下
├── sample/                      # 经典 Views 演示（:sample），验证「清单合并」是否成立
│   └── src/main/AndroidManifest.xml        # 故意不写任何权限 / provider
└── docs/                        # 完整知识库（01–05 基础篇、06–11 进阶篇）
    ├── README.md               # 知识库总目录与阅读顺序
    └── 09-llm-playbook.md      # ★ 大模型一步到位接入手册（决策树 + 完整代码 + 验收）
```

`updater/src/main/kotlin/dev/cuculus/update/` 文件职责（**改代码前逐个读**）：

| 文件 | 公开性 | 职责 |
|---|---|---|
| `Updater.kt` | public | 门面 `Updater.create(...)`、`status`、`checkAndPrefetch` |
| `UpdateGateway.kt` | public | `UpdateGateway` 接口 + `UpdateCheck` / `UpdateStatus` 密封类型 |
| `UpdaterConfig.kt` | public | `UpdaterConfig` / `UpdaterStrings` / `ReleaseVersionResolver` |
| `ReleaseApi.kt` | public 数据类 + internal 实现 | `ReleaseInfo` / `ReleaseAsset`；GitHub `releases/latest` 解析 |
| `ReleaseSelection.kt` | internal | 挑 APK、版本比较、文件名 sanitize |
| `ApkDownloader.kt` | public 数据类 + internal 实现 | `UpdateProgress`；续传 / 代理回退 / ZIP 头校验 / 原子改名 |
| `ApkInstaller.kt` | public 结果类型 + internal 实现 | `InstallResult`；FileProvider + ACTION_VIEW |
| `UpdateStateStore.kt` | internal | `<scope>.updater` SharedPreferences，脏记录清理 |
| `UpdateDownloadWorker.kt` | **public** | WorkManager `CoroutineWorker`（反射实例化） |
| `UpdateWorkScheduler.kt` | internal | unique work `<scope>.download`，`REPLACE` |
| `UpdateNotifier.kt` | internal | 渠道 `<scope>.updates`、通知、`ForegroundInfo(dataSync)` |
| `WorkerHttp.kt` | internal object | 进程级 `OkHttpClient` 槽位（worker 无法注入） |
| `UpdaterPaths.kt` | internal | `updaterCacheDir()` — **唯一的缓存目录真源** |
| `UpdaterException.kt` | public | `UpdaterException` / `ReleaseApiException` / `DownloadException` |

`updater/src/test/kotlin/dev/cuculus/update/`：4 个测试类、23 例，见 [`docs/11-testing-and-build.md`](docs/11-testing-and-build.md)。

## 3. 构建 / 测试命令

> 环境：JDK 17（需自行配置 `JAVA_HOME` 指向本机 JDK 17，例如 Amazon Corretto 17）、Gradle wrapper 9.5.0、AGP 9.3.1（**内置 Kotlin，无需单独 Kotlin 插件**）、compileSdk 37、minSdk 26。
> 本机 `gradlew` 无执行位，一律用 `sh gradlew`，不要 `./gradlew`，也不要去 `chmod +x`（会改现有文件）。

```bash
export JAVA_HOME=/path/to/jdk-17   # 替换为你本机 JDK 17 的实际路径

# 一次性：单测 + AAR(release) + sample APK(debug)
sh gradlew :updater:testDebugUnitTest :updater:assembleRelease :sample:assembleDebug --console=plain

# 单独命令
sh gradlew :updater:testDebugUnitTest --console=plain          # 23 例 JVM 单测
sh gradlew :updater:assembleRelease --console=plain            # AAR：updater/build/outputs/aar/updater-release.aar
sh gradlew :sample:assembleDebug --console=plain               # sample APK
sh gradlew :updater:publishToMavenLocal --console=plain        # 发到 ~/.m2：dev.cuculus:cuculus-update:<updaterSdkVersion>
sh gradlew clean --console=plain
```

版本号来自 `gradle.properties` 的 `updaterSdkVersion`（当前 `1.0.0`），与任何消费方 App 的 `versionCode` / `versionName` 解耦。完整说明见 [`docs/11-testing-and-build.md`](docs/11-testing-and-build.md)。

## 4. 必须遵守的不变量（改代码前先确认没破坏它们）

1. **worker 反射构造的 keep 规则不可删**。`UpdateDownloadWorker` 由 WorkManager 按类名 + `(Context, WorkerParameters)` 两参签名反射创建；`consumer-rules.pro` 的
   `-keep class dev.cuculus.update.UpdateDownloadWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }`
   必须保留。删掉它 → 消费方开 R8 的 release 包下载直接崩。`UpdateDownloadWorker` 必须维持 `public`、构造签名不变。
2. **缓存目录与 `updater_file_paths.xml` 必须一致**。`UpdaterPaths.kt:updaterCacheDir()` 是唯一真源：`cacheDir/updater/<scope>`。`res/xml/updater_file_paths.xml` 用 `<cache-path path="updater/"/>` 暴露这个子树。`Updater.download()` / `checkAndPrefetch()` 与 `UpdateDownloadWorker` **都**经 `updaterCacheDir()` 解析目标，禁止在别处硬编码目录字符串。改目录必须同步改 file_paths，否则 `FileProvider.getUriForFile` 抛异常、安装失败。
3. **scope 命名即命名空间**。`scope`（默认 `"self_update"`）派生出：缓存目录 `cacheDir/updater/<scope>`、SharedPreferences `"<scope>.updater"`、unique work `"<scope>.download"`、通知渠道 `"<scope>.updates"`、通知 id `scope.hashCode()`。一个 App 多个更新源必须用不同 scope；scope 必须是合法文件名片段（不要含 `/`、`\`、空格等，`ReleaseSelection.sanitizeAssetFileName` 只清洗 asset 名，不清洗 scope）。
4. **versionCode 必须单调递增且严格大于已安装值**。`ReleaseSelection.isUpdate()` 只认 `available > installed`（相等/更小/解析为 null 都判 `UpToDate` / `NoInstallableAsset`）。`AssetNameSuffix()` 读资产名尾部数字、`TagScheme()` 把 `v1.9.3` 折算为 `1_009_003`（每字段 0..999 才单调）；`Or(primary, fallback)` 先资产名后 tag。**永远不要把 versionName 当 versionCode 传进 `check` / `checkAndPrefetch`**。
5. **清单合并不可在 sample 里重复声明**。`sample/src/main/AndroidManifest.xml` 故意为空（无权限、无 provider、无 service），它就是「库 manifest 合并生效」的回归测试。若你在 sample 里补声明权限/provider，等于把回归测试改坏了；需要验证合并结果请看合并后的 manifest（见 11 篇），而不是在 sample 里手写。
6. **公开签名里的依赖用 `api` 不用 `implementation`**。`OkHttpClient`（`Updater.create`）、`Flow` / `StateFlow`（`UpdateGateway` / `Updater.status`）出现在公开 API，必须是 `api(...)`，否则 AAR 消费方编译期拿不到类型。
7. **`REQUEST_INSTALL_PACKAGES` 与 `POST_NOTIFICATIONS` 语义不要改**。前者是 normal 权限（无运行时弹窗，用户仍逐次确认安装），后者虽由库声明、仍需在 API 33+ 运行时授权；未授权时下载照常进行、只是进度通知被隐藏。
8. **不做静默安装、不内置 UI、不发布 Maven Central**（见 [`docs/10-design-rationale.md`](docs/10-design-rationale.md)）。
9. **`Updater.create()` 只能在已 attach 的 Context 上调用**。`Updater` 构造立即读 `context.applicationContext` 并 `getSharedPreferences`；`UpdaterConfig` 惯例用 `"$packageName.updater"` 拼 authority。而 `Activity` 在 `attachBaseContext()` 之前 `mBase` 为 null，把 `private val updater = Updater.create(this, ...)` 写在**属性初始化器**里会直接 NPE、App 启动即崩。必须放 `Application.onCreate()`（推荐：进程级单例，也让冷启动恢复的 worker 拿到 `WorkerHttp.client`），最晚也要在 `Activity.onCreate()` 里传 `applicationContext`。sample 用 `SampleApp : Application()` + manifest `android:name=".SampleApp"` 演示正确写法，改动此处必须同步更新 README Quick start 与 09 篇禁止事项。

## 5. 提交规范

- 使用 **Conventional Commits**：`feat: ...` / `fix: ...` / `docs: ...` / `test: ...` / `refactor: ...` / `chore: ...`，可带作用域如 `docs(09): ...`。
- **每个小任务单独一个 commit**，不要把不相关的改动塞进同一个提交；提交信息正文写清「为什么」。
- 只提交本任务的产物：不要顺手改 `README.md`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`.gitignore`、任何 `.kt` 源码，除非该任务明确要求。
- 提交前跑一遍 §3 的单测与 assemble 命令，确认 23 例全绿。
- **本仓库当前由仓库所有者统一执行 `git add` / `git commit` / `git push`；执行具体写文档 / 改代码任务时不要自行提交。**
- 禁止强推、禁止 `--no-verify`、禁止改写已发布历史。

## 6. 改代码前先读哪些文档（按顺序）

1. `llms.txt` — 全局索引，判断该读哪篇。
2. 本文件 `AGENTS.md` — 目录地图、不变量、命令。
3. `README.md` — 现有公开 API 的人类可读说明（**以源码为准**，文档若与源码冲突信源码）。
4. `docs/README.md` — 知识库总目录与阅读顺序。
5. `docs/01-architecture.md` → `02-integration.md` → `03-api-reference.md` → `04-configuration.md` → `05-release-contract.md`（基础篇：架构与数据流 / 一页式接入 / API 签名 / 配置与版本编码 / 发布契约）。确切阅读顺序以 `docs/README.md` 为准。
6. **接入 / 排障 / 迁移 / 发布**分别读：`docs/09-llm-playbook.md`（接入必读）、`docs/06-behavior-and-edge-cases.md`（边界行为）、`docs/07-migration.md`（从 venera-native 迁移）、`docs/08-troubleshooting.md`（故障排查）、`docs/10-design-rationale.md`（为什么这么设计）、`docs/11-testing-and-build.md`（构建与验证）。
7. **改任何 `.kt` 之前**：先打开 `updater/src/main/kotlin/dev/cuculus/update/*.kt` 逐个核对签名，禁止凭记忆编造 API。

## 7. 与其他仓库的关系（重要阶段约束）

- 原项目 `venera-native` 的自更新代码仍在 `data/update`，**目前保持不动、尚未删除**；其设计文档（原项目 `venera-native` 仓库中的 `docs/sdk/updater-sdk-design.md`，设计依据，内容已整理进本仓库 `docs/10-design-rationale.md`）只读、禁止修改。
- **必须等本 SDK 在原项目里接入并验证无异常后，才能删除旧的 `data/update` 代码**。在此之前不要改动、删除原项目任何文件，也不要在接入验证通过前改原项目装配代码。详见 [`docs/07-migration.md`](docs/07-migration.md)。
