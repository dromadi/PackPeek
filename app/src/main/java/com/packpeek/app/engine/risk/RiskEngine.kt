package com.packpeek.app.engine.risk

import android.os.Build
import com.packpeek.app.data.AppRepository
import com.packpeek.app.model.AppRecord
import com.packpeek.app.model.AuditReport
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Evidence-based analysis: manifests of every APK (T1), the app's code (T2, T3) and, for installed
 * apps, device state (T4). [audit] supplies certificate details when available; otherwise they are
 * read from the package manager.
 */
class RiskEngine(private val device: DeviceStateReader, private val apps: AppRepository) {
    suspend fun analyze(app: AppRecord, audit: AuditReport?): RiskReport =
        withContext(Dispatchers.IO) {
            val files = app.paths.map(::File)
            val facts = ManifestScanner.scan(files, Build.VERSION.SDK_INT)
            ensureActive()
            // Code analysis is bounded; a failure only removes code evidence, never the report.
            val code = runCatching { DexScanner.scan(files) }.getOrNull()
            ensureActive()
            val certificate = audit?.certificates?.firstOrNull()
            val signer = if (audit == null && !app.fromFile) apps.signer(app.packageName) else null
            val notBefore = certificate?.notBefore?.takeIf { it > 0 } ?: signer?.notBefore?.takeIf { it > 0 }
            val context =
                RiskContext(
                    label = app.label,
                    fromFile = app.fromFile,
                    system = app.system,
                    source = app.source,
                    debugCertificate =
                        audit?.certificates?.any { it.subject.contains("CN=Android Debug", true) } == true ||
                            signer?.name.equals("Android Debug", ignoreCase = true),
                    certificateAgeDays =
                        notBefore?.let { TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - it) },
                )
            RiskRules.evaluate(facts, context, if (app.fromFile) null else device.read(app.packageName), code)
        }
}
