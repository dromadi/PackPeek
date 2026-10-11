package com.packpeek.app

import com.packpeek.app.model.*
import org.junit.Assert.*
import org.junit.Test

class SplitRulesTest {
    private val names =
        listOf(
            "",
            "config.arm64_v8a",
            "config.armeabi_v7a",
            "config.x86_64",
            "config.xxhdpi",
            "config.xhdpi",
            "config.mdpi",
            "config.en",
            "config.in",
            "config.de",
            "camera",
            "camera.config.arm64_v8a",
            "camera.config.x86_64",
        )

    private fun plan(dpi: Int = 420, languages: List<String> = listOf("id-ID")) =
        SplitPlanner.plan(names, listOf("arm64-v8a", "armeabi-v7a"), dpi, languages)

    private fun recommended(parts: List<SplitPart>) =
        parts.filter { it.recommended }.map { it.name }.toSet()

    @Test
    fun parsesKindsAndModules() {
        assertEquals(Triple(SplitKind.BASE, "", ""), SplitPlanner.parse(""))
        assertEquals(Triple(SplitKind.ABI, "", "arm64_v8a"), SplitPlanner.parse("config.arm64_v8a"))
        assertEquals(Triple(SplitKind.DENSITY, "", "xxhdpi"), SplitPlanner.parse("config.xxhdpi"))
        assertEquals(Triple(SplitKind.LOCALE, "", "zh_TW"), SplitPlanner.parse("config.zh_TW"))
        assertEquals(Triple(SplitKind.FEATURE, "camera", ""), SplitPlanner.parse("camera"))
        assertEquals(
            Triple(SplitKind.ABI, "camera", "x86_64"),
            SplitPlanner.parse("camera.config.x86_64"),
        )
    }

    @Test
    fun recommendsWhatThisDeviceNeeds() {
        assertEquals(
            setOf(
                "",
                "config.arm64_v8a",
                "config.xxhdpi",
                "config.in",
                "camera",
                "camera.config.arm64_v8a",
            ),
            recommended(plan()),
        )
    }

    @Test
    fun densityTiePrefersHigherAndLanguageFallsBackToEnglish() {
        // 400 dpi is 80 from both xhdpi and xxhdpi.
        val parts = plan(dpi = 400, languages = listOf("fr-FR"))
        assertTrue("config.xxhdpi" in recommended(parts))
        assertTrue("config.en" in recommended(parts))
        assertFalse("config.in" in recommended(parts))
    }

    @Test
    fun deselectedFeatureDropsItsConfigsAndBaseStays() {
        val parts = plan()
        val selected = recommended(parts) - "camera" - ""
        val kept = SplitPlanner.resolve(parts, selected)
        assertTrue("" in kept)
        assertFalse("camera.config.arm64_v8a" in kept)
        assertFalse(SplitPlanner.missingAbi(parts, selected))
    }

    @Test
    fun missingCpuPartIsDetected() {
        val parts = plan()
        assertTrue(SplitPlanner.missingAbi(parts, recommended(parts) - "config.arm64_v8a"))
        assertTrue(SplitPlanner.missingAbi(parts, recommended(parts) - "camera.config.arm64_v8a"))
    }

    @Test
    fun incompatibleCpuRecommendsNoAbiPart() {
        val parts = SplitPlanner.plan(listOf("", "config.x86"), listOf("arm64-v8a"), 420, listOf("en"))
        assertEquals(setOf(""), recommended(parts))
        assertTrue(SplitPlanner.missingAbi(parts, recommended(parts)))
    }

    @Test
    fun bundletoolStandalonesAreIgnored() {
        assertEquals(
            listOf("splits/base-master.apk", "splits/base-arm64_v8a.apk"),
            archiveApkEntries(
                listOf(
                    "toc.pb",
                    "splits/base-master.apk",
                    "splits/base-arm64_v8a.apk",
                    "standalones/standalone-arm64_v8a_xxhdpi.apk",
                )
            ),
        )
        assertEquals(
            listOf("standalones/standalone.apk"),
            archiveApkEntries(listOf("standalones/standalone.apk", "toc.pb")),
        )
        assertEquals(
            listOf("base.apk", "config.en.apk"),
            archiveApkEntries(listOf("base.apk", "config.en.apk", "icon.png", "manifest.json")),
        )
    }
}
