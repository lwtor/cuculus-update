# 09 · 大模型「一步到位」接入手册（核心）

> **本文是完整知识库的核心。** 目标：一个大模型**只读本文一遍**，就能独立、一步到位把 `dev.cuculus:cuculus-update:1.1.1` 接入任意 Android 项目，不需要来回追问。
> 本文给的是**确定规则、决策树、完整可复制代码、逐条可验证的验收清单**。
>
> 前提事实（均以 `updater/src/main/kotlin/dev/cuculus/update/*.kt` 为准）：
> - 门面：`Updater.create(context, config, httpClient?): Updater`；`Updater : UpdateGateway`。
> - `Updater.status: StateFlow<UpdateStatus>`（**注意：`status` 与 `checkAndPrefetch` 只在具体 `Updater` 上，不在 `UpdateGateway` 接口上**）。
> - `UpdateGateway`：`check` / `cachedApk` / `downloadInForeground` / `observeForegroundProgress` / `cancelForegroundDownload` / `download` / `install`。
> - 库 manifest 自动合并 6 权限 + `SystemForegroundService`(dataSync) + `FileProvider ${applicationId}.updater`；**消费者 App 不声明任何权限 / 服务 / provider**。
>
> 深入阅读：[06 行为与边界](06-behavior-and-edge-cases.md)、[08 故障排查](08-troubleshooting.md)、[11 测试与构建](11-testing-and-build.md)。

---

## ① 接到接入需求时的决策树

> 按顺序回答，**每个分支都有确定结论**。凡结论是「不要改」的，就照抄 §2 模板，不要自作主张。

### 决策 A — 版本方案（决定 `UpdaterConfig.versionResolver`）

| 观察到的 release 形态 | 结论 | 代码 |
|---|---|---|
| 资产名以 `-<纯数字>.apk` 结尾，且该数字 = 构建时的 `versionCode`（如 `app-v1.9.3-1009003.apk`） | 用默认 `AssetNameSuffix()`，**可不传** `versionResolver` | 省略 |
| 只有语义化 tag（`v1.9.3`），且能映射成 `major*1_000_000 + minor*1_000 + patch`（每字段 0..999） | `TagScheme()` | `ReleaseVersionResolver.TagScheme()` |
| 两种都可能出现，或「资产名优先、tag 回退」 | **`Or`**（最稳，venera-native 迁移用这个） | `ReleaseVersionResolver.Or(AssetNameSuffix(), TagScheme())` |
| 都不符合（自定义版本串 / 时间戳 / JSON 清单 / 别的分隔符） | 转「决策 B：自定义 resolver」 | 见下 |

**铁律**：resolver 返回的是**可比较、单调递增的 `Long`**；`ReleaseSelection.isUpdate()` 只在 `available > installed` 时判为有更新（相等/更小/解析为 null → `UpToDate` / `NoInstallableAsset`）。解析失败一律返回 `null`，**不要抛异常、不要臆造版本号、不要把 `versionName` 当 `versionCode`**。

### 决策 B — 是否自定义 resolver

- **不需要**：命中决策 A 的任一内置方案 → 不写自定义代码。
- **需要**：内置三种都解析不出你的 release → 实现 `fun interface ReleaseVersionResolver`（或用 SAM lambda）：

```kotlin
import dev.cuculus.update.ReleaseVersionResolver

// 方式一：SAM lambda（fun interface 支持）
val resolver = ReleaseVersionResolver { releaseTag, apkAssetName ->
    Regex("""build-(\d+)\.apk""").find(apkAssetName)
        ?.groupValues?.get(1)?.toLongOrNull()
    // 解析不出就返回 null，交给 SDK 得到 NoInstallableAsset
}

// 方式二：具名类
class BuildNumberResolver : ReleaseVersionResolver {
    override fun versionCodeOf(releaseTag: String, apkAssetName: String): Long? =
        Regex("""build-(\d+)\.apk""").find(apkAssetName)?.groupValues?.get(1)?.toLongOrNull()
}
```

把它填进 `UpdaterConfig(versionResolver = resolver)`。**自定义后仍要满足决策 A 的铁律**（单调 `Long`、失败返回 null）。

### 决策 C — 是否多 scope

- **单更新源**（绝大多数）→ 用默认 `scope = "self_update"`，**不传**该字段。
- **一个 App 多个更新源**（主包 + 插件、两个仓库）→ 每个源配不同 `scope`，各自 `Updater.create`、各自收集 `status`、各自点红点。**下面两行必须写在 `Application.onCreate()` 内**（与 §2.3 `App.kt` 同一位置），`this` 指 `Application`；用 `applicationContext` 更明确：
  ```kotlin
  // 在 Application.onCreate() 内：
  val mainUpdater   = Updater.create(applicationContext, config.copy(scope = "main"))
  val pluginUpdater = Updater.create(applicationContext, config.copy(scope = "plugin"))
  ```
- **铁律**：`scope` 派生缓存目录 / SharedPreferences / unique work / 通知渠道 / 通知 id；必须是合法文件名片段，**不要含 `/`、`\`、空格**。（`config` 是 `data class`，可用 `.copy()`。）

### 决策 D — 是否已有 FileProvider

- **App 已有自己的 FileProvider（不同 authority）** → **不要改** `fileProviderAuthority`，照用 SDK 默认的 `${applicationId}.updater`。两个不同 authority 的 provider 可共存，互不冲突。
- **App 想用自己的 authority / 把 APK 放到自己的路径** → 三处必须**同步**改，缺一不可：
  1. `UpdaterConfig.fileProviderAuthority = "<你的 authority>"`；
  2. app manifest 用 `tools:node="replace"` 覆盖库的 `<provider>`，声明相同 authority；
  3. 提供 `file_paths` 暴露 `cacheDir/updater/` 子树（对齐 `updaterCacheDir()`）。
  → **默认强烈建议不要走这条**，照抄模板即可。
- 判断方法：`grep -n 'FileProvider\|authorities' app/src/main/AndroidManifest.xml`。

### 决策 E — 是否已有 WorkManager

- **App 已用 WorkManager（任意版本）** → **无需任何额外配置**。SDK 依赖 `androidx.work:work-runtime-ktx:2.12.0`，Gradle 自动做版本对齐；SDK 用默认 `InitializationProvider` + `WorkManager.getInstance(context)`。
- **App 自定义了 WorkManager**（移除默认 initializer / on-demand `Configuration.Provider`）→ 确保下载前 WorkManager 已初始化；unique work 名 `"<scope>.download"` 带 scope，不会与 App 其他 work 冲突。
- **铁律**：不要在 app manifest 再声明 `SystemForegroundService`（库已用 `tools:node="merge"` 合并）；不要试图改 `ExistingWorkPolicy`（SDK 固定 `REPLACE`）；不要重复声明 6 个权限。

### 决策 F — 是否有共享 `OkHttpClient` / 代理 / VPN / 内网 DNS

- **有** → 通过 `Updater.create(context, config, httpClient = sharedClient)` 传入；`Updater.create` 会把它发布给 `WorkerHttp`，让前台 worker 也复用（含代理 / DNS 配置）。
- **无** → 留 `null`（`httpClient` 有默认值），SDK 自建。
- **铁律**：只要 App 的网络有代理 / VPN / 自定义 DNS，**必须传**；且 `create` 要在 `Application.onCreate` 尽早调用（进程被杀后 worker 先恢复时，未 `create` 会回退裸 client，见 [06 §8](06-behavior-and-edge-cases.md)）。

### 决策 G — `POST_NOTIFICATIONS`（API 33+）

- 库只**声明**该权限，**授予由 App 运行时申请**。未授权时下载照常、仅通知隐藏。
- **不要**在 app manifest 重复声明；在合适时机用 `registerForActivityResult(ActivityResultContracts.RequestPermission())` 申请一次（代码见 §2.6）。

---

## ② 可直接复制的完整接入代码模板

> 依赖坐标：`dev.cuculus:cuculus-update:1.1.1`；`compileSdk 37` / `minSdk 26` / JDK 17；AGP 9.3.1 内置 Kotlin（无需单独 Kotlin 插件）。以下 5 个文件覆盖「检查 / 下载 / 安装 / 取消 / 启动预取 + 红点」全链路。

### 2.1 Gradle

```kotlin
// settings.gradle.kts —— 仓库声明（演练本地发布时才需要 mavenLocal()）
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()   // 仅本地 publishToMavenLocal 演练时加；正式用远端删掉
    }
}
```

```kotlin
// app/build.gradle.kts
plugins { id("com.android.application") }   // AGP 9 内置 Kotlin，不用另加 Kotlin 插件

android {
    namespace = "com.example.app"
    compileSdk = 37                          // 必须 >= 37（AAR 的 minCompileSdk）
    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 26                          // 必须与 SDK 一致（SDK minSdk 26）
        targetSdk = 37
        versionCode = 1009003                // ★ 这就是后面要传的 installedVersionCode
        versionName = "1.9.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }    // 为了 BuildConfig.VERSION_CODE
}

dependencies {
    implementation("dev.cuculus:cuculus-update:1.1.1")
    // SDK 的 api(...) 只带出 okhttp / kotlinx-coroutines-core；androidx.core 用的是
    // implementation(...)，不会传递给消费方，必须自己声明。后续代码块实际会用到：
    implementation("androidx.appcompat:appcompat:<your-version>")       // §2.5 的 AppCompatActivity
    implementation("androidx.core:core-ktx:<your-version>")             // PackageInfoCompat、androidx.core.view.isVisible
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:<your-version>")
    implementation("androidx.activity:activity-ktx:<your-version>")
    implementation("androidx.fragment:fragment-ktx:<your-version>") // 若用 Fragment
}
```

### 2.2 AndroidManifest（**最小** —— 不写任何权限 / 服务 / provider）

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".App" android:theme="@style/Theme.App">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> 6 权限、`SystemForegroundService`、`FileProvider ${applicationId}.updater` 全部由库合并进来。**唯一要写的是 `android:name=".App"`**（指向 §2.3 的 Application）。

### 2.3 Application 单例 + 启动预取（`App.kt`）

```kotlin
package com.example.app

import android.app.Application
import androidx.core.content.pm.PackageInfoCompat
import dev.cuculus.update.ReleaseVersionResolver
import dev.cuculus.update.Updater
import dev.cuculus.update.UpdaterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        updater = Updater.create(
            context = this,
            config = UpdaterConfig(
                owner = "your-org",                                // ★ 发布「本 App APK」的 GitHub owner / org
                repository = "your-app",                           // ★ 发布「本 App APK」的仓库，不是 SDK 仓库、不是别的 App
                fileProviderAuthority = "$packageName.updater",    // ★ 必须等于 ${applicationId}.updater
                versionResolver = ReleaseVersionResolver.Or(        // 决策 A
                    ReleaseVersionResolver.AssetNameSuffix(),       // 资产名尾部数字优先
                    ReleaseVersionResolver.TagScheme(),             // tag 回退
                ),
                // scope = "self_update",         // 单源用默认，不传
                // strings = UpdaterStrings(),    // 需要改通知文案才传
            ),
            httpClient = null,   // 决策 F：有共享 client / 代理就传，否则留 null
        )

        // 启动检查 + 静默预下载：永不抛异常（除取消外），失败只让红点亮着
        appScope.launch { updater.checkAndPrefetch(installedVersionCode()) }
    }

    private fun installedVersionCode(): Long = runCatching {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        PackageInfoCompat.getLongVersionCode(info)
    }.getOrDefault(0L)
}
```

> 要点：
> - `fileProviderAuthority` 用运行期 `packageName`（= `applicationId`）；若你用了 `applicationIdSuffix`，manifest 占位符 `${applicationId}` 与运行期 `packageName` 都会是最终 applicationId，仍一致。
> - `httpClient` 传入后，`Updater.create` 会设置 `WorkerHttp.client`，前台 worker 复用同一代理 / DNS 配置。
> - `checkAndPrefetch` 与 §2.4 的 `status` 收集配套：预取结果通过 `status` 回放给后创建的 ViewModel。
> - 若不想在 Application 起协程，可把这一行 `checkAndPrefetch` 移到 §2.4 `UpdateViewModel` 的 `init` 里（**二选一，不要两处都调**）。

### 2.4 ViewModel：检查 / 下载 / 安装 / 取消 / 红点（`UpdateViewModel.kt`）

```kotlin
package com.example.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cuculus.update.InstallResult
import dev.cuculus.update.UpdateCheck
import dev.cuculus.update.UpdateStatus
import dev.cuculus.update.Updater
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdateViewModel(
    private val updater: Updater,             // 具体类型：需要 status / checkAndPrefetch
    private val installedVersionCode: Long,  // = BuildConfig.VERSION_CODE.toLong()
) : ViewModel() {

    enum class Phase { IDLE, CHECKING, DOWNLOADING, READY_TO_INSTALL, INSTALLING, LAUNCHED }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val versionCode: Long? = null,        // 发现的新版本
        val progress: Float? = null,          // 0f..1f，null 表示未知
        val updateAvailable: Boolean = false, // ★ 红点
        val releaseNotes: String? = null,
        val message: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var availableAsset: dev.cuculus.update.ReleaseAsset? = null
    private var apkToInstall: File? = null
    private var downloadJob: Job? = null

    init {
        // 红点 + 预取命中：只认 Updater.status（UpdateStatus 由 checkAndPrefetch 驱动）
        viewModelScope.launch {
            updater.status.collect { status ->
                when (status) {
                    UpdateStatus.Idle ->
                        _ui.update { it.copy(updateAvailable = false) }
                    is UpdateStatus.UpdateAvailable ->
                        _ui.update { it.copy(updateAvailable = true, versionCode = status.versionCode) }
                    is UpdateStatus.Downloaded -> {
                        apkToInstall = status.apkFile
                        _ui.update {
                            it.copy(
                                updateAvailable = true,
                                versionCode = status.versionCode,
                                phase = Phase.READY_TO_INSTALL,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 手动「检查更新」 */
    fun check() {
        if (downloadJob?.isActive == true) return
        _ui.update { it.copy(phase = Phase.CHECKING, message = null) }
        viewModelScope.launch {
            val check = try {
                updater.check(installedVersionCode)              // suspend，放到协程里
            } catch (e: CancellationException) {
                throw e                                          // 不要吞掉取消
            } catch (e: Exception) {
                _ui.update { it.copy(phase = Phase.IDLE, message = "检查失败：${e.message}") }
                return@launch
            }
            when (check) {
                is UpdateCheck.Available -> {
                    availableAsset = check.asset
                    _ui.update {
                        it.copy(
                            phase = Phase.IDLE,
                            updateAvailable = true,
                            versionCode = check.versionCode,
                            releaseNotes = check.release.notes?.trim(),
                        )
                    }
                }
                is UpdateCheck.UpToDate -> {
                    availableAsset = check.asset  // 调试用：同版本也能强制重下
                    _ui.update {
                        it.copy(phase = Phase.IDLE, updateAvailable = false,
                            versionCode = null, message = "已是最新版本")
                    }
                }
                UpdateCheck.NoInstallableAsset ->
                    _ui.update { it.copy(phase = Phase.IDLE, message = "该发布没有可安装的 APK") }
            }
        }
    }

    /** 「下载 / 立即更新」：已有缓存就用缓存，否则前台下载 + 进度 */
    fun download() {
        if (downloadJob?.isActive == true) return
        val versionCode = _ui.value.versionCode
        _ui.update { it.copy(phase = Phase.DOWNLOADING, progress = null, message = null) }
        downloadJob = viewModelScope.launch {
            val already = apkToInstall?.takeIf { it.isFile }
                ?: updater.cachedApk(versionCode ?: -1L)
            val apk: File = already ?: run {
                // 进度观察与下载协程并行；下载结束后取消观察
                val progressJob = launch {
                    updater.observeForegroundProgress().collect { fraction ->
                        _ui.update { it.copy(progress = fraction) }
                    }
                }
                val asset = availableAsset
                if (asset == null) {
                    progressJob.cancel()
                    _ui.update { it.copy(phase = Phase.IDLE, message = "请先检查更新") }
                    return@launch
                }
                try {
                    val file = updater.downloadInForeground(asset)  // suspend，抛 DownloadException
                    progressJob.cancel()
                    file
                } catch (e: CancellationException) {
                    progressJob.cancel()
                    throw e
                } catch (e: Exception) {
                    progressJob.cancel()
                    _ui.update {
                        it.copy(phase = Phase.IDLE, progress = null, message = "下载失败：${e.message}")
                    }
                    return@launch
                }
            }
            apkToInstall = apk
            _ui.update { it.copy(phase = Phase.READY_TO_INSTALL, progress = 1f) }
        }
    }

    /** 「安装」：只代表拉起系统安装器，没有结果回调 */
    fun install() {
        val apk = apkToInstall ?: return
        if (!apk.isFile) {
            _ui.update { it.copy(message = "安装包已失效，请重新下载") }
            return
        }
        _ui.update { it.copy(phase = Phase.INSTALLING) }
        when (val result = updater.install(apk)) {
            InstallResult.Launched ->
                _ui.update { it.copy(phase = Phase.LAUNCHED, message = "请在系统弹窗中确认安装") }
            is InstallResult.Failure ->
                _ui.update {
                    it.copy(phase = Phase.READY_TO_INSTALL, message = "无法拉起安装器：${result.message}")
                }
        }
    }

    /** 「取消」：取消 worker + 本地等待/观察协程，复位 UI */
    fun cancel() {
        updater.cancelForegroundDownload()
        downloadJob?.cancel()
        downloadJob = null
        _ui.update { it.copy(phase = Phase.IDLE, progress = null) }
    }
}
```

> **可测试性说明**：`Updater.status` 与 `checkAndPrefetch` **只在具体 `Updater` 上**（`UpdateGateway` 接口没有它们）。若要对 ViewModel 做 JVM 单测，把构造改为
> `UpdateViewModel(gateway: UpdateGateway, status: StateFlow<UpdateStatus>, prefetch: suspend () -> Unit, installedVersionCode: Long)`，
> 工厂里传 `app.updater`（它同时实现 `UpdateGateway`）、`app.updater.status`、`{ app.updater.checkAndPrefetch(code) }`。

### 2.5 与 UI 的连接（`MainActivity.kt`，要点片段）

```kotlin
package com.example.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.app.UpdateViewModel.Phase
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: UpdateViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(
                    updater = (application as App).updater,
                    installedVersionCode = BuildConfig.VERSION_CODE.toLong(),  // ★ 不是 versionName
                ) as T
        }
    }

    // API 33+ 通知权限：运行时申请（库只声明、不申请）
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted 与否都可行 */ }

    // 最小 layout：res/layout/activity_main.xml，需含以下 id（类型见下方声明）：
    // R.id.badge(View)、R.id.progress_bar(ProgressBar)、R.id.check_button(Button)、
    // R.id.download_button(Button)、R.id.install_button(Button)、R.id.cancel_button(Button)、
    // R.id.status_text(TextView)。
    private lateinit var badge: View
    private lateinit var progressBar: ProgressBar
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button
    private lateinit var cancelButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        badge = findViewById(R.id.badge)
        progressBar = findViewById(R.id.progress_bar)
        checkButton = findViewById(R.id.check_button)
        downloadButton = findViewById(R.id.download_button)
        installButton = findViewById(R.id.install_button)
        cancelButton = findViewById(R.id.cancel_button)
        statusText = findViewById(R.id.status_text)
        if (Build.VERSION.SDK_INT >= 33) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { state ->
                    badge.isVisible = state.updateAvailable          // 红点
                    progressBar.progress = ((state.progress ?: 0f) * 100).toInt()
                    downloadButton.isEnabled = state.phase == Phase.IDLE || state.phase == Phase.READY_TO_INSTALL
                    installButton.isEnabled = state.phase == Phase.READY_TO_INSTALL
                    statusText.text = state.message ?: ""
                }
            }
        }
        checkButton.setOnClickListener { viewModel.check() }
        downloadButton.setOnClickListener { viewModel.download() }
        installButton.setOnClickListener { viewModel.install() }
        cancelButton.setOnClickListener { viewModel.cancel() }
    }
}
```

### 2.6 自定义文案（可选）

```kotlin
import dev.cuculus.update.UpdaterConfig
import dev.cuculus.update.UpdaterStrings

UpdaterConfig(
    owner = "your-org",
    repository = "your-app",              // ★ 发布「本 App APK」的仓库，不要照抄示例值
    fileProviderAuthority = "$packageName.updater",
    strings = UpdaterStrings(
        channelName = "版本更新",
        channelDescription = "下载新版本安装包",
        downloadTitle = "正在下载更新",
        progressFormat = "已下载 %d%%",   // String.format 模式，接收整数百分比
    ),
)
```

---

## ③ 明确禁止事项（违反即接入失败或运行期异常）

1. **不要在 app manifest 重复声明 6 个权限、`<provider>` 或 `SystemForegroundService`**。库已用 `tools:node="merge"` 合并；重复 provider authority 会冲突（`INSTALL_FAILED_CONFLICTING_PROVIDER`）。
2. **不要改 `fileProviderAuthority` 默认值，除非同步改 manifest**。它必须等于 `${applicationId}.updater`（用 `"$packageName.updater"`）；改了不同步 → `getUriForFile` 失败、安装拉不起（决策 D）。
3. **不要在主线程调用 `suspend` 函数**。`check` / `downloadInForeground` / `download` / `checkAndPrefetch` 全是 `suspend`，必须在 `viewModelScope` / `lifecycleScope` / 应用级 `CoroutineScope` 里调用。
4. **不要假设 `install()` 有结果回调**。`InstallResult.Launched` 只代表系统安装器被拉起；用户确认/取消/安装成功/失败都不会回调给 App。
5. **不要硬编码 `versionCode`**。传给 `check` / `checkAndPrefetch` / `installedVersionCode` 的必须是运行期真实值：`BuildConfig.VERSION_CODE.toLong()` 或 `PackageInfoCompat.getLongVersionCode(...)`；**绝不能用 `versionName`**。
6. **不要以为 `UpdateGateway` 上有 `status` / `checkAndPrefetch`**——它们在具体 `Updater` 上；用接口就拿不到红点状态。
7. **不要吞掉 `CancellationException`**。`catch (e: Exception)` 里遇到它要 `throw e`，否则取消/协程结构化并发被破坏。
8. **不要假设 `downloadInForeground()` 完成后 `status` 会变 `Downloaded` 或 `cachedApk()` 能命中**。前台 worker **不写** `UpdateStateStore`；只有 `checkAndPrefetch()` 会 `saveDownloadedApk`。前台下载要用它**返回的 `File`**（模板里存进 `apkToInstall`）。
9. **不要手动删除 `.part` 文件**，不要把它当失败。它是续传锚点（[06 §1](06-behavior-and-edge-cases.md)）。
10. **不要重复声明 / 改写 `SystemForegroundService`，不要改 `ExistingWorkPolicy`**（SDK 固定 `REPLACE`），不要在 app manifest 里手写 `foregroundServiceType`。
11. **不要在同一时刻让 inline `download()` 与 `checkAndPrefetch()` 对同一 asset 并发下载**。用单个 `downloadJob` 串行化（模板已做）；`check()` 可并发，但以最后一次渲染为准（[06 §12](06-behavior-and-edge-cases.md)）。
12. **不要用 `checkAndPrefetch()` 的异常驱动 UI 错误**——它除取消外**永不抛**，失败只把状态留成 `UpdateAvailable`（红点亮）。UI 错误来自 `check()` / `downloadInForeground()`。
13. **不要假设 `REQUEST_INSTALL_PACKAGES` 需要运行时申请**（它是 normal 权限），也不要假设 `POST_NOTIFICATIONS` 被拒绝会导致下载失败（只是隐藏通知）。
14. **不要覆盖 / 删除 `consumer-rules.pro` 的 worker keep 规则**，否则 release（R8）包前台下载崩溃（[08 §7](08-troubleshooting.md)）。
15. **不要假设 SDK 内置 UI**。角标、对话框、进度条全部由接入方自己画；SDK 只给 `status` / `UpdateProgress` / `Flow<Float?>` / `InstallResult`。
16. **不要改 `updaterCacheDir()` 而不改 `updater_file_paths.xml`**，否则 FileProvider 暴露不到 APK（AGENTS.md 不变量 2）。
17. **不要每次用到都 `new` 一个 `Updater`**。每个进程、每个 scope 应只有一个 `Updater` 单例（放 Application），以便 `WorkerHttp` 与 `status` 一致。
18. **不要在 `Activity` 的属性初始化器里调用 `Updater.create(this, ...)`，也不要在那里求值 `"$packageName.updater"`**。Activity 构造时 base Context 尚未 attach，`getPackageName()` / `getApplicationContext()` 抛 NPE → **App 一启动就崩**，且在 `onCreate` 之前，日志栈顶落在 `<init>`。唯一正确位置是 `Application.onCreate()` 里创建单例（§2.3 `App.kt`），最晚 `Activity.onCreate()` 内用 `applicationContext`。这是本 SDK 最容易被大模型写错的一处；照抄 §2 模板即可避开。
19. **不要把 `repository` 指向别的 App 的仓库或 SDK 自己的源码仓库**。`check(installedVersionCode)` 比较的是「已安装 App 的 versionCode」与「该 release 资产名解出的 code」，返回的 asset 就是要装进本 App 的 APK。指向别的 App 会让比较失去意义，且因 applicationId / 签名不同导致安装必然失败。`repository` 只能是**发布本 App APK** 的那个仓库。
20. **不要把更新发成 prerelease 却指望 `check()` 能查到**。SDK 调 GitHub `/releases/latest`，该接口**排除 draft 与 prerelease**；只有 prerelease 的仓库会抛 `ReleaseApiException(404, "no published release")`。演示自更新时，云端「新版本」必须是普通 release（[05 篇](05-release-contract.md)、[08 §1](08-troubleshooting.md)）。
21. **不要把「下载」按钮的可用性和 `UpdateCheck.Available` 绑定**。`check()` 的语义是「要不要亮更新红点」，不是「能不能下载」：`UpToDate(release, asset)` 本身就带 asset，`NoInstallableAsset` 也可能只是版本解析失败而实际仍有 APK。需要「随时可下载 / 强制重装 / Demo 反复测试」时，用具体 `Updater` 上的 `latestInstallableAsset()`（不做版本比较、不解析 versionCode），再交给 `download()` / `downloadInForeground()`（按场景选，见禁止事项 22）；`check()` 继续只负责展示判定（[03 §1.4](03-api-reference.md)）。本仓库 `:sample` 就是这个写法。
22. **不要用 `downloadInForeground()` 给一个只在当前页面显示进度的 Demo / 调试页提供进度**。前台链路依赖 FGS 能否启动、`POST_NOTIFICATIONS` 与 WorkManager 的进度持久化；任一环节不满足就会表现为「进度一直是 0」。这类页面直接在 `Dispatchers.IO` 上调 `download(asset, onProgress)`（回调里切回主线程），拿真实字节数画进度条；`downloadInForeground()` 只用于「离开页面 / 锁屏仍要继续下载」的场景（[03 §12.3](03-api-reference.md)、[08 §2](08-troubleshooting.md)）。本仓库 `:sample` 就是这个写法。
23. **不要在 UI 侧给下载协程设短超时（`withTimeout(30_000)`）来「防卡死」**。超时取消会丢掉续传点：分段路径取消时 `.part` 会被删（预分配文件带空洞，长度不可信），于是每次超时重试都从 0 开始，越重试越像卡死。SDK 自己会在 15s 无字节时识别停滞并从末位续传（[03 §2.5](03-api-reference.md)）；要做的是**保存 `Job` 并给用户一个取消按钮**，而不是让 UI 猜什么时候该杀。
24. **不要把「下载中」的按钮 `isEnabled = false`**。传输期间没有任何出口的话，一旦链路真的卡住，用户只能杀进程——这正是「卡在 80% 再也无法恢复」的另一半原因。按钮应做成「下载 / 取消」双态，取消后 `progressBar` 归零即可重来（[08 §2.2](08-troubleshooting.md)）。

---

## ④ 验收清单（逐条可验证，接入完成必须全绿）

| # | 验证项 | 怎么验证 | 通过 |
|---|---|---|---|
| 1 | 依赖与编译 | `sh gradlew :sample:assembleDebug --console=plain` 及消费方 app `assembleDebug` 成功；无重复类 / authority 冲突 | ☐ |
| 2 | 合并清单 | app merged manifest 含 6 权限 + `SystemForegroundService`(dataSync) + `${applicationId}.updater` provider，且 **authority 唯一**；app 自身 manifest 无这些条目 | ☐ |
| 3 | `fileProviderAuthority` 一致 | `UpdaterConfig.fileProviderAuthority == "$packageName.updater"` 且 == manifest 的 `${applicationId}.updater` | ☐ |
| 4 | `installedVersionCode` 正确 | 传入的是 `BuildConfig.VERSION_CODE.toLong()` / `PackageInfoCompat`，与 `adb shell dumpsys package <pkg> \| grep versionCode` 一致 | ☐ |
| 5 | 红点 | 造一个 `versionCode` 更大的 **普通 release（非 prerelease）**，启动 App → `status` 变 `UpdateAvailable`/`Downloaded` → 角标出现 | ☐ |
| 6 | 静默预下载 | 预取成功后 `Downloaded.apkFile` 存在且头 4 字节为 `50 4b 03 04`；杀进程重进 `cachedApk()` 命中、不重复下载 | ☐ |
| 7 | 手动检查 | 点「检查更新」得到 `Available(release, asset, versionCode)`；无更新得 `UpToDate`；无 APK 得 `NoInstallableAsset` | ☐ |
| 8 | 点击更新不重复下载 | 已 `Downloaded`/`cachedApk()` 命中时直接 `install()`，不二次下载；无缓存时只启动一个前台 worker（`adb shell dumpsys jobscheduler`） | ☐ |
| 9 | 前台进度 | `observeForegroundProgress()` 从 0 递增到 1；通知渠道 `"<scope>.updates"` 显示进度（授权后） | ☐ |
| 10 | 安装拉起 | `install()` 返回 `InstallResult.Launched`，系统安装弹窗出现，用户确认后装上 | ☐ |
| 11 | 断点续传 | 下载中断网 / 杀进程，恢复后 `.part` 续传（`Range: bytes=N-`），最终 APK 完整 | ☐ |
| 12 | 取消 | 取消后进度停止、worker 结束、UI 复位、可重新下载、`.part` 保留 | ☐ |
| 13 | R8 release | `assembleRelease`（minify）包前台下载不崩（worker keep 生效） | ☐ |
| 14 | API 矩阵 | API 26 / 33 / 34+ 真机各跑一遍下载 + 安装；33+ 验证通知权限两种状态 | ☐ |
| 15 | 异常分流 | 断网 / 404 / 403 时 UI 显示对应文案而非崩溃；`CancellationException` 不外泄 | ☐ |

本地演练与真机验证的具体命令见 [11 篇](11-testing-and-build.md)。

---

## ⑤ 常见误判与纠正

| 误判 | 纠正 |
|---|---|
| 「`status` / `checkAndPrefetch` 在 `UpdateGateway` 上」 | 在**具体 `Updater`** 上；`UpdateGateway` 只有 7 个动作方法。要红点就持有 `Updater`（或把 `StateFlow<UpdateStatus>` 注入 VM）。 |
| 「`install()` 返回 `Launched` = 安装完成」 | 只代表拉起系统安装器；**无任何结果回调**，用户可能取消。UI 要提示「请在系统弹窗确认」并允许重试。 |
| 「`checkAndPrefetch` 失败会抛异常」 | 除 `CancellationException` 外**永不抛**；失败仍把 `status` 置 `UpdateAvailable`（红点亮），用户可手动重试。 |
| 「前台下载完成后 `cachedApk()` 能命中 / `status` 变 `Downloaded`」 | worker **不写** `UpdateStateStore`；用 `downloadInForeground()` 返回的 `File`。`saveDownloadedApk` 只在 `checkAndPrefetch()` 里发生。 |
| 「`NoInstallableAsset` = 网络错误」 | 是「无 `.apk` 资产」或「resolver 解析不出 versionCode」；网络错误会抛 `ReleaseApiException` / `DownloadException`（[08 §1](08-troubleshooting.md)）。 |
| 「`POST_NOTIFICATIONS` 被拒绝 → 下载失败」 | 下载照常完成，只是通知隐藏；不要因此禁用下载（[06 §6](06-behavior-and-edge-cases.md)）。 |
| 「`UpToDate` 里没有 asset」 | 有：`UpToDate(release, asset)`；可用于调试时强制重下同版本 APK。 |
| 「`versionName`（`1.9.3`）可以当 `installedVersionCode`」 | 必须传 `Long` 型 `versionCode`；用 `BuildConfig.VERSION_CODE.toLong()`。 |
| 「`repository` 填个有 release 的仓库就行，比如 SDK 仓库或参考项目」 | 必须填**发布本 App APK** 的仓库。填别的 App：比较的是别人的 versionCode，下载的是别人的 APK，装不上（applicationId / 签名不同）。本仓库 `:sample` 指向 `lwtor/cuculus-update` 是因为 demo 的 APK 就发在这里。 |
| 「prerelease 也算 latest release」 | GitHub `/releases/latest` **排除 draft 与 prerelease**；只有 prerelease 时 `check()` 抛 404 `no published release`。 |
| 「需要自己在 manifest 写权限 / provider」 | 库全部合并；app manifest 只写 `<application android:name=".App">`。 |
| 「`REQUEST_INSTALL_PACKAGES` 要运行时申请」 | normal 权限，无运行时弹窗；用户仍逐次确认安装。 |
| 「相等版本也应触发更新」 | `isUpdate` 要求**严格大于**；相等 → `UpToDate`（[08 §12](08-troubleshooting.md)）。 |
| 「`fileProviderAuthority` 随便填个字符串」 | 必须等于 `${applicationId}.updater`；否则 `InstallResult.Failure("unable to expose the APK...")`。 |
| 「每次用的时候 `Updater.create` 一下就行」 | 应做**进程级单例**（每 scope 一个），放 Application；否则 `WorkerHttp` / `status` 不一致。 |
| 「SDK 自带更新弹窗 / 进度条」 | 不内置 UI；红点、对话框、进度条由接入方按 §2.4/§2.5 自己画。 |
| 「改缓存目录只改一处就行」 | `updaterCacheDir()` 与 `updater_file_paths.xml` 必须一致，且 `Updater` 与 worker 都经它解析（[06 §2](06-behavior-and-edge-cases.md)）。 |

---

## ⑥ 两条路径

### 路径一：最小改动（只想「启动检查 + 红点 + 点一下安装」）

1. §2.1 加依赖 + `buildConfig = true`。
2. §2.2 app manifest 只加 `android:name=".App"`，**不写任何权限 / provider / service**。
3. §2.3 `App.kt`：`Updater.create(...)` 单例 + `appScope.launch { updater.checkAndPrefetch(installedVersionCode()) }`。
4. 收集 `updater.status`：`UpdateAvailable` / `Downloaded` → 点亮红点；`Downloaded(versionCode, apkFile)` 把 `apkFile` 存起来。
5. 更新入口（如「关于」页按钮）：直接 `updater.install(apkFile)`，按 `InstallResult` 提示「请在系统弹窗确认」。
   - 不做手动 `check()`、不做前台下载、不做进度条、不申请通知权限、不自定义 resolver（用 `Or(AssetNameSuffix(), TagScheme())` 兜底）。

> 这条路径下预下载由 `checkAndPrefetch` 完成（inline 下载，无前台通知）；适合快速接入 / 内网工具 App。

### 路径二：完整集成（生产级，推荐）

在路径一基础上按顺序加：

1. §2.4 `UpdateViewModel`：手动 `check()`、`download()`、`install()`、`cancel()`，`downloadJob` 串行化。
2. §2.4 的 `init` 收集 `updater.status` 驱动红点；`download()` 里并行 `observeForegroundProgress()` 观察进度。
3. §2.5 UI：按钮按 `Phase` 启用/禁用、进度条、`Badge` 红点、错误文案。
4. §2.5 `POST_NOTIFICATIONS`：API 33+ 用 `registerForActivityResult` 运行时申请（不在 manifest 重复声明）。
5. 按决策 A/B 选 `versionResolver`；按决策 C 配 `scope`；按决策 F 传共享 `OkHttpClient`（有代理/VPN 必传）。
6. 需要改通知文案 → §2.6 `UpdaterStrings`。
7. 本地演练：`sh gradlew :updater:publishToMavenLocal` → 消费方 `mavenLocal()` + `implementation("dev.cuculus:cuculus-update:1.1.1")` → 检查 merged manifest（[11 篇](11-testing-and-build.md)）。
8. 真机验证：走完 §4 验收清单 15 项（含断点续传、取消、R8 release、API 26/33/34+ 矩阵）。
9. 若是 venera-native 迁移：读 [07 篇](07-migration.md)，**记住旧 `data/update` 验证通过后才删**。

> 两条路径都遵守 §3 的全部禁止事项；拿不准的现象查 [08 篇](08-troubleshooting.md)，边界行为查 [06 篇](06-behavior-and-edge-cases.md)。
