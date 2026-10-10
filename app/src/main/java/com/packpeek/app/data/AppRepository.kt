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
