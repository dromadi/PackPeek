package com.packpeek.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.model.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    vm: MainViewModel,
    backup: (List<AppRecord>) -> Unit,
    details: () -> Unit,
    folder: () -> Unit,
    inspectFile: () -> Unit,
    openSettings: () -> Unit,
) {
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val apps = filtered.orEmpty()
    val loading = filtered == null || (scanning && apps.isEmpty())
    val all by vm.apps.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val filtering by vm.filtering.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val latest by vm.latestJob.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val busy = latest?.state in listOf("QUEUED", "RUNNING")
    val selecting = selected.isNotEmpty()
    val naming = settings?.naming ?: NamingConfig()
    val favorites = settings?.favorites.orEmpty()
    val c = LocalContext.current
    var quick by remember { mutableStateOf<AppRecord?>(null) }
    fun openFull(app: AppRecord, tab: Int = 0) {
        vm.requestedTab = tab
        vm.inspect(app)
        details()
    }
    quick?.let { app ->
        QuickDetailSheet(
            vm,
            app,
            extractEnabled = !busy,
            onDismiss = { quick = null },
            onExtract = {
                quick = null
                backup(listOf(app))
            },
            full = { tab ->
                quick = null
                openFull(app, tab)
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (selecting)
            SelectionBar(
                count = selected.size,
                onClose = { vm.selected.value = emptySet() },
                onSelectAll = vm::selectAll,
                onExtract = { backup(all.filter { it.packageName in selected }) },
                extractEnabled = !busy,
            )
        else
            SearchBarRow(
                text = query.text,
                onText = { vm.setQuery(query.copy(text = it)) },
                onInspect = inspectFile,
                inspectEnabled = !importing,
                onSettings = openSettings,
            )
        FilterRow(query, vm)
        if (settings?.folder.isNullOrBlank())
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PackIcons.Folder, null)
                    Text(
                        stringResource(R.string.no_folder_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    TextButton(onClick = folder) { Text(stringResource(R.string.choose)) }
                }
            }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (loading) ""
                else pluralStringResource(R.plurals.app_count_short, apps.size, apps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!selecting && apps.isNotEmpty())
                TextButton(onClick = vm::selectAll) { Text(stringResource(R.string.select_all)) }
        }
        if (filtering) LinearProgressIndicator(Modifier.fillMaxWidth())
        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f),
        ) {
            // While scanning, the pull-to-refresh indicator already shows progress.
            if (loading && !scanning)
                Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            else if (!loading && apps.isEmpty())
                EmptyState(
                    if (all.isEmpty()) PackIcons.Apps else Icons.Rounded.Search,
                    stringResource(if (all.isEmpty()) R.string.empty_apps else R.string.no_matches),
                    action =
                        if (all.isNotEmpty())
                            {
                                {
                                    OutlinedButton(
                                        onClick = { vm.setQuery(AppQuery(sort = query.sort)) }
                                    ) {
                                        Text(stringResource(R.string.reset_filters))
                                    }
                                }
                            }
                        else null,
                )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selected
                    AppRow(
                        app = app,
                        extension = FileNames.extension(app, naming),
                        selected = isSelected,
                        favorite = app.packageName in favorites,
                        extractEnabled = !busy,
                        onClick = {
                            when {
                                selecting -> vm.toggle(app.packageName)
                                settings?.tap == "QUICK" -> quick = app
                                else -> openFull(app)
                            }
                        },
                        onLongClick = { vm.toggle(app.packageName) },
                        onToggleSelect = { vm.toggle(app.packageName) },
                        onExtract = { backup(listOf(app)) },
                        menu = { close ->
                            AppMenuItems(
                                app,
                                favorite = app.packageName in favorites,
                                close = close,
                                quick = { quick = app },
                                full = { openFull(app) },
                                favoriteToggle = { vm.toggleFavorite(app.packageName) },
                                saveIcon = { vm.saveIcon(app) },
                                copy = { c.copyText(app.packageName) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    text: String,
    onText: (String) -> Unit,
    onInspect: () -> Unit,
    inspectEnabled: Boolean,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val searchLabel = stringResource(R.string.search_apps)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
                    if (text.isEmpty())
                        Text(
                            stringResource(R.string.search_apps_short),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        singleLine = true,
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = searchLabel
                            },
                    )
                }
                if (text.isNotEmpty())
                    IconButton(onClick = { onText("") }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.clear_search))
                    }
            }
        }
        IconButton(onClick = onInspect, enabled = inspectEnabled) {
            Icon(PackIcons.FileOpen, stringResource(R.string.analyze_file))
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onExtract: () -> Unit,
    extractEnabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, stringResource(R.string.clear_selection_short))
            }
            Text(
                stringResource(R.string.selected_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(PackIcons.SelectAll, stringResource(R.string.select_all))
            }
            Button(
                onClick = onExtract,
                enabled = extractEnabled,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(PackIcons.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.extract))
            }
        }
    }
}

@Composable
private fun FilterRow(query: AppQuery, vm: MainViewModel) {
    val active =
        query.group != null ||
            query.kind != "ALL" ||
            query.source != "ALL" ||
            query.maxSdk != 0 ||
            query.minMb != 0 ||
            query.framework != "ALL"
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        query.group?.let { group ->
            InputChip(
                selected = true,
                onClick = { vm.setQuery(query.copy(group = null)) },
                label = { Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(Icons.Rounded.Close, stringResource(R.string.show_all_apps), Modifier.size(18.dp))
                },
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
        FilterMenuChip(
            stringResource(R.string.app_type),
            query.kind,
            "ALL",
            listOf(
                "ALL" to R.string.all_apps,
                "USER" to R.string.user_apps,
                "SYSTEM" to R.string.system_apps,
                "SPLIT" to R.string.split_apps,
                "FAVORITE" to R.string.favorite_apps,
            ),
        ) {
            vm.setQuery(query.copy(kind = it))
        }
        FilterMenuChip(
            stringResource(R.string.sort),
            query.sort,
            "NAME_ASC",
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
        FilterMenuChip(
            stringResource(R.string.source),
            query.source,
            "ALL",
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
        FilterMenuChip(
            stringResource(R.string.size),
            query.minMb.toString(),
            "0",
            listOf(
                "0" to R.string.any_size,
                "50" to R.string.size_50,
                "100" to R.string.size_100,
                "500" to R.string.size_500,
            ),
        ) {
            vm.setQuery(query.copy(minMb = it.toInt()))
        }
        FilterMenuChip(
            stringResource(R.string.target_sdk),
            query.maxSdk.toString(),
            "0",
            listOf(
                "0" to R.string.any_sdk,
                "28" to R.string.sdk_28,
                "30" to R.string.sdk_30,
                "33" to R.string.sdk_33,
            ),
        ) {
            vm.setQuery(query.copy(maxSdk = it.toInt()))
        }
        FilterMenuChip(
            stringResource(R.string.framework),
            query.framework,
            "ALL",
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
        if (active)
            AssistChip(
                onClick = { vm.setQuery(AppQuery(text = query.text, sort = query.sort)) },
                label = { Text(stringResource(R.string.reset_filters)) },
                leadingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)) },
            )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppRecord,
    extension: String,
    selected: Boolean,
    favorite: Boolean,
    extractEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtract: () -> Unit,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val selectLabel = stringResource(R.string.select_app, app.label)
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the icon selects, like Gmail and Files.
        Box(
            Modifier.size(48.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = onToggleSelect)
                .semantics {
                    contentDescription = selectLabel
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            if (selected)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Check,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            else AppIcon(app, Modifier.size(44.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (favorite)
                    Icon(
                        Icons.Rounded.Star,
                        stringResource(R.string.unfavorite),
                        Modifier.padding(start = 4.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
            }
            Text(
                "${sizeText(app.size)} · .$extension · v${app.version}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (app.split || app.system)
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (app.split) Tag(stringResource(R.string.tag_split))
                    if (app.system)
                        Tag(
                            stringResource(R.string.tag_system),
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    stringResource(R.string.actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu { open = false }
            }
        }
        FilledTonalIconButton(onClick = onExtract, enabled = extractEnabled) {
            Icon(PackIcons.Download, stringResource(R.string.extract))
        }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun AppMenuItems(
    app: AppRecord,
    favorite: Boolean,
    close: () -> Unit,
    quick: () -> Unit,
    full: () -> Unit,
    favoriteToggle: () -> Unit,
    saveIcon: () -> Unit,
    copy: () -> Unit,
) {
    val c = LocalContext.current
    @Composable
    fun item(text: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) =
        DropdownMenuItem(
            text = { Text(stringResource(text)) },
            leadingIcon = { Icon(icon, null) },
            onClick = {
                close()
                action()
            },
        )
    item(R.string.quick_details, Icons.Rounded.Info, quick)
    item(R.string.full_details, PackIcons.Policy, full)
    HorizontalDivider()
    if (c.packageManager.getLaunchIntentForPackage(app.packageName) != null)
        item(R.string.open_app, PackIcons.OpenInNew) { launchApp(c, app.packageName) }
    item(R.string.system_info, Icons.Rounded.Settings) { openSystemInfo(c, app.packageName) }
    item(R.string.open_store, Icons.Rounded.ShoppingCart) { openStore(c, app.packageName) }
    item(R.string.save_icon, PackIcons.Image, saveIcon)
    item(R.string.copy_package, Icons.Rounded.Edit, copy)
    item(
        if (favorite) R.string.unfavorite else R.string.favorite,
        if (favorite) Icons.Rounded.Star else PackIcons.StarOutline,
        favoriteToggle,
    )
}
