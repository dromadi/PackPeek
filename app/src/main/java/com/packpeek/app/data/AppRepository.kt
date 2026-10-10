package com.packpeek.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.packpeek.app.model.AppRecord
import java.io.File
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
