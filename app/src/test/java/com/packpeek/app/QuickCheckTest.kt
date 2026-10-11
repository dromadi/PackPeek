package com.packpeek.app

import com.packpeek.app.engine.risk.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The overview's quick check (no value tracking) gives every sample the same findings and levels. */
class QuickCheckTest {
    private val samples =
        File(requireNotNull(javaClass.getResource("/samples")).toURI()).listFiles { f -> f.extension == "apk" }!!.sorted()

    private fun findings(apk: File, track: Boolean): List<Pair<RiskId, Severity>> =
        RiskRules.evaluate(
                ManifestScanner.scan(listOf(apk)),
                RiskContext(apk.name, true, false, "SIDE", true, 1),
                null,
                DexScanner.scan(listOf(apk), track = track),
            )
            .findings
            .map { it.id to it.severity }

    @Test
    fun sameFindingsWithoutTracking() {
        assertTrue(samples.size >= 16)
        samples.forEach { apk -> assertEquals(apk.name, findings(apk, true), findings(apk, false)) }
    }

    @Test
    fun noTrackingGapWhenTrackingIsOff() {
        samples.forEach { apk -> assertTrue(apk.name, DexScanner.scan(listOf(apk), track = false).gaps.none { it.kind == GapKind.TRACKING }) }
    }
}
