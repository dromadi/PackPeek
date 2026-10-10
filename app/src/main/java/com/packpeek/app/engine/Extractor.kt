package com.packpeek.app.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.packpeek.app.model.*
import java.io.*
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class Extracted(val uri: String, val name: String, val bytes: Long)

/** Split name as Android declares it (e.g. config.arm64_v8a), falling back to the file name. */
private fun splitId(path: String, index: Int): String =
    runCatching { ManifestRoot.read(File(path))["split"] }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: File(path).name.removeSuffix(".apk").removePrefix("split_").ifBlank { "split_$index" }

/** manifest.json in the layout APKPure-compatible installers read (xapk_version 2). */
private fun xapkManifest(
    app: AppRecord,
    ids: List<String>,
    files: List<String>,
    total: Long,
): String =
    JSONObject()
        .put("xapk_version", 2)
        .put("package_name", app.packageName)
        .put("name", app.label)
        .put("version_code", app.versionCode.toString())
        .put("version_name", app.version)
        .put("min_sdk_version", app.minSdk.toString())
        .put("target_sdk_version", app.targetSdk.toString())
        .put("total_size", total)
        .put("split_configs", JSONArray(ids.drop(1)))
        .put(
            "split_apks",
            JSONArray(
                ids.indices.map { JSONObject().put("file", files[it]).put("id", ids[it]) }
            ),
        )
        .toString(2)

class Extractor(private val context: Context) {
    suspend fun extract(
        app: AppRecord,
        folderUri: Uri,
        naming: NamingConfig,
        created: suspend (String, String) -> Unit = { _, _ -> },
        progress: suspend (Long, Long) -> Unit,
    ): Extracted =
        withContext(Dispatchers.IO) {
            val folder =
                DocumentFile.fromTreeUri(context, folderUri)?.takeIf {
                    it.exists() && it.canWrite()
                } ?: throw AppFailure(ErrorCode.FOLDER)
            if (app.paths.any { !File(it).canRead() }) throw AppFailure(ErrorCode.SOURCE)
            val total = app.paths.sumOf { File(it).length() }.coerceAtLeast(1)
            val base = FileNames.format(app, naming)
            // One listing instead of a provider query per findFile() call.
            val existing = folder.listFiles().mapNotNullTo(HashSet()) { it.name }
            var n = 0
            var name = base
            while (name in existing) {
                name = FileNames.numbered(base, ++n)
                if (n > 9999) throw AppFailure(ErrorCode.FOLDER)
            }
            val merging = app.split && naming.split == SplitFormat.APK
            val document =
                folder.createFile(
                    if (app.split && !merging) "application/zip"
                    else "application/vnd.android.package-archive",
                    name,
                ) ?: throw AppFailure(ErrorCode.FOLDER)
            // When merging, the merge takes the first 60% of the bar and copying the rest.
            var copyLength = total
            var processed = 0L
            var last = 0L
            suspend fun report(delta: Long) {
                processed += delta
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - last >= 100 || processed >= copyLength) {
                    progress(
                        if (merging) (total * (0.6 + 0.4 * processed / copyLength)).toLong()
                        else processed,
                        total,
                    )
                    last = now
                }
            }
            var merged: File? = null
            try {
                created(document.uri.toString(), name)
                if (document.name != name) {
                    document.renameTo(name)
                    created(document.uri.toString(), document.name ?: name)
                }
                if (!app.split || merging) {
                    val input =
                        if (merging)
                            ApkMerger(context)
                                .merge(app) { progress((total * 0.6f * it).toLong(), total) }
                                .also { merged = it }
                        else File(app.basePath)
                    copyLength = input.length().coerceAtLeast(1)
                    val descriptor =
                        context.contentResolver.openFileDescriptor(document.uri, "w")
                            ?: throw AppFailure(ErrorCode.FOLDER)
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { target ->
                        FileInputStream(input).use { source ->
                            var position = 0L
                            while (position < copyLength) {
                                ensureActive()
                                val size =
                                    source.channel.transferTo(
                                        position,
                                        minOf(1024 * 1024L, copyLength - position),
                                        target.channel,
                                    )
                                if (size > 0) {
                                    position += size
                                    report(size)
                                } else {
                                    source.channel.position(position)
                                    val buffer = ByteArray(64 * 1024)
                                    val read = source.read(buffer)
                                    if (read < 0) break
                                    target.write(buffer, 0, read)
                                    position += read
                                    report(read.toLong())
                                }
                            }
                            if (position != copyLength) throw AppFailure(ErrorCode.SOURCE)
                            target.flush()
                        }
                    }
                } else {
                    val stream =
                        context.contentResolver.openOutputStream(document.uri, "w")
                            ?: throw AppFailure(ErrorCode.FOLDER)
                    ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                        zip.setLevel(Deflater.NO_COMPRESSION) // APKs are already compressed and aligned.
                        val xapk = naming.split == SplitFormat.XAPK
                        val ids = app.paths.mapIndexed { index, path ->
                            if (index == 0) "base" else splitId(path, index)
                        }
                        val names = mutableSetOf<String>()
                        val entries =
                            app.paths.mapIndexed { index, path ->
                                var entry =
                                    when {
                                        index == 0 && xapk -> "${app.packageName}.apk"
                                        index == 0 -> "base.apk"
                                        xapk -> FileNames.clean("${ids[index]}.apk")
                                        else -> FileNames.clean(File(path).name)
                                    }
                                if (!names.add(entry)) {
                                    entry = "split_${index}.apk"
                                    names += entry
                                }
                                entry
                            }
                        if (xapk) {
                            zip.putNextEntry(ZipEntry("manifest.json"))
                            zip.write(xapkManifest(app, ids, entries, total).toByteArray())
                            zip.closeEntry()
                        }
                        app.paths.forEachIndexed { index, path ->
                            ensureActive()
                            zip.putNextEntry(ZipEntry(entries[index]))
                            val expected = File(path).length()
                            var copied = 0L
                            FileInputStream(path).use { source ->
                                val b = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val read = source.read(b)
                                    if (read < 0) break
                                    zip.write(b, 0, read)
                                    copied += read
                                    report(read.toLong())
                                }
                            }
                            if (copied != expected) throw AppFailure(ErrorCode.SOURCE)
                            zip.closeEntry()
                        }
                    }
                }
                progress(total, total)
                Extracted(document.uri.toString(), document.name ?: name, document.length())
            } catch (e: CancellationException) {
                withContext(NonCancellable) { document.delete() }
                throw e
            } catch (e: Exception) {
                document.delete()
                throw when (e) {
                    is AppFailure -> e
                    is SecurityException -> AppFailure(ErrorCode.FOLDER, e)
                    else ->
                        AppFailure(
                            if (e.message.orEmpty().contains("ENOSPC")) ErrorCode.STORAGE
                            else ErrorCode.SOURCE,
                            e,
                        )
                }
            } finally {
                merged?.parentFile?.deleteRecursively()
            }
        }
}
