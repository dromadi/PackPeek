package com.packpeek.app

import com.packpeek.app.engine.LimitedInput
import com.packpeek.app.engine.StreamManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LimitedInputTest {
    /** An endless stream of zeros, like a huge archive entry. */
    private class Endless : InputStream() {
        override fun read() = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill(0, off, off + len)
            return len
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(8192)
        while (input.read(buffer) >= 0) Unit
    }

    @Test
    fun stopsAtTheByteLimit() {
        val limited = LimitedInput(Endless(), 1_000_000, Long.MAX_VALUE) { true }
        assertThrows(InterruptedIOException::class.java) { drain(limited) }
        assertTrue(limited.consumed in 1_000_000..1_010_000)
    }

    @Test
    fun stopsAtTheDeadlineAndOnCancel() {
        assertThrows(InterruptedIOException::class.java) {
            drain(LimitedInput(Endless(), Long.MAX_VALUE, 0L) { true })
        }
        var active = true
        val limited = LimitedInput(Endless(), Long.MAX_VALUE, Long.MAX_VALUE) { active }
        limited.read(ByteArray(10))
        active = false
        assertThrows(InterruptedIOException::class.java) { limited.read(ByteArray(10)) }
    }

    /** A highly compressible entry before the manifest is inflated while skipping; the cap stops it. */
    @Test
    fun aZipBombBeforeTheManifestIsCut() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("bomb.bin"))
            val zeros = ByteArray(1 shl 20)
            repeat(8) { zip.write(zeros) } // 8 MiB inflated; tiny compressed input
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            javaClass.getResourceAsStream("/omniextract_manifest.bin")!!.use { zip.write(it.readBytes()) }
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        assertTrue(bytes.size < 2_000_000)
        // No short deadline: a fast machine must hit the same inflated-byte guard as a slow one.
        val failure = assertThrows(InterruptedIOException::class.java) {
            StreamManifest.read(ByteArrayInputStream(bytes), maxInflatedBytes = 1024 * 1024L)
        }
        assertEquals("inflated read limit", failure.message)
        // The archive itself is valid; with enough budget its later manifest is readable.
        assertEquals(
            "io.github.omniextract.claude",
            StreamManifest.read(ByteArrayInputStream(bytes), maxInflatedBytes = 16 * 1024 * 1024L)?.get("package"),
        )
    }
}
