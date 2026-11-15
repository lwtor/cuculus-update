# 07 · 从 venera-native 迁移到 cuculus-update

> 进阶篇。本文给出原项目 `venera-native` 的 `data/update` 自更新代码 → 本 SDK 的**一一映射**、分步迁移流程、App 侧调用点改造示例、接入验证清单。
>
> 事实来源：原项目 `venera-native` 仓库中的 `data/update/src/main/kotlin/dev/veneranative/data/update/*.kt`、app 侧 `AppGraph.kt` / `SelfUpdateConfiguration.kt`、`feature/profile/.../AboutViewModel.kt` / `AboutRoute.kt` / `ProfileRoute.kt`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/file_paths.xml`，以及设计文档 `docs/sdk/updater-sdk-design.md`（设计依据，内容已整理进本仓库 `docs/10-design-rationale.md`，**只读，禁止修改**）。本 SDK 侧以 `updater/src/main/kotlin/dev/cuculus/update/*.kt` 为准。

---

## ⚠️ 0. 当前阶段约束（最高优先级，先读）

> **原项目代码目前保持不动。旧 `data/update` 尚未删除。必须等通过本 SDK 接入并验证无异常后，才能删除旧代码。**

具体含义，逐条执行：

1. **现在不要修改 / 删除 venera-native 里的任何文件**，包括 `data/update`、`AppGraph.kt`、`SelfUpdateConfiguration.kt`、`feature/profile` UI、app manifest、`file_paths.xml`、设计文档。
2. 当前阶段的唯一工作成果是**本仓库（cuculus-update）的 SDK 与知识库**；原项目是「待接入方」，不是「待改动方」。
3. 迁移按「**先接入、后删旧**」两段式推进：
   - **阶段 A（现在）**：SDK 独立可用（26 例单测 + AAR + sample APK + `publishToMavenLocal` 全部通过），知识库齐备。
   - **阶段 B（本次验证通过后、另行安排）**：在原项目里加入 SDK 依赖 → 装配切换 → 走完 §6 接入验证清单，确认红点 / 静默预下载 / 下载 / 安装 / 续传 / 取消全部无异常 → **此时才允许删除旧 `data/update` 代码**及 app 里重复的 manifest 条目。
4. 在阶段 B 完成前，原项目应保持**旧代码继续工作**；新旧并存是预期的中间态，不要为了「干净」提前删旧代码。
5. 原项目 `venera-native` 仓库中的 `docs/sdk/updater-sdk-design.md` 是历史决策记录（**只读**，其结论已整理进本仓库 [10 篇](10-design-rationale.md)），不要在源项目里改它。

后文 §2–§5 是**阶段 B 允许开始时**的改造清单（现在先备好，不要执行）。

---

## 1. 包名 / 模块 / 坐标总览

| | venera-native（旧） | cuculus-update（新） |
|---|---|---|
| 模块 | `:data:update`（`venera.android.library` 约定插件） | `:updater`（`com.android.library` + `maven-publish`） |
| 包名 | `dev.veneranative.data.update` | `dev.cuculus.update` |
| 依赖引入 | 工程内 `project(":data:update")` | `implementation("dev.cuculus:cuculus-update:1.0.0")` |
| 发布 | 无 | `mavenLocal` / 内网仓库；`updaterSdkVersion=1.0.0` |
| 对外可见性 | 大量实现类是 `public` / `class` | 实现类收敛为 `internal`，仅门面 + 数据类 + 结果类型 + worker 为 public |

---

## 2. 完整映射表

### 2.1 旧类 → 新类

| 旧类 / 类型（`dev.veneranative.data.update`） | 新类 / 类型（`dev.cuculus.update`） | 说明 |
|---|---|---|
| `SelfUpdateService` | `Updater` | 门面；构造改为静态工厂 `Updater.create(context, config, httpClient?)` |
| `SelfUpdateGateway` | `UpdateGateway` | 接口；方法重命名见 §2.2 |
| `SelfUpdateCheck` | `UpdateCheck` | 密封接口，分支见下 |
| `SelfUpdateCheck.UpToDate(release, apk)` | `UpdateCheck.UpToDate(release, asset)` | 字段名 `apk` → `asset` |
| `SelfUpdateCheck.NoInstallableApk` | `UpdateCheck.NoInstallableAsset` | 常量改名（`Apk` → `Asset`） |
| `SelfUpdateCheck.Available(release, apk, versionCode)` | `UpdateCheck.Available(release, asset, versionCode)` | 字段名 `apk` → `asset` |
| `SelfUpdateStatus` | `UpdateStatus` | `Idle` / `UpdateAvailable(versionCode)` / `Downloaded(versionCode, apkFile)` 三个分支同名 |
| `SelfUpdateService.updateStatus: StateFlow<SelfUpdateStatus>` | `Updater.status: StateFlow<UpdateStatus>` | 红点数据源，改字段名 |
| `GitHubReleaseApi` | `ReleaseApi`（**internal**） | 不再对外暴露；门面内部持有 |
| `GitHubRelease` | `ReleaseInfo` | 数据类；字段 `tagName/name/notes/publishedAt/assets` 同名（类型 `List<ReleaseAsset>`） |
| `GitHubReleaseAsset` | `ReleaseAsset` | 数据类；字段 `name/downloadUrl/sizeBytes/contentType` 同名 |
| `GitHubReleaseException` | `ReleaseApiException` | 继承从 `IOException` 变为 `UpdaterException`；仍带 `statusCode` |
| `ApkDownloader` | `ApkDownloader`（**internal**） | 逻辑照搬；异常类型收敛 |
| `ApkDownloadException` | `DownloadException` | 继承从 `IOException` 变为 `UpdaterException`；不再带 `cause` 参数 |
| `ApkConnectException`（private） | `DownloadConnectFailure`（private） | 内部连接失败标记，不对外 |
| `ApkDownloadProgress` | `UpdateProgress` | 字段 `bytesDownloaded/totalBytes`、派生 `fraction` 同名 |
| `ApkInstaller` | `ApkInstaller`（**internal**） | 逻辑照搬 |
| `ApkInstallResult` | `InstallResult` | `Launched` / `Failure` 同名；`Failure.message` 由 `String?` 收紧为非空 `String` |
| `ReleaseSelection` | `ReleaseSelection`（**internal**） | 版本规则外移到 `ReleaseVersionResolver` |
| `UpdateStateStore` | `UpdateStateStore`（**internal**） | SharedPreferences 名改为作用域化 `"<scope>.updater"` |
| `UpdateDownloadWorker` | `UpdateDownloadWorker` | **仍为 public**（WorkManager 反射实例化）；构造签名不变；输入数据新增 scope 与文案 |
| `UpdateNotifier` | `UpdateNotifier`（**internal**） | 渠道 id / 通知 id 由 scope 派生，文案可配 |
| `UpdateWorkScheduler`（`object`，单例） | `UpdateWorkScheduler`（**internal class**，构造入参 `workName`） | unique work 名由 `"<scope>.download"` 注入，不再是全局常量 |
| `UpdateEnvironment`（`object`，进程级 OkHttp） | `WorkerHttp`（**internal object**） | 更名；由 `Updater.create` 自动填充，取消手工 `configure()` |

### 2.2 旧方法 → 新方法（`SelfUpdateGateway` → `UpdateGateway`）

| 旧方法 | 新方法 | 变化 |
|---|---|---|
| `checkForUpdate(installedVersionCode): SelfUpdateCheck` | `check(installedVersionCode): UpdateCheck` | 重命名 |
| `downloadInForeground(apk: GitHubReleaseAsset): File` | `downloadInForeground(asset: ReleaseAsset): File` | 参数名 / 类型 |
| `observeDownloadProgress(): Flow<Float?>` | `observeForegroundProgress(): Flow<Float?>` | 重命名（强调前台下载） |
| `cancelDownload()` | `cancelForegroundDownload()` | 重命名 |
| `download(apk, onProgress: (ApkDownloadProgress)->Unit): File` | `download(asset, onProgress: (UpdateProgress)->Unit = {}): File` | 类型更换；`onProgress` **新增默认值** `{}`，可省略 |
| `install(apk: File): ApkInstallResult` | `install(apk: File): InstallResult` | 返回类型更换 |
| `cachedApk(versionCode: Long): File?` | `cachedApk(versionCode: Long): File?` | 不变 |
| `SelfUpdateService.checkAndPrefetch(installedVersionCode)` | `Updater.checkAndPrefetch(installedVersionCode)` | 不变（仍是「永不抛、除取消外」） |
| `UpdateEnvironment.configure(httpClient)` | **删除** | 改由 `Updater.create(context, config, httpClient)` 内部 `WorkerHttp.client = client` 完成 |

### 2.3 旧常量 / 硬编码 → 新配置字段

| 旧位置 / 值 | 新配置字段 | 说明 |
|---|---|---|
| `SelfUpdateConfiguration.REPOSITORY_OWNER = "lwtor"` | `UpdaterConfig.owner` | |
| `SelfUpdateConfiguration.REPOSITORY_NAME = "venera-native"` | `UpdaterConfig.repository` | |
| `SelfUpdateConfiguration.FILE_PROVIDER_AUTHORITY = "dev.veneranative.selfupdate"` | `UpdaterConfig.fileProviderAuthority` | 必须等于库 manifest 合并的 `${applicationId}.updater`；推荐 `"$packageName.updater"` |
| `SelfUpdateService(context, httpClient, owner, repo, providerAuthority)` | `Updater.create(context, UpdaterConfig(...), httpClient?)` | 散参 → 配置对象；`httpClient` 可空 |
| 版本方案 `MAJOR*1_000_000 + MINOR*1_000 + PATCH` 硬编码于 `ReleaseSelection.versionCodeFromTag` | `ReleaseVersionResolver.TagScheme(majorMultiplier=1_000_000L, minorMultiplier=1_000L)` | 乘数可配 |
| 资产名 `…-<digits>.apk` 硬编码于 `versionCodeFromAssetName` | `ReleaseVersionResolver.AssetNameSuffix(separator='-')` | 分隔符可配；**默认 resolver** |
| `releaseVersionCode()` 固定「资产名优先、tag 回退」 | `ReleaseVersionResolver.Or(primary, fallback)` | 默认 `AssetNameSuffix()`；原项目行为 = `Or(AssetNameSuffix(), TagScheme())` |
| 缓存目录 `"self-update"`（`SelfUpdateService.kt` 与 `UpdateDownloadWorker.kt` 两处硬编码） | `UpdaterPaths.updaterCacheDir()` → `cacheDir/updater/<scope>/` | **单一真源**，与 `updater_file_paths.xml` 对齐 |
| `UpdateWorkScheduler.WORK_NAME = "self_update"` | `"<scope>.download"`（`scope` 默认 `"self_update"`） | 作用域化 |
| `UpdateStateStore.PREFERENCES_NAME = "self_update"` | `"<scope>.updater"` | 作用域化 |
| 通知渠道 `CHANNEL_ID = "venera.self_update"` | `"<scope>.updates"` | 作用域化 |
| 通知 id `NOTIFICATION_ID = 2002` | `scope.hashCode()` | 作用域化、跨进程稳定 |
| `UpdateNotifier` 内 4 个中文文案硬编码 | `UpdaterStrings(channelName, channelDescription, downloadTitle, progressFormat)` | 可覆写；默认仍是同一套中文 |
| `GitHubReleaseApi.USER_AGENT = "Venera-Native-Android"` | `ReleaseApi.USER_AGENT = "cuculus-update"` | internal 常量 |
| —（新增） | `UpdaterConfig.scope`（默认 `"self_update"`） | 多更新源隔离 |
| —（新增） | `UpdaterConfig.strings`（默认 `UpdaterStrings()`） | 文案 |

### 2.4 manifest / file_paths / 权限迁移

**旧（venera-native `app/src/main/AndroidManifest.xml` + `res/xml/file_paths.xml`）** 全部由 App 手写：

- 6 个权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`REQUEST_INSTALL_PACKAGES`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`；
- `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge"/>`；
- `<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.selfupdate" ...>` + `@xml/file_paths`；
- `file_paths.xml`：`<cache-path name="self_update" path="self-update/"/>`。

**新（cuculus-update `updater/src/main/AndroidManifest.xml` + `res/xml/updater_file_paths.xml`）** 全部由库合并：

- 同样 6 个权限 + `SystemForegroundService`(dataSync) + FileProvider，但 authority 改为 **`${applicationId}.updater`**；
- `updater_file_paths.xml`：`<cache-path name="updater_downloads" path="updater/"/>`（覆盖 `cacheDir/updater/` 下所有 scope）；
- `consumer-rules.pro` 保留 worker 两参构造（App 无需关心，但必须让 AAR 带着它分发）。

**迁移动作（阶段 B 才执行）**：

1. 从 app manifest **删除**上述 6 个 `<uses-permission>`、`<service>`、`<provider>` 块——库会合并进来，重复声明尤其是重复 authority 会引发冲突。
2. **删除** `app/src/main/res/xml/file_paths.xml`（或确认无人再引用），改用库自带的 `@xml/updater_file_paths`。
3. authority 从 `"dev.veneranative.selfupdate"` / `${applicationId}.selfupdate` 改为 **`${applicationId}.updater`**，并在 `UpdaterConfig.fileProviderAuthority` 填 `"$packageName.updater"`；旧配置常量 `SelfUpdateConfiguration.FILE_PROVIDER_AUTHORITY` 删除。
4. `POST_NOTIFICATIONS` 仍需在 API 33+ **运行时申请**（库只负责声明）；`REQUEST_INSTALL_PACKAGES` 是 normal 权限，无需运行时申请。
5. `compileSdk` 必须 ≥ 37（AAR 的 `minCompileSdk` 约束）。

> 注意：若原项目 App 还有**别的**功能用了自己的 FileProvider（不同 authority），它与 SDK 的 `${applicationId}.updater` **不冲突**，可共存；不要为了「统一」把 SDK 的 authority 改掉（见 [08 篇](08-troubleshooting.md) authority 冲突条）。

---

## 3. 分步迁移流程（阶段 B 允许开始后按顺序执行）

1. **接入依赖**：`app/build.gradle.kts` 加 `implementation("dev.cuculus:cuculus-update:1.0.0")`（本地演练先 `mavenLocal()`，见 [11 篇](11-testing-and-build.md)）。保留旧 `project(":data:update")` 依赖暂不删，让新旧并存。
2. **确认清单合并**：编译后检查 app 的 merged manifest，确认 6 权限 / service / `${applicationId}.updater` provider 出现且**只有一份** authority；此时**先不要**删 app 里的旧声明，避免一步跨太大——先用 `tools:node` 冲突检测确认，再在步骤 6 统一清理。
3. **装配切换（AppGraph）**：见 §4，改为 `Updater.create(...)`，删除 `UpdateEnvironment.configure(...)`，把 `selfUpdateService` 字段换成 `updater`。
4. **UI 调用点切换（feature/profile）**：见 §5，把 `SelfUpdateGateway` → `UpdateGateway`、类型与方法名全部替换。
5. **走完接入验证清单（§6）**，逐条打勾，确认无异常。
6. **验证通过后**才执行清理：删 app manifest 旧条目、删 `file_paths.xml`、删 `SelfUpdateConfiguration` 中的 authority 常量、移除 `project(":data:update")` 依赖、**最后删除整个 `data/update` 模块**及其测试（26 例已在 SDK 侧保留，见 [11 篇](11-testing-and-build.md)）。
7. 全量回归：原项目 assemble + 单测 + 真机自更新全链路。

---

## 4. App 侧装配改造示例（`AppGraph.kt`）

旧（节选）：

```kotlin
import dev.veneranative.data.update.SelfUpdateService
import dev.veneranative.data.update.UpdateEnvironment

init {
    UpdateEnvironment.configure(httpClient)           // 进程级注入
    // ...
}
val selfUpdateService = SelfUpdateService(
    getApplication(), httpClient,
    SelfUpdateConfiguration.REPOSITORY_OWNER,
    SelfUpdateConfiguration.REPOSITORY_NAME,
    SelfUpdateConfiguration.FILE_PROVIDER_AUTHORITY,
)
init {
    scope.launch {
        val installedVersionCode = /* PackageInfoCompat.getLongVersionCode(info) */ ...
        selfUpdateService.checkAndPrefetch(installedVersionCode)
    }
}
// 红点来源：selfUpdateService.updateStatus
```

新（等价改造，阶段 B 才落地）：

> 注意：下面是「把原 ViewModel 里的创建语句逐行替换」的对照示意，重点在参数映射。
> 真正落地时仍应遵守铁律，把 `Updater.create(...)` 提到 `Application.onCreate()` 里做进程级单例，
> ViewModel 只通过 getter 取用；否则冷启动的 WorkManager worker 会拿不到共享的 `WorkerHttp.client`。

```kotlin
import dev.cuculus.update.UpdateStatus
import dev.cuculus.update.Updater
import dev.cuculus.update.UpdaterConfig
import dev.cuculus.update.ReleaseVersionResolver

// UpdateEnvironment.configure(httpClient) 整行删除：create() 内部会设置 WorkerHttp.client
val updater = Updater.create(
    context = getApplication(),
    config = UpdaterConfig(
        owner = "lwtor",
        repository = "venera-native",
        fileProviderAuthority = "${getApplication<android.app.Application>().packageName}.updater",
        // 复刻原项目「资产名优先、tag 回退」：
        versionResolver = ReleaseVersionResolver.Or(
            ReleaseVersionResolver.AssetNameSuffix(),   // venera-native-v1.6.0-1006000.apk -> 1006000
            ReleaseVersionResolver.TagScheme(),         // v1.9.3 -> 1_009_003
        ),
        // scope 用默认 "self_update"；字符串保持原中文默认即可
    ),
    httpClient = httpClient,   // 复用进程共享 client，保留代理 / DNS 配置
)
init {
    scope.launch {
        val installedVersionCode = runCatching {
            val app = getApplication<android.app.Application>()
            @Suppress("DEPRECATION")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
        }.getOrNull() ?: return@launch
        updater.checkAndPrefetch(installedVersionCode)   // 永不抛；失败只留红点
    }
}
// 红点来源改为 updater.status（StateFlow<UpdateStatus>）
```

要点：
- `installedVersionCode` 继续用 `PackageInfoCompat.getLongVersionCode`（或 `BuildConfig.VERSION_CODE.toLong()`），**不要**改成 versionName。
- 原项目版本方案与 SDK 默认不同（默认只有 `AssetNameSuffix()`），**必须显式传 `Or(AssetNameSuffix(), TagScheme())`** 才能保持 tag-only release 也能判更新。
- `fileProviderAuthority` 必须与库 manifest 的 `${applicationId}.updater` 一致；原项目旧的 `dev.veneranative.selfupdate` 不再使用。

---

## 5. `AboutViewModel` 侧调用点改造示例

旧 `AboutViewModel.kt` 依赖 `SelfUpdateGateway` 及 `dev.veneranative.data.update.*` 全部类型。改造 = **替换 import + 替换类型/方法名 + 替换 `.apk` → `.asset`**，状态机（`AboutUpdatePhase`）与 UI（`AboutRoute` / `ProfileRoute`）结构保持不变。

import 与字段：

```kotlin
// 旧
import dev.veneranative.data.update.ApkInstallResult
import dev.veneranative.data.update.GitHubRelease
import dev.veneranative.data.update.GitHubReleaseAsset
import dev.veneranative.data.update.GitHubReleaseException
import dev.veneranative.data.update.SelfUpdateCheck
import dev.veneranative.data.update.SelfUpdateGateway

// 新
import dev.cuculus.update.InstallResult
import dev.cuculus.update.ReleaseInfo
import dev.cuculus.update.ReleaseAsset
import dev.cuculus.update.ReleaseApiException
import dev.cuculus.update.UpdateCheck
import dev.cuculus.update.UpdateGateway
```

`AboutUiState` 字段类型：

```kotlin
val release: ReleaseInfo? = null,      // 原 GitHubRelease?
val apk: ReleaseAsset? = null,         // 原 GitHubReleaseAsset?（字段名可保留，类型换）
```

构造与检查：

```kotlin
class AboutViewModel(
    private val gateway: UpdateGateway,            // 原 SelfUpdateGateway
    private val installedVersionCode: Long,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() { /* ... */ }

private fun checkForUpdate() {
    if (job?.isActive == true) return
    job = viewModelScope.launch {
        // ...
        val check = withContext(workerDispatcher) { gateway.check(installedVersionCode) }  // 原 checkForUpdate
        when (check) {
            is UpdateCheck.Available -> mutableState.update {
                it.copy(phase = AboutUpdatePhase.CONFIRM, release = check.release,
                    apk = check.asset, newVersionCode = check.versionCode, downloadProgress = null)
            }
            is UpdateCheck.UpToDate -> mutableState.update {
                it.copy(phase = AboutUpdatePhase.IDLE, upToDate = true, release = check.release,
                    apk = check.asset, forceDownloadAvailable = true)
            }
            UpdateCheck.NoInstallableAsset ->                       // 原 NoInstallableApk
                mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE,
                    problem = AboutUpdateProblem.NO_INSTALLABLE_APK) }
        }
        // ...
    }
}
```

下载 / 观察 / 安装 / 取消：

```kotlin
val cached = withContext(workerDispatcher) { gateway.cachedApk(newVersionCode) }   // 不变
val progressJob = launch {
    gateway.observeForegroundProgress().collect { fraction ->     // 原 observeDownloadProgress()
        mutableState.update { current -> current.copy(downloadProgress = fraction) }
    }
}
val downloaded = try {
    gateway.downloadInForeground(apk)                            // 参数仍是 ReleaseAsset
} catch (failure: Exception) { /* ... */ }

when (gateway.install(file)) {                                   // 返回 InstallResult
    InstallResult.Launched -> mutableState.update { it.copy(phase = AboutUpdatePhase.INSTALLED) }
    is InstallResult.Failure -> mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE,
        problem = AboutUpdateProblem.INSTALL) }
}

private fun cancel() {
    gateway.cancelForegroundDownload()                            // 原 cancelDownload()
    job?.cancel(); job = null
    mutableState.value = AboutUiState()
}

private fun problemOf(failure: Exception): AboutUpdateProblem =
    if (failure is ReleaseApiException && failure.statusCode == 404) {  // 原 GitHubReleaseException
        AboutUpdateProblem.NO_RELEASE
    } else {
        AboutUpdateProblem.NETWORK
    }
```

`AboutRoute.kt`：只把 `import dev.veneranative.data.update.SelfUpdateGateway` 换成 `import dev.cuculus.update.UpdateGateway`，`AboutRoute(info, gateway: UpdateGateway, onBack)` 与 `AboutViewModel(gateway, info.versionCode)` 不变。
`ProfileRoute.kt`：`updateAvailable: Boolean` 的来源从「收集 `selfUpdateService.updateStatus`」改为「收集 `updater.status` 是否为 `UpdateAvailable` / `Downloaded`」，`BadgedBox` 展示逻辑不变。

> 完整、可直接复制的 ViewModel 模板（不依赖原项目类型）见 [09 篇](09-llm-playbook.md) §2。

---

## 6. 接入验证清单（阶段 B 逐条打勾，全部通过才允许删旧代码）

| # | 验证项 | 期望结果 | 通过 |
|---|---|---|---|
| 1 | 编译 / 单测 / AAR / sample APK | `sh gradlew :updater:testDebugUnitTest :updater:assembleRelease :sample:assembleDebug --console=plain` 全绿，26 例通过 | ☐ |
| 2 | `publishToMavenLocal` | `~/.m2/repository/dev/cuculus/cuculus-update/1.0.0/` 出现 AAR + sources jar + pom | ☐ |
| 3 | 合并清单 | app merged manifest 含 6 权限 + `SystemForegroundService`(dataSync) + `${applicationId}.updater` provider，且 authority 唯一、无重复声明 | ☐ |
| 4 | 红点 | 启动时 `checkAndPrefetch()` 发现新版本，profile 角标点亮；预取失败时红点仍亮（`UpdateAvailable`） | ☐ |
| 5 | 静默预下载 | 有可用更新且无缓存时，后台下载完成后 `status` 变为 `Downloaded(versionCode, apkFile)`；杀进程重进能从 `cachedApk()` 命中 | ☐ |
| 6 | 点击更新不重复下载 | 已有 `Downloaded` / `cachedApk()` 命中时直接 `install()`，不二次下载；无缓存时只启动一个前台 worker | ☐ |
| 7 | 安装拉起 | `install()` 返回 `InstallResult.Launched`，系统安装弹窗出现；用户确认后正常安装 | ☐ |
| 8 | 断点续传 | 下载中途断网 / 杀进程，恢复后 `.part` 续传（`Range` 请求），最终 APK 完整、头为 `PK\x03\x04` | ☐ |
| 9 | 取消 | 取消后进度停止、可重新下载，`.part` 保留，无残留 worker | ☐ |
| 10 | 通知（API 33+） | 授权 `POST_NOTIFICATIONS` 后显示 `"<scope>.updates"` 渠道进度通知；未授权时下载仍完成 | ☐ |
| 11 | R8 release | release（minify）包前台下载不崩（worker 两参构造被 keep） | ☐ |
| 12 | 多版本矩阵 | API 26 / 33 / 34+ 真机各跑一遍下载 + 安装 | ☐ |

**只有 1–12 全部打勾，才进入 §3 步骤 6 的删旧代码环节。** 任一项失败，回到 [08 篇](08-troubleshooting.md) 排查，期间旧 `data/update` 保持不变、继续兜底。
