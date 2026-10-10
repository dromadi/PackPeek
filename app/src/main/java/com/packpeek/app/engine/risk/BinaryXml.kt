package com.packpeek.app.engine.risk

import com.packpeek.app.model.AppFailure
import com.packpeek.app.model.ErrorCode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** One typed attribute value from compiled (binary) Android XML. */
data class XmlValue(val type: Int, val data: Int, val text: String?) {
    val isReference
        get() = type == TYPE_REFERENCE

    /** Readable form: strings as-is, booleans as true/false, references as @0x7f…. */
    fun asString(): String =
        text
            ?: when (type) {
                TYPE_BOOLEAN -> (data != 0).toString()
                TYPE_REFERENCE -> "@0x%08x".format(data)
                TYPE_INT_HEX -> "0x%x".format(data)
                else -> data.toString()
            }

    fun asBoolean(): Boolean? =
        when {
            type == TYPE_BOOLEAN -> data != 0
            text != null -> text.toBooleanStrictOrNull()
            else -> null
        }

    fun asInt(): Int? =
        when {
            type == TYPE_INT_DEC || type == TYPE_INT_HEX -> data
            text != null -> text.toIntOrNull()
            else -> null
        }

    companion object {
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_BOOLEAN = 0x12
    }
}

class XmlNode(val name: String, val attrs: Map<String, XmlValue>, val children: List<XmlNode>) {
    operator fun get(attr: String): XmlValue? = attrs[attr]

    fun child(name: String): List<XmlNode> = children.filter { it.name == name }

    /** All descendants (including this node) in document order. */
    fun walk(): Sequence<XmlNode> = sequence {
        yield(this@XmlNode)
        children.forEach { yieldAll(it.walk()) }
    }
}

/**
 * Bounded parser for compiled Android XML (manifests and res/xml files). It never executes APK
 * code. Android attributes PackPeek relies on are named by their framework resource ID, which the
 * platform itself trusts, so obfuscated or stripped names in the string pool cannot hide them. Other
 * attributes keep their string-pool name and never override an ID-named one.
 */
object BinaryXml {
    const val LIMIT = 4 * 1024 * 1024

    private val knownIds =
        mapOf(
            0x01010001 to "label",
            0x01010002 to "icon",
            0x01010003 to "name",
            0x01010006 to "permission",
            0x01010007 to "readPermission",
            0x01010008 to "writePermission",
            0x0101000c to "hasCode",
            0x0101000e to "enabled",
            0x0101000f to "debuggable",
            0x01010010 to "exported",
            0x0101001c to "priority",
            0x01010024 to "value",
            0x01010025 to "resource",
            0x01010202 to "targetActivity",
            0x0101020c to "minSdkVersion",
            0x0101021b to "versionCode",
            0x0101021c to "versionName",
            0x01010270 to "targetSdkVersion",
            0x01010271 to "maxSdkVersion",
            0x01010380 to "accessibilityEventTypes",
            0x01010385 to "canRetrieveWindowContent",
            0x010104ec to "usesCleartextTraffic",
            0x0101050d to "canPerformGestures",
            0x01010527 to "networkSecurityConfig",
            0x01010599 to "foregroundServiceType",
        )

    /** Reads one entry of an APK, bounded to [LIMIT]; null when the entry does not exist. */
    fun readEntry(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= LIMIT)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    fun parse(bytes: ByteArray): XmlNode =
        try {
            walk(bytes)
        } catch (e: AppFailure) {
            throw e
        } catch (e: Exception) {
            throw AppFailure(ErrorCode.ARCHIVE, e)
        }

    private class Builder(val name: String, val attrs: Map<String, XmlValue>) {
        val children = mutableListOf<Builder>()

        fun build(): XmlNode = XmlNode(name, attrs, children.map { it.build() })
    }

    private fun walk(bytes: ByteArray): XmlNode {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(p: Int) = b.getShort(p).toInt() and 65535
        fun i32(p: Int) = b.getInt(p)
        require(bytes.size >= 8 && bytes.size <= LIMIT && u16(0) == 3)
        var pos = u16(2)
        var strings = emptyList<String>()
        var resourceIds = IntArray(0)
        fun str(index: Int): String {
            require(index in strings.indices)
            return strings[index]
        }
        var root: Builder? = null
        val stack = ArrayList<Builder>()
        while (pos + 8 <= bytes.size) {
            val type = u16(pos)
            val header = u16(pos + 2)
            val size = i32(pos + 4)
            require(size >= 8 && size <= bytes.size - pos && header in 8..size)
            val end = pos + size
            when (type) {
                0x0001 -> strings = readStringPool(bytes, b, pos, header, end)
                0x0180 -> {
                    val count = (size - header) / 4
                    require(count in 0..100000)
                    resourceIds = IntArray(count) { i32(pos + header + 4 * it) }
                }
                0x0102 -> {
                    val ext = pos + header
                    require(ext + 20 <= end)
                    val element = str(i32(ext + 4))
                    val first = ext + u16(ext + 8)
                    val stride = u16(ext + 10)
                    val count = u16(ext + 12)
                    require(stride >= 20 && count <= 1000 && first + stride.toLong() * count <= end)
                    val attrs = LinkedHashMap<String, XmlValue>()
                    val byId = HashSet<String>()
                    for (index in 0 until count) {
                        val p = first + stride * index
                        val nameIndex = i32(p + 4)
                        val raw = i32(p + 8)
                        val t = bytes[p + 15].toInt() and 255
                        val data = i32(p + 16)
                        val known = knownIds[resourceIds.getOrNull(nameIndex)]
                        val name = known ?: str(nameIndex)
                        if (name.isEmpty() || (known == null && name in byId)) continue
                        if (known != null) byId += known
                        val text =
                            when {
                                raw >= 0 -> str(raw)
                                t == XmlValue.TYPE_STRING -> str(data)
                                else -> null
                            }
                        attrs[name] = XmlValue(t, data, text)
                    }
                    val node = Builder(element, attrs)
                    if (root == null) root = node else stack.lastOrNull()?.children?.add(node)
                    stack += node
                    require(stack.size <= 64)
                }
                0x0103 -> stack.removeLastOrNull()
            }
            pos = end
        }
        return root?.build() ?: throw AppFailure(ErrorCode.ARCHIVE)
    }

    private fun readStringPool(
        bytes: ByteArray,
        b: ByteBuffer,
        pos: Int,
        header: Int,
        end: Int,
    ): List<String> {
        fun u16(p: Int) = b.getShort(p).toInt() and 65535
        fun i32(p: Int) = b.getInt(p)
        val count = i32(pos + 8)
        val utf8 = i32(pos + 16) and 256 != 0
        val start = pos + i32(pos + 20)
        require(count in 0..100000 && pos + header + 4L * count <= end)
        require(start in pos..end)
        return (0 until count).map { index ->
            var p = start + i32(pos + header + 4 * index)
            require(p in start until end)
            fun len8(): Int {
                val n = bytes[p++].toInt() and 255
                return if (n and 128 != 0) ((n and 127) shl 8) or (bytes[p++].toInt() and 255)
                else n
            }
            fun len16(): Int {
                val n = u16(p)
                p += 2
                return if (n and 32768 != 0) ((n and 32767) shl 16) or u16(p).also { p += 2 }
                else n
            }
            if (utf8) {
                len8()
                val n = len8()
                require(p + n.toLong() <= end)
                String(bytes, p, n, Charsets.UTF_8)
            } else {
                val n = len16()
                require(p + 2L * n <= end)
                String(bytes, p, n * 2, Charsets.UTF_16LE)
            }
        }
    }
}
