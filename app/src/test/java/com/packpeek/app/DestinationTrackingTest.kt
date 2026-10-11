package com.packpeek.app

import com.packpeek.app.engine.risk.*
import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Where a traced value goes: hosts of URL constants that travel into the same send call
 * (tools/scam-samples/kotlin/destination-cookie, compiled by Kotlin, inert).
 */
class DestinationTrackingTest {
    private val apk = File(requireNotNull(javaClass.getResource("/samples/destination-cookie.apk")).toURI())
    private val traced by lazy {
        var out: Map<String, Map<Capability, Map<Capability, Set<String>>>> = emptyMap()
        DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true) { out = it }
        out
    }

    private fun cookieHosts(method: String) = traced[method]?.get(NETWORK)?.get(COOKIES)

    /** The address is passed to a helper; the cookie arrives at the same send through another parameter. */
    @Test
    fun cookieToInstagramThroughAHelper() {
        assertEquals(setOf("i.instagram.com"), cookieHosts("sim.app.destination.Main.toInstagram"))
    }

    /** From a coroutine, to a base address kept in a field of the API client. */
    @Test
    fun cookieToOwnServerFromACoroutine() {
        assertEquals(setOf("collect.sim-server.example"), cookieHosts("sim.app.destination.Main.toOwnServer"))
        assertEquals(setOf("collect.sim-server.example"), cookieHosts("sim.app.destination.Main\$toOwnServer\$1.invokeSuspend"))
    }

    /** A small app is tracked completely: no gap about value tracking is reported. */
    @Test
    fun smallAppIsTrackedCompletely() {
        val code = DexScanner.scan(listOf(apk))
        assertTrue(code.gaps.none { it.kind == GapKind.TRACKING || it.kind == GapKind.LIMIT })
    }

    /** Reading the cookie and sending a fixed text to the server is not the cookie going there. */
    @Test
    fun fixedTextIsNotTheCookie() {
        assertNull(cookieHosts("sim.app.destination.Main.pingOnly"))
    }

    /** The destinations reach the evidence the report shows. */
    @Test
    fun destinationsReachTheFlows() {
        val code = DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true)
        val traced = code.flows.filter { it.source == COOKIES && it.traced }
        assertTrue(traced.isNotEmpty())
        assertTrue(traced.all { it.destinations.isNotEmpty() })
        assertTrue(traced.flatMap { it.destinations }.toSet().all { it == "i.instagram.com" || it == "collect.sim-server.example" })
    }

    /** The finding's evidence names the address, so the screen and the report can show it. */
    @Test
    fun evidenceNamesTheAddress() {
        val report =
            RiskRules.evaluate(
                ManifestScanner.scan(listOf(apk)),
                RiskContext("Destination Sim", false, false, "PLAY", false, 900),
                null,
                DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true),
            )
        val hosts = report.findings.flatMap { it.evidence }.flatMap(RiskRules::destinations).toSet()
        assertTrue(hosts.isNotEmpty())
        assertTrue(hosts.all { it == "i.instagram.com" || it == "collect.sim-server.example" })
    }
}
