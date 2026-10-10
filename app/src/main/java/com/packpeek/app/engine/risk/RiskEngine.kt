package com.packpeek.app.engine.risk

import android.os.Build
import com.packpeek.app.model.AppRecord
import com.packpeek.app.model.AuditReport
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Phase 1 analysis: manifests of every APK, certificate context and device state. */
class RiskEngine(private val device: DeviceStateReader) {
    suspend fun analyze(app: AppRecord, audit: AuditReport?): RiskReport =
        withContext(Dispatchers.IO) {
            val facts = ManifestScanner.scan(app.paths.map(::File), Build.VERSION.SDK_INT)
            val certificate = audit?.certificates?.firstOrNull()
            val context =
                RiskContext(
                    label = app.label,
                    fromFile = app.fromFile,
                    system = app.system,
                    source = app.source,
                    debugCertificate =
                        audit?.certificates?.any { it.subject.contains("CN=Android Debug", true) } == true,
                    certificateAgeDays =
                        certificate
                            ?.notBefore
                            ?.takeIf { it > 0 }
                            ?.let { TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - it) },
                )
            RiskRules.evaluate(facts, context, if (app.fromFile) null else device.read(app.packageName))
        }
}
