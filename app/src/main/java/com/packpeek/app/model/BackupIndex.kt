package com.packpeek.app.model

import java.util.Locale

/** How a backup file relates to what is installed now. */
enum class BackupStatus {
    NEWER,
    SAME,
    OLDER,
    NOT_INSTALLED,
}

/** One package archive found in the backup folder. */
data class BackupFile(
    val uri: String,
    val name: String,
    val bytes: Long,
    val modified: Long,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val status: BackupStatus,
    val label: String,
)

fun backupStatus(backupCode: Long, installedCode: Long?): BackupStatus =
    when {
        installedCode == null -> BackupStatus.NOT_INSTALLED
        backupCode > installedCode -> BackupStatus.NEWER
        backupCode == installedCode -> BackupStatus.SAME
        else -> BackupStatus.OLDER
    }

/** Full version code from the manifest attributes, including versionCodeMajor. */
fun manifestVersionCode(manifest: Map<String, String>): Long {
    val minor = manifest["versionCode"]?.toLongOrNull() ?: 0
    val major = manifest["versionCodeMajor"]?.toLongOrNull() ?: 0
    return (major shl 32) or (minor and 0xFFFFFFFFL)
}

fun isPackageArchive(name: String): Boolean =
    name.lowercase(Locale.ROOT).let { n -> listOf(".apk", ".apks", ".xapk", ".apkm").any(n::endsWith) }

/** Installed user apps that have no backup in the folder. */
fun appsWithoutBackup(apps: List<AppRecord>, backups: List<BackupFile>): List<AppRecord> {
    val covered = backups.map { it.packageName }.toSet()
    return apps.filter { !it.system && it.packageName !in covered }.sortedBy {
        it.label.lowercase(Locale.ROOT)
    }
}

/** Newest version first within a package, packages by label. */
fun sortBackups(backups: List<BackupFile>): List<BackupFile> =
    backups.sortedWith(
        compareBy<BackupFile> { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.packageName }
            .thenByDescending { it.versionCode }
            .thenByDescending { it.modified }
    )

/** Re-derives status and label after the installed apps changed, e.g. after a restore. */
fun withInstalled(files: List<BackupFile>, installed: Map<String, AppRecord>): List<BackupFile> =
    sortBackups(
        files.map { f ->
            val app = installed[f.packageName]
            f.copy(
                status = backupStatus(f.versionCode, app?.versionCode),
                label = app?.label ?: f.packageName,
            )
        }
    )
