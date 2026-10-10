package com.packpeek.app

import com.packpeek.app.engine.risk.*
import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Deep analysis on the v2 sample: Intent hop, Base64 and XOR texts, hidden and encrypted payloads. */
class DeepAnalysisTest {
    private val sample = File(requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI())
    private val code by lazy { DexScanner.scan(listOf(sample), DexScanner.DEEP_TIME_LIMIT_MS, deep = true) }
    private val deep by lazy { requireNotNull(code.deep) }

    /** OtpReceiver reads SMS and starts UploadService, which calls Courier.deliver. */
    @Test
    fun followsAnIntentToTheServiceThatSends() {
        val chain = deep.chains.first { it.source == SMS_READ && it.toSink.any { s -> s.via == StepVia.INTENT } }
        assertEquals("sim.packpeek.undangan.OtpReceiver.onReceive", chain.toSink.first().method)
        assertEquals("sim.packpeek.undangan.UploadService.onStartCommand", chain.toSink[1].method)
        assertTrue(chain.toSink.last().method.endsWith("Courier.deliver"))
        assertTrue(chain.sink == NETWORK || chain.sink == TELEGRAM)
        // The one-level scan cannot see this chain.
        val shallow = DexScanner.scan(listOf(sample))
        assertTrue(shallow.flows.none { it.method.endsWith("OtpReceiver.onReceive") })
        assertTrue(code.flows.any { it.method.endsWith("OtpReceiver.onReceive") })
    }

    @Test
    fun decodesBase64AndXorTexts() {
        val base64 = deep.decoded.single { it.technique == Decoding.BASE64 }
        assertEquals(TELEGRAM, base64.capability)
        assertTrue(base64.decoded.contains("api.telegram.org"))
        assertFalse("token must stay masked", base64.decoded.contains("SIMULATIONxONLY"))
        val xor = deep.decoded.single { it.technique == Decoding.XOR }
        assertEquals(DISCORD, xor.capability)
        assertEquals("0x2a", xor.detail)
        // The decoded bot counts as a hidden destination in a class that sends.
        assertTrue(code.appHits(TELEGRAM).any { it.method.endsWith("Courier.deliver") && it.api.startsWith("Base64") })
    }

    @Test
    fun scansHiddenAndEncryptedPayloads() {
        val payload = deep.payloads.single { it.kind == PayloadKind.DEX }
        assertEquals("assets/payload.bin", payload.path)
        assertTrue(payload.classes >= 1)
        assertTrue(SMS_READ in payload.capabilities)
        val encrypted = deep.payloads.single { it.kind == PayloadKind.ENCRYPTED }
        assertEquals("assets/config.dat", encrypted.path)
        assertTrue(encrypted.entropy > 7.6)
    }

    @Test
    fun keepsListingsAndFingerprint() {
        val listing = deep.listings.getValue("sim.packpeek.undangan.SmsReceiver.onReceive")
        assertTrue(listing.any { it.startsWith("⚠ call SmsMessage.createFromPdu") })
        assertEquals(64, deep.sha256.length)
    }

    /** Deep chains upgrade the findings: the Intent path becomes T3 evidence. */
    @Test
    fun deepEvidenceReachesTheRules() {
        val report =
            RiskRules.evaluate(
                ManifestScanner.scan(listOf(sample)),
                RiskContext("Undangan Pernikahan", true, false, "SIDE", true, 1),
                null,
                code,
            )
        val otp = report.findings.single { it.id == RiskId.OTP_THEFT }
        assertTrue(otp.evidence.any { it.kind == EvidenceKind.FLOW && it.location.endsWith("OtpReceiver.onReceive") })
    }
}
