package com.packpeek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.engine.risk.Severity
import com.packpeek.app.model.AndroidVersions
import com.packpeek.app.model.AppGroup
import com.packpeek.app.model.AppRecord
import kotlin.math.roundToInt

/** Ways to group apps in the analysis card. */
private enum class Dimension(val title: Int) {
    SOURCE(R.string.source),
    TARGET(R.string.target_android),
    MINIMUM(R.string.min_android),
    SIGNER(R.string.certificates),
    ABI(R.string.cpu_architecture),
    TECH(R.string.technology),
}

@Composable
fun DashboardScreen(vm: MainViewModel, inspect: (AppRecord) -> Unit, showGroup: (AppGroup) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val tech by vm.technologies.collectAsStateWithLifecycle()
    val briefs by vm.riskBriefs.collectAsStateWithLifecycle()
    val checking by vm.checkingOverview.collectAsStateWithLifecycle()
    val checkingApp by vm.checkingApp.collectAsStateWithLifecycle()
    val active by vm.activeServices.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refreshDeviceState()
        onPauseOrDispose {}
    }
    LaunchedEffect(apps) { if (apps.isNotEmpty()) vm.readExtras() }
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val userApps = remember(apps) { apps.filter { !it.system } }
    val review =
        remember(briefs, byPackage) {
            briefs
                .filter { (pkg, b) -> pkg in byPackage && (b.strong > 0 || b.attention > 0) }
                .toList()
                .sortedWith(compareByDescending<Pair<String, RiskBrief>> { it.second.strong }.thenByDescending { it.second.attention })
        }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SecurityCard(
                total = userApps.size,
                checked = userApps.count { it.packageName in briefs },
                strong = review.count { it.second.strong > 0 },
                attention = review.count { it.second.strong == 0 },
                checking = checking,
                current = checkingApp,
                start = { vm.scanOverview() },
                again = { vm.scanOverview(again = true) },
                cancel = vm::cancelOverview,
            )
        }
        if (review.isNotEmpty())
            item {
                var all by rememberSaveable { mutableStateOf(false) }
                RowGroup(title = stringResource(R.string.needs_review)) {
                    (if (all) review else review.take(5)).forEachIndexed { index, (pkg, brief) ->
                        if (index > 0) GroupDivider()
                        ReviewLine(byPackage.getValue(pkg), brief) { inspect(byPackage.getValue(pkg)) }
                    }
                    if (review.size > 5)
                        TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                if (all) stringResource(R.string.show_fewer)
                                else stringResource(R.string.show_all_groups, review.size)
                            )
                        }
                }
            }
        active?.let { services ->
            item { ActiveSection(services, byPackage, inspect) }
        }
        item { StatsRow(apps) }
        item {
            AnalysisCard(apps, extras, tech, showGroup)
        }
        item {
            var all by rememberSaveable { mutableStateOf(false) }
            val largest = remember(apps) { apps.sortedByDescending { it.size }.take(10) }
            RowGroup(title = stringResource(R.string.largest_apps)) {
                (if (all) largest else largest.take(5)).forEachIndexed { index, app ->
                    if (index > 0) GroupDivider()
                    AppLine(app, sizeText(app.size), { inspect(app) })
                }
                if (largest.size > 5)
                    TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            if (all) stringResource(R.string.show_fewer)
                            else stringResource(R.string.show_all_groups, largest.size)
                        )
                    }
            }
        }
    }
}

/** Top card: the evidence check of all user apps and its outcome. */
@Composable
private fun SecurityCard(
    total: Int,
    checked: Int,
    strong: Int,
    attention: Int,
    checking: Boolean,
    current: String?,
    start: () -> Unit,
    again: () -> Unit,
    cancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val done = checked > 0 && !checking
    val (container, content) =
        when {
            !done -> scheme.secondaryContainer to scheme.onSecondaryContainer
            strong > 0 -> scheme.errorContainer to scheme.onErrorContainer
            attention > 0 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
            else -> scheme.primaryContainer to scheme.onPrimaryContainer
        }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        done && strong > 0 -> PackIcons.GppMaybe
                        done && attention == 0 -> Icons.Rounded.CheckCircle
                        else -> PackIcons.Policy
                    },
                    null,
                    Modifier.size(32.dp),
                )
                Text(
                    when {
                        checking -> stringResource(R.string.security_checking, checked, total)
                        !done -> stringResource(R.string.security_check_title)
                        strong > 0 -> pluralStringResource(R.plurals.security_strong, strong, strong)
                        attention > 0 -> pluralStringResource(R.plurals.security_attention, attention, attention)
                        else -> stringResource(R.string.security_clear)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                when {
                    checking -> current.orEmpty()
                    !done -> stringResource(R.string.security_check_body)
                    else -> stringResource(R.string.security_checked, checked, total)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (checking) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (checking)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else checked.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = content,
                    trackColor = content.copy(alpha = 0.2f),
                )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    checking -> OutlinedButton(onClick = cancel) { Text(stringResource(R.string.cancel)) }
                    !done ->
                        Button(onClick = start, enabled = total > 0) {
                            Text(pluralStringResource(R.plurals.security_check_button, total, total))
                        }
                    checked < total -> {
                        Button(onClick = start) { Text(stringResource(R.string.security_continue)) }
                        TextButton(onClick = again) { Text(stringResource(R.string.security_again)) }
                    }
                    else -> OutlinedButton(onClick = again) { Text(stringResource(R.string.security_again)) }
                }
            }
        }
    }
}

@Composable
private fun ReviewLine(app: AppRecord, brief: RiskBrief, onClick: () -> Unit) {
    val top = brief.top
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (top != null)
                Text(
                    stringArrayResource(riskTexts(top.id))[0],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            if (top != null)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val severity = if (brief.strong > 0) Severity.STRONG else Severity.ATTENTION
                    val (container, content) = severityColors(severity)
                    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
                        Text(
                            stringResource(
                                if (severity == Severity.STRONG) R.string.severity_strong else R.string.severity_attention
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Tag(top.strongestLevel.tag, MaterialTheme.colorScheme.surfaceContainerHighest)
                }
        }
        Text(
            (brief.strong + brief.attention).toString(),
            style = MaterialTheme.typography.titleMedium,
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Sensitive services switched on right now; collapses to one line when there are none. */
@Composable
private fun ActiveSection(
    services: com.packpeek.app.engine.risk.ActiveServices,
    byPackage: Map<String, AppRecord>,
    inspect: (AppRecord) -> Unit,
) {
    val groups =
        listOf(
                R.string.active_accessibility to services.accessibility,
                R.string.active_notifications to services.notificationListeners,
                R.string.active_admins to services.deviceAdmins,
            )
            .filter { it.second.isNotEmpty() }
    RowGroup(title = stringResource(R.string.active_on_device)) {
        if (groups.isEmpty())
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.active_all_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        else {
            Text(
                stringResource(R.string.active_on_device_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            groups.forEach { (title, packages) ->
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp),
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
        services.defaultSms?.let { pkg ->
            GroupDivider()
            val app = byPackage[pkg]
            ActionRow(
                stringResource(R.string.active_default_sms),
                app?.label ?: pkg,
                onClick = app?.let { { inspect(it) } },
            )
        }
    }
}

@Composable
private fun StatsRow(apps: List<AppRecord>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            listOf(
                    apps.size.toString() to R.string.apps,
                    apps.count { !it.system }.toString() to R.string.user_apps,
                    apps.count { it.split }.toString() to R.string.split_apps,
                    sizeText(apps.sumOf { it.size }) to R.string.apk_storage,
                )
                .forEach { (value, label) ->
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            value,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
        }
    }
}

/** Part-to-whole of the apps along one dimension: one stacked bar plus a tappable legend. */
@Composable
private fun AnalysisCard(
    apps: List<AppRecord>,
    extras: Map<String, AppExtras>,
    tech: Map<String, List<String>>,
    showGroup: (AppGroup) -> Unit,
) {
    var dimension by rememberSaveable { mutableStateOf(Dimension.SOURCE) }
    var expanded by rememberSaveable(dimension) { mutableStateOf(false) }
    val unknown = stringResource(R.string.unknown)
    val noNative = stringResource(R.string.no_native_code)
    val sourceNames =
        mapOf(
            "PLAY" to sourceName("PLAY"),
            "SIDE" to sourceName("SIDE"),
            "OTHER" to sourceName("OTHER"),
            "SYSTEM" to sourceName("SYSTEM"),
        )
    val read = apps.filter { it.packageName in extras }
    val (entries, total) =
        when (dimension) {
            Dimension.SOURCE -> groups(apps) { listOf(sourceNames[it.source] ?: it.source) } to apps.size
            Dimension.TARGET -> groups(apps) { listOf(AndroidVersions.label(it.targetSdk)) } to apps.size
            Dimension.MINIMUM -> groups(apps) { listOf(AndroidVersions.label(it.minSdk)) } to apps.size
            Dimension.SIGNER ->
                groups(read) {
                    listOf(extras[it.packageName]?.signer?.let { s -> "${s.name} · ${s.digest.take(8).uppercase()}" } ?: unknown)
                } to read.size
            Dimension.ABI ->
                groups(read) { extras[it.packageName]?.abis?.toList()?.ifEmpty { listOf(noNative) } ?: emptyList() } to read.size
            Dimension.TECH ->
                groups(apps.filter { it.packageName in tech }) { tech[it.packageName].orEmpty() } to tech.size
        }
    val colors = seriesColors()
    val shown = entries.take(colors.size)
    val rest = entries.drop(colors.size)
    val other = MaterialTheme.colorScheme.outline
    val title = stringResource(dimension.title)
    RowGroup(title = stringResource(R.string.app_analysis)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dimension.entries.forEach {
                FilterChip(
                    selected = it == dimension,
                    onClick = { dimension = it },
                    label = { Text(stringResource(it.title)) },
                )
            }
        }
        if (entries.isEmpty())
            Text(
                stringResource(if (dimension == Dimension.TECH) R.string.tech_after_check else R.string.reading_groups),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        else {
            StackedBar(
                shown.map { it.second.size.toFloat() } + listOfNotNull(rest.sumOf { it.second.size }.takeIf { it > 0 }?.toFloat()),
                colors.take(shown.size) + listOfNotNull(other.takeIf { rest.isNotEmpty() }),
                Modifier.padding(horizontal = 16.dp),
            )
            if (dimension == Dimension.SIGNER || dimension == Dimension.ABI)
                Text(
                    stringResource(if (dimension == Dimension.SIGNER) R.string.signer_note else R.string.abi_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
            Column(Modifier.padding(vertical = 6.dp)) {
                shown.forEachIndexed { i, (label, packages) ->
                    LegendRow(colors[i], label, packages.size, total) {
                        showGroup(AppGroup("$title: $label", packages))
                    }
                }
                if (rest.isNotEmpty()) {
                    if (expanded)
                        rest.forEach { (label, packages) ->
                            LegendRow(other, label, packages.size, total) {
                                showGroup(AppGroup("$title: $label", packages))
                            }
                        }
                    else
                        LegendRow(
                            other,
                            pluralStringResource(R.plurals.other_groups, rest.size, rest.size),
                            rest.flatMap { it.second }.toSet().size,
                            total,
                        ) {
                            expanded = true
                        }
                }
            }
        }
    }
}

/** Categorical slots from the validated reference palette, stepped for light or dark. */
@Composable
private fun seriesColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.run { 0.299f * red + 0.587f * green + 0.114f * blue } < 0.5f
    return if (dark)
        listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181))
    else listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4))
}

/** One 100% bar; segments are separated by a 2dp surface gap. */
@Composable
private fun StackedBar(values: List<Float>, colors: List<Color>, modifier: Modifier = Modifier) {
    val sum = values.sum().takeIf { it > 0f } ?: return
    Row(modifier.fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.small)) {
        values.forEachIndexed { i, v ->
            if (i > 0) Spacer(Modifier.width(2.dp))
            Box(Modifier.weight((v / sum).coerceAtLeast(0.004f)).fillMaxHeight().background(colors[i]))
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, count: Int, total: Int, onClick: () -> Unit) {
    val percent = if (total == 0) 0 else (count * 100f / total).roundToInt()
    val share = if (percent == 0 && count > 0) "<1%" else "$percent%"
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "$label, $count, $share" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Text(share, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp).widthIn(min = 24.dp),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Apps grouped by one or more labels each, largest group first. */
private fun groups(apps: List<AppRecord>, keys: (AppRecord) -> List<String>): List<Pair<String, Set<String>>> {
    val result = LinkedHashMap<String, MutableSet<String>>()
    apps.forEach { app -> keys(app).forEach { result.getOrPut(it) { mutableSetOf() } += app.packageName } }
    return result.map { it.key to it.value.toSet() }.sortedByDescending { it.second.size }
}

@Composable
private fun AppLine(
    app: AppRecord,
    value: String?,
    onClick: () -> Unit,
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
    }
}
