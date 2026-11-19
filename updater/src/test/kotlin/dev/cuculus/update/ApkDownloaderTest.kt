package dev.cuculus.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloader = ApkDownloader(OkHttpClient(), progressIntervalBytes = 1024)

    @Test
    fun `downloads an apk with progress and leaves no part file`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(4096)
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val target = File(tmp.newFolder(), "update.apk")

            val progress = mutableListOf<UpdateProgress>()
            val result = downloader.download(server.url("/release.apk").toString(), target, progress::add)

            assertEquals(target, result)
            assertTrue(target.isFile)
            assertEquals(bytes.size.toLong(), target.length())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
            assertEquals(bytes.size.toLong(), progress.last().bytesDownloaded)
            assertEquals(bytes.size.toLong(), progress.last().totalBytes)
            // A small body arrives in one buffered read, so every report already carries the
            // full byte count; the guarantees that matter are monotonicity and completeness.
            assertTrue(progress.zipWithNext().all { (earlier, later) -> later.bytesDownloaded >= earlier.bytesDownloaded })
            assertEquals(1f, progress.last().fraction!!, 0.0001f)
        }
    }

    @Test
    fun `rejects a body that is not an apk`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<html>404 page</html>").build())
            val target = File(tmp.newFolder(), "update.apk")

            val failure = runCatching {
                downloader.download(server.url("/release.apk").toString(), target)
            }.exceptionOrNull()

            assertTrue(failure is DownloadException)
            assertTrue(failure!!.message!!.contains("not an Android package"))
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
        }
    }

    @Test
    fun `maps an http error to a download exception and writes nothing`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("gone").build())
            val target = File(tmp.newFolder(), "update.apk")

            val failure = runCatching {
                downloader.download(server.url("/release.apk").toString(), target)
            }.exceptionOrNull()

            assertTrue(failure is DownloadException)
            assertTrue(failure!!.message!!.contains("404"))
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
        }
    }

    @Test
    fun `replaces a stale file from an earlier download attempt`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(2048)
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk").apply { writeBytes(ByteArray(16)) }

            downloader.download(server.url("/release.apk").toString(), target)

            assertEquals(bytes.size.toLong(), target.length())
            assertEquals(1, Files.list(folder.toPath()).use { it.count() })
        }
    }

    @Test
    fun `resumes a partial download via a range request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(4096)
            // The client asks for bytes 2048- with a range request.
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 2048-4095/4096")
                    .body(Buffer().write(bytes.copyOfRange(2048, 4096)))
                    .build(),
            )
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            // A prior attempt left the first half behind.
            File(folder, "update.apk.part").writeBytes(bytes.copyOfRange(0, 2048))

            val result = downloader.download(server.url("/release.apk").toString(), target)

            assertEquals(target, result)
            assertEquals(4096L, target.length())
            assertTrue(target.readBytes().contentEquals(bytes))
            val request = server.takeRequest()
            assertTrue(request.headers["Range"]!!.startsWith("bytes=2048-"))
        }
    }

    @Test
    fun `retries from zero when the server rejects a stale range`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(2048)
            // A previous foreground worker left an over-long .part (e.g. the asset was replaced).
            server.enqueue(MockResponse.Builder().code(416).body("range not satisfiable").build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            File(folder, "update.apk.part").writeBytes(ByteArray(4096))

            val result = downloader.download(server.url("/release.apk").toString(), target)

            assertEquals(target, result)
            assertTrue(target.readBytes().contentEquals(bytes))
            assertFalse(File(folder, "update.apk.part").exists())
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("bytes=4096-", first.headers["Range"])
            assertEquals(null, second.headers["Range"])
        }
    }

    @Test
    fun `keeps the part file when the download fails mid-transfer`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(4096)
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val part = File(folder, "update.apk.part")
            // A prior attempt left the first half behind.
            part.writeBytes(bytes.copyOfRange(0, 2048))

            // This attempt fails before completing (an HTTP error). The `.part` from the previous
            // run must survive so the next attempt can resume instead of restarting. The bug this
            // guards against is a `finally { part.delete() }` that wiped the resume point on every
            // failure, forcing a restart from zero.
            server.enqueue(MockResponse.Builder().code(500).body("boom").build())
            val failure = runCatching {
                downloader.download(server.url("/release.apk").toString(), target)
            }.exceptionOrNull()
            assertTrue(failure is DownloadException)
            assertTrue("the .part must survive a failed attempt", part.isFile)
            assertEquals(2048L, part.length())
            assertFalse(target.exists())
        }
    }

    @Test
    fun `fans out over parallel range requests when the size is known`() = runTest {
        val bytes = apkBytes(16 * 1024)
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val (from, to) = request.headers["Range"]!!
                        .removePrefix("bytes=")
                        .split("-")
                        .map { it.toLong() }
                    return MockResponse.Builder()
                        .code(206)
                        .addHeader("Content-Range", "bytes $from-$to/${bytes.size}")
                        .body(Buffer().write(bytes.copyOfRange(from.toInt(), to.toInt() + 1)))
                        .build()
                }
            }
            server.start()
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val downloader = ApkDownloader(
                OkHttpClient(),
                progressIntervalBytes = 1024,
                segmentCount = 4,
                minSegmentTotalBytes = 1024,
            )

            val result = downloader.download(
                server.url("/release.apk").toString(),
                target,
                bytes.size.toLong(),
            )

            assertEquals(target, result)
            assertTrue(target.readBytes().contentEquals(bytes))
            assertFalse(File(folder, "update.apk.part").exists())
            // One request per segment, each asking for a disjoint slice that tiles the file.
            val ranges = (1..4)
                .map { server.takeRequest().headers["Range"]!! }
                .map { it.removePrefix("bytes=").split("-").map(String::toLong) }
                .sortedBy { it[0] }
            assertEquals(listOf(0L, 4096L, 8192L, 12288L), ranges.map { it[0] })
            assertEquals(listOf(4095L, 8191L, 12287L, 16383L), ranges.map { it[1] })
        }
    }

    @Test
    fun `falls back to a single stream when the server ignores range requests`() = runTest {
        val bytes = apkBytes(16 * 1024)
        MockWebServer().use { server ->
            // This server answers every request with the whole body and `200`, so no ranged
            // request can be satisfied. The segmented path must degrade to one plain stream
            // instead of leaving a corrupt file behind.
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                    MockResponse.Builder().body(Buffer().write(bytes)).build()
            }
            server.start()
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val downloader = ApkDownloader(
                OkHttpClient(),
                progressIntervalBytes = 1024,
                segmentCount = 4,
                minSegmentTotalBytes = 1024,
            )

            val result = downloader.download(
                server.url("/release.apk").toString(),
                target,
                bytes.size.toLong(),
            )

            assertEquals(target, result)
            assertTrue(target.readBytes().contentEquals(bytes))
            assertFalse(File(folder, "update.apk.part").exists())
            // At least one rejected segment attempt, then the plain stream fetches from zero.
            assertTrue("expected a segment attempt plus a fallback", server.requestCount >= 2)
        }
    }

    @Test
    fun `reconnects a stalled segment and continues from its last byte`() = runTest {
        val bytes = apkBytes(2048)
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                private val servedShortFirst = java.util.concurrent.atomic.AtomicBoolean(false)

                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val range = request.headers["Range"]
                        ?: return MockResponse.Builder().body(Buffer().write(bytes)).build()
                    val spec = range.removePrefix("bytes=")
                    val (from, to) = spec.split("-").map { it.toLong() }
                    // The server promises 0-1023 but delivers only the first 512 bytes and closes
                    // the connection — the "stuck at 80%" failure, reproduced deterministically.
                    val short = spec == "0-1023" && servedShortFirst.compareAndSet(false, true)
                    val last = if (short) 511L else to
                    return MockResponse.Builder()
                        .code(206)
                        .addHeader("Content-Range", "bytes $from-$to/${bytes.size}")
                        .body(Buffer().write(bytes.copyOfRange(from.toInt(), last.toInt() + 1)))
                        .build()
                }
            }
            server.start()
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val downloader = ApkDownloader(
                OkHttpClient(),
                progressIntervalBytes = 1024,
                segmentCount = 2,
                minSegmentTotalBytes = 1024,
            )

            val result = downloader.download(
                server.url("/release.apk").toString(),
                target,
                bytes.size.toLong(),
            )

            assertEquals(target, result)
            assertTrue(target.readBytes().contentEquals(bytes))
            assertFalse(File(folder, "update.apk.part").exists())
            val ranges = (1..server.requestCount).map { server.takeRequest().headers["Range"] }
            // The retry must ask for exactly the missing half, not repeat the whole segment:
            // re-requesting 0-1023 would download those 512 bytes twice and inflate the counter.
            assertTrue(
                "the retry must resume at 512 instead of restarting the segment: $ranges",
                ranges.contains("bytes=512-1023"),
            )
            assertFalse(
                "the segment must not be re-fetched from its start: $ranges",
                ranges.count { it == "bytes=0-1023" } > 1,
            )
        }
    }

    @Test
    fun `resumes a single stream that ends early instead of restarting`() = runTest {
        val bytes = apkBytes(4096)
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val range = request.headers["Range"]
                    if (range == null) {
                        // The first attempt announces the whole file but delivers only half of it.
                        return MockResponse.Builder()
                            .code(206)
                            .addHeader("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
                            .body(Buffer().write(bytes.copyOfRange(0, 2048)))
                            .build()
                    }
                    val parts = range.removePrefix("bytes=").split("-")
                    val from = parts[0].toLong()
                    val requested = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong()
                    val last = (requested ?: (bytes.size - 1).toLong()).coerceAtMost(bytes.size - 1L)
                    return MockResponse.Builder()
                        .code(206)
                        .addHeader("Content-Range", "bytes $from-$last/${bytes.size}")
                        .body(Buffer().write(bytes.copyOfRange(from.toInt(), last.toInt() + 1)))
                        .build()
                }
            }
            server.start()
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val downloader = ApkDownloader(OkHttpClient(), progressIntervalBytes = 1024)

            // No size hint, so the single-stream path is used.
            val result = downloader.download(server.url("/release.apk").toString(), target)

            assertEquals(target, result)
            assertTrue(target.readBytes().contentEquals(bytes))
            assertFalse(File(folder, "update.apk.part").exists())
            val ranges = (1..server.requestCount).map { server.takeRequest().headers["Range"] }
            assertTrue(
                "the single stream must resume at 2048 instead of restarting: $ranges",
                ranges.contains("bytes=2048-"),
            )
        }
    }

    @Test
    fun `cancelling the caller aborts the transfer instead of finishing it`() = runTest {
        val bytes = apkBytes(1024 * 1024)
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val (from, to) = request.headers["Range"]!!
                        .removePrefix("bytes=")
                        .split("-")
                        .map { it.toLong() }
                    return MockResponse.Builder()
                        .code(206)
                        .addHeader("Content-Range", "bytes $from-$to/${bytes.size}")
                        .body(Buffer().write(bytes.copyOfRange(from.toInt(), to.toInt() + 1)))
                        .build()
                }
            }
            server.start()
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val part = File(folder, "update.apk.part")
            val downloader = ApkDownloader(
                OkHttpClient(),
                progressIntervalBytes = 1024,
                segmentCount = 4,
                minSegmentTotalBytes = 1024,
            )

            var job: Job? = null
            val reported = java.util.concurrent.atomic.AtomicInteger()
            job = launch {
                downloader.download(server.url("/release.apk").toString(), target, bytes.size.toLong()) {
                    // Cancel from inside the callback: the blocking socket reads cannot see the
                    // cancellation by themselves, so this proves the per-buffer check works.
                    reported.incrementAndGet()
                    job?.cancel()
                }
            }
            job.join()

            assertTrue("the transfer must have started before the cancel", reported.get() > 0)
            assertTrue("the job must report cancellation", job.isCancelled)
            // A cancelled segmented transfer leaves holes in the preallocated file, so it must be
            // removed rather than left to be mistaken for a resumable .part.
            assertFalse("the preallocated .part must be removed", part.exists())
            assertFalse("a cancelled download must not produce an APK", target.exists())
        }
    }

    /**
     * A byte-exact, *valid* APK fixture: a real ZIP whose central directory the SDK's integrity
     * check can read. Tests assert both the exact byte count and content equality after ranged
     * assembly, so the fixture must come back to exactly [size] bytes.
     */
    private fun apkBytes(size: Int): ByteArray {
        require(size > 256) { "too small for a valid zip" }
        val payload = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(payload).use { zip ->
            zip.setLevel(0) // stored, so entry bytes are predictable
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
            // aim under the target; the entry header + data descriptor + central directory add overhead
            zip.write(ByteArray(size - 512))
            zip.closeEntry()
        }
        val zip = payload.toByteArray()
        check(zip.size <= size) { "zip fixture ${zip.size} exceeded target $size" }
        // Pad inside the ZIP via an extra field would need re-writing offsets; instead append
        // trailing bytes after the EOCD, which ZipFile tolerates, to reach the exact size.
        if (zip.size == size) return zip
        val padded = ByteArray(size)
        System.arraycopy(zip, 0, padded, 0, zip.size)
        return padded
    }
}
