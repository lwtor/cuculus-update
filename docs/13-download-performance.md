# 13 · 下载性能与代理恢复

> 进阶篇。说明下载器为什么默认并发、`.part` 续传如何避免退化回单流、大文件如何自动加段，以及进程被杀后代理配置怎样恢复。本文对应 `ApkDownloader.kt` / `WorkerHttp.kt` 的当前实现。

## 1. 为什么默认并发

GitHub Releases 的资源 CDN 按**单连接**限速很常见：一条流跑到几十到一百多 KB/s，而四条独立连接能把带宽用满。实测同一个 3.8 MB APK、同一条链路：

| 方式 | 耗时 | 说明 |
|---|---|---|
| 单流 | 15.6 s | 约 244 KB/s |
| 4 段并发（默认） | 6.3 s | 约 614 KB/s |
| 8 段并发 | 4.0 s | 约 963 KB/s |

因此只要知道资源总大小（`ReleaseAsset.sizeBytes`），下载器就把 `[startBytes, total)` 切成不相交的区间，每段一个**独立的 OkHttpClient + 独立连接池**（共享连接池会把多个 Range 复用同一条 socket，并发就失效了）。

## 2. `.part` 续传不再退化成单流

旧实现要求 `startBytes == 0` 才允许分段。单流路径失败时会**故意保留 `.part`** 以便续传，于是下次再点下载时 `startBytes > 0`，分段条件永远不成立——用户经历一次网络抖动后，之后每次下载都退回 15.6 s 的单流路径。

当前实现去掉了 `startBytes == 0` 的限制：

- 已下载的前缀原样保留，只把剩余区间 `[startBytes, total)` 切段并发；
- `SegmentProgress` 以 `startBytes` 为初值，进度上报不会从 0 倒退；
- 不再 `setLength(total)` 覆盖文件，最后一段的位置写入自然把文件扩展到完整长度；
- `.part` 已经达到或超过 `total` 的（资源被替换过的陈旧文件）仍拒绝分段，走单流的 416 检测后从头重下。

另外，分段响应会校验 `Content-Range` 声明的总长度与计划值一致。不一致说明资源在 API 调用与下载之间被换掉了，拼接会产出损坏的 APK，此时整段失败并回落到单流完整重下。

## 3. 段数自适应

`ApkDownloader` 暴露两个构造参数（SDK 内部使用，接入方通过 `Updater` 不直接触碰）：

| 参数 | 默认 | 作用 |
|---|---|---|
| `segmentCount` | 4 | 基准段数 |
| `largeAssetThresholdBytes` | 8 MiB | 超过该大小进入大文件档 |
| `largeAssetSegmentCount` | 8 | 大文件档的段数 |

规则：

- `total >= 8 MiB` → 8 段；更小的资源 → 4 段（避免握手开销超过收益）；
- `segmentCount = 1` 显式关闭分段，全走单流——用于测试，也用于连接建立成本高于带宽收益的网络；
- 段数不会超过剩余字节数，不会出现空区间请求。

## 4. 进程被杀后的代理恢复

WorkManager 的 worker 由类名反射重建，无法构造函数注入 `OkHttpClient`。`Updater.create()` 会调用 `WorkerHttp.install(context, client)`：

1. 把 client 存进进程级 `@Volatile` 变量（热路径直接复用）；
2. 把 client 的代理端点以 `HTTP|host|port` / `SOCKS|host|port` 明文存进 `SharedPreferences`。

冷启动后如果 worker 先于 `Application.onCreate()` 恢复，`WorkerHttp.resolve(context)` 从持久化数据重建一个带同样代理的 client，不会静默退回直连。**持久化范围只有代理端点**：认证凭据、自定义 DNS、自定义 SSL 都不落盘，仍须由接入方在自己的 `Application.onCreate()` 里重新创建 client 并调 `Updater.create()` 覆盖。

> 接入方注意：要享受这条能力，请把 App 全局使用的那个 `OkHttpClient`（已配好代理/DNS）传给 `Updater.create(context, config, httpClient)`，而不是让 SDK 自建裸 client。demo 的 `SampleApp` 演示了传 client 的写法。

## 5. 测试覆盖

- `ApkDownloaderTest`：`.part` 存在时仍按剩余区间分段、大小文件段数自适应、分段上报不同总长度时回落完整流、原有的续传/超时/取消/stale 用例；
- `WorkerHttpTest`：HTTP/SOCKS 代理编解码往返、空值与畸形输入回落无代理。

全部 49 例 JVM 单测通过（`sh gradlew :updater:testDebugUnitTest --console=plain`）。
