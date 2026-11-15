# cuculus-update

App self-update through release hosts such as GitHub Releases — the update member of the
**Cuculus** toolkit series (named after the cuckoo's brood parasitism: an app lays its new
version into a host's nest and lets the host hatch it). Android first, but the name and API are
deliberately not tied to a platform so the series can grow into KMP / Flutter.

The library checks the latest published release of a repository, picks the right APK asset,
downloads it resumably with a foreground notification, and hands it to the system installer
through a FileProvider. The consumer app declares **no permissions, no service and no provider** —
the library manifest merges all of it.

> **完整知识库（面向大模型一步到位接入）**：[`llms.txt`](llms.txt) · [`AGENTS.md`](AGENTS.md) · [`docs/`](docs/README.md)。
> 接入前先读 `docs/09-llm-playbook.md`，它给出决策树、可直接复制的完整代码和验收清单。

Derived from [Venera Native](https://github.com/lwtor/venera-native); see `NOTICE`.

## Requirements

| | |
|---|---|
| JDK | 17 |
| Gradle | 9.5.0 (wrapper included) |
| AGP | 9.3.1 (Kotlin support is built in — no Kotlin plugin to apply) |
| compileSdk | 37 |
| minSdk | 26 |

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal() // only while testing a local publish
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.cuculus:cuculus-update:1.0.0")
}
```

## Quick start

Create the `Updater` **once, from an attached Context**. The recommended place is
`Application.onCreate()`; register the class with `android:name=".MyApplication"` on your
`<application>` tag.

```kotlin
class MyApplication : Application() {
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
```

> **Do not call `Updater.create()` in an Activity property initializer**, e.g.
> `private val updater = Updater.create(this, ...)`. An Activity is not attached to a base Context
> until `attachBaseContext()`, so `packageName` and `applicationContext` are unavailable there and
> the app crashes on launch. Create the process-wide singleton in `Application.onCreate()`.
> `Activity.onCreate()` with `applicationContext` is only a last-resort fallback (at the latest):
> a cold-start WorkManager worker can be created before that Activity exists, so it would find no
> shared `WorkerHttp.client`.

Check:

```kotlin
val check = updater.check(BuildConfig.VERSION_CODE.toLong())
when (check) {
    is UpdateCheck.Available -> showUpdate(check.versionCode, check.asset)
    is UpdateCheck.UpToDate -> showUpToDate()
    UpdateCheck.NoInstallableAsset -> showNoApk()
}
```

Download and install — either in the foreground (a WorkManager worker with a progress
notification, survives leaving the screen):

```kotlin
val apk = updater.downloadInForeground(asset)   // suspends until finished, throws on failure
updater.observeForegroundProgress().collect { fraction -> /* 0f..1f or null */ }
updater.cancelForegroundDownload()
val result = updater.install(apk)               // InstallResult.Launched | InstallResult.Failure
```

or inline, with byte-level progress on the caller's dispatcher:

```kotlin
val apk = updater.download(asset) { progress -> render(progress.fraction) }
```

Startup check plus silent prefetch (never throws):

```kotlin
lifecycleScope.launch(Dispatchers.IO) { updater.checkAndPrefetch(BuildConfig.VERSION_CODE.toLong()) }
updater.status.collect { status -> /* Idle | UpdateAvailable | Downloaded */ }
```

`checkAndPrefetch` performs a silent inline download with blocking disk writes, so run it on an IO
dispatcher (`kotlinx.coroutines.Dispatchers`); `status` itself can be collected on Main.

`cachedApk(versionCode)` returns a previously downloaded APK, but only when the recorded file
still exists and still starts with the ZIP local file header; a stale record is cleared.

## Configuration

| Field | Default | Meaning |
|---|---|---|
| `owner` | — | GitHub user or organisation |
| `repository` | — | repository whose **latest** release is the update source |
| `fileProviderAuthority` | — | must equal `${applicationId}.updater` unless you override the manifest |
| `scope` | `self_update` | namespaces cache dir, SharedPreferences, notification channel and unique work name |
| `versionResolver` | `AssetNameSuffix()` | maps a release to the Android versionCode |
| `strings` | Chinese | notification channel name/description, title and `String.format` progress pattern |

### Version resolution

The release's versionCode competes with `BuildConfig.VERSION_CODE`; an update needs a strictly
larger number.

| Resolver | Reads |
|---|---|
| `AssetNameSuffix(separator = '-')` | trailing number of the asset name: `app-v1.9.3-1009003.apk` → `1009003` |
| `TagScheme(majorMultiplier = 1_000_000L, minorMultiplier = 1_000L)` | `v1.9.3` → `1_009_003` |
| `Or(primary, fallback)` | `primary`, then `fallback` when it returns null |

`AssetNameSuffix` is the reliable scheme when your release tooling bakes the build's versionCode
into the file name; `Or(AssetNameSuffix(), TagScheme())` keeps tag-only releases working too.

### Storage layout

```
cacheDir/updater/<scope>/<sanitised-asset-name>.apk
cacheDir/updater/<scope>/<sanitised-asset-name>.apk.part   # kept for resume
SharedPreferences "<scope>.updater"                        # versionCode -> apk path
notification channel "<scope>.updates"
unique work "<scope>.download"
```

The bundled `res/xml/updater_file_paths.xml` exposes `cacheDir/updater/`, which covers every
scope. If you point `fileProviderAuthority` at your own provider, expose the same subtree.

### What the library manifest merges

`INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`; the WorkManager
`SystemForegroundService` with `foregroundServiceType="dataSync"`; and a `${applicationId}.updater`
FileProvider backed by `@xml/updater_file_paths`.

`POST_NOTIFICATIONS` must still be granted at runtime on API 33+ — until it is, the download runs
but its progress notification is hidden.

## Sample

`:sample` is a classic-Views demo (`Cuculus Update Demo`, applicationId
`dev.cuculus.update.sample`) with 检查更新 / 下载 / 安装 buttons and a log of the last
`UpdateCheck`. Its manifest declares no permissions and no provider on purpose: if it builds and
runs, the library merge works.

The demo uses a `NoActionBar` theme and draws its own title inside the scrolling content, then
pads the root view with `OnApplyWindowInsetsListener` for the status/navigation bars. On
Android 15+ a `targetSdk 37` app is forced edge-to-edge, so a system ActionBar or an unpadded
root would cover the top buttons. Keep that in mind if you copy the sample layout.

```bash
sh gradlew :updater:testDebugUnitTest --console=plain   # 23 unit tests
sh gradlew :updater:assembleRelease --console=plain
sh gradlew :sample:assembleDebug --console=plain
sh gradlew :updater:publishToMavenLocal --console=plain # dev.cuculus:cuculus-update:<updaterSdkVersion>
```

## License

Apache-2.0. See `LICENSE` and `NOTICE`.
