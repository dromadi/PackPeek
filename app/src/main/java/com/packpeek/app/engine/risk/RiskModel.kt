package com.packpeek.app.engine.risk

/** How strong a piece of evidence is. Levels are shown side by side, never summed. */
enum class EvidenceLevel(val tag: String) {
    /** Written in a manifest; may never be used. */
    DECLARED("T1"),
    /** A call found in the app's code; may never run. (Phase 2.) */
    CODE("T2"),
    /** Reading data and sending it share a code path; still not proof. (Phase 3.) */
    FLOW("T3"),
    /** Seen on this device: an enabled service, a granted permission, data usage. */
    OBSERVED("T4"),
}

enum class Severity {
    /** Combined evidence matches a known abuse pattern. */
    STRONG,
    /** A sensitive capability worth understanding. */
    ATTENTION,
    /** Context; normal for many apps. */
    INFO,
}

enum class EvidenceKind { PERMISSION, COMPONENT, CONFIG, FLAG, CERTIFICATE, SOURCE, LABEL, STATE, NETWORK }

/** Device facts with translated wording in the UI. */
enum class StateKey {
    ACCESSIBILITY_ENABLED,
    NOTIFICATION_LISTENER_ENABLED,
    DEVICE_ADMIN_ACTIVE,
    DEFAULT_SMS_APP,
    PERMISSION_GRANTED,
    OVERLAY_ALLOWED,
    BATTERY_EXEMPT,
    SIDELOADED,
    FILE_NOT_INSTALLED,
    NO_LAUNCHER,
    DEBUG_CERTIFICATE,
    NEW_CERTIFICATE,
    LURE_LABEL,
    CLEARTEXT,
    INTERNET,
}

/**
 * One piece of evidence. [detail] is technical text (a permission or component name) or, for
 * [stateKey], an argument for the translated sentence; [location] says where it was found.
 */
data class Evidence(
    val level: EvidenceLevel,
    val kind: EvidenceKind,
    val detail: String,
    val location: String,
    val stateKey: StateKey? = null,
)

enum class RiskId {
    OTP_THEFT,
    SCREEN_SPYING,
    ACCESSIBILITY,
    NOTIFICATIONS,
    DEVICE_ADMIN,
    SMS,
    SMS_SEND,
    CONTACTS_CALLS,
    PHONE_IDENTITY,
    CAMERA_MIC,
    LOCATION,
    SCREEN_CAPTURE,
    OVERLAY,
    PERSISTENCE,
    HIDDEN_ICON,
    INSTALL_APPS,
    ALL_FILES,
    CLEARTEXT,
    NETWORK_USAGE,
    UNTRUSTED_SIGNER,
}

/** Why a capability is expected here, shown instead of the generic context. */
enum class ContextNote { NONE, DEFAULT_SMS_APP, SYSTEM_APP }

data class RiskFinding(
    val id: RiskId,
    val severity: Severity,
    val evidence: List<Evidence>,
    val note: ContextNote = ContextNote.NONE,
    /** Extra number for the text, e.g. background bytes for [RiskId.NETWORK_USAGE]. */
    val amount: Long = 0,
) {
    val strongestLevel
        get() = evidence.maxOf { it.level }

    val levels
        get() = evidence.map { it.level }.toSortedSet()
}

/** What the analysis covered and what it could not. */
data class Coverage(
    val apks: Int,
    val unreadable: List<String>,
    val unresolvedConfigs: Int,
    /** DEX code is analysed from phase 2 on. */
    val codeAnalyzed: Boolean,
    /** Device state is only available for installed apps. */
    val deviceStateRead: Boolean,
    /** null when not applicable (files); false when usage access was not granted. */
    val usageAccess: Boolean?,
)

data class RiskReport(val findings: List<RiskFinding>, val coverage: Coverage) {
    fun count(severity: Severity) = findings.count { it.severity == severity }
}

/** Facts about the app beyond its manifest. */
data class RiskContext(
    val label: String,
    val fromFile: Boolean,
    val system: Boolean,
    /** PLAY, SIDE, OTHER or SYSTEM, as in AppRecord.source. */
    val source: String,
    val debugCertificate: Boolean,
    /** Days since the signing certificate became valid; null if unknown. */
    val certificateAgeDays: Long?,
)

data class NetworkUsage(val foregroundBytes: Long, val backgroundBytes: Long, val days: Int)

/** What this device says about an installed app right now. */
data class DeviceState(
    val accessibilityEnabled: Set<String> = emptySet(),
    val notificationListenerEnabled: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val defaultSmsApp: Boolean = false,
    val overlayAllowed: Boolean = false,
    val batteryExempt: Boolean = false,
    val granted: Set<String> = emptySet(),
    val usageAccess: Boolean = false,
    val network: NetworkUsage? = null,
    /** Whether the launcher shows this app right now; null when unknown. */
    val launcherVisible: Boolean? = null,
)
