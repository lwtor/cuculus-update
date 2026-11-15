package dev.cuculus.update.sample

import android.app.Application
import dev.cuculus.update.Updater
import dev.cuculus.update.UpdaterConfig

/**
 * Builds the [Updater] once per process, from an Application context that is already attached.
 *
 * **This demo updates itself.** `repository` points at this SDK's own repository because that is
 * where *this app's* APKs are published. Pointing it at some other app's repository is the classic
 * integration mistake: the check would compare against that app's versionCode and download an APK
 * with a different applicationId/signature, so the install step could never succeed.
 *
 * Version scheme: demo assets are named `cuculus-update-demo-v<name>-<versionCode>.apk`, so the
 * SDK's default `AssetNameSuffix()` reads a plain increasing integer (6, 7, ...) that lives in the
 * same number space as this app's `BuildConfig.VERSION_CODE`. No custom resolver is needed.
 *
 * This must NOT be done in an Activity property initializer: an Activity has no base Context
 * until attachBaseContext() runs, so getPackageName() and getApplicationContext() throw a
 * NullPointerException there and the app crashes before onCreate(). Application.onCreate() is
 * also the right place to repopulate WorkerHttp after a cold start where a WorkManager worker
 * is restored before the UI.
 */
class SampleApp : Application() {

    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        updater = Updater.create(
            applicationContext,
            UpdaterConfig(
                owner = "lwtor",
                // The repository that publishes THIS app's APKs — not the SDK source, not another app.
                repository = "cuculus-update",
                fileProviderAuthority = "$packageName.updater",
                // default versionResolver = AssetNameSuffix(); see the class KDoc above.
            ),
        )
    }
}
