package com.packpeek.app

import com.packpeek.app.engine.risk.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The progress shown while one app is checked: it only moves forward and ends at 100%. */
class ScanProgressTest {
    private val apk = File(requireNotNull(javaClass.getResource("/samples/two-clients.apk")).toURI())

    private fun steps(track: Boolean, deep: Boolean = false) =
        mutableListOf<ScanProgress>().also { list -> DexScanner.scan(listOf(apk), deep = deep, track = track, progress = { list += it }) }

    @Test
    fun movesForwardToTheEnd() {
        for ((track, deep) in listOf(true to false, false to false, true to true)) {
            val steps = steps(track, deep)
            assertTrue(steps.size >= 2)
            assertEquals(0f, steps.first().fraction, 0.001f)
            assertEquals(1f, steps.last().fraction, 0.001f)
            assertEquals(steps.map { it.fraction }.sorted(), steps.map { it.fraction })
            assertTrue(steps.all { it.dexFile in 1..it.dexFiles })
            assertEquals(track, steps.any { it.resolving })
        }
    }
}
