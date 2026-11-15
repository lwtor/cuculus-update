# 03 · API 参考（逐个 public 类型与函数）

> 目的：写代码时核对**完整签名、参数含义、返回值、默认值、抛出的异常、协程/线程约束、状态流转**。所有签名均来自 `updater/src/main/kotlin/dev/cuculus/update/*.kt` 实际源码；不要凭类名猜 API。
> 约定：`context` 一律指 `android.content.Context`，`File` 指 `java.io.File`。`Updater` 实现 `UpdateGateway`，因此接口上的 7 个方法都能在 `Updater` 实例上直接调用。

---

## 0. 可见性速查（最重要）

| 类别 | 类型 | 能否 `import` 并调用 |
|---|---|---|
| **公开 API** | `Updater`、`UpdateGateway`、`UpdateCheck`（+3 子类）、`UpdateStatus`（+3 子类）、`UpdaterConfig`、`UpdaterStrings`、`ReleaseVersionResolver`（+ `AssetNameSuffix` / `TagScheme` / `Or`）、`UpdaterException`、`ReleaseApiException`、`DownloadException`、`ReleaseInfo`、`ReleaseAsset`、`UpdateProgress`、`InstallResult` | ✅ 能 |
| **公开但不供消费者调用** | `UpdateDownloadWorker`（`public class`，仅因 WorkManager 反射实例化而公开；`consumer-rules.pro` 保留其两参构造） | ⚠️ 不要直接 `new` / 调用 |
| **internal（编译期不可见）** | `ReleaseApi`、`parseRelease()`、`segment()`、`awaitResponse()`、`ReleaseSelection`、`ApkDownloader`、`ApkInstaller`、`UpdateWorkScheduler`、`UpdateNotifier`、`UpdateStateStore`、`isValidApk()`、`updaterCacheDir()`、`WorkerHttp` | ❌ 不能（写了会编译失败） |

> 契约：`internal` 类型只描述内部行为，用来解释「为什么」，**永远不要**出现在接入代码里。它们的字段/行为见本文 §11 与 `01-architecture.md`。

---

## 1. `Updater`（门面，`Updater.kt:24`）

```kotlin
class Updater private constructor(
    context: Context,
    /* private */ val config: UpdaterConfig,
    /* private */ val httpClient: OkHttpClient,
) : UpdateGateway
```

构造是 `private`，**唯一入口是 `Updater.create()`**。类自身实现了 `UpdateGateway` 全部 7 个方法，另有 `status`、`checkAndPrefetch()` 与 `latestInstallableAsset()` 三个独有成员。

### 1.1 `Updater.create()`

```kotlin
companion object {
    fun create(
        context: Context,
        config: UpdaterConfig,
        httpClient: OkHttpClient? = null,
    ): Updater
}
```

| 参数 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `context` | `Context` | 必填 | 内部立即取 `context.applicationContext`，可长期持有，传 Activity 也不会泄漏 |
| `config` | `UpdaterConfig` | 必填 | 一个实例 = 一个更新源；见 §5 |
| `httpClient` | `OkHttpClient?` | `null` | `null` → SDK 自建 `OkHttpClient()`；传入则以共享连接池 / proxy / DNS 运行 |

- **副作用**：把最终使用的 client 写入进程级 `WorkerHttp.client`，供反射创建的前台 worker 取用（见 `01-architecture.md` §6.4）。
- **返回值**：`Updater`（实现 `UpdateGateway`）。
- **抛出的异常**：无。不校验 `owner` / `repository` / authority 的合法性，错误会在运行时 `check()` / `install()` 暴露。
- **协程/线程约束**：普通函数，任意线程可调用；**建议只在 `Application.onCreate()` 调用一次并存成单例**。
- **Context 约束（违反会启动崩溃）**：`create()` 内部立即读 `context.applicationContext` 并构造 `UpdateStateStore`（`getSharedPreferences`），且 `fileProviderAuthority` 惯例由 `$packageName` 拼出。`Activity` 在 `attachBaseContext()` 之前没有 base Context，因此**禁止在 Activity 属性初始化器里调用**（`private val updater = Updater.create(this, ...)` → NPE，App 启动即崩）。用 `Application.onCreate()`；必须放 Activity 时也只能放进 `onCreate()` 且传 `applicationContext`。
- **误判**：多个页面各 `create()` 一次 → 后建的覆盖 `WorkerHttp.client`，破坏共享 proxy/DNS 配置。

### 1.2 `Updater.status`

```kotlin
val status: StateFlow<UpdateStatus>
```

- 初值 `UpdateStatus.Idle`；**只由 `checkAndPrefetch()` 更新**。
- 进程内 `MutableStateFlow.asStateFlow()`，**不持久化**（重启 App 重新计算）。
- `StateFlow` 线程安全，可在任意 dispatcher 上写、在任意协程 `collect`。
- 用 `status.collect { ... }` 驱动红点；`Downloaded` 时 `apkFile` 可直接交给 `install()`。

### 1.3 `Updater.checkAndPrefetch()`

```kotlin
suspend fun checkAndPrefetch(installedVersionCode: Long)
```

| 项 | 说明 |
|---|---|
| 参数 | `installedVersionCode: Long`（通常是 `BuildConfig.VERSION_CODE.toLong()`） |
| 返回值 | `Unit` |
| 抛出的异常 | **吞掉所有 `Exception`，仅 `CancellationException` 向上抛**；绝不因瞬时网络问题崩溃或打扰用户 |
| 协程约束 | `suspend`，必须包在协程里 |
| 线程约束 | 内部会执行一次**静默内联下载**（含阻塞式文件写盘）；`status` 通过线程安全 `StateFlow` 更新。为避免占用主线程，建议在后台 dispatcher（如 `Dispatchers.IO` / 应用级后台作用域）调用 |

**状态流转（精确）**

```
起始: Idle（或上次遗留的任意状态）
  │
  ├─ check() 抛异常（非 CancellationException）────────────▶ 静默返回，status 不变
  ├─ check() = UpToDate / NoInstallableAsset ─────────────▶ 静默返回，status 不变
  └─ check() = Available(code, asset)
        │  status := UpdateAvailable(code)   ← 红点亮
        ├─ cachedApk(code) 命中有效 APK ─────▶ status := Downloaded(code, cachedFile)
        ├─ 内联 download 成功 ─────────────▶ 写入 StateStore；status := Downloaded(code, target)
        └─ 内联 download 失败（非 Cancellation）▶ status 保持 UpdateAvailable(code)，红点仍亮
```

> `Downloaded` 的 `apkFile` 与 `cachedApk(code)` 是同一份落盘文件（记录写进 SharedPreferences `"<scope>.updater"`）。

### 1.4 `Updater.latestInstallableAsset()`（不做版本比较）

```kotlin
suspend fun latestInstallableAsset(): ReleaseAsset?
```

| 项 | 说明 |
|---|---|
| 参数 | 无 |
| 返回值 | latest release 里可安装的 APK（`ReleaseAsset?`）；release 存在但没有 `.apk` 资产时返回 `null` |
| 抛出的异常 | `ReleaseApiException`（404：仓库只有 draft/prerelease）、`IOException` 等网络异常；**不吞异常** |
| 版本判断 | **完全没有**。不比较 `installedVersionCode`，也不解析资产名里的 versionCode |
| 所在类型 | 具体 `Updater`，**不在 `UpdateGateway` 上**（接口仍只有 7 个动作方法） |

用途：手动重试、强制重装、Debug/Demo 的「直接下载最新版」按钮，或发布端资产名不遵循 `-<versionCode>.apk` 契约的场景。内部等于 `ReleaseApi.latestRelease()` + `ReleaseSelection.pickUpdateApk()`。

与 `check()` 的分工要分清：

| 需求 | 用哪个 |
|---|---|
| 判断「要不要亮更新红点」 | `check(installedVersionCode)` / `checkAndPrefetch()` |
| 「不管新旧，把最新 APK 给我」 | `latestInstallableAsset()` |

因此**不要**把下载按钮的可用性和 `UpdateCheck.Available` 绑定：`UpToDate(release, asset)` 本身也带 asset，而 `NoInstallableAsset` 也可能只是版本解析失败、实际仍有 APK。需要「随时可下载」的界面应当直接调用本方法，再把结果交给 `downloadInForeground()` / `download()`。

---

## 2. `UpdateGateway`（接口，`UpdateGateway.kt:43`）

```kotlin
interface UpdateGateway {
    suspend fun check(installedVersionCode: Long): UpdateCheck
    fun cachedApk(versionCode: Long): File?
    suspend fun downloadInForeground(asset: ReleaseAsset): File
    fun observeForegroundProgress(): Flow<Float?>
    fun cancelForegroundDownload()
    suspend fun download(asset: ReleaseAsset, onProgress: (UpdateProgress) -> Unit = {}): File
    fun install(apk: File): InstallResult
}
```

> `Updater` 重写了全部 7 个方法。Kotlin 规则：重写方法**不能重复声明默认值**，但会继承被重写方法的默认值——因此 `updater.download(asset)`（省略 lambda）合法，等价于 `download(asset) {}`。

| 方法 | suspend | 参数 | 返回值 | 抛出的异常 | 线程/协程约束 |
|---|---|---|---|---|---|
| `check` | ✅ | `installedVersionCode: Long` | `UpdateCheck` | `ReleaseApiException`、`IOException` | 任意协程；内部 OkHttp `enqueue`，不阻塞 |
| `cachedApk` | ❌ | `versionCode: Long` | `File?` | 无（内部 `runCatching`） | 任意线程；会读 SharedPreferences + 校验文件头，极轻 |
| `downloadInForeground` | ✅ | `asset: ReleaseAsset` | `File`（落盘 APK） | `DownloadException` | 挂起等 WorkManager 终态；本身不阻塞，任意协程 |
| `observeForegroundProgress` | ❌ | — | `Flow<Float?>`（`0f..1f` 或 `null`） | 无 | 返回冷流无关的 Flow；在需要更新 UI 的 dispatcher 上 `collect` |
| `cancelForegroundDownload` | ❌ | — | `Unit` | 无 | 任意线程 |
| `download` | ✅ | `asset`、`onProgress: (UpdateProgress) -> Unit = {}` | `File` | `DownloadException`、`CancellationException` | **含阻塞式流式写盘，必须在 `Dispatchers.IO` 等后台 dispatcher 调用**；`onProgress` 所在线程不确定，只写线程安全容器 |
| `install` | ❌ | `apk: File` | `InstallResult` | **不抛异常**，失败以 `InstallResult.Failure` 返回 | 任意线程；内部 `startActivity`，建议主线程调用 |

### 2.1 `check(installedVersionCode)` 细节

```
latestRelease(owner, repository)
  → pickUpdateApk(assets) → ReleaseAsset?
  → versionResolver.versionCodeOf(tagName, asset.name) → Long?
  → isUpdate(code, installedVersionCode)
```

| 结果 | 条件 |
|---|---|
| `UpdateCheck.NoInstallableAsset` | 无 `.apk` asset，**或** versionResolver 返回 `null` |
| `UpdateCheck.Available(release, asset, versionCode)` | 有 APK 且 `versionCode > installedVersionCode`（严格大于） |
| `UpdateCheck.UpToDate(release, asset)` | 有 APK 且 `versionCode <= installedVersionCode` |

- 不比较 tag 字符串、不比较 `publishedAt`、不比较 asset 名。
- 异常：`ReleaseApiException`（GitHub 非 2xx，404 时 `statusCode=404`）、`IOException`（网络失败 / release 缺 `tag_name`）。二者都是 `IOException` 子类，`catch (e: IOException)` 可一并兜住。

### 2.2 `cachedApk(versionCode)` 细节

返回非 null **当且仅当**：SharedPreferences 里记录的 versionCode 相等、`apk_path` 对应文件存在、且文件头仍是 `PK\x03\x04`。否则**清掉脏记录**并返回 `null`。不抛异常。

### 2.3 `downloadInForeground(asset)` 细节

1. `targetName = sanitize(asset.name)`（非 `[A-Za-z0-9._-]` 字符替换为 `_`）。
2. `enqueueUniqueWork("<scope>.download", REPLACE, OneTimeWorkRequest)`，约束 `NetworkType.CONNECTED`。
3. 挂起直到该 unique work 全部 `isFinished`。
4. `SUCCEEDED` 且 `OUTPUT_FILE_PATH` 非空 → `File(path)`；否则抛 `DownloadException(OUTPUT_ERROR ?: "update download did not complete (<state>)")`。

**取消、失败、进程被杀后重启导致 worker 未完成，都以抛 `DownloadException` 表达，不会返回 `null` 或空 `File`。**

### 2.4 `observeForegroundProgress()` 细节

`WorkInfo.progress.getFloat(PROGRESS_KEY, -1f)` → `<0` 视为 `null` → `distinctUntilChanged()`。
- 语义：`0f..1f` 表示已下载比例，`null` 表示尚未解析到进度。
- 它是**独立 Flow**：与 `downloadInForeground()` 的调用先后无关，可单独起协程收集；主下载协程结束时应 `cancel()` 掉它（见 `02-integration.md`）。

### 2.5 `download(asset, onProgress)` 细节

- 写入 `<target>.apk.part`；若已存在则发 `Range: bytes=<n>-`，`206` 续写、`200` 重写。
- 每约 256 KiB 触发一次 `onProgress(UpdateProgress(bytesDownloaded, totalBytes))`，结束再触发一次。
- 完成前校验 ZIP 头 `PK\x03\x04`；失败删 `.part` 并抛 `DownloadException("the downloaded file is not an Android package")`。
- 截断（已知总长但接收不足）→ `DownloadException`。
- `onProgress` 默认 `{}`；省略时收不到进度但仍能下载。

### 2.6 `install(apk)` 细节

| 返回 | 触发条件 |
|---|---|
| `InstallResult.Launched` | `startActivity(ACTION_VIEW)` 成功；系统安装器弹窗出现 |
| `InstallResult.Failure("the downloaded file is missing")` | `!apk.isFile` |
| `InstallResult.Failure("unable to expose the APK: ...")` | `FileProvider.getUriForFile` 抛异常（多为 authority 不匹配） |
| `InstallResult.Failure("no installer is available on this device")` | `ActivityNotFoundException` |
| `InstallResult.Failure(<message>)` | 其它异常 |

> **不回报安装结果**：用户确认/取消/签名不符都发生在系统弹窗里，SDK 不知道，也没有回调。

---

## 3. `UpdateCheck`（密封接口，`UpdateGateway.kt:7`）

```kotlin
sealed interface UpdateCheck {
    data class UpToDate(val release: ReleaseInfo, val asset: ReleaseAsset) : UpdateCheck
    data object NoInstallableAsset : UpdateCheck
    data class Available(
        val release: ReleaseInfo,
        val asset: ReleaseAsset,
        val versionCode: Long,
    ) : UpdateCheck
}
```

| 分支 | 字段 | 含义 | 该做什么 |
|---|---|---|---|
| `UpToDate` | `release`、`asset` | 已安装版本 ≥ release 的 versionCode | 显示「已是最新」；`release`/`asset` 仍暴露，可用于调试时强制重下同一 APK |
| `NoInstallableAsset` | 无（`data object`） | release 存在但没有可安装 APK，或 versionCode 解析失败 | 检查 asset 命名 / `versionResolver`（见 `04` / `05`） |
| `Available` | `release`、`asset`、`versionCode` | 有严格更新的版本 | 用 `asset` 调 `downloadInForeground()` / `download()` |

`when` 必须穷举三个分支；`NoInstallableAsset` 是 `object`，用 `UpdateCheck.NoInstallableAsset ->` 而非 `is`。

---

## 4. `UpdateStatus`（密封接口，`UpdateGateway.kt:29`）

```kotlin
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class UpdateAvailable(val versionCode: Long) : UpdateStatus
    data class Downloaded(val versionCode: Long, val apkFile: File) : UpdateStatus
}
```

| 状态 | 含义 | UI 建议 |
|---|---|---|
| `Idle` | 初始 / 尚无已知更新 | 不显示红点 |
| `UpdateAvailable(versionCode)` | 已知有更新，APK 未就绪或预下载失败 | 亮红点，允许手动下载 |
| `Downloaded(versionCode, apkFile)` | APK 已下载并校验，可直接 `install(apkFile)` | 亮红点 + 「安装」可用 |

**只由 `checkAndPrefetch()` 驱动**（见 §1.3 状态流转）；`check()` / `download*()` / `install()` **不会**改变 `status`。

---

## 5. `UpdaterConfig`（数据类，`UpdaterConfig.kt:16`）

```kotlin
data class UpdaterConfig(
    val owner: String,
    val repository: String,
    val fileProviderAuthority: String,
    val scope: String = "self_update",
    val versionResolver: ReleaseVersionResolver = ReleaseVersionResolver.AssetNameSuffix(),
    val strings: UpdaterStrings = UpdaterStrings(),
)
```

| 字段 | 类型 | 默认 | 必填 | 含义 / 约束 |
|---|---|---|---|---|
| `owner` | `String` | — | ✅ | GitHub 用户 / 组织 |
| `repository` | `String` | — | ✅ | 其 `releases/latest` 作为更新源 |
| `fileProviderAuthority` | `String` | — | ✅ | **必须等于 `${applicationId}.updater`**（最终 applicationId，含 suffix）；否则 `install()` 失败。见 `04` |
| `scope` | `String` | `"self_update"` | ❌ | 命名空间：cache 子目录 / SharedPreferences 名 / 通知渠道 / unique work 名。见 `04` |
| `versionResolver` | `ReleaseVersionResolver` | `AssetNameSuffix()` | ❌ | 把 release 映射成 versionCode；见 §7 与 `04` |
| `strings` | `UpdaterStrings` | 中文默认值 | ❌ | 通知文案；见 §6 |

`data class` 提供 `copy()` / `equals()` / `hashCode()` / `componentN()` / `toString()`。无校验逻辑，无异常。

---

## 6. `UpdaterStrings`（数据类，`UpdaterConfig.kt:27`）

```kotlin
data class UpdaterStrings(
    val channelName: String = "应用更新",
    val channelDescription: String = "显示应用内新版本 APK 的下载进度",
    val downloadTitle: String = "正在下载新版本",
    val progressFormat: String = "已下载 %d%%",
)
```

| 字段 | 默认（中文） | 用途 |
|---|---|---|
| `channelName` | `应用更新` | 通知渠道名（channelId 仍是 `"<scope>.updates"`） |
| `channelDescription` | `显示应用内新版本 APK 的下载进度` | 渠道描述 |
| `downloadTitle` | `正在下载新版本` | 通知标题 |
| `progressFormat` | `已下载 %d%%` | `String.format(pattern, percent)` 的 `Int` 百分比；`%d` 显示数字，`%%` 显示字面 `%` |

- 这 4 个字符串会随 WorkManager `inputData` 持久化，进程被杀后重建的 worker 仍能正确写通知。
- **必须保留一个 `%d` 占位符**才能显示进度数字；缺失不会崩溃，只是 notifications 里没有数字。
- 覆盖英文示例见 `04-configuration.md`。

---

## 7. `ReleaseVersionResolver`（`fun interface`，`UpdaterConfig.kt:36`）

```kotlin
fun interface ReleaseVersionResolver {
    fun versionCodeOf(releaseTag: String, apkAssetName: String): Long?
}
```

- 返回 `Long?`；**返回 `null` 会让 `check()` 得到 `UpdateCheck.NoInstallableAsset`**。
- `releaseTag` 来自 `ReleaseInfo.tagName`，`apkAssetName` 来自被选中 `ReleaseAsset.name`（原始名，未经消毒）。
- 因为是 `fun interface`，可用 SAM/lambda 自定义（见 `04`）。

### 7.1 `AssetNameSuffix`

```kotlin
class AssetNameSuffix(private val separator: Char = '-') : ReleaseVersionResolver
```

- `separator: Char`，默认 `'-'`。构造时可改，如 `AssetNameSuffix('_')`。
- 解析：`stem = apkAssetName.substringBeforeLast('.', missingDelimiterValue = "").trim()`；空 → `null`；`stem.substringAfterLast(separator).toLongOrNull()`。
- 例：`app-v1.9.3-1009003.apk` → `1009003`；`app-9.apk` → `9`；`app-9.APK` → `9`。`venera-native.apk` → `null`。
- **忽略 `releaseTag`**；要求「最后一个分隔符之后（扩展名之前）」是可解析为 `Long` 的数字。
- 默认策略；发布工具把 versionCode 烘焙进文件名时最可靠。

### 7.2 `TagScheme`

```kotlin
class TagScheme(
    private val majorMultiplier: Long = 1_000_000L,
    private val minorMultiplier: Long = 1_000L,
) : ReleaseVersionResolver
```

- 解析：`releaseTag.trim().removePrefix("v").removePrefix("V").split('.')`；段数必须 `2..3`；三段各解析为 `Int`（第三段缺失当 `0`）；每段 `0..999`，越界返回 `null`；返回 `major * majorMultiplier + minor * minorMultiplier + patch`。
- 例：`v1.9.3` / `1.9.3` → `1_009_003`；`v1.6` → `1_006_000`；`v999.999.999` → `999_999_999`；`v1` / `v1.6.0.4` / `v1.1000.0` / `latest` → `null`。
- **忽略 `apkAssetName`**。
- `majorMultiplier` / `minorMultiplier` 是 `private val`，只能在构造时传入。

### 7.3 `Or`

```kotlin
class Or(
    private val primary: ReleaseVersionResolver,
    private val fallback: ReleaseVersionResolver,
) : ReleaseVersionResolver
```

- `primary.versionCodeOf(...) ?: fallback.versionCodeOf(...)`：`primary` 返回 `null` 才试 `fallback`。
- 常用组合：`Or(AssetNameSuffix(), TagScheme())` —— 文件名有 code 优先用文件名，只有 tag 时也能工作。

---

## 8. 异常类型（`UpdaterException.kt`）

```kotlin
open class UpdaterException(message: String) : java.io.IOException(message)
class ReleaseApiException(val statusCode: Int, message: String) : UpdaterException(message)
class DownloadException(message: String) : UpdaterException(message)
```

| 类型 | 构造签名 | 何时出现 | 可读取的信息 |
|---|---|---|---|
| `UpdaterException` | `(message: String)` | 基类，不直接抛出 | `message` |
| `ReleaseApiException` | `(val statusCode: Int, message: String)` — **两个参数** | GitHub Releases API 非 2xx | `statusCode`（如 `404`、`503`）、`message`（404 时为 `"no published release"`；否则 GitHub 返回的 `message` 或 `"GitHub release request failed"`） |
| `DownloadException` | `(message: String)` | 连接失败、HTTP 非 `200/206`、空 body、截断、非 APK body、worker 失败/取消 | `message` |

> 三者都继承 `IOException`，因此 `catch (e: IOException)` 能兜住全部 SDK 抛出的受检异常。
> **注意**：`ReleaseApiException` 的构造是 `(statusCode: Int, message: String)` 两个参数，不是单参数；它由 SDK 内部构造，消费者只需 `catch` 并读 `statusCode` / `message`，不要自己 `throw` 它。

---

## 9. 数据类型

### 9.1 `ReleaseInfo`（`ReleaseApi.kt:21`）

```kotlin
data class ReleaseInfo(
    val tagName: String,           // 必有；缺失会让解析抛 IOException
    val name: String?,             // release 标题，可能为 null
    val notes: String?,            // release body，可能为 null
    val publishedAt: String?,      // ISO 时间字符串，可能为 null
    val assets: List<ReleaseAsset>,
)
```

### 9.2 `ReleaseAsset`（`ReleaseApi.kt:14`）

```kotlin
data class ReleaseAsset(
    val name: String,              // 原始 asset 名（未消毒）
    val downloadUrl: String,       // browser_download_url
    val sizeBytes: Long,           // size，缺省 0L
    val contentType: String?,      // content_type，可能为 null
)
```

`downloadUrl` 直接传给下载器；`name` 用于 versionCode 解析与落盘文件名消毒。二者都来自 GitHub `releases/latest` 的 JSON。

### 9.3 `UpdateProgress`（`ApkDownloader.kt:22`）

```kotlin
data class UpdateProgress(val bytesDownloaded: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).toFloat() }
}
```

- `fraction` 是**计算属性**（非构造参数）：`totalBytes` 为 `null` / `<=0` 时为 `null`。
- 只作为 `download()` 的 `onProgress` 回调参数出现。

### 9.4 `InstallResult`（`ApkInstaller.kt:9`）

```kotlin
sealed interface InstallResult {
    data object Launched : InstallResult
    data class Failure(val message: String) : InstallResult
}
```

`when` 穷举两个分支；`Launched` 是 `object`，用 `InstallResult.Launched ->`。

---

## 10. `UpdateDownloadWorker`（`public`，仅供 WorkManager 反射）

```kotlin
class UpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params)
```

- **不要直接实例化或调用**。它公开只是因为 WorkManager 按持久化类名反射创建；`consumer-rules.pro` 保留了这个两参构造在 R8 混淆下的存活。
- `doWork()` 读 `inputData` → `setForeground` → `ApkDownloader(WorkerHttp.resolve()).download(...)` → `setProgressAsync(PROGRESS_KEY)` → `Result.success(OUTPUT_FILE_PATH)` / `Result.failure(OUTPUT_ERROR)`；`CancellationException` 重新抛出。
- companion object 常量（`public`，内部协议用；消费者不要依赖）：

| 常量 | 值 |
|---|---|
| `INPUT_DOWNLOAD_URL` | `"download_url"` |
| `INPUT_TARGET_NAME` | `"target_name"` |
| `INPUT_SCOPE` | `"scope"` |
| `INPUT_CHANNEL_NAME` | `"channel_name"` |
| `INPUT_CHANNEL_DESCRIPTION` | `"channel_description"` |
| `INPUT_DOWNLOAD_TITLE` | `"download_title"` |
| `INPUT_PROGRESS_FORMAT` | `"progress_format"` |
| `OUTPUT_FILE_PATH` | `"file_path"` |
| `OUTPUT_ERROR` | `"error"` |
| `PROGRESS_KEY` | `"progress"` |

---

## 11. `internal` 实现类型（**不可调用**，仅用于理解行为）

| 类型 | 关键行为 |
|---|---|
| `ReleaseApi` | 匿名 GET `https://api.github.com/repos/{owner}/{repository}/releases/latest`；请求头 `Accept: application/vnd.github+json`、`X-GitHub-Api-Version: 2026-03-10`、`User-Agent: cuculus-update`；非 2xx → `ReleaseApiException` |
| `ReleaseSelection` | `pickUpdateApk`（`.apk` 过滤 → contentType==APK 优先 → `sizeBytes` 降序 → firstOrNull）、`isUpdate`（严格大于）、`sanitizeAssetFileName`（`[^A-Za-z0-9._-]` → `_`）、常量 `APK_CONTENT_TYPE = "application/vnd.android.package-archive"` |
| `ApkDownloader` | `.part` 续传（`Range` / `206`）、代理优先→直连回退（仅连接失败且未写字节时）、ZIP 头校验、原子 `Files.move`、进度间隔 256 KiB、连接/读超时 30s/120s |
| `ApkInstaller` | `FileProvider.getUriForFile` + `ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION` |
| `UpdateWorkScheduler` | `Constraints(NetworkType.CONNECTED)`、`enqueueUniqueWork(workName, REPLACE, ...)`、`cancelUniqueWork` |
| `UpdateNotifier` | channelId `"<scope>.updates"`、`notificationId = scope.hashCode()`、`IMPORTANCE_LOW`、API 34+ `FOREGROUND_SERVICE_TYPE_DATA_SYNC` |
| `UpdateStateStore` | SharedPreferences `"<scope>.updater"`，键 `downloaded_version_code` / `downloaded_apk_path`，单条记录；`isValidApk()` 校验 `PK\x03\x04` |
| `updaterCacheDir()` | `File(applicationContext.cacheDir, "updater/$scope").apply { mkdirs() }` —— 所有落盘路径的唯一来源 |
| `WorkerHttp` | `@Volatile var client: OkHttpClient?`；`resolve(): OkHttpClient = client ?: OkHttpClient()` |

---

## 12. 状态流转 / 异常传播 / 线程约束 汇总

### 12.1 异常传播矩阵

| 调用点 | 抛出 | 是否被内部吞掉 |
|---|---|---|
| `Updater.create()` | 无 | — |
| `check()` | `ReleaseApiException` / `IOException` | `checkAndPrefetch()` 内部吞掉（非 `CancellationException`） |
| `checkAndPrefetch()` | 仅 `CancellationException` | 其余全吞 |
| `cachedApk()` / `install()` / `cancelForegroundDownload()` | 不抛（以返回值表达） | — |
| `downloadInForeground()` | `DownloadException` | 否 |
| `download()` | `DownloadException` / `CancellationException` | 否（`checkAndPrefetch` 内部调用时会吞掉非取消异常） |
| `observeForegroundProgress()` | 无 | — |

### 12.2 线程 / 协程约束矩阵

| 方法 | `suspend` | 阻塞式 I/O | 确定规则 |
|---|---|---|---|
| `create()` | ❌ | 否 | 任意线程；只在 Application 调一次 |
| `check()` | ✅ | 否（OkHttp `enqueue` + 轻量 JSON 解析） | 任意协程 |
| `cachedApk()` | ❌ | 极轻（读 prefs + 读 4 字节） | 任意线程 |
| `downloadInForeground()` | ✅ | 否（等 WorkManager Flow） | 任意协程；不阻塞调用线程 |
| `observeForegroundProgress()` | ❌（返回 Flow） | 否 | `collect` 在要更新 UI 的 dispatcher |
| `cancelForegroundDownload()` | ❌ | 否 | 任意线程 |
| `download()` | ✅ | **是**（流式写盘） | **必须后台 dispatcher（`Dispatchers.IO`）**；`onProgress` 线程不确定，只写 `StateFlow` 等线程安全容器 |
| `install()` | ❌ | 否 | 任意线程（建议主线程，因为 `startActivity`） |
| `checkAndPrefetch()` | ✅ | **是**（内部静默下载） | 建议后台 dispatcher；`status` 用线程安全 `StateFlow` 更新 |
| `status.collect` | — | 否 | 任意协程 |

### 12.3 `download()` 与 `downloadInForeground()` 的关系

二者写入**同一个目录、同一个文件名**（都经 `updaterCacheDir()` + `sanitizeAssetFileName()`），共用同一个 `.apk.part` 续传点；区别只在执行体与进度通道（见 `01-architecture.md` §4）。

---

## 13. 该做什么 / 不该做什么 / 常见误判

**该做什么**
- 所有 `suspend` 调用包在协程里；`check()` / `downloadInForeground()` 用 `try/catch` 处理 `ReleaseApiException` / `IOException` / `DownloadException`。
- `when (check)` / `when (status)` / `when (result)` 都穷举全部分支；`object` 分支用 `X.Y ->` 而非 `is X.Y`。
- `download()` 放 `Dispatchers.IO`；进度回调只写线程安全容器。
- `install()` 的入参来自 `download*()` 返回的 `File`、`cachedApk()` 或 `UpdateStatus.Downloaded.apkFile`。

**不该做什么**
- ❌ 不要 `import` 任何 §11 的 `internal` 类型，也不要实例化 `UpdateDownloadWorker`。
- ❌ 不要把 `ReleaseApiException` 当成单参数异常自己构造；不要假设存在 `install()` 的成功回调。
- ❌ 不要用 `versionName` 当 `installedVersionCode`；不要忘 `.toLong()`。
- ❌ 不要 `catch (Exception)` 后吞掉 `CancellationException`（应重新抛出）。

**常见误判**

| 误判 | 真相 |
|---|---|
| 「`ReleaseApiException` 只有一个 `statusCode` 参数」 | 实际构造是 `(val statusCode: Int, message: String)`；消费者只 `catch` 读字段。 |
| 「`NoInstallableAsset` 表示没发版」 | 它表示「有 release 但没有可装 APK 或 versionCode 解析失败」；没发版（404）是 `ReleaseApiException`。 |
| 「`UpToDate` 没有 asset 可用」 | `UpToDate(release, asset)` 仍带 asset，可强制重下。 |
| 「`downloadInForeground` 取消后会返回 null」 | 抛 `DownloadException`。 |
| 「`status` 会被 `check()` / `install()` 改变」 | 只有 `checkAndPrefetch()` 改 `status`。 |
| 「`progressFormat` 可以不含 `%d`」 | 不含不会崩溃，但通知里永远不显示百分比数字。 |
| 「`onProgress` 一定在主线程」 | 线程不确定；更新 UI 前显式切回主线程或走 `StateFlow`。 |
| 「`cachedApk()` 会返回所有历史版本」 | SharedPreferences 只存**一条**记录（一个 versionCode + 一个路径）；只认当前记录的 code。 |
