package dev.cuculus.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `accepts a valid apk with a readable zip central directory`() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
            zip.write("dex".toByteArray())
            zip.closeEntry()
        }
        val apk = File(tmp.newFolder(), "update.apk").apply { writeBytes(bytes.toByteArray()) }
        assertTrue(isValidApk(apk))
    }

    @Test
    fun `rejects a truncated zip that has only the local file header`() {
        val apk = File(tmp.newFolder(), "update.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00))
        }
        assertFalse("a PK header without a central directory is not a valid APK", isValidApk(apk))
    }

    @Test
    fun `rejects a file that does not start with the zip header`() {
        val notApk = File(tmp.newFolder(), "update.apk").apply { writeBytes("not an apk".toByteArray()) }
        assertFalse(isValidApk(notApk))
    }

    @Test
    fun `rejects a missing file`() {
        assertFalse(isValidApk(File(tmp.newFolder(), "missing.apk")))
    }

    @Test
    fun `rejects a file shorter than the header`() {
        val short = File(tmp.newFolder(), "update.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        assertFalse(isValidApk(short))
    }
}
