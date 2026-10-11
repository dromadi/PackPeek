package com.packpeek.app

import com.packpeek.app.engine.risk.*
import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** A bare IP is strong evidence only when it is public, sits in the sending code, and the source is untrusted. */
class IpCalibrationTest {
    @Test
    fun onlyPublicAddressesCount() {
        listOf("185.199.108.153", "45.33.32.156", "2001:db8::1").forEach { assertTrue(it, NetAddress.isPublic(it)) }
        listOf(
                "10.0.2.2", "192.168.1.1", "172.16.0.5", "172.31.255.1", "127.0.0.1", "169.254.1.1",
                "100.64.0.1", "0.0.0.0", "255.255.255.255", "224.0.0.251", "8.8.8.8", "1.1.1.1",
                "::1", "fe80::1", "fd00::1", "example.com", "999.1.1.1", "1.2.3",
            )
            .forEach { assertFalse(it, NetAddress.isPublic(it)) }
        assertTrue(NetAddress.isPublic("172.32.0.1"))
        assertFalse(NetAddress.isIp("api.example.com"))
    }

    private val manifest by lazy {
        ManifestScanner.scan(listOf(File(requireNotNull(javaClass.getResource("/samples/control-clean.apk")).toURI())))
    }

    private fun code(flowIp: String?, vararg hosts: String) =
        CodeFacts(
            1, 10, 10,
            mapOf(CONTACTS to listOf(CodeHit(CONTACTS, "ContentResolver.query", "t.app.Sync.run", null)),
                NETWORK to listOf(CodeHit(NETWORK, "URL.openConnection", "t.app.Sync.run", null))),
            listOf(CodeFlow(CONTACTS, NETWORK, "t.app.Sync.run", "ContentResolver.query", "URL.openConnection", flowIp)),
            hosts.map { Destination(it, true, NetAddress.isIp(it), NetAddress.isPublic(it)) },
            emptyList(),
            emptyList(),
        )

    private val sideFile = RiskContext("Cleaner", true, false, "SIDE", true, 1)
    private val play = RiskContext("Cleaner", false, false, "PLAY", false, 900)

    private fun RiskReport.severityOf(id: RiskId) = findings.firstOrNull { it.id == id }?.severity

    @Test
    fun publicIpInTheSendingCodeOfAnUntrustedFileIsStrong() {
        val report = RiskRules.evaluate(manifest, sideFile, null, code("185.199.108.153", "185.199.108.153"))
        assertEquals(Severity.STRONG, report.severityOf(RiskId.DATA_THEFT))
        val theft = report.findings.single { it.id == RiskId.DATA_THEFT }
        assertTrue(theft.evidence.any { it.kind == EvidenceKind.DESTINATION && it.detail.startsWith("185.199.108.153|") })
    }

    @Test
    fun theSameFromPlayNeedsAttentionOnly() {
        val report = RiskRules.evaluate(manifest, play, null, code("185.199.108.153", "185.199.108.153"))
        assertEquals(Severity.ATTENTION, report.severityOf(RiskId.DATA_THEFT))
        assertTrue(report.findings.none { it.severity == Severity.STRONG })
    }

    /** An IP somewhere in the app is not linked to the code that sends personal data. */
    @Test
    fun anIpElsewhereIsNotTheft() {
        val report = RiskRules.evaluate(manifest, sideFile, null, code(null, "185.199.108.153"))
        assertNull(report.severityOf(RiskId.DATA_THEFT))
        assertEquals(Severity.ATTENTION, report.severityOf(RiskId.HIDDEN_DESTINATION))
    }

    @Test
    fun localAddressesAreIgnored() {
        val report = RiskRules.evaluate(manifest, sideFile, null, code(null, "192.168.1.10", "10.0.2.2", "8.8.8.8"))
        assertNull(report.severityOf(RiskId.DATA_THEFT))
        assertNull(report.severityOf(RiskId.HIDDEN_DESTINATION))
    }

    /** The scanner ties the IP to the class that sends, through the call from the reading method. */
    @Test
    fun scannerLinksTheIpToTheSendingClass() {
        val apk = File(requireNotNull(javaClass.getResource("/samples/spyware-exfil.apk")).toURI())
        val code = DexScanner.scan(listOf(apk))
        val flow = code.flows.first { it.sink == NETWORK && it.ipHost != null }
        assertEquals("185.199.108.153", flow.ipHost)
        assertTrue(flow.method.startsWith("sim.scam.spyware.Main"))
        assertTrue(code.destinations.single { it.host == "185.199.108.153" }.publicIp)
    }
}
