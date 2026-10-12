package com.packpeek.app

import com.packpeek.app.engine.risk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * DEX files are read with a limit on the bytes actually inflated: a crafted APK can understate an
 * entry's size in its ZIP directory, and one oversized file must not end the rest of the analysis.
 */
class DexLimitsTest {
    private val sample = File(requireNotNull(javaClass.getResource("/samples/two-clients.apk")).toURI())
    private val dex = ZipFile(sample).use { z -> z.getInputStream(z.getEntry("classes.dex")).readBytes() }

    @Test
    fun readsUpToTheLimitAndNoFurther() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, DexScanner.readBounded(ByteArrayInputStream(data), 1000))
        assertThrows(DexScanner.TooLarge::class.java) { DexScanner.readBounded(ByteArrayInputStream(data), 999) }
    }

    /** classes.dex claims 200 bytes; the limit counts what is inflated, so the claim does not help. */
    @Test
    fun anUnderstatedDexSizeIsCaught() {
        val apk = apk(mapOf("classes.dex" to dex), understate = setOf("classes.dex"))
        val limited = DexScanner.scan(listOf(apk), maxDexBytes = 1024)
        assertTrue(limited.gaps.any { it.kind == GapKind.LIMIT && it.detail.endsWith("!classes.dex") })
        assertEquals(0, limited.dexFiles)
        // Within the limit, the same file is read and analysed as usual.
        val normal = DexScanner.scan(listOf(apk))
        assertEquals(1, normal.dexFiles)
        assertTrue(normal.gaps.none { it.kind == GapKind.LIMIT })
    }

    /** An oversized hidden payload (scanned first, in the deep analysis) leaves the app's code analysed. */
    @Test
    fun anOversizedPayloadLeavesTheMainCode() {
        val payload = dex + ByteArray(256 * 1024)
        val apk = apk(mapOf("classes.dex" to dex, "assets/extra.bin" to payload), understate = setOf("assets/extra.bin"))
        val code = DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true, maxDexBytes = dex.size + 1024L)
        assertEquals(1, code.dexFiles)
        assertTrue(code.classes > 0)
        assertTrue(code.gaps.none { it.kind == GapKind.LIMIT })
        assertEquals(0, code.deep!!.payloads.single { it.path == "assets/extra.bin" }.classes)
    }

    /** A ZIP of [entries]; for names in [understate], the central directory claims 200 bytes. */
    private fun apk(entries: Map<String, ByteArray>, understate: Set<String>): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val bytes = out.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p + 46 <= bytes.size) {
            if (b.getInt(p) == 0x02014b50) {
                val nameLength = b.getShort(p + 28).toInt() and 0xffff
                val name = String(bytes, p + 46, nameLength)
                if (name in understate) b.putInt(p + 24, 200)
                p += 46 + nameLength + (b.getShort(p + 30).toInt() and 0xffff) + (b.getShort(p + 32).toInt() and 0xffff)
            } else p++
        }
        return Files.createTempFile("limits", ".apk").toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        }
    }
}
