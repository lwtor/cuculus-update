package dev.cuculus.update.sample

import android.app.Application
import dev.cuculus.update.ArtifactRepoRef
import dev.cuculus.update.UpdateSourceMode
import dev.cuculus.update.Updater
import dev.cuculus.update.UpdaterConfig

/**
 * Builds the [Updater] once per process, from an Application context that is already attached.
 *
 * **This demo updates itself.** In [UpdateSourceMode.CURRENT_REPO] (the default) `repository`
 * points at this SDK's own repository because that is where *this app's* APKs are published.
 * Switch to [UpdateSourceMode.ARTIFACT_REPO] from the UI to read a tagged release from a shared
 * public artifact repository instead (see [buildUpdaterFor]).
 *
 * Version scheme: demo assets are named `cuculus-update-demo-v<name>-<versionCode>.apk`, so the
 * SDK's default `AssetNameSuffix()` reads a plain increasing integer that lives in the same number
 * space as this app's `BuildConfig.VERSION_CODE`. No custom resolver is needed. Note that the
 * download button does not depend on this at all — it uses `Updater.latestInstallableAsset()`.
 *
 * This must NOT be done in an Activity property initializer: an Activity has no base Context
 * until attachBaseContext() runs, so getPackageName() and getApplicationContext() throw a
 * NullPointerException there and the app crashes before onCreate(). Application.onCreate() is
 * also the right place to repopulate WorkerHttp after a cold start where a WorkManager worker
 * is restored before the UI.
 */
class SampleApp : Application() {

    /** The source the demo currently uses; the UI can rebuild the Updater when it changes. */
    data class SourceState(
        val mode: UpdateSourceMode = UpdateSourceMode.CURRENT_REPO,
        val owner: String = "lwtor",
        val repository: String = "cuculus-update",
        val namespace: String = "",
    )

    @Volatile
    var sourceState: SourceState = SourceState()
        private set

    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        rebuildUpdater(sourceState)
    }

    /**
     * Rebuilds the process-wide [Updater] for a new source. Each mode gets its own [UpdaterConfig.scope]
     * so a cached APK from one source never satisfies the other — this is exactly the multi-source
     * isolation the SDK promises, exercised here for real.
     */
    fun rebuildUpdater(state: SourceState): Updater {
        sourceState = state
        updater = Updater.create(applicationContext, buildUpdaterConfig(state))
        return updater
    }

    private fun buildUpdaterConfig(state: SourceState): UpdaterConfig = when (state.mode) {
        UpdateSourceMode.CURRENT_REPO -> UpdaterConfig(
            owner = state.owner.ifBlank { "lwtor" },
            repository = state.repository.ifBlank { "cuculus-update" },
            fileProviderAuthority = "$packageName.updater",
            scope = "demo_current",
        )

        UpdateSourceMode.ARTIFACT_REPO -> UpdaterConfig(
            owner = state.owner.ifBlank { "lwtor" },
            repository = state.repository.ifBlank { "cuculus-update" },
            fileProviderAuthority = "$packageName.updater",
            scope = "demo_artifact",
            sourceMode = UpdateSourceMode.ARTIFACT_REPO,
            // Blank fields fall back to the SDK's built-in public artifact repo + this app's
            // repository as the tag namespace; the UI lets the tester override all three.
            artifactRepo = if (state.owner.isBlank() && state.repository.isBlank()) {
                null
            } else {
                ArtifactRepoRef(state.owner.trim(), state.repository.trim())
            },
            artifactNamespace = state.namespace.ifBlank { null },
        )
    }
}
