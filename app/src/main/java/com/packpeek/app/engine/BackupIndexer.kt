package com.packpeek.app.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.packpeek.app.model.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*

/** Reads the base manifest of APK and APK-set streams; SAF files have no random access. */
object StreamManifest {
    private const val MAX_ENTRIES = 10000
    private const val MAX_MANIFEST = 2 * 1024 * 1024

    fun read(input: InputStream): Map<String, String>? = read(input, nested = false)

    private fun read(input: InputStream, nested: Boolean): Map<String, String>? {
        // Not closed here: a nested stream would close the outer archive.
        val zip = ZipInputStream(input)
        var count = 0
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (++count > MAX_ENTRIES) return null
            if (entry.isDirectory) continue
            if (entry.name == "AndroidManifest.xml") return ManifestRoot.decode(bounded(zip))
            if (!nested && entry.name.endsWith(".apk", true)) {
                // Sets hold one base APK; config and feature splits carry a split name.
                val manifest = runCatching { read(zip, nested = true) }.getOrNull()
                if (manifest != null && manifest["split"].isNullOrBlank()) return manifest
            }
        }
    }

    private fun bounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= MAX_MANIFEST)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

class BackupIndexer(private val context: Context) {
    private data class Key(val uri: String, val modified: Long, val bytes: Long)

    /** Parsed manifests by file version; unreadable files map to an empty manifest. */
    private val cache = mutableMapOf<Key, Map<String, String>>()

    /** Lists package archives directly in [folder] and compares them with [installed]. */
    suspend fun scan(
        folder: Uri,
        installed: Map<String, AppRecord>,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): List<BackupFile> =
        withContext(Dispatchers.IO) {
            val tree =
                DocumentFile.fromTreeUri(context, folder)?.takeIf { it.canRead() }
                    ?: throw AppFailure(ErrorCode.FOLDER)
            val files = tree.listFiles().filter { it.isFile && isPackageArchive(it.name.orEmpty()) }
            val seen = mutableSetOf<Key>()
            val result =
                files.mapIndexedNotNull { index, doc ->
                    ensureActive()
                    progress(index, files.size)
                    val key = Key(doc.uri.toString(), doc.lastModified(), doc.length())
                    seen += key
                    val manifest =
                        synchronized(cache) { cache[key] }
                            ?: runCatching {
                                    context.contentResolver.openInputStream(doc.uri)?.use {
                                        StreamManifest.read(it.buffered())
                                    }
                                }
                                .getOrNull()
                                .orEmpty()
                                .also { synchronized(cache) { cache[key] = it } }
                    val pkg = manifest["package"]?.takeIf { it.isNotBlank() }
                        ?: return@mapIndexedNotNull null
                    val code = manifestVersionCode(manifest)
                    val app = installed[pkg]
                    BackupFile(
                        key.uri,
                        doc.name.orEmpty(),
                        key.bytes,
                        key.modified,
                        pkg,
                        manifest["versionName"].orEmpty(),
                        code,
                        backupStatus(code, app?.versionCode),
                        app?.label ?: pkg,
                    )
                }
            synchronized(cache) { cache.keys.retainAll(seen) }
            progress(files.size, files.size)
            sortBackups(result)
        }
}
