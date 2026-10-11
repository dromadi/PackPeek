package com.packpeek.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.packpeek.app.model.AppRecord
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import kotlinx.coroutines.*

class AppRepository(context: Context) {
    val pm: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun packageInfo(pkg: String, flags: Int = 0): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(pkg, flags)

    @Suppress("DEPRECATION")
    fun archiveInfo(path: String, flags: Int = 0): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(path, flags)

    @Suppress("DEPRECATION")
    fun launcherActivities(pkg: String): Set<String> =
        listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { category ->
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg)
                runCatching {
                        if (Build.VERSION.SDK_INT >= 33)
                            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                        else pm.queryIntentActivities(intent, 0)
                    }
                    .getOrDefault(emptyList())
            }
            .map { it.activityInfo.name }
            .toSet()

    /** Sorted SHA-256 digests of the installed app's current signer(s), or "" if unknown. */
    @Suppress("DEPRECATION")
    fun signerDigest(pkg: String): String =
        runCatching {
                val p =
                    packageInfo(
                        pkg,
                        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                        else PackageManager.GET_SIGNATURES,
                    )
                val signatures =
                    if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners.orEmpty()
                    else p.signatures.orEmpty()
                signatures
                    .map {
                        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(
                            ""
                        ) { b ->
                            "%02x".format(b)
                        }
                    }
                    .sorted()
                    .joinToString(",")
            }
            .getOrDefault("")

    /** First signer of an installed app: SHA-256 digest and certificate subject name. */
    @Suppress("DEPRECATION")
    fun signer(pkg: String): Signer? =
        runCatching {
                val p =
                    packageInfo(
                        pkg,
                        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                        else PackageManager.GET_SIGNATURES,
                    )
                val signature =
                    (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners
                        else p.signatures)
                        ?.firstOrNull() ?: return null
                val bytes = signature.toByteArray()
                val cert =
                    CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
                        as X509Certificate
                val digest =
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                Signer(digest, subjectName(cert.subjectX500Principal.name), cert.notBefore.time)
            }
            .getOrNull()

    /** CPU architectures with native code in any of the app's APKs; empty for pure Java/Kotlin. */
    fun abis(app: AppRecord): Set<String> =
        app.paths
            .flatMap { path ->
                runCatching {
                        ZipFile(path).use { zip ->
                            zip.entries()
                                .asSequence()
                                .map { it.name }
                                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                                .map { it.split('/')[1] }
                                .toSet()
                        }
                    }
                    .getOrDefault(emptySet())
            }
            .toSet()

    /** Declared permissions and components, read cheaply from the package manager. */
    @Suppress("DEPRECATION")
    fun counts(pkg: String): ComponentCounts? =
        runCatching {
                val p =
                    packageInfo(
                        pkg,
                        PackageManager.GET_PERMISSIONS or
                            PackageManager.GET_ACTIVITIES or
                            PackageManager.GET_SERVICES or
                            PackageManager.GET_RECEIVERS or
                            PackageManager.GET_PROVIDERS or
                            PackageManager.MATCH_DISABLED_COMPONENTS,
                    )
                ComponentCounts(
                    p.requestedPermissions?.size ?: 0,
                    p.activities?.size ?: 0,
                    p.services?.size ?: 0,
                    p.receivers?.size ?: 0,
                    p.providers?.size ?: 0,
                )
            }
            .getOrNull()

    /** "Google Play Store" for com.android.vending; the package name when it has no label. */
    fun label(pkg: String): String =
        runCatching { packageInfo(pkg).applicationInfo?.loadLabel(pm)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: pkg

    fun installed(pkg: String): AppRecord = fromInfo(packageInfo(pkg))

    @Suppress("DEPRECATION")
    fun fromInfo(
        p: PackageInfo,
        fromFile: Boolean = false,
        splits: List<String>? = null,
    ): AppRecord {
        val a = requireNotNull(p.applicationInfo)
        val paths = splits ?: a.splitSourceDirs.orEmpty().toList()
        val installer =
            if (fromFile) ""
            else
                runCatching {
                        if (Build.VERSION.SDK_INT >= 30)
                            pm.getInstallSourceInfo(p.packageName).installingPackageName
                        else pm.getInstallerPackageName(p.packageName)
                    }
                    .getOrNull()
                    .orEmpty()
        return AppRecord(
            p.packageName,
            runCatching { a.loadLabel(pm).toString() }.getOrDefault(p.packageName),
            p.versionName.orEmpty(),
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong(),
            a.minSdkVersion,
            a.targetSdkVersion,
            a.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            a.sourceDir,
            paths,
            (listOf(a.sourceDir) + paths).sumOf { File(it).length() },
            p.lastUpdateTime,
            p.firstInstallTime,
            installer,
            a.uid,
            fromFile,
        )
    }

    @Suppress("DEPRECATION")
    suspend fun scan(): List<AppRecord> =
        withContext(Dispatchers.IO) {
            val list =
                if (Build.VERSION.SDK_INT >= 33)
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                else pm.getInstalledApplications(0)
            list.mapNotNull {
                ensureActive()
                runCatching { installed(it.packageName) }.getOrNull()
            }
        }
}

data class Signer(val digest: String, val name: String, val notBefore: Long = 0)

data class ComponentCounts(
    val permissions: Int,
    val activities: Int,
    val services: Int,
    val receivers: Int,
    val providers: Int,
)

/** "CN=Android Debug, O=Android, C=US" → "Android Debug"; falls back to O, then the raw name. */
fun subjectName(dn: String): String {
    val parts =
        Regex("""(?:^|,)\s*([A-Za-z.0-9]+)=((?:\\.|"[^"]*"|[^,])*)""")
            .findAll(dn)
            .associate { it.groupValues[1].uppercase() to it.groupValues[2].trim().trim('"') }
    return parts["CN"]?.takeIf { it.isNotBlank() } ?: parts["O"]?.takeIf { it.isNotBlank() } ?: dn
}
