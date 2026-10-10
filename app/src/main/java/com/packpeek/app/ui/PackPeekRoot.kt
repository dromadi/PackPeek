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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.packpeek.app.R
import com.packpeek.app.data.Settings
import com.packpeek.app.model.*

@Composable
fun PackPeekTheme(settings: Settings?, content: @Composable () -> Unit) {
    val dark =
        when (settings?.theme) {
            "LIGHT" -> false
            "DARK" -> true
            else -> isSystemInDarkTheme()
        }
    val c = LocalContext.current
    val accents =
        mapOf(
            "TEAL" to (0xFF006A5CL to 0xFF76E4C5L),
            "BLUE" to (0xFF205EABL to 0xFFA6C8FFL),
            "PURPLE" to (0xFF6840A3L to 0xFFD6B9FFL),
            "ORANGE" to (0xFF904D00L to 0xFFFFBA73L),
            "RED" to (0xFFA63440L to 0xFFFFB2B9L),
            "GRAY" to (0xFF465C68L to 0xFFADC9D9L),
        )
    val pair = accents[settings?.accent] ?: accents.getValue("TEAL")
    val scheme =
        if (settings?.accent == "DYNAMIC" && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        } else if (dark)
            darkColorScheme(
                primary = Color(pair.second),
                onPrimary = Color(0xFF08251F),
                background = Color(0xFF101619),
                surface = Color(0xFF101619),
            )
        else
            lightColorScheme(
                primary = Color(pair.first),
                background = Color(0xFFF7F9F8),
                surface = Color(0xFFF7F9F8),
            )
    MaterialTheme(colorScheme = scheme, content = content)
}

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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when (route) {
                                    "apps" -> R.string.app_name
                                    "history" -> R.string.history
                                    "dashboard" -> R.string.overview
                                    "settings" -> R.string.settings
                                    "about" -> R.string.about
                                    else -> R.string.app_details
                                }
                            )
                        )
                    },
                    navigationIcon = {
                        if (route !in listOf("apps", "history", "dashboard"))
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.back),
                                )
                            }
                    },
                    actions = {
                        if (route == "apps") {
                            TextButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                enabled = !importing && !installing,
                            ) {
                                Text(stringResource(R.string.analyze_file))
                            }
                            IconButton(onClick = vm::refresh) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                            }
                        }
                        if (route != "settings")
                            IconButton(
                                onClick = { nav.navigate("settings") { launchSingleTop = true } }
                            ) {
                                Icon(Icons.Default.Settings, stringResource(R.string.settings))
                            }
                    },
                )
            },
            bottomBar = {
                if (route in listOf("apps", "history", "dashboard"))
                    NavigationBar {
                        listOf(
                                Triple("apps", R.string.apps, Icons.Default.Home),
                                Triple("history", R.string.history, Icons.Default.Check),
                                Triple("dashboard", R.string.overview, Icons.Default.Info),
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
                if (latest?.state in listOf("QUEUED", "RUNNING"))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    work?.progress?.getString("label")
                                        ?: stringResource(R.string.backup_running),
                                    Modifier.weight(1f),
                                )
                                TextButton(onClick = vm::cancelBackup) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                            LinearProgressIndicator(
                                progress = { (work?.progress?.getInt("percent", 0) ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
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
                        )
                    }
                    composable("detail") { DetailScreen(vm, ::backup) }
                    composable("history") { HistoryScreen(vm) { nav.navigate("detail") } }
                    composable("dashboard") {
                        DashboardScreen(vm) {
                            vm.inspect(it)
                            nav.navigate("detail")
                        }
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
                title = { Text(stringResource(R.string.confirm_backup)) },
                text = {
                    Column {
                        Text(stringResource(R.string.backup_count, pending.size))
                        Text(
                            folderName(c, current.folder),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(pending) { app ->
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(app.label)
                                    Text(
                                        FileNames.format(app, current.naming),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        sizeText(app.size),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(current.confirm, { vm.flag("confirm", it) })
                            Text(stringResource(R.string.confirm_each_time))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.startBackup(pending) }) {
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
                !job.dismissed
        )
            AlertDialog(
                onDismissRequest = vm::dismissResult,
                title = {
                    Text(
                        stringResource(
                            if (job.state == "CANCELLED") R.string.backup_cancelled
                            else R.string.backup_result
                        )
                    )
                },
                text = {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        item {
                            Text(
                                stringResource(
                                    R.string.result_counts,
                                    records.count { it.state == "SUCCESS" },
                                    records.count { it.state == "FAILED" },
                                    records.count { it.state == "CANCELLED" },
                                )
                            )
                            Text(
                                sizeText(
                                    records.filter { it.state == "SUCCESS" }.sumOf { it.bytes }
                                )
                            )
                            Text(
                                folderName(c, job.folder),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        items(records) { r ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(r.label)
                                Text(
                                    if (r.state == "SUCCESS") r.fileName
                                    else
                                        stringResource(
                                            if (r.state == "CANCELLED") R.string.cancelled
                                            else
                                                errorText(
                                                    runCatching { ErrorCode.valueOf(r.error) }
                                                        .getOrDefault(ErrorCode.UNKNOWN)
                                                )
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::dismissResult) { Text(stringResource(R.string.done)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { openFolder(c, job.folder) }) {
                            Text(stringResource(R.string.open_folder))
                        }
                        TextButton(
                            enabled = records.any { it.state == "SUCCESS" },
                            onClick = {
                                shareFiles(
                                    c,
                                    records
                                        .filter { it.state == "SUCCESS" }
                                        .map { Uri.parse(it.uri) },
                                )
                            },
                        ) {
                            Text(stringResource(R.string.share))
                        }
                    }
                },
            )
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
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(40.dp))
        Text(stringResource(titles[step]), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(texts[step]), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.privacy_local), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        Text("${step+1} / 3", style = MaterialTheme.typography.labelLarge)
        if (step == 2) {
            OutlinedButton(onClick = folder, modifier = Modifier.fillMaxWidth()) { Text(location) }
            Text(
                stringResource(R.string.backup_disclaimer),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { if (step < 2) step++ else finish() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (step == 2) R.string.start else R.string.next))
        }
        if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
        if (step < 2) TextButton(onClick = finish) { Text(stringResource(R.string.skip)) }
    }
}
