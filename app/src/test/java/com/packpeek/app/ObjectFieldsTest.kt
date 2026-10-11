package com.packpeek.app

import com.packpeek.app.engine.risk.*
import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Fields per object (tools/scam-samples/kotlin/two-clients, Kotlin, inert): one Client class with
 * the base address in a field, two instances kept in a singleton plus a local one. Each send must
 * name only the address of the instance it was made on.
 */
class ObjectFieldsTest {
    private val apk = File(requireNotNull(javaClass.getResource("/samples/two-clients.apk")).toURI())
    private val traced by lazy {
        var out: Map<String, Map<Capability, Map<Capability, Set<String>>>> = emptyMap()
        DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true) { out = it }
        out
    }

    private fun hosts(method: String, source: Capability) = traced[method]?.get(NETWORK)?.get(source)

    @Test
    fun cookieGoesOnlyToInstagram() {
        assertEquals(setOf("i.instagram.com"), hosts("sim.app.twoclients.Main.profile", COOKIES))
    }

    @Test
    fun clipboardGoesOnlyToTheOwnServer() {
        assertEquals(setOf("collect.sim-server.example"), hosts("sim.app.twoclients.Main.parse", CLIPBOARD))
    }

    @Test
    fun aLocalInstanceKeepsItsOwnAddress() {
        assertEquals(setOf("local.sim-server.example"), hosts("sim.app.twoclients.Main.local", COOKIES))
    }
}
