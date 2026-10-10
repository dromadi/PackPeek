package com.packpeek.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Why the system rejected an installation, from its status code and status message. */
enum class InstallFailure {
    ABORTED,
    BLOCKED,
    CONFLICT,
    INCOMPATIBLE,
    INVALID,
    STORAGE,
    SIGNATURE,
    DOWNGRADE,
    ABI,
    SDK,
    TARGET,
    MISSING_SPLIT,
    FEATURE,
    /** The system never reported back: the prompt was lost or the session expired. */
    NO_RESULT,
    UNKNOWN,
}

/** What to conclude while waiting for the installer's report on one session. */
enum class InstallWait {
    WAIT,
    SUCCESS,
    NO_RESULT,
}

data class InstallResult(
    val packageName: String,
    val failure: InstallFailure? = null,
    val detail: String = "",
    /** The installer session this result belongs to; -1 when unknown. */
    val sessionId: Int = -1,
) {
    /** Whether this report answers [session] (for [pkg], when the system did not name a session). */
    fun answers(session: Int, pkg: String) =
        if (sessionId >= 0) sessionId == session else packageName.isBlank() || packageName == pkg

    val success
        get() = failure == null
}

object InstallErrors {
    // Values of PackageInstaller.STATUS_FAILURE_*; kept here so the rules stay JVM-testable.
    private val byStatus =
        mapOf(
            2 to InstallFailure.BLOCKED,
            3 to InstallFailure.ABORTED,
            4 to InstallFailure.INVALID,
            5 to InstallFailure.CONFLICT,
            6 to InstallFailure.STORAGE,
            7 to InstallFailure.INCOMPATIBLE,
        )

    // The status message usually starts with the precise INSTALL_FAILED_* / PARSE_FAILED_* code.
    private val byMessage =
        listOf(
            "UPDATE_INCOMPATIBLE" to InstallFailure.SIGNATURE,
            "SHARED_USER_INCOMPATIBLE" to InstallFailure.SIGNATURE,
            "INCONSISTENT_CERTIFICATES" to InstallFailure.SIGNATURE,
            "NO_CERTIFICATES" to InstallFailure.SIGNATURE,
            "VERSION_DOWNGRADE" to InstallFailure.DOWNGRADE,
            "NO_MATCHING_ABIS" to InstallFailure.ABI,
            "CPU_ABI_INCOMPATIBLE" to InstallFailure.ABI,
            "DEPRECATED_SDK_VERSION" to InstallFailure.TARGET,
            "OLDER_SDK" to InstallFailure.SDK,
            "MISSING_SPLIT" to InstallFailure.MISSING_SPLIT,
            "MISSING_FEATURE" to InstallFailure.FEATURE,
            "INSUFFICIENT_STORAGE" to InstallFailure.STORAGE,
            "DUPLICATE_PERMISSION" to InstallFailure.CONFLICT,
            "CONFLICTING_PROVIDER" to InstallFailure.CONFLICT,
            "USER_RESTRICTED" to InstallFailure.BLOCKED,
        )

    /** The user may take a while in the system prompt; after this the session is given up. */
    const val TIMEOUT_MS = 10 * 60 * 1000L
    /** A report can arrive shortly after its session disappears. */
    const val GRACE_MS = 5_000L

    /**
     * Decides, without a report from the system, whether to keep waiting. [goneForMs] is how long
     * the session has been gone (null while it exists); [updated] says the package was installed
     * or updated after the session started.
     */
    fun settle(goneForMs: Long?, updated: Boolean, waitedMs: Long): InstallWait =
        when {
            goneForMs == null -> if (waitedMs >= TIMEOUT_MS) InstallWait.NO_RESULT else InstallWait.WAIT
            goneForMs < GRACE_MS -> InstallWait.WAIT
            updated -> InstallWait.SUCCESS
            else -> InstallWait.NO_RESULT
        }

    fun classify(status: Int, message: String?): InstallFailure {
        val text = message.orEmpty().uppercase(Locale.ROOT)
        return byMessage.firstOrNull { text.contains(it.first) }?.second
            ?: byStatus[status]
            ?: InstallFailure.UNKNOWN
    }
}

enum class ReadinessIssue {
    SIGNATURE_INVALID,
    SDK,
    TARGET,
    ABI,
    FEATURES,
    SIGNER,
    DOWNGRADE,
}

data class ReadinessInput(
    val minSdk: Int,
    val targetSdk: Int,
    val deviceSdk: Int,
    val abis: List<String>,
    val deviceAbis: List<String>,
    val signatureValid: Boolean?,
    val signerMatch: Boolean?,
    val versionCode: Long,
    val installedVersionCode: Long?,
    val missingFeatures: List<String>,
)

/** Common reasons Android would refuse a local archive; the system still has the final say. */
fun readiness(input: ReadinessInput): List<ReadinessIssue> = buildList {
    if (input.signatureValid == false) add(ReadinessIssue.SIGNATURE_INVALID)
    if (input.minSdk > input.deviceSdk) add(ReadinessIssue.SDK)
    // Android 14 refuses new installs that target API 22 or lower.
    if (input.deviceSdk >= 34 && input.targetSdk < 23) add(ReadinessIssue.TARGET)
    val device = input.deviceAbis.map { it.replace('_', '-') }
    if (input.abis.isNotEmpty() && input.abis.none { it.replace('_', '-') in device })
        add(ReadinessIssue.ABI)
    if (input.missingFeatures.isNotEmpty()) add(ReadinessIssue.FEATURES)
    if (input.installedVersionCode != null) {
        if (input.signerMatch == false) add(ReadinessIssue.SIGNER)
        if (input.versionCode < input.installedVersionCode) add(ReadinessIssue.DOWNGRADE)
    }
}

/** Archive layout of an imported file, for display. */
enum class ArchiveKind {
    APK,
    APKS,
    XAPK,
    APKM,
}

fun archiveKind(single: Boolean, entries: Collection<String>): ArchiveKind =
    when {
        single -> ArchiveKind.APK
        "manifest.json" in entries -> ArchiveKind.XAPK
        "info.json" in entries -> ArchiveKind.APKM
        else -> ArchiveKind.APKS
    }

fun appListCsv(apps: List<AppRecord>): String {
    val iso =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    fun cell(value: Any): String {
        var text = value.toString()
        // Keep spreadsheet apps from evaluating app labels as formulas.
        if (text.isNotEmpty() && text[0] in "=+-@\t\r") text = "'$text"
        return if (text.any { it in ",\"\n\r" }) "\"${text.replace("\"", "\"\"")}\"" else text
    }
    val header =
        listOf(
            "label",
            "package",
            "version",
            "version_code",
            "min_sdk",
            "target_sdk",
            "system",
            "split",
            "size_bytes",
            "source",
            "installer",
            "updated_utc",
        )
    return (listOf(header.joinToString(",")) +
            apps.map { a ->
                listOf(
                        a.label,
                        a.packageName,
                        a.version,
                        a.versionCode,
                        a.minSdk,
                        a.targetSdk,
                        a.system,
                        a.split,
                        a.size,
                        a.source,
                        a.installer,
                        iso.format(Date(a.updated)),
                    )
                    .joinToString(",", transform = ::cell)
            })
        .joinToString("\r\n", postfix = "\r\n")
}
