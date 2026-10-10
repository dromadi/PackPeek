package com.packpeek.app

import com.packpeek.app.engine.risk.*
import org.junit.Assert.*
import org.junit.Test

class RiskRulesTest {
    private fun facts(
        permissions: List<String> = emptyList(),
        components: List<ComponentFacts> = emptyList(),
        launcher: Boolean = true,
        accessibility: Map<String, AccessibilityConfig> = emptyMap(),
    ) =
        ManifestFacts(
            packageName = "test.app",
            apks = listOf("base.apk"),
            permissions = permissions.associateWith { "base.apk" },
            components = components,
            usesCleartextTraffic = null,
            hasNetworkSecurityConfig = false,
            debuggable = false,
            hasLauncher = launcher,
            accessibility = accessibility,
            deviceAdminPolicies = emptyMap(),
            unresolvedConfigs = 0,
            unreadable = emptyList(),
        )

    private fun context(
        label: String = "Example",
        fromFile: Boolean = false,
        system: Boolean = false,
        source: String = "PLAY",
        debugCert: Boolean = false,
    ) = RiskContext(label, fromFile, system, source, debugCert, 900)

    private fun receiver(name: String, action: String, priority: Int = 0) =
        ComponentFacts(
            "receiver",
            name,
            true,
            "",
            true,
            listOf(IntentFilterFacts(setOf(action), emptySet(), priority)),
            emptyMap(),
            0,
            "base.apk",
        )

    private fun service(name: String, permission: String) =
        ComponentFacts("service", name, true, permission, true, emptyList(), emptyMap(), 0, "base.apk")

    private val internet = RiskRules.INTERNET
    private val receiveSms = "android.permission.RECEIVE_SMS"

    /** The fake wedding-invitation APK: hidden icon, SMS receiver, internet, lure name. */
    @Test
    fun invitationApkIsAStrongOtpTheftIndicator() {
        val report =
            RiskRules.evaluate(
                facts(
                    listOf(internet, receiveSms, "android.permission.READ_SMS"),
                    listOf(receiver("test.app.SmsReceiver", RiskRules.SMS_RECEIVED, 999)),
                    launcher = false,
                ),
                context(label = "Undangan Pernikahan", fromFile = true, debugCert = true),
                null,
            )
        val otp = report.findings.first()
        assertEquals(RiskId.OTP_THEFT, otp.id)
        assertEquals(Severity.STRONG, otp.severity)
        val keys = otp.evidence.mapNotNull { it.stateKey }.toSet()
        assertTrue(
            keys.containsAll(
                setOf(
                    StateKey.NO_LAUNCHER,
                    StateKey.FILE_NOT_INSTALLED,
                    StateKey.DEBUG_CERTIFICATE,
                    StateKey.LURE_LABEL,
                )
            )
        )
        assertTrue(otp.evidence.any { it.detail.contains("priority 999") })
        assertTrue(report.findings.any { it.id == RiskId.HIDDEN_ICON })
        assertFalse(report.coverage.codeAnalyzed)
    }

    /** A Play Store downloader with clipboard/WebView needs is never a strong indicator. */
    @Test
    fun ordinaryPrivacyAppHasNoStrongIndicator() {
        val report =
            RiskRules.evaluate(
                facts(
                    listOf(
                        internet,
                        "android.permission.READ_MEDIA_VIDEO",
                        "android.permission.POST_NOTIFICATIONS",
                    )
                ),
                context(label = "InSaver"),
                DeviceState(usageAccess = true, network = NetworkUsage(5_000_000, 1_000_000, 30)),
            )
        assertEquals(0, report.count(Severity.STRONG))
        val usage = report.findings.single { it.id == RiskId.NETWORK_USAGE }
        assertEquals(Severity.INFO, usage.severity)
        assertEquals(EvidenceLevel.OBSERVED, usage.strongestLevel)
    }

    @Test
    fun defaultSmsAppIsExplainedNotAccused() {
        val report =
            RiskRules.evaluate(
                facts(
                    listOf(internet, receiveSms, "android.permission.SEND_SMS"),
                    listOf(receiver("test.app.Sms", RiskRules.SMS_RECEIVED)),
                    launcher = false,
                ),
                context(source = "SIDE"),
                DeviceState(defaultSmsApp = true, granted = setOf(receiveSms)),
            )
        assertEquals(0, report.count(Severity.STRONG))
        val sms = report.findings.single { it.id == RiskId.SMS }
        assertEquals(Severity.INFO, sms.severity)
        assertEquals(ContextNote.DEFAULT_SMS_APP, sms.note)
        assertTrue(sms.evidence.any { it.stateKey == StateKey.PERMISSION_GRANTED })
    }

    @Test
    fun systemAppsStayInformational() {
        val report =
            RiskRules.evaluate(
                facts(
                    listOf(internet, receiveSms, "android.permission.SYSTEM_ALERT_WINDOW"),
                    listOf(receiver("android.Sms", RiskRules.SMS_RECEIVED, 999)),
                    launcher = false,
                ),
                context(system = true, source = "SYSTEM"),
                null,
            )
        assertTrue(report.findings.all { it.severity == Severity.INFO })
        assertTrue(report.findings.none { it.id == RiskId.HIDDEN_ICON })
    }

    @Test
    fun screenReadingAccessibilityWithConcealmentIsStrong() {
        val acc = service("test.app.Watch", RiskRules.BIND_ACCESSIBILITY)
        val report =
            RiskRules.evaluate(
                facts(
                    listOf(internet),
                    listOf(acc),
                    launcher = false,
                    accessibility = mapOf(acc.name to AccessibilityConfig(true, true, -1)),
                ),
                context(source = "SIDE"),
                DeviceState(accessibilityEnabled = setOf(acc.name)),
            )
        val spying = report.findings.first()
        assertEquals(RiskId.SCREEN_SPYING, spying.id)
        assertEquals(EvidenceLevel.OBSERVED, spying.strongestLevel)
        assertTrue(spying.evidence.any { it.detail == "canRetrieveWindowContent" })
    }

    /** A password manager from Play: accessibility is shown, but not as spying. */
    @Test
    fun accessibilityAloneIsAttentionOnly() {
        val acc = service("test.app.Autofill", RiskRules.BIND_ACCESSIBILITY)
        val report =
            RiskRules.evaluate(
                facts(
                    listOf(internet),
                    listOf(acc),
                    accessibility = mapOf(acc.name to AccessibilityConfig(true, false, 0)),
                ),
                context(label = "Passwords"),
                DeviceState(accessibilityEnabled = setOf(acc.name)),
            )
        assertEquals(0, report.count(Severity.STRONG))
        assertEquals(Severity.ATTENTION, report.findings.single { it.id == RiskId.ACCESSIBILITY }.severity)
    }

    @Test
    fun heavyBackgroundTrafficNeedsAttention() {
        val report =
            RiskRules.evaluate(
                facts(listOf(internet)),
                context(),
                DeviceState(usageAccess = true, network = NetworkUsage(0, 250_000_000, 30)),
            )
        assertEquals(Severity.ATTENTION, report.findings.single { it.id == RiskId.NETWORK_USAGE }.severity)
    }

    /** End to end on the code-free sample built by tools/undangan-sim/build.sh. */
    @Test
    fun undanganSampleIsScannedAndFlagged() {
        val apk =
            java.io.File(
                requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI()
            )
        val facts = ManifestScanner.scan(listOf(apk))
        assertEquals("sim.packpeek.undangan", facts.packageName)
        assertFalse(facts.hasLauncher)
        assertEquals(999, facts.receiversFor(RiskRules.SMS_RECEIVED).single().maxPriority(RiskRules.SMS_RECEIVED))
        val config = facts.accessibility.getValue("sim.packpeek.undangan.ScreenService")
        assertTrue(config.canRetrieveWindowContent)
        assertTrue(config.canPerformGestures)
        assertEquals(0, facts.unresolvedConfigs)

        val report =
            RiskRules.evaluate(
                facts,
                context(label = "Undangan Pernikahan", fromFile = true, debugCert = true),
                null,
            )
        val ids = report.findings.map { it.id }
        assertEquals(listOf(RiskId.OTP_THEFT, RiskId.SCREEN_SPYING), ids.take(2))
        assertTrue(
            ids.containsAll(
                listOf(
                    RiskId.SMS,
                    RiskId.SMS_SEND,
                    RiskId.ACCESSIBILITY,
                    RiskId.OVERLAY,
                    RiskId.HIDDEN_ICON,
                    RiskId.UNTRUSTED_SIGNER,
                )
            )
        )
        assertNull(report.coverage.usageAccess)
        assertFalse(report.coverage.deviceStateRead)
    }
}
