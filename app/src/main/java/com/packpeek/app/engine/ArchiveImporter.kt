package com.packpeek.app.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import com.android.apksig.ApkVerifier
import com.packpeek.app.data.AppRepository
import com.packpeek.app.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/** Bounded parser for the binary XML root only. It never executes APK code. */
object ManifestRoot {
    fun read(apk: File): Map<String, String> =
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: throw AppFailure(ErrorCode.ARCHIVE)
            val bytes =
                zip.getInputStream(entry).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= 2 * 1024 * 1024)
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            decode(bytes)
        }

    fun decode(bytes: ByteArray): Map<String, String> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(p: Int) = b.getShort(p).toInt() and 65535
        fun i32(p: Int) = b.getInt(p)
        require(bytes.size >= 8 && u16(0) == 3)
        var pos = u16(2)
        var strings = emptyList<String>()
        while (pos + 8 <= bytes.size) {
            val type = u16(pos)
            val header = u16(pos + 2)
            val size = i32(pos + 4)
            require(size >= 8 && size <= bytes.size - pos)
            if (type == 1) {
                val count = i32(pos + 8)
                val utf8 = i32(pos + 16) and 256 != 0
                val start = pos + i32(pos + 20)
                require(count in 0..100000)
                strings =
                    (0 until count).map { index ->
                        var p = start + i32(pos + header + 4 * index)
                        fun len8(): Int {
                            val n = bytes[p++].toInt() and 255
                            return if (n and 128 != 0)
                                ((n and 127) shl 8) or (bytes[p++].toInt() and 255)
                            else n
                        }
                        fun len16(): Int {
                            val n = u16(p)
                            p += 2
                            return if (n and 32768 != 0)
                                ((n and 32767) shl 16) or u16(p).also { p += 2 }
                            else n
                        }
                        if (utf8) {
                            len8()
                            val n = len8()
                            require(n <= 32768 && p + n <= bytes.size)
                            String(bytes, p, n, Charsets.UTF_8)
                        } else {
                            val n = len16()
                            require(n <= 32768 && p + 2 * n <= bytes.size)
                            String(bytes, p, n * 2, Charsets.UTF_16LE)
                        }
                    }
            } else if (type == 0x102) {
                val ext = pos + header
                require(strings[i32(ext + 4)] == "manifest")
                val first = ext + u16(ext + 8)
                val stride = u16(ext + 10)
                val count = u16(ext + 12)
                require(stride >= 20 && count <= 1000)
                return (0 until count).associate { index ->
                    val p = first + stride * index
                    val name = strings[i32(p + 4)]
                    val raw = i32(p + 8)
                    val t = bytes[p + 15].toInt() and 255
                    val value = i32(p + 16)
                    name to
                        if (raw >= 0) strings[raw]
                        else if (t == 3) strings[value] else value.toUInt().toString()
                }
            }
            pos += size
        }
        throw AppFailure(ErrorCode.ARCHIVE)
    }
}
