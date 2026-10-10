package com.packpeek.app.ui

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.data.BackupRecord
import com.packpeek.app.data.signerChanges
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
        PrimaryTabRow(selectedTabIndex = tab) {
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
    val installed by vm.apps.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val c = LocalContext.current
    var search by remember { mutableStateOf("") }
    var hide by remember { mutableStateOf<Long?>(null) }
    val signerChanged = remember(history) { signerChanges(history) }
    val byPackage = remember(installed) { installed.associateBy { it.packageName } }
    // One group per app, newest backup first; groups ordered by their newest backup.
    val groups =
        remember(history, search) {
            history
                .filter {
                    it.label.contains(search, true) ||
                        it.packageName.contains(search, true) ||
                        it.fileName.contains(search, true)
                }
                .groupBy { it.packageName }
                .values
                .map { rows -> rows.sortedByDescending { it.created } }
                .sortedByDescending { it.first().created }
        }
    val canInstall = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PackIcons.Inventory2, null, Modifier.size(28.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(
                            stringResource(R.string.backup_totals, stats.count, sizeText(stats.bytes)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.history_intro),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (history.isNotEmpty())
            item {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            if (search.isEmpty())
                                Text(
                                    stringResource(R.string.search_history),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            BasicTextField(
                                search,
                                { search = it },
                                singleLine = true,
                                textStyle =
                                    MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (search.isNotEmpty())
                            IconButton(onClick = { search = "" }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.clear_search))
                            }
                    }
                }
            }
        if (groups.isEmpty())
            item {
                EmptyState(
                    PackIcons.History,
                    stringResource(
                        if (history.isEmpty()) R.string.history_empty_title
                        else R.string.empty_history
                    ),
                    if (history.isEmpty()) stringResource(R.string.history_empty_body) else null,
                )
            }
        items(groups, key = { it.first().packageName }) { rows ->
            val first = rows.first()
            RowGroup {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val app = byPackage[first.packageName]
                    if (app != null) AppIcon(app, Modifier.size(40.dp))
                    else
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(PackIcons.Android, null)
                            }
                        }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            first.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pluralStringResource(R.plurals.version_count, rows.size, rows.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                rows.forEach { r ->
                    GroupDivider()
                    VersionRow(
                        r,
                        signerChanged = r.id in signerChanged,
                        busy = importing,
                        onReinstall = { vm.reinstall(Uri.parse(r.uri), canInstall, onInspect) },
                        onShare = { shareFiles(c, listOf(Uri.parse(r.uri))) },
                        onInspect = { vm.import(Uri.parse(r.uri), onInspect) },
                        onHide = { hide = r.id },
                    )
                }
            }
        }
    }
    val target = hide
    if (target != null)
        AlertDialog(
            onDismissRequest = { hide = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.hide_history)) },
            text = { Text(stringResource(R.string.hide_history_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.hideRecord(target)
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

@Composable
private fun VersionRow(
    r: BackupRecord,
    signerChanged: Boolean,
    busy: Boolean,
    onReinstall: () -> Unit,
    onShare: () -> Unit,
    onInspect: () -> Unit,
    onHide: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v${r.version}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Tag(".${r.fileName.substringAfterLast('.', r.format)}")
            }
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.created))} · ${sizeText(r.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (signerChanged)
                Text(
                    stringResource(R.string.signer_changed_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
        }
        FilledTonalIconButton(onClick = onReinstall, enabled = !busy) {
            Icon(PackIcons.InstallMobile, stringResource(R.string.reinstall))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    onClick = {
                        menu = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inspect)) },
                    leadingIcon = { Icon(PackIcons.Policy, null) },
                    enabled = !busy,
                    onClick = {
                        menu = false
                        onInspect()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.hide)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = {
                        menu = false
                        onHide()
                    },
                )
            }
        }
    }
}
