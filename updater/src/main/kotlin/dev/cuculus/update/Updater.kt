package dev.cuculus.update

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/**
 * GitHub-Releases-backed self-update, assembled once per app from an [UpdaterConfig].
 *
 * Everything project-specific — the repository, the FileProvider authority, the on-disk scope, the
 * user-visible strings and the versionCode scheme — comes from the config, so the same library
 * serves any app that publishes APKs as release assets.
 */
class Updater private constructor(
    context: Context,
    private val config: UpdaterConfig,
    httpClient: OkHttpClient,
) : UpdateGateway {
    private val appContext = context.applicationContext
    private val api = ReleaseApi(httpClient)
    private val downloader = ApkDownloader(httpClient)
    private val installer = ApkInstaller(config.fileProviderAuthority)
    private val stateStore = UpdateStateStore(appContext, config.scope)
    private val scheduler = UpdateWorkScheduler("${config.scope}.download")

    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)

    /** The foreground download this process started, so progress is read from the right work. */
    @Volatile
    private var currentDownloadId: java.util.UUID? = null

    /** The process-wide update state, updated by [checkAndPrefetch]. */
    val status: StateFlow<UpdateStatus> = mutableStatus.asStateFlow()

    private val downloadDirectory: File
        get() = updaterCacheDir(appContext, config.scope)

    override suspend fun check(installedVersionCode: Long): UpdateCheck {
        val release = api.latestRelease(config.owner, config.repository)
        val asset = ReleaseSelection.pickUpdateApk(release.assets)
            ?: return UpdateCheck.NoInstallableAsset
        val versionCode = config.versionResolver.versionCodeOf(release.tagName, asset.name)
            ?: return UpdateCheck.NoInstallableAsset
        return if (ReleaseSelection.isUpdate(versionCode, installedVersionCode)) {
            UpdateCheck.Available(release, asset, versionCode)
        } else {
            UpdateCheck.UpToDate(release, asset)
        }
    }

    /**
     * Returns the APK asset of the latest release **without any version comparison**.
     *
     * Unlike [check], this neither decodes a versionCode from the asset name nor requires the
     * release to be newer than the installed build. Use it whenever the caller wants "just give me
     * the APK" — manual retry, forced re-install, a debug/demo download button, or a release whose
     * asset naming does not follow the `-<versionCode>.apk` contract. [check] remains the API for
     * deciding whether an update badge should be shown.
     *
     * This is a method on the concrete [Updater] rather than on [UpdateGateway], so the gateway's
     * seven actions and its JVM-testable surface stay unchanged.
     *
     * @throws ReleaseApiException / java.io.IOException when the latest release cannot be fetched
     *   (e.g. the repository has only drafts/prereleases).
     * @return null when the release exists but carries no `.apk` asset.
     */
    suspend fun latestInstallableAsset(): ReleaseAsset? {
        val release = api.latestRelease(config.owner, config.repository)
        return ReleaseSelection.pickUpdateApk(release.assets)
    }

    override fun cachedApk(versionCode: Long): File? = stateStore.cachedApkForVersion(versionCode)

    override suspend fun downloadInForeground(asset: ReleaseAsset): File {
        val targetName = ReleaseSelection.sanitizeAssetFileName(asset.name)
        val requestId = scheduler.start(
            appContext,
            asset.downloadUrl,
            targetName,
            asset.sizeBytes,
            config.scope,
            config.strings,
        )
        currentDownloadId = requestId

        // Wait for THIS request only. getWorkInfosForUniqueWorkFlow() also emits historic records
        // with the same unique name (REPLACE keeps them), so `all { isFinished }` could be
        // satisfied immediately by a stale record whose output data belongs to an older download.
        // Surface a terminal failure as an exception so the caller never installs a missing file.
        val infos = WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(scheduler.workName)
            .first { list -> list.any { it.id == requestId && it.state.isFinished } }
        val finished = infos.first { it.id == requestId }
        if (finished.state == WorkInfo.State.SUCCEEDED) {
            val path = finished.outputData.getString(UpdateDownloadWorker.OUTPUT_FILE_PATH)
            if (!path.isNullOrBlank()) return File(path)
        }
        val reason = finished.outputData.getString(UpdateDownloadWorker.OUTPUT_ERROR)
            ?: "update download did not complete (${finished.state})"
        throw DownloadException(reason)
    }

    override fun observeForegroundProgress(): Flow<Float?> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(scheduler.workName)
            .map { infos ->
                // Prefer the work this process actually started; a unique-work flow also carries
                // historic records whose stale progress would otherwise win via lastOrNull().
                val id = currentDownloadId
                (infos.firstOrNull { it.id == id } ?: infos.lastOrNull())
                    ?.progress
                    ?.getFloat(UpdateDownloadWorker.PROGRESS_KEY, -1f)
                    ?.takeIf { it >= 0f }
            }
            .distinctUntilChanged()

    override fun cancelForegroundDownload() {
        scheduler.cancel(appContext)
    }

    override suspend fun download(
        asset: ReleaseAsset,
        onProgress: (UpdateProgress) -> Unit,
    ): File {
        val target = File(downloadDirectory, ReleaseSelection.sanitizeAssetFileName(asset.name))
        // The advertised size lets the downloader fan out over several connections; a wrong value
        // only costs one wasted attempt, because the segmented path verifies the byte count and
        // falls back to a single stream.
        return downloader.download(asset.downloadUrl, target, asset.sizeBytes, onProgress)
    }

    override fun install(apk: File): InstallResult = installer.install(appContext, apk)

    /**
     * Startup update check plus silent prefetch. Any failure is swallowed so a transient network
     * problem never crashes the app or spams the user. A known update is surfaced through [status]
     * so the badge lights even when the prefetch fails.
     *
     * Never throws (cancellation excepted).
     */
    suspend fun checkAndPrefetch(installedVersionCode: Long) {
        val check = try {
            check(installedVersionCode)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return
        }
        val available = when (check) {
            is UpdateCheck.Available -> check
            else -> return
        }

        mutableStatus.value = UpdateStatus.UpdateAvailable(available.versionCode)

        stateStore.cachedApkForVersion(available.versionCode)?.let { cached ->
            mutableStatus.value = UpdateStatus.Downloaded(available.versionCode, cached)
            return
        }

        val target = File(downloadDirectory, ReleaseSelection.sanitizeAssetFileName(available.asset.name))
        try {
            downloader.download(available.asset.downloadUrl, target, available.asset.sizeBytes)
            stateStore.saveDownloadedApk(available.versionCode, target.absolutePath)
            mutableStatus.value = UpdateStatus.Downloaded(available.versionCode, target)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Keep the badge lit; the user can still tap through and retry the download manually.
            mutableStatus.value = UpdateStatus.UpdateAvailable(available.versionCode)
        }
    }

    companion object {
        /**
         * Builds an [Updater] for [config].
         *
         * [httpClient] null lets the SDK build its own; pass yours to share connection pools (and,
         * importantly, your proxy/DNS configuration) with the rest of the app. The client is also
         * published to [WorkerHttp] because the foreground worker cannot be handed it directly.
         */
        fun create(
            context: Context,
            config: UpdaterConfig,
            httpClient: OkHttpClient? = null,
        ): Updater {
            val client = httpClient ?: OkHttpClient()
            WorkerHttp.client = client
            return Updater(context, config, client)
        }
    }
}
