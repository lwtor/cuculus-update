package dev.cuculus.update

import java.io.File
import java.nio.file.Files
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

    private fun apkBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
        // APK files are ZIP archives starting with a local file header.
        bytes[0] = 0x50
        bytes[1] = 0x4B
        bytes[2] = 0x03
        bytes[3] = 0x04
    }
}
