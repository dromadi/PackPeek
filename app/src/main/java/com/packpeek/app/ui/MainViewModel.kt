package com.packpeek.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.packpeek.app.PackPeekApp
import com.packpeek.app.data.*
import com.packpeek.app.engine.*
import com.packpeek.app.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DetailState(
    val app: AppRecord? = null,
    val report: AuditReport? = null,
    val loading: Boolean = false,
    val error: ErrorCode? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val owner = application as PackPeekApp
    private val dao = owner.database.backups()
    private val work = WorkManager.getInstance(owner)
    val settings = owner.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _apps = MutableStateFlow<List<AppRecord>>(emptyList())
    val apps = _apps.asStateFlow()
    val query = MutableStateFlow(AppQuery())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    private val tech = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val technologies = tech.asStateFlow()
    val riskScores = MutableStateFlow<Map<String, Int>>(emptyMap())
    val checkingOverview = MutableStateFlow(false)
    val filtered =
        combine(apps, query, tech) { a, q, t -> filterApps(a, q, t) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scanning = MutableStateFlow(false)
    val filtering = MutableStateFlow(false)
    val error = MutableStateFlow<ErrorCode?>(null)
    val detail = MutableStateFlow(DetailState())
    val confirmation = MutableStateFlow<List<AppRecord>?>(null)
    val importing = MutableStateFlow(false)
    val installing = MutableStateFlow(false)
    val iconSaved = MutableStateFlow<Uri?>(null)
    val lastCrash = MutableStateFlow(runCatching { owner.crashFile.readText() }.getOrNull())
    val latestJob = dao.latest().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val jobRecords =
        latestJob
            .flatMapLatest { if (it == null) flowOf(emptyList()) else dao.observeRecords(it.id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workInfo =
        latestJob
            .flatMapLatest {
                if (it == null) flowOf(null) else work.getWorkInfoByIdFlow(UUID.fromString(it.id))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val history =
        dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val stats = dao.stats().stateIn(viewModelScope, SharingStarted.Eagerly, BackupStats(0, 0))
    private var scanJob: Job? = null
    private var auditJob: Job? = null
    private var filterJob: Job? = null
    private var overviewJob: Job? = null
    private var imported: ImportedArchive? = null
    private var starting = false
    private var pendingRefresh: Job? = null
    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_CHANGED) {
                    // Only whole-package enable/disable changes the list, not component toggles.
                    val pkg = intent.data?.schemeSpecificPart
                    val changed =
                        intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST)
                    if (pkg == null || changed?.contains(pkg) != true) return
                }
                // Updates send REMOVED, ADDED and REPLACED together; rescan once.
                pendingRefresh?.cancel()
                pendingRefresh = viewModelScope.launch {
                    delay(500)
                    refresh()
                }
            }
        }

    init {
        viewModelScope.launch {
            query.value = query.value.copy(sort = owner.settings.flow.first().sort)
            owner.settings.flow
                .map { it.onboarding }
                .distinctUntilChanged()
                .collect { if (it) refresh() }
        }
        viewModelScope.launch {
            latestJob
                .filterNotNull()
                .flatMapLatest { job ->
                    work.getWorkInfoByIdFlow(UUID.fromString(job.id)).map { job to it }
                }
                .collect { (job, info) -> reconcile(job, info) }
        }
        ContextCompat.registerReceiver(
            owner,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Keeps the job row in step with WorkManager so a stale QUEUED/RUNNING row never blocks new
     * backups: work cancelled before it ran, a worker that died, or work WorkManager has pruned.
     */
    private suspend fun reconcile(job: BackupJob, info: WorkInfo?) {
        if (job.state !in ACTIVE) return
        when {
            // The row is written before the work is enqueued.
            info == null -> if (!starting) fail(job.id)
            info.state == WorkInfo.State.CANCELLED -> {
                dao.cancelPending(job.id)
                dao.jobState(job.id, "CANCELLED")
            }
            info.state.isFinished -> fail(job.id)
        }
    }

    private suspend fun fail(jobId: String) {
        dao.failPending(jobId)
        dao.jobState(jobId, "FAILED")
    }

    fun refresh() {
        if (settings.value?.onboarding != true) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanning.value = true
            try {
                val result = owner.apps.scan()
                val old = _apps.value.associateBy { it.packageName }
                val unchanged =
                    result
                        .filter {
                            old[it.packageName]?.let { a ->
                                a.versionCode == it.versionCode && a.updated == it.updated
                            } == true
                        }
                        .map { it.packageName }
                        .toSet()
                tech.value = tech.value.filterKeys { it in unchanged }
                riskScores.value = riskScores.value.filterKeys { it in unchanged }
                _apps.value = result
                selected.value = selected.value intersect result.map { it.packageName }.toSet()
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = ErrorCode.SOURCE
            } finally {
                scanning.value = false
            }
        }
    }

    fun toggle(pkg: String) {
        selected.value = if (pkg in selected.value) selected.value - pkg else selected.value + pkg
    }

    fun selectAll() {
        selected.value = filtered.value.map { it.packageName }.toSet()
    }

    fun setQuery(value: AppQuery) {
        query.value = value
        text("sort", value.sort)
        filterJob?.cancel()
        if (value.framework != "ALL")
            filterJob = viewModelScope.launch {
                filtering.value = true
                try {
                    for (app in apps.value) {
                        ensureActive()
                        if (app.packageName !in tech.value) {
                            val fw =
                                try {
                                    owner.audit.technology(app).frameworks
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    listOf("Unknown")
                                }
                            tech.value = tech.value + (app.packageName to fw)
                        }
                    }
                } finally {
                    filtering.value = false
                }
            }
        else filtering.value = false
    }

    fun scanOverview() {
        if (checkingOverview.value) return
        overviewJob = viewModelScope.launch {
            checkingOverview.value = true
            try {
                for (app in apps.value) {
                    ensureActive()
                    try {
                        val report = owner.audit.audit(app, false)
                        tech.value = tech.value + (app.packageName to report.frameworks)
                        riskScores.value = riskScores.value + (app.packageName to report.score)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        /* Outside scan coverage. */
                    }
                }
            } finally {
                checkingOverview.value = false
            }
        }
    }

    fun cancelOverview() {
        overviewJob?.cancel()
    }

    fun text(key: String, value: String) {
        viewModelScope.launch { owner.settings.text(key, value) }
    }

    fun flag(key: String, value: Boolean) {
        viewModelScope.launch { owner.settings.flag(key, value) }
    }

    fun chooseFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                owner.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                owner.settings.text("folder", uri.toString())
                error.value = null
            } catch (e: Exception) {
                error.value = ErrorCode.FOLDER
            }
        }
    }

    fun requestBackup(items: List<AppRecord>) {
        if (items.isEmpty()) return
        if (settings.value?.confirm != false) confirmation.value = items.toList()
        else startBackup(items)
    }

    fun startBackup(items: List<AppRecord>) {
        confirmation.value = null
        if (starting || items.isEmpty() || latestJob.value?.state in ACTIVE) return
        starting = true
        viewModelScope.launch {
            var jobId: String? = null
            try {
                val s = owner.settings.flow.first()
                val folder = Uri.parse(s.folder)
                if (
                    s.folder.isBlank() ||
                        owner.contentResolver.persistedUriPermissions.none {
                            it.uri == folder && it.isWritePermission
                        }
                ) {
                    owner.settings.text("folder", "")
                    throw AppFailure(ErrorCode.FOLDER)
                }
                // KEEP silently drops a new request while older work is unfinished, which would
                // leave the new job row QUEUED forever.
                if (work.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK).first().any { !it.state.isFinished })
                    throw AppFailure(ErrorCode.UNKNOWN)
                val id = UUID.randomUUID()
                jobId = id.toString()
                dao.create(
                    BackupJob(id.toString(), folder = s.folder),
                    items.map {
                        BackupRecord(
                            jobId = id.toString(),
                            packageName = it.packageName,
                            label = it.label,
                            version = it.version,
                            format = it.format,
                        )
                    },
                )
                val request =
                    OneTimeWorkRequestBuilder<BackupWorker>()
                        .setId(id)
                        .setInputData(workDataOf("job" to id.toString()))
                        .build()
                work.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request).await()
                if (work.getWorkInfoByIdFlow(id).first() == null) throw AppFailure(ErrorCode.UNKNOWN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jobId?.let { fail(it) }
                error.value = (e as? AppFailure)?.code ?: ErrorCode.UNKNOWN
            } finally {
                starting = false
            }
        }
    }

    fun cancelBackup() {
        latestJob.value?.let { work.cancelWorkById(UUID.fromString(it.id)) }
    }

    fun dismissResult() {
        latestJob.value?.let { job -> viewModelScope.launch { dao.dismiss(job.id) } }
    }

    fun clearCrash() {
        lastCrash.value = null
        runCatching { owner.crashFile.delete() }
    }

    fun hideRecord(id: Long) {
        viewModelScope.launch { dao.hide(id) }
    }

    fun inspect(app: AppRecord) {
        auditJob?.cancel()
        detail.value = DetailState(app = app, loading = true)
        auditJob = viewModelScope.launch {
            try {
                val report = owner.audit.audit(app)
                detail.value = DetailState(app, report)
                if (!app.fromFile) {
                    riskScores.value = riskScores.value + (app.packageName to report.score)
                    tech.value = tech.value + (app.packageName to report.frameworks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detail.value =
                    DetailState(app = app, error = (e as? AppFailure)?.code ?: ErrorCode.UNKNOWN)
            }
        }
    }

    fun import(uri: Uri, onReady: () -> Unit) {
        if (importing.value || installing.value) return
        importing.value = true
        viewModelScope.launch {
            try {
                auditJob?.cancelAndJoin()
                imported?.close()
                imported = null
                imported = ArchiveImporter(owner, owner.apps).open(uri)
                inspect(imported!!.app)
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = (e as? AppFailure)?.code ?: ErrorCode.ARCHIVE
            } finally {
                importing.value = false
            }
        }
    }

    fun install() {
        val archive = imported ?: return
        if (installing.value) return
        installing.value = true
        viewModelScope.launch {
            try {
                val files = ArchiveImporter(owner, owner.apps).validateForInstall(archive)
                installPackage(owner, archive.app.packageName, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = (e as? AppFailure)?.code ?: ErrorCode.UNKNOWN
            } finally {
                installing.value = false
            }
        }
    }

    fun saveIcon(app: AppRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folderValue = owner.settings.flow.first().folder
                if (folderValue.isBlank()) throw AppFailure(ErrorCode.FOLDER)
                val folder =
                    DocumentFile.fromTreeUri(owner, Uri.parse(folderValue))?.takeIf {
                        it.canWrite()
                    } ?: throw AppFailure(ErrorCode.FOLDER)
                val drawable = loadAppDrawable(owner, app) ?: throw AppFailure(ErrorCode.SOURCE)
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                if (Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable) {
                    drawable.background.setBounds(0, 0, 512, 512)
                    drawable.background.draw(canvas)
                    drawable.foreground.setBounds(0, 0, 512, 512)
                    drawable.foreground.draw(canvas)
                } else {
                    drawable.setBounds(0, 0, 512, 512)
                    drawable.draw(canvas)
                }
                val doc =
                    folder.createFile(
                        "image/png",
                        "${FileNames.clean(app.label)}_icon_${System.currentTimeMillis()}.png",
                    ) ?: throw AppFailure(ErrorCode.FOLDER)
                try {
                    owner.contentResolver.openOutputStream(doc.uri)?.use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } ?: throw AppFailure(ErrorCode.FOLDER)
                } catch (e: Exception) {
                    doc.delete()
                    throw e
                } finally {
                    bitmap.recycle()
                }
                iconSaved.value = doc.uri
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = (e as? AppFailure)?.code ?: ErrorCode.UNKNOWN
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        owner.unregisterReceiver(packageReceiver)
        imported?.close()
    }

    private companion object {
        const val UNIQUE_WORK = "packpeek-backup"
        val ACTIVE = listOf("QUEUED", "RUNNING")
    }
}
