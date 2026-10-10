package com.packpeek.app.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import com.android.apksig.ApkVerifier
import com.packpeek.app.data.AppRepository
import com.packpeek.app.engine.risk.BinaryXml
import com.packpeek.app.engine.risk.XmlNode
import com.packpeek.app.model.*
import java.io.*
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*

data class ImportedArchive(val directory: File, val app: AppRecord, val files: List<File>) {
    fun close() {
        directory.deleteRecursively()
    }
}

class ArchiveImporter(private val context: Context, private val apps: AppRepository) {
    suspend fun open(uri: Uri): ImportedArchive =
        withContext(Dispatchers.IO) {
            val directory =
                File(context.cacheDir, "imports/${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val outer = File(directory, "source.apk")
                context.contentResolver.openInputStream(uri)?.use {
                    copyBounded(it, outer, 4L * 1024 * 1024 * 1024)
                } ?: throw AppFailure(ErrorCode.SOURCE)
                val files =
                    if (apps.archiveInfo(outer.path) != null) listOf(outer)
                    else {
                        var total = 0L
                        val result = mutableListOf<File>()
                        ZipFile(outer).use { zip ->
                            val entries = zip.entries()
                            var count = 0
                            while (entries.hasMoreElements()) {
                                ensureActive()
                                if (++count > 10000) throw AppFailure(ErrorCode.ARCHIVE)
                                val entry = entries.nextElement()
                                if (!FileNames.safeZipEntry(entry.name))
                                    throw AppFailure(ErrorCode.ARCHIVE)
                                if (!entry.isDirectory && entry.name.endsWith(".apk", true)) {
                                    if (result.size >= 512) throw AppFailure(ErrorCode.ARCHIVE)
                                    val file = File(directory, "part_${result.size}.apk")
                                    zip.getInputStream(entry).use {
                                        total +=
                                            copyBounded(it, file, 4L * 1024 * 1024 * 1024 - total)
                                    }
                                    result += file
                                }
                            }
                        }
                        outer.delete()
                        result
                    }
                if (files.isEmpty()) throw AppFailure(ErrorCode.ARCHIVE)
                val metadata = files.associateWith { ManifestRoot.read(it) }
                val bases = files.filter { metadata.getValue(it)["split"].isNullOrBlank() }
                if (bases.size != 1) throw AppFailure(ErrorCode.ARCHIVE)
                val base = bases.single()
                val identity = metadata.getValue(base)
                if (identity["package"].isNullOrBlank()) throw AppFailure(ErrorCode.ARCHIVE)
                if (
                    metadata.values.any {
                        it["package"] != identity["package"] ||
                            it["versionCode"] != identity["versionCode"] ||
                            it["versionCodeMajor"] != identity["versionCodeMajor"]
                    }
                )
                    throw AppFailure(ErrorCode.ARCHIVE)
                val names = metadata.values.map { it["split"].orEmpty() }
                if (names.toSet().size != names.size) throw AppFailure(ErrorCode.ARCHIVE)
                val p = apps.archiveInfo(base.path) ?: throw AppFailure(ErrorCode.ARCHIVE)
                p.applicationInfo?.apply {
                    sourceDir = base.path
                    publicSourceDir = base.path
                }
                val ordered = listOf(base) + files.filter { it != base }
                ImportedArchive(
                    directory,
                    apps.fromInfo(p, true, ordered.drop(1).map { it.path }),
                    ordered,
                )
            } catch (e: CancellationException) {
                directory.deleteRecursively()
                throw e
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw if (e is AppFailure) e else AppFailure(ErrorCode.ARCHIVE, e)
            }
        }

    private suspend fun copyBounded(input: InputStream, file: File, limit: Long): Long {
        var size = 0L
        file.outputStream().use { out ->
            val b = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(b)
                if (n < 0) break
                size += n
                if (size > limit) throw AppFailure(ErrorCode.ARCHIVE)
                out.write(b, 0, n)
            }
        }
        return size
    }

    suspend fun validateForInstall(archive: ImportedArchive): List<File> =
        withContext(Dispatchers.IO) {
            var expected: Set<String>? = null
            for (file in archive.files) {
                ensureActive()
                val result =
                    ApkVerifier.Builder(file)
                        .setMinCheckedPlatformVersion(maxOf(24, archive.app.minSdk))
                        .build()
                        .verify()
                if (!result.isVerified) throw AppFailure(ErrorCode.SIGNATURE)
                val signers = result.signerCertificates.map { digest(it.encoded) }.toSet()
                if (signers.isEmpty() || (expected != null && expected != signers))
                    throw AppFailure(ErrorCode.SIGNATURE)
                expected = signers
            }
            fun abi(file: File): String? =
                AbiSelector.fromSplitName(ManifestRoot.read(file)["split"].orEmpty())
            val declared = archive.files.mapNotNull(::abi).toSet()
            if (declared.isEmpty()) return@withContext archive.files
            val best = AbiSelector.choose(declared, Build.SUPPORTED_ABIS.toList())
            archive.files.filter { abi(it) == null || abi(it) == best }
        }
}

/** Manifest root attributes and launcher entries, read with [BinaryXml]. Never executes code. */
object ManifestRoot {
    private val launcherCategories =
        setOf("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER")

    class Manifest(val root: Map<String, String>, val launchers: Set<String>)

    fun read(apk: File): Map<String, String> = parseFile(apk).root

    /** Fully qualified names of activities and aliases with a launcher category. */
    fun launcherActivities(apk: File): Set<String> = parseFile(apk).launchers

    fun decode(bytes: ByteArray): Map<String, String> = parse(bytes).root

    fun readTree(apk: File): XmlNode =
        failClosed {
            ZipFile(apk).use { zip ->
                BinaryXml.parse(
                    BinaryXml.readEntry(zip, "AndroidManifest.xml")
                        ?: throw AppFailure(ErrorCode.ARCHIVE)
                )
            }
        }

    private fun parseFile(apk: File): Manifest = summarize(readTree(apk))

    fun parse(bytes: ByteArray): Manifest = summarize(BinaryXml.parse(bytes))

    private inline fun <T> failClosed(block: () -> T): T =
        try {
            block()
        } catch (e: AppFailure) {
            throw e
        } catch (e: Exception) {
            throw AppFailure(ErrorCode.ARCHIVE, e)
        }

    private fun summarize(tree: XmlNode): Manifest {
        if (tree.name != "manifest") throw AppFailure(ErrorCode.ARCHIVE)
        // Legacy rendering: unsigned decimal for non-string values, as split checks compare it.
        val root = tree.attrs.mapValues { (_, v) -> v.text ?: v.data.toUInt().toString() }
        val pkg = root["package"].orEmpty()
        val launchers =
            tree.child("application")
                .flatMap { it.children }
                .filter { it.name == "activity" || it.name == "activity-alias" }
                .filter { activity ->
                    activity.child("intent-filter").any { filter ->
                        filter.child("category").any {
                            it["name"]?.asString() in launcherCategories
                        }
                    }
                }
                .mapNotNull { it["name"]?.asString() }
                .map { qualify(pkg, it) }
                .toSet()
        return Manifest(root, launchers)
    }

    fun qualify(pkg: String, name: String): String =
        when {
            name.startsWith('.') -> pkg + name
            '.' !in name -> "$pkg.$name"
            else -> name
        }
}
