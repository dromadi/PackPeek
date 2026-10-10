package com.packpeek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.model.AppRecord

@Composable
fun DashboardScreen(vm: MainViewModel, inspect: (AppRecord) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val tech by vm.technologies.collectAsStateWithLifecycle()
    val scores by vm.riskScores.collectAsStateWithLifecycle()
    val checking by vm.checkingOverview.collectAsStateWithLifecycle()
    val active by vm.activeServices.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refreshDeviceState()
        onPauseOrDispose {}
    }
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val source = remember(apps) { apps.groupingBy { it.source }.eachCount().toList().sortedByDescending { it.second } }
    val sdk =
        remember(apps) {
            apps.groupingBy { it.targetSdk }.eachCount().toSortedMap().toList().reversed()
        }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    PackIcons.Apps,
                    stringResource(R.string.apps),
                    apps.size.toString(),
                    Modifier.weight(1f),
                )
                StatTile(
                    PackIcons.Inventory2,
                    stringResource(R.string.apk_storage),
                    sizeText(apps.sumOf { it.size }),
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    PackIcons.Android,
                    stringResource(R.string.user_apps),
                    apps.count { !it.system }.toString(),
                    Modifier.weight(1f),
                )
                StatTile(
                    PackIcons.Layers,
                    stringResource(R.string.split_apps),
                    apps.count { it.split }.toString(),
                    Modifier.weight(1f),
                )
            }
        }
        active?.let { services ->
            item {
                RowGroup(title = stringResource(R.string.active_on_device)) {
                    Text(
                        stringResource(R.string.active_on_device_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    listOf(
                            R.string.active_accessibility to services.accessibility,
                            R.string.active_notifications to services.notificationListeners,
                            R.string.active_admins to services.deviceAdmins,
                            R.string.active_default_sms to setOfNotNull(services.defaultSms),
                        )
                        .forEach { (title, packages) ->
                            GroupDivider()
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                            )
                            if (packages.isEmpty())
                                Text(
                                    stringResource(R.string.active_none),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp),
                                )
                            packages.sorted().forEach { pkg ->
                                val app = byPackage[pkg]
                                if (app != null) AppLine(app, null, { inspect(app) })
                                else
                                    Text(
                                        pkg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                            }
                        }
                }
            }
        }
        item {
            RowGroup(title = stringResource(R.string.source)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    source.forEach { (key, count) -> CountBar(sourceName(key), count, apps.size) }
                }
            }
        }
        item {
            RowGroup(title = stringResource(R.string.target_sdk)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    sdk.forEach { (key, count) -> CountBar("API $key", count, apps.size) }
                }
            }
        }
        item {
            RowGroup(title = stringResource(R.string.largest_apps)) {
                apps.sortedByDescending { it.size }.take(10).forEachIndexed { index, app ->
                    if (index > 0) GroupDivider()
                    AppLine(app, sizeText(app.size), { inspect(app) })
                }
            }
        }
        item {
            RowGroup(title = stringResource(R.string.technology_and_risk)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.overview_scan_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (checking)
                            OutlinedButton(onClick = vm::cancelOverview) {
                                Text(stringResource(R.string.cancel_scan))
                            }
                        else
                            Button(onClick = vm::scanOverview, enabled = apps.isNotEmpty()) {
                                Icon(PackIcons.Policy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.scan_overview))
                            }
                        Text(
                            stringResource(R.string.scan_coverage, scores.size, apps.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    if (checking)
                        LinearProgressIndicator(
                            progress = {
                                if (apps.isEmpty()) 0f else scores.size.toFloat() / apps.size
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                }
                val frameworks = tech.values.flatten().groupingBy { it }.eachCount().toList()
                if (frameworks.isNotEmpty()) {
                    GroupDivider()
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        frameworks.sortedByDescending { it.second }.forEach { (framework, count) ->
                            CountBar(framework, count, tech.size)
                        }
                    }
                }
            }
        }
        val risky = apps.filter { it.packageName in scores }.sortedByDescending { scores[it.packageName] }.take(10)
        if (risky.isNotEmpty())
            item {
                RowGroup(title = stringResource(R.string.review_title)) {
                    risky.forEachIndexed { index, app ->
                        if (index > 0) GroupDivider()
                        val score = scores[app.packageName] ?: 0
                        AppLine(app, null, { inspect(app) }) {
                            val (container, content) = scoreColors(score)
                            Surface(color = container, shape = MaterialTheme.shapes.small) {
                                Text(
                                    score.toString(),
                                    color = content,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.audit_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AppLine(
    app: AppRecord,
    value: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, Modifier.size(36.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (value != null)
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        trailing?.invoke()
    }
}

/** Rounded bar without the end dot of Material's progress indicator. */
@Composable
private fun CountBar(label: String, count: Int, total: Int) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(count.toString(), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                Modifier.fillMaxWidth(if (total == 0) 0f else count.toFloat() / total)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
