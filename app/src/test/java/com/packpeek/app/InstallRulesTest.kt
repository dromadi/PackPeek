package com.packpeek.app

import com.packpeek.app.model.*
import org.junit.Assert.*
import org.junit.Test

class InstallRulesTest {
    private fun input(
        minSdk: Int = 24,
        targetSdk: Int = 34,
        deviceSdk: Int = 35,
        abis: List<String> = emptyList(),
        valid: Boolean? = true,
        signer: Boolean? = null,
        version: Long = 10,
        installed: Long? = null,
        features: List<String> = emptyList(),
    ) =
        ReadinessInput(
            minSdk,
            targetSdk,
            deviceSdk,
            abis,
            listOf("arm64-v8a", "armeabi-v7a"),
            valid,
            signer,
            version,
            installed,
            features,
        )

    @Test
    fun statusMessageCodeWinsOverGenericStatus() {
        assertEquals(
            InstallFailure.SIGNATURE,
            InstallErrors.classify(
                5,
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match",
            ),
        )
        assertEquals(
            InstallFailure.DOWNGRADE,
            InstallErrors.classify(4, "INSTALL_FAILED_VERSION_DOWNGRADE"),
        )
        assertEquals(InstallFailure.ABI, InstallErrors.classify(7, "INSTALL_FAILED_NO_MATCHING_ABIS"))
        assertEquals(
            InstallFailure.TARGET,
            InstallErrors.classify(1, "INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 23"),
        )
    }

    @Test
    fun statusCodeIsFallback() {
        assertEquals(InstallFailure.ABORTED, InstallErrors.classify(3, null))
        assertEquals(InstallFailure.STORAGE, InstallErrors.classify(6, ""))
        assertEquals(InstallFailure.UNKNOWN, InstallErrors.classify(1, "something else"))
    }

    @Test
    fun cleanNewInstallHasNoIssues() {
        assertTrue(readiness(input(abis = listOf("arm64-v8a", "x86_64"))).isEmpty())
    }

    @Test
    fun detectsDowngradeAndSignerOnlyWhenInstalled() {
        assertEquals(
            listOf(ReadinessIssue.SIGNER, ReadinessIssue.DOWNGRADE),
            readiness(input(signer = false, version = 5, installed = 9)),
        )
        assertTrue(readiness(input(signer = false, version = 5, installed = null)).isEmpty())
    }

    @Test
    fun detectsDeviceIncompatibility() {
        assertEquals(
            listOf(
                ReadinessIssue.SIGNATURE_INVALID,
                ReadinessIssue.SDK,
                ReadinessIssue.TARGET,
                ReadinessIssue.ABI,
                ReadinessIssue.FEATURES,
            ),
            readiness(
                input(
                    minSdk = 36,
                    targetSdk = 22,
                    abis = listOf("x86"),
                    valid = false,
                    features = listOf("android.hardware.nfc"),
                )
            ),
        )
        // Split-name spelling of an ABI still matches the device list.
        assertTrue(readiness(input(abis = listOf("arm64_v8a"))).isEmpty())
    }

    @Test
    fun archiveKindFromLayout() {
        assertEquals(ArchiveKind.APK, archiveKind(true, emptyList()))
        assertEquals(ArchiveKind.XAPK, archiveKind(false, listOf("manifest.json", "base.apk")))
        assertEquals(ArchiveKind.APKM, archiveKind(false, listOf("info.json", "base.apk")))
        assertEquals(ArchiveKind.APKS, archiveKind(false, listOf("base.apk", "split_1.apk")))
    }

    @Test
    fun csvEscapesQuotesCommasAndFormulas() {
        val app =
            AppRecord(
                "a.b",
                "=HYPERLINK(\"x\"), evil",
                "1.0",
                7,
                24,
                34,
                false,
                "/base.apk",
                emptyList(),
                123,
                0,
                0,
                "",
                1,
            )
        val lines = appListCsv(listOf(app)).split("\r\n")
        assertTrue(lines[0].startsWith("label,package,"))
        assertTrue(lines[1].startsWith("\"'=HYPERLINK(\"\"x\"\"), evil\",a.b,1.0,7,24,34,false,false,123,SIDE,,1970-01-01T00:00:00Z"))
    }

    @Test
    fun aLostReportDoesNotBlockForever() {
        // Session alive: wait, until the timeout.
        assertEquals(InstallWait.WAIT, InstallErrors.settle(null, false, 30_000))
        assertEquals(InstallWait.NO_RESULT, InstallErrors.settle(null, false, InstallErrors.TIMEOUT_MS))
        // Session just gone: the report may still be on its way.
        assertEquals(InstallWait.WAIT, InstallErrors.settle(1_000, false, 60_000))
        // Gone for a while: an updated package means it was installed, otherwise nothing came back.
        assertEquals(InstallWait.SUCCESS, InstallErrors.settle(InstallErrors.GRACE_MS, true, 60_000))
        assertEquals(InstallWait.NO_RESULT, InstallErrors.settle(InstallErrors.GRACE_MS, false, 60_000))
    }

    @Test
    fun resultsAreMatchedBySession() {
        assertTrue(InstallResult("a.b", sessionId = 7).answers(7, "a.b"))
        assertFalse(InstallResult("a.b", sessionId = 8).answers(7, "a.b"))
        // Without a session id, the package name decides.
        assertTrue(InstallResult("a.b").answers(7, "a.b"))
        assertFalse(InstallResult("c.d").answers(7, "a.b"))
    }

    /** Android 14 refuses targets below 23; Android 15 and later below 24. */
    @Test
    fun targetLimitFollowsTheAndroidVersion() {
        assertNull(InstallErrors.minTargetSdk(33))
        assertEquals(23, InstallErrors.minTargetSdk(34))
        assertEquals(24, InstallErrors.minTargetSdk(35))
        assertEquals(24, InstallErrors.minTargetSdk(36))
        // A target-23 APK is fine on Android 14 but refused on Android 15.
        assertFalse(ReadinessIssue.TARGET in readiness(input(targetSdk = 23, deviceSdk = 34)))
        assertTrue(ReadinessIssue.TARGET in readiness(input(targetSdk = 23, deviceSdk = 35)))
    }
}
