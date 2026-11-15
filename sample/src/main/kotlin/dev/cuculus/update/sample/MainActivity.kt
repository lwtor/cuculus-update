package dev.cuculus.update.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import dev.cuculus.update.InstallResult
import dev.cuculus.update.UpdateCheck
import dev.cuculus.update.UpdateProgress
import dev.cuculus.update.UpdateStatus
import dev.cuculus.update.Updater
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole integration: the [Updater] is built once in [SampleApp] from an attached Application
 * context, then this screen drives check / download / install from the UI. No permission, no
 * FileProvider, no service declaration lives in this app — the library manifest supplies all of it.
 *
 * Do NOT create the Updater in a property initializer here: an Activity has no base Context until
 * attachBaseContext() runs, so getPackageName() and getApplicationContext() throw there and the
 * app would crash before onCreate().
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val updater: Updater
        get() = (application as SampleApp).updater

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button

    private var downloadedApk: File? = null

    /** The in-flight transfer, so the download button can cancel it instead of locking the UI. */
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root_scroll))
        requestNotificationPermissionIfNeeded()

        val versionText: TextView = findViewById(R.id.version_text)
        statusText = findViewById(R.id.status_text)
        logText = findViewById(R.id.log_text)
        progressBar = findViewById(R.id.progress_bar)
        downloadButton = findViewById(R.id.download_button)
        installButton = findViewById(R.id.install_button)

        versionText.text = getString(
            R.string.version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        findViewById<Button>(R.id.check_button).setOnClickListener { checkForUpdate() }
        downloadButton.setOnClickListener { onDownloadButtonClick() }
        installButton.setOnClickListener { installUpdate() }

        // The SDK's own state, kept in sync by checkAndPrefetch() in a production app.
        scope.launch {
            updater.status.collect { status ->
                when (status) {
                    UpdateStatus.Idle -> Unit
                    is UpdateStatus.UpdateAvailable -> showStatus("发现新版本 code=${status.versionCode}")
                    is UpdateStatus.Downloaded -> {
                        downloadedApk = status.apkFile
                        installButton.isEnabled = true
                        showStatus("已下载 code=${status.versionCode}：${status.apkFile.name}")
                    }
                }
            }
        }
    }

    /**
     * With targetSdk 37 the system forces edge-to-edge on Android 15+, and a system ActionBar
     * would overlay the first rows of content. The demo uses NoActionBar and draws its own title
     * inside the scrollable content, then pads the root for the status/navigation bars so nothing
     * is covered by the system UI. On older platforms the insets are already consumed by the
     * decor view and come back as zero, so this is a no-op there.
     *
     * The listener returns the insets unconsumed (matching the official edge-to-edge sample), so
     * no child of root_scroll may set android:fitsSystemWindows or its own insets listener — that
     * would apply the same insets twice. Padding is assigned absolutely from the saved baseline,
     * which keeps repeated dispatch (IME, rotation) idempotent.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                intArrayOf(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            } else {
                intArrayOf(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            view.setPadding(
                baseLeft + bars[0],
                baseTop + bars[1],
                baseRight + bars[2],
                baseBottom + bars[3],
            )
            insets
        }
        root.requestApplyInsets()
    }

    /**
     * The foreground download posts a progress notification. Granting POST_NOTIFICATIONS is the
     * app's job (the SDK declares the permission but cannot request it at runtime), so a demo that
     * really downloads and installs its own update has to ask for it on API 33+.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    /**
     * `check()` is only for *showing* the version comparison. It must never gate the download
     * button: a build that is already up to date (or whose release code cannot be decoded) still
     * has an APK available, and a test/demo app needs to exercise download + install repeatedly.
     */
    private fun checkForUpdate() {
        logText.text = ""
        showStatus("正在检查…")
        scope.launch {
            val check = try {
                updater.check(BuildConfig.VERSION_CODE.toLong())
            } catch (error: Exception) {
                showStatus("检查失败：${error.message}")
                logText.text = error.toString()
                return@launch
            }
            render(check)
        }
    }

    /**
     * No version gate, and no WorkManager/FGS dependency: stream the APK inline with real
     * byte-level progress. The foreground worker is a separate SDK capability (it keeps downloading
     * after the screen is left), but a demo must not depend on foreground-service permissions,
     * notification channels or WorkManager progress persistence just to show a working download.
     *
     * While a transfer is running this button doubles as "cancel". A download that looks frozen is
     * the worst possible state for a demo: even with the SDK's stall detection there is no way to
     * tell a slow CDN from a dead one without a way out, so the button toggles instead of locking.
     */
    private fun onDownloadButtonClick() {
        if (downloadJob?.isActive == true) {
            downloadJob?.cancel()
            showStatus("已取消下载")
            progressBar.progress = 0
            return
        }
        startDownload()
    }

    private fun startDownload() {
        showStatus("正在获取最新版本…")
        progressBar.progress = 0
        downloadButton.setText(R.string.cancel_download)
        downloadJob = scope.launch {
            // latestInstallableAsset() deliberately skips version comparison and versionCode
            // parsing, so any release carrying a .apk asset is downloadable.
            val asset = try {
                updater.latestInstallableAsset()
            } catch (error: Exception) {
                showStatus("获取最新版本失败：${error.message}")
                logText.text = error.toString()
                return@launch
            }
            if (asset == null) {
                showStatus("最新 Release 里没有 .apk 资产")
                return@launch
            }
            showStatus("开始下载 ${asset.name}（不校验版本号）")

            try {
                // The SDK fans a known-size asset out over several connections and reports progress
                // on a time interval, so the bar keeps moving even at 50 KB/s. 150 ms is the
                // display throttle; the rate is measured over the same window.
                val apk = withContext(Dispatchers.IO) {
                    var lastBytes = 0L
                    var lastSampledAt = System.currentTimeMillis()
                    var lastShownAt = 0L
                    updater.download(asset) { progress ->
                        val now = System.currentTimeMillis()
                        if (now - lastShownAt >= 150) {
                            val elapsed = (now - lastSampledAt).coerceAtLeast(1L)
                            val rate = (progress.bytesDownloaded - lastBytes) * 1000L / elapsed
                            lastBytes = progress.bytesDownloaded
                            lastSampledAt = now
                            lastShownAt = now
                            // onProgress runs on a download thread; hop to Main for the Views.
                            scope.launch { renderProgress(progress, rate) }
                        }
                    }
                }
                downloadedApk = apk
                installButton.isEnabled = true
                progressBar.progress = 100
                showStatus("下载完成：${apk.name}（${apk.length() / 1024} KB）")
                logText.text = buildString {
                    appendLine("asset.name = ${asset.name}")
                    appendLine("asset.sizeBytes = ${asset.sizeBytes}")
                    appendLine("asset.downloadUrl = ${asset.downloadUrl}")
                    appendLine()
                    appendLine("downloaded to = ${apk.absolutePath}")
                    appendLine("bytes = ${apk.length()}")
                }
            } catch (cancelled: CancellationException) {
                // Cancelling a transfer is a user action, not a failure: report it as such.
                progressBar.progress = 0
                showStatus("下载已取消")
                throw cancelled
            } catch (error: Exception) {
                showStatus("下载失败：${error.message}")
                logText.text = error.toString()
            } finally {
                downloadButton.setText(R.string.download)
            }
        }
    }

    /**
     * Shows the real transfer rate next to the byte count. Chasing "why is it slow" without a
     * number is guesswork: a percentage alone cannot distinguish a stalled connection from a
     * throttled CDN, and 0.4% of a 3.8 MB file is indistinguishable from no progress at all.
     */
    private fun renderProgress(progress: UpdateProgress, bytesPerSecond: Long) {
        val fraction = progress.fraction
        progressBar.progress = ((fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
        val percent = String.format(Locale.US, "%.1f", (fraction ?: 0f) * 100f)
        val done = formatBytes(progress.bytesDownloaded)
        val size = progress.totalBytes?.let { " / " + formatBytes(it) } ?: ""
        showStatus("下载中 $percent%   $done$size   ${formatBytes(bytesPerSecond)}/s")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun installUpdate() {
        val apk = downloadedApk ?: return
        when (val result = updater.install(apk)) {
            InstallResult.Launched -> showStatus("已交给系统安装器，请在系统弹窗中确认")
            is InstallResult.Failure -> showStatus("安装失败：${result.message}")
        }
    }

    private fun render(check: UpdateCheck) {
        val installed = BuildConfig.VERSION_CODE.toLong()
        when (check) {
            is UpdateCheck.Available ->
                showStatus("云端 code=${check.versionCode} > 本 App code=$installed（有更新）")
            is UpdateCheck.UpToDate ->
                showStatus("云端不比本 App code=$installed 新，但「下载」仍可用")
            UpdateCheck.NoInstallableAsset ->
                showStatus("check() 未解析出可安装 APK；「下载」仍会直接取最新 .apk")
        }
        logText.text = "installedVersionCode = $installed\n\n" + describe(check)
    }

    /** The last [UpdateCheck], printed in full so the release/asset pick is visible. */
    private fun describe(check: UpdateCheck): String = when (check) {
        is UpdateCheck.UpToDate -> """
            UpdateCheck.UpToDate
            release.tagName = ${check.release.tagName}
            release.name = ${check.release.name ?: "-"}
            release.publishedAt = ${check.release.publishedAt ?: "-"}
            asset.name = ${check.asset.name}
            asset.sizeBytes = ${check.asset.sizeBytes}
            asset.contentType = ${check.asset.contentType ?: "-"}
        """.trimIndent()

        UpdateCheck.NoInstallableAsset -> """
            UpdateCheck.NoInstallableAsset
            the latest release carries no asset this updater can install
        """.trimIndent()

        is UpdateCheck.Available -> """
            UpdateCheck.Available
            versionCode = ${check.versionCode}
            release.tagName = ${check.release.tagName}
            release.name = ${check.release.name ?: "-"}
            release.publishedAt = ${check.release.publishedAt ?: "-"}
            release.notes = ${check.release.notes?.trim() ?: "-"}
            asset.name = ${check.asset.name}
            asset.sizeBytes = ${check.asset.sizeBytes}
            asset.contentType = ${check.asset.contentType ?: "-"}
            asset.downloadUrl = ${check.asset.downloadUrl}
        """.trimIndent()
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
