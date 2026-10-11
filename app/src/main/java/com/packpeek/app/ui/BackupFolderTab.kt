@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import com.packpeek.app.model.*
import java.text.DateFormat
import java.util.Date

/** Contents of the backup folder compared with installed apps, with restore and cleanup. */
@Composable
fun BackupFolderTab(
    vm: MainViewModel,
    onInspect: () -> Unit,
    restore: (Uri) -> Unit,
    backup: (List<AppRecord>) -> Unit,
) {
    val files by vm.backups.collectAsStateWithLifecycle()
    val progress by vm.indexProgress.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val latest by vm.latestJob.collectAsStateWithLifecycle()
    val c = LocalContext.current
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var delete by remember { mutableStateOf<BackupFile?>(null) }
    val folder = settings?.folder.orEmpty()
    // Rescan when the folder changes or a backup job finishes.
    LaunchedEffect(folder, latest?.state) { if (folder.isNotBlank()) vm.scanBackups() }
    val all = files.orEmpty()
    val missing = remember(apps, all) { appsWithoutBackup(apps, all) }
    val shown =
        remember(all, filter) {
            if (filter == "ALL") all else all.filter { it.status.name == filter }
        }
    fun count(status: BackupStatus) = all.count { it.status == status }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folderName(c, folder),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = vm::scanBackups, enabled = folder.isNotBlank()) {
                    Text(stringResource(R.string.rescan))
                }
            }
            Text(stringResource(R.string.folder_intro), style = MaterialTheme.typography.bodySmall)
            FilterMenuChip(
                stringResource(R.string.backup_status),
                filter,
                "ALL",
                listOf(
                    "ALL" to R.string.status_all,
                    "NEWER" to R.string.status_newer,
                    "SAME" to R.string.status_same,
                    "OLDER" to R.string.status_older,
                    "NOT_INSTALLED" to R.string.status_not_installed,
                    "MISSING" to R.string.status_missing,
                ),
            ) {
                filter = it
            }
            Text(
                stringResource(
                    R.string.folder_counts,
                    all.size,
                    count(BackupStatus.NEWER),
                    count(BackupStatus.SAME),
                    count(BackupStatus.OLDER),
                    count(BackupStatus.NOT_INSTALLED),
                    missing.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        progress?.let { (done, total) ->
            if (total > 0)
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (folder.isBlank())
                item { Text(stringResource(R.string.error_folder)) }
            else if (filter == "MISSING") {
                if (missing.isEmpty()) item { Text(stringResource(R.string.all_backed_up)) }
                items(missing, key = { "missing:${it.packageName}" }) { app ->
                    OutlinedCard {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app, Modifier.size(40.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "v${app.version} · ${sizeText(app.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            FilledTonalButton(
                                onClick = { backup(listOf(app)) },
                                enabled = latest?.state !in listOf("QUEUED", "RUNNING"),
                            ) {
                                Text(stringResource(R.string.extract))
                            }
                        }
                    }
                }
                if (missing.size > 1)
                    item {
                        Button(
                            onClick = { backup(missing) },
                            enabled = latest?.state !in listOf("QUEUED", "RUNNING"),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.backup_all_missing, missing.size))
                        }
                    }
            } else {
                if (files != null && shown.isEmpty())
                    item { Text(stringResource(R.string.folder_empty)) }
                items(shown, key = { it.uri }) { f ->
                    OutlinedCard {
                        Column(Modifier.padding(14.dp)) {
                            Text(f.label, style = MaterialTheme.typography.titleMedium)
                            Text(f.name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "v${f.version} (${f.versionCode}) · ${sizeText(f.bytes)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(f.modified))}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                stringResource(statusText(f.status)),
                                style = MaterialTheme.typography.labelLarge,
                                color =
                                    if (f.status == BackupStatus.OLDER)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                            )
                            FlowRow {
                                TextButton(
                                    onClick = { restore(Uri.parse(f.uri)) },
                                    enabled =
                                        !importing &&
                                            !installing &&
                                            f.status != BackupStatus.SAME,
                                ) {
                                    Text(
                                        stringResource(
                                            if (f.status == BackupStatus.NEWER) R.string.update_from_backup
                                            else R.string.restore
                                        )
                                    )
                                }
                                TextButton(
                                    onClick = { vm.import(Uri.parse(f.uri), onInspect) },
                                    enabled = !importing && !installing,
                                ) {
                                    Text(stringResource(R.string.inspect))
                                }
                                TextButton(onClick = { shareFiles(c, listOf(Uri.parse(f.uri))) }) {
                                    Text(stringResource(R.string.share))
                                }
                                TextButton(onClick = { delete = f }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    delete?.let { f ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(stringResource(R.string.delete_backup_title)) },
            text = { Text(stringResource(R.string.delete_backup_body, f.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteBackup(f)
                        delete = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { delete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun statusText(status: BackupStatus): Int =
    when (status) {
        BackupStatus.NEWER -> R.string.status_newer_body
        BackupStatus.SAME -> R.string.status_same_body
        BackupStatus.OLDER -> R.string.status_older_body
        BackupStatus.NOT_INSTALLED -> R.string.status_not_installed_body
    }
