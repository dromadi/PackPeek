package com.packpeek.app

import com.packpeek.app.engine.risk.*
import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DexScannerTest {
    private val sample = File(requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI())

    @Test
    fun findsSensitiveCallsWithTheirLocation() {
        val code = DexScanner.scan(listOf(sample))
        assertEquals(1, code.dexFiles)
        assertTrue(code.appClasses >= 4)
        val sms = code.appHits(SMS_READ)
        assertTrue(sms.any { it.api == "SmsMessage.createFromPdu" && it.method == "sim.packpeek.undangan.SmsReceiver.onReceive" })
        assertTrue(code.inApp(NETWORK))
        assertTrue(code.inApp(SCREEN_READ))
        assertTrue(code.inApp(HIDE_ICON))
        assertTrue(code.inApp(DYNAMIC_CODE))
        assertTrue(code.inApp(CALL_FORWARDING))
    }

    @Test
    fun maskTelegramTokensAndRecordsDestinations() {
        val code = DexScanner.scan(listOf(sample))
        val telegram = code.appHits(TELEGRAM).single()
        assertTrue(telegram.api.contains("api.telegram.org"))
        assertFalse("token must be masked", telegram.api.contains("SIMULATIONxONLY"))
        assertTrue(code.destinations.any { it.host == "api.telegram.org" && !it.cleartext })
    }

    /** onReceive reads SMS and calls Sender.send, which opens a Telegram connection. */
    @Test
    fun followsOneLevelOfCallsToFindFlows() {
        val code = DexScanner.scan(listOf(sample))
        val sms = code.flowsFrom(SMS_READ)
        assertTrue(sms.any { it.sink == TELEGRAM && it.method == "sim.packpeek.undangan.SmsReceiver.onReceive" })
        assertTrue(sms.any { it.sink == NETWORK })
        assertTrue(code.flowsFrom(SCREEN_READ).any { it.method.endsWith("ScreenService.onAccessibilityEvent") })
        // Hidden destinations are listed first.
        assertTrue(code.flows.first().sink in Capability.hiddenDestinations)
    }

    @Test
    fun reportsHiddenPayloadsAsCoverageGaps() {
        val code = DexScanner.scan(listOf(sample))
        assertTrue(code.gaps.any { it.kind == GapKind.HIDDEN_CODE && it.detail.endsWith("assets/payload.bin") })
    }

    @Test
    fun corruptDexIsAGapNotACrash() {
        val bytes = sample.readBytes()
        val random = kotlin.random.Random(7)
        repeat(200) {
            val copy = bytes.copyOf()
            repeat(20) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            val file = File.createTempFile("fuzz", ".apk").apply { deleteOnExit() }
            file.writeBytes(copy)
            DexScanner.scan(listOf(file), timeLimitMs = 2_000)
            file.delete()
        }
    }

    /** Detector.suspicious holds the same texts as the scam code but never queries or sends. */
    @Test
    fun textsWithoutCallsAreNotFlowsOrDestinations() {
        val code = DexScanner.scan(listOf(sample))
        assertTrue(code.flows.none { it.method.contains("Detector") })
        assertTrue(code.hits.values.flatten().none { it.method.contains("Detector") && it.capability in Capability.hiddenDestinations })
        // The real sender is still found.
        assertTrue(code.appHits(TELEGRAM).any { it.method.endsWith("Sender.send") })
    }
}
