# 04 · 配置、版本编码与多实例隔离

> 目的：确定 `UpdaterConfig` 每个字段怎么填、`scope` 怎么隔离多个更新源、通知文案怎么覆盖成英文、`ReleaseVersionResolver` 怎么选/怎么自定义、versionCode 遵循什么单调递增规则。接入代码见 `02-integration.md`，签名见 `03-api-reference.md`。

---

## 1. `UpdaterConfig` 字段逐项

```kotlin
data class UpdaterConfig(
    val owner: String,                                                   // 必填
    val repository: String,                                              // 必填
    val fileProviderAuthority: String,                                   // 必填
    val scope: String = "self_update",
    val versionResolver: ReleaseVersionResolver = ReleaseVersionResolver.AssetNameSuffix(),
    val strings: UpdaterStrings = UpdaterStrings(),
)
```

| 字段 | 类型 | 默认 | 必填 | 含义 | 硬约束 |
|---|---|---|---|---|---|
| `owner` | `String` | — | ✅ | GitHub 用户 / 组织 | 与发布仓库一致；用于 `GET /repos/{owner}/{repository}/releases/latest` |
| `repository` | `String` | — | ✅ | 仓库名，其 **latest release** 是更新源 | 同上 |
| `fileProviderAuthority` | `String` | — | ✅ | FileProvider authority | **必须等于 `${applicationId}.updater`**（见 §2） |
| `scope` | `String` | `"self_update"` | ❌ | 命名空间，隔离所有落盘状态 | 必须是**单个安全路径段**（见 §3） |
| `versionResolver` | `ReleaseVersionResolver` | `AssetNameSuffix()` | ❌ | release → versionCode | 必须与 App 的 versionCode 编码方案一致（见 §5、§6） |
| `strings` | `UpdaterStrings` | 中文默认值 | ❌ | 通知文案 | `progressFormat` 必须恰好消费一个整数参数（见 §4） |

`data class` 自带 `copy()`；可用 `config.copy(scope = "beta")` 派生第二个更新源配置。**无构造校验**：错误配置在运行时 `check()` / `install()` 才暴露。

---

## 2. `fileProviderAuthority` 约束

### 2.1 默认值与推导

库清单合并出的 provider（`updater/src/main/AndroidManifest.xml`）：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.updater"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/updater_file_paths" />
</provider>
```

`updater_file_paths.xml`：

```xml
<paths>
    <cache-path name="updater_downloads" path="updater/" />
</paths>
```

- `${applicationId}` 在清单合并时替换为 **App 最终 applicationId**（含 `applicationIdSuffix`，例如 `.debug` 变体也会带上）。
- 因此确定规则：`fileProviderAuthority = "<最终 applicationId>.updater"`。在 `Application` 里写 `"$packageName.updater"` 即可自动匹配（`packageName` 返回的正是最终 applicationId）。

```kotlin
UpdaterConfig(
    owner = "lwtor",
    repository = "venera-native",
    fileProviderAuthority = "$packageName.updater",   // ✅ 确定写法
)
```

### 2.2 不匹配会怎样

`ApkInstaller.install()` 调 `FileProvider.getUriForFile(context, authority, apk)`。authority 与实际注册的 provider 不一致时抛出，被包成：

```
InstallResult.Failure("unable to expose the APK: ...")
```

下载照常完成，只有安装失败。

### 2.3 自定义 provider（仅在有强理由时）

若你必须换 authority（例如与其它库冲突），**必须在 App manifest 里自己声明一个 provider**，并同时满足：

1. `android:authorities` 与传给 `UpdaterConfig` 的 `fileProviderAuthority` **逐字相等**。
2. `android:name` 为 `androidx.core.content.FileProvider`（或其子类）——SDK 用 `FileProvider.getUriForFile(...)` 生成 URI。
3. `android:exported="false"`、`android:grantUriPermissions="true"`。
4. 用 `<cache-path path="updater/" />`（或覆盖该子树的父路径）暴露 `cacheDir/updater/`——因为所有 scope 的 APK 都落在 `updaterCacheDir()` 返回的 `cacheDir/updater/<scope>/`。**暴露不到就 `Failure`**。
5. 库合并出的默认 provider（`${applicationId}.updater`）依然存在；若你不再使用它，可用 `tools:node="replace"` 覆盖它，避免两个 provider 并存。

> **默认建议：不要自定义。** 直接用 `"$packageName.updater"`，零配置、零冲突面。

---

## 3. `scope` 多实例隔离规则

### 3.1 它命名空间化了什么（精确）

| 状态 | 名称 | 来源 |
|---|---|---|
| 落盘目录 | `cacheDir/updater/<scope>/` | `UpdaterPaths.updaterCacheDir()` |
| 续传临时文件 | `cacheDir/updater/<scope>/<asset>.apk.part` | `ApkDownloader` |
| SharedPreferences | `"<scope>.updater"`（键 `downloaded_version_code` / `downloaded_apk_path`） | `UpdateStateStore` |
| 通知渠道 | `"<scope>.updates"`，notificationId `scope.hashCode()` | `UpdateNotifier` |
| WorkManager 唯一任务 | `"<scope>.download"`（`ExistingWorkPolicy.REPLACE`） | `UpdateWorkScheduler` |

**不同 `scope` 的两个 `UpdaterConfig` 完全隔离**：各自独立的缓存目录、下载记录、通知渠道、下载任务，互不覆盖。**相同 `scope` 则共享**这些状态（`REPLACE` 会让后一次下载顶掉前一次）。

### 3.2 命名建议（确定规则）

- ✅ 用**小写 ASCII、稳定的标识符**：`self_update`（默认）、`stable`、`beta`、`main`、`china`。
- ✅ 字符限定在 `[A-Za-z0-9._-]`。
- ❌ **绝对不要包含 `/`、`\`、`..` 或任何路径分隔符**。`scope` 被直接拼进 `File(cacheDir, "updater/$scope")`，**未经消毒**（消毒只作用于 asset 文件名，`ReleaseSelection.sanitizeAssetFileName` 不处理 scope）。`scope = "a/b"` 会建出嵌套目录，`scope = "../x"` 会逃出 `updater/` 根目录并破坏 FileProvider 暴露范围。
- ✅ 一旦发布就**保持稳定**：改 `scope` 会让旧 APK / 下载记录 / 渠道 / 任务名全部成为孤儿，预下载记录对不上。
- ℹ️ `notificationId = scope.hashCode()`：两个 scope 哈希碰撞最多让一条进度通知被另一条替换，只是视觉问题；渠道不同，状态互不污染。
- ⚠️ `WorkerHttp.client` 是**进程级**（不按 scope 隔离）。即使建两个不同 scope 的 `Updater`，最后一次 `Updater.create()` 的 `OkHttpClient` 会覆盖前者；若两个源需要不同 proxy/DNS，目前做不到——这是设计限制，见 `01-architecture.md` §6.4。

### 3.3 多更新源示例

```kotlin
// 在 Application.onCreate() 里建两个实例，用不同 scope 隔离；注意 httpClient 仍是进程级共享。
// 必须传 applicationContext：Activity 构造期调用会 NPE 导致启动崩溃（见 09 篇禁止事项 18）。
val stableUpdater = Updater.create(
    applicationContext,
    UpdaterConfig(
        owner = "lwtor",
        repository = "venera-native",
        fileProviderAuthority = "$packageName.updater",
        scope = "stable",
        versionResolver = ReleaseVersionResolver.Or(
            ReleaseVersionResolver.AssetNameSuffix(),
            ReleaseVersionResolver.TagScheme(),
        ),
    ),
)

val betaUpdater = Updater.create(
    applicationContext,
    UpdaterConfig(
        owner = "lwtor",
        repository = "venera-native-beta",
        fileProviderAuthority = "$packageName.updater",   // 同一个 FileProvider 足够：cache-path 覆盖所有 scope
        scope = "beta",
    ),
)
```

> `fileProviderAuthority` 两者可以相同：`updater_file_paths.xml` 暴露的是 `cacheDir/updater/` 整棵子树，覆盖所有 scope，不需要为每个 scope 配一个 provider。

---

## 4. `UpdaterStrings` 中英文覆盖

```kotlin
data class UpdaterStrings(
    val channelName: String = "应用更新",
    val channelDescription: String = "显示应用内新版本 APK 的下载进度",
    val downloadTitle: String = "正在下载新版本",
    val progressFormat: String = "已下载 %d%%",
)
```

### 英文覆盖（可直接抄）

```kotlin
import dev.cuculus.update.UpdaterStrings

UpdaterStrings(
    channelName = "App updates",
    channelDescription = "Shows the download progress of a new in-app APK",
    downloadTitle = "Downloading update",
    progressFormat = "Downloaded %d%%",
)
```

放进 `UpdaterConfig(strings = ...)`；4 个字符串会随 WorkManager `inputData` 持久化，进程被杀后重建的 worker 仍用同一套文案。

### `progressFormat` 的硬约束（重要）

`UpdateNotifier.notification()` 内部执行：

```kotlin
String.format(strings.progressFormat, percent)   // percent 是一个 Int
```

- ✅ **必须恰好包含一个整数占位符**：`%d`（或 `%1$d`）。`%%` 表示字面 `%`。
- ❌ 不要加第二个 `%d` / `%s` / 其它格式符：只有 1 个实参，会抛 `MissingFormatArgumentException` / `IllegalFormatConversionException`。而 `UpdateDownloadWorker.doWork()` 里的 `setForeground(notifier.foregroundInfo(0f))` **在 try/catch 之外**，该异常会让 worker 直接失败 → `downloadInForeground()` 抛 `DownloadException`。
- ❌ 完全不含 `%d` 不会崩溃，但通知里永远不显示百分比数字。
- ✅ 多语言时按目标语言写，如中文 `"已下载 %d%%"`、日文 `"%d%% ダウンロード済み"`。

---

## 5. `ReleaseVersionResolver` 三种策略：选择决策树

```
Q1: 你的发布流程会把 versionCode 烘焙进 APK 文件名吗？
    例：venera-native-v1.9.3-1009003.apk（最后一段是 versionCode）
    ├─ 是 → AssetNameSuffix()                         ← 默认、首选、最可靠
    │        └─ 还想兼容「只有 tag、文件名没 code」的历史 release
    │             → Or(AssetNameSuffix(), TagScheme())
    ├─ 否，但 tag 是规范的 vMAJOR.MINOR[.PATCH] → TagScheme()
    └─ 都不是（自定义命名 / 私有字段）→ 自定义 ReleaseVersionResolver { tag, assetName -> ... }
```

| 策略 | 读什么 | 忽略什么 | 典型输入 → 输出 |
|---|---|---|---|
| `AssetNameSuffix(separator: Char = '-')` | asset 名「最后一个分隔符之后、扩展名之前」的数字 | `releaseTag` | `app-v1.9.3-1009003.apk` → `1009003`；`app-9.APK` → `9`；`venera-native.apk` → `null` |
| `TagScheme(majorMultiplier = 1_000_000L, minorMultiplier = 1_000L)` | `releaseTag`（去 `v`/`V` 前缀后按 `.` 切 2~3 段） | `apkAssetName` | `v1.9.3` → `1_009_003`；`v1.6` → `1_006_000`；`v999.999.999` → `999_999_999`；`v1.1000.0` → `null` |
| `Or(primary, fallback)` | `primary` 非 null 直接用，否则 `fallback` | — | `Or(AssetNameSuffix(), TagScheme())`：文件名有 code 优先 |

**选择规则（确定）**
- 发布工具能把构建出来的 versionCode 写进文件名 → 用 `AssetNameSuffix()`（默认）。它读的是「实际构建出的 code」，不与 tag 文本耦合，最不容易出错。
- 只有规范 tag、文件名没有 code → 用 `TagScheme()`。
- 两者都要兼容 → `Or(AssetNameSuffix(), TagScheme())`（**顺序有意义**：primary 先赢）。
- 命名不符合以上任一 → 自定义。

**注意**：`AssetNameSuffix` 的 `separator` 是 `Char`（如 `'-'`、`'_'`），不是 `String`；`TagScheme` 的两个 multiplier 是 `Long`，且是 `private val`，只能在构造时传入。

### 自定义写法

`ReleaseVersionResolver` 是 `fun interface`，可直接用 SAM lambda：

```kotlin
import dev.cuculus.update.ReleaseVersionResolver
import dev.cuculus.update.UpdaterConfig

// 例：从自定义文件名里抽 6 位以上数字作为 versionCode
val resolver = ReleaseVersionResolver { releaseTag, apkAssetName ->
    Regex("""-(\d{6,})\.apk$""", RegexOption.IGNORE_CASE)
        .find(apkAssetName)
        ?.groupValues?.get(1)
        ?.toLongOrNull()
}

val config = UpdaterConfig(
    owner = "lwtor",
    repository = "venera-native",
    fileProviderAuthority = "$packageName.updater",
    versionResolver = resolver,
)
```

自定义约束（确定规则）：
- 签名固定 `fun versionCodeOf(releaseTag: String, apkAssetName: String): Long?`；两个参数都是**原始值**（tag 可能带 `v`，asset 名未消毒）。
- 必须是**纯函数**：无 I/O、无网络、确定性；它在 `check()` 路径里被同步调用。
- 返回 `null` → `check()` 得到 `UpdateCheck.NoInstallableAsset`（不会更新）。
- 返回值必须与 App 的 `versionCode` 使用**同一套编码**（见 §6），否则比较无意义。

---

## 6. versionCode 单调递增规则

### 6.1 公式

```
versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH
每段 0..999，最大值 999_999_999（< Int.MAX_VALUE，可安全放进 BuildConfig.VERSION_CODE）
```

对应 `TagScheme` 的默认 `majorMultiplier = 1_000_000L`、`minorMultiplier = 1_000L`；`AssetNameSuffix` 则直接读文件名里这个数值。

| versionName | tag | versionCode | 推荐的 APK asset 名 |
|---|---|---|---|
| `1.9.3` | `v1.9.3` | `1_009_003` | `venera-native-v1.9.3-1009003.apk` |
| `1.10.0` | `v1.10.0` | `1_010_000` | `venera-native-v1.10.0-1010000.apk` |
| `2.0.0` | `v2.0.0` | `2_000_000` | `venera-native-v2.0.0-2000000.apk` |
| `999.999.999` | `v999.999.999` | `999_999_999` | 上限 |

### 6.2 为什么每段必须 ≤ 999

- `minorMultiplier = 1_000` 给 `PATCH` 预留 3 位十进制；`majorMultiplier = 1_000_000` 给 `MINOR` 再预留 3 位。
- 若 `MINOR = 1000`：`1*1_000_000 + 1000*1_000 = 2_000_000`，与 `MAJOR=2, MINOR=0, PATCH=0` 撞车 → **非单调**。`TagScheme` 也因此显式校验 `>999 → null`。
- 结论：`MAJOR` / `MINOR` / `PATCH` 全部保持 `0..999`。

### 6.3 铁律：两端必须用同一套编码

SDK 只做一件事：`release 的 versionCode > installedVersionCode`（严格大于）才算更新（`ReleaseSelection.isUpdate`）。因此：

```kotlin
// app/build.gradle.kts —— 必须与发布端 tag / 文件名后缀用同一套公式
defaultConfig {
    versionCode = 1_009_003
    versionName = "1.9.3"
}
```

```kotlin
// 发布端：tag v1.9.3，asset venera-native-v1.9.3-1009003.apk
// 检查时：updater.check(BuildConfig.VERSION_CODE.toLong())
```

- ✅ 每个新 Release 的 versionCode **严格大于**线上已发布的所有版本；只增不减、不复用。
- ✅ 若用 `Or(AssetNameSuffix(), TagScheme())`，**文件名后缀的数字必须与 tag 推导出的数字一致**：asset suffix 会赢，若两者不一致会按错误的 code 比较。
- ❌ 不要只改 `versionName` / tag 文本却不改 versionCode：`check()` 仍返回 `UpToDate`。
- ❌ 不要把 `versionName`（`"1.9.3"`）当作 versionCode 用。

### 6.4 自定义 multiplier

```kotlin
// 2 位方案：MAJOR*10_000 + MINOR*100 + PATCH，各段保持 0..99
ReleaseVersionResolver.TagScheme(
    majorMultiplier = 10_000L,
    minorMultiplier = 100L,
)
```

规则：每段必须小于「相邻 multiplier 的比值」（默认方案里是 1000），否则数字会互相进位、破坏单调性。改了 multiplier，App 的 `versionCode` 也必须同步改成同一公式。

---

## 7. 该做什么 / 不该做什么 / 常见误判

**该做什么**
- `fileProviderAuthority` 固定写 `"$packageName.updater"`。
- 多个更新源用不同 `scope`；`scope` 保持为单个 `[A-Za-z0-9._-]` 标识符且长期不变。
- `progressFormat` 恰好一个 `%d`，`%%` 表示百分号。
- 发布端 versionCode、App `versionCode`、`versionResolver` 三者用同一套编码且严格递增。

**不该做什么**
- ❌ 不要让 `scope` 含 `/`、`..`、空格或非 ASCII（未经消毒，会拼进文件路径）。
- ❌ 不要在 `progressFormat` 里加第二个格式符（会让前台 worker 直接失败）。
- ❌ 不要自定义 authority 却忘记在 App manifest 里注册对应 FileProvider、忘记暴露 `cacheDir/updater/`。
- ❌ 不要只改 tag / versionName 不改 versionCode。
- ❌ 不要以为不同 `scope` 能隔离 `OkHttpClient`（`WorkerHttp` 是进程级）。

**常见误判**

| 误判 | 真相 |
|---|---|
| 「`scope` 随便填，SDK 会消毒」 | 消毒只作用于 asset 文件名；`scope` 原样拼进 `File(cacheDir, "updater/$scope")`。 |
| 「每个 scope 要配一个 FileProvider」 | `updater_file_paths.xml` 暴露 `cacheDir/updater/` 整棵子树，一个 provider 覆盖所有 scope。 |
| 「`progressFormat = "Downloading"` 更简洁」 | 不含 `%d`，通知里永远没有百分比数字；含多余格式符会直接让 worker 抛异常。 |
| 「`Or(TagScheme(), AssetNameSuffix())` 与反过来一样」 | 顺序有意义：`primary` 先赢。推荐 `Or(AssetNameSuffix(), TagScheme())`。 |
| 「`AssetNameSuffix` 的分隔符传字符串 `'-'` 或 `"-"`」 | 参数是 `Char`，写 `'-'`；`"-"` 是 `String`，类型不符。 |
| 「把 tag 从 `v1.9.3` 改成 `v1.9.4` 就能触发更新」 | 只有 versionCode 严格变大才触发；tag 文本不参与比较。 |
| 「`applicationIdSuffix` 不影响 authority」 | `${applicationId}` 包含 suffix，`packageName` 也返回带 suffix 的值，二者天然一致；**硬编码** authority 才会在 debug 变体失配。 |
