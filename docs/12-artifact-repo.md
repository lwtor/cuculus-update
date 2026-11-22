# 12 · 公共制品库与双源更新

> 目的：让接入方在「当前仓库」与「外置公共制品库」之间二选一，并说明如何用发布侧工具建库、如何打 tag、demo 怎么测。
>
> 本文是新增能力 `UpdateSourceMode` 的专题文档；基础接入仍以 `02-integration.md` 为准，API 签名以 `03-api-reference.md` 为准。

## 1. 为什么需要它

默认更新源是「本 App 自己的仓库」（`owner`/`repository` 的 latest release）。这在单一仓库场景下最简单，但有两个现实问题：

1. **聚合**：多个 App 的 APK 分散在各自仓库，接入方要为每个 App 单独配地址、单独放行网络。
2. **可达性**：主仓库若因网络 / 权限原因不可达，更新就失效。

公共制品库把多个 App 的 APK 放进**一个公开仓库**，用 **tag 前缀**区分 App，App 端匿名只读：

```
cuculus-artifacts/                       ← 一个公开仓库
  video-archive/v1.9.0  (tag) → APk     ← namespace = "video-archive"
  notes-app/v2.3.0      (tag) → APk     ← namespace = "notes-app"
```

SDK 遍历该仓库的 `/releases`（GitHub 按发布时间倒序），取第一个 tag 以 `<namespace>/` 开头且非 draft / 非 prerelease 的 release。

## 2. 配置：一个参数二选一

`UpdaterConfig` 新增三个字段，全部有默认值，**老接入方零改动、零感知**：

| 字段 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `sourceMode` | `UpdateSourceMode` | `CURRENT_REPO` | `CURRENT_REPO` = 读本仓库 latest；`ARTIFACT_REPO` = 读公共制品库 |
| `artifactRepo` | `ArtifactRepoRef?` | `null` | 制品库坐标；`null` → 内置公共库 `lwtor/cuculus-artifacts` |
| `artifactNamespace` | `String?` | `null` | tag 前缀；`null` → 用 `repository` 的值 |
| `apiBaseUrl` | `String` | `https://api.github.com` | GitHub Enterprise 可覆盖；匿名读 unaffected |

```kotlin
// 读本仓库（与旧版行为完全一致）
val current = UpdaterConfig(
    owner = "lwtor", repository = "video-archive",
    fileProviderAuthority = "$packageName.updater",
)

// 读公共制品库，匹配 tag 前缀 video-archive/*，地址与前缀都可覆盖
val artifact = UpdaterConfig(
    owner = "lwtor", repository = "video-archive",          // repository 同时作为默认 namespace
    fileProviderAuthority = "$packageName.updater",
    sourceMode = UpdateSourceMode.ARTIFACT_REPO,
    artifactRepo = ArtifactRepoRef("my-org", "apk-mirror"), // 省略则用内置公共库
    artifactNamespace = "video-archive",                    // 省略则用 repository
)
```

**命名空间如何决定版本**：制品库 tag 形如 `video-archive/v1.9.0`。SDK 在把 tag 交给 `versionResolver` 前会剥掉 `<namespace>/` 前缀，所以默认的 `AssetNameSuffix()`（读 asset 名尾部数字）与 `TagScheme()`（读 `v1.9.0`）都能正常工作。`scope` 仍按原规则隔离落盘；建议两个源用不同 `scope`，避免一个源缓存的 APK 被另一个源命中。

## 3. 发布侧建库（ArtifactRepoProvisioner）

**创建仓库是写操作、需要 token，绝不能放进 App 运行时**。SDK 提供发布侧工具，供 CI / 构建脚本调用：

```kotlin
val result = ArtifactRepoProvisioner.ensureRepository(
    client = OkHttpClient(),
    token = System.getenv("GITHUB_TOKEN"),   // CI secrets，绝不打包进 App
    owner = "lwtor",
    repository = "cuculus-artifacts",
)
// Result.AlreadyExists / Result.Created —— 幂等：GET 命中即跳过，仅 404 时 POST 建公开仓库
```

- 个人账号用 `POST /user/repos`；组织传 `ownerIsOrganization = true` 走 `POST /orgs/{org}/repos`。
- 建的是 **public** 仓库（`private = false`），所以 App 端读取完全匿名、零凭证。
- 典型 CI 流程：建库（幂等）→ 打带 namespace 前缀的 tag → 上传 APK asset → App 端即可读到。

打 tag 与上传 asset 用 GitHub Actions 的 `softprops/action-gh-release` 即可：

```yaml
- uses: softprops/action-gh-release@v2
  with:
    tag_name: video-archive/v1.9.0
    files: app-release.apk
```

## 4. Demo 怎么测

`sample` 已接入双源切换：页面顶部「更新源配置」区可切「当前仓库 / 公共制品库」，并覆盖 owner / repository / namespace，点「应用更新源配置」后 `SampleApp` 重建 `Updater`（不同源用不同 `scope`，缓存天然隔离）。

两个测试库都已就绪，装好 demo APK 后两条路径都能开箱实测（demo 本地 `versionCode=18`，release 里的包也是 18，所以 check 会显示 UpToDate；用「下载最新版本」按钮可反复验证读取 + 下载，它不校验版本）：

- **测 CURRENT_REPO**：切到「当前仓库」（默认）。读 `lwtor/cuculus-update` 的 latest release `demo-v1.3.6`，asset `cuculus-update-demo-v1.3.6-18.apk`。
- **测 ARTIFACT_REPO**：切到「公共制品库」——输入框会自动清空，SDK 回落到内置公共库 `lwtor/cuculus-artifacts` + tag 前缀 `cuculus-update`（即 `renderSourceState` / `onModeSwitched` 的默认值）。该库已有 release `cuculus-update/v1.3.6`，asset 同为 `cuculus-update-demo-v1.3.6-18.apk`。

> 想测「发现新版本 → 下载 → 安装」完整链路：装一个旧包（如 release `demo-v1.3.5` 的 `-17.apk`），再让 check 读到 release 里的 `18`；两条路径的 release 都保留了各版本 asset。这两个 v18 包携带 SDK 1.1.1 的下载优化，适合真机验证分段续传 / 大文件 8 段 / 代理恢复。

## 5. 行为与边界

| 场景 | SDK 行为 |
|---|---|
| 制品库没有任何匹配 tag 的 release | `check()` / `latestInstallableAsset()` 抛 `ReleaseApiException(404, "no tagged release ...")` |
| 某匹配 tag 是 draft / prerelease | 跳过，继续找下一个 |
| 第一页 100 条都不是本 App | 自动翻到下一页，直到找到或读完 |
| 未传 `artifactRepo` 又用了 ARTIFACT_REPO | 读内置公共库 `lwtor/cuculus-artifacts` |
| `artifactNamespace` 留白 | 用 `repository` 作为前缀 |
| 老接入方未设 `sourceMode` | 等同 `CURRENT_REPO`，行为与 1.0.0 完全一致 |

## 6. 不变量（不要破坏）

- 公共制品库必须是 **public**：App 端全程匿名，不要试图在 App 里注入 token。
- 发布侧 token 只存在于 CI secrets；`ArtifactRepoProvisioner` 不得在 App 代码路径被调用。
- tag 前缀匹配是 `<namespace>/` 严格前缀（带斜杠），`video-archive2/...` 不会被 `video-archive` 误命中。
- `api.github.com` 的匿名 `/releases` 有 rate limit；大量 App 高频轮询时接入方应自行节流 / 用 Enterprise 地址。
