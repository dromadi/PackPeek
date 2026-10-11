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
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.packpeek.app.BuildConfig
import com.packpeek.app.PackPeekApp
import com.packpeek.app.data.*
import com.packpeek.app.engine.*
import com.packpeek.app.engine.HashLookup
import com.packpeek.app.engine.risk.ActiveServices
import com.packpeek.app.engine.risk.CodeFacts
import com.packpeek.app.engine.risk.EvidenceLevel
import com.packpeek.app.engine.risk.RiskId
import com.packpeek.app.engine.risk.ScanProgress
import com.packpeek.app.engine.risk.RiskReport
import com.packpeek.app.engine.risk.Severity
import com.packpeek.app.engine.risk.codeAnalyzed
import com.packpeek.app.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AppExtras(val signer: Signer?, val abis: Set<String>)

/** The deep analysis of the app shown in details. */
data class DeepState(
    val app: AppRecord? = null,
    val running: Boolean = false,
    val report: RiskReport? = null,
    val code: CodeFacts? = null,
    val failed: Boolean = false,
    val lookup: HashLookup? = null,
)

/** What the overview needs from one app's evidence check; small enough to save on the device. */
data class RiskBrief(
    val strong: Int,
    val attention: Int,
    /** The most important finding that is not just information, and its strongest evidence. */
    val topId: RiskId?,
    val topLevel: EvidenceLevel?,
    val codeAnalyzed: Boolean,
) {
    fun saved(app: AppRecord) =
        SavedCheck(app.versionCode, app.updated, strong, attention, topId?.name, topLevel?.name, codeAnalyzed)

    companion object {
        fun of(report: RiskReport): RiskBrief {
            val top = report.findings.firstOrNull { it.severity != Severity.INFO }
            return RiskBrief(
                report.count(Severity.STRONG),
                report.count(Severity.ATTENTION),
                top?.id,
                top?.strongestLevel,
                report.coverage.codeAnalyzed,
            )
        }

        /** A saved result, if it still applies: same app version, and rules this build knows. */
        fun of(saved: SavedCheck, app: AppRecord): RiskBrief? {
            if (saved.versionCode != app.versionCode || saved.updated != app.updated) return null
            val id = saved.topId?.let { name -> RiskId.entries.firstOrNull { it.name == name } ?: return null }
            val level = saved.topLevel?.let { name -> EvidenceLevel.entries.firstOrNull { it.name == name } ?: return null }
            return RiskBrief(saved.strong, saved.attention, id, level, saved.codeAnalyzed)
        }
    }
}

/** One file of a multi-file installation and, once known, its outcome. */
data class QueueEntry(
    val name: String,
    val label: String = "",
    val result: InstallResult? = null,
    val error: ErrorCode? = null,
)

data class DetailState(
    val app: AppRecord? = null,
    val report: AuditReport? = null,
    val loading: Boolean = false,
    val error: ErrorCode? = null,
    /** The latest backup when its signer differs from the installed app's signer now. */
    val signerChangedSince: BackupRecord? = null,
    /** Evidence-based concerns; null while loading or when the analysis failed. */
    val risk: RiskReport? = null,
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
    /** Evidence check results per installed app, from the overview check or opened details. */
    val riskBriefs = MutableStateFlow<Map<String, RiskBrief>>(emptyMap())
    /** Progress of the code scan behind the app currently in details. */
    val analysisProgress = MutableStateFlow<ScanProgress?>(null)
    /** Progress of the deep analysis. */
    val deepProgress = MutableStateFlow<ScanProgress?>(null)
    private val checkStore = CheckStore(File(owner.filesDir, "checks.tsv"), BuildConfig.VERSION_CODE)
    private var checksRestored = false
    private var saveJob: Job? = null
    /** App being checked by the overview check, for its progress line. */
    val checkingApp = MutableStateFlow<String?>(null)
    /** Signer and CPU architectures per package, read for the overview groups. */
    val extras = MutableStateFlow<Map<String, AppExtras>>(emptyMap())
    val readingExtras = MutableStateFlow(false)
    /** Detail tab to open next, e.g. Permissions when tapped from the quick sheet. */
    var requestedTab = 0
    val checkingOverview = MutableStateFlow(false)
    /** True once the first app scan finished (successfully or not). */
    val loaded = MutableStateFlow(false)
    val filtered =
        // null until the first scan has been filtered, so the UI never shows "no matches"
        // while the app list is still loading.
        combine(apps, query, tech, settings, loaded) { a, q, t, s, ready ->
                if (ready) filterApps(a, q, t, s?.favorites.orEmpty()) else null
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val scanning = MutableStateFlow(false)
    val filtering = MutableStateFlow(false)
    val error = MutableStateFlow<ErrorCode?>(null)
    val detail = MutableStateFlow(DetailState())
    val confirmation = MutableStateFlow<List<AppRecord>?>(null)
    val importing = MutableStateFlow(false)
    val installing = MutableStateFlow(false)
    val iconSaved = MutableStateFlow<Uri?>(null)
    val lastCrash = MutableStateFlow(runCatching { owner.crashFile.readText() }.getOrNull())
    val appListShared = MutableStateFlow<Uri?>(null)
    val installResult = owner.installResult.asStateFlow()
    /** Layout of the archive behind the current local-file detail screen. */
    val importSummary = MutableStateFlow<Pair<ArchiveKind, Int>?>(null)
    val splitParts = MutableStateFlow<List<SplitPart>>(emptyList())
    val splitSelection = MutableStateFlow<Set<String>>(emptySet())
    val queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queueRunning = MutableStateFlow(false)
    private var queueJob: Job? = null
    private val indexer = BackupIndexer(owner)
    val backupIndex = MutableStateFlow<List<BackupFile>?>(null)
    val backups =
        combine(backupIndex, apps) { files, installed ->
                files?.let { withInstalled(it, installed.associateBy { a -> a.packageName }) }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val indexProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private var indexJob: Job? = null
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
    private var extrasJob: Job? = null
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
                riskBriefs.value = riskBriefs.value.filterKeys { it in unchanged }
                extras.value = extras.value.filterKeys { it in unchanged }
                _apps.value = result
                if (!checksRestored) {
                    checksRestored = true
                    // Results of an earlier check, for apps not changed since: a check of all apps
                    // goes on where it stopped instead of starting over.
                    val saved = withContext(Dispatchers.IO) { checkStore.load() }
                    val restored = result.mapNotNull { app -> saved[app.packageName]?.let { RiskBrief.of(it, app) }?.let { app.packageName to it } }
                    riskBriefs.value = restored.toMap() + riskBriefs.value
                }
                selected.value = selected.value intersect result.map { it.packageName }.toSet()
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = ErrorCode.SOURCE
            } finally {
                // A cancelled scan must not hide the indicator of the scan that replaced it.
                if (scanJob === coroutineContext.job) scanning.value = false
                loaded.value = true
            }
        }
    }

    fun toggle(pkg: String) {
        selected.value = if (pkg in selected.value) selected.value - pkg else selected.value + pkg
    }

    fun selectAll() {
        selected.value = filtered.value.orEmpty().map { it.packageName }.toSet()
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
                    if (filterJob === coroutineContext.job) filtering.value = false
                }
            }
        else filtering.value = false
    }

    /** Saves the check results shortly after the last change, so closing the app loses at most one. */
    private fun saveChecks() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            val byPackage = _apps.value.associateBy { it.packageName }
            val checks = riskBriefs.value.mapNotNull { (pkg, brief) -> byPackage[pkg]?.let { pkg to brief.saved(it) } }.toMap()
            withContext(NonCancellable + Dispatchers.IO) { runCatching { checkStore.save(checks) } }
        }
    }

    /**
     * Runs the evidence check (manifest, code, device state) on every user app not checked yet,
     * plus the technology detection. System apps are skipped: their findings stay informational.
     */
    fun scanOverview(again: Boolean = false) {
        if (checkingOverview.value) return
        checkingOverview.value = true
        if (again) {
            riskBriefs.value = emptyMap()
            saveChecks()
        }
        overviewJob = viewModelScope.launch {
            try {
                val queue = apps.value.filter { !it.system && it.packageName !in riskBriefs.value }.iterator()
                // Two apps at a time: code reading waits on storage as much as on the CPU.
                // Updates are applied on the main thread, so the workers never race on them.
                List(2) {
                    launch {
                        while (true) {
                            val app = queue.takeIf { it.hasNext() }?.next() ?: break
                            checkingApp.value = app.label
                            try {
                                val report = owner.risk.quick(app)
                                riskBriefs.value = riskBriefs.value + (app.packageName to RiskBrief.of(report))
                                saveChecks()
                                if (app.packageName !in tech.value)
                                    tech.value = tech.value + (app.packageName to owner.audit.technology(app).frameworks)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                /* Outside check coverage. */
                            }
                        }
                    }
                }.joinAll()
            } finally {
                checkingApp.value = null
                checkingOverview.value = false
            }
        }
    }

    /** Reads signers and native code for apps not read yet; cheap enough to run on open. */
    fun readExtras() {
        if (readingExtras.value) return
        readingExtras.value = true
        extrasJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Chunked so the overview fills in progressively without a flood of updates.
                apps.value.filter { it.packageName !in extras.value }.chunked(25).forEach { chunk ->
                    ensureActive()
                    extras.value =
                        extras.value +
                            chunk.associate {
                                it.packageName to
                                    AppExtras(owner.apps.signer(it.packageName), owner.apps.abis(it))
                            }
                }
            } finally {
                readingExtras.value = false
            }
        }
    }

    /** Shows only [group] in the app list. */
    fun showGroup(group: AppGroup) {
        selected.value = emptySet()
        setQuery(AppQuery(sort = query.value.sort, group = group))
    }

    fun cancelOverview() {
        overviewJob?.cancel()
    }

    fun text(key: String, value: String) {
        viewModelScope.launch { owner.settings.text(key, value) }
    }

    val usageAccess = MutableStateFlow(false)
    val activeServices = MutableStateFlow<ActiveServices?>(null)

    /** Re-reads device-wide state; cheap, called when the overview or settings appear. */
    fun refreshDeviceState() {
        viewModelScope.launch(Dispatchers.IO) {
            usageAccess.value = owner.deviceState.usageAccessGranted()
            activeServices.value = owner.deviceState.activeServices()
        }
    }

    fun number(key: String, value: Int) {
        viewModelScope.launch { owner.settings.number(key, value) }
    }

    fun toggleFavorite(pkg: String) {
        viewModelScope.launch { owner.settings.toggleFavorite(pkg) }
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
                            format = FileNames.extension(it, s.naming),
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
        analysisProgress.value = null
        detail.value = DetailState(app = app, loading = true)
        auditJob = viewModelScope.launch {
            try {
                val report = owner.audit.audit(app)
                val changed =
                    if (app.fromFile) null
                    else
                        withContext(Dispatchers.IO) {
                            val previous =
                                dao.successes(app.packageName).firstOrNull {
                                    it.signer.isNotBlank()
                                }
                            val now = owner.apps.signerDigest(app.packageName)
                            previous?.takeIf { now.isNotBlank() && it.signer != now }
                        }
                detail.value = DetailState(app, report, loading = true, signerChangedSince = changed)
                val risk =
                    try {
                        owner.risk.analyze(app, report) { analysisProgress.value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                detail.value = DetailState(app, report, signerChangedSince = changed, risk = risk)
                if (!app.fromFile) {
                    if (risk != null) {
                        riskBriefs.value = riskBriefs.value + (app.packageName to RiskBrief.of(risk))
                        saveChecks()
                    }
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

    val deep = MutableStateFlow(DeepState())
    private var deepJob: Job? = null

    /** Runs the deep analysis on the app currently in details. */
    fun analyzeDeep() {
        val app = detail.value.app ?: return
        if (deep.value.app == app && (deep.value.running || deep.value.report != null)) return
        deepJob?.cancel()
        deep.value = DeepState(app = app, running = true)
        deepJob = viewModelScope.launch {
            try {
                deepProgress.value = null
                val (report, code) = owner.risk.deep(app, detail.value.report) { deepProgress.value = it }
                deep.value = DeepState(app = app, report = report, code = code, failed = code?.deep == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deep.value = DeepState(app = app, failed = true)
            }
        }
    }

    /** Looks the APK's SHA-256 up on VirusTotal with the user's own key; sends nothing else. */
    fun lookUpHash() {
        val sha = deep.value.code?.deep?.sha256?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val key = owner.settings.flow.first().virusTotalKey
            deep.value = deep.value.copy(lookup = HashLookup.Running)
            deep.value = deep.value.copy(lookup = VirusTotal.lookUp(sha, key))
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
                importSummary.value = null
                splitParts.value = emptyList()
                val importer = ArchiveImporter(owner, owner.apps)
                imported = importer.open(uri)
                importSummary.value = imported!!.kind to imported!!.obbCount
                if (imported!!.files.size > 1) {
                    val parts = importer.plan(imported!!)
                    splitParts.value = parts
                    splitSelection.value = parts.filter { it.recommended }.map { it.name }.toSet()
                }
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

    /** Opens a backup from history and, when installs are allowed, starts installing it. */
    fun reinstall(uri: Uri, canInstall: Boolean, onReady: () -> Unit) {
        import(uri) {
            onReady()
            if (canInstall) install()
        }
    }

    fun install() {
        val archive = imported ?: return
        if (installing.value) return
        installing.value = true
        viewModelScope.launch {
            try {
                val files =
                    ArchiveImporter(owner, owner.apps)
                        .validateForInstall(
                            archive,
                            splitSelection.value.takeIf { splitParts.value.isNotEmpty() },
                        )
                owner.installResult.value = null
                val session = installPackage(owner, archive.app.packageName, files)
                // Stay locked until the system answers, so a second install cannot start meanwhile.
                awaitInstall(session, archive.app.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = (e as? AppFailure)?.code ?: ErrorCode.UNKNOWN
            } finally {
                installing.value = false
            }
        }
    }

    /**
     * Waits for the system's report on [session]. The report normally arrives through
     * InstallStatusActivity; if it is lost, the session is checked every second: once it is gone,
     * an updated package counts as installed, otherwise the result says nothing came back.
     * The result is also published in [PackPeekApp.installResult] for the dialog.
     */
    private suspend fun awaitInstall(session: Int, pkg: String): InstallResult {
        val installer = owner.packageManager.packageInstaller
        fun lastUpdate() = runCatching { owner.apps.packageInfo(pkg).lastUpdateTime }.getOrNull()
        val before = withContext(Dispatchers.IO) { lastUpdate() }
        val started = System.currentTimeMillis()
        var goneAt: Long? = null
        try {
            while (true) {
                owner.installResult.value?.takeIf { it.answers(session, pkg) }?.let { return it }
                val now = System.currentTimeMillis()
                val exists = withContext(Dispatchers.IO) { installer.getSessionInfo(session) != null }
                if (exists) goneAt = null else if (goneAt == null) goneAt = now
                val updated = withContext(Dispatchers.IO) { lastUpdate() }?.let { it != before } == true
                when (InstallErrors.settle(goneAt?.let { now - it }, updated, now - started)) {
                    InstallWait.WAIT -> Unit
                    InstallWait.SUCCESS ->
                        return InstallResult(pkg, sessionId = session).also { owner.installResult.value = it }
                    InstallWait.NO_RESULT -> {
                        if (exists) runCatching { installer.abandonSession(session) }
                        return InstallResult(pkg, InstallFailure.NO_RESULT, "", session)
                            .also { owner.installResult.value = it }
                    }
                }
                withTimeoutOrNull(1_000) {
                    owner.installResult.first { it != null && it.answers(session, pkg) }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { installer.abandonSession(session) } }
            throw e
        }
    }

    fun toggleSplit(name: String) {
        if (splitParts.value.any { it.name == name && it.kind == SplitKind.BASE }) return
        splitSelection.value =
            if (name in splitSelection.value) splitSelection.value - name
            else splitSelection.value + name
    }

    fun resetSplits() {
        splitSelection.value = splitParts.value.filter { it.recommended }.map { it.name }.toSet()
    }

    /** Installs several files one after another, each with its recommended parts. */
    fun installMany(uris: List<Uri>) {
        if (uris.isEmpty() || installing.value || importing.value) return
        installing.value = true
        queueRunning.value = true
        val resolver = owner.contentResolver
        // Picker grants can end with the activity; keep reads alive for the whole queue.
        uris.forEach {
            runCatching {
                resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        queue.value =
            uris.map {
                QueueEntry(
                    runCatching { DocumentFile.fromSingleUri(owner, it)?.name }.getOrNull()
                        ?: it.lastPathSegment.orEmpty()
                )
            }
        fun update(index: Int, change: (QueueEntry) -> QueueEntry) {
            queue.value = queue.value.mapIndexed { i, e -> if (i == index) change(e) else e }
        }
        queueJob = viewModelScope.launch {
            try {
                val importer = ArchiveImporter(owner, owner.apps)
                uris.forEachIndexed { index, uri ->
                    ensureActive()
                    var archive: ImportedArchive? = null
                    try {
                        archive = importer.open(uri)
                        val label = archive.app.label
                        update(index) { it.copy(label = label) }
                        val files = importer.validateForInstall(archive)
                        owner.installResult.value = null
                        val session = installPackage(owner, archive.app.packageName, files)
                        // The system reports back after the user confirms or dismisses its prompt;
                        // a lost report ends in NO_RESULT instead of blocking the queue.
                        val result = awaitInstall(session, archive.app.packageName)
                        owner.installResult.value = null
                        update(index) { it.copy(result = result) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        update(index) {
                            it.copy(error = (e as? AppFailure)?.code ?: ErrorCode.UNKNOWN)
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { archive?.close() }
                    }
                }
            } finally {
                uris.forEach {
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                queueRunning.value = false
                installing.value = false
            }
        }
    }

    /** Re-reads the backup folder; unchanged files come from the indexer's cache. */
    fun scanBackups() {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            indexProgress.value = 0 to 0
            try {
                val folder = owner.settings.flow.first().folder
                if (folder.isBlank()) throw AppFailure(ErrorCode.FOLDER)
                backupIndex.value =
                    indexer.scan(Uri.parse(folder), apps.value.associateBy { it.packageName }) {
                        done,
                        total ->
                        indexProgress.value = done to total
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = (e as? AppFailure)?.code ?: ErrorCode.FOLDER
            } finally {
                if (indexJob === coroutineContext.job) indexProgress.value = null
            }
        }
    }

    fun deleteBackup(file: BackupFile) {
        viewModelScope.launch {
            val deleted =
                withContext(Dispatchers.IO) {
                    runCatching {
                            DocumentFile.fromSingleUri(owner, Uri.parse(file.uri))?.delete() == true
                        }
                        .getOrDefault(false)
                }
            if (!deleted) {
                error.value = ErrorCode.FOLDER
                return@launch
            }
            dao.hideUri(file.uri)
            backupIndex.value = backupIndex.value?.filterNot { it.uri == file.uri }
        }
    }

    fun cancelQueue() {
        queueJob?.cancel()
    }

    fun dismissQueue() {
        queue.value = emptyList()
    }

    fun dismissInstallResult() {
        owner.installResult.value = null
    }

    fun exportAppList(items: List<AppRecord>) {
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(owner.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.filter { it.name.endsWith(".csv") }?.forEach { it.delete() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                val file = File(dir, "packpeek-apps-$stamp.csv")
                // A BOM lets spreadsheet apps detect UTF-8 labels.
                file.writeText("\uFEFF" + appListCsv(items))
                appListShared.value =
                    FileProvider.getUriForFile(owner, "${owner.packageName}.fileprovider", file)
            } catch (e: Exception) {
                error.value = ErrorCode.STORAGE
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
                    listOfNotNull(drawable.background, drawable.foreground).forEach {
                        it.setBounds(0, 0, 512, 512)
                        it.draw(canvas)
                    }
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
