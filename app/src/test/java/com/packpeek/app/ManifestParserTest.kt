package com.packpeek.app

import com.packpeek.app.engine.risk.BinaryXml
import com.packpeek.app.engine.risk.DeviceState
import com.packpeek.app.engine.risk.EvidenceLevel
import com.packpeek.app.engine.risk.ManifestScanner
import com.packpeek.app.engine.risk.RiskContext
import com.packpeek.app.engine.risk.RiskId
import com.packpeek.app.engine.risk.RiskRules
import com.packpeek.app.engine.risk.StateKey
import com.packpeek.app.model.AppFailure
import com.packpeek.app.model.ErrorCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestParserTest {
    @get:Rule val temp = TemporaryFolder()

    /** Minimal compiled-XML writer, enough to build hostile manifests for the parser. */
    private class Attr(val name: String, val resId: Int?, val type: Int, val data: Int = 0, val text: String? = null)

    private class Element(val name: String, val attrs: List<Attr>, val children: List<Element> = emptyList())

    private fun str(name: String, resId: Int?, value: String) =
        Attr(name, resId, 0x03, text = value)

    private fun bool(name: String, resId: Int?, value: Boolean) =
        Attr(name, resId, 0x12, if (value) -1 else 0)

    private fun int(name: String, resId: Int?, value: Int) = Attr(name, resId, 0x10, value)

    /**
     * [endName] picks the name written in each end tag (null skips the end tag); [extra] elements
     * are emitted after the root, as stray top-level elements.
     */
    private fun compile(
        root: Element,
        endName: (Element) -> String? = { it.name },
        extra: List<Element> = emptyList(),
        strayEnd: Boolean = false,
    ): ByteArray {
        val all = generateSequence(listOf(root) + extra) { level -> level.flatMap { it.children }.ifEmpty { null } }
            .flatten()
            .toList()
        // Resource-mapped attribute names must come first in the pool, in resource-map order.
        val mapped = all.flatMap { it.attrs }.filter { it.resId != null }.distinctBy { it.name to it.resId }
        val pool = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        mapped.forEach {
            pool += it.name
            ids += it.resId!!
        }
        // Plain strings live after the mapped block, even when they repeat a mapped name.
        fun index(s: String): Int =
            (mapped.size until pool.size).firstOrNull { pool[it] == s } ?: pool.size.also { pool += s }
        fun mappedIndex(a: Attr) = mapped.indexOfFirst { it.name == a.name && it.resId == a.resId }
        all.forEach { e ->
            index(e.name)
            endName(e)?.let(::index)
            e.attrs.forEach { a ->
                if (a.resId == null) index(a.name)
                a.text?.let(::index)
            }
        }

        val body = ByteArrayOutputStream()
        fun chunk(type: Int, header: Int, content: ByteArray) {
            val b = ByteBuffer.allocate(8 + content.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(type.toShort()).putShort(header.toShort()).putInt(8 + content.size).put(content)
            body.write(b.array())
        }
        // String pool (UTF-16).
        val data = ByteArrayOutputStream()
        val offsets = pool.map { s ->
            val offset = data.size()
            val b = ByteBuffer.allocate(4 + 2 * s.length + 2).order(ByteOrder.LITTLE_ENDIAN)
            if (s.length >= 0x8000) b.putShort((0x8000 or (s.length ushr 16)).toShort())
            b.putShort((s.length and 0xffff).toShort())
            s.forEach { b.putChar(it) }
            b.putShort(0)
            data.write(b.array(), 0, b.position())
            offset
        }
        while (data.size() % 4 != 0) data.write(0)
        val sp = ByteBuffer.allocate(20 + 4 * pool.size + data.size()).order(ByteOrder.LITTLE_ENDIAN)
        sp.putInt(pool.size).putInt(0).putInt(0).putInt(28 + 4 * pool.size).putInt(0)
        offsets.forEach { sp.putInt(it) }
        sp.put(data.toByteArray())
        chunk(0x0001, 28, sp.array())
        val rm = ByteBuffer.allocate(4 * ids.size).order(ByteOrder.LITTLE_ENDIAN)
        ids.forEach { rm.putInt(it) }
        chunk(0x0180, 8, rm.array())
        fun emit(e: Element) {
            val b = ByteBuffer.allocate(8 + 20 + 20 * e.attrs.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1).putInt(-1) // line, comment
            b.putInt(-1).putInt(index(e.name))
            b.putShort(20).putShort(20).putShort(e.attrs.size.toShort())
            b.putShort(0).putShort(0).putShort(0)
            e.attrs.forEach { a ->
                b.putInt(-1)
                b.putInt(if (a.resId != null) mappedIndex(a) else index(a.name))
                b.putInt(a.text?.let(::index) ?: -1)
                b.putShort(8).put(0).put(a.type.toByte())
                b.putInt(a.text?.let(::index) ?: a.data)
            }
            chunk(0x0102, 16, b.array())
            e.children.forEach(::emit)
            val name = endName(e) ?: return
            val end = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            end.putInt(1).putInt(-1).putInt(-1).putInt(index(name))
            chunk(0x0103, 16, end.array())
        }
        emit(root)
        extra.forEach(::emit)
        if (strayEnd) {
            val end = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            end.putInt(1).putInt(-1).putInt(-1).putInt(index(root.name))
            chunk(0x0103, 16, end.array())
        }
        val out = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(3).putShort(8).putInt(8 + body.size()).put(body.toByteArray())
        return out.array()
    }

    private fun apk(manifest: ByteArray): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
        }
        return file
    }

    private val NAME = 0x01010003

    private fun launcherActivity(nameAttr: String = "name") =
        Element(
            "activity",
            listOf(str(nameAttr, NAME, "t.app.Main"), bool("exported", 0x01010010, true)),
            listOf(
                Element(
                    "intent-filter",
                    emptyList(),
                    listOf(
                        Element("action", listOf(str(nameAttr, NAME, "android.intent.action.MAIN"))),
                        Element("category", listOf(str(nameAttr, NAME, "android.intent.category.LAUNCHER"))),
                    ),
                )
            ),
        )

    @Test
    fun obfuscatedAttributeNamesAreResolvedByResourceId() {
        // Obfuscators rename android:name to junk; the platform only looks at the resource ID.
        val manifest =
            Element(
                "manifest",
                listOf(str("package", null, "t.app")),
                listOf(
                    Element("uses-permission", listOf(str("x", NAME, RiskRules.INTERNET))),
                    Element(
                        "application",
                        listOf(bool("y", 0x0101000f, true)),
                        listOf(launcherActivity(nameAttr = "x")),
                    ),
                ),
            )
        val facts = ManifestScanner.scan(listOf(apk(compile(manifest))))
        assertTrue(facts.has(RiskRules.INTERNET))
        assertTrue(facts.debuggable)
        assertTrue(facts.hasLauncher)
    }

    @Test
    fun aDecoyAttributeCannotOverrideTheRealOne() {
        // A plain "exported" attribute without a resource ID is ignored by Android.
        val node =
            BinaryXml.parse(
                compile(
                    Element(
                        "activity",
                        listOf(bool("exported", 0x01010010, true), bool("exported", null, false)),
                    )
                )
            )
        assertEquals(true, node["exported"]?.asBoolean())
    }

    @Test
    fun stringsLongerThan32kAreRead() {
        val long = "A".repeat(40_000)
        val node =
            BinaryXml.parse(
                compile(Element("manifest", listOf(str("package", null, "t.app"), str("label", 0x01010001, long))))
            )
        assertEquals(long, node["label"]?.asString())
    }

    @Test
    fun permissionsCappedBelowTheDeviceSdkAreSkipped() {
        val manifest =
            Element(
                "manifest",
                listOf(str("package", null, "t.app")),
                listOf(
                    Element(
                        "uses-permission",
                        listOf(
                            str("name", NAME, "android.permission.READ_EXTERNAL_STORAGE"),
                            int("maxSdkVersion", 0x01010271, 32),
                        ),
                    ),
                    Element("uses-permission", listOf(str("name", NAME, RiskRules.INTERNET))),
                ),
            )
        val file = apk(compile(manifest))
        val modern = ManifestScanner.scan(listOf(file), deviceSdk = 36)
        assertFalse(modern.has("android.permission.READ_EXTERNAL_STORAGE"))
        assertTrue(modern.has(RiskRules.INTERNET))
        val old = ManifestScanner.scan(listOf(file), deviceSdk = 30)
        assertTrue(old.has("android.permission.READ_EXTERNAL_STORAGE"))
    }

    @Test
    fun launcherNeedsMainAndCategoryInTheSameFilter() {
        fun child(tag: String, value: String) = Element(tag, listOf(str("name", NAME, value)))
        fun filter(vararg children: Element) = Element("intent-filter", emptyList(), children.toList())
        val main = child("action", "android.intent.action.MAIN")
        val category = child("category", "android.intent.category.LAUNCHER")
        val cases = listOf(
            listOf(filter(category)) to false,
            listOf(filter(main), filter(category)) to false,
            listOf(filter(main, category)) to true,
            listOf(filter(main, child("category", "android.intent.category.LEANBACK_LAUNCHER"))) to true,
        )
        for (kind in listOf("activity", "activity-alias")) {
            for ((filters, expected) in cases) {
                val activity = Element(kind, listOf(str("name", NAME, ".Main")), filters)
                val bytes = compile(Element("manifest", listOf(str("package", null, "t.app")),
                    listOf(Element("application", emptyList(), listOf(activity)))))
                assertEquals(expected, com.packpeek.app.engine.ManifestRoot.parse(bytes).launchers.contains("t.app.Main"))
                assertEquals(expected, ManifestScanner.scan(listOf(apk(bytes))).hasLauncher)
            }
        }
    }

    @Test
    fun launcherHiddenAtRuntimeIsObserved() {
        val manifest =
            Element(
                "manifest",
                listOf(str("package", null, "t.app")),
                listOf(Element("application", emptyList(), listOf(launcherActivity()))),
            )
        val facts = ManifestScanner.scan(listOf(apk(compile(manifest))))
        assertTrue(facts.hasLauncher)
        val context = RiskContext("Example", false, false, "SIDE", false, 900)
        val hidden = RiskRules.evaluate(facts, context, DeviceState(launcherVisible = false))
        val finding = hidden.findings.single { it.id == RiskId.HIDDEN_ICON }
        assertEquals(EvidenceLevel.OBSERVED, finding.strongestLevel)
        assertTrue(finding.evidence.all { it.stateKey == StateKey.NO_LAUNCHER })
        val shown = RiskRules.evaluate(facts, context, DeviceState(launcherVisible = true))
        assertTrue(shown.findings.none { it.id == RiskId.HIDDEN_ICON })
    }

    private val nested =
        Element(
            "manifest",
            listOf(str("package", null, "t.app")),
            listOf(Element("application", emptyList(), listOf(launcherActivity()))),
        )

    private fun failsAsArchive(bytes: ByteArray) {
        val failure = runCatching { BinaryXml.parse(bytes) }.exceptionOrNull()
        assertEquals(ErrorCode.ARCHIVE, (failure as? AppFailure)?.code)
    }

    @Test
    fun wellFormedNestingIsAccepted() {
        val tree = BinaryXml.parse(compile(nested))
        assertEquals("manifest", tree.name)
        assertEquals(1, tree.child("application").single().child("activity").size)
    }

    /** An end tag naming another element would let a crafted file reshape the tree. */
    @Test
    fun endTagMustCloseTheOpenElement() {
        failsAsArchive(compile(nested, endName = { if (it.name == "intent-filter") "application" else it.name }))
        failsAsArchive(compile(nested, endName = { if (it.name == "activity") "service" else it.name }))
    }

    @Test
    fun unclosedElementsFailClosed() {
        failsAsArchive(compile(nested, endName = { if (it.name == "application") null else it.name }))
        failsAsArchive(compile(nested, endName = { if (it.name == "manifest") null else it.name }))
    }

    @Test
    fun strayEndTagsAndSecondRootsFailClosed() {
        failsAsArchive(compile(nested, strayEnd = true))
        failsAsArchive(compile(nested, extra = listOf(Element("application", emptyList(), listOf(launcherActivity())))))
    }

    /** The declared document size must match the file, and chunks must end exactly at its end. */
    @Test
    fun documentSizeMustMatch() {
        val good = compile(nested)
        assertEquals("manifest", BinaryXml.parse(good).name)
        for (extra in 1..7) failsAsArchive(good + ByteArray(extra))
        // Trailing bytes that the header also claims: they are not a chunk.
        for (extra in 1..7) {
            val padded = good + ByteArray(extra)
            java.nio.ByteBuffer.wrap(padded).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, padded.size)
            failsAsArchive(padded)
        }
        val shortHeader = good.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, good.size - 4) }
        failsAsArchive(shortHeader)
        val wrongHeaderSize = good.copyOf().also { it[2] = 16 }
        failsAsArchive(wrongHeaderSize)
    }

    @Test
    fun stringPoolHeaderOffsetsAndTerminatorsStayInsideTheirChunk() {
        val good = compile(nested)
        fun corrupt(edit: (ByteBuffer) -> Unit) = good.copyOf().also {
            edit(ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN))
        }
        val pool = 8
        val header = 28
        failsAsArchive(corrupt { it.putShort(pool + 2, 8) })
        failsAsArchive(corrupt { it.putInt(pool + 20, header) }) // data overlaps offset table
        failsAsArchive(corrupt { it.putInt(pool + header, -1) })
        failsAsArchive(corrupt { it.putInt(pool + header, Int.MAX_VALUE) })
        failsAsArchive(corrupt { b ->
            val first = pool + b.getInt(pool + 20) + b.getInt(pool + header)
            val length = b.getShort(first).toInt() and 65535
            b.putShort(first + 2 + length * 2, 1) // missing UTF-16 terminator
        })
        failsAsArchive(corrupt { b ->
            val poolSize = b.getInt(pool + 4)
            val dataOffset = b.getInt(pool + 20)
            b.putInt(pool + header, poolSize - dataOffset - 1) // length prefix crosses chunk
        })
    }

    /** Random corruption must end in a clean AppFailure, never a crash or a hang. */
    @Test
    fun corruptedManifestsFailCleanly() {
        val original =
            ZipFile(File(requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI())).use {
                requireNotNull(BinaryXml.readEntry(it, "AndroidManifest.xml"))
            }
        val random = Random(20005)
        repeat(3000) { round ->
            val bytes = original.copyOf()
            repeat(1 + random.nextInt(8)) {
                val at = random.nextInt(bytes.size)
                bytes[at] =
                    when (random.nextInt(3)) {
                        0 -> random.nextInt(256).toByte()
                        1 -> 0xff.toByte()
                        else -> 0
                    }
            }
            val input = if (round % 10 == 0) bytes.copyOf(random.nextInt(bytes.size)) else bytes
            try {
                BinaryXml.parse(input)
            } catch (_: AppFailure) {}
        }
    }
}
