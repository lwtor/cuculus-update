package dev.cuculus.update

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Nothing has been written yet and the channel looks unusable; retrying on the other channel
 * (proxy vs direct) may help. Private: [DownloadException] stays the single public failure type.
 */
private class DownloadConnectFailure(message: String) : Exception(message)

/**
 * Some bytes were written and then the transfer stopped (stall, reset, premature end of body).
 * The bytes already on disk are a valid prefix, so the fix is to reconnect and continue from
 * there — not to start over. This is the difference between "recovers on its own" and the
 * "stuck at 80% forever" behaviour users reported.
 */
private class DownloadRetryable(message: String) : Exception(message)

/**
 * A segment stopped early, carrying the offset it actually reached. Without this the retry would
 * restart at the segment's original start, re-fetching (and double-counting) bytes that are
 * already on disk.
 */
private class SegmentStalled(val reached: Long, message: String) : Exception(message)

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
 * Streams a release APK to a local file with byte-level progress, resumable downloads and
 * self-healing after a stalled connection.
 *
 * The bytes land in a `.part` sibling; if a download is interrupted, the next attempt requests the
 * missing range (`Range: bytes=<resumed>-`) so it continues from where it left off instead of
 * starting over. GitHub's release CDN answers range requests with `206 Partial Content`.
 *
 * When the total size is known and nothing has been downloaded yet, the transfer is split into
 * [segmentCount] ranged requests that run concurrently, each on its own connection. A release CDN
 * commonly caps throughput *per connection*, so a single stream leaves bandwidth unused: measured
 * against GitHub's asset CDN one stream sat at ~90–180 KB/s while four streams reached
 * ~130–480 KB/s on the same link.
 *
 * **Every transfer is retried from where it stopped.** Four concurrent connections also mean four
 * chances for one socket to die silently, and a single dead socket used to freeze the whole
 * download until the (previously 120 s) read timeout — the UI sat at a fixed percentage with the
 * button disabled. Now each segment has a [SEGMENT_READ_TIMEOUT_SECONDS] stall window and retries
 * from its own last-written offset, and the single stream retries mid-transfer the same way. No
 * bytes are fetched twice and nothing is restarted from zero.
 *
 * The segmented path is an optimisation only: if the server ignores `Range`, or the advertised
 * size no longer matches the asset, the download falls back to the plain single stream.
 *
 * Progress is throttled by *both* bytes and time. A bytes-only throttle meant that at 90 KB/s the
 * UI received its first update only after three seconds and then one every three seconds, which
 * reads as "stuck at 0%" on a slow link.
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
                // The preallocated file has holes, so its length says nothing about what is
                // actually complete. It must never be picked up as a resume point.
                part.delete()
                throw cancelled
            } catch (_: Exception) {
                // A segmented transfer is only a speed optimisation. Discard the half-written file
                // and do one plain stream instead.
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
            // The magic number alone lets a truncated or mis-assembled body through — it still
            // starts with PK\x03\x04 and may even have the right length. Reading the ZIP central
            // directory (a few KB at the end) catches those before the system installer does.
            verifyZipStructure(part)
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
                    async { fetchSegment(url, channel, range, progress) }
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
     * A client with its own [ConnectionPool] so this segment never shares a socket with the other
     * segments (OkHttp would otherwise reuse one connection and multiplex the ranged requests onto
     * it, defeating the parallelism). The short read timeout turns a silently dead socket into a
     * retry within seconds. One client per segment, reused across that segment's retries.
     */
    private fun segmentClient(): OkHttpClient = viaProxyClient.newBuilder()
        .readTimeout(SEGMENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(1, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .build()

    /**
     * Reads one segment, reconnecting and continuing from the last written offset whenever the
     * transfer stops. Only a server that refuses ranges is fatal (the caller then drops to a
     * single stream); everything else is worth another attempt.
     */
    private suspend fun fetchSegment(
        url: String,
        channel: FileChannel,
        range: LongRange,
        progress: SegmentProgress,
    ) {
        var from = range.first
        var attempt = 0
        // One client per segment (not per attempt): its own connection pool is what keeps the
        // ranged requests off each other's socket, and a retry reuses the same pool so a broken
        // connection is replaced instead of piling up abandoned clients.
        val client = segmentClient()
        try {
            while (true) {
                try {
                    readSegment(client, url, channel, from, range.last, progress)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: RangeIgnoredException) {
                    throw ignored
                } catch (stall: Exception) {
                    // Resume exactly where the bytes stopped, never from the segment's start.
                    if (stall is SegmentStalled) from = stall.reached
                    val retryable = stall is SegmentStalled || stall is DownloadConnectFailure
                    if (!retryable) throw stall
                    if (++attempt >= SEGMENT_MAX_ATTEMPTS) {
                        throw DownloadException(
                            "download stalled at ${from}+: ${stall.message ?: "no progress"}",
                        )
                    }
                    // Drop the connection that just failed: a timed-out or half-read exchange can
                    // leave a socket in the pool that looks reusable but is not.
                    client.connectionPool.evictAll()
                    delay(RETRY_BACKOFF_MILLIS * attempt)
                }
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /** Streams `[from, to]` into [channel]; returns normally only once `to` has been written. */
    private suspend fun readSegment(
        client: OkHttpClient,
        url: String,
        channel: FileChannel,
        from: Long,
        to: Long,
        progress: SegmentProgress,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$to")
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
        var position = from
        response.use {
            if (it.code != 206) throw RangeIgnoredException(it.code)
            val body = it.body ?: throw SegmentStalled(position, "download returned an empty body")
            val source = body.source()
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (position <= to) {
                // Blocking socket reads ignore coroutine cancellation, so a cancelled download
                // would keep pulling bytes until the segment finished. Check once per buffer
                // instead: at 100 KB/s that is a sub-second response to the caller's cancel.
                currentCoroutineContext().ensureActive()
                val want = minOf(buffer.size.toLong(), to - position + 1).toInt()
                val read = try {
                    source.read(buffer, 0, want)
                } catch (error: InterruptedIOException) {
                    // No byte for the whole stall window. The bytes already written are valid, so
                    // the caller reconnects with a Range that starts right here.
                    throw SegmentStalled(
                        position,
                        "the connection stopped after $position bytes: ${error.message}",
                    )
                }
                if (read == -1) break
                writeFully(channel, buffer, read, position)
                position += read
                progress.add(read)
            }
        }
        if (position <= to) {
            throw SegmentStalled(
                position,
                "the connection ended early at $position (expected ${to + 1})",
            )
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

    /** Tries the proxy channel first, then the direct channel, and resumes after any stall. */
    private suspend fun downloadInto(
        url: String,
        part: File,
        startBytes: Long,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val channels = listOf(viaProxyClient, directClient)
        var channel = 0
        var resumeFrom = startBytes
        var retries = 0
        while (true) {
            val request = Request.Builder().url(url).get().apply {
                if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
            }.build()
            try {
                streamFrom(channels[channel], request, part, resumeFrom, onProgress)
                return
            } catch (failure: DownloadConnectFailure) {
                // Only a connect failure justifies switching channels, and only while no bytes
                // have been written. HTTP errors and mid-stream failures must not be replayed.
                val written = if (part.isFile) part.length() else 0L
                if (channel < channels.lastIndex && written <= resumeFrom) {
                    channel++
                    continue
                }
                throw DownloadException(failure.message ?: "download failed")
            } catch (stall: DownloadRetryable) {
                if (++retries > MID_STREAM_RETRIES) {
                    throw DownloadException(stall.message ?: "download failed")
                }
                // Continue from what is already on disk instead of restarting the file.
                resumeFrom = if (part.isFile) part.length() else 0L
                delay(RETRY_BACKOFF_MILLIS * retries)
            }
        }
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
                    // See readSegment(): a blocking read cannot observe cancellation by itself.
                    currentCoroutineContext().ensureActive()
                    val read = try {
                        source.read(buffer)
                    } catch (error: InterruptedIOException) {
                        // Nothing arrived for the whole stall window. With zero new bytes the
                        // channel itself is the problem; otherwise keep the bytes and resume.
                        if (copied <= startBytes) {
                            throw DownloadConnectFailure(
                                "the download stalled before any bytes arrived: ${error.message}",
                            )
                        }
                        throw DownloadRetryable(
                            "the connection stopped after $copied bytes: ${error.message}",
                        )
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
                throw DownloadRetryable("truncated download: received $copied of $totalBytes bytes")
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

    /** Cheap integrity check: a valid APK's ZIP central directory must be readable. */
    private fun verifyZipStructure(file: File) {
        try {
            java.util.zip.ZipFile(file).use { zip ->
                if (zip.size() <= 0) throw DownloadException("the downloaded package has no entries")
            }
        } catch (error: java.io.IOException) {
            throw DownloadException("the downloaded package is corrupted: ${error.message}")
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

        /** Idle window for a single stream: no byte for this long means the socket is dead. */
        private const val READ_TIMEOUT_SECONDS = 30L

        /** Segments are retried often and cheaply, so they get a tighter stall window. */
        private const val SEGMENT_READ_TIMEOUT_SECONDS = 15L
        private const val SEGMENT_MAX_ATTEMPTS = 4
        private const val MID_STREAM_RETRIES = 2
        private const val RETRY_BACKOFF_MILLIS = 500L
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
