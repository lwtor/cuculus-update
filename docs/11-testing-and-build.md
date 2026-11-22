# 11 · 测试与构建

> 进阶篇。本文给出本仓库的环境要求、完整命令清单、manifest 合并验证、真机验证、mavenLocal 接入演练、29 个单测逐项覆盖说明与 CI 建议。
>
> 事实来源：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`、`updater/build.gradle.kts`、`updater/consumer-rules.pro`、`updater/src/main/AndroidManifest.xml`、`updater/src/main/res/xml/updater_file_paths.xml`，以及 4 个测试类源码。
>
> 关联：[07 接入验证清单](07-migration.md)、[09 验收清单](09-llm-playbook.md)、[08 故障排查](08-troubleshooting.md)。

---

## 1. 环境要求

| 项 | 值 | 说明 |
|---|---|---|
| JDK | **17** | 需自行配置 `JAVA_HOME` 指向本机 JDK 17（例如 Amazon Corretto 17）；`compileOptions` 为 `VERSION_17` |
| Gradle | **9.5.0** | wrapper 自带，`gradle/wrapper/gradle-wrapper.properties` |
| AGP | **9.3.1** | `com.android.library` / `com.android.application`；**内置 Kotlin 支持，无需单独 Kotlin 插件、无需维护 Kotlin 版本** |
| compileSdk | **37** | 写进 AAR 的 `minCompileSdk`，消费方必须 ≥ 37 |
| minSdk | **26** | 库与 sample 一致 |
| okhttp | **5.5.0** | `api(libs.okhttp)` |
| coroutines | **1.11.0** | `api(libs.kotlinx.coroutines.core)` |
| androidx.work | **2.12.0** | `implementation(libs.androidx.work.runtime.ktx)` |
| androidx.core | **1.19.1** | `implementation(libs.androidx.core.ktx)`（FileProvider） |
| 测试 | junit 4.13.2 / coroutines-test / mockwebserver3 / org.json 20260814 | android.jar 只有 org.json 桩，单测需真实实现 |

> 本机 `gradlew` **无执行位**（`-rw-r--r--`），一律用 `sh gradlew ...`，不要 `./gradlew`，也不要去 `chmod +x`（会改现有文件）。

统一前置：

```bash
cd "$(git rev-parse --show-toplevel)"   # 进入本仓库根目录
export JAVA_HOME=/path/to/jdk-17   # 替换为你本机 JDK 17 的实际路径
```

---

## 2. 完整命令清单

```bash
# ① 一次性全量（单测 + AAR + sample APK）—— 推荐作为改代码后的回归
sh gradlew :updater:testDebugUnitTest :updater:assembleRelease :sample:assembleDebug --console=plain

# ② 单独：29 例 JVM 单测
sh gradlew :updater:testDebugUnitTest --console=plain
#    单测报告：updater/build/reports/tests/testDebugUnitTest/index.html

# ③ 单独：AAR（release）
sh gradlew :updater:assembleRelease --console=plain
#    产物：updater/build/outputs/aar/updater-release.aar
#    确认 consumer keep 规则随 AAR 分发：
#    unzip -p updater/build/outputs/aar/updater-release.aar proguard.txt

# ④ 单独：sample APK（验证清单合并是否成立）
sh gradlew :sample:assembleDebug --console=plain
#    产物：sample/build/outputs/apk/debug/sample-debug.apk

# ⑤ 发布到本机 mavenLocal（接入演练用）
sh gradlew :updater:publishToMavenLocal --console=plain
#    产物：~/.m2/repository/dev/cuculus/cuculus-update/1.0.0/
#          cuculus-update-1.0.0.aar / -sources.jar / .pom / .module

# ⑥ 清理
sh gradlew clean --console=plain
```

版本号来自 `gradle.properties` 的 `updaterSdkVersion`（当前 `1.0.0`），`updater/build.gradle.kts` 用 `providers.gradleProperty("updaterSdkVersion").getOrElse("1.0.0")` 读取；与任何消费方 App 的 `versionCode` / `versionName` 解耦。`settings.gradle.kts` 中 `rootProject.name = "cuculus-update"`，模块 `:updater`（library + maven-publish）与 `:sample`（application）。

> `settings.gradle.kts` 使用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，仓库只在 settings 声明（`google()`、`mavenCentral()`）；本地演练时在消费方工程的 settings 里加 `mavenLocal()`（见 §5），不要改本仓库的 settings。

---

## 3. 如何验证 manifest 合并结果

库 manifest（`updater/src/main/AndroidManifest.xml`）应合并进消费方：6 权限 + `SystemForegroundService`(dataSync) + FileProvider `${applicationId}.updater`；`sample/src/main/AndroidManifest.xml` **故意为空**（无权限 / provider / service），它就是合并契约的回归测试——**sample 能编译、且合并产物含这些条目，即证明契约成立**。

### 3.1 找到合并后的 manifest

```bash
# AGP 版本不同目录名可能是 merged_manifest 或 merged_manifests，用 find 兜底
find . -path '*/build/intermediates/*' -name AndroidManifest.xml \
     | grep -iE 'merged_manifest|merged_manifests' | sort
```

### 3.2 检查合并条目

```bash
MERGED=sample/build/intermediates/merged_manifest/debug/AndroidManifest.xml   # 按实际路径替换

# 6 个权限
grep -nE 'INTERNET|ACCESS_NETWORK_STATE|REQUEST_INSTALL_PACKAGES|FOREGROUND_SERVICE|FOREGROUND_SERVICE_DATA_SYNC|POST_NOTIFICATIONS' "$MERGED"

# 前台服务与 provider
grep -nE 'SystemForegroundService|foregroundServiceType|FileProvider|authorities|FILE_PROVIDER_PATHS' "$MERGED"

# authority 应恰好出现一次且为 ${applicationId}.updater（占位符在打包前已被替换成真实包名）
grep -c 'dev.cuculus.update.sample.updater' "$MERGED"   # 期望 1
```

### 3.3 从 APK 反查（更接近最终产物）

```bash
# 用 Android SDK 自带工具打印二进制 manifest
$ANDROID_HOME/build-tools/<version>/aapt2 dump xmltree sample/build/outputs/apk/debug/sample-debug.apk --file AndroidManifest.xml \
  | grep -iE 'permission|provider|service|authorities|dataSync'
# 或
apkanalyzer manifest print sample/build/outputs/apk/debug/sample-debug.apk
```

### 3.4 设备侧确认（安装后）

```bash
adb shell dumpsys package dev.cuculus.update.sample \
  | grep -iE 'REQUEST_INSTALL_PACKAGES|POST_NOTIFICATIONS|FOREGROUND_SERVICE|Provider|authorit|versionCode'
```

检查点：
- 6 权限齐全；
- `SystemForegroundService` 有 `foregroundServiceType="dataSync"`（API 34+ 缺了会抛 `MissingForegroundServiceTypeException`）；
- FileProvider authority = `<applicationId>.updater`，**唯一、无重复声明**；
- `@xml/updater_file_paths` 的 `<cache-path path="updater/"/>` 与 `UpdaterPaths.updaterCacheDir()` 的 `cacheDir/updater/<scope>/` 对齐（AGENTS.md 不变量 2）。

---

## 4. 如何在真实设备验证下载与安装

> 前置：准备一个**已发布**（非 draft / 非 prerelease）的 GitHub release，含 `.apk` 资产且其 versionCode 大于 sample 当前的 `versionCode`（见 `sample/build.gradle.kts`）；或把 sample 的 `applicationId` / `owner` / `repository` 指向你自己的测试仓库。注意 `:sample` 的「下载最新版本」按钮**不校验版本号**，任何带 `.apk` 的普通 release 都可反复下载测试。

```bash
# 1) 安装 sample
adb install -r sample/build/outputs/apk/debug/sample-debug.apk

# 2) 打开 logcat（另开终端）
adb logcat -v brief | grep -iE 'cuculus|UpdateDownload|WorkManager|WM-|DownloadException|ReleaseApiException|FileProvider|PackageInstaller'

# 3) 在 sample 上依次点：检查更新 → 下载最新版本 → 安装
#    检查：应打印 UpdateCheck.Available（含 asset.name / versionCode / downloadUrl），或 UpToDate
#    下载：ProgressBar 按真实字节数递增到 100%，状态文字显示已下载 KB
#          :sample 走 inline download(asset, onProgress)，不依赖 WorkManager / FGS / 通知渠道，
#          因此这一步不会出现进度通知；通知只在接入方改用 downloadInForeground() 时才有

# 4) 观察文件（前台 work 只在用 downloadInForeground() 的接入方才存在）
adb shell dumpsys jobscheduler | grep -i dev.cuculus.update.sample     # 可选
adb shell run-as dev.cuculus.update.sample ls -l cache/updater/self_update/
adb shell run-as dev.cuculus.update.sample sh -c 'od -An -tx1 -N4 cache/updater/self_update/*.apk'  # 期望 50 4b 03 04

# 5) 断点续传验证：下载到一半断网 / 强杀进程，恢复后 .part 续传，最终 APK 完整
# 6) 安装：系统弹窗出现 → 确认 → 应用被替换
```

按版本矩阵至少覆盖：

| API | 关注点 |
|---|---|
| 26（minSdk） | 通知渠道必建、前台服务无 type 要求 |
| 33 | `POST_NOTIFICATIONS` 运行时授权：授权后通知显示，拒绝后下载仍完成 |
| 34+ | `FOREGROUND_SERVICE_DATA_SYNC` + `foregroundServiceType="dataSync"`；`REQUEST_INSTALL_PACKAGES` 下 MIUI/HyperOS 安装弹窗 |

R8 场景额外验证（debug 正常易掩盖问题）：用消费方的 `assembleRelease`（minifyEnabled）跑一次前台下载，确认 `UpdateDownloadWorker` 两参构造被 `consumer-rules.pro` 保住（见 [08 §7](08-troubleshooting.md)）。

### 4.1 进度一直是 0% 时怎么定位

进度条停在 0 通常不是「下载慢」，而是 **UI 拿不到字节级进度**。按下表区分（本仓库 SDK 已逐条修复）：

| 现象 | 根因 | SDK 的处理 |
|---|---|---|
| `.part` 不增长、logcat 反复 `WorkManager` 重试 | `setForeground()` 抛异常且旧实现把它放在 `try` 外，worker 无限重试，`downloadInForeground()` 永远等不到终态 | `setForeground()` 移入 `try`，异常转 `Result.failure(OUTPUT_ERROR)`；调用方收到 `DownloadException` 而不是永久挂起 |
| worker 成功但进度不动 | 最后一次 `setProgressAsync()` 未 await 就结束；`observeForegroundProgress()` 用 `lastOrNull()` 读到历史 WorkInfo 的旧 progress | 结束时 `setProgress(1f)` suspend 落库；Flow 优先读本次 `requestId` |
| 点下载立刻「完成」却拿到旧文件 | unique work 的历史终态让 `all { isFinished }` 立刻成立，等错了 work | `downloadInForeground()` 只等 `UpdateWorkScheduler.start()` 返回的本次 `UUID` |
| 秒失败 / 一直 0 且 `.part` 已存在 | `.part` 与新 asset 不匹配，服务端对 `Range` 返回 416 | 删掉 `.part` 并从 0 重下（单测 #6） |
| 代理接受连接但零字节 | 旧逻辑要等满 120s `readTimeout` 才失败 | 零字节 + `InterruptedIOException` 视为连接失败立刻切直连；`connectTimeout` 10s |
| 能下，但满速也就几十 KB/s | 到 release CDN 的**单连接**带宽本身很低，和 SDK 的读写循环无关 | 已知 `sizeBytes` 时切 4 段并发 Range（每段独立连接）；实测单连接 90–180 KB/s → 4 连接 130–480 KB/s。先用 curl 测同一 URL 做基准，判定方法与「换托管」的最终解法见 [08 §2.1](08-troubleshooting.md) |

> **不要**用前台下载的 `Flow<Float?>` 给一个只想「点一下就看到下载」的页面：在 `Dispatchers.IO` 上跑 `download(asset, onProgress)`、回调里切回主线程更新 View，是最短且最可靠的链路（[03 §12.3](03-api-reference.md)）。`:sample` 已按此实现，[08 §2](08-troubleshooting.md) 给出了对应的排查顺序。

---

## 5. 如何本地用 mavenLocal 做接入演练

用于「不发布远端就验证 AAR 能被干净消费」：

```bash
# ① 本仓库发布到本机 ~/.m2
cd "$(git rev-parse --show-toplevel)"   # 进入本仓库根目录
export JAVA_HOME=/path/to/jdk-17   # 替换为你本机 JDK 17 的实际路径
sh gradlew :updater:publishToMavenLocal --console=plain
ls -R ~/.m2/repository/dev/cuculus/cuculus-update/1.0.0/
```

```kotlin
// ② 消费方工程 settings.gradle.kts：仓库加 mavenLocal()，且放在 google()/mavenCentral() 之前优先命中
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

```kotlin
// ③ 消费方 app/build.gradle.kts
dependencies {
    implementation("dev.cuculus:cuculus-update:1.1.0")
}
```

```bash
# ④ 编译并按 §3 检查合并清单
cd <consumer-project>
sh gradlew :app:assembleDebug --console=plain
find . -path '*/build/intermediates/*' -name AndroidManifest.xml | grep -i merged
# 确认权限 / SystemForegroundService / ${applicationId}.updater provider 都合并进来了

# ⑤ 按 §4 真机跑通下载 + 安装
```

演练要点：
- `mavenLocal()` 只在演练期保留，正式接入改用远端仓库后移除，避免误用本机旧包。
- 演练 App 的 manifest **不写任何权限 / provider**（照 [09 §2.2](09-llm-playbook.md)），若合并失败说明 AAR 分发 / 仓库解析有问题，而不是去 App 里补声明。
- 每次改了 SDK 源码后重新 `publishToMavenLocal`；Gradle 可能缓存同版本 AAR，必要时 `sh gradlew --refresh-dependencies :app:assembleDebug`。

---

## 6. 29 个单测分别覆盖什么

`updater/src/test/kotlin/dev/cuculus/update/` 共 **4 个测试类、29 例**（12 + 3 + 10 + 4）。命令：`sh gradlew :updater:testDebugUnitTest --console=plain`。

下表按测试类分组，用例名就是源码里的反引号函数名，可直接全局搜索定位。**刻意不编号**：以前每加一个用例都要把后面二十多行整体重排，改一次错一次。

### `ApkDownloaderTest`（12 例，下载语义）

| 用例 | 覆盖内容 |
|---|---|
| `downloads an apk with progress and leaves no part file` | 正常下载：返回 target、产物长度一致、**无残留 `.part`**、进度单调且最终 `fraction == 1f`、`bytesDownloaded` / `totalBytes` 正确 |
| `rejects a body that is not an apk` | 返回 HTML（200 但非 APK）→ `DownloadException("the downloaded file is not an Android package")`；`target` 与 `.part` 都不存在（垃圾被删除） |
| `maps an http error to a download exception and writes nothing` | HTTP 404 → `DownloadException` 含 `404`，**零写入**、无 `.part` |
| `replaces a stale file from an earlier download attempt` | 已存在的陈旧 `target` 被新下载覆盖（原子改名 `REPLACE_EXISTING`），目录最终只剩 1 个文件 |
| `resumes a partial download via a range request` | 已有 `.part`（前半）→ 请求头带 `Range: bytes=2048-`，服务端 `206 Partial Content`，最终文件完整且字节一致 |
| `retries from zero when the server rejects a stale range` | 已存在过长的 `.part`（4096 字节）→ 首个请求带 `Range: bytes=4096-`、服务端返回 `416`；SDK 删掉 `.part` 并从 0 重下（第二个请求**没有** Range），最终字节完整、无残留 `.part`。防止旧 worker 留下的过期续传点让下载永久失败、进度停在 0 |
| `keeps the part file when the download fails mid-transfer` | 中途失败（HTTP 500）时 `.part` **必须保留**（2048 字节）、`target` 不存在——防止 `finally { part.delete() }` 抹掉续传点 |
| `fans out over parallel range requests when the size is known` | 传入已知总长 → 发出 4 个 `Range` 请求，它们的区间**无缝铺满** `[0, total)`；产物字节一致、无残留 `.part`。锁住分段并行的正确性（区间算错会静默产生空洞文件） |
| `falls back to a single stream when the server ignores range requests` | 服务端对每个 Range 都回 `200` 全量 → 分段路径放弃并行、删掉半成品 `.part`、改单流重下一次，最终字节正确而不是半截文件 |
| `reconnects a stalled segment and continues from its last byte` | 服务端承诺 `0-1023` 却只给 512 字节就断流 → 重试必须是 `Range: bytes=512-1023`，**不能重发 `0-1023`**。重发会把已落盘的 512 字节再累加一遍，`downloaded != total` 触发截断判定，整体退回单流从头下——这正是「卡在 80% 之后进度归零重来」的机制 |
| `resumes a single stream that ends early instead of restarting` | 单流（`sizeBytes` 未知）收到 `Content-Range` 声明 4096 却只来 2048 → 下一请求带 `Range: bytes=2048-` 而不是从头开始，最终字节一致 |
| `cancelling the caller aborts the transfer instead of finishing it` | 在进度回调里取消协程：阻塞式 socket 读自己看不到取消，靠每读满一个 buffer 调一次 `ensureActive()` 才能亚秒级停下；预分配出空洞的 `.part` 必须被删除，且不能产出 APK |

### `GitHubReleaseApiTest`（3 例，JSON 解析与错误映射）

| 用例 | 覆盖内容 |
|---|---|
| `parses the latest release with its assets` | 解析 `tag_name` / `name` / `body` / `published_at`、2 个 asset；APK 的 `contentType == application/vnd.android.package-archive`、`sizeBytes`、`downloadUrl` 正确；非 APK asset 的 `contentType` 为 `application/zip` |
| `maps a 404 to a no-published-release error` | 404 → `ReleaseApiException(statusCode = 404)`（`"no published release"`） |
| `maps a server error with the github message` | 503 + `{"message":"rate limit exceeded"}` → `ReleaseApiException(503)`，message 含 GitHub 返回的 `rate limit` 文案 |

### `ReleaseSelectionTest`（10 例，选包 / 版本号 / 纯规则）

| 用例 | 覆盖内容 |
|---|---|
| `picks the apk asset GitHub identifies as an android package` | 优先选 `contentType == APK_CONTENT_TYPE` 的 `.apk` |
| `picks an apk without a content type when no typed apk exists` | 无 typed APK 时仍按「`.apk` 过滤 + `sizeBytes` 降序（相同则保序）」选取 |
| `returns null when the release has no apk at all` | 只有 `notes.txt` / 空列表 → `pickUpdateApk` 返回 null |
| `reads the version code from the release asset name` | `AssetNameSuffix`：尾部数字 `1006000`、`app-9.apk → 9`、大小写 `.APK → 9` |
| `rejects asset names without a trailing numeric code` | `venera-native.apk` / `-v1.6.0.apk` / `app-v1.6.0.apk` / 空串 → null |
| `derives the version code from release tags` | `TagScheme`：`v1.6.0 → 1_006_000`、`1.6.0`、`v1.0.0 → 1_000_000`、`v2.0.10 → 2_000_010`、`v1.6 → 1_006_000`、`v999.999.999 → 999_999_999` |
| `rejects unparsable or out-of-scheme tags` | 空 / `latest` / `vX.Y.Z` / `v1` / `v1.6.0.4`（4 段）/ `v1.1000.0`（字段 >999）→ null |
| `the asset name wins and the tag is only the fallback` | `Or`：资产名优先；资产名解析失败回退 tag；两者都失败 → null；blank 资产名两者都拒绝、**不臆造版本号** |
| `an update needs a strictly larger version code` | `isUpdate`：`>` true、`==` false、`<` false、`null` false |
| `asset file names are stripped of path characters` | `sanitizeAssetFileName`：`a/b\c.apk → a_b_c.apk`，合法名不变 |

### `UpdateStateStoreTest`（4 例，APK 头校验）

| 用例 | 覆盖内容 |
|---|---|
| `accepts a file starting with the zip local file header` | `isValidApk`：`PK\x03\x04` 开头 → true |
| `rejects a file that does not start with the zip header` | 普通文本 → false |
| `rejects a missing file` | 文件不存在 → false |
| `rejects a file shorter than the header` | 仅 2 字节（短于 4 字节魔数）→ false |

> **未覆盖**（设计文档 §2 已标注，靠真机验证补）：`ApkInstaller`、`UpdateNotifier`、`UpdateWorkScheduler` / `UpdateDownloadWorker`（FGS 启动、进度持久化、按 requestId 等待）、`checkAndPrefetch` 的状态迁移、代理 → 直连回退分支与零字节 stall 回退、`UpdateStateStore` 的 SharedPreferences I/O。对应验证见 §4、§4.1 与 [06 篇](06-behavior-and-edge-cases.md)。

---

## 7. CI 建议

```yaml
# 概念示意（按需落到具体 CI 配置）
steps:
  - name: JDK 17
    uses: actions/setup-java@v4
    with: { distribution: corretto, java-version: '17' }
  - name: 校验 wrapper
    run: sh gradlew --version --console=plain
  - name: 单测 + AAR + sample
    run: sh gradlew :updater:testDebugUnitTest :updater:assembleRelease :sample:assembleDebug --console=plain
  - name: 发布到 mavenLocal 并校验产物
    run: |
      sh gradlew :updater:publishToMavenLocal --console=plain
      test -f ~/.m2/repository/dev/cuculus/cuculus-update/1.0.0/cuculus-update-1.0.0.aar
      test -f ~/.m2/repository/dev/cuculus/cuculus-update/1.0.0/cuculus-update-1.0.0-sources.jar
  - name: 校验 consumer keep 规则随 AAR 分发
    run: unzip -p updater/build/outputs/aar/updater-release.aar proguard.txt | grep UpdateDownloadWorker
  - name: 归档测试报告
    uses: actions/upload-artifact@v4
    with: { path: updater/build/reports/tests/testDebugUnitTest/ }
```

要点：

- **固定 JDK 17**（`compileOptions` / AGP 9.3.1 要求）；用 `sh gradlew`（本机无执行位）。
- 每次 PR 跑 §2 ① 的全量命令：29 例单测必须全绿，AAR 与 sample APK 必须能产出。
- **把「AAR 携带 `proguard.txt` 且含 worker keep 规则」作为 CI 断言**，防止 `consumer-rules.pro` 被误删导致 release 崩溃（[08 §7](08-troubleshooting.md)）。
- 增加一步「模拟消费方」：用 `mavenLocal()` + 一个零清单声明的 scratch app 编译，检查合并清单条目（§3、§5），把「清单合并契约」也纳入回归。
- 版本号来自 `gradle.properties:updaterSdkVersion`，发布流程只改这一处，不要散改。
- 真机 / API 矩阵（26 / 33 / 34+）与 R8 release 下载验证建议做成**夜间 / 手动触发**任务（需要设备与网络），不放进每次 PR 的快反馈路径。
- 提交规范见 `AGENTS.md` §5（Conventional Commits、每个小任务单独 commit）；本仓库由所有者统一执行 `git add/commit/push`。
