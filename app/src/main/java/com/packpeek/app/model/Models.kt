package com.packpeek.app.model

import java.text.Normalizer
import java.util.Locale

data class AppRecord(
    val packageName: String,
    val label: String,
    val version: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val system: Boolean,
    val basePath: String,
    val splitPaths: List<String>,
    val size: Long,
    val updated: Long,
    val installed: Long,
    val installer: String,
    val uid: Int,
    val fromFile: Boolean = false,
) {
    val split
        get() = splitPaths.isNotEmpty()

    val paths
        get() = listOf(basePath) + splitPaths

    val format
        get() = if (split) "apks" else "apk"

    val source
        get() =
            when {
                system -> "SYSTEM"
                installer == "com.android.vending" -> "PLAY"
                installer.isBlank() -> "SIDE"
                else -> "OTHER"
            }
}

/** Container for apps installed as base + split APKs. Standalone apps are always .apk. */
enum class SplitFormat(val extension: String) {
    /** ZIP of base.apk + splits, read by SAI and PackPeek. */
    APKS("apks"),
    /** ZIP with manifest.json, read by APKPure and other XAPK installers. */
    XAPK("xapk"),
    /** Same layout as APKS under a generic extension. */
    ZIP("zip"),
    /** Experimental: splits merged into one APK, re-signed with PackPeek's key. */
    APK("apk");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.name == value } ?: APKS
    }
}

data class NamingConfig(
    val name: Boolean = true,
    val pkg: Boolean = false,
    val version: Boolean = true,
    val code: Boolean = false,
    val split: SplitFormat = SplitFormat.APKS,
)

object FileNames {
    fun clean(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("[\\x00-\\x1F\\x7F/\\\\:*?\"<>|]"), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "app" }

    fun format(app: AppRecord, config: NamingConfig): String {
        val parts = buildList {
            if (config.name) add(app.label)
            if (config.pkg) add(app.packageName)
            if (config.version) add("v${app.version}")
            if (config.code) add("c${app.versionCode}")
        }
        return clean(parts.joinToString("_").ifBlank { app.packageName }) +
            ".${extension(app, config)}"
    }

    fun extension(app: AppRecord, config: NamingConfig): String =
        if (app.split) config.split.extension else "apk"

    fun numbered(name: String, n: Int): String =
        if (n == 0) name
        else "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}"

    fun safeZipEntry(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith('/') &&
            !name.startsWith('\\') &&
            !Regex("^[A-Za-z]:").containsMatchIn(name) &&
            name.replace('\\', '/').split('/').none { it == ".." }
}

data class AppQuery(
    val text: String = "",
    val kind: String = "ALL",
    val source: String = "ALL",
    val maxSdk: Int = 0,
    val minMb: Int = 0,
    val framework: String = "ALL",
    val sort: String = "NAME_ASC",
)

fun filterApps(
    apps: List<AppRecord>,
    query: AppQuery,
    technologies: Map<String, List<String>> = emptyMap(),
): List<AppRecord> {
    val result = apps.filter { a ->
        (query.text.isBlank() ||
            a.label.contains(query.text, true) ||
            a.packageName.contains(query.text, true)) &&
            (when (query.kind) {
                "USER" -> !a.system
                "SYSTEM" -> a.system
                "SPLIT" -> a.split
                else -> true
            }) &&
            (query.source == "ALL" || a.source == query.source) &&
            (query.maxSdk == 0 || a.targetSdk <= query.maxSdk) &&
            a.size >= query.minMb.toLong() * 1024 * 1024 &&
            (query.framework == "ALL" ||
                query.framework in technologies[a.packageName].orEmpty())
    }
    return when (query.sort) {
        "NAME_DESC" -> result.sortedByDescending { it.label.lowercase(Locale.ROOT) }
        "SIZE_DESC" -> result.sortedByDescending { it.size }
        "SIZE_ASC" -> result.sortedBy { it.size }
        "UPDATED" -> result.sortedByDescending { it.updated }
        "SDK" -> result.sortedBy { it.targetSdk }
        else -> result.sortedBy { it.label.lowercase(Locale.ROOT) }
    }
}

enum class FindingCode(val weight: Int) {
    DEBUGGABLE(25),
    BACKUP(5),
    CLEARTEXT(10),
    OLD_TARGET(15),
    EXPORTED(20),
    DANGEROUS(10),
    SIDELOAD(5),
    DEBUG_CERT(20),
}

/**
 * Fixed threshold so the same app scores the same on every device. API 31 (Android 12) brought
 * explicit exported flags, exact-alarm and pending-intent mutability rules.
 */
const val OLD_TARGET_SDK = 31

fun isOldTarget(targetSdk: Int) = targetSdk < OLD_TARGET_SDK

data class Finding(val code: FindingCode, val count: Int = 1)

fun riskScore(findings: List<Finding>): Int =
    findings.distinctBy { it.code }.sumOf { it.code.weight }.coerceAtMost(100)

data class PermissionRow(
    val name: String,
    val category: String,
    val granted: Boolean?,
    val description: String,
)

data class ComponentRow(
    val name: String,
    val kind: String,
    val exported: Boolean,
    val permission: String,
    val enabled: Boolean,
)

data class CertificateRow(
    val subject: String,
    val issuer: String,
    val validFrom: String,
    val validTo: String,
    val algorithm: String,
    val sha1: String,
    val sha256: String,
    val md5: String,
    val serial: String,
    val expired: Boolean,
)

data class AuditReport(
    val permissions: List<PermissionRow>,
    val components: List<ComponentRow>,
    val certificates: List<CertificateRow>,
    val findings: List<Finding>,
    val schemes: List<String>,
    val signatureValid: Boolean?,
    val frameworks: List<String>,
    val abis: List<String>,
    val libraries: List<String>,
    val signerMatch: Boolean? = null,
) {
    val score
        get() = riskScore(findings)
}

enum class ErrorCode {
    FOLDER,
    STORAGE,
    SOURCE,
    ARCHIVE,
    SIGNATURE,
    ABI,
    MERGE,
    UNKNOWN,
}

class AppFailure(val code: ErrorCode, cause: Throwable? = null) : Exception(code.name, cause)

object AbiSelector {
    private val known = listOf("arm64_v8a", "armeabi_v7a", "x86_64", "x86")

    fun fromSplitName(name: String): String? = known.firstOrNull { name.endsWith(".$it") }

    fun choose(declared: Set<String>, supported: List<String>): String? {
        if (declared.isEmpty()) return null
        return supported.map { it.replace('-', '_') }.firstOrNull { it in declared }
            ?: throw AppFailure(ErrorCode.ABI)
    }
}
