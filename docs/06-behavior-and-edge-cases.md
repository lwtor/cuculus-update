# 06 · 行为与边界情况

> 进阶篇。本文逐条说明 SDK 在异常 / 边界场景下的**确定行为**，每条固定给出「现象 / 原因 / SDK 的处理 / 接入方该怎么做」四段。事实来源：`updater/src/main/kotlin/dev/cuculus/update/*.kt`、`updater/src/main/AndroidManifest.xml`、`consumer-rules.pro`、`updater_file_paths.xml`。接入方读到某条现象时，按「接入方该怎么做」执行即可，无需追问。
>
> 关联：[09 接入手册](09-llm-playbook.md)、[08 故障排查](08-troubleshooting.md)、[07 迁移](07-migration.md)。

## 阅读前必须记住的 5 个事实

1. `Updater` 是**进程级门面**：一个 `Updater` = 一个 `UpdaterConfig` = 一个更新源。`status: StateFlow<UpdateStatus>` 只由 `checkAndPrefetch()` 驱动。
2. 下载目标目录由 `UpdaterPaths.kt:updaterCacheDir()` 唯一决定：`cacheDir/updater/<scope>/<sanitised-asset-name>.apk`，断点文件是同名加 `.part`。
3. 前台下载走 `UpdateDownloadWorker`（WorkManager，`CoroutineWorker` + `setForeground` + `dataSync`），unique work 名为 `"<scope>.download"`，策略 `REPLACE`。
4. `install()` 只是**拉起系统安装器**，返回 `InstallResult.Launched` / `Failure`，**没有任何安装结果回调**。
5. 库 manifest 合并 6 权限 + `SystemForegroundService`(dataSync) + `FileProvider ${applicationId}.updater`；消费者 App 不声明任何权限 / 服务 / provider。

---

## 1. 断点续传与 `.part`

- **现象**：下载被中断（网络断开、进程被杀、用户取消）后，再次下载不会从 0 开始；缓存目录里出现 `xxx.apk.part` 文件。
- **原因**：`ApkDownloader.download()` 把字节先写入 `target.name + ".part"`；`startBytes = if (part.isFile) part.length() else 0L`，若 >0 则在请求头加 `Range: bytes=<startBytes>-`。GitHub release CDN 对 Range 返回 `206 Partial Content`。
- **SDK 的处理**：
  - 成功时 `moveIntoPlace()` 用 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 把 `.part` 原子改名为 `target`，成功路径不残留 `.part`。
  - **传输失败时故意保留 `.part`**，下一次从它的长度续传（测试 `keeps the part file when the download fails mid-transfer` 专门防止 `finally { part.delete() }` 抹掉续传点）。
  - 若服务端回 `200`（忽略 Range 发了全文件），`offset = 0`，从头覆盖写；若回 `206`，`offset = startBytes` 续写。
  - 唯一会删除 `.part` 的失败：下载体不是 APK（见第 2、3 条），因为往垃圾后面追加有效字节没有意义。
  - `totalBytes` 由 `Content-Range` 尾部或 `Content-Length` 推出；收齐后若 `copied != totalBytes` 抛 `DownloadException("truncated download...")`，`.part` 保留供续传。
- **接入方该怎么做**：不要手动删除 `.part`，不要看到 `.part` 就当成失败；重复调用 `downloadInForeground()` / `download()` 即可自动续传。清理缓存时把同名 `.apk` 与 `.apk.part` 一起删。

## 2. 脏记录清理与 ZIP 头校验

- **现象**：系统清理过缓存、或用户手动删过 APK 后，App 不会拿一个不存在 / 损坏的文件去安装；`cachedApk(versionCode)` 返回 null，状态回到「可更新」。
- **原因**：APK 是 ZIP，文件头固定为本地文件头魔数 `PK\x03\x04`（`byteArrayOf(0x50,0x4B,0x03,0x04)`）。`UpdateStateStore` 只记「一个 versionCode → 一个路径」，用轻量魔数校验代替完整签名校验。
- **SDK 的处理**：
  - 下载完成后 `verifyApkHeader(part)`：读前 4 字节，不等于 `PK\x03\x04` 就抛 `DownloadException("the downloaded file is not an Android package")` 并删除 `.part`，不会把 HTML 错误页当成 APK。
  - `UpdateStateStore.cachedApkForVersion(versionCode)`：先比对存储的 versionCode，再取路径，`isValidApk(file)`（存在、长度 ≥ 4、头 4 字节为魔数）通过才返回；**任一条件不满足就 `clear()` 清掉脏记录并返回 null**。
  - `checkAndPrefetch()` 命中 `cachedApkForVersion` 才把状态置为 `Downloaded`，否则重新下载。
- **接入方该怎么做**：直接用 `cachedApk()` / `status` 判断能否安装，**不要自己缓存路径字符串**；拿到 `Downloaded.apkFile` 后仍应在 `install()` 前确认 `apkFile.isFile`（参见 09 手册示例）。魔数是 cheap sanity check，不是防盗校验——发布 APK 请照常签名。

## 3. 下载失败（HTTP 错误 / 截断 / 非 APK body）

- **现象**：`downloadInForeground()` 抛 `DownloadException`；UI 一直停在 loading 或显示「下载失败」。
- **原因**：CDN 返回 4xx/5xx、返回 HTML 错误页（200 但内容非 APK）、或传输中途断开导致字节不全。
- **SDK 的处理**：
  - 非 `200/206` → `DownloadException("download failed with HTTP $code")`，零写入，`.part` 保留（若之前有）。
  - 空 body → `DownloadException("download returned an empty body")`。
  - 字节数不符 → `DownloadException("truncated download: received X of Y bytes")`。
  - 非 APK 头 → 删 `.part` 并抛（见第 2 条）。
  - `UpdateDownloadWorker.doWork()` 捕获异常（除 `CancellationException` 外）→ `Result.failure(workDataOf(OUTPUT_ERROR to message))`；`Updater.downloadInForeground()` 等 unique work 到达终态后，若非 `SUCCEEDED` 就读 `OUTPUT_ERROR` 抛 `DownloadException`。**worker 不自动 retry**，用户再点一次时由 `REPLACE` 重新入队。
  - `checkAndPrefetch()` 的静默预下载失败被吞掉（除取消外不抛），状态保持 `UpdateAvailable`，红点仍亮。
- **接入方该怎么做**：`check()` / `checkAndPrefetch()` / `downloadInForeground()` 都可能抛 `Exception`（`ReleaseApiException` / `DownloadException`），用 `try/catch` 映射到 UI 文案；**不要吞掉 `CancellationException`**（要 `throw` 回去）。给用户保留「重试」入口。

## 4. 网络切换（Wi-Fi ↔ 移动数据 / 短暂断网）

- **现象**：下载途中切网或短暂断网，进度停滞，恢复后自动继续。
- **原因**：`UpdateWorkScheduler.constraints()` 设 `NetworkType.CONNECTED`；WorkManager 在约束不满足时挂起 worker，恢复后重新调度。`ApkDownloader` 用 `readTimeout(120s)` / `connectTimeout(30s)` / `callTimeout(0, 无限制)`，避免大文件被默认超时掐断。
- **SDK 的处理**：worker 被系统重跑时，`doWork()` 重新执行 `ApkDownloader.download()`，看到已有 `.part` 就发 `Range` 续传；进度通过 `setProgressAsync` 重新上报。`Updater.downloadInForeground()` 等的是 `state.isFinished` 终态，不会因中途 ENQUEUED/RUNNING 提前返回。
- **接入方该怎么做**：不要在网络切换时手动取消 / 重建 `Updater`；把「下载中」UI 设计成可等待态（ indeterminate 进度）。需要仅 Wi-Fi 下载请自行在调用前判断，SDK 只要求 `CONNECTED`。

## 5. 代理回退（proxy → direct）

- **现象**：用户开着 VPN / 系统代理，某些环境下载连不上或极慢，但换直连就成功。
- **原因**：`ApkDownloader` 建两个 client：`viaProxyClient`（沿用 `Updater.create` 传入的 client 的系统代理 / DNS 配置）与 `directClient`（`.proxy(Proxy.NO_PROXY)`）。系统代理是 VPN 加速浏览器的通道，但不稳定时会卡住下载。
- **SDK 的处理**：`downloadInto()` 按 `[viaProxyClient, directClient]` 顺序尝试。**只有「连接失败」(`DownloadConnectFailure`，含 `SocketTimeoutException`) 且「本次尚未写入任何字节」(`part.length() <= startBytes`) 时才允许切到下一个通道**；HTTP 错误、中途失败绝不重放（避免把 404 / 半截文件重复追加）。
- **接入方该怎么做**：
  - 如果 App 有共享 `OkHttpClient`（带代理 / DNS / 拦截器），**务必通过 `Updater.create(context, config, httpClient)` 传入**；`Updater.create` 会把它发布给 `WorkerHttp`，使前台 worker 也用同一配置。
  - 进程被杀后 worker 先于 `create` 恢复时，`WorkerHttp.resolve()` 回退到裸 `OkHttpClient()`——下载仍能完成，只是那一次丢了代理配置。若代理是刚需，在 `Application.onCreate` 尽早 `Updater.create`。
  - 不要自己再包一层代理重试，交给 SDK。

## 6. POST_NOTIFICATIONS 未授权（API 33+）

- **现象**：Android 13+ 上下载正常进行，但看不到进度通知。
- **原因**：`POST_NOTIFICATIONS` 是运行时危险权限，库 manifest 只是**声明**它，授予必须由 App 在运行时申请。
- **SDK 的处理**：`UpdateNotifier` 照常 `createNotificationChannel` 并构建 `ForegroundInfo`；系统因权限未授予而隐藏通知，但 `setForeground()` 与下载**不受影响**，下载继续跑完。SDK 不弹权限请求、不因未授权而失败。
- **接入方该怎么做**：在合适时机（如进入更新 UI 前）用 `ActivityResultContracts.RequestPermission()` 申请 `android.permission.POST_NOTIFICATIONS`；被拒绝时不要禁用下载，只在 UI 上说明「进度通知已关闭」。**不要**在 app manifest 重复声明该权限（库已合并）。

## 7. 用户拒绝安装 / 安装无结果回调

- **现象**：调用 `install(apk)` 返回 `InstallResult.Launched` 后，用户在系统弹窗点「取消」，App 得不到任何通知；状态可能停在「已发起安装」。
- **原因**：`ApkInstaller` 用 `ACTION_VIEW` + `application/vnd.android.package-archive` + FileProvider URI 拉起系统安装器，确认与结果完全由系统 UI 负责，Android 没有给调用方回传安装结果。
- **SDK 的处理**：`install()` 同步返回 `InstallResult.Launched`（`startActivity` 成功）或 `InstallResult.Failure(message)`（文件缺失、`getUriForFile` 失败、`ActivityNotFoundException`）。`REQUEST_INSTALL_PACKAGES` 是 normal 权限，无运行时弹窗，用户仍逐次确认。**SDK 不做静默安装**（见 [10 篇](10-design-rationale.md)）。
- **接入方该怎么做**：把 `Launched` 当作「已交给系统安装器」而非「安装完成」；UI 提示「请在系统弹窗中确认」，并允许用户在取消后重新点安装。不要假设有 `onActivityResult` / 回调 / 广播。进程可能在用户确认后被系统杀掉，属正常。

## 8. 进程被杀后 WorkManager 恢复

- **现象**：下载中强杀 App / 设备重启，重新打开 App 后下载继续或已完成，路径仍正确。
- **原因**：前台下载是 WorkManager 的持久化 unique work；输入数据（URL、targetName、scope、4 个文案字符串）随 work 持久化，Worker 由系统按类名反射重建。
- **SDK 的处理**：
  - `UpdateWorkScheduler.start()` 把 `INPUT_DOWNLOAD_URL` / `INPUT_TARGET_NAME` / `INPUT_SCOPE` / 文案全部写进 `workDataOf`，不依赖任何进程级变量。
  - worker 用 `updaterCacheDir(applicationContext, scope)` 重新算目录，`ReleaseSelection.sanitizeAssetFileName(targetName)` 重新算文件名，与 `Updater` 侧一致。
  - HTTP client 经 `WorkerHttp.resolve()` 取；若 App 还没重新 `create`，回退裸 `OkHttpClient()`（见第 5 条）。
  - 约束 `NetworkType.CONNECTED` 由 WorkManager 在恢复网络后重新满足。
- **接入方该怎么做**：在 `Application.onCreate` 里**尽早、且每次进程启动都**调用 `Updater.create(...)`（重新填充 `WorkerHttp` 与门面）。不要依赖 `downloadInForeground()` 那次挂起的协程还能回来——进程被杀后它已不存在；重进页面时重新 `observeForegroundProgress()` 观察同名 unique work 即可。

## 9. 同一 App 多 scope 隔离

- **现象**：一个 App 接两个更新源（如主包 + 插件包）时互不干扰，各自独立进度、缓存、红点。
- **原因**：所有可变状态都由 `scope` 派生（见 AGENTS.md 不变量 3）：
  `cacheDir/updater/<scope>/`、SharedPreferences `"<scope>.updater"`、unique work `"<scope>.download"`、通知渠道 `"<scope>.updates"`、通知 id `scope.hashCode()`。
- **SDK 的处理**：每个 `UpdaterConfig` 带自己的 `scope`；两个 `Updater` 实例互不共享 `UpdateStateStore`、`UpdateWorkScheduler`、`UpdateNotifier` 的命名空间。`REPLACE` / `cancel` 只作用于本 scope 的 unique work。渠道 id 不同，进度通知不会串台（`scope.hashCode()` 碰撞最坏只是同渠道视觉叠加，不影响状态）。
- **接入方该怎么做**：多源时给每个源配不同 `scope`（如 `"main"`、`"plugin"`），各自 `Updater.create`、各自收集 `status`、各自点红点。单源用默认 `"self_update"` 即可。scope 用合法文件名片段，不要含 `/ \` 或空格。

## 10. 取消下载

- **现象**：用户点「取消」，进度停止、通知消失，`.part` 保留供下次续传。
- **原因**：`cancelForegroundDownload()` → `UpdateWorkScheduler.cancel()` → `WorkManager.cancelUniqueWork("<scope>.download")`。
- **SDK 的处理**：
  - WorkManager 取消 worker，`CancellationException` 在 `UpdateDownloadWorker` 里被**重新抛出**（不转成 `Result.failure`），由 WorkManager 标记为已取消。
  - 正在等待终态的 `Updater.downloadInForeground()` 的 `first { ...isFinished }` 会因该 work 进入终态（CANCELLED）而继续，随后因非 `SUCCEEDED` 抛 `DownloadException("update download did not complete (...)")`。
  - `.part` 不删除，下次下载续传。
- **接入方该怎么做**：取消时同时 `viewModelScope` 里 `cancel()` 掉观察 progress 与等待下载的协程（避免拿到取消异常当失败弹窗），见 09 手册的 ViewModel 模板。取消后把 UI 复位为 `IDLE` / 可重新下载态。

## 11. 重复点击下载

- **现象**：用户在下载中反复点「更新」，不会排队出多个 worker、不会重复写同一文件。
- **原因**：`enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)`——同名 work 再次入队会替换前一个。
- **SDK 的处理**：`REPLACE` 让新请求顶掉旧请求；被顶掉的 worker 取消后，新 worker 看到 `.part` 续传，代价很小。`downloadInForeground()` 用 `getWorkInfosForUniqueWorkFlow(...).first { infos.isNotEmpty() && infos.all { it.state.isFinished } }` 只认一次终态。
- **接入方该怎么做**：仍应在 UI 层禁用按钮（用 `job?.isActive` / 状态机），避免无意义地反复 `REPLACE`；但即使漏判，SDK 也不会产生并发写坏文件。不要自己用 `KEEP` / `APPEND` 改策略。

## 12. 并发调用（多个 `check` / `download` 同时发生）

- **现象**：同时发起多次 `check()`、或手动 `download()` 与 `checkAndPrefetch()` 的静默预下载撞在一起。
- **原因**：`check()` 是无状态的网络读取，彼此独立；`download()`（inline）与 `checkAndPrefetch()` 的静默预下载**都会写同一个 `target` 文件**（`updaterCacheDir` + sanitized asset 名）。
- **SDK 的处理**：
  - `Updater` 内部没有对 `check()` / `download()` 加全局互斥锁；`status` 用 `MutableStateFlow` 收敛，最后一次赋值生效。
  - 前台下载由 unique work 串行化（第 11 条）；但**inline `download()` 与 `checkAndPrefetch()` 之间 SDK 未做互斥**。
  - 两个写同一 `target` 的下载各自用 `.part` + `ATOMIC_MOVE / REPLACE_EXISTING` 原子改名，最终落盘的是一个完整 APK，不会拼出半截文件；但 `UpdateStateStore` 的 `saveDownloadedApk` 可能发生覆盖写。
- **接入方该怎么做**：**同一时刻只让一条下载链路在运行**——推荐只用 `checkAndPrefetch()` 做静默预下载 + `downloadInForeground()` 做用户触发下载，二者不要同时对一个 asset 启动；在 ViewModel 里用单个 `job` 串行化（模板已这么做）。`check()` 可并发，但结果以最后一次 UI 渲染为准。

## 13. R8 / 混淆

- **现象**：debug 包正常，release（minify）包一点下载就崩 / worker 起不来。
- **原因**：WorkManager 按持久化类名反射实例化 `UpdateDownloadWorker(context, WorkerParameters)`；R8 改写类名 / 裁掉构造就会失败。
- **SDK 的处理**：`updater/consumer-rules.pro` 随 AAR 分发：
  `-keep class dev.cuculus.update.UpdateDownloadWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }`
  该文件通过 `consumerProguardFiles("consumer-rules.pro")` 打进 AAR，自动作用于消费方 R8。`UpdateDownloadWorker` 保持 public、两参构造签名不变。
- **接入方该怎么做**：不要在自己的 `proguard-rules.pro` 里覆盖 / 删除这条 keep；自定义了 `WorkerFactory` 时也要保留该构造。发布前用 `assembleRelease` + 真机验证一次前台下载（见 [11 篇](11-testing-and-build.md)）。

## 14. API 26 → 37 差异（前台服务类型 / 通知权限 / 安装未知来源）

- **现象**：不同 Android 版本上「通知 / 前台服务 / 安装」的表现与要求不同。
- **原因**：平台权限模型演进。
- **SDK 的处理**（逐版本精确行为）：
  - **≥ 34（UPSIDE_DOWN_CAKE）**：`UpdateNotifier.foregroundServiceType()` 返回 `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`；库 manifest 合并 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` 权限与 `SystemForegroundService` 的 `foregroundServiceType="dataSync"`（缺一会在 API 34+ 抛 `MissingForegroundServiceTypeException`）。
  - **< 34**：`foregroundServiceType()` 返回 `0`，不声明 type。
  - **≥ 33**：`POST_NOTIFICATIONS` 需在运行时授权，未授权仅隐藏通知（见第 6 条）。
  - **≥ 26（minSdk）**：通知渠道必建，`UpdateNotifier.ensureChannel()` 用 `IMPORTANCE_LOW` 建 `"<scope>.updates"`；重复创建是 no-op。
  - **安装未知来源**：`REQUEST_INSTALL_PACKAGES` 在库 manifest 声明（normal 权限，无运行时弹窗）；**没有它，MIUI / HyperOS 会用「无权限」拒绝 `ACTION_VIEW` 而不弹安装确认**。用户仍需在系统弹窗逐次确认；部分 ROM 首次会引导打开「允许安装未知应用」开关，这是系统行为，SDK 不干预。
- **接入方该怎么做**：`compileSdk` 必须 ≥ 37（AAR 的 `minCompileSdk` 硬约束）；`targetSdk` 建议 ≥ 34 并按第 6 条申请通知权限；**不要**按版本在 app manifest 里手写这些条目，库已合并。真机矩阵至少覆盖 API 26 / 33 / 34+ 各一台。

## 15. 磁盘不足

- **现象**：下载途中失败，抛 `DownloadException`，`.part` 保留。
- **原因**：写 `.part` / `Files.move` 时 `IOException`（ENOSPC）冒泡。
- **SDK 的处理**：`streamFrom` 的磁盘写异常不在「连接失败」集合内，会被 `downloadInto`/`download` 作为一般失败向上抛（`DownloadException`），不会误触发代理回退（回退只认连接失败且零字节写入）。SDK **不预检查可用空间、不自动清理旧 APK**。
- **接入方该怎么做**：在触发下载前用 `StatFs` 估算（APK `sizeBytes` 来自 `ReleaseAsset.sizeBytes`，注意只是参考值）+ 预留余量；失败文案提示「存储空间不足」，并提供清理缓存入口（删除 `cacheDir/updater/<scope>/` 下旧 `.apk` / `.part`）。

## 16. 磁盘缓存被系统清理

- **现象**：系统 / 用户清理了 App 缓存后，明明「已下载」的状态失效，重新下载或红点回到「可更新」。
- **原因**：APK 放在 `context.cacheDir` 下，系统可在存储压力时回收；`SharedPreferences "<scope>.updater"` 里的路径记录还在，但文件没了。
- **SDK 的处理**：`UpdateStateStore.cachedApkForVersion()` 读记录时跑 `isValidApk(file)`，文件不存在 / 非 APK 就 `clear()` 脏记录并返回 null（第 2 条）。`checkAndPrefetch()` 因此走重新下载分支；`status` 从 `Downloaded` 回到 `UpdateAvailable` → `Downloaded`。**不会把失效路径交给安装器**。
- **接入方该怎么做**：不要依赖「下载完的 APK 永久可用」；每次从 `status` / `cachedApk()` 现取现用，缺失就重新下载。需要长期保留请自行把 APK 复制到 `filesDir`（那时 `fileProviderAuthority` 指向的 `updater_file_paths.xml` 需同步暴露该子树，见 AGENTS.md 不变量 2）。

## 17. asset 名含非法字符的 sanitize

- **现象**：Release 资产名带 `/`、`\`、空格、中文等字符时，仍能安全落盘、不会逃出缓存目录。
- **原因**：`ReleaseSelection.sanitizeAssetFileName(name)` 用 `Regex("[^A-Za-z0-9._-]")` 把非法字符替换成 `_`（`a/b\c.apk` → `a_b_c.apk`）。
- **SDK 的处理**：`Updater.download()`、`checkAndPrefetch()`、`downloadInForeground()`（worker 内 `targetName`）**都**经 `sanitizeAssetFileName` 生成文件名，三处一致；`UpdaterPaths.updaterCacheDir()` 再把文件约束在 `cacheDir/updater/<scope>/` 内，防止路径穿越。测试 `asset file names are stripped of path characters` 覆盖此行为。
- **接入方该怎么做**：直接用 `ReleaseAsset.name` 展示给用户即可，落盘文件名交给 SDK；不要自己拼路径。若两个不同 asset 清洗后同名，会互相覆盖 `target`——给不同版本用不同 asset 名（版本号后缀本来就不同）。

---

## 附：异常类型速查

| 类型 | 继承 | 抛出场景 |
|---|---|---|
| `UpdaterException` | `IOException` | 所有 SDK 上报失败的基类 |
| `ReleaseApiException(statusCode, message)` | `UpdaterException` | GitHub API 非 2xx（404 = 无已发布 release；403/503 = 限流 / 服务错误，message 取 JSON `message`） |
| `DownloadException(message)` | `UpdaterException` | 连接 / HTTP 状态 / 截断 / 非 APK body / 前台 worker 终态非成功 |

`checkAndPrefetch()` 吞掉除 `CancellationException` 外的一切异常（静默预取），其余 `suspend` 方法会把异常交给调用方。
