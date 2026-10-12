package com.packpeek.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.packpeek.app.engine.HashLookup
import com.packpeek.app.engine.VirusTotal
import com.packpeek.app.engine.risk.*
import com.packpeek.app.model.AppRecord
import com.packpeek.app.ui.EvidenceReport
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EvidenceReportTest {
    private val sample = File(requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI())

    @Test
    fun virusTotalReportIsParsed() {
        val json =
            """{"data":{"attributes":{"last_analysis_stats":{"malicious":41,"suspicious":3,"undetected":20,"harmless":0,"timeout":2},
               "popular_threat_classification":{"suggested_threat_label":"trojan.smsthief/spymax"}}}}"""
        assertEquals(HashLookup.Found(41, 3, 66, "trojan.smsthief/spymax"), VirusTotal.parse(json))
    }

    @Test
    fun reportListsEvidenceDeepResultsAndFingerprint() {
        val code = DexScanner.scan(listOf(sample), DexScanner.DEEP_TIME_LIMIT_MS, deep = true)
        val report =
            RiskRules.evaluate(
                ManifestScanner.scan(listOf(sample)),
                RiskContext("Undangan Pernikahan", true, false, "SIDE", true, 1),
                null,
                code,
            )
        val app = AppRecord("sim.packpeek.undangan", "Undangan Pernikahan", "1.0-sim", 1, 24, 28, false, sample.path, emptyList(), sample.length(), 0, 0, "", 0, true)
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val text = EvidenceReport.text(res, app, report, code, HashLookup.NotFound)
        assertTrue(text.contains("SHA-256: ${code.deep!!.sha256}"))
        assertTrue(text, text.contains("T3 Value of SMS traced into the call that sends to a Telegram bot"))
        assertTrue(text.contains("OtpReceiver"))
        assertTrue(text.contains("code outside recognized SDKs"))
        assertFalse(text.contains("Connects to "))
        assertTrue(text.contains("Decoded with XOR key 0x2a"))
        assertTrue(text.contains("assets/payload.bin"))
        assertFalse("token must stay masked", text.contains("SIMULATIONxONLY"))
        // PdfDocument has no working native side under Robolectric; the PDF is checked on the emulator.
    }
}
