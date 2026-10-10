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
    /** Public IP literal in the sending code's class, when the data goes to a bare address. */
    val ipHost: String? = null,
    /**
     * True when the value read from the source was followed through registers, fields and app
     * calls into the sending call's arguments. False: reading and sending only share a code path.
     */
    val traced: Boolean = false,
)

/** A host found in a URL string in the app's own code. */
data class Destination(
    val host: String,
    val cleartext: Boolean,
    val ipAddress: Boolean,
    /** A public IP: not private, loopback, link-local or a well-known resolver. */
    val publicIp: Boolean = false,
)

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
    /** Present only after a deep analysis. */
    val deep: DeepFacts? = null,
) {
    fun appHits(capability: Capability) = hits[capability].orEmpty().filter { it.library == null }

    fun inApp(capability: Capability) = appHits(capability).isNotEmpty()

    fun flowsFrom(vararg sources: Capability) = flows.filter { it.source in sources }
}

/** How one step of a chain reaches the next method. */
enum class StepVia {
    /** The first method of the chain. */
    START,
    CALL,
    /** An Intent or WorkRequest naming another component (Receiver → Service). */
    INTENT,
    /** A new object whose methods run later (Runnable, Thread, lambda). */
    OBJECT,
}

data class ChainStep(val method: String, val via: StepVia)

/** A deep read-then-send path: from [steps].first() it reaches the reading and the sending code. */
data class CodeChain(
    val source: Capability,
    val sink: Capability,
    val sourceApi: String,
    val sinkApi: String,
    /** Methods from the start to where data is read. */
    val toSource: List<ChainStep>,
    /** Methods from the start to where data is sent. */
    val toSink: List<ChainStep>,
    /** Public IP literal in the class that sends, for network chains. */
    val ipHost: String? = null,
)

enum class Decoding { BASE64, JOINED, CHAR_ARRAY, XOR, REVERSED }

/** A text the code hides, as decoded statically. Bot tokens are masked. */
data class DecodedText(
    val technique: Decoding,
    val decoded: String,
    val method: String,
    val capability: Capability?,
    /** XOR key or other detail; empty if none. */
    val detail: String = "",
)

enum class PayloadKind { DEX, ARCHIVE, ENCRYPTED }

/** A code file hidden inside the package and what its code can do. */
data class PayloadScan(
    val path: String,
    val kind: PayloadKind,
    val classes: Int,
    val capabilities: Set<Capability>,
    /** Bits per byte, 0–8; encrypted or compressed data is close to 8. */
    val entropy: Double = 0.0,
)

/** Results only the deep analysis produces. */
data class DeepFacts(
    val chains: List<CodeChain>,
    val decoded: List<DecodedText>,
    val payloads: List<PayloadScan>,
    /** Short code listings of the methods in the evidence, by readable method name. */
    val listings: Map<String, List<String>>,
    val sha256: String,
    val bytes: Long,
    val millis: Long,
)
