@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.packpeek.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.model.*

@Composable
fun AppsScreen(
    vm: MainViewModel,
    backup: (List<AppRecord>) -> Unit,
    details: () -> Unit,
    folder: () -> Unit,
) {
    val apps by vm.filtered.collectAsStateWithLifecycle()
    val all by vm.apps.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val filtering by vm.filtering.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val latest by vm.latestJob.collectAsStateWithLifecycle()
    val busy = latest?.state in listOf("QUEUED", "RUNNING")
    var showFilters by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.home_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                query.text,
                { vm.setQuery(query.copy(text = it)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(stringResource(R.string.search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.text.isNotEmpty())
                        IconButton(onClick = { vm.setQuery(query.copy(text = "")) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear_search))
                        }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu(
                    stringResource(R.string.app_type),
                    query.kind,
                    listOf(
                        "ALL" to R.string.all_apps,
                        "USER" to R.string.user_apps,
                        "SYSTEM" to R.string.system_apps,
                        "SPLIT" to R.string.split_apps,
                    ),
                ) {
                    vm.setQuery(query.copy(kind = it))
                }
                ChoiceMenu(
                    stringResource(R.string.sort),
                    query.sort,
                    listOf(
                        "NAME_ASC" to R.string.name_asc,
                        "NAME_DESC" to R.string.name_desc,
                        "SIZE_DESC" to R.string.size_desc,
                        "SIZE_ASC" to R.string.size_asc,
                        "UPDATED" to R.string.recently_updated,
                        "SDK" to R.string.oldest_target,
                    ),
                ) {
                    vm.setQuery(query.copy(sort = it))
                }
                FilterChip(
                    selected = showFilters,
                    onClick = { showFilters = !showFilters },
                    label = { Text(stringResource(R.string.filters)) },
                )
            }
            if (showFilters)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceMenu(
                        stringResource(R.string.source),
                        query.source,
                        listOf(
                            "ALL" to R.string.any_source,
                            "PLAY" to R.string.play_store,
                            "SIDE" to R.string.unknown_source,
                            "OTHER" to R.string.other_store,
                            "SYSTEM" to R.string.system_apps,
                        ),
                    ) {
                        vm.setQuery(query.copy(source = it))
                    }
                    ChoiceMenu(
                        stringResource(R.string.target_sdk),
                        query.maxSdk.toString(),
                        listOf(
                            "0" to R.string.any_sdk,
                            "28" to R.string.sdk_28,
                            "30" to R.string.sdk_30,
                            "33" to R.string.sdk_33,
                        ),
                    ) {
                        vm.setQuery(query.copy(maxSdk = it.toInt()))
                    }
                    ChoiceMenu(
                        stringResource(R.string.size),
                        query.minMb.toString(),
                        listOf(
                            "0" to R.string.any_size,
                            "50" to R.string.size_50,
                            "100" to R.string.size_100,
                            "500" to R.string.size_500,
                        ),
                    ) {
                        vm.setQuery(query.copy(minMb = it.toInt()))
                    }
                    ChoiceMenu(
                        stringResource(R.string.framework),
                        query.framework,
                        listOf(
                            "ALL" to R.string.any_framework,
                            "Flutter" to R.string.flutter,
                            "React Native" to R.string.react_native,
                            "Unity" to R.string.unity,
                            "Android" to R.string.android_native,
                        ),
                    ) {
                        vm.setQuery(query.copy(framework = it))
                    }
                    TextButton(onClick = { vm.setQuery(AppQuery(sort = query.sort)) }) {
                        Text(stringResource(R.string.reset_filters))
                    }
                }
            if (settings?.folder.isNullOrBlank())
                TextButton(onClick = folder) { Text(stringResource(R.string.choose_backup_folder)) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.app_count, apps.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = vm::selectAll, enabled = apps.isNotEmpty()) {
                    Text(stringResource(R.string.select_all))
                }
            }
        }
        if (filtering) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (selected.isNotEmpty())
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(onClick = { vm.selected.value = emptySet() }) {
                        Text(stringResource(R.string.clear_selection, selected.size))
                    }
                    Button(
                        onClick = { backup(all.filter { it.packageName in selected }) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.extract_selected))
                    }
                }
            }
        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (apps.isEmpty() && !scanning)
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (all.isEmpty()) R.string.empty_apps else R.string.no_matches
                        )
                    )
                }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(330.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    OutlinedCard(
                        onClick = {
                            vm.inspect(app)
                            details()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(app)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val selectLabel = stringResource(R.string.select_app, app.label)
                                Checkbox(
                                    app.packageName in selected,
                                    { vm.toggle(app.packageName) },
                                    Modifier.semantics { contentDescription = selectLabel },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${sizeText(app.size)} · ." +
                                            FileNames.extension(
                                                app,
                                                settings?.naming ?: NamingConfig(),
                                            ),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        "v${app.version} · SDK ${app.targetSdk}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { backup(listOf(app)) },
                                    enabled = !busy,
                                ) {
                                    Text(stringResource(R.string.extract))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChoiceMenu(
    label: String,
    value: String,
    options: List<Pair<String, Int>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    "$label: ${stringResource(options.firstOrNull {it.first==value}?.second ?: options.first().second)}"
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}
