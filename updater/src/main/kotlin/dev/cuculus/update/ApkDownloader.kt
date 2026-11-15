package dev.cuculus.update

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A failure establishing the connection; retrying through the other network channel may help.
 * Private to the downloader: [DownloadException] stays a single final public type, and this
 * marker exists only so `downloadInto` can tell "the channel is unreachable" apart from "the
 * server answered with an error", which must never be replayed.
 */
private class DownloadConnectFailure(message: String) : Exception(message)

/** The server rejected our Range: the cached `.part` no longer matches this asset. */
private class StalePartException : Exception("stale partial download")

/** The server answered a ranged request with something other than 206, i.e. it ignored Range. */
private class RangeIgnoredException(code: Int) :
    Exception("the server ignored the range request (HTTP $code)")

/** Byte-level progress of a download; [fraction] is null while the total size is unknown. */
data class UpdateProgress(val bytesDownloaded: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).toFloat() }
}

/**
 * Streams a release APK to a local file with byte-level progress and resumable downloads.
 *
 * The bytes land in a `.part` sibling; if a download is interrupted, the next attempt requests the
 * missing range (`Range: bytes=<resumed>-`) so it continues from where it left off instead of
 * starting over. GitHub's release CDN answers range requests with `206 Partial Content`.
 *
 * When the total size is known and nothing has been downloaded yet, the transfer is split into
 * [segmentCount] ranged requests that run concurrently, each on its own connection. A release CDN
 * commonly caps throughput *per connection*, so a single stream leaves bandwidth unused: measured
 * against GitHub's asset CDN one stream sat at ~90–180 KB/s while four streams reached
 * ~130–480 KB/s on the same link. The segmented path is an optimisation only — if the server
 * ignores `Range`, or the advertised size no longer matches the asset, the download falls back to
 * the plain single stream instead of failing.
 *
 * The download first tries the device's system proxy (which is how the user's VPN accelerates the
 * browser), then falls back to a direct connection when the proxy fails before any progress is
 * made — the two paths are tried per attempt so an unstable proxy does not leave the download
 * permanently stuck.
 *
 * Progress is throttled by *both* bytes and time. A bytes-only throttle (the previous behaviour)
 * meant that at 90 KB/s the UI received its first update only after three seconds and then one
 * every three seconds, which reads as "stuck at 0%" on a slow link.
 */
internal class ApkDownloader(
    baseClient: OkHttpClient,
    private val progressIntervalBytes: Long = DEFAULT_PROGRESS_INTERVAL_BYTES,
    private val progressIntervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
    private val segmentCount: Int = DEFAULT_SEGMENT_COUNT,
    private val minSegmentTotalBytes: Long = MIN_SEGMENT_TOTAL_BYTES,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val viaProxyClient = baseClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val directClient = viaProxyClient.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .build()

    /** Downloads [url] into [target], resuming a partial `.part` if one already exists. */
    suspend fun download(url: String, target: File, onProgress: (UpdateProgress) -> Unit = {}): File =
        download(url, target, null, onProgress)

    /**
     * Downloads [url] into [target]. [totalBytes] is a size hint (the release asset's advertised
     * size); when it is known and non-trivial the transfer is segmented. A wrong hint is harmless:
     * the segmented attempt trips its length check and the single stream runs instead.
     */
    suspend fun download(
        url: String,
        target: File,
        totalBytes: Long?,
        onProgress: (UpdateProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val parent = target.parentFile ?: throw DownloadException("target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw DownloadException("cannot create ${parent.name}")
        }
        val part = File(parent, target.name + ".part")
        val startBytes = if (part.isFile) part.length() else 0L

        if (canSegment(startBytes, totalBytes)) {
            try {
                downloadSegments(url, part, totalBytes!!, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A segmented transfer is only a speed optimisation. Discard the half-written file
                // (its holes make it useless as a resume point) and do one plain stream instead.
                part.delete()
                downloadInto(url, part, 0L, onProgress)
            }
        } else {
            // On success `moveIntoPlace` renames the `.part` into `target`, so nothing is left
            // behind. On a transfer failure the `.part` is deliberately kept: the next attempt
            // resumes from its length instead of starting over. The one exception is a body that
            // is not an APK at all — that is garbage (e.g. an HTML error page served as 200), and
            // resuming it would only append valid bytes to junk, so it is discarded below.
            try {
                downloadInto(url, part, startBytes, onProgress)
            } catch (_: StalePartException) {
                // 416 means the .part is stale — typically the release asset was replaced with a
                // smaller file, or a previous foreground worker left a full-length .part behind.
                // Discard it and fetch from byte zero once, instead of failing forever.
                part.delete()
                downloadInto(url, part, 0L, onProgress)
            }
        }
        try {
            verifyApkHeader(part)
        } catch (error: DownloadException) {
            part.delete()
            throw error
        }
        moveIntoPlace(part, target)
        target
    }

    private fun canSegment(startBytes: Long, totalBytes: Long?): Boolean =
        startBytes == 0L &&
            totalBytes != null &&
            totalBytes >= minSegmentTotalBytes &&
            segmentCount > 1

    /**
     * Fetches the asset as [segmentCount] concurrent ranged requests writing into one preallocated
     * `.part`. Each segment owns a client with its own connection pool, because sharing one
     * connection would put every `Range` on the same (throttled) TCP connection.
     */
    private suspend fun downloadSegments(
        url: String,
        part: File,
        total: Long,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val channel = file.channel
            val progress = SegmentProgress(
                total = total,
                byteInterval = progressIntervalBytes,
                timeIntervalMillis = progressIntervalMillis,
                now = nowMillis,
                onProgress = onProgress,
            )
            coroutineScope {
                segmentRanges(total).map { range ->
                    async {
                        readSegment(segmentClient(), url, channel, range, progress)
                    }
                }.awaitAll()
            }
            val downloaded = progress.downloaded
            if (downloaded != total) {
                throw DownloadException("truncated download: received $downloaded of $total bytes")
            }
            onProgress(UpdateProgress(total, total))
        }
    }

    /** `[0, total)` split into [segmentCount] contiguous, gap-free ranges. */
    private fun segmentRanges(total: Long): List<LongRange> {
        val count = segmentCount.coerceAtLeast(1)
        val base = total / count
        val remainder = total % count
        var next = 0L
        return (0 until count).map { index ->
            val size = base + if (index < remainder) 1L else 0L
            val start = next
            next += size
            start until start + size
        }
    }

    /**
     * One connection per segment: a fresh [ConnectionPool] prevents OkHttp from reusing a shared
     * connection (and multiplexing every `Range` onto it), which would defeat the parallelism.
     */
    private fun segmentClient(): OkHttpClient = viaProxyClient.newBuilder()
        .connectionPool(ConnectionPool(1, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .build()

    private suspend fun readSegment(
        client: OkHttpClient,
        url: String,
        channel: FileChannel,
        range: LongRange,
        progress: SegmentProgress,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${range.first}-${range.last}")
            .build()
        val response = try {
            awaitResponse(client, request)
        } catch (error: IOException) {
            throw DownloadConnectFailure(
                if (error is SocketTimeoutException) {
                    "the download timed out while connecting: ${error.message}"
                } else {
                    "could not reach the download server: ${error.message}"
                },
            )
        }
        response.use {
            if (it.code != 206) throw RangeIgnoredException(it.code)
            val body = it.body ?: throw DownloadException("download returned an empty body")
            val source = body.source()
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var position = range.first
            while (position <= range.last) {
                val want = minOf(buffer.size.toLong(), range.last - position + 1).toInt()
                val read = source.read(buffer, 0, want)
                if (read == -1) break
                writeFully(channel, buffer, read, position)
                position += read
                progress.add(read)
            }
            if (position <= range.last) {
                throw DownloadException(
                    "truncated download: segment ${range.first}-${range.last} " +
                        "ended at ${position - 1}",
                )
            }
        }
    }

    /** Positional write, so concurrent segments never race on the channel's file pointer. */
    private fun writeFully(channel: FileChannel, bytes: ByteArray, length: Int, position: Long) {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        var at = position
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at)
        }
    }

    /** Tries the proxy channel first, then the direct channel when the proxy makes no progress. */
    private suspend fun downloadInto(
        url: String,
        part: File,
        startBytes: Long,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val request = Request.Builder().url(url).get().apply {
            if (startBytes > 0) header("Range", "bytes=$startBytes-")
        }.build()

        val attempts = listOf(viaProxyClient, directClient)
        var lastFailure: String? = null
        for ((index, client) in attempts.withIndex()) {
            try {
                streamFrom(client, request, part, startBytes, onProgress)
                return
            } catch (failure: DownloadConnectFailure) {
                lastFailure = failure.message
                // Only a connect failure justifies switching channels, and only while no bytes
                // have been written. HTTP errors and mid-stream failures must not be replayed.
                val canFallback = index < attempts.lastIndex && part.length() <= startBytes
                if (!canFallback) throw DownloadException(failure.message ?: "download failed")
            }
        }
        throw DownloadException(lastFailure ?: "download failed")
    }

    private suspend fun streamFrom(
        client: OkHttpClient,
        request: Request,
        part: File,
        startBytes: Long,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val response = try {
            awaitResponse(client, request)
        } catch (error: IOException) {
            if (error is SocketTimeoutException) {
                throw DownloadConnectFailure("the download timed out while connecting: ${error.message}")
            }
            throw DownloadConnectFailure("could not reach the download server: ${error.message}")
        }
        response.use {
            val code = it.code
            if (code == 416 && startBytes > 0) throw StalePartException()
            if (code != 200 && code != 206) throw DownloadException("download failed with HTTP $code")

            val body = it.body ?: throw DownloadException("download returned an empty body")
            val totalBytes = parseTotalBytes(it.header("Content-Range"), body.contentLength())
            // The server may ignore our range and send the whole file (200): restart from zero.
            val offset = if (code == 206) startBytes else 0L
            var copied = offset

            RandomAccessFile(part, "rw").use { file ->
                file.seek(offset)
                val source = body.source()
                val buffer = ByteArray(IO_BUFFER_BYTES)
                var lastReported = copied
                var lastReportedAt = nowMillis()
                while (true) {
                    val read = try {
                        source.read(buffer)
                    } catch (error: java.io.InterruptedIOException) {
                        // A proxy that accepts the connection but never delivers a byte used to
                        // stall for the whole read timeout. With zero new bytes it is a connect
                        // failure, so downloadInto() can fall back to the direct channel.
                        if (copied <= startBytes) {
                            throw DownloadConnectFailure(
                                "the download stalled before any bytes arrived: ${error.message}",
                            )
                        }
                        throw error
                    }
                    if (read == -1) break
                    file.write(buffer, 0, read)
                    copied += read
                    val now = nowMillis()
                    if (copied - lastReported >= progressIntervalBytes ||
                        now - lastReportedAt >= progressIntervalMillis
                    ) {
                        lastReported = copied
                        lastReportedAt = now
                        onProgress(UpdateProgress(copied, totalBytes))
                    }
                }
            }
            onProgress(UpdateProgress(copied, totalBytes))
            if (totalBytes != null && totalBytes > 0 && copied != totalBytes) {
                throw DownloadException("truncated download: received $copied of $totalBytes bytes")
            }
        }
    }

    private fun parseTotalBytes(contentRange: String?, contentLength: Long): Long? {
        // "bytes 0-499/500" -> 500, falling back to Content-Length when absent.
        return contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
            ?: contentLength.takeIf { it > 0 }
    }

    private fun verifyApkHeader(file: File) {
        val header = ByteArray(ZIP_LOCAL_FILE_HEADER.size)
        val read = FileInputStream(file).use { it.read(header) }
        if (read != header.size || !header.contentEquals(ZIP_LOCAL_FILE_HEADER)) {
            throw DownloadException("the downloaded file is not an Android package")
        }
    }

    private fun moveIntoPlace(part: File, target: File) {
        try {
            java.nio.file.Files.move(
                part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** Aggregates per-segment byte counts into one throttled progress stream. */
    private class SegmentProgress(
        private val total: Long,
        private val byteInterval: Long,
        private val timeIntervalMillis: Long,
        private val now: () -> Long,
        private val onProgress: (UpdateProgress) -> Unit,
    ) {
        private val lock = Any()
        private var written = 0L
        private var reportedBytes = 0L
        private var reportedAt = now()

        val downloaded: Long
            get() = synchronized(lock) { written }

        fun add(count: Int) {
            val snapshot = synchronized(lock) {
                written += count
                val at = now()
                if (written - reportedBytes >= byteInterval || at - reportedAt >= timeIntervalMillis) {
                    reportedBytes = written
                    reportedAt = at
                    written
                } else {
                    null
                }
            }
            snapshot?.let { onProgress(UpdateProgress(it, total)) }
        }
    }

    companion object {
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 120L
        private const val KEEP_ALIVE_MINUTES = 5L
        private const val DEFAULT_PROGRESS_INTERVAL_BYTES = 256L * 1024
        private const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 200L
        private const val DEFAULT_SEGMENT_COUNT = 4

        /** Below this advertised size a single stream is cheaper than four handshakes. */
        private const val MIN_SEGMENT_TOTAL_BYTES = 1L * 1024 * 1024
        // APK files are ZIP archives and always start with a local file header.
        private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
