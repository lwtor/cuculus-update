package dev.cuculus.update

import java.io.File
import kotlinx.coroutines.flow.Flow

/** The result of comparing the latest release with the installed version. */
sealed interface UpdateCheck {
    /** The installed version is already the latest, but the release is still exposed so a caller
     *  can force a re-download of the same APK (debug/testing aid). */
    data class UpToDate(
        val release: ReleaseInfo,
        val asset: ReleaseAsset,
    ) : UpdateCheck

    /** A release exists but carries no APK this updater can install (missing or unrecognised). */
    data object NoInstallableAsset : UpdateCheck

    data class Available(
        val release: ReleaseInfo,
        val asset: ReleaseAsset,
        val versionCode: Long,
    ) : UpdateCheck
}

/**
 * The latest known self-update state, driven by [Updater.checkAndPrefetch]. Collect it to light a
 * badge; `Downloaded` means an APK is ready to install.
 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus

    /** A newer release is known; its APK is either not yet downloaded or failed to prefetch. */
    data class UpdateAvailable(val versionCode: Long) : UpdateStatus

    /** The APK for [versionCode] has been downloaded and is ready to hand to the installer. */
    data class Downloaded(val versionCode: Long, val apkFile: File) : UpdateStatus
}

/**
 * The surface an app drives. Kept as an interface so app-side view models stay JVM-testable
 * without Android framework types.
 */
interface UpdateGateway {
    /** Fetches the latest release and decides whether it contains an installable update. */
    suspend fun check(installedVersionCode: Long): UpdateCheck

    /** Returns the already-downloaded APK for [versionCode], or null when none is cached. */
    fun cachedApk(versionCode: Long): File?

    /**
     * Records [apk] as the downloaded package for [versionCode] so a later process finds it via
     * [cachedApk]. Call after a successful [download] when the app drives its own UI state; the SDK
     * cannot infer the versionCode from [download]'s asset alone. Without this, an in-app download
     * is forgotten the moment the process dies and gets fetched again on the next launch.
     */
    fun rememberDownloadedApk(versionCode: Long, apk: File)

    /** Downloads the APK in the foreground via a WorkManager worker and awaits its completion. */
    suspend fun downloadInForeground(asset: ReleaseAsset): File

    /** Emits the foreground download's progress as a fraction in `0f..1f`; null while unresolved. */
    fun observeForegroundProgress(): Flow<Float?>

    /** Cancels the in-flight foreground download, if any. */
    fun cancelForegroundDownload()

    /** Downloads the APK; [onProgress] is invoked on the caller's dispatcher as bytes arrive. */
    suspend fun download(asset: ReleaseAsset, onProgress: (UpdateProgress) -> Unit = {}): File

    /**
     * Hands the downloaded APK to the system package installer. Returns immediately once the
     * installer is launched; the user confirms the install in the system dialog. No install
     * outcome is reported back.
     */
    fun install(apk: File): InstallResult
}
