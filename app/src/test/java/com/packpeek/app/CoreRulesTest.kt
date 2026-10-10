package com.packpeek.app

import com.packpeek.app.engine.ManifestRoot
import com.packpeek.app.model.*
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
                mapOf(one.packageName to "Flutter", two.packageName to "Unity"),
            ),
        )
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
    fun malformedBinaryXmlFailsClosed() {
        listOf(byteArrayOf(), byteArrayOf(3, 0, 8, 0), fixture().copyOf(30), ByteArray(100))
            .forEach { assertTrue(runCatching { ManifestRoot.decode(it) }.isFailure) }
    }

    private fun fixture() =
        javaClass.getResourceAsStream("/omniextract_manifest.bin")!!.use { it.readBytes() }
}
