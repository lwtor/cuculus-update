# 05 · 发布契约（App 维护者必读）

> 目的：规定**发布方**（打 APK、建 GitHub Release 的人）必须遵守什么，SDK 才能正确检查、下载、安装。接入方也读本篇来定位「为什么不弹更新」。末尾有**可直接抄的发布检查清单**。
> SDK 侧签名见 `03-api-reference.md`；版本编码与 `versionResolver` 见 `04-configuration.md`。

---

## 0. 契约总览

SDK 对发布侧只有一条数据通道：匿名 `GET https://api.github.com/repos/{owner}/{repository}/releases/latest`，拿到 JSON 后按固定规则挑 asset、算 versionCode、下载、交给系统安装器。**任何不符合下列约定的发布，都不会触发更新，且不会有任何回调告诉你「为什么」**——排查时按本文逐条对照。

---

## 1. GitHub `releases/latest` 语义

- 请求：`GET /repos/{owner}/{repository}/releases/latest`，请求头 `Accept: application/vnd.github+json`、`X-GitHub-Api-Version: 2026-03-10`、`User-Agent: cuculus-update`，**无 `Authorization`（匿名访问）**。
- GitHub 该端点只返回**最新一个已发布的正式 Release**：**草稿（draft）与预发布（prerelease）都不算**，按创建时间取最新的非草稿、非预发布版本。
- 仓库没有任何已发布正式 Release 时返回 `404` → SDK 抛 `ReleaseApiException(404, "no published release")`。
- 其它非 2xx（如限流 `403/503`）→ `ReleaseApiException(statusCode, message)`。
- `check()` 抛出的异常会向上传播（`checkAndPrefetch()` 内部吞掉）；接入方应 `catch (ReleaseApiException)`（见 `02-integration.md`）。

**确定规则**
- ✅ 必须有且持续维护**一个最新正式 Release** 作为更新源。
- ❌ 不要把草稿或预发布当更新源——它们永远不会被读到。
- ❌ 不要高频轮询 `check()`：匿名请求受 GitHub 未认证速率限制（通常 60 次/小时/IP），超限会得到非 2xx → `ReleaseApiException`。建议在启动 / 用户主动检查时调用，不要做秒级轮询。
- `owner` / `repository` 来自 `UpdaterConfig`，必须与发布仓库**逐字一致**。

---

## 2. tag 命名

| 场景 | 规则 |
|---|---|
| 使用 `TagScheme()` / `Or(..., TagScheme())` 兜底 | tag 形如 `vMAJOR.MINOR[.PATCH]`，前导 `v` / `V` 可有可无；按 `.` 切分，**2 或 3 段**；每段 `0..999` 的整数 |
| 只用 `AssetNameSuffix()` | tag 不参与 versionCode 计算（被忽略），但仍建议用清晰的 `v1.9.3` 便于人工识别 |

`TagScheme` 实例：
- `v1.9.3` / `1.9.3` → `1_009_003`
- `v1.6` → `1_006_000`（缺 PATCH 当 0）
- `v999.999.999` → `999_999_999`（上限）
- `v1` / `v1.6.0.4` / `v1.1000.0` / `latest` / `vX.Y.Z` → `null` → `UpdateCheck.NoInstallableAsset`

**确定规则**：tag 用 `vMAJOR.MINOR.PATCH`，各段 ≤ 999；不要用 `latest`、`release-2026-11-15` 这类非数字 tag 指望 `TagScheme` 解析。

---

## 3. APK asset 命名约定

推荐名（与 `TagScheme` 的 `v1.9.3 → 1_009_003` 对应）：

```
venera-native-v1.9.3-1009003.apk
```

### 3.1 硬性规则

1. **必须以 `.apk` 结尾**（忽略大小写）。`ReleaseSelection.pickUpdateApk` 先按 `name.endsWith(".apk", ignoreCase = true)` 过滤；不结尾的不是候选。
2. **versionCode 必须是「最后一个分隔符之后、扩展名之前」的那一段**，且能解析为 `Long`。默认分隔符是 `'-'`（`AssetNameSuffix(separator = '-')`）。
   - ✅ `app-v1.9.3-1009003.apk` → `1009003`
   - ✅ `app-9.apk` → `9`
   - ❌ `app-v1.9.3-1009003-signed.apk` → 最后一段是 `signed`，解析失败 → `null` → `NoInstallableAsset`
   - ❌ `venera-native-v1.9.3.apk` → 最后一段是 `v1.9.3`，解析失败
3. **分隔符之后不要再追加任何 `-xxx` 后缀**（`-signed`、`-universal`、`-arm64` 等）；要区分就放到 versionCode **之前**。
4. asset 名建议只用 ASCII `[A-Za-z0-9._-]`。落盘前 `ReleaseSelection.sanitizeAssetFileName` 会把其它字符（空格、斜杠、非 ASCII）替换成 `_`；这**不影响** versionCode 解析（解析用的是原始 `asset.name`），但会让缓存文件名和你预期的不一致，增加排查成本。
5. `browser_download_url` 必须是一个以 `http` 开头的可匿名访问 URL；`parseRelease` 会丢弃 `name` 为空或 URL 不以 `http` 开头的 asset。

### 3.2 解析链路（为什么这样命名）

```
asset.name = "venera-native-v1.9.3-1009003.apk"
   │ substringBeforeLast('.', ...) → stem = "venera-native-v1.9.3-1009003"
   │ substringAfterLast('-')       → "1009003"
   │ toLongOrNull()                → 1009003L
   ▼
ReleaseSelection.isUpdate(1009003, installedVersionCode) → 严格大于才更新
```

> 落盘文件名是 `sanitize(asset.name)`，即 `cacheDir/updater/<scope>/venera-native-v1.9.3-1009003.apk`；断点续传用同名 `.apk.part`。

---

## 4. versionCode 映射与单调递增

### 4.1 公式

```
versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH
每段 0..999，最大 999_999_999（< Int.MAX_VALUE，能放进 BuildConfig.VERSION_CODE）
```

| versionName | tag | versionCode | asset 后缀 |
|---|---|---|---|
| `1.9.3` | `v1.9.3` | `1_009_003` | `-1009003.apk` |
| `1.10.0` | `v1.10.0` | `1_010_000` | `-1010000.apk` |
| `2.0.0` | `v2.0.0` | `2_000_000` | `-2000000.apk` |

### 4.2 铁律

- SDK 只做一次比较：**release 的 versionCode 严格大于 `BuildConfig.VERSION_CODE.toLong()`** 才返回 `Available`（`ReleaseSelection.isUpdate`）。不比较 tag 文本、不比较 `publishedAt`、不比较 asset 名。
- 因此 **App 的 `defaultConfig.versionCode`、发布 tag、APK 文件名后缀三者必须用同一套公式**。任一处不一致 → 永远 `UpToDate` 或 `NoInstallableAsset`。
- 每个新 Release 的 versionCode 必须**严格递增**、只增不减、不复用。
- 若用 `Or(AssetNameSuffix(), TagScheme())`，**文件名后缀的数字必须与 tag 推导出的数字一致**：asset 名方案是 primary，两者不一致时会按错误的 code 比较。
- 各段保持 ≤ 999：`MINOR=1000` 会与 `MAJOR=2,MINOR=0` 撞车（非单调），`TagScheme` 会直接返回 `null`。

---

## 5. APK 必须正式签名且签名一致

- Android 系统安装器**强制要求**更新 APK 与已安装 App **使用同一签名密钥**，否则拒绝安装。
- 必须用 **release 正式签名**（不要传 `debug` 签名的 APK 给线上用户）。
- SDK 侧只校验文件头 4 字节 `PK\x03\x04`（`ApkDownloader.verifyApkHeader` / `isValidApk`），**不校验签名**；签名不一致的失败发生在系统安装弹窗里，SDK 不知情。
- `install()` 返回 `InstallResult.Launched` 只代表「已把 APK 交给系统安装器」，**不代表安装成功**；没有安装结果回调。

**确定规则**：用与线上版本完全相同的 keystore / 签名配置打 Release APK；换签名等于换 App，用户必须先卸载。

---

## 6. 多个 APK asset 时的挑选规则

`ReleaseSelection.pickUpdateApk(assets)`（纯规则，无 I/O）：

```
1. 过滤：name.endsWith(".apk", ignoreCase = true)
2. 排序：compareByDescending { contentType == "application/vnd.android.package-archive" }
          .thenByDescending { sizeBytes }
3. 取 firstOrNull()
```

- 优先选 GitHub 标记为 Android 包的 asset（`content_type == application/vnd.android.package-archive`；上传 `.apk` 时 GitHub 通常自动设置），再选体积最大的。
- 非 `.apk` asset（`notes.txt`、`source-code.zip`）直接忽略。
- 没有 `.apk` → `UpdateCheck.NoInstallableAsset`。

**确定规则**
- ✅ **一个 Release 只上传一个目标 APK**，最确定、最省心。
- ❌ 不要上传多个 ABI / 渠道分包 APK 指望 SDK 挑对的：SDK **不按 ABI / 渠道 / 设备挑选**，只按 contentType + 体积，可能挑错。
- 若确有多个 `.apk`，确保目标 APK 是 GitHub 标记为该 contentType 且体积最大的那个；否则挑选结果不确定。
- 不要把同一个 versionCode 的多个变体混在一个 Release 里。

---

## 7. 预发布 / 草稿

- `/releases/latest` **不返回** `draft: true` 与 `prerelease: true` 的 Release。
- 把版本标记为预发布 / 留成草稿 → 用户端完全看不到更新（`check()` 要么拿到上一个正式版，要么 404）。
- 灰度请自建渠道（不同 `owner/repository` + 不同 `scope`，见 `04`），不要依赖 GitHub 预发布标记。

**确定规则**：要让用户收到更新，就必须 **Publish release** 且 **不勾选 pre-release**。

---

## 8. `REQUEST_INSTALL_PACKAGES` 与安装确认

- 权限由**库清单合并**，App 不需要声明；它是普通权限，**没有运行时授权弹窗**。
- 安装走 `Intent.ACTION_VIEW` + FileProvider `content://` URI，系统安装器自己弹「安装未知应用 / 确认安装」对话框，**用户逐次确认**。
- 在 Android 8.0（API 26）及以上，设备可能要求用户先在「设置 → 安装未知应用」里对该 App 允许一次；这是系统/OEM 行为，SDK 无法代劳。
- 部分设备 / Play Protect 可能对侧载 APK 给出风险提示，属系统行为。
- 注意 `POST_NOTIFICATIONS` 不同：库虽声明，但 **API 33+ 仍需 App 运行时申请**；未授权时下载照常进行，只是进度通知隐藏（见 `01-architecture.md` §7.2、`02-integration.md` 第 4 步）。

**确定规则**：不要试图做静默安装，也不要在 `install()` 之后等「安装成功」回调——都不存在。

---

## 9. 代理与直连回退

`ApkDownloader` 由传入的 `OkHttpClient` 派生两个客户端，每次下载尝试按序使用：

| 顺序 | 客户端 | 说明 |
|---|---|---|
| 1 | `viaProxyClient` | 使用设备系统代理（用户的 VPN / 代理通常从这里生效） |
| 2 | `directClient` | `Proxy.NO_PROXY` 直连 |

- **只有**「连接失败 / `SocketTimeoutException`」且「尚未写入新字节」（`part.length() <= startBytes`）时才切换到直连。
- **HTTP 4xx/5xx 与传输中途失败绝不重放**（避免把半截内容重复追加）。二者都变成 `DownloadException`。
- 两者都是 `callTimeout = 0`、`connectTimeout = 30s`、`readTimeout = 120s`。
- 断点续传：存在 `.apk.part` 就发 `Range: bytes=<n>-`，期待 `206 Partial Content`；服务端若忽略 Range 回 `200`，则从 0 重写。
- 完成后校验 ZIP 头 `PK\x03\x04`；把 HTML 错误页当 200 返回 → `DownloadException("the downloaded file is not an Android package")`，`.part` 被删除。

**确定规则（发布侧）**
- ✅ asset 的 `browser_download_url` 必须是**可匿名 HTTPS 访问**的直链（GitHub release CDN 天然满足），不要放在需要登录 / Cookie / 临时签名的地址后面。
- ✅ 服务端应支持 `Range` 并返回 `206`（GitHub release CDN 支持）。
- ✅ 不要返回 `200 + HTML 错误页`（会被判为非 APK）。
- ✅ 若 App 配置了自定义 `OkHttpClient`（proxy/DNS），`Updater.create(..., httpClient = ...)` 会把它写入 `WorkerHttp`，前台 worker 也用它（见 `01-architecture.md` §6.4）。

---

## 10. 发布检查清单（可直接抄）

打新版本前逐项确认：

- [ ] 目标仓库已 **Publish release**（非草稿、未勾选 pre-release），且它是 `/releases/latest` 会返回的最新正式版。
- [ ] `UpdaterConfig.owner` / `repository` 与实际发布仓库逐字一致。
- [ ] tag 为 `vMAJOR.MINOR.PATCH`（如 `v1.9.3`），各段 `0..999`、纯数字。
- [ ] APK asset 名以 `.apk` 结尾，且**最后一段是 versionCode**（如 `...-1009003.apk`），其后没有额外 `-signed` / `-arm64` 等后缀。
- [ ] asset 名只用 ASCII `[A-Za-z0-9._-]`，无空格 / 斜杠 / 中文。
- [ ] `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`，且**严格大于**当前线上版本与 App 的 `BuildConfig.VERSION_CODE`。
- [ ] 文件名后缀的数字与 tag 推导出的数字**一致**（使用 `Or(AssetNameSuffix(), TagScheme())` 时必查）。
- [ ] App 的 `defaultConfig.versionCode` 与发布端使用同一套公式。
- [ ] APK 用**与线上版本相同的 keystore 做 release 正式签名**（非 debug）。
- [ ] 一个 Release 只上传**一个**目标 APK；若多个，目标是 GitHub 标记为 `application/vnd.android.package-archive` 且体积最大的那个。
- [ ] `browser_download_url` 可匿名访问、支持 `Range` / `206`，不会返回 `200 + HTML`。
- [ ] App 的 `fileProviderAuthority` = `${applicationId}.updater`（默认 `"$packageName.updater"`）。
- [ ] 已引导 API 33+ 用户授予 `POST_NOTIFICATIONS`（否则下载仍跑但看不到进度通知）。
- [ ] 设备已允许该 App「安装未知应用」（首次安装时）。

---

## 11. 该做什么 / 不该做什么 / 常见误判

**该做什么**
- 维护一个最新正式 Release；tag、asset 后缀、App versionCode 三者同公式且严格递增。
- 一个 Release 只放一个正式签名的 APK。
- 用公开 HTTPS 直链、支持 Range。
- 发版前跑一遍 §10 检查清单。

**不该做什么**
- ❌ 不要用草稿 / 预发布作为更新源。
- ❌ 不要在 versionCode 之后再追加 `-xxx` 后缀。
- ❌ 不要传 debug 签名或换签名密钥的 APK。
- ❌ 不要放多个 ABI / 渠道 APK 指望 SDK 智能挑选。
- ❌ 不要只改 `versionName` / tag 文本却不改 versionCode。
- ❌ 不要把 APK 放在需登录 / 临时签名才能下载的 URL 后面。

**常见误判**

| 误判 | 真相 |
|---|---|
| 「把 Release 存成草稿先测试」 | 草稿对 `/releases/latest` 不可见，用户端 404 或拿到旧版。 |
| 「勾了 pre-release 就是灰度」 | 预发布同样被 `latest` 排除。 |
| 「文件名 `app-1009003-signed.apk` 也能读」 | 最后一段是 `signed`，`AssetNameSuffix` 返回 `null` → `NoInstallableAsset`。 |
| 「上传多个 ABI APK 更保险」 | 按 contentType + 体积挑一个，不按 ABI，可能挑错。 |
| 「改了 tag 从 `v1.9.3` 到 `v1.9.4` 就会更新」 | 只有 versionCode 严格变大才触发。 |
| 「`install()` 返回 `Launched` 说明装好了」 | 只说明拉起了系统安装器；签名不符 / 用户取消发生在系统弹窗，SDK 无回调。 |
| 「进度通知不显示说明下载挂了」 | API 33+ 未授予 `POST_NOTIFICATIONS` 时下载照常，仅通知隐藏。 |
| 「匿名 API 可以无限轮询」 | 受 GitHub 未认证速率限制，超限返回非 2xx → `ReleaseApiException`。 |
