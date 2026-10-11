package com.packpeek.app

import com.packpeek.app.data.CheckStore
import com.packpeek.app.data.SavedCheck
import com.packpeek.app.engine.risk.*
import com.packpeek.app.model.AppRecord
import com.packpeek.app.ui.RiskBrief
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Check results saved on the device, so a check of all apps goes on where it stopped. */
class CheckStoreTest {
    private val dir = Files.createTempDirectory("checks").toFile()
    private val file = File(dir, "checks.tsv")
    private val app = AppRecord("com.example", "Example", "1.0", 7, 24, 34, false, "/base.apk", emptyList(), 1, 100, 50, "com.android.vending", 10_001)

    @Test
    fun savedResultsComeBack() {
        val brief = RiskBrief(1, 2, RiskId.OTP_THEFT, EvidenceLevel.FLOW, true)
        CheckStore(file, 20019).save(mapOf(app.packageName to brief.saved(app)))
        val loaded = CheckStore(file, 20019).load()
        assertEquals(brief, RiskBrief.of(loaded.getValue(app.packageName), app))
    }

    @Test
    fun anotherPackPeekVersionStartsOver() {
        CheckStore(file, 20019).save(mapOf(app.packageName to SavedCheck(7, 100, 0, 0, null, null, true)))
        assertTrue(CheckStore(file, 20020).load().isEmpty())
    }

    @Test
    fun anUpdatedAppIsCheckedAgain() {
        val saved = SavedCheck(7, 100, 0, 1, "SMS", "CODE", true)
        assertNotNull(RiskBrief.of(saved, app))
        assertNull(RiskBrief.of(saved, app.copy(versionCode = 8)))
        assertNull(RiskBrief.of(saved, app.copy(updated = 101)))
        // A finding this build does not know (renamed rule) is not shown from an old result.
        assertNull(RiskBrief.of(saved.copy(topId = "GONE"), app))
    }

    @Test
    fun damagedLinesAreSkipped() {
        file.writeText("packpeek-checks\t1\t5\ncom.ok\t1\t2\t0\t0\t\t\t1\nbroken line\ncom.bad\tx\t2\t0\t0\t\t\t1\n")
        assertEquals(setOf("com.ok"), CheckStore(file, 5).load().keys)
        assertTrue(CheckStore(File(dir, "missing.tsv"), 5).load().isEmpty())
    }
}
