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
            drain(LimitedInput(Endless(), Long.MAX_VALUE, System.currentTimeMillis() + 50) { true })
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
            repeat(256) { zip.write(zeros) } // 256 MiB of zeros, a few hundred KiB compressed
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        assertTrue(bytes.size < 2_000_000)
        val start = System.currentTimeMillis()
        val result =
            runCatching {
                StreamManifest.read(LimitedInput(ByteArrayInputStream(bytes), Long.MAX_VALUE, start + 200) { true })
            }
        assertTrue(result.isFailure)
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }
}
