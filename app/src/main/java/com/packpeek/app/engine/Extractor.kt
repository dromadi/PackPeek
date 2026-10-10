package com.packpeek.app.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.packpeek.app.model.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*

data class Extracted(val uri: String, val name: String, val bytes: Long)

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
            var n = 0
            var name = base
            while (folder.findFile(name) != null) {
                name = FileNames.numbered(base, ++n)
                if (n > 9999) throw AppFailure(ErrorCode.FOLDER)
            }
            val document =
                folder.createFile(
                    if (app.split) "application/zip" else "application/vnd.android.package-archive",
                    name,
                ) ?: throw AppFailure(ErrorCode.FOLDER)
            var processed = 0L
            var last = 0L
            suspend fun report(delta: Long) {
                processed += delta
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - last >= 100 || processed >= total) {
                    progress(processed, total)
                    last = now
                }
            }
            try {
                created(document.uri.toString(), name)
                if (document.name != name) {
                    document.renameTo(name)
                    created(document.uri.toString(), document.name ?: name)
                }
                if (!app.split) {
                    val descriptor =
                        context.contentResolver.openFileDescriptor(document.uri, "w")
                            ?: throw AppFailure(ErrorCode.FOLDER)
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { target ->
                        FileInputStream(app.basePath).use { source ->
                            var position = 0L
                            while (position < total) {
                                ensureActive()
                                val size =
                                    source.channel.transferTo(
                                        position,
                                        minOf(1024 * 1024L, total - position),
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
                            if (position != total) throw AppFailure(ErrorCode.SOURCE)
                            target.flush()
                        }
                    }
                } else {
                    val stream =
                        context.contentResolver.openOutputStream(document.uri, "w")
                            ?: throw AppFailure(ErrorCode.FOLDER)
                    ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                        zip.setLevel(1)
                        val names = mutableSetOf<String>()
                        app.paths.forEachIndexed { index, path ->
                            ensureActive()
                            var entry =
                                if (index == 0) "base.apk" else FileNames.clean(File(path).name)
                            if (!names.add(entry)) {
                                entry = "split_${index}.apk"
                                names += entry
                            }
                            zip.putNextEntry(ZipEntry(entry))
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
            }
        }
}
