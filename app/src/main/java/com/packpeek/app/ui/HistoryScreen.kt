package com.packpeek.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.model.AppRecord
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    vm: MainViewModel,
    onInspect: () -> Unit,
    restore: (Uri) -> Unit,
    backup: (List<AppRecord>) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf(R.string.history, R.string.backup_folder).forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(stringResource(title)) },
                )
            }
        }
        if (tab == 0) HistoryList(vm, onInspect) else BackupFolderTab(vm, onInspect, restore, backup)
    }
}

@Composable
private fun HistoryList(vm: MainViewModel, onInspect: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val c = LocalContext.current
    var search by remember { mutableStateOf("") }
    var hide by remember { mutableStateOf<Long?>(null) }
    val records =
        remember(history, search) {
            history.filter {
                it.label.contains(search, true) ||
                    it.packageName.contains(search, true) ||
                    it.fileName.contains(search, true)
            }
        }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text(
                stringResource(R.string.backup_totals, stats.count, sizeText(stats.bytes)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.history_intro), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                search,
                { search = it },
                label = { Text(stringResource(R.string.search_history)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        if (records.isEmpty())
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_history))
            }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(records, key = { it.id }) { r ->
                OutlinedCard {
                    Column(Modifier.padding(14.dp)) {
                        Text(r.label, style = MaterialTheme.typography.titleMedium)
                        Text(r.fileName, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${sizeText(r.bytes)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(r.created))}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row {
                            TextButton(onClick = { shareFiles(c, listOf(Uri.parse(r.uri))) }) {
                                Text(stringResource(R.string.share))
                            }
                            TextButton(
                                enabled = !importing,
                                onClick = { vm.import(Uri.parse(r.uri), onInspect) },
                            ) {
                                Text(stringResource(R.string.inspect))
                            }
                            TextButton(onClick = { hide = r.id }) {
                                Text(stringResource(R.string.hide))
                            }
                        }
                    }
                }
            }
        }
    }
    if (hide != null)
        AlertDialog(
            onDismissRequest = { hide = null },
            title = { Text(stringResource(R.string.hide_history)) },
            text = { Text(stringResource(R.string.hide_history_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.hideRecord(hide!!)
                        hide = null
                    }
                ) {
                    Text(stringResource(R.string.hide))
                }
            },
            dismissButton = {
                TextButton(onClick = { hide = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
}
