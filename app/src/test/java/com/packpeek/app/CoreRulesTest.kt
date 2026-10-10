package com.packpeek.app

import com.packpeek.app.data.BackupRecord
import com.packpeek.app.data.PrunePlan
import com.packpeek.app.data.inFolder
import com.packpeek.app.data.prunePlan
import com.packpeek.app.data.signerChanges
import com.packpeek.app.engine.ManifestRoot
import com.packpeek.app.model.*
import com.packpeek.app.ui.sizeText
import org.junit.Assert.*
import org.junit.Test

class CoreRulesTest {
    private fun app(
        name: String = "Example",
        pkg: String = "example.app",
        splits: List<String> = emptyList(),
        bytes: Long = 10L * 1024 * 1024,
        sdk: Int = 36,
        system: Boolean = false,
        installer: String = "com.android.vending",
    ) =
        AppRecord(
            pkg,
            name,
            "1.0",
            1,
            24,
            sdk,
            system,
            "/data/app/base.apk",
            splits,
            bytes,
            100,
            50,
            installer,
            10001,
        )

    @Test
    fun mixedBatchKeepsIndividualFormats() {
        assertEquals(
            listOf("Example_v1.0.apk", "Split_v1.0.apks"),
            listOf(app(), app("Split", splits = listOf("config.apk"))).map {
                FileNames.format(it, NamingConfig())
            },
        )
    }

    @Test
    fun splitFormatOnlyChangesSplitApps() {
        val xapk = NamingConfig(split = SplitFormat.XAPK)
        assertEquals("Example_v1.0.apk", FileNames.format(app(), xapk))
        assertEquals(
            "Split_v1.0.xapk",
            FileNames.format(app("Split", splits = listOf("config.apk")), xapk),
        )
        assertEquals(SplitFormat.APKS, SplitFormat.of(null))
        assertEquals(SplitFormat.APKS, SplitFormat.of("UNKNOWN"))
        assertEquals(SplitFormat.ZIP, SplitFormat.of("ZIP"))
        assertEquals(
            "Split_v1.0.apk",
            FileNames.format(
                app("Split", splits = listOf("config.apk")),
                NamingConfig(split = SplitFormat.APK),
            ),
        )
    }

    @Test
    fun unsafeAndEmptyNamesAreSanitized() {
        assertEquals("_a_b_c", FileNames.clean("../a\\b:c"))
        assertEquals("app", FileNames.clean(" ... "))
        assertTrue(FileNames.clean("long".repeat(100)).length <= 120)
        assertEquals(
            "example.app.apk",
            FileNames.format(app(), NamingConfig(false, false, false, false)),
        )
    }

    @Test
    fun collisionSuffixPreservesExtension() {
        assertEquals("name.with.dots (3).apks", FileNames.numbered("name.with.dots.apks", 3))
    }

    @Test
    fun zipTraversalRejected() {
        listOf(
                "../base.apk",
                "parts/../../base.apk",
                "C:\\base.apk",
                "/base.apk",
                "..\\base.apk",
                "",
            )
            .forEach { assertFalse(it, FileNames.safeZipEntry(it)) }
        assertTrue(FileNames.safeZipEntry("splits/config.en.apk"))
    }

    @Test
    fun filtersIntersectAtExactSizeBoundary() {
        val valid = app("Wanted", bytes = 100L * 1024 * 1024, sdk = 30)
        val rows =
            listOf(
                valid,
                app("Small", bytes = 99L * 1024 * 1024, sdk = 30),
                app("System", sdk = 30, system = true),
                app("New", bytes = 200L * 1024 * 1024),
            )
        assertEquals(
            listOf(valid),
            filterApps(rows, AppQuery(kind = "USER", source = "PLAY", maxSdk = 30, minMb = 100)),
        )
    }

    @Test
    fun packageSearchAndFrameworkIntersect() {
        val one = app(pkg = "org.Example.one")
        val two = app(pkg = "org.other.two")
        assertEquals(
            listOf(one),
            filterApps(
                listOf(one, two),
                AppQuery(text = "EXAMPLE", framework = "Flutter"),
                mapOf(one.packageName to listOf("Flutter"), two.packageName to listOf("Unity")),
            ),
        )
    }

    @Test
    fun frameworkFilterMatchesAnyDetectedFramework() {
        val hybrid = app(pkg = "hybrid.app")
        assertEquals(
            listOf(hybrid),
            filterApps(
                listOf(hybrid, app(pkg = "native.app")),
                AppQuery(framework = "React Native"),
                mapOf(
                    hybrid.packageName to listOf("Flutter", "React Native"),
                    "native.app" to listOf("Android"),
                ),
            ),
        )
    }

    @Test
    fun oldTargetDoesNotDependOnDevice() {
        assertTrue(isOldTarget(30))
        assertFalse(isOldTarget(31))
        assertFalse(isOldTarget(36))
    }

    @Test
    fun favoriteFilterUsesSavedPackages() {
        val star = app(pkg = "star.app")
        assertEquals(
            listOf(star),
            filterApps(
                listOf(star, app(pkg = "plain.app")),
                AppQuery(kind = "FAVORITE"),
                favorites = setOf("star.app"),
            ),
        )
    }

    private fun record(id: Long, pkg: String, created: Long, signer: String) =
        BackupRecord(
            id = id,
            jobId = "job",
            packageName = pkg,
            label = pkg,
            version = "1",
            format = "apk",
            created = created,
            state = "SUCCESS",
            signer = signer,
        )

    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ABackup"

    private fun doc(name: String) = "$folder/document/primary%3ABackup%2F$name"

    private fun stored(id: Long, uri: String) = record(id, "a", id, "s").copy(uri = uri)

    @Test
    fun pruneKeepsNewestOnly() {
        val rows = (5L downTo 1L).map { stored(it, doc("v$it.apk")) }
        val plan = prunePlan(rows, 3, folder)
        assertEquals(listOf(2L, 1L), plan.delete.map { it.id })
        assertTrue(plan.forget.isEmpty())
        assertEquals(PrunePlan(emptyList(), emptyList()), prunePlan(rows, 0, folder))
        assertTrue(prunePlan(rows, 9, folder).delete.isEmpty())
    }

    /** The user deleted the old file by hand; the new backup reused its name and URI. */
    @Test
    fun pruneNeverDeletesAUriStillUsedByAKeptBackup() {
        val shared = doc("App_v1.apk")
        val plan = prunePlan(listOf(stored(2, shared), stored(1, shared)), 1, folder)
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf(1L), plan.forget.map { it.id })
    }

    @Test
    fun pruneLeavesBackupsInAnotherFolder() {
        val other = "content://com.android.externalstorage.documents/tree/primary%3AOld/document/primary%3AOld%2Fa.apk"
        val plan = prunePlan(listOf(stored(3, doc("a3.apk")), stored(2, other), stored(1, "")), 1, folder)
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf(1L), plan.forget.map { it.id })
        assertFalse(inFolder(doc("x.apk"), ""))
        assertFalse(inFolder("$folder-evil/document/x", folder))
    }

    @Test
    fun signerChangeFlagsOnlyTheChangedBackup() {
        val rows =
            listOf(
                record(1, "a", 10, "k1"),
                record(2, "a", 20, "k1"),
                record(3, "a", 30, "k2"),
                record(4, "b", 15, "k9"),
                record(5, "a", 25, ""),
            )
        assertEquals(setOf(3L), signerChanges(rows))
    }

    @Test
    fun sizesUseDecimalUnits() {
        assertEquals("78.9 MB", sizeText(78_940_000).replace(',', '.'))
        assertEquals("1.50 GB", sizeText(1_500_000_000).replace(',', '.'))
        assertEquals("999 B", sizeText(999))
    }

    @Test
    fun numericSizeSort() {
        assertEquals(
            listOf(100L, 20L, 3L),
            filterApps(
                    listOf(app(bytes = 20), app(bytes = 100), app(bytes = 3)),
                    AppQuery(sort = "SIZE_DESC"),
                )
                .map { it.size },
        )
    }

    @Test
    fun scoresDeduplicateAndCap() {
        assertEquals(
            25,
            riskScore(listOf(Finding(FindingCode.DEBUGGABLE), Finding(FindingCode.DEBUGGABLE))),
        )
        assertEquals(100, riskScore(FindingCode.entries.map { Finding(it) }))
    }

    @Test
    fun abiFollowsDevicePreference() {
        assertEquals(
            "arm64_v8a",
            AbiSelector.choose(
                setOf("armeabi_v7a", "arm64_v8a", "x86"),
                listOf("arm64-v8a", "armeabi-v7a"),
            ),
        )
        assertEquals("x86_64", AbiSelector.fromSplitName("feature.config.x86_64"))
        assertNull(AbiSelector.fromSplitName("config.en"))
        assertNull(AbiSelector.choose(emptySet(), listOf("arm64-v8a")))
    }

    @Test
    fun incompatibleAbiFails() {
        assertEquals(
            ErrorCode.ABI,
            (runCatching { AbiSelector.choose(setOf("x86"), listOf("arm64-v8a")) }.exceptionOrNull()
                    as AppFailure)
                .code,
        )
    }

    @Test
    fun originalOwnerManifestIsReadable() {
        val root = ManifestRoot.decode(fixture())
        assertEquals("io.github.omniextract.claude", root["package"])
        assertEquals("1.0.0", root["versionName"])
        assertTrue(root["split"].isNullOrEmpty())
    }

    @Test
    fun launcherActivitiesAreQualified() {
        assertEquals(
            setOf("io.github.omniextract.claude.MainActivity"),
            ManifestRoot.parse(fixture()).launchers,
        )
    }

    @Test
    fun malformedBinaryXmlFailsClosed() {
        val truncated = fixture().let { it.copyOf(it.size - 4) }
        listOf(
                byteArrayOf(),
                byteArrayOf(3, 0, 8, 0),
                fixture().copyOf(30),
                ByteArray(100),
                truncated,
            )
            .forEach {
                val failure = runCatching { ManifestRoot.decode(it) }.exceptionOrNull()
                assertEquals(ErrorCode.ARCHIVE, (failure as? AppFailure)?.code)
            }
    }

    private fun fixture() =
        javaClass.getResourceAsStream("/omniextract_manifest.bin")!!.use { it.readBytes() }
}
