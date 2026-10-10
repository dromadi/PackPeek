@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.packpeek.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.packpeek.app.R
import com.packpeek.app.data.BackupJob
import com.packpeek.app.data.BackupRecord
import com.packpeek.app.data.Settings
import com.packpeek.app.model.*

@Composable
fun PackPeekRoot(vm: MainViewModel, incoming: Uri?, consumed: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    PackPeekTheme(settings) {
        if (settings == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@PackPeekTheme
        }
        val c = LocalContext.current
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "apps"
        val folderPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                it?.let(vm::chooseFolder)
            }
        val filePicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                it?.let { uri -> vm.import(uri) { nav.navigate("detail") } }
            }
        val multiPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                vm.installMany(it)
            }
        fun canInstall(): Boolean {
            if (Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls())
                return true
            c.launchSafe(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${c.packageName}"),
                )
            )
            return false
        }
        fun pickInstallFiles() {
            if (canInstall()) multiPicker.launch(arrayOf("*/*"))
        }
        fun installFiles(uris: List<Uri>) {
            if (canInstall()) vm.installMany(uris)
        }
        val notifyPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        fun backup(items: List<AppRecord>) {
            if (Build.VERSION.SDK_INT >= 33)
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            vm.requestBackup(items)
        }
        LaunchedEffect(incoming, settings?.onboarding) {
            if (incoming != null && settings?.onboarding == true) {
                vm.import(incoming) { nav.navigate("detail") }
                consumed()
            }
        }
        if (settings?.onboarding != true) {
            Onboarding(
                { folderPicker.launch(null) },
                { vm.flag("onboarding", true) },
                folderName(c, settings!!.folder),
            )
            return@PackPeekTheme
        }
        val latest by vm.latestJob.collectAsStateWithLifecycle()
        val records by vm.jobRecords.collectAsStateWithLifecycle()
        val work by vm.workInfo.collectAsStateWithLifecycle()
        val importing by vm.importing.collectAsStateWithLifecycle()
        val installing by vm.installing.collectAsStateWithLifecycle()
        val queue by vm.queue.collectAsStateWithLifecycle()
        val queueRunning by vm.queueRunning.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val resources = LocalResources.current
        // A single successful extract gets a quick "Saved · Share" bar instead of a dialog.
        val quick = latest?.let { quickResult(it, records) }
        var announced by remember { mutableStateOf<BackupRecord?>(null) }
        LaunchedEffect(quick?.id) {
            if (quick != null) {
                announced = quick
                vm.dismissResult()
            }
        }
        // Keyed on the announced record, so dismissing the job does not cancel the bar.
        LaunchedEffect(announced?.id) {
            val record = announced ?: return@LaunchedEffect
            val result =
                snackbar.showSnackbar(
                    resources.getString(R.string.saved_file, record.fileName),
                    actionLabel = resources.getString(R.string.share),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) shareFiles(c, listOf(Uri.parse(record.uri)))
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (route != "apps")
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(
                                    when (route) {
                                        "history" -> R.string.history
                                        "dashboard" -> R.string.overview
                                        "settings" -> R.string.settings
                                        "about" -> R.string.about
                                        "deep" -> R.string.deep_button_title
                                        else -> R.string.app_details
                                    }
                                )
                            )
                        },
                        navigationIcon = {
                            if (route !in listOf("history", "dashboard"))
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        stringResource(R.string.back),
                                    )
                                }
                        },
                        actions = {
                            if (route in listOf("history", "dashboard"))
                                IconButton(
                                    onClick = {
                                        nav.navigate("settings") { launchSingleTop = true }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                                }
                        },
                    )
            },
            bottomBar = {
                if (route in listOf("apps", "history", "dashboard"))
                    NavigationBar {
                        listOf(
                                Triple("apps", R.string.apps, PackIcons.Apps),
                                Triple("history", R.string.history, PackIcons.History),
                                Triple("dashboard", R.string.overview, PackIcons.Insights),
                            )
                            .forEach { (key, title, icon) ->
                                NavigationBarItem(
                                    selected = route == key,
                                    onClick = {
                                        nav.navigate(key) {
                                            popUpTo("apps") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(icon, null) },
                                    label = { Text(stringResource(title)) },
                                )
                            }
                    }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (latest?.state in listOf("QUEUED", "RUNNING")) {
                    val percent = work?.progress?.getInt("percent", 0) ?: 0
                    val done = records.count { it.state in listOf("SUCCESS", "FAILED") }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(PackIcons.Download, null, Modifier.size(20.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(
                                        work?.progress?.getString("label")
                                            ?: stringResource(R.string.backup_running),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.backup_progress,
                                            (done + 1).coerceAtMost(records.size.coerceAtLeast(1)),
                                            records.size.coerceAtLeast(1),
                                            percent,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = vm::cancelBackup) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                            )
                        }
                    }
                }
                if (queueRunning) {
                    val done = queue.count { it.result != null || it.error != null }
                    val current = queue.getOrNull(done)
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    R.string.queue_progress,
                                    (done + 1).coerceAtMost(queue.size),
                                    queue.size,
                                    current?.label?.ifBlank { current.name } ?: "",
                                ),
                                Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::cancelQueue) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
                if (importing || installing) LinearProgressIndicator(Modifier.fillMaxWidth())
                NavHost(
                    navController = nav,
                    startDestination = "apps",
                    modifier = Modifier.weight(1f),
                ) {
                    composable("apps") {
                        AppsScreen(
                            vm,
                            ::backup,
                            { nav.navigate("detail") },
                            { folderPicker.launch(null) },
                            { filePicker.launch(arrayOf("*/*")) },
                            { nav.navigate("settings") { launchSingleTop = true } },
                            ::pickInstallFiles,
                        )
                    }
                    composable("detail") { DetailScreen(vm, ::backup) { nav.navigate("deep") } }
                    composable("deep") { DeepScreen(vm) { nav.navigate("settings") { launchSingleTop = true } } }
                    composable("history") {
                        HistoryScreen(
                            vm,
                            { nav.navigate("detail") },
                            { uri -> installFiles(listOf(uri)) },
                            ::backup,
                        )
                    }
                    composable("dashboard") {
                        DashboardScreen(
                            vm,
                            {
                                vm.inspect(it)
                                nav.navigate("detail")
                            },
                            {
                                vm.showGroup(it)
                                nav.navigate("apps") {
                                    popUpTo("apps") { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(vm, { folderPicker.launch(null) }, { nav.navigate("about") })
                    }
                    composable("about") { AboutScreen() }
                }
            }
        }
        val error by vm.error.collectAsStateWithLifecycle()
        // Dialog content composes separately from this scope and can run once more after the
        // state is cleared, so every dialog works on a captured non-null value, never `!!`.
        error?.let { code ->
            AlertDialog(
                onDismissRequest = { vm.error.value = null },
                title = { Text(stringResource(R.string.attention)) },
                text = { Text(stringResource(errorText(code))) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.error.value = null
                            if (code == ErrorCode.FOLDER) folderPicker.launch(null)
                        }
                    ) {
                        Text(
                            stringResource(
                                if (code == ErrorCode.FOLDER) R.string.choose_folder
                                else R.string.close
                            )
                        )
                    }
                },
            )
        }
        val confirmation by vm.confirmation.collectAsStateWithLifecycle()
        val current = settings
        val pending = confirmation
        if (pending != null && current != null)
            AlertDialog(
                onDismissRequest = { vm.confirmation.value = null },
                icon = { Icon(PackIcons.Download, null) },
                title = { Text(stringResource(R.string.confirm_backup)) },
                text = {
                    Column {
                        Text(
                            pluralStringResource(
                                R.plurals.backup_summary,
                                pending.size,
                                pending.size,
                                sizeText(pending.sumOf { it.size }),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            Modifier.padding(top = 6.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                PackIcons.Folder,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                folderName(c, current.folder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (current.naming.split == SplitFormat.APK && pending.any { it.split })
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(bottom = 10.dp),
                            ) {
                                Text(
                                    stringResource(R.string.merged_apk_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        HorizontalDivider()
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(pending) { app ->
                                Row(
                                    Modifier.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppIcon(app, Modifier.size(36.dp))
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(
                                            FileNames.format(app, current.naming),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                        )
                                        Text(
                                            sizeText(app.size),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.flag("confirm", !current.confirm) }
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(!current.confirm, null)
                            Text(
                                stringResource(R.string.dont_ask_again),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { vm.startBackup(pending) }) {
                        Text(stringResource(R.string.extract))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.confirmation.value = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        val job = latest
        if (
            job != null &&
                job.state in listOf("COMPLETED", "FAILED", "CANCELLED") &&
                !job.dismissed &&
                quickResult(job, records) == null
        ) {
            val succeeded = records.filter { it.state == "SUCCESS" }
            val failed = records.count { it.state == "FAILED" }
            AlertDialog(
                onDismissRequest = vm::dismissResult,
                icon = {
                    Icon(
                        when {
                            job.state == "CANCELLED" -> Icons.Rounded.Close
                            failed > 0 -> Icons.Rounded.Warning
                            else -> Icons.Rounded.CheckCircle
                        },
                        null,
                    )
                },
                title = {
                    Text(
                        stringResource(
                            when {
                                job.state == "CANCELLED" -> R.string.backup_cancelled
                                failed > 0 -> R.string.backup_partial
                                else -> R.string.backup_done
                            }
                        )
                    )
                },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.result_summary,
                                succeeded.size,
                                records.size,
                                sizeText(succeeded.sumOf { it.bytes }),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            Modifier.padding(top = 6.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                PackIcons.Folder,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                folderName(c, job.folder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(records) { r ->
                                val ok = r.state == "SUCCESS"
                                Row(
                                    Modifier.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        when (r.state) {
                                            "SUCCESS" -> Icons.Rounded.CheckCircle
                                            "CANCELLED" -> Icons.Rounded.Close
                                            else -> Icons.Rounded.Warning
                                        },
                                        null,
                                        Modifier.size(20.dp),
                                        tint =
                                            if (ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                    )
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(r.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (ok) r.fileName
                                            else
                                                stringResource(
                                                    if (r.state == "CANCELLED") R.string.cancelled
                                                    else
                                                        errorText(
                                                            runCatching {
                                                                    ErrorCode.valueOf(r.error)
                                                                }
                                                                .getOrDefault(ErrorCode.UNKNOWN)
                                                        )
                                                ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = { openFolder(c, job.folder) }) {
                                Icon(PackIcons.Folder, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.open_folder))
                            }
                            TextButton(
                                enabled = succeeded.isNotEmpty(),
                                onClick = {
                                    shareFiles(c, succeeded.map { Uri.parse(it.uri) })
                                },
                            ) {
                                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.share))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = vm::dismissResult) { Text(stringResource(R.string.done)) }
                },
            )
        }
        val crash by vm.lastCrash.collectAsStateWithLifecycle()
        crash?.let { log ->
            AlertDialog(
                onDismissRequest = vm::clearCrash,
                title = { Text(stringResource(R.string.crash_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.crash_body))
                        Text(
                            log.lineSequence().take(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier.heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            c.copyText(log)
                            vm.clearCrash()
                        }
                    ) {
                        Text(stringResource(R.string.crash_copy))
                    }
                },
                dismissButton = {
                    TextButton(onClick = vm::clearCrash) { Text(stringResource(R.string.close)) }
                },
            )
        }
        val installResult by vm.installResult.collectAsStateWithLifecycle()
        if (!queueRunning) installResult?.let { InstallResultDialog(it, vm) }
        if (!queueRunning && queue.isNotEmpty()) QueueSummaryDialog(queue, vm::dismissQueue)
        val appList by vm.appListShared.collectAsStateWithLifecycle()
        LaunchedEffect(appList) {
            appList?.let {
                shareFiles(c, listOf(it), "text/csv")
                vm.appListShared.value = null
            }
        }
        val icon by vm.iconSaved.collectAsStateWithLifecycle()
        val saved = icon
        if (saved != null)
            AlertDialog(
                onDismissRequest = { vm.iconSaved.value = null },
                title = { Text(stringResource(R.string.icon_saved)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            shareFiles(c, listOf(saved), "image/png")
                            vm.iconSaved.value = null
                        }
                    ) {
                        Text(stringResource(R.string.share))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.iconSaved.value = null }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
    }
}

/** The one record of a finished, fully successful single-app job not yet acknowledged. */
private fun quickResult(job: BackupJob, records: List<BackupRecord>): BackupRecord? =
    records.singleOrNull()?.takeIf {
        job.state == "COMPLETED" && !job.dismissed && it.jobId == job.id && it.state == "SUCCESS"
    }

@Composable
private fun InstallResultDialog(result: InstallResult, vm: MainViewModel) {
    val c = LocalContext.current
    val installed =
        remember(result) {
            result.packageName.isNotBlank() &&
                runCatching { vm.owner.apps.packageInfo(result.packageName) }.isSuccess
        }
    val launch =
        remember(result) {
            if (result.success && result.packageName.isNotBlank())
                c.packageManager.getLaunchIntentForPackage(result.packageName)
            else null
        }
    val canUninstall =
        installed &&
            result.failure in
                listOf(InstallFailure.SIGNATURE, InstallFailure.DOWNGRADE, InstallFailure.CONFLICT)
    AlertDialog(
        onDismissRequest = vm::dismissInstallResult,
        title = {
            Text(
                stringResource(
                    if (result.success) R.string.install_success else R.string.install_failed_title
                )
            )
        },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.success) Text(stringResource(R.string.install_success_body))
                    else {
                        Text(stringResource(installFailureText(result.failure!!)))
                        if (result.detail.isNotBlank())
                            Text(
                                stringResource(R.string.install_system_message, result.detail),
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
        },
        confirmButton = {
            if (launch != null)
                TextButton(
                    onClick = {
                        vm.dismissInstallResult()
                        c.launchSafe(launch)
                    }
                ) {
                    Text(stringResource(R.string.open_app))
                }
            else if (canUninstall)
                TextButton(
                    onClick = {
                        vm.dismissInstallResult()
                        c.launchSafe(
                            android.content.Intent(
                                android.content.Intent.ACTION_DELETE,
                                Uri.parse("package:${result.packageName}"),
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.uninstall_current))
                }
            else
                TextButton(onClick = vm::dismissInstallResult) {
                    Text(stringResource(R.string.close))
                }
        },
        dismissButton = {
            if (launch != null || canUninstall)
                TextButton(onClick = vm::dismissInstallResult) {
                    Text(stringResource(R.string.close))
                }
        },
    )
}

@Composable
private fun QueueSummaryDialog(queue: List<QueueEntry>, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.queue_summary_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(
                        stringResource(
                            R.string.queue_counts,
                            queue.count { it.result?.success == true },
                            queue.count { it.result?.success == false || it.error != null },
                        )
                    )
                }
                items(queue) { e ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(e.label.ifBlank { e.name })
                        Text(
                            when {
                                e.result?.success == true -> stringResource(R.string.install_success)
                                e.result != null ->
                                    stringResource(installFailureText(e.result.failure!!))
                                e.error != null -> stringResource(errorText(e.error))
                                else -> stringResource(R.string.cancelled)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (e.result?.success == true) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.done)) } },
    )
}

fun installFailureText(failure: InstallFailure): Int =
    when (failure) {
        InstallFailure.ABORTED -> R.string.install_fail_aborted
        InstallFailure.BLOCKED -> R.string.install_fail_blocked
        InstallFailure.CONFLICT -> R.string.install_fail_conflict
        InstallFailure.INCOMPATIBLE -> R.string.install_fail_incompatible
        InstallFailure.INVALID -> R.string.install_fail_invalid
        InstallFailure.STORAGE -> R.string.install_fail_storage
        InstallFailure.SIGNATURE -> R.string.install_fail_signature
        InstallFailure.DOWNGRADE -> R.string.install_fail_downgrade
        InstallFailure.ABI -> R.string.install_fail_abi
        InstallFailure.SDK -> R.string.install_fail_sdk
        InstallFailure.TARGET -> R.string.install_fail_target
        InstallFailure.MISSING_SPLIT -> R.string.install_fail_missing_split
        InstallFailure.FEATURE -> R.string.install_fail_feature
        InstallFailure.NO_RESULT -> R.string.install_fail_no_result
        InstallFailure.UNKNOWN -> R.string.install_fail_unknown
    }

@Composable
private fun Onboarding(folder: () -> Unit, finish: () -> Unit, location: String) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(R.string.onboarding_one, R.string.onboarding_two, R.string.onboarding_three)
    val texts =
        listOf(
            R.string.onboarding_one_body,
            R.string.onboarding_two_body,
            R.string.onboarding_three_body,
        )
    val icons = listOf(PackIcons.Inventory2, PackIcons.VerifiedUser, PackIcons.Folder)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (step < 2) TextButton(onClick = finish) { Text(stringResource(R.string.skip)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(96.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icons[step],
                            null,
                            Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(titles[step]), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(texts[step]),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (step == 2) {
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = folder, modifier = Modifier.fillMaxWidth()) {
                        Icon(PackIcons.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(location, maxLines = 1)
                    }
                    Text(
                        stringResource(R.string.backup_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.privacy_local),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) {
                        Box(
                            Modifier.size(width = if (it == step) 20.dp else 8.dp, height = 8.dp)
                                .background(
                                    if (it == step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                        )
                    }
                }
                if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
                Button(onClick = { if (step < 2) step++ else finish() }) {
                    Text(stringResource(if (step == 2) R.string.start else R.string.next))
                }
            }
        }
    }
}
