package com.packpeek.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.packpeek.app.R
import com.packpeek.app.engine.HashLookup
import com.packpeek.app.engine.risk.*
import com.packpeek.app.model.AppRecord
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A shareable summary of the evidence, as plain text or a PDF, written in the app's language. */
object EvidenceReport {
    fun text(res: Resources, app: AppRecord, report: RiskReport, code: CodeFacts?, lookup: HashLookup?): String =
        buildString {
            fun line(s: String = "") = append(s).append('\n')
            line(res.getString(R.string.report_title))
            line(res.getString(R.string.report_created, DateFormat.getDateTimeInstance().format(Date())))
            line()
            line(res.getString(R.string.report_app, app.label, app.packageName, app.version))
            line(
                if (app.fromFile) res.getString(R.string.report_from_file)
                else res.getString(R.string.report_installed, sourceText(res, app.source))
            )
            code?.deep?.sha256?.takeIf { it.isNotBlank() }?.let { line("SHA-256: $it") }
            line(
                res.getString(
                    R.string.report_counts,
                    report.count(Severity.STRONG),
                    report.count(Severity.ATTENTION),
                    report.count(Severity.INFO),
                )
            )
            line()
            line(res.getString(R.string.report_disclaimer))
            line()
            line("== ${res.getString(R.string.report_findings)} ==")
            report.findings.forEach { f ->
                val texts = res.getStringArray(riskTexts(f.id))
                val severity =
                    res.getString(
                        when (f.severity) {
                            Severity.STRONG -> R.string.severity_strong
                            Severity.ATTENTION -> R.string.severity_attention
                            Severity.INFO -> R.string.severity_info
                        }
                    )
                line()
                line("• ${texts[0]} [$severity]")
                f.evidence.forEach { e ->
                    val code = e.level == EvidenceLevel.CODE || e.level == EvidenceLevel.FLOW
                    val origin =
                        when {
                            !code || e.location.isBlank() -> null
                            e.library != null -> res.getString(R.string.ev_origin_library, e.library)
                            else -> res.getString(R.string.ev_origin_app)
                        }
                    val where = listOfNotNull(e.location.takeIf { it.isNotBlank() }, origin ?: e.library.takeIf { !code }).joinToString(" · ")
                    line("  ${e.level.tag} ${evidenceText(res, e).replace("\n", " ")}${if (where.isBlank()) "" else " — $where"}")
                }
            }
            line()
            line("== ${res.getString(R.string.coverage)} ==")
            if (code == null) line("  ${res.getString(R.string.coverage_code_failed)}")
            else {
                line("  ${res.getString(R.string.coverage_code, code.dexFiles, code.classes, code.appClasses)}")
                shownGaps(code.gaps).forEach { line("  ! ${gapText(res, it)}") }
            }
            code?.deep?.let { deep ->
                line()
                line("== ${res.getString(R.string.report_deep)} ==")
                if (deep.chains.isNotEmpty()) {
                    line(res.getString(R.string.section_chains) + ":")
                    deep.chains.forEach { c ->
                        line("  ${capabilityName(res, c.source)} → ${capabilityName(res, c.sink)}")
                        line("    ${(c.toSource + c.toSink.drop(1)).distinct().joinToString(" → ") { shortMethod(it.method) }}")
                        line("    ${c.sourceApi} → ${c.sinkApi}")
                        if (c.destinations.isNotEmpty()) line("    " + destinationText(res, c.destinations))
                    }
                }
                if (deep.decoded.isNotEmpty()) {
                    line(res.getString(R.string.section_decoded) + ":")
                    deep.decoded.forEach { line("  ${decodingText(res, it)}: ${it.decoded} — ${it.method}") }
                }
                if (deep.payloads.isNotEmpty()) {
                    line(res.getString(R.string.section_payloads) + ":")
                    deep.payloads.forEach { line("  ${it.path}: ${payloadText(res, it)}") }
                }
            }
            if (lookup is HashLookup.Found || lookup is HashLookup.NotFound) {
                line()
                line("== ${res.getString(R.string.report_lookup)} ==")
                line("  ${lookupText(res, lookup)}")
            }
            line()
            line(res.getString(R.string.report_footer))
        }

    /** "Class.method" from "package.Class.method", so a chain step names where it is. */
    fun shortMethod(method: String): String {
        val name = method.substringBefore(" [")
        val cls = name.substringBeforeLast('.', "")
        return if (cls.isEmpty()) name else "${cls.substringAfterLast('.')}.${name.substringAfterLast('.')}"
    }

    fun sourceText(res: Resources, source: String) =
        res.getString(
            when (source) {
                "PLAY" -> R.string.play_store
                "SIDE" -> R.string.unknown_source
                "SYSTEM" -> R.string.system_apps
                else -> R.string.other_store
            }
        )

    fun decodingText(res: Resources, d: DecodedText) =
        when (d.technique) {
            Decoding.BASE64 -> res.getString(R.string.tech_base64)
            Decoding.JOINED -> res.getString(R.string.tech_joined)
            Decoding.CHAR_ARRAY -> res.getString(R.string.tech_char_array)
            Decoding.XOR -> res.getString(R.string.tech_xor, d.detail)
            Decoding.REVERSED -> res.getString(R.string.tech_reversed)
        }

    fun payloadText(res: Resources, p: PayloadScan): String {
        val head =
            when (p.kind) {
                PayloadKind.DEX -> res.getString(R.string.payload_dex, p.classes)
                PayloadKind.ARCHIVE -> res.getString(R.string.payload_archive, p.classes)
                PayloadKind.ENCRYPTED -> res.getString(R.string.payload_encrypted, "%.2f".format(p.entropy))
            }
        if (p.kind == PayloadKind.ENCRYPTED) return head
        val can = p.capabilities.filter { it.source || it.sink }.joinToString { capabilityName(res, it) }
        return head + " · " + if (can.isEmpty()) res.getString(R.string.payload_nothing) else res.getString(R.string.payload_can, can)
    }

    fun lookupText(res: Resources, lookup: HashLookup): String =
        when (lookup) {
            is HashLookup.Found ->
                if (lookup.malicious + lookup.suspicious == 0) res.getString(R.string.lookup_clean, lookup.engines)
                else
                    listOfNotNull(
                            res.getString(R.string.lookup_found, lookup.malicious, lookup.engines),
                            lookup.suspicious.takeIf { it > 0 }?.let { res.getString(R.string.lookup_suspicious, it) },
                            lookup.label?.let { res.getString(R.string.lookup_label, it) },
                        )
                        .joinToString(" · ")
            HashLookup.NotFound -> res.getString(R.string.lookup_not_found)
            HashLookup.NoKey -> res.getString(R.string.lookup_no_key)
            HashLookup.Running -> res.getString(R.string.lookup_running)
            is HashLookup.Failed ->
                res.getString(
                    when (lookup.reason) {
                        HashLookup.Reason.KEY -> R.string.lookup_key_error
                        HashLookup.Reason.QUOTA -> R.string.lookup_quota
                        HashLookup.Reason.NETWORK -> R.string.lookup_network
                    }
                )
        }

    fun shareText(context: Context, text: String) {
        context.launchSafe(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                context.getString(R.string.share),
            )
        )
    }

    /** Writes [text] to an A4 PDF in the shared cache folder and opens the share sheet. */
    fun sharePdf(context: Context, text: String, name: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "PackPeek-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.pdf")
        writePdf(text, file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        shareFiles(context, listOf(uri), "application/pdf")
    }

    fun writePdf(text: String, file: File) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40
        val paint = TextPaint().apply { textSize = 10f; isAntiAlias = true }
        val width = pageWidth - 2 * margin
        val layout =
            if (Build.VERSION.SDK_INT >= 23)
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setLineSpacing(0f, 1.15f).build()
            else @Suppress("DEPRECATION") StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false)
        val document = PdfDocument()
        try {
            var line = 0
            var page = 1
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= pageHeight - 2 * margin) last++
                val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, page).create())
                pdfPage.canvas.apply {
                    save()
                    translate(margin.toFloat(), (margin - top).toFloat())
                    clipRect(0, top, width, layout.getLineBottom(last))
                    layout.draw(this)
                    restore()
                }
                document.finishPage(pdfPage)
                line = last + 1
                page++
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }
}
