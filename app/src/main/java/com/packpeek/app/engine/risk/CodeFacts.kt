package com.packpeek.app.engine.risk

/**
 * What code can do. Sources read personal data, sinks send data off the device; the rest are
 * stand-alone signals. Ordinals are used as bit positions, so keep this under 32 entries.
 */
enum class Capability(val source: Boolean = false, val sink: Boolean = false) {
    SMS_READ(source = true),
    CONTACTS(source = true),
    CALL_LOG(source = true),
    CLIPBOARD(source = true),
    NOTIFICATION_READ(source = true),
    SCREEN_READ(source = true),
    COOKIES(source = true),
    PHONE_ID(source = true),
    ACCOUNTS(source = true),
    LOCATION(source = true),
    NETWORK(sink = true),
    SMS_SEND(sink = true),
    TELEGRAM(sink = true),
    DISCORD(sink = true),
    GESTURES,
    CAMERA_MIC,
    SCREEN_CAPTURE,
    JS_BRIDGE,
    HIDE_ICON,
    DYNAMIC_CODE,
    SHELL,
    INSTALL_PACKAGE,
    DEVICE_ADMIN_ACTION,
    CALL_FORWARDING,
    /** ContentResolver.query: turns content:// strings into real reads. Not a finding itself. */
    CONTENT_QUERY;

    val bit
        get() = 1 shl ordinal

    companion object {
        /** Sinks that send to a hidden, attacker-run place rather than the app's own server. */
        val hiddenDestinations = setOf(TELEGRAM, DISCORD)
    }
}

/**
 * One place where code uses a capability: [api] is the call or string ("SmsMessage.createFromPdu"),
 * [method] where it happens ("com.x.SmsReceiver.onReceive"), [library] the known SDK it belongs to
 * or null for the app's own code.
 */
data class CodeHit(
    val capability: Capability,
    val api: String,
    val method: String,
    val library: String?,
    /** Found as a text constant rather than a call. */
    val string: Boolean = false,
)

/**
 * A method that reads [source] data and sends with [sink], itself or through the methods it calls
 * directly. An indication only: the analysis does not follow the data value.
 */
data class CodeFlow(
    val source: Capability,
    val sink: Capability,
    val method: String,
    val sourceApi: String,
    val sinkApi: String,
)

/** A host found in a URL string in the app's own code. */
data class Destination(val host: String, val cleartext: Boolean, val ipAddress: Boolean)

enum class GapKind {
    /** DEX, JAR or APK files hidden in assets or resources; loaded at runtime, if at all. */
    HIDDEN_CODE,
    /** A commercial packer that encrypts the real code. */
    PACKER,
    /** Flutter, React Native or Unity: logic lives outside DEX. */
    CROSS_PLATFORM,
    /** A DEX file that could not be read. */
    UNREADABLE,
    /** Analysis stopped at the time or size limit. */
    LIMIT,
}

data class CodeGap(val kind: GapKind, val detail: String)

data class CodeFacts(
    val dexFiles: Int,
    val classes: Int,
    val appClasses: Int,
    /** Up to a few places per capability, the app's own code first. */
    val hits: Map<Capability, List<CodeHit>>,
    val flows: List<CodeFlow>,
    val destinations: List<Destination>,
    /** Advertising and analytics SDKs found by package name. */
    val sdks: List<String>,
    val gaps: List<CodeGap>,
) {
    fun appHits(capability: Capability) = hits[capability].orEmpty().filter { it.library == null }

    fun inApp(capability: Capability) = appHits(capability).isNotEmpty()

    fun flowsFrom(vararg sources: Capability) = flows.filter { it.source in sources }
}
