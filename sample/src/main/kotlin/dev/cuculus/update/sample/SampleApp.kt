package dev.cuculus.update.sample

import android.app.Application
import dev.cuculus.update.ReleaseVersionResolver
import dev.cuculus.update.Updater
import dev.cuculus.update.UpdaterConfig

/**
 * Builds the [Updater] once per process, from an Application context that is already attached.
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
                repository = "venera-native",
                fileProviderAuthority = "$packageName.updater",
                versionResolver = ReleaseVersionResolver.Or(
                    ReleaseVersionResolver.AssetNameSuffix(),
                    ReleaseVersionResolver.TagScheme(),
                ),
            ),
        )
    }
}
