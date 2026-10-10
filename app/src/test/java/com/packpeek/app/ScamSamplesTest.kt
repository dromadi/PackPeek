package com.packpeek.app

import com.packpeek.app.engine.risk.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Every scam sample must trigger its intended pattern; the two controls must not be strong. */
class ScamSamplesTest {
    private fun analyse(name: String, label: String, source: String = "SIDE"): RiskReport {
        val apk = File(requireNotNull(javaClass.getResource("/samples/$name.apk")).toURI())
        val code = DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true)
        return RiskRules.evaluate(
            ManifestScanner.scan(listOf(apk)),
            RiskContext(label, true, false, source, true, 1),
            null,
            code,
        )
    }

    private fun RiskReport.ids(sev: Severity) = findings.filter { it.severity == sev }.map { it.id }.toSet()

    @Test fun otpTelegram() = assertTrue(RiskId.OTP_THEFT in analyse("otp-telegram", "Undangan Pernikahan").ids(Severity.STRONG))

    @Test
    fun notifWebhook() {
        val r = analyse("notif-webhook", "Status Pengiriman")
        assertTrue(RiskId.NOTIFICATIONS in r.findings.map { it.id })
        assertTrue(r.count(Severity.STRONG) > 0 || RiskId.HIDDEN_DESTINATION in r.findings.map { it.id })
    }

    @Test
    fun bankOverlay() {
        val r = analyse("bank-overlay", "Pembaruan Sistem")
        assertTrue(RiskId.SCREEN_SPYING in r.ids(Severity.STRONG))
        assertTrue(RiskId.OVERLAY in r.findings.map { it.id })
    }

    @Test fun clipboardSwap() = assertTrue(RiskId.CLIPBOARD in analyse("clipboard-swap", "QR Pembayaran").findings.map { it.id })

    @Test fun spywareExfil() = assertTrue(RiskId.DATA_THEFT in analyse("spyware-exfil", "Cleaner Pro").ids(Severity.STRONG))

    @Test fun callForward() = assertTrue(RiskId.CALL_FORWARDING in analyse("call-forward", "Info BPJS").findings.map { it.id })

    @Test fun dropper() = assertTrue(RiskId.DYNAMIC_CODE in analyse("dropper", "Font Installer").findings.map { it.id })

    @Test fun deviceAdmin() = assertTrue(RiskId.DEVICE_ADMIN in analyse("device-admin", "Google Services").findings.map { it.id })

    @Test
    fun controlAdwareIsNotStrong() {
        val r = analyse("control-adware", "Flashlight Free", source = "PLAY")
        assertEquals(0, r.count(Severity.STRONG))
        assertTrue(RiskId.AD_SDKS in r.findings.map { it.id })
    }

    @Test
    fun controlCleanIsNotStrong() {
        val r = analyse("control-clean", "Catatan Harian", source = "PLAY")
        assertEquals(0, r.count(Severity.STRONG))
        assertTrue(r.findings.none { it.id == RiskId.HIDDEN_DESTINATION })
    }

    /** a(String) reads the clipboard and a(int) sends: two methods, so no clipboard path. */
    @Test
    fun overloadsAreNotMerged() {
        val apk = File(requireNotNull(javaClass.getResource("/samples/control-overload.apk")).toURI())
        val code = DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true)
        assertTrue(code.inApp(Capability.CLIPBOARD))
        assertTrue(code.flows.none { it.source == Capability.CLIPBOARD })
        assertTrue(analyse("control-overload", "Penghitung").findings.none { it.severity == Severity.STRONG })
    }
}
