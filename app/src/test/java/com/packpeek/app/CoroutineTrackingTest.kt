package com.packpeek.app

import com.packpeek.app.engine.risk.*
import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Values followed through Kotlin coroutines, compiled by the real Kotlin compiler
 * (tools/scam-samples/kotlin/coroutine-cookie, inert): a lambda capturing the value, a suspend
 * function keeping its parameter in its continuation (L$0), and a suspend function returning it.
 */
class CoroutineTrackingTest {
    private val apk = File(requireNotNull(javaClass.getResource("/samples/coroutine-cookie.apk")).toURI())
    private val traced by lazy {
        var out: Map<String, Map<Capability, Map<Capability, Set<String>>>> = emptyMap()
        DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true) { out = it }
        out
    }

    private fun cookieSent(method: String) = traced[method]?.get(NETWORK)?.containsKey(COOKIES) == true

    @Test
    fun capturedByALaunchedLambda() {
        // scope.launch { post(c) }: c is stored in Main$captured$1.$c and read in invokeSuspend.
        assertTrue(cookieSent("sim.app.coroutine.Main.captured"))
        assertTrue(cookieSent("sim.app.coroutine.Main\$captured\$1.invokeSuspend"))
    }

    @Test
    fun keptAcrossASuspension() {
        // upload(data) keeps data in Main$upload$1.L$0 across delay(), then sends it.
        assertTrue(cookieSent("sim.app.coroutine.Main.spilled"))
    }

    @Test
    fun returnedBySuspendFunction() {
        // val c = readLater() (suspends, then returns the cookie); post(c).
        assertTrue(cookieSent("sim.app.coroutine.Main\$returned\$1.invokeSuspend"))
    }

    /** Reading the cookie only to decide, then sending a fixed text, is not a traced value. */
    @Test
    fun aDecisionIsNotAValue() {
        assertFalse(cookieSent("sim.app.coroutine.Main.unrelated"))
        assertFalse(cookieSent("sim.app.coroutine.Main\$unrelated\$1.invokeSuspend"))
    }

    /** The captured outer object (this$0) is not the cookie, even though both go into the lambda. */
    @Test
    fun parametersAreKeptApart() {
        val code = DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true)
        assertTrue(code.flows.filter { it.source == COOKIES }.any { it.traced })
        // Every traced path starts where the cookie is actually read or carried.
        assertTrue(code.flows.filter { it.traced }.none { it.method.endsWith("Main.unrelated") })
    }
}
