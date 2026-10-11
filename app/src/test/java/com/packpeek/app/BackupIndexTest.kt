package com.packpeek.app

import com.packpeek.app.engine.StreamManifest
import com.packpeek.app.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupIndexTest {
    private val manifest =
        javaClass.getResourceAsStream("/omniextract_manifest.bin")!!.use { it.readBytes() }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                entries.forEach { (name, bytes) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
        }.toByteArray()

    @Test
    fun readsManifestOfPlainApk() {
        val apk = zip("classes.dex" to ByteArray(10), "AndroidManifest.xml" to manifest)
        assertEquals(
            "io.github.omniextract.claude",
            StreamManifest.read(apk.inputStream())?.get("package"),
        )
    }

    @Test
    fun readsBaseManifestInsideApkSets() {
        val base = zip("AndroidManifest.xml" to manifest)
        val apks = zip("toc.pb" to ByteArray(4), "splits/base-master.apk" to base)
        assertEquals(
            "io.github.omniextract.claude",
            StreamManifest.read(apks.inputStream())?.get("package"),
        )
        // XAPK: metadata and icon around the APK.
        val xapk = zip("manifest.json" to "{}".toByteArray(), "icon.png" to ByteArray(3), "app.apk" to base)
        assertEquals("1.0.0", StreamManifest.read(xapk.inputStream())?.get("versionName"))
    }

    @Test
    fun nonArchivesYieldNothing() {
        assertNull(StreamManifest.read(ByteArray(64).inputStream()))
        assertNull(StreamManifest.read(zip("readme.txt" to ByteArray(5)).inputStream()))
    }

    @Test
    fun skippedEntriesUseInflatedBudgetEvenWithTinyCompressedInput() {
        val archive = zip("padding.bin" to ByteArray(1024 * 1024), "AndroidManifest.xml" to manifest)
        assertTrue(archive.size < 16_384)
        assertThrows(java.io.InterruptedIOException::class.java) {
            StreamManifest.read(archive.inputStream(), maxInflatedBytes = 32_768)
        }
    }

    @Test
    fun nestedLimitsAreNotSwallowedAndLaterBaseCannotBypassThem() {
        val oversized = zip("padding.bin" to ByteArray(1024 * 1024))
        val base = zip("AndroidManifest.xml" to manifest)
        val archive = zip("config.apk" to oversized, "base.apk" to base)
        assertThrows(java.io.InterruptedIOException::class.java) {
            StreamManifest.read(archive.inputStream(), maxInflatedBytes = 32_768)
        }
        assertThrows(java.io.InterruptedIOException::class.java) {
            StreamManifest.read(archive.inputStream(), maxEntries = 2)
        }
    }

    @Test
    fun entryBudgetIsSharedAcrossNestedArchives() {
        val emptyApk = zip("resource.bin" to ByteArray(1))
        val base = zip("AndroidManifest.xml" to manifest)
        val archive = zip("one.apk" to emptyApk, "two.apk" to emptyApk, "base.apk" to base)
        assertThrows(java.io.InterruptedIOException::class.java) {
            StreamManifest.read(archive.inputStream(), maxEntries = 5)
        }
        assertEquals("io.github.omniextract.claude", StreamManifest.read(archive.inputStream(), maxEntries = 6)?.get("package"))
    }

    @Test
    fun cancellationIsCheckedWhileInflatingBufferedData() {
        val archive = zip("padding.bin" to ByteArray(1024 * 1024))
        var checks = 0
        assertThrows(java.io.InterruptedIOException::class.java) {
            StreamManifest.read(archive.inputStream(), active = { ++checks < 5 })
        }
        assertEquals(5, checks)
    }

    @Test
    fun exactInflatedBudgetStillReadsManifestAndLeavesCallerStreamOpen() {
        var closed = false
        val input = object : java.io.ByteArrayInputStream(zip("AndroidManifest.xml" to manifest)) {
            override fun close() { closed = true }
        }
        assertNotNull(StreamManifest.read(input, maxInflatedBytes = manifest.size.toLong()))
        assertFalse(closed)
    }

    @Test
    fun statusComparesVersionCodes() {
        assertEquals(BackupStatus.NOT_INSTALLED, backupStatus(5, null))
        assertEquals(BackupStatus.NEWER, backupStatus(6, 5))
        assertEquals(BackupStatus.SAME, backupStatus(5, 5))
        assertEquals(BackupStatus.OLDER, backupStatus(4, 5))
        assertEquals((1L shl 32) + 7, manifestVersionCode(mapOf("versionCode" to "7", "versionCodeMajor" to "1")))
        assertEquals(4294967295L, manifestVersionCode(mapOf("versionCode" to "4294967295")))
    }

    @Test
    fun missingBackupsCoverUserAppsOnly() {
        fun app(pkg: String, system: Boolean = false) =
            AppRecord(pkg, pkg, "1", 1, 24, 34, system, "/b.apk", emptyList(), 1, 0, 0, "", 1)
        val file =
            BackupFile("u", "a.apk", 1, 0, "a", "1", 1, BackupStatus.SAME, "a")
        assertEquals(
            listOf("b"),
            appsWithoutBackup(listOf(app("a"), app("b"), app("s", system = true)), listOf(file))
                .map { it.packageName },
        )
        assertTrue(isPackageArchive("X v1.APKS"))
        assertFalse(isPackageArchive("notes.txt"))
    }
}
