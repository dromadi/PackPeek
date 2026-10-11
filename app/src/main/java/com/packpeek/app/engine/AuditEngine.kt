package com.packpeek.app.engine

import android.content.pm.*
import android.os.Build
import com.android.apksig.ApkVerifier
import com.packpeek.app.data.AppRepository
import com.packpeek.app.model.*
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.zip.ZipFile
import kotlinx.coroutines.*

data class Technology(
    val frameworks: List<String>,
    val abis: List<String>,
    val libraries: List<String>,
)

fun digest(bytes: ByteArray, algorithm: String = "SHA-256") =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") {
        "%02X".format(it.toInt() and 255)
    }

class AuditEngine(private val apps: AppRepository) {
    private val cache =
        object : LinkedHashMap<String, Technology>(128, .75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Technology>?) =
                size > 128
        }

    suspend fun technology(app: AppRecord): Technology =
        withContext(Dispatchers.IO) {
            val key = "${app.packageName}:${app.versionCode}:${app.updated}:${app.basePath}"
            synchronized(cache) { cache[key] }
                ?.let {
                    return@withContext it
                }
            val libs = mutableSetOf<String>()
            val abis = mutableSetOf<String>()
            val frameworks = mutableSetOf<String>()
            for (path in app.paths) {
                ensureActive()
                ZipFile(path).use { zip ->
                    val entries = zip.entries()
                    var count = 0
                    while (entries.hasMoreElements()) {
                        ensureActive()
                        if (++count > 100000) throw AppFailure(ErrorCode.ARCHIVE)
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            abis += name.split('/').getOrElse(1) { "?" }
                            libs += name.substringAfterLast('/')
                        }
                        if (
                            name.startsWith("assets/flutter_assets/") ||
                                name.endsWith("libflutter.so")
                        )
                            frameworks += "Flutter"
                        if (
                            name.endsWith("index.android.bundle") ||
                                name.endsWith("libreactnative.so") ||
                                name.endsWith("libreactnativejni.so")
                        )
                            frameworks += "React Native"
                        if (name.endsWith("libunity.so")) frameworks += "Unity"
                        if (name.endsWith("libUE4.so") || name.endsWith("libUnreal.so"))
                            frameworks += "Unreal"
                        if (name.startsWith("assets/www/cordova")) frameworks += "Cordova"
                        if (name.startsWith("assemblies/") || name.endsWith("libmonodroid.so"))
                            frameworks += "Xamarin"
                    }
                }
            }
            Technology(
                    frameworks.ifEmpty { setOf("Android") }.sorted(),
                    abis.sorted(),
                    libs.sorted(),
                )
                .also { synchronized(cache) { cache[key] = it } }
        }

    @Suppress("DEPRECATION")
    suspend fun audit(app: AppRecord, verifySignature: Boolean = true): AuditReport =
        withContext(Dispatchers.IO) {
            val signFlags =
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            val flags =
                PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_CONFIGURATIONS or
                    signFlags
            val p =
                if (app.fromFile)
                    apps.archiveInfo(app.basePath, flags) ?: throw AppFailure(ErrorCode.ARCHIVE)
                else apps.packageInfo(app.packageName, flags)
            val info = requireNotNull(p.applicationInfo)
            val permissions =
                p.requestedPermissions.orEmpty().mapIndexed { index, name ->
                    val pi = runCatching { apps.pm.getPermissionInfo(name, 0) }.getOrNull()
                    val kind =
                        when ((pi?.protectionLevel ?: 0) and PermissionInfo.PROTECTION_MASK_BASE) {
                            PermissionInfo.PROTECTION_DANGEROUS -> "DANGEROUS"
                            PermissionInfo.PROTECTION_SIGNATURE -> "SIGNATURE"
                            else -> "NORMAL"
                        }
                    PermissionRow(
                        name,
                        kind,
                        if (app.fromFile) null
                        else
                            (p.requestedPermissionsFlags?.getOrNull(index) ?: 0) and
                                PackageInfo.REQUESTED_PERMISSION_GRANTED != 0,
                        runCatching { pi?.loadDescription(apps.pm)?.toString() }
                            .getOrNull()
                            .orEmpty(),
                    )
                }
            val components = buildList {
                p.activities.orEmpty().forEach {
                    add(
                        ComponentRow(
                            it.name,
                            "ACTIVITY",
                            it.exported,
                            it.permission.orEmpty(),
                            it.enabled,
                        )
                    )
                }
                p.services.orEmpty().forEach {
                    add(
                        ComponentRow(
                            it.name,
                            "SERVICE",
                            it.exported,
                            it.permission.orEmpty(),
                            it.enabled,
                        )
                    )
                }
                p.receivers.orEmpty().forEach {
                    add(
                        ComponentRow(
                            it.name,
                            "RECEIVER",
                            it.exported,
                            it.permission.orEmpty(),
                            it.enabled,
                        )
                    )
                }
                p.providers.orEmpty().forEach {
                    add(
                        ComponentRow(
                            it.name,
                            "PROVIDER",
                            it.exported,
                            listOfNotNull(it.readPermission, it.writePermission).joinToString(", "),
                            it.enabled,
                        )
                    )
                }
            }
            val sigs =
                if (Build.VERSION.SDK_INT >= 28)
                    p.signingInfo
                        ?.let {
                            if (it.hasMultipleSigners()) it.apkContentsSigners
                            else it.signingCertificateHistory
                        }
                        .orEmpty()
                else p.signatures.orEmpty()
            val certificates =
                sigs
                    .mapNotNull { s ->
                        runCatching {
                            val c =
                                CertificateFactory.getInstance("X.509")
                                    .generateCertificate(s.toByteArray().inputStream())
                                    as X509Certificate
                            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
                            CertificateRow(
                                c.subjectX500Principal.name,
                                c.issuerX500Principal.name,
                                df.format(c.notBefore),
                                df.format(c.notAfter),
                                c.sigAlgName,
                                digest(c.encoded, "SHA-1"),
                                digest(c.encoded),
                                digest(c.encoded, "MD5"),
                                c.serialNumber.toString(16),
                                runCatching { c.checkValidity() }.isFailure,
                                c.notBefore.time,
                            )
                        }
                            .getOrNull()
                    }
                    .distinctBy { it.sha256 }
            ensureActive()
            val verified =
                if (verifySignature)
                    runCatching {
                            ApkVerifier.Builder(File(app.basePath))
                                .setMinCheckedPlatformVersion(maxOf(24, app.minSdk))
                                .build()
                                .verify()
                        }
                        .getOrNull()
                else null
            val schemes = buildList {
                if (verified?.isVerifiedUsingV1Scheme == true) add("v1")
                if (verified?.isVerifiedUsingV2Scheme == true) add("v2")
                if (verified?.isVerifiedUsingV3Scheme == true) add("v3")
                if (verified?.isVerifiedUsingV31Scheme == true) add("v3.1")
            }
            val findings = buildList {
                if (info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    add(Finding(FindingCode.DEBUGGABLE))
                if (info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
                    add(Finding(FindingCode.BACKUP))
                if (info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
                    add(Finding(FindingCode.CLEARTEXT))
                if (isOldTarget(app.targetSdk)) add(Finding(FindingCode.OLD_TARGET))
                // Launcher entry points must be exported; counting them flags nearly every app.
                val launchers =
                    if (app.fromFile)
                        runCatching { ManifestRoot.launcherActivities(File(app.basePath)) }
                            .getOrDefault(emptySet())
                    else apps.launcherActivities(app.packageName)
                components
                    .count {
                        it.exported &&
                            it.permission.isBlank() &&
                            it.enabled &&
                            !(it.kind == "ACTIVITY" && it.name in launchers)
                    }
                    .takeIf { it > 0 }
                    ?.let { add(Finding(FindingCode.EXPORTED, it)) }
                permissions
                    .count { it.category == "DANGEROUS" }
                    .takeIf { it > 0 }
                    ?.let { add(Finding(FindingCode.DANGEROUS, it)) }
                if (!app.fromFile && app.source == "SIDE") add(Finding(FindingCode.SIDELOAD))
                if (certificates.any { it.subject.contains("CN=Android Debug", true) })
                    add(Finding(FindingCode.DEBUG_CERT))
            }
            val tech = technology(app)
            val installed =
                if (app.fromFile)
                    runCatching { apps.packageInfo(app.packageName, signFlags) }.getOrNull()
                else null
            val match = installed?.let { currentSigners(it) == currentSigners(p) }
            val missingFeatures =
                if (app.fromFile)
                    p.reqFeatures
                        .orEmpty()
                        .filter {
                            it.name != null &&
                                it.flags and FeatureInfo.FLAG_REQUIRED != 0 &&
                                !apps.pm.hasSystemFeature(it.name)
                        }
                        .map { it.name }
                        .distinct()
                else emptyList()
            AuditReport(
                permissions,
                components,
                certificates,
                findings,
                schemes,
                verified?.isVerified,
                tech.frameworks,
                tech.abis,
                tech.libraries,
                match,
                installed?.versionName,
                installed?.let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
                },
                missingFeatures,
            )
        }

    @Suppress("DEPRECATION")
    private fun currentSigners(p: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners.orEmpty()
            else p.signatures.orEmpty())
            .map { digest(it.toByteArray()) }
            .toSet()
}
