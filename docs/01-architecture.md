# 01 · 架构与数据流

> 目的：让接入方/排查者理解库内部如何运转、能力边界在哪、为什么这样设计。接入代码见 `02-integration.md`，逐个签名见 `03-api-reference.md`。

## 1. 模块结构

`settings.gradle.kts`：`rootProject.name = "cuculus-update"`，`include(":updater", ":sample")`。

| 模块 | 角色 |
|---|---|
| `:updater` | `com.android.library` + `maven-publish`，产出 `dev.cuculus:cuculus-update:1.0.0` AAR（`singleVariant("release")` + `withSourcesJar()`）。全部实现都在这里。 |
| `:sample` | 经典 View 的演示 App（applicationId `dev.cuculus.update.sample`），manifest **故意不声明任何权限 / provider**，用来证明清单合并可用。 |
| 根 `build.gradle.kts` | 只声明 AGP 9.3.1（`com.android.application` / `com.android.library`，`apply false`）。AGP 9 内置 Kotlin 支持，**不需要**单独的 Kotlin 插件。 |

### `:updater/src/main/kotlin/dev/cuculus/update/` 文件职责

路径统一前缀记为 `updater/src/main/kotlin/dev/cuculus/update/`。

| 文件 | 可见性 | 职责 |
|---|---|---|
| `Updater.kt` | `public class Updater : UpdateGateway` | 门面。组装所有内部组件；实现 `UpdateGateway` 全部方法；额外持有 `status: StateFlow<UpdateStatus>` 与 `checkAndPrefetch()`；`companion object.create()` 是唯一入口。 |
| `UpdateGateway.kt` | `public interface` | 定义 7 个调用方法；同文件定义 `UpdateCheck`、`UpdateStatus` 两个密封接口。 |
| `UpdaterConfig.kt` | `public` | `UpdaterConfig`、`UpdaterStrings`、`ReleaseVersionResolver`（`fun interface` + `AssetNameSuffix` / `TagScheme` / `Or` 三种策略）。 |
| `UpdaterException.kt` | `public` | `UpdaterException : IOException`、`ReleaseApiException`、`DownloadException`。 |
| `ReleaseApi.kt` | `public` 数据类 + `internal` 实现 | `ReleaseInfo` / `ReleaseAsset` 为 public；`ReleaseApi`、`parseRelease()`、`segment()`、`awaitResponse()` 为 internal：匿名访问 GitHub `releases/latest`。 |
| `ReleaseSelection.kt` | `internal object` | 纯 JVM 规则：挑 APK、判定是否更新、消毒文件名。无 I/O，便于单测。 |
| `ApkDownloader.kt` | `public` 数据类 + `internal` 实现 | `UpdateProgress` 为 public；`ApkDownloader` 为 internal：流式下载、断点续传、代理/直连回退、APK 头校验。 |
| `ApkInstaller.kt` | `public` 密封接口 + `internal` 实现 | `InstallResult` 为 public；`ApkInstaller` 为 internal：`ACTION_VIEW` + FileProvider 交给系统安装器。 |
| `UpdateDownloadWorker.kt` | `public class`（**仅供 WorkManager 反射**） | `CoroutineWorker`，前台下载的执行体。对消费者不应直接实例化/调用。 |
| `UpdateWorkScheduler.kt` | `internal` | 唯一一处启停 WorkManager unique work 的地方。 |
| `UpdateNotifier.kt` | `internal` | 通知渠道、通知、`ForegroundInfo`（API 34+ `dataSync`）。 |
| `UpdateStateStore.kt` | `internal` | SharedPreferences 记录 versionCode→apk 路径；顶层 `isValidApk()` 校验 ZIP 头。 |
| `UpdaterPaths.kt` | `internal` 顶层函数 | `updaterCacheDir(context, scope)`：所有落盘路径的唯一来源。 |
| `WorkerHttp.kt` | `internal object` | 进程级持有 `OkHttpClient`，供反射创建的 worker 取用。 |

配套资源：`updater/src/main/AndroidManifest.xml`（清单合并）、`res/xml/updater_file_paths.xml`（FileProvider 暴露路径）、`consumer-rules.pro`（R8 keep worker 构造）。

---

## 2. 能力边界

### 做什么（确定行为）

1. 匿名 GET `https://api.github.com/repos/{owner}/{repository}/releases/latest`，解析出 `ReleaseInfo` 与 `assets`。
2. 从 assets 中挑一个 `.apk`，用可配置的 `ReleaseVersionResolver` 算出 versionCode，与调用方传入的 `installedVersionCode` 比较（**严格大于**才算更新）。
3. 两种下载通道：WorkManager 前台下载（带进度通知、离开页面/锁屏/进程被杀后仍继续）与调用方协程内的内联下载（带字节级进度回调）。
4. 下载支持 `.apk.part` 断点续传（`Range: bytes=<n>-` / `206`）、代理失败回退直连、APK ZIP 头校验、原子落盘。
5. 落盘到 `cacheDir/updater/<scope>/`，用 SharedPreferences 记住 versionCode→路径，支持 `cachedApk()` 复用。
6. 通过 FileProvider `content://` URI + `ACTION_VIEW` 交给系统安装器，返回 `InstallResult.Launched | Failure`。
7. `checkAndPrefetch()`：启动时检查 + 静默预下载 + 通过 `status` 驱动红点。

### 不做什么（绝对不要误判）

| 不做 | 事实依据 / 后果 |
|---|---|
| 不提供静默/免确认安装 | `ApkInstaller` 只发 `Intent.ACTION_VIEW`，系统弹窗让用户逐次确认。 |
| 不回报安装结果 | `install()` 只返回 `Launched` / `Failure`；安装成功、失败、用户取消都**不会**回调给 SDK。 |
| 不校验 APK 签名是否与已装 App 一致 | SDK 只校验文件头 4 字节 `PK\x03\x04`；签名一致性由系统安装器强制，失败发生在系统弹窗里，SDK 不知情。 |
| 不做差分包 / 增量更新 | 永远整包下载 `browser_download_url`。 |
| 不管理 GitHub 认证 | 请求头只有 `Accept` / `X-GitHub-Api-Version: 2026-03-10` / `User-Agent: cuculus-update`，无 `Authorization`；匿名访问受 GitHub 未认证速率限制。 |
| 不向调用方暴露「暂停/恢复下载」API | 断点续传是内部自动行为（`.part`）；对外只有 `cancelForegroundDownload()` 取消。 |
| 不读取 `BuildConfig` / 不自动安装 | 已安装 versionCode 由调用方以 `Long` 传入；下载完成后必须显式调用 `install(apk)`。 |
| 不做 ABI / 渠道智能匹配 | 多个 APK asset 时按「contentType 优先 + 体积降序」挑一个（`ReleaseSelection.pickUpdateApk`），**不是**按设备 ABI 挑选。 |
| 不负责业务 UI / 不提供下载历史 | 只提供通知文案 `UpdaterStrings` 与状态 `status`；UI 全部由 App 自己写。 |
| 不访问草稿 / 预发布 | 只走 `/releases/latest`，GitHub 该端点只返回最新正式发布。 |

---

## 3. 完整数据流与时序

### 3.1 主时序（文字序列图，确定性描述）

```
调用方                 Updater (门面)           内部组件                         远端 / 系统
  │  check(installedCode) │                          │                                 │
  │──────────────────────▶│ ReleaseApi.latestRelease(owner, repository)             │
  │                        │──────────────────────────────────────────────────────▶│ GET /repos/{o}/{r}/releases/latest
  │                        │◀──────────────────────────────────────────────────────│ 200 JSON / 404 / 5xx
  │                        │ parseRelease → ReleaseInfo(tagName, assets[])           │
  │                        │ ReleaseSelection.pickUpdateApk(assets) → ReleaseAsset?  │
  │                        │ versionResolver.versionCodeOf(tag, asset.name) → Long?  │
  │                        │ ReleaseSelection.isUpdate(code, installedCode)          │
  │◀──────────────────────│ UpdateCheck.Available | UpToDate | NoInstallableAsset   │
  │                        │                                                         │
  │  downloadInForeground(asset)                                                     │
  │──────────────────────▶│ scheduler.start(...) → enqueueUniqueWork(REPLACE)       │
  │                        │ UpdateDownloadWorker.doWork(): setForeground(通知)      │
  │                        │   ApkDownloader.download(url, target, {progress})       │
  │                        │     HTTP GET (+Range) ────────────────────────────────▶│ 200 / 206 字节流
  │                        │     写 <target>.apk.part → 校验 ZIP 头 → 原子改名为 .apk│
  │                        │   setProgressAsync(progress) / Result.success(file_path)│
  │  observeForegroundProgress().collect { Float? } ◀── WorkManager WorkInfo.progress│
  │◀──────────────────────│ File(落盘 APK) 或抛 DownloadException                    │
  │                        │                                                         │
  │  install(apk)          │ ApkInstaller: FileProvider.getUriForFile → ACTION_VIEW ─▶│ 系统安装器弹窗（用户确认）
  │◀──────────────────────│ InstallResult.Launched | Failure                        │
```

### 3.2 `check(installedVersionCode)` 逐步（`Updater.kt:44`）

1. `api.latestRelease(config.owner, config.repository)` → `ReleaseInfo`。失败抛 `ReleaseApiException`（非 2xx）或 `IOException`（缺 `tag_name`、网络）。
2. `ReleaseSelection.pickUpdateApk(release.assets)`：过滤 `.apk`（忽略大小写）→ 按 `contentType == "application/vnd.android.package-archive"` 降序 → 再按 `sizeBytes` 降序 → `firstOrNull()`。**返回 null 即 `UpdateCheck.NoInstallableAsset`**。
3. `config.versionResolver.versionCodeOf(release.tagName, asset.name)` → `Long?`。**返回 null 也得到 `NoInstallableAsset`**（有 APK 但版本编码识别不了）。
4. `ReleaseSelection.isUpdate(versionCode, installedVersionCode)`：`versionCode != null && versionCode > installedVersionCode`。
   - 真 → `UpdateCheck.Available(release, asset, versionCode)`
   - 假 → `UpdateCheck.UpToDate(release, asset)`（仍带 release/asset，可用于调试时强制重下同一 APK）

> 判定只看**一个数字严格大于**，不比较 tag 字符串、不比较 `publishedAt`、不比较 asset 名。

### 3.3 前台下载 `downloadInForeground(asset)`（`Updater.kt:59`）

1. `targetName = ReleaseSelection.sanitizeAssetFileName(asset.name)`（把 `[^A-Za-z0-9._-]` 换成 `_`，防止路径逃逸）。
2. `scheduler.start(appContext, asset.downloadUrl, targetName, config.scope, config.strings)`：`OneTimeWorkRequestBuilder<UpdateDownloadWorker>()`，约束 `NetworkType.CONNECTED`，`enqueueUniqueWork("${scope}.download", ExistingWorkPolicy.REPLACE, request)`。`scope` 与 4 个字符串都塞进 `inputData`（WorkManager 持久化 → 进程被杀后重启仍知道往哪写、通知写什么）。
3. `WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(workName).first { infos.isNotEmpty() && infos.all { it.state.isFinished } }`：**挂起直到该 unique work 全部进入终态**。
4. 成功（最后一个 `WorkInfo.State.SUCCEEDED` 且 `OUTPUT_FILE_PATH` 非空）→ 返回 `File(path)`。
5. 否则读 `OUTPUT_ERROR`，抛 `DownloadException(reason)`；无 error 时抛 `DownloadException("update download did not complete (<state>)")`。

`UpdateDownloadWorker.doWork()`：读 `inputData` → `setForeground(notifier.foregroundInfo(0f))` → `ApkDownloader(WorkerHttp.resolve()).download(url, target) { progress -> setProgressAsync(workDataOf(PROGRESS_KEY to fraction)) }` → `Result.success(OUTPUT_FILE_PATH)`；异常（非 `CancellationException`）→ `Result.failure(OUTPUT_ERROR)`。**失败不重试**，用户再次点击时 `REPLACE` 重新入队，靠 `.part` 续传。

### 3.4 进度与取消

- `observeForegroundProgress()`：把 `WorkInfo.progress.getFloat(PROGRESS_KEY, -1f)` 映射为 `Float?`（`<0` 视为 null），`distinctUntilChanged()`。语义：`0f..1f` 或 null（未解析到进度时）。它是冷流无关的独立 Flow，可在任意协程单独 `collect`。
- `cancelForegroundDownload()`：`cancelUniqueWork("${scope}.download")`。取消后 `downloadInForeground()` 会因终态而抛 `DownloadException`（不是返回文件）。

### 3.5 内联下载 `download(asset, onProgress)`（`Updater.kt:94`）

- `target = File(downloadDirectory, sanitize(asset.name))`；`downloader.download(asset.downloadUrl, target, onProgress)`。
- 字节先写 `<target>.apk.part`：若已存在则从 `part.length()` 发 `Range: bytes=<n>-`；服务端回 `206` 续写，回 `200` 则从 0 重写。
- 每次缓冲 64 KiB，`onProgress` 每累计约 256 KiB 触发一次 `UpdateProgress(bytesDownloaded, totalBytes)`，结束再触发一次。
- 完成后 `verifyApkHeader()`（前 4 字节必须是 `PK\x03\x04`），否则删 `.part` 并抛 `DownloadException("the downloaded file is not an Android package")`；然后 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 把 `.part` 改名为 `.apk`。
- 截断（已知总长但接收不足）→ `DownloadException`。

### 3.6 安装 `install(apk)`（`ApkInstaller.kt:26`）

1. `!apk.isFile` → `Failure("the downloaded file is missing")`。
2. `FileProvider.getUriForFile(context, authority, apk)`，异常 → `Failure("unable to expose the APK: ...")`。
3. `Intent(ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")` + `FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION`。
4. `startActivity` 成功 → `Launched`；`ActivityNotFoundException` → `Failure("no installer is available on this device")`；其它异常 → `Failure(error.message ?: "unable to launch the installer")`。

### 3.7 `checkAndPrefetch(installedVersionCode)` 状态机（`Updater.kt:111`）

```
Idle ──check→Available──▶ UpdateAvailable(versionCode)
                              │ cachedApk(versionCode) 命中 ──▶ Downloaded(versionCode, cached)
                              │ 未命中：inline download 成功 ─▶ Downloaded(versionCode, target) + 写入 StateStore
                              │ 未命中：download 失败 ───────▶ 保持 UpdateAvailable（红点仍亮）
```

- `check()` 抛异常（非 `CancellationException`）→ 静默返回，`status` 保持原值。
- 非 `Available`（`UpToDate` / `NoInstallableAsset`）→ 静默返回。
- **吞掉所有异常，只有 `CancellationException` 向上抛**；绝不因瞬时网络问题崩溃或打扰用户。
- `status` 初值 `Idle`，进程内全局（不是持久化，重启 App 后重新计算）。

---

## 4. 两条下载通道对比

| | `downloadInForeground(asset)` | `download(asset, onProgress)` |
|---|---|---|
| 执行体 | WorkManager `CoroutineWorker`（独立进程/线程） | 调用方协程内 |
| 离开页面 / 锁屏 / 进程被杀 | 继续（前台服务 + 持久化 WorkInfo） | 随协程取消而中断（靠 `.part` 下次续传） |
| 进度 | `observeForegroundProgress(): Flow<Float?>` + 系统通知 | `onProgress: (UpdateProgress) -> Unit`，字节级 |
| 取消 | `cancelForegroundDownload()` | 取消调用方协程 |
| 失败表达 | 抛 `DownloadException` | 抛 `DownloadException` |
| 结果 | 返回落盘 `File` | 返回落盘 `File` |
| 适用 | 生产默认、大文件、需要「后台继续下」 | 调试、内联短下载、自定义进度 UI |

> 两者写入**同一个目录、同一个文件名**（都经 `updaterCacheDir()` + `sanitizeAssetFileName()`），因此共用同一个 `.part` 续传点。

---

## 5. 运行时状态与存储布局

全部以 `scope`（默认 `"self_update"`）命名空间隔离：

| 项 | 位置 / 名称 | 来源 |
|---|---|---|
| 落盘 APK | `cacheDir/updater/<scope>/<sanitised-asset>.apk` | `UpdaterPaths.kt` `updaterCacheDir()` |
| 续传临时文件 | `cacheDir/updater/<scope>/<sanitised-asset>.apk.part` | `ApkDownloader` |
| 已下载记录 | SharedPreferences `"<scope>.updater"`，键 `downloaded_version_code` / `downloaded_apk_path`（**单条记录**：一个 versionCode + 一个路径） | `UpdateStateStore.kt` |
| 通知渠道 | channelId `"<scope>.updates"`，notificationId `scope.hashCode()` | `UpdateNotifier.kt` |
| WorkManager 唯一任务 | unique work `"<scope>.download"`（`ExistingWorkPolicy.REPLACE`） | `UpdateWorkScheduler` / `Updater.kt:34` |
| FileProvider 暴露根 | `cache-path path="updater/"`（覆盖所有 scope） | `res/xml/updater_file_paths.xml` |

`cachedApk(versionCode)` 只在「记录的 code 相等 **且** 文件存在 **且** 文件头仍是 `PK\x03\x04`」时返回 `File`；否则清掉脏记录并返回 null（`UpdateStateStore.cachedApkForVersion` + `isValidApk`）。

---

## 6. 关键设计决策（为什么）

### 6.1 为什么用 WorkManager 前台服务
- 自更新 APK 往往几十 MB，用户会切走/锁屏。普通协程随页面销毁；WorkManager 把任务持久化，`SystemForegroundService`（`foregroundServiceType="dataSync"`）让下载在后台继续。
- `setForeground()` + `getForegroundInfo()` 覆盖 API 34+ 的前台服务类型要求；`FOREGROUND_SERVICE_DATA_SYNC` 权限由库清单合并。
- unique work + `REPLACE`：再次点「更新」不会排第二个 worker，而是重启；配合 `.part` 续传，重启代价很低。
- `scope` / 文案放进 `inputData` 而非进程全局变量：WorkManager 持久化 input data，进程被杀后重建的 worker 仍知道目标目录与通知文案。
- 约束 `NetworkType.CONNECTED`；失败返回 `Result.failure` 而非 `retry()`，重试由用户再次点击触发。
- `consumer-rules.pro` 保留 `UpdateDownloadWorker(Context, WorkerParameters)` 两参构造：WorkManager 按持久化类名反射实例化，R8 全量混淆时必须 keep。

### 6.2 为什么用 FileProvider
- 下载物在 App 私有 `cacheDir`，系统安装器进程无法直接读文件路径。
- 用 `content://` URI + `FLAG_GRANT_READ_URI_PERMISSION` 临时授权，`exported="false"`，最小暴露面。
- `updater_file_paths.xml` 只暴露 `cacheDir/updater/` 一棵子树；`UpdaterPaths.updaterCacheDir()` 是唯一路径来源，保证 worker 写入路径与 FileProvider 暴露路径**字节级一致**——两者一旦漂移，`install()` 会失败。

### 6.3 为什么用 OkHttp（且是 `api` 依赖）
- 需要 `Range` 续传（`206`）、连接/读超时（`30s` / `120s`）、`Proxy.NO_PROXY` 直连回退、流式写盘。
- `Updater.create(..., httpClient: OkHttpClient?)` 把 `OkHttpClient` 暴露在 public 签名里；`Flow` / `StateFlow` 出现在 `UpdateGateway` 与 `status` 上。因此 okhttp 与 kotlinx-coroutines-core 用 `api(...)`，消费者编译时必须能看见它们。
- 代理/直连回退：先用系统代理客户端，再备一个 `Proxy.NO_PROXY` 客户端；**只有连接失败 / `SocketTimeoutException` 且尚未写入新字节**（`part.length() <= startBytes`）时才切换通道。HTTP 4xx/5xx 与传输中途失败**绝不重放**（会把半截内容重复追加）。

### 6.4 为什么 `WorkerHttp` 是进程级
- WorkManager 以持久化类名 + `(Context, WorkerParameters)` 签名反射重建 worker，**没有构造器注入通道**，无法把 App 的 `OkHttpClient` 传进去。
- `Updater.create()` 把客户端写进 `WorkerHttp.client`（`@Volatile`）；worker 用 `WorkerHttp.resolve()` 取回。
- 若进程被杀、worker 先于 App 再次 `create()` 启动，`resolve()` 回退到 `OkHttpClient()` 让下载仍能完成，只是那一次失去 App 的 proxy/DNS 配置。→ 这正是 `create()` 应做成 Application 单例的原因。

### 6.5 为什么 `ReleaseSelection` / `isValidApk` 是纯 JVM
- 挑 asset、判定更新、消毒文件名、校验 ZIP 头都不依赖 Android framework，可在 JVM 单测里直接跑（对应 `ReleaseSelectionTest` 10 例 / `UpdateStateStoreTest` 4 例）。

---

## 7. 依赖与清单合并原理

### 7.1 `:updater` 依赖（`gradle/libs.versions.toml`）

| 依赖 | 版本 | 配置 | 原因 |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 5.5.0 | `api` | `OkHttpClient` 出现在 `Updater.create` public 签名；`ReleaseApi` / `ApkDownloader` 用它 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | `api` | `Flow` / `StateFlow` 出现在 `UpdateGateway` / `Updater.status` |
| `androidx.core:core-ktx` | 1.19.1 | `implementation` | `FileProvider`、`NotificationCompat`；不泄漏到 public API |
| `androidx.work:work-runtime-ktx` | 2.12.0 | `implementation` | 前台下载 worker；不泄漏到 public API |
| junit4 4.13.2 / coroutines-test 1.11.0 / okhttp-mockwebserver3 5.5.0 / org.json 20260814 | — | `testImplementation` | JVM 单测；`android.jar` 只带 `org.json` 桩，故补真实实现 |

- 版本号来自版本目录；SDK 自身版本来自 `gradle.properties` 的 `updaterSdkVersion=1.0.0`（`updater/build.gradle.kts:28` `getOrElse("1.0.0")`）。
- `group = "dev.cuculus"`、`artifactId = "cuculus-update"` → 坐标 `dev.cuculus:cuculus-update:1.0.0`。
- 工程要求：Gradle wrapper 9.5.0、JDK 17、AGP 9.3.1（内置 Kotlin，无需单独 Kotlin 插件）、compileSdk 37、minSdk 26。

### 7.2 清单合并（`updater/src/main/AndroidManifest.xml`）

库的 manifest 会**自动合并进消费者 App**，因此 App **不需要声明任何权限 / Service / FileProvider**：

| 合并项 | 值 | 作用 |
|---|---|---|
| `uses-permission` | `INTERNET`、`ACCESS_NETWORK_STATE` | GitHub API 与 APK 传输、网络状态 |
| | `REQUEST_INSTALL_PACKAGES` | 让系统安装器弹出「安装未知应用」确认；**普通权限，无运行时弹窗**，用户仍逐次确认安装 |
| | `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC` | 前台下载服务；API 34+ 需声明类型 |
| | `POST_NOTIFICATIONS` | 声明在库里，但 **API 33+ 仍需 App 在运行时申请授权**；未授权时下载照常进行，只是进度通知被隐藏 |
| `service` | `androidx.work.impl.foreground.SystemForegroundService`，`foregroundServiceType="dataSync"`，`tools:node="merge"` | WorkManager 拥有该服务；`merge` 允许已声明它的消费者保留自身属性 |
| `provider` | `androidx.core.content.FileProvider`，`authorities="${applicationId}.updater"`，`exported="false"`，`grantUriPermissions="true"`，meta-data `@xml/updater_file_paths` | 把 APK 暴露给系统安装器 |

`${applicationId}` 在合并时替换为 App 最终 applicationId（含 `applicationIdSuffix` 时也随之变化），所以 `UpdaterConfig.fileProviderAuthority` 必须等于这个最终值 + `.updater`（见 `04-configuration.md`）。

---

## 8. 该做什么 / 不该做什么 / 常见误判

**该做什么**
- 把下载的 `File` 直接交给 `install(apk)`；不要自己重组路径。
- 用 `status` 驱动红点，用 `cachedApk(versionCode)` 复用已下载 APK。
- 前台下载时用 `observeForegroundProgress()` 单独起协程收集进度，与主下载协程解耦。
- 多个更新源用不同 `scope`，而不是创建多个进程/多个 FileProvider。

**不该做什么**
- 不要在 App manifest 里重复声明上述权限/provider/service（合并已提供）。
- 不要直接引用任何 `internal` 类（`ApkDownloader`、`ReleaseApi`、`ReleaseSelection`、`UpdateWorkScheduler`、`UpdateNotifier`、`UpdateStateStore`、`WorkerHttp`、`updaterCacheDir`、`UpdateDownloadWorker` 的常量等）。
- 不要认为 `install()` 返回 `Launched` 就代表安装成功。
- 不要认为下载失败会自动重试；SDK 返回 `Result.failure`，重试由用户再次触发。

**常见误判**
| 误判 | 真相 |
|---|---|
| 「`UpToDate` 表示没有 release」 | `UpToDate` 表示有 release 且有 APK，只是 versionCode ≤ 已安装；`NoInstallableAsset` 才是「没有可装 APK / 编码识别不了」。 |
| 「`checkAndPrefetch` 会抛网络异常」 | 它吞掉所有异常，仅 `CancellationException` 上抛；失败时 `status` 停在 `UpdateAvailable`。 |
| 「多放几个 ABI APK 会更稳」 | SDK 按 contentType + 体积挑一个，不按 ABI，可能挑错；一个目标 APK 最稳妥。 |
| 「进度通知不显示 = 下载没跑」 | API 33+ 未授予 `POST_NOTIFICATIONS` 时下载仍跑，仅通知隐藏。 |
| 「`status` 重启后还在」 | `status` 是进程内 `MutableStateFlow`，重启重新计算；只有 SharedPreferences 里的 APK 记录持久化。 |
| 「`.part` 是垃圾文件可以随便删」 | 它是续传点；删掉只会让下次从头下，不会出错，但会浪费流量。 |
