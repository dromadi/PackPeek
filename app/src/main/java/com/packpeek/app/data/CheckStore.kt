package com.packpeek.app.data

import java.io.File

/**
 * One app's result from the evidence check, kept with the app version it was made for. The top
 * finding is kept by name ([topId], [topLevel]) so the store does not depend on the rules.
 */
data class SavedCheck(
    val versionCode: Long,
    val updated: Long,
    val strong: Int,
    val attention: Int,
    val topId: String?,
    val topLevel: String?,
    val codeAnalyzed: Boolean,
)

/**
 * Evidence check results saved on the device, so a check of all apps survives the app being
 * closed and continues where it stopped. Results from another PackPeek version ([engine]) are
 * dropped, since its rules may differ. A small text file, written whole and replaced atomically.
 */
class CheckStore(private val file: File, private val engine: Int) {
    fun load(): Map<String, SavedCheck> {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return emptyMap()
        if (lines.firstOrNull() != header()) return emptyMap()
        return lines
            .drop(1)
            .mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 8) return@mapNotNull null
                val check =
                    SavedCheck(
                        f[1].toLongOrNull() ?: return@mapNotNull null,
                        f[2].toLongOrNull() ?: return@mapNotNull null,
                        f[3].toIntOrNull() ?: return@mapNotNull null,
                        f[4].toIntOrNull() ?: return@mapNotNull null,
                        f[5].ifEmpty { null },
                        f[6].ifEmpty { null },
                        f[7] == "1",
                    )
                f[0] to check
            }
            .toMap()
    }

    fun save(checks: Map<String, SavedCheck>) {
        val text = buildString {
            append(header()).append('\n')
            checks.forEach { (pkg, c) ->
                if ('\t' in pkg || '\n' in pkg) return@forEach
                append(pkg).append('\t').append(c.versionCode).append('\t').append(c.updated).append('\t')
                append(c.strong).append('\t').append(c.attention).append('\t')
                append(c.topId.orEmpty()).append('\t').append(c.topLevel.orEmpty()).append('\t')
                append(if (c.codeAnalyzed) "1" else "0").append('\n')
            }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun clear() {
        file.delete()
    }

    private fun header() = "packpeek-checks\t1\t$engine"
}
