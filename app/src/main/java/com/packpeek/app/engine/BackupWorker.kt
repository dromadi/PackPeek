package com.packpeek.app.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.packpeek.app.MainActivity
import com.packpeek.app.PackPeekApp
import com.packpeek.app.R
import com.packpeek.app.data.pruneTargets
import com.packpeek.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as PackPeekApp
    private val dao = app.database.backups()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString("job") ?: return Result.failure()
        val job = dao.job(jobId) ?: return Result.failure()
        try {
            setForeground(notification(0, applicationContext.getString(R.string.starting)))
            dao.jobState(jobId, "RUNNING")
            val settings = app.settings.flow.first()
            val records = dao.records(jobId)
            var lastNotification = 0L
            records.forEachIndexed { index, record ->
                if (record.state == "SUCCESS") return@forEachIndexed
                currentCoroutineContext().ensureActive()
                if (record.uri.isNotBlank())
                    runCatching {
                        DocumentFile.fromSingleUri(applicationContext, Uri.parse(record.uri))
                            ?.delete()
                    }
                dao.finish(record.id, "RUNNING")
                try {
                    val data = app.apps.installed(record.packageName)
                    val result =
                        Extractor(applicationContext).extract(
                            data,
                            Uri.parse(job.folder),
                            settings.naming,
                            created = { uri, name -> dao.finish(record.id, "RUNNING", uri, name) },
                        ) { done, total ->
                            val percent =
                                ((index + done.toDouble() / total) / records.size * 100)
                                    .toInt()
                                    .coerceIn(0, 100)
                            setProgress(workDataOf("percent" to percent, "label" to record.label))
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastNotification >= 1000) {
                                setForeground(notification(percent, record.label))
                                lastNotification = now
                            }
                        }
                    dao.finish(record.id, "SUCCESS", result.uri, result.name, result.bytes)
                    dao.setSigner(record.id, app.apps.signerDigest(record.packageName))
                    prune(record.packageName, settings.keep)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dao.finish(
                        record.id,
                        "FAILED",
                        error = (e as? AppFailure)?.code?.name ?: "SOURCE",
                    )
                }
            }
            dao.jobState(jobId, "COMPLETED")
            return Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val work = WorkManager.getInstance(applicationContext)
                if (work.getWorkInfoByIdFlow(id).first()?.state == WorkInfo.State.CANCELLED) {
                    dao.cancelPending(jobId)
                    dao.jobState(jobId, "CANCELLED")
                } else {
                    // Stopped by the system (quota, Doze, process limits): WorkManager runs this
                    // work again, and finished records are skipped on the next attempt.
                    dao.requeue(jobId)
                    dao.jobState(jobId, "QUEUED")
                }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                dao.failPending(jobId)
                dao.jobState(jobId, "FAILED")
            }
            return Result.failure()
        }
    }

    /** Applies the keep-N setting. A file already deleted by the user still leaves history. */
    private suspend fun prune(pkg: String, keep: Int) {
        for (old in pruneTargets(dao.successes(pkg), keep)) {
            runCatching {
                DocumentFile.fromSingleUri(applicationContext, Uri.parse(old.uri))?.delete()
            }
            dao.prune(old.id)
        }
    }

    private fun notification(progress: Int, label: String): ForegroundInfo {
        val c = applicationContext
        val manager = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel(
                    "backups",
                    c.getString(R.string.backups),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        val open =
            PendingIntent.getActivity(
                c,
                0,
                Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val n =
            NotificationCompat.Builder(c, "backups")
                .setSmallIcon(R.drawable.ic_pack_mono)
                .setContentTitle(c.getString(R.string.backup_running))
                .setContentText(label)
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(
                    0,
                    c.getString(R.string.cancel),
                    WorkManager.getInstance(c).createCancelPendingIntent(id),
                )
                .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(71, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(71, n)
    }
}
