package com.packpeek.app.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.packpeek.app.model.*
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*

/** Reads the base manifest of APK and APK-set streams; SAF files have no random access. */
object StreamManifest {
    private const val MAX_MANIFEST = 2 * 1024 * 1024

    /** Limits are shared by outer and nested ZIPs, including entries skipped before the manifest. */
    fun read(
        input: InputStream,
        maxInflatedBytes: Long = 512L * 1024 * 1024,
        maxEntries: Int = 10_000,
        active: () -> Boolean = { true },
    ): Map<String, String>? = read(input, nested = false, Budget(maxInflatedBytes, maxEntries, active))

    private class Budget(var bytesLeft: Long, var entriesLeft: Int, val active: () -> Boolean) {
        private val start = System.nanoTime()

        init {
            require(bytesLeft >= 0 && entriesLeft >= 0)
        }

        fun check() {
            if (!active()) throw InterruptedIOException("cancelled")
            if (System.nanoTime() - start > 20_000_000_000L) throw InterruptedIOException("time limit")
        }

        fun read(input: InputStream, buffer: ByteArray, off: Int = 0, len: Int = buffer.size): Int {
            check()
            // At the boundary probe one byte for EOF, never inflate a whole extra buffer.
            val length = if (bytesLeft < len) bytesLeft.toInt() + 1 else len
            val n = input.read(buffer, off, length)
            if (n > 0) {
                bytesLeft -= n
                if (bytesLeft < 0) throw InterruptedIOException("inflated read limit")
            }
            return n
        }
    }

    private fun read(input: InputStream, nested: Boolean, budget: Budget): Map<String, String>? {
        // Release each inflater without closing the containing ZIP or caller-owned SAF stream.
        val borrowed = object : FilterInputStream(input) { override fun close() {} }
        ZipInputStream(borrowed).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                budget.check()
                val entry = zip.nextEntry ?: return null
                if (--budget.entriesLeft < 0) throw InterruptedIOException("entry limit")
                if (!entry.isDirectory && entry.name == "AndroidManifest.xml") {
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = budget.read(zip, buffer)
                        if (n < 0) break
                        require(out.size() + n <= MAX_MANIFEST)
                        out.write(buffer, 0, n)
                    }
                    return ManifestRoot.decode(out.toByteArray())
                }
                if (!nested && !entry.isDirectory && entry.name.endsWith(".apk", true)) {
                    // Count outer decompression as well as inner APK decompression.
                    val counted = object : InputStream() {
                        override fun read(): Int {
                            val one = ByteArray(1)
                            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (len == 0) return 0
                            return budget.read(zip, b, off, len)
                        }
                    }
                    val manifest = try {
                        read(counted, nested = true, budget)
                    } catch (e: InterruptedIOException) {
                        throw e // Cancellation and shared limits must not be swallowed as a bad split.
                    } catch (_: Exception) {
                        null
                    }
                    if (manifest != null && manifest["split"].isNullOrBlank()) return manifest
                }
                // nextEntry() otherwise drains the entry internally, bypassing our inflated limit.
                while (budget.read(zip, buffer) >= 0) Unit
            }
        }
        return null
    }
}

/**
 * Caps what one archive may cost while looking for its manifest: raw bytes read and time, and stops
 * as soon as [active] turns false. ZipInputStream inflates every skipped entry, so a huge set or a
 * zip bomb would otherwise stall the scan. StreamManifest separately bounds inflated bytes and
 * checks cancellation while draining entries, including data already buffered by the inflater.
 */
class LimitedInput(
    input: InputStream,
    private val maxBytes: Long,
    private val deadline: Long,
    private val active: () -> Boolean,
) : FilterInputStream(input) {
    var consumed = 0L
        private set

    private fun check() {
        if (!active()) throw InterruptedIOException("cancelled")
        if (consumed > maxBytes) throw InterruptedIOException("read limit")
        if (System.currentTimeMillis() > deadline) throw InterruptedIOException("time limit")
    }

    override fun read(): Int {
        check()
        return super.read().also { if (it >= 0) consumed++ }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check()
        return super.read(b, off, len).also { if (it > 0) consumed += it }
    }

    override fun skip(n: Long): Long {
        check()
        return super.skip(n).also { consumed += it }
    }
}

class BackupIndexer(private val context: Context) {
    companion object {
        /** Per archive: base manifests normally sit in the first entries. */
        const val MAX_READ_BYTES = 1024L * 1024 * 1024
        const val MAX_MILLIS_PER_FILE = 20_000L
    }

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
            val job = coroutineContext.job
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
                                        val limited =
                                            LimitedInput(
                                                it,
                                                MAX_READ_BYTES,
                                                System.currentTimeMillis() + MAX_MILLIS_PER_FILE,
                                                job::isActive,
                                            )
                                        StreamManifest.read(limited.buffered(), active = job::isActive)
                                    }
                                }
                                .also { ensureActive() }
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
