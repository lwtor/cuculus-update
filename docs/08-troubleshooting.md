# 08 · 常见故障与排查

> 进阶篇。每条给出「检查命令 / 日志线索 / 修复方式」三段。命令默认在本仓库根目录执行，`JAVA_HOME` 见 [11 篇](11-testing-and-build.md)。`<pkg>` 指消费方 App 的 applicationId，`<apk>` 指产物路径。
>
> 关联：[06 行为与边界](06-behavior-and-edge-cases.md)、[09 接入手册](09-llm-playbook.md)、[11 测试与构建](11-testing-and-build.md)。

## 排查前先建立 3 个基线

```bash
# 1) 本机环境
export JAVA_HOME=/path/to/jdk-17   # 替换为你本机 JDK 17 的实际路径
sh gradlew :updater:testDebugUnitTest :updater:assembleRelease :sample:assembleDebug --console=plain

# 2) 合并后的清单（确认库条目是否合并进 App）
#    app 模块：app/build/intermediates/merged_manifest/<variant>/AndroidManifest.xml
#    sample：  sample/build/intermediates/merged_manifest/debug/AndroidManifest.xml
grep -nE 'uses-permission|provider|authorities|SystemForegroundService|foregroundServiceType|FILE_PROVIDER_PATHS' \
  sample/build/intermediates/merged_manifest/debug/AndroidManifest.xml

# 3) 设备侧权限与 provider
adb shell dumpsys package <pkg> | grep -iE 'REQUEST_INSTALL_PACKAGES|POST_NOTIFICATIONS|FOREGROUND_SERVICE|provider|authorit|versionCode'
```

日志统一用 tag 过滤（SDK 自身不主动打 log，异常由调用方打印，故重点看抛出的异常类型与 message）：

```bash
adb logcat -v brief | grep -iE 'cuculus|UpdateDownload|WorkManager|WM-|DownloadException|ReleaseApiException|FileProvider|PackageInstaller|InstallException'
```

---

## 0. App 一启动就崩溃（最高频，先查这条）

- **日志线索**：`NullPointerException: Attempt to invoke virtual method '...ContextWrapper.getApplicationContext()' (or getPackageName()) on a null object reference`；栈顶在 `Activity.<init>` / 属性初始化，发生在 `onCreate` 之前，logcat 里常伴随 `FATAL EXCEPTION: main`。
- **原因**：`Updater.create(this, ...)` 或 `"$packageName.updater"` 被写在 **Activity 属性初始化器**里。Activity 在 `attachBaseContext()` 之前 `mBase` 为 null；`Updater` 构造还会立即 `context.getSharedPreferences(...)`，同样需要有效 Context。这与 SDK 逻辑无关，纯属 Context 生命周期误用。
- **修复**：把创建移到 `Application.onCreate()`，存成进程级单例（`lateinit var updater`），Activity 里用 `(application as MyApplication).updater` 取；**记得在 manifest 的 `<application>` 上注册 `android:name=".MyApplication"`**。最晚可在 `Activity.onCreate()` 内用 `applicationContext` 创建，但那样进程被杀后先恢复的 WorkManager worker 可能拿不到 `WorkerHttp.client`。
- **自查**：
  ```bash
  grep -rn 'Updater.create' app/src   # 任何出现在 Activity 顶层 private val 上的调用都是错的
  dexdump -d <apk> | grep 'Updater;->create'   # 看它落在 <init> 还是 onCreate
  ```
  修复后 `<init>` 里不应再出现 `create` / `getPackageName`。

---

## 1. 无更新 / 一直 `UpdateCheck.NoInstallableAsset`

- **检查命令**：
  ```bash
  # 直接打 GitHub API（地址来自 ReleaseApi.DEFAULT_API_BASE_URL）
  curl -sS -H 'Accept: application/vnd.github+json' \
       -H 'X-GitHub-Api-Version: 2026-03-10' \
       https://api.github.com/repos/<owner>/<repo>/releases/latest \
       | python3 -m json.tool | grep -E 'tag_name|"name"|browser_download_url|content_type|"size"'
  # 确认 App 传入的 installedVersionCode
  adb shell dumpsys package <pkg> | grep versionCode
  ```
- **日志线索**：`check()` 返回 `NoInstallableAsset`；或抛 `ReleaseApiException(404, "no published release")`。`ReleaseSelection.pickUpdateApk()` 只挑 `.apk`（不区分大小写）、优先 `contentType == "application/vnd.android.package-archive"`、再按 `sizeBytes` 降序。`versionResolver.versionCodeOf(...)` 返回 null 也会得到 `NoInstallableAsset`。
- **修复方式**：
  1. 仓库有**已发布**（非 draft、非 prerelease）的 release 且 `tag_name` 非空；
  2. release 里至少有一个 `.apk` 资产，且 `browser_download_url` 以 `http` 开头；
  3. `versionResolver` 能从「资产名尾部数字」或「tag」解析出 versionCode —— 对照 [09 决策树](09-llm-playbook.md)：只有 tag 的 release 要配 `TagScheme()` 或 `Or(AssetNameSuffix(), TagScheme())`；
  4. 解析出的 versionCode **严格大于** `installedVersionCode`（相等/更小 → `UpToDate`，见第 12 条）；
  5. 若返回 `UpToDate` 想强制重下同一个 APK，用 `check.release` / `check.asset` 直接 `download(asset)`（debug 辅助，见 `UpdateCheck.UpToDate` KDoc）。

## 2. 下载卡住 / 一直 loading

- **检查命令**：
  ```bash
  # WorkManager 任务状态
  adb shell dumpsys jobscheduler | grep -i <pkg>          # 看是否有 pending/active job
  adb shell dumpsys activity services <pkg> | grep -i foreground
  # 进度通知渠道是否存在
  adb shell dumpsys notification | grep -iE '<scope>.updates|download'
  # 缓存里的 .part 是否在增长
  adb shell run-as <pkg> ls -l cache/updater/<scope>/     # 多次执行对比 .part 大小
  ```
- **日志线索**：进度停在 0 通常不是「下载慢」，而是 **UI 拿不到字节级进度**。四类根因按出现频率排查：① `setForeground()` 被系统拒绝后 worker 无限重试 → `downloadInForeground()` 永远等不到终态；② 最后一次 `setProgressAsync()` 未 await，或 `observeForegroundProgress()` 用 `lastOrNull()` 读到历史 WorkInfo 的旧进度；③ `getWorkInfosForUniqueWorkFlow()` 的历史终态让 `all { isFinished }` 立刻成立，等错了 work；④ 陈旧的 `.part` 让服务端对 `Range` 返回 416，或代理接受连接却零字节，旧逻辑要等满 120s `readTimeout`。SDK 已分别修为：`setForeground` 移入 `try` 并转 `Result.failure`、结束时 `setProgress(1f)` 落库、按本次 `requestId` 等待/读进度、416 删 `.part` 从头重下、零字节 stall 视为连接失败立刻切直连（`connectTimeout` 10s）。
- **修复方式**：
  1. 确认设备联网且 `CONNECTIVITY` 约束可满足；切到稳定网络重试；
  2. 系统代理不稳定时 SDK 会在「连接失败且零字节」时回退直连（[06 §5](06-behavior-and-edge-cases.md)），若代理可用但极慢，把共享 `OkHttpClient` 通过 `Updater.create(..., httpClient)` 传入以便复用（或反之不传，让 SDK 用默认 client）；
  3. 确认没有同时跑 inline `download()` 与 `checkAndPrefetch()` 对同一 asset 并发写（[06 §12](06-behavior-and-edge-cases.md)）；
  4. **只想「点一下就看到下载」的页面（含本仓库 `:sample`）不要走前台链路**：在 `Dispatchers.IO` 上用 `download(asset, onProgress)`，`onProgress` 里切回主线程更新 View，绕过 FGS 权限 / 通知渠道 / WorkManager 进度持久化（[03 §12.3](03-api-reference.md)）；
  5. UI 把「下载中」做成可等待态，不要自己设短超时杀掉协程；用户可点「取消」后重试。

### 2.1 下载很慢（几十 KB/s），和别的 App 差几十倍

先分清是谁的问题，别先改代码。用同一台电脑测同一个 URL 拿基准：

```bash
curl -o /dev/null -w 'speed=%{speed_download}B/s total=%{time_total}s\n' -L \
  "https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>.apk"
```

- **同一 URL 用 curl 也慢** → 到 GitHub 资产 CDN（`release-assets.githubusercontent.com`）的链路问题，和 SDK 无关。实测某网络单连接 90–180 KB/s，直连更差（约 44 KB/s），三个常见 GitHub 镜像也只有约 170 KB/s；而同一台设备下载漫画站的图能到几 MB/s——两类内容不在同一个 CDN 上，不能横向比。
- **curl 快、App 慢** → 才轮到查接入方：是否配了极慢的系统代理（见第 11 条）、是否把 `download()` 放在主线程、是否每收一次进度就整页重绘。

SDK 侧已经做的两件事（细节见 [03 §2.5](03-api-reference.md)）：

1. **分段并行**：已知 `asset.sizeBytes` 时切 4 段并发 `Range`，每段独立 `OkHttpClient` + 独立 `ConnectionPool`。release CDN 常按**单连接**限速，单流只能吃掉一部分带宽。实测同一链路单连接 90–180 KB/s、4 连接 130–480 KB/s（波动很大，平均约 2x）。
2. **进度按时间节流**（≥200 ms）而不是只按 256 KB 累计。否则 90 KB/s 下第一次回调要等约 3 秒，界面「0% / 0 KB」看着就像完全没动。

> 想彻底摆脱这条慢链路，最终解法是**换托管**：把 APK 放到国内对象存储/自建域名，让 release 资产的 `downloadUrl` 指过去。GitHub release 的资产 URL 是固定的，所以这要么需要发布侧改造，要么给 SDK 加一个「自定义下载 URL」开关——当前版本没有，需要时再加。

### 2.2 下载卡在某个百分比（65% / 80%）不动，而且永远不恢复

- **现象**：进度跑到某个数就定住，按钮一直禁用，等多久都不动、也不报错，只能杀进程。
- **检查命令**：
  ```bash
  # .part 是否还在增长（隔 10s 各执行一次）；长度是否已等于 asset.sizeBytes
  adb shell run-as <pkg> ls -l cache/updater/<scope>/
  # 同时看有没有读超时异常被抛出（SDK 不自打 log，异常由调用方打印）
  adb logcat -v brief | grep -iE 'DownloadException|SocketTimeout|InterruptedIOException'
  ```
- **根因（分两层，缺一层都「卡死」）**：
  1. **引擎层**：一条 socket 静默死亡（NAT 超时、移动网络切换、CDN 节点回收连接）时，读操作不会报错，只会一直阻塞到 `readTimeout` 才抛 `InterruptedIOException`。旧版读超时 120 s，4 段并行里任意一段中招，`awaitAll` 就带着整个下载干等 120 s；等到之后又因为没有重试逻辑，把半成品作废、退回单流从 0 开始——用户看到的是「卡住不动」，然后（如果没杀进程）「进度突然归零重下」。
  2. **UI 层**：下载按钮在传输期间被禁用，没有任何取消入口，所以卡住时用户连自救都做不到。
- **SDK 现在怎么处理**（[03 §2.5](03-api-reference.md)）：
  - 分段连接 `readTimeout` 降到 **15 s**、单流降到 **30 s**：死连接最多 15 s 就被识别为停滞；
  - 停滞**不等于失败**：已落盘的字节是合法前缀，SDK 用 `Range: bytes=<已达偏移>-<段尾>` **重连续传**，同一段最多 4 次（单流 2 次），不重下已完成的字节；
  - 单测 `reconnects a stalled segment and continues from its last byte` 锁住这点：服务端承诺 `0-1023` 只给 512 字节，重试必须是 `bytes=512-1023`；
  - 协程取消在每个 buffer 后检查一次（`ensureActive()`），调用方 `cancel()` 能在亚秒级停下，不会「取消后还在偷偷下」。
- **接入方该怎么做**：
  1. 下载放在 `Dispatchers.IO` 的协程里，并**保存 Job**，让 UI 能 `cancel()`（`:sample` 的下载按钮就是「下载/取消」双态）；
  2. 不要在 UI 侧自己设短超时 kill 协程——那会绕开续传，每次重试都从头开始；
  3. 卡住时先看 `.part` 是否在变长：在变长就是链路慢（见 §2.1），不变长才是停滞（SDK 会在 15 s 内自愈）。

> **与源项目 `venera-native/data/update` 的逐行对照**：两边都有单流下载、`Range` 断点续传、代理/直连双通道回退、ZIP 头校验；`416` 处理、4 段并行、停滞重试只有 SDK 侧有。原版之所以「没遇到过」，不是因为它更健壮，而是：① 单流只有一条 socket，暴露面是 4 段并行的 1/4；② 原版读超时 120 s 后**直接抛异常失败**（`streamFrom` 的读循环没有 try/catch，`InterruptedIOException` 直接冒泡），用户看到「下载失败」会重试，而不会看到「卡死」；SDK 旧版把失败换成了「等一条死连接」，感知上就成了永久卡住。结论：**原版是失败得快，不是不会失败**；修复后的 SDK 在这一项上严格优于原版。

## 3. 安装报「解析软件包时出现问题」

- **检查命令**：
  ```bash
  # 落盘文件头必须是 ZIP 本地文件头 PK\x03\x04
  adb shell run-as <pkg> sh -c 'od -An -tx1 -N4 cache/updater/<scope>/*.apk'   # 期望 50 4b 03 04
  adb shell run-as <pkg> ls -l cache/updater/<scope>/
  # 与官方 APK 对比签名 / 体积
  aapt dump badging <release-apk> | grep -E 'package:|application-label'
  ```
- **日志线索**：`PackageInstaller` / `PackageParser` 抛 parse 错误；`install()` 返回的是 `InstallResult.Launched`（只代表拉起成功），真正解析发生在系统安装器里。
- **修复方式**：
  1. 头 4 字节不是 `50 4b 03 04` → 下载体被换成 HTML / 错误页：SDK 本应拦下（[06 §2](06-behavior-and-edge-cases.md)），若仍出现，检查是否把 `.part` 或别的临时文件误交给 `install()`；**只传 `Downloaded.apkFile` / `downloadInForeground()` 返回的 `File`**；
  2. APK 被截断：删掉 `cache/updater/<scope>/` 下对应 `.apk` 与 `.apk.part`，重新下载触发续传 / 全量；
  3. 签名不一致 / 覆盖安装用了不同签名：这是 Android 系统限制，确认 release APK 与已装 App 同签名；
  4. `minSdk` 不兼容、架构不匹配、APK 本身损坏：用 `aapt`/`apkanalyzer` 校验 release 产物；
  5. 存储被系统清理过：`cachedApk()` 应已清脏记录返回 null（[06 §16](06-behavior-and-edge-cases.md)），确认拿的是现取的文件。

## 4. FileProvider authority 冲突（多 app / 多 provider）

- **检查命令**：
  ```bash
  # 设备上所有已装包的 provider authority
  adb shell dumpsys package providers | grep -iE 'FileProvider|updater|selfupdate'
  # 合并清单里 authority 是否重复
  grep -n 'authorities' <merged-manifest>/AndroidManifest.xml
  ```
- **日志线索**：安装时报 `IllegalArgumentException: Couldn't find meta-data for provider with authority ...`，或 `Failed to find configured root for ...`；`ApkInstaller` 捕获后返回 `InstallResult.Failure("unable to expose the APK: ...")`。同一设备上两个 App 用了相同 authority 会 `INSTALL_FAILED_CONFLICTING_PROVIDER`。
- **修复方式**：
  1. SDK 合并的 authority 是 `${applicationId}.updater`，每个 App 天然不同；**不要在 app manifest 再声明一个同名 provider**（重复声明也会冲突）。
  2. `UpdaterConfig.fileProviderAuthority` 必须等于 `"$packageName.updater"`。若你改了这个值，就**必须同步**用 `tools:node="replace"` 覆盖库 manifest 的 provider 并给出一致的 authority（见 AGENTS.md 不变量 2）——一般不建议改。
  3. App 自己另有 FileProvider（不同 authority）可与 SDK 共存，互不影响；不要合并成一个。
  4. 自定义 provider 时，`file_paths` 必须暴露 `cacheDir/updater/` 子树（`updater_file_paths.xml` 的 `<cache-path path="updater/"/>`），否则 `getUriForFile` 抛「Failed to find configured root」。

## 5. 通知不显示

- **检查命令**：
  ```bash
  adb shell dumpsys notification | grep -iE '<scope>.updates|POST_NOTIFICATIONS'
  adb shell cmd notification get_app_permission <pkg>        # 或
  adb shell dumpsys package <pkg> | grep -A2 POST_NOTIFICATIONS
  ```
- **日志线索**：下载正常完成但状态栏无进度；渠道 id 是 `"<scope>.updates"`（默认 `"self_update.updates"`），`IMPORTANCE_LOW`、`setShowBadge(false)`。
- **修复方式**：
  1. API 33+ 需在**运行时**申请 `android.permission.POST_NOTIFICATIONS`（库只声明、不申请）；用 `ActivityResultContracts.RequestPermission()`；
  2. 未授权是预期行为，下载照常进行、只是通知被隐藏（[06 §6](06-behavior-and-edge-cases.md)），不要因此禁用下载；
  3. 用户在系统设置里关了该渠道通知 → 引导到通知设置页，SDK 无法强制显示；
  4. 确认 `observeForegroundProgress()` 之外，前台服务确实起来了（见第 6 条）。

## 6. WorkManager 不启动

- **检查命令**：
  ```bash
  adb shell dumpsys jobscheduler | grep -i <pkg>
  adb logcat -v brief | grep -iE 'WM-|WorkManager|SystemForegroundService|UpdateDownloadWorker'
  # 合并清单里 service 节点
  grep -n 'SystemForegroundService' <merged-manifest>/AndroidManifest.xml
  ```
- **日志线索**：`downloadInForeground()` 永久挂起（`WorkInfo` 一直不进入 `isFinished`）；或 `MissingForegroundServiceTypeException`（API 34+）；或 `Could not instantiate UpdateDownloadWorker`（见第 7 条）。约束 `NetworkType.CONNECTED` 未满足时 worker 停在 ENQUEUED。
- **修复方式**：
  1. 确认 merged manifest 含 `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync"/>` 与 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` 权限（库会合并，别在 app 里删/改）；
  2. 若 App 自定义了 WorkManager 初始化（移除默认 `InitializationProvider` 改为 on-demand），确保 `WorkManager` 在下载前已初始化——SDK 用 `WorkManager.getInstance(context)`，未初始化会抛异常；
  3. 确认 `Updater.create(...)` 在 `Application.onCreate` 里**尽早调用**，以便 `WorkerHttp` 就绪（进程被杀后恢复场景，见 [06 §8](06-behavior-and-edge-cases.md)）；
  4. 电池优化 / 厂商省电策略可能冻结后台任务，真机验证时把 App 加入白名单；
  5. 设备离线：SDK 要求 `CONNECTED`，恢复网络后 WorkManager 会自动重新调度。

## 7. R8 后 worker 崩溃（debug 正常、release 崩）

- **检查命令**：
  ```bash
  # 确认 AAR 携带 consumer keep 规则
  unzip -p updater/build/outputs/aar/updater-release.aar proguard.txt
  # 对 App 的 release mapping 查类名是否被改写
  grep -i 'UpdateDownloadWorker' app/build/outputs/mapping/release/mapping.txt
  ```
- **日志线索**：`Could not instantiate dev.cuculus.update.UpdateDownloadWorker` / `NoSuchMethodException: <init>(Context, WorkerParameters)`；仅 `minifyEnabled true` 的 release 包出现。
- **修复方式**：
  1. 确认 `consumer-rules.pro` 内容 `-keep class dev.cuculus.update.UpdateDownloadWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }` 且 `updater/build.gradle.kts` 配了 `consumerProguardFiles("consumer-rules.pro")`；
  2. 不要在消费方 `proguard-rules.pro` 里覆盖 / 删这条；
  3. 自定义 `WorkerFactory` 时保留该两参构造；
  4. **必须**用 `assembleRelease` + 真机跑一次前台下载验证，别只跑 debug（[11 篇](11-testing-and-build.md)）。

## 8. GitHub API 限流 / 403 / 5xx

- **检查命令**：
  ```bash
  curl -sS -D - -o /dev/null -H 'Accept: application/vnd.github+json' \
       https://api.github.com/repos/<owner>/<repo>/releases/latest \
       | grep -iE 'HTTP/|x-ratelimit|retry-after'
  ```
- **日志线索**：`ReleaseApiException(statusCode = 403/429/503, message = "API rate limit exceeded ..." / GitHub 的 JSON message)`。`ReleaseApi.releaseError()` 取响应 JSON 的 `message` 字段作为异常信息，404 特判为 `"no published release"`。匿名调用 GitHub API 有每小时限额。
- **修复方式**：
  1. 等限额重置，或降低检查频率（SDK 不自带节流，检查频率由接入方控制——建议启动一次 + 用户手动触发）；
  2. 不要频繁在循环里调 `check()`；
  3. 通过 `Updater.create(..., httpClient)` 传入带合理 DNS/代理配置的 `OkHttpClient`（企业内网走代理时尤其重要）；
  4. 排除网络被墙 / DNS 污染；移动端匿名请求不需要 token，别把私有仓库逻辑套上来；
  5. UI 把 `ReleaseApiException` 映射为「暂时无法连接 / 稍后重试」，不要当成「无更新」。

## 9. tag 解析不出 versionCode

- **检查命令**：
  ```bash
  curl -sS https://api.github.com/repos/<owner>/<repo>/releases/latest | python3 -c 'import sys,json;print(json.load(sys.stdin)["tag_name"])'
  ```
- **日志线索**：`check()` 返回 `NoInstallableAsset`（因为 `versionCodeOf` 返回 null）。`TagScheme` 规则：去掉前缀 `v`/`V`，按 `.` 切分，**只允许 2 或 3 段**，每段必须解析为 `Int` 且在 `0..999`，结果 `major*1_000_000 + minor*1_000 + patch`。`latest`、`v1`、`v1.6.0.4`、`v1.1000.0` 都会返回 null。
- **修复方式**：
  1. 让 tag 遵循 `vMAJOR.MINOR[.PATCH]` 且各字段 ≤ 999（如 `v1.9.3` → `1_009_003`）；
  2. 乘数不符时自定义 `TagScheme(majorMultiplier, minorMultiplier)`；
  3. 完全自定义规则就实现 `ReleaseVersionResolver { fun versionCodeOf(releaseTag, apkAssetName): Long? }`（见 [09 §1](09-llm-playbook.md)）；
  4. 保持 versionCode **单调**（[06 / AGENTS.md 不变量 4](06-behavior-and-edge-cases.md)）。

## 10. asset 名解析失败（资产名尾部数字）

- **检查命令**：
  ```bash
  curl -sS https://api.github.com/repos/<owner>/<repo>/releases/latest \
       | python3 -c 'import sys,json;print("\n".join(a["name"] for a in json.load(sys.stdin)["assets"]))'
  ```
- **日志线索**：`AssetNameSuffix(separator='-')` 取「去掉最后一个 `.` 后的主干」，再取「最后一个 `-` 之后」的部分 `toLongOrNull()`。`app-1009003.apk` → `1009003`；`venera-native-v1.6.0-1009003.apk` → `1009003`；但 `app-v1.6.0.apk`（尾部是 `v1.6.0`？其实是 `0` 后面无数字段）、`venera-native.apk` 都会返回 null。
- **修复方式**：
  1. 资产名以 `-<纯数字>` 结尾且紧跟 `.apk`，例如 `myapp-v1.9.3-1009003.apk`；
  2. 分隔符不是 `-` 时传 `AssetNameSuffix(separator = '_')` 等；
  3. 资产名带非法字符会被 `sanitizeAssetFileName` 清洗（只影响落盘，不影响版本解析）；
  4. 用 `Or(AssetNameSuffix(), TagScheme())` 让资产名解析失败时回退到 tag；
  5. 单测 `ReleaseSelectionTest` 覆盖了这些边界，改规则前先跑 `sh gradlew :updater:testDebugUnitTest --console=plain`。

## 11. 代理环境（VPN / 内网 / DNS）

- **检查命令**：
  ```bash
  adb shell settings get global http_proxy
  adb shell dumpsys connectivity | grep -iE 'proxy|NetworkAgentInfo'
  ```
- **日志线索**：`DownloadConnectFailure("could not reach the download server: ...")` / `SocketTimeoutException`；`Updater.downloadInForeground()` 抛 `DownloadException`。SDK 先试系统代理通道，连接失败且零字节写入才回退 `Proxy.NO_PROXY` 直连（[06 §5](06-behavior-and-edge-cases.md)）。
- **修复方式**：
  1. App 有共享 `OkHttpClient`（带代理 / DNS / 拦截器）务必通过 `Updater.create(context, config, httpClient)` 传入，SDK 会把它发布给 `WorkerHttp` 供前台 worker 使用；
  2. `Application.onCreate` 尽早 `create`，否则进程被杀后恢复的 worker 会用裸 `OkHttpClient()`（丢代理配置）；
  3. 代理半可用（能连但卡死）时 SDK 不会触发回退（回退只认连接失败），此时在 App 侧调整代理配置或关闭 VPN 重试；
  4. 不要把 `REQUEST_INSTALL_PACKAGES` 之类与网络问题混淆——安装阶段失败看第 3、4 条。

## 12. 版本回退规则（为什么「新 release」没被判为更新）

- **检查命令**：
  ```bash
  adb shell dumpsys package <pkg> | grep versionCode          # 已装值
  curl -sS https://api.github.com/repos/<owner>/<repo>/releases/latest \
       | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["tag_name"], [a["name"] for a in d["assets"]])'
  ```
- **日志线索**：`ReleaseSelection.isUpdate(available, installed)` 为 true 的**唯一**条件是 `available != null && available > installed`。因此：相等 → `UpToDate`、更小（回退/降级 release）→ `UpToDate`、解析为 null → `NoInstallableAsset`。**SDK 永不触发降级安装**。
- **修复方式**：
  1. 传入 `check()` / `checkAndPrefetch()` 的必须是 `Long` 型的 **versionCode**（`BuildConfig.VERSION_CODE.toLong()` 或 `PackageInfoCompat.getLongVersionCode`），不要用 `versionName`（`"1.9.3"`）或硬编码常量；
  2. 保证 release 的 versionCode 单调递增，且每次发版都大于上一版；
  3. 若想对同版本强制重下（测试 / 调试），用 `UpdateCheck.UpToDate(release, asset)` 里的 `asset` 直接 `download(asset)`，不要试图绕过 `isUpdate`；
  4. 自定义 resolver 时同样要返回可比较、单调的 `Long`。

---

## 附：按异常类型快速分流

| 现象 | 多半是 | 先看 |
|---|---|---|
| `ReleaseApiException(404)` | 无已发布 release | §1 |
| `ReleaseApiException(403/503)` | 限流 / 服务错误 | §8 |
| `DownloadException` + `.part` 存在 | 网络 / 截断，可续传 | §2、§11 |
| `InstallResult.Failure("unable to expose the APK: ...")` | FileProvider authority / paths | §4 |
| 「解析软件包时出现问题」 | APK 头 / 签名 / 截断 | §3 |
| release 包 `Could not instantiate ...Worker` | R8 keep 丢失 | §7 |
| `check()` 恒为 `NoInstallableAsset` | asset / tag / resolver | §1、§9、§10 |
| 已是最新但想强制重下 | `UpToDate` 语义 | §12 |
