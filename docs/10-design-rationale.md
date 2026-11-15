# 10 · 设计缘由（SDK 抽取设计整理）

> 进阶篇。本文把原项目 `venera-native` 仓库中的 `docs/sdk/updater-sdk-design.md`（设计依据，内容已整理进本仓库 `docs/10-design-rationale.md`；状态 **Accepted**，日期 2026-11-15，基线 `main`、app 1.9.3）的结论**整理为简体中文**，供理解「为什么这样设计」。
>
> **该源文件只读、禁止修改。** 本文保留其结论、六处项目耦合、AAR 构建缺口、目标设计、迁移路径、风险与不做事项。文末附「落地对照」——设计文档写于抽取之前，个别命名与本仓库实际实现不同，接入时一律**以本仓库源码与 [09 篇](09-llm-playbook.md) 为准**。

---

## 1. 结论

**能抽，而且底子很好。** 现有实现已经落在独立模块 `:data:update` 里，只依赖 OkHttp、coroutines、core-ktx、WorkManager 四个外部库，不引用本项目的任何业务模块，也没有 `res/` 资源。

真正阻碍复用的**不是架构**，而是：

- **六处把本项目约定写死的地方**（第 3 节），改成配置对象即可；
- **三处会阻碍 AAR 消费的构建缺口**（第 4 节），补齐即可。

抽取后本仓库继续作为第一个消费者，行为不变。

---

## 2. 现有能力盘点（抽取的前提）

`data/update` 的职责划分本来就很清楚：

| 文件 | 职责 | 复用价值 |
|---|---|---|
| `GitHubReleaseApi` | GitHub Releases `releases/latest` 匿名读取，HTTP 错误映射 | 通用 |
| `ReleaseSelection` | 纯规则：挑 APK、解析版本号、比较、清洗文件名 | 通用（版本号规则需可配） |
| `ApkDownloader` | 流式下载、断点续传（`Range`）、代理优先 + 直连回退、`.part` 原子改名、ZIP 头校验 | **通用，是最值钱的部分** |
| `ApkInstaller` | `FileProvider` + `ACTION_VIEW` 拉起系统安装器 | 通用（authority 需可配） |
| `UpdateStateStore` | 「已下载版本 → APK 路径」持久化，脏记录自动清理 | 通用（文件名需作用域化） |
| `UpdateWorkScheduler` / `UpdateDownloadWorker` | WorkManager 前台下载，带进度与网络约束，进程被杀可续 | 通用（work 名需作用域化） |
| `UpdateNotifier` | 通知渠道、前台通知 | 通用（文案需可配） |
| `SelfUpdateService` | 门面：检查、预下载、前台下载、安装、缓存 | 通用（构造参数需配置对象化） |

测试覆盖 **26 项**，分布在四块容易出错的地方（`ApkDownloaderTest` 9 + `GitHubReleaseApiTest` 3 + `ReleaseSelectionTest` 10 + `UpdateStateStoreTest` 4 = 26）：

- 选包 / 版本号解析（`ReleaseSelectionTest`，10 项）；
- GitHub JSON 与错误映射（`GitHubReleaseApiTest`，3 项）；
- 下载语义（`ApkDownloaderTest`，9 项：续传、非 APK 拒绝、HTTP 错误零写入、陈旧文件覆盖、416 陈旧 `.part` 从头重下、失败保留 `.part`、4 连接分段并行下载、Range 被忽略时回退单流）；
- 已下载版本 → APK 路径的持久化与脏记录清理（`UpdateStateStoreTest`，4 项）。

这四块是有契约的，抽取时可以放心冻结。逐项说明见 [11 篇](11-testing-and-build.md)。

**未覆盖**（抽取时仍需注意）：`ApkInstaller`、`UpdateNotifier`、`UpdateWorkScheduler/Worker`、`SelfUpdateService.checkAndPrefetch` 的状态迁移，代理 → 直连回退分支，`UpdateStateStore` 的 SharedPreferences 读写。这些靠真机验证，见 [06 篇](06-behavior-and-edge-cases.md) 与 [11 篇](11-testing-and-build.md)。

---

## 3. 六处焊死本项目的约定

| 约定 | 原位置 | 影响 | 本 SDK 的化解 |
|---|---|---|---|
| `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH` | `ReleaseSelection.kt:29-41` | 别的项目版本方案不同，直接失效 | `ReleaseVersionResolver.TagScheme(majorMultiplier, minorMultiplier)`，乘数可配 |
| 资产名 `…-<digits>.apk` | `ReleaseSelection.kt:22-26` | 同上 | `AssetNameSuffix(separator)`，分隔符可配；`Or` 组合策略 |
| 中文通知文案、渠道 id `venera.self_update`、通知 id `2002` | `UpdateNotifier.kt:67-79` | 别的项目改不动文案 | `UpdaterStrings`（4 个文案可覆写）；渠道 id `<scope>.updates`、通知 id `scope.hashCode()` |
| 缓存目录 `"self-update"`（两处重复硬编码） | `SelfUpdateService.kt:93`、`UpdateDownloadWorker.kt:32` | 必须和 `file_paths.xml` 对齐，改一处漏一处 | `UpdaterPaths.updaterCacheDir()` **单一真源**，与 `updater_file_paths.xml` 对齐 |
| 全局单例：unique work 名 `"self_update"`、SharedPreferences 名、进程级 OkHttp 持有者 | `UpdateWorkScheduler.kt:19-21`、`UpdateStateStore.kt:45`、`UpdateEnvironment.kt:14-24` | 一个 app 里两个更新源会互相 `REPLACE` / `cancel` | `scope` 派生 work 名 `<scope>.download`、prefs `<scope>.updater`、缓存目录；`WorkerHttp` 仍进程级（见风险） |
| FileProvider authority 字面量在 app 层手写，且与 manifest 手工同步 | `SelfUpdateConfiguration.kt:9`、`app/src/main/AndroidManifest.xml:43` | 接错就装不上，且错误在运行期才暴露 | 库 manifest 用 `${applicationId}.updater` + `UpdaterConfig.fileProviderAuthority = "$packageName.updater"`，两侧由同一占位符推导 |

---

## 4. 三处阻碍 AAR 消费的构建缺口

1. **库没有 `AndroidManifest.xml`**。权限（6 个）、`FileProvider`、`SystemForegroundService` 的 `dataSync` 合并节点、`res/xml/file_paths.xml` 全在 `:app` 里。别人接入必须逐条抄，抄漏一条就是运行期崩溃或 MIUI 上装不上。
   → 本 SDK 自带 `updater/src/main/AndroidManifest.xml` 合并全部条目 + `res/xml/updater_file_paths.xml`，消费者零清单负担。
2. **没有 `consumer-rules.pro`**。`UpdateDownloadWorker` 由 WorkManager 按类名反射实例化，消费方开 R8 后类名被重写就直接失败。
   → 本 SDK 提供 `consumer-rules.pro`（keep 两参构造），经 `consumerProguardFiles(...)` 随 AAR 分发。
3. **没有发布配置**，且公开签名里的 `OkHttpClient` / `Flow` 来自 `implementation` 依赖，AAR 消费方编译期拿不到这些类型。
   → 本 SDK 用 `maven-publish` + `singleVariant("release") { withSourcesJar() }`，并把 okhttp / coroutines 改成 `api(...)`。

---

## 5. 目标设计

### 5.1 一个配置对象，一个入口

> ⚠️ **以下代码块摘自原项目设计稿（写于 SDK 抽取之前），是历史伪代码：其中类名、包名、构造形式均为当时的计划值，本仓库真实入口是 `Updater.create(context, config, httpClient?)`，不可直接复制使用。** 接入一律以本仓库源码与 [09 篇](09-llm-playbook.md) 为准。

```kotlin
val updater = Updater.create(
    context = applicationContext,
    config = UpdaterConfig(
        owner = "lwtor",
        repository = "venera-native",
        fileProviderAuthority = "${packageName}.updater",
    ),
    httpClient = sharedHttpClient,   // null 时内部自建
)
```

`UpdaterConfig` 承载全部项目相关决定：仓库、authority、作用域、版本解析策略、文案。

> 落地形态见 [09 §2.3](09-llm-playbook.md)：入口是 `Updater.create(context, config, httpClient?)`。

### 5.2 版本号由策略决定，不写死

```kotlin
fun interface ReleaseVersionResolver {
    fun versionCodeOf(releaseTag: String, apkAssetName: String): Long?
}
```

内置三种：`AssetNameSuffix`（读资产名尾部数字，默认）、`TagScheme`（从 `v1.9.3` 按 `MAJOR*1e6 + MINOR*1e3 + PATCH` 折算）、`Or`（先试前者，失败再用后者）。

这样本项目仍保持「资产名优先、tag 回退」，而别的项目（比如用语义化 `versionCode` 或直接在仓库里放 JSON 的）换一个策略即可。

### 5.3 作用域隔离

`scope`（默认 `"self_update"`）派生出：WorkManager unique work 名、SharedPreferences 文件名、缓存目录 `cacheDir/updater/<scope>`、通知渠道 id、通知 id。

意义：同一个 app 里挂两个更新源（主包 + 插件包）不会互相踩；测试时可以给每个用例一个独立 scope，不污染真实数据。

### 5.4 清单自动合并

SDK 自带 `AndroidManifest.xml`，把 6 个权限、`FileProvider`（authority `${applicationId}.updater`）、`SystemForegroundService` 的 `foregroundServiceType=dataSync` 合并节点、`res/xml/updater_file_paths.xml` 一起打进去。接入方**不需要写任何 manifest 条目**。

authority 用 `${applicationId}.updater` 而不是写死字符串，保证「manifest 里声明的」和「运行期用 `packageName + ".updater"` 算出来的」永远一致——这正是现在这个项目里靠手写同步、最容易接错的一处。

### 5.5 发布产物

- `maven-publish` + `singleVariant("release") { withSourcesJar() }`。
- 版本走独立的 `updaterSdkVersion`（`gradle.properties`，初始 `1.0.0`），与 app 的 `versionName` 解耦：SDK 稳定后不必跟着 app 发版。
- `consumer-rules.pro` 保住 worker。
- 公开签名用到的库改成 `api`。

---

## 6. 迁移路径

1. `data/update` → `lib/updater`，包名 `dev.veneranative.data.update` → `dev.veneranative.updater`（**落地为 `dev.cuculus.update`，见文末对照**）。
2. 引入 `UpdaterConfig` / `UpdaterStrings` / `ReleaseVersionResolver`，删掉 `UpdateEnvironment` 全局持有者，其余内部实现基本照搬。
3. 补库 manifest、`updater_file_paths.xml`、`consumer-rules.pro`、publish 配置。
4. `:app` 侧：`AppGraph` 改为传配置对象；`AppRoot` / `feature:profile` 只改 import。
5. 删除 app 里重复的权限、`FileProvider` 块与 `file_paths.xml`。

行为不变，源项目 `data/update` 的 23 项测试全部保留（与本仓库 SDK 侧的 26 例是两个不同测试集）。

> **重要阶段约束（现状）**：原项目代码**目前保持不动**，旧 `data/update` **尚未删除**，必须等通过本 SDK 接入并验证无异常后才能删旧代码。完整映射表、分步流程与接入验证清单见 [07 篇](07-migration.md)。

---

## 7. 风险与不做的事

### 风险

- 库 manifest 声明 `REQUEST_INSTALL_PACKAGES` 会被合并进所有消费者。这是自升级功能的必要前提（MIUI / HyperOS 不加会拒绝 `ACTION_VIEW`），但对「只是把这个库当依赖」的 app 来说多了一个敏感权限。缓解：在接入文档里显式写明，并在 `UpdaterConfig` 的 KDoc 里标注。
- `compileSdk 37` 会写进 AAR 的 `minCompileSdk`，消费方编译 SDK 必须 ≥ 37。这是硬约束，只能文档说明。
- Worker 拿不到 Service 实例的 OkHttp，只能走一个进程级槽位（`internal`，KDoc 说明原因）。这不是设计洁癖问题，是 WorkManager 反射实例化的固有约束（本 SDK 为 `WorkerHttp`）。

### 明确不做

- **不做「静默安装」**：需要设备管理员或 root，与安全模型冲突。
- **不做多平台（iOS / 桌面）**：版本比对与下载可复用，安装不可能。
- **不内置任何 UI**：只暴露状态与动作，角标、对话框、进度条由接入方自己画。本项目 `feature:profile` 就是参考实现。
- **不发布到 Maven Central**：签名、S01、发布审核的运维成本与收益不匹配。先支持 `mavenLocal` 与公司内网仓库，需要时再加。

---

## 附：落地对照（设计文档 → 本仓库实际实现）

> 设计文档写于抽取之前，以下命名是**计划值**，与本仓库实际不同。**接入 / 改代码一律以本仓库源码为准**，本附仅用于消除阅读设计文档时的混淆。

| 设计文档（计划） | 本仓库实际 |
|---|---|
| group `dev.veneranative`，artifact `updater` | `dev.cuculus:cuculus-update:1.0.0` |
| 包名 `dev.veneranative.updater` | `dev.cuculus.update` |
| `SelfUpdateService` / `SelfUpdateGateway` | `Updater` / `UpdateGateway` |
| `SelfUpdateCheck` / `SelfUpdateStatus` | `UpdateCheck` / `UpdateStatus` |
| `GitHubRelease` / `GitHubReleaseAsset` | `ReleaseInfo` / `ReleaseAsset` |
| `ApkDownloadProgress` / `ApkInstallResult` | `UpdateProgress` / `InstallResult` |
| `GitHubReleaseException` / `ApkDownloadException` | `ReleaseApiException` / `DownloadException`（统一继承 `UpdaterException`） |
| `UpdateEnvironment` | `WorkerHttp` |
| 模块 `:data:update` → `lib/updater` | 独立仓库模块 `:updater`（含 `:sample`） |
| unique work `"self_update"`、渠道 `venera.self_update` | `"<scope>.download"`、`"<scope>.updates"`（scope 默认 `self_update`） |
| 缓存目录 `"self-update"` | `cacheDir/updater/<scope>` |
| authority `dev.veneranative.selfupdate` | `${applicationId}.updater` |
| `USER_AGENT = "Venera-Native-Android"` | `"cuculus-update"` |

设计文档中的「结论 / 六处耦合 / 构建缺口 / 目标设计 / 迁移路径 / 风险与不做事项」均已在本 SDK 落地，只是命名按 Cuculus 系列（不绑定 Android / GitHub、为 KMP / Flutter 留位）重新确定。
