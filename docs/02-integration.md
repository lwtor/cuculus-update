# 02 · 一页式接入（可直接复制粘贴）

> 目的：一个大模型/工程师读完本篇，不查其它资料就能把 SDK 装进任意 Android 项目并跑通。所有代码含 `package` / `import` / 协程作用域 / 错误处理；`com.example.app` 是**包名占位**，替换为你自己的 namespace / applicationId。
> 每个步骤末尾都有「验收方式」。签名细节见 `03-api-reference.md`，字段含义见 `04-configuration.md`，发布侧约定见 `05-release-contract.md`。

---

## 0. 接入总览（先看清全貌）

```
Gradle 加 1 行依赖（+ coroutines-android）
        │
        ▼
Application.onCreate 里 Updater.create(...) 存成单例   ← 唯一入口
        │
        ├─ check(installedVersionCode)       → UpdateCheck 三态
        ├─ checkAndPrefetch(code) + status   → 启动静默预下载 + 红点
        ├─ downloadInForeground(asset)       → File（离开页面仍继续）
        │     ├─ observeForegroundProgress() → Flow<Float?>
        │     └─ cancelForegroundDownload()
        ├─ download(asset) { UpdateProgress } → File（内联，字节级进度）
        └─ install(apk)                      → InstallResult.Launched | Failure
```

**App 的 `AndroidManifest.xml` 不需要任何权限 / Service / FileProvider 声明**——全部由库清单合并（见 `01-architecture.md` §7.2）。

---

## 1. Gradle 依赖

### `settings.gradle.kts`（你的工程）

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // mavenLocal() // 仅当你用 :updater:publishToMavenLocal 本地调试时启用
    }
}
```

### `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.app"          // 占位：替换为你的 namespace
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.app"  // 占位：决定 ${applicationId}.updater 这个 authority
        minSdk = 26                        // 必须 >= 26（库 minSdk）；低于 26 无法合并
        targetSdk = 37
        versionCode = 1_009_003            // 占位：必须与发布端同一套编码，见 04/05
        versionName = "1.9.3"
    }

    buildFeatures {
        buildConfig = true                // 关键：AGP 8+ 默认关闭，不开就没有 BuildConfig.VERSION_CODE
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.cuculus:cuculus-update:1.0.0")

    // 必须：SDK 用 api(kotlinx-coroutines-core:1.11.0) 暴露 Flow/StateFlow，
    // 但 Android 上的 Dispatchers.Main 实现来自 coroutines-android，需显式同版本引入。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
```

> 下面的 **Compose / ViewModel 示例**（第 6 步）还会用到标准 AndroidX 组件：`androidx.lifecycle:lifecycle-viewmodel-ktx`、`androidx.lifecycle:lifecycle-viewmodel-compose`、`androidx.activity:activity-compose`、Compose BOM。它们是 Android 工程常规依赖，按你工程现有版本管理（版本目录 / BOM）添加即可，**不属于 cuculus-update 的 API**。若走第 2~5 步的传统 View + 自建 `CoroutineScope` 方案，则只需要上面两行依赖。

**验收方式**：`./gradlew :app:assembleDebug` 编译通过；`external libraries` 里能看到 `cuculus-update-1.0.0`。

### 「清单无需任何声明」——反例对照

```xml
<!-- ❌ 反例：以下全部不要写进 App 的 AndroidManifest.xml，库已经合并了 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<service android:name="androidx.work.impl.foreground.SystemForegroundService" />
<provider android:name="androidx.core.content.FileProvider" ... />
```

✅ 正确写法只有「注册 Application + Activity」（见第 2 步）。

**验收方式**：Build 后在 Android Studio 打开 app 模块的 **Merged Manifest** 视图，确认已出现 6 个 `uses-permission`（`INTERNET` / `ACCESS_NETWORK_STATE` / `REQUEST_INSTALL_PACKAGES` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`）、`SystemForegroundService`（`dataSync`）以及 `authorities="<applicationId>.updater"` 的 FileProvider。若没出现，说明库没被正确依赖。

---

## 2. 初始化：Application 单例（唯一入口）

### `app/src/main/java/com/example/app/App.kt`

```kotlin
package com.example.app   // 占位：替换为你的包名

import android.app.Application
import dev.cuculus.update.ReleaseVersionResolver
import dev.cuculus.update.Updater
import dev.cuculus.update.UpdaterConfig
import okhttp3.OkHttpClient

class App : Application() {

    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()

        // 可选：复用 App 现成的 OkHttpClient，让 SDK 与前台 worker 共用连接池 / proxy / DNS。
        // 不传（或传 null）时 SDK 自建 OkHttpClient()。
        // 注意：Updater.create 会把该 client 写入进程级 WorkerHttp，供反射创建的 worker 取用。
        val sharedClient: OkHttpClient? = null // 例如：= provideYourOkHttpClient()

        updater = Updater.create(
            context = this,                        // 内部取 applicationContext，可长期持有，不会泄漏 Activity
            config = UpdaterConfig(
                owner = "lwtor",                   // 占位：替换为你发布仓库的 GitHub owner / org
                repository = "venera-native",      // 占位：替换为仓库名（其 latest release 是更新源）
                fileProviderAuthority = "$packageName.updater", // 必须等于 ${applicationId}.updater
                versionResolver = ReleaseVersionResolver.Or(
                    ReleaseVersionResolver.AssetNameSuffix(),    // 首选：从 asset 名尾部读 versionCode
                    ReleaseVersionResolver.TagScheme(),        // 兜底：从 tag v1.9.3 读 1_009_003
                ),
                // scope = "self_update",          // 默认即可；多更新源才改，见 04
                // strings = UpdaterStrings(...),  // 默认中文；覆盖英文见 04
            ),
            httpClient = sharedClient,
        )
    }
}
```

### `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 只注册 Application 与 Activity；权限/Service/FileProvider 全部由库合并，绝对不要在此声明。 -->
    <application
        android:name=".App"
        android:label="@string/app_name"
        android:theme="@style/Theme.App">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**该做什么 / 不该做什么**
- ✅ 只在 `Application.onCreate()` 里 `create()` 一次，全 App 共用这个实例。
- ❌ 不要每个页面各建一个 `Updater`：`WorkerHttp.client` 是进程级变量，多实例会互相覆盖 proxy/DNS 配置。
- ✅ `fileProviderAuthority` 用 `"$packageName.updater"`；在 Application 里 `packageName` 返回最终 applicationId（含 `applicationIdSuffix`），与合并清单的 `${applicationId}.updater` 一致。
- ❌ 不要传 Activity 作为 `context` 长期持有（虽然内部会取 `applicationContext`，但语义上应传 Application）。

**验收方式**：App 启动不崩溃，`(application as App).updater` 非空；首次安装后 `install()` 能正常拉起系统安装器（说明 authority 匹配）。

---

## 3. 读取已安装 versionCode

`Updater.check()` / `checkAndPrefetch()` 都要一个 `Long` 型的「已安装版本」。用 `BuildConfig.VERSION_CODE.toLong()`：

```kotlin
// 与 Activity 同包，BuildConfig 无需 import。
private fun installedVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()
```

- `BuildConfig.VERSION_CODE` 是 `Int`，**必须 `.toLong()`**。
- 前提是第 1 步开了 `buildFeatures { buildConfig = true }`。
- 可选替代（不想开 buildConfig 时）：`context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode`，但 `longVersionCode` 是 API 28+，而库 minSdk 26，需要 `Build.VERSION.SDK_INT >= 28` 分支兜底——**推荐直接用 BuildConfig**。

**常见误判**：把 `versionName`（`"1.9.3"` 字符串）传进去，或忘了 `buildConfig = true` 导致 `BuildConfig` 找不到。

---

## 4. 完整 Activity：检查 + 前台下载 + 进度 + 取消 + 安装 + 启动预下载 + 红点

这是**可直接复制粘贴**的完整文件（传统 View）。布局需提供这些 id：`check_button`、`download_button`、`cancel_button`、`install_button`、`progress_bar`（水平、`max=100`）、`status_text`。

### `app/src/main/java/com/example/app/MainActivity.kt`

```kotlin
package com.example.app   // 占位：替换为你的包名（与 App.kt 同包，BuildConfig 可直接用）

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import dev.cuculus.update.DownloadException
import dev.cuculus.update.InstallResult
import dev.cuculus.update.ReleaseApiException
import dev.cuculus.update.ReleaseAsset
import dev.cuculus.update.UpdateCheck
import dev.cuculus.update.UpdateStatus
import dev.cuculus.update.Updater
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    // 自建作用域，不依赖 lifecycle-runtime-ktx；onDestroy 里 cancel。
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 单例来自 Application（第 2 步），不要在这里再 create 一个 Updater。
    private val updater: Updater get() = (application as App).updater

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button

    private var availableAsset: ReleaseAsset? = null
    private var downloadedApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        downloadButton = findViewById(R.id.download_button)
        installButton = findViewById(R.id.install_button)

        findViewById<Button>(R.id.check_button).setOnClickListener { checkForUpdate() }
        downloadButton.setOnClickListener { downloadUpdate() }
        findViewById<Button>(R.id.cancel_button).setOnClickListener {
            updater.cancelForegroundDownload()
        }
        installButton.setOnClickListener { installUpdate() }

        ensureNotificationPermission()

        // 启动即检查：静默预下载 + 红点。checkAndPrefetch 吞掉所有异常，
        // 仅 CancellationException 向上抛，因此这里不需要 try/catch。
        // ★ checkAndPrefetch 内部会静默内联下载、用 RandomAccessFile 阻塞写盘，
        //   跑在 uiScope 的 Main.immediate 上有 ANR 风险，必须切到 Dispatchers.IO。
        uiScope.launch {
            withContext(Dispatchers.IO) { updater.checkAndPrefetch(installedVersionCode()) }
        }

        // 用 SDK 的进程内状态驱动红点；Downloaded 时 APK 已就绪，可直接安装。
        uiScope.launch { updater.status.collect { renderStatus(it) } }
    }

    private fun installedVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    /** 检查更新：suspend 调用，必须包在协程里；分别处理 API 错误与网络错误。 */
    private fun checkForUpdate() {
        availableAsset = null
        downloadButton.isEnabled = false
        installButton.isEnabled = false
        show("正在检查…")
        uiScope.launch {
            val check: UpdateCheck = try {
                updater.check(installedVersionCode())
            } catch (error: ReleaseApiException) {
                show("检查失败：GitHub API ${error.statusCode}：${error.message}")
                return@launch
            } catch (error: IOException) {
                show("检查失败：${error.message}")
                return@launch
            }
            when (check) {
                is UpdateCheck.Available -> {
                    availableAsset = check.asset
                    downloadButton.isEnabled = true
                    show("发现新版本 code=${check.versionCode}，asset=${check.asset.name}")
                }
                is UpdateCheck.UpToDate ->
                    show("已是最新版本（code 未超过 ${installedVersionCode()}）")
                UpdateCheck.NoInstallableAsset ->
                    show("该发布中没有可安装的 APK，或 versionCode 无法解析")
            }
        }
    }

    /** 前台下载：进度 Flow 独立收集，与下载协程解耦；离开页面/锁屏仍继续。 */
    private fun downloadUpdate() {
        val asset = availableAsset ?: return
        downloadButton.isEnabled = false
        show("开始前台下载 ${asset.name}（离开页面仍继续）")

        // observeForegroundProgress() 是独立 Flow；在 Main 上 collect，直接更新 View 是安全的。
        val progressJob: Job = uiScope.launch {
            updater.observeForegroundProgress().collect { fraction ->
                val percent = ((fraction ?: 0f) * 100).toInt()
                progressBar.progress = percent
                show("下载中 $percent%")
            }
        }

        uiScope.launch {
            try {
                // 挂起直到 worker 成功（返回落盘 File）或进入失败终态（抛 DownloadException）。
                val apk: File = updater.downloadInForeground(asset)
                downloadedApk = apk
                installButton.isEnabled = true
                progressBar.progress = 100
                show("下载完成：${apk.name}（${apk.length() / 1024} KB）")
            } catch (error: DownloadException) {
                show("下载失败：${error.message}")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                show("下载失败：${error.message}")
            } finally {
                progressJob.cancel()
            }
        }
    }

    /** 安装：只表示「已把 APK 交给系统安装器」，由用户在系统弹窗里确认。 */
    private fun installUpdate() {
        val apk = downloadedApk ?: return
        when (val result = updater.install(apk)) {
            InstallResult.Launched -> show("已交给系统安装器，请在系统弹窗中确认")
            is InstallResult.Failure -> show("安装失败：${result.message}")
        }
    }

    /** status 三态：Idle / UpdateAvailable（亮红点）/ Downloaded（可直接安装）。 */
    private fun renderStatus(status: UpdateStatus) {
        when (status) {
            UpdateStatus.Idle -> show("空闲")
            is UpdateStatus.UpdateAvailable ->
                show("红点：发现新版本 code=${status.versionCode}（APK 未就绪或预下载失败）")
            is UpdateStatus.Downloaded -> {
                downloadedApk = status.apkFile
                installButton.isEnabled = true
                show("红点：已就绪 code=${status.versionCode}，可点安装")
            }
        }
    }

    /** API 33+ 运行时申请 POST_NOTIFICATIONS；未授权时下载仍进行，只是进度通知隐藏。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun show(message: String) { statusText.text = message }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
```

**验收方式（逐步）**

| 步骤 | 操作 | 期望结果 |
|---|---|---|
| 检查 | 点 `check_button` | 有更新 → `发现新版本 code=...`；无 → `已是最新版本`；Release 无 APK → `没有可安装的 APK`。断网 → `检查失败：...`（不崩溃）。 |
| 前台下载 | 点 `download_button` | 通知栏出现「正在下载新版本」进度条；`progress_bar` 从 0 涨到 100；切到别的 App / 锁屏再回来，下载仍完成。 |
| 取消 | 下载中点 `cancel_button` | 通知消失，`下载失败：...`（`downloadInForeground` 抛 `DownloadException`），进度协程被 `cancel()`。 |
| 安装 | 下载完成后点 `install_button` | 系统安装器弹窗出现；`Launched` 文案。若 `Failure`，先查 `fileProviderAuthority` 与落盘文件是否存在。 |
| 启动预下载 + 红点 | 冷启动 App（已有新版本 Release） | `status` 依次变 `UpdateAvailable` → `Downloaded`，安装按钮变可用；杀掉网络再启动，停在 `UpdateAvailable` 且不崩溃。 |

---

## 5. 可选：内联下载 `download()`（字节级进度）

`download(asset, onProgress)` 在**调用方协程内**执行，含阻塞式文件 I/O。**必须在 `Dispatchers.IO` 上调用**，且 `onProgress` 回调所在线程不确定，只写线程安全的容器（如 `StateFlow` / `MutableStateFlow`），不要直接改 View。

```kotlin
// 额外 import：
// import dev.cuculus.update.UpdateProgress
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.withContext
// import kotlinx.coroutines.flow.MutableStateFlow
// import kotlinx.coroutines.flow.collect

private fun downloadInline(asset: ReleaseAsset) {
    val progress = MutableStateFlow<Float?>(null)   // 线程安全，充当回调→UI 的桥

    uiScope.launch {
        progress.collect { fraction ->
            progressBar.progress = ((fraction ?: 0f) * 100).toInt()  // collect 在 Main，安全
        }
    }

    uiScope.launch {
        val apk: File = withContext(Dispatchers.IO) {
            updater.download(asset) { p: UpdateProgress ->
                progress.value = p.fraction      // 只写 StateFlow，不碰 View
            }
        }
        downloadedApk = apk
        installButton.isEnabled = true
        show("内联下载完成：${apk.name}")
    }
}
```

- `UpdateProgress.bytesDownloaded: Long`、`totalBytes: Long?`、`fraction: Float?`（总长未知时为 null）。
- `onProgress` 默认 `{}`：`updater.download(asset)` 省略 lambda 也能编译（Kotlin 重写方法继承接口默认值），只是收不到进度。
- 断点续传是内部自动行为（`.apk.part` + `Range`），调用方无需干预。

---

## 6. Compose / ViewModel 完整示例

> 依赖备注：本示例用到 `androidx.lifecycle:lifecycle-viewmodel-ktx`、`androidx.lifecycle:lifecycle-viewmodel-compose`（`viewModel()`）、`androidx.activity:activity-compose`（`ComponentActivity` / `setContent`）与 Compose BOM。按你工程现有版本添加，不属于 cuculus-update 的 API。`Updater` 仍来自第 2 步的 Application 单例。

### `UpdateViewModel.kt`

```kotlin
package com.example.app   // 占位

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cuculus.update.DownloadException
import dev.cuculus.update.InstallResult
import dev.cuculus.update.ReleaseApiException
import dev.cuculus.update.ReleaseAsset
import dev.cuculus.update.UpdateCheck
import dev.cuculus.update.UpdateStatus
import dev.cuculus.update.Updater
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateUiState(
    val message: String = "",
    val progress: Int = 0,                 // 0..100
    val canDownload: Boolean = false,
    val canInstall: Boolean = false,
    val hasUpdateBadge: Boolean = false,
)

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val updater: Updater = (app as App).updater   // 复用 Application 单例

    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    private var availableAsset: ReleaseAsset? = null
    private var downloadedApk: File? = null
    private var progressJob: Job? = null

    private fun installedVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    init {
        // 启动静默预下载（吞异常）+ 订阅红点状态。
        // ★ viewModelScope 默认在 Main；checkAndPrefetch 内部会静默内联下载、
        //   用 RandomAccessFile 阻塞写盘，必须切到 Dispatchers.IO 以免 ANR。
        viewModelScope.launch {
            withContext(Dispatchers.IO) { updater.checkAndPrefetch(installedVersionCode()) }
        }
        viewModelScope.launch { updater.status.collect(::renderStatus) }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(message = "正在检查…", canDownload = false, canInstall = false)
            val check = try {
                updater.check(installedVersionCode())
            } catch (error: ReleaseApiException) {
                _ui.value = _ui.value.copy(message = "检查失败：GitHub API ${error.statusCode}：${error.message}")
                return@launch
            } catch (error: IOException) {
                _ui.value = _ui.value.copy(message = "检查失败：${error.message}")
                return@launch
            }
            when (check) {
                is UpdateCheck.Available -> {
                    availableAsset = check.asset
                    _ui.value = _ui.value.copy(
                        message = "发现新版本 code=${check.versionCode}",
                        canDownload = true,
                        hasUpdateBadge = true,
                    )
                }
                is UpdateCheck.UpToDate ->
                    _ui.value = _ui.value.copy(message = "已是最新版本", hasUpdateBadge = false)
                UpdateCheck.NoInstallableAsset ->
                    _ui.value = _ui.value.copy(message = "该发布中没有可安装的 APK")
            }
        }
    }

    fun download() {
        val asset = availableAsset ?: return
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            updater.observeForegroundProgress().collect { fraction ->
                _ui.value = _ui.value.copy(
                    progress = ((fraction ?: 0f) * 100).toInt(),
                    message = "下载中 ${((fraction ?: 0f) * 100).toInt()}%",
                )
            }
        }
        viewModelScope.launch {
            try {
                val apk = updater.downloadInForeground(asset)
                downloadedApk = apk
                _ui.value = _ui.value.copy(
                    message = "下载完成：${apk.name}",
                    progress = 100,
                    canInstall = true,
                )
            } catch (error: DownloadException) {
                _ui.value = _ui.value.copy(message = "下载失败：${error.message}")
            } finally {
                progressJob?.cancel()
            }
        }
    }

    fun cancelDownload() {
        updater.cancelForegroundDownload()
        progressJob?.cancel()
        _ui.value = _ui.value.copy(message = "已取消下载")
    }

    fun install() {
        val apk = downloadedApk ?: return
        when (val result = updater.install(apk)) {
            InstallResult.Launched ->
                _ui.value = _ui.value.copy(message = "已交给系统安装器，请在弹窗中确认")
            is InstallResult.Failure ->
                _ui.value = _ui.value.copy(message = "安装失败：${result.message}")
        }
    }

    private fun renderStatus(status: UpdateStatus) {
        _ui.value = when (status) {
            UpdateStatus.Idle -> _ui.value
            is UpdateStatus.UpdateAvailable -> _ui.value.copy(
                message = "红点：发现新版本 code=${status.versionCode}",
                hasUpdateBadge = true,
            )
            is UpdateStatus.Downloaded -> {
                downloadedApk = status.apkFile
                _ui.value.copy(
                    message = "红点：已就绪 code=${status.versionCode}",
                    hasUpdateBadge = true,
                    canInstall = true,
                )
            }
        }
    }
}
```

### `MainActivity.kt`（Compose 版）

```kotlin
package com.example.app   // 占位

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UpdateScreen() }
    }
}

@Composable
fun UpdateScreen(vm: UpdateViewModel = viewModel()) {
    val state by vm.ui.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = state.message)
        if (state.hasUpdateBadge) {
            Text(text = "● 有新版本")
        }
        LinearProgressIndicator(
            progress = { state.progress / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::checkForUpdate, modifier = Modifier.fillMaxWidth()) {
            Text("检查更新")
        }
        Button(
            onClick = vm::download,
            enabled = state.canDownload,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("下载") }
        Button(onClick = vm::cancelDownload, modifier = Modifier.fillMaxWidth()) {
            Text("取消下载")
        }
        Button(
            onClick = vm::install,
            enabled = state.canInstall,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("安装") }
    }
}
```

> `LinearProgressIndicator(progress = { ... })` 用 Compose Material3 的 lambda 重载；若你的 Compose 版本用旧签名 `progress: Float`，改成 `progress = state.progress / 100f`。`POST_NOTIFICATIONS` 运行时申请放在 `ComponentActivity.onCreate()` 里，代码与第 4 步 `ensureNotificationPermission()` 完全相同。

**验收方式**：Compose 页面点「检查更新」出现红点；点「下载」进度条 0→100；点「安装」拉起系统弹窗；冷启动自动预下载并点亮安装按钮。

---

## 7. 每步验收汇总

| # | 步骤 | 验收方式 |
|---|---|---|
| 1 | Gradle / 清单 | `assembleDebug` 通过；Merged Manifest 含 6 权限 + Service + `${applicationId}.updater` provider |
| 2 | 初始化 | App 启动不崩溃，`(application as App).updater` 非空 |
| 3 | versionCode | `BuildConfig.VERSION_CODE.toLong()` 能编译，且与发布端编码一致 |
| 4 | 检查 | `check()` 返回三态之一；断网/404 走 catch 不崩溃 |
| 5 | 前台下载 | 通知栏有进度；离屏/锁屏仍完成；失败抛 `DownloadException` |
| 6 | 取消 / 安装 | 取消后进度协程收尾；安装拉起系统弹窗 |
| 7 | 预下载 + 红点 | `status` 依次 `UpdateAvailable` → `Downloaded` |
| 8 | Compose / ViewModel | 红点、进度条、安装按钮按状态联动 |

---

## 8. 该做什么 / 不该做什么 / 常见误判

**该做什么**
- 只在 `Application` 里 `Updater.create()` 一次；全 App 复用该实例。
- 所有 `suspend` 调用（`check` / `downloadInForeground` / `download` / `checkAndPrefetch`）都包在协程里。
- `check()` / `downloadInForeground()` 用 `try/catch` 包住：`ReleaseApiException` / `IOException` / `DownloadException`。
- 前台下载用 `observeForegroundProgress()` 单独起协程收集；`finally` 里 `cancel()` 掉它。
- `install()` 之前确保 `File` 来自 `download*` / `cachedApk()` / `status.Downloaded.apkFile`。
- 把 `checkAndPrefetch()` 放进启动路径，用 `status.collect` 驱动红点。

**不该做什么**
- ❌ **不要把 `Updater.create(this, ...)` 写在 `Activity` 的属性初始化器里**：Activity 构造时 base Context 未 attach，`packageName` / `applicationContext` 为 null → 启动即崩。只在 `Application.onCreate()` 创建单例（见 §2 的 `App.kt` 模板）。
- ❌ 不要在 `AndroidManifest.xml` 声明权限 / Service / FileProvider（库已合并）。
- ❌ 不要在 `Dispatchers.Main` 上直接调 `download()`（下载循环含阻塞 I/O）；不要在 `onProgress` 里直接改 View。
- ❌ 不要向 `check()` 传 `versionName`；不要忘 `buildConfig = true` 和 `.toLong()`。
- ❌ 不要认为 `install()` 返回 `Launched` 就代表装好了；不要等一个「安装成功」回调（不存在）。
- ❌ 不要 `import dev.cuculus.update.ApkDownloader` 等 `internal` 类（编译不可见）。
- ❌ 不要 `catch (Exception)` 后把 `CancellationException` 一起吞掉：应重新抛出（见第 4 步示例）。

**常见误判**

| 误判 | 真相 |
|---|---|
| 「要自己声明 `REQUEST_INSTALL_PACKAGES` / FileProvider」 | 库清单已合并；自己写反而可能冲突。 |
| 「`updater.download(asset)` 省略 lambda 会编译失败」 | 接口默认值 `{}` 被重写方法继承，`download(asset)` 合法，只是收不到进度。 |
| 「`observeForegroundProgress()` 必须在 `downloadInForeground()` 之后才能 collect」 | 它是独立 Flow，先后无所谓；进度未解析时发 `null`。 |
| 「`checkAndPrefetch()` 需要 try/catch」 | 它吞掉所有异常，仅 `CancellationException` 上抛；不写 try/catch 是安全的。 |
| 「`status` 重启后还在 / 会持久化」 | `status` 是进程内 `StateFlow`，重启重新算；持久化的只有 SharedPreferences 里的 APK 记录。 |
| 「通知没显示 = 下载没跑」 | API 33+ 未授予 `POST_NOTIFICATIONS` 时下载照常，仅进度通知隐藏。 |
| 「`cancelForegroundDownload()` 后 `downloadInForeground()` 会返回 null」 | 它抛 `DownloadException`（worker 进入失败终态）。 |
| 「用 `versionName` 比较更直观」 | SDK 只认一个严格递增的 `Long` versionCode；见 `04` / `05`。 |
