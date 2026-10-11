@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.packpeek.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.engine.risk.Severity
import com.packpeek.app.model.*
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailScreen(vm: MainViewModel, backup: (List<AppRecord>) -> Unit, deepAnalysis: () -> Unit) {
    val state by vm.detail.collectAsStateWithLifecycle()
    val progress by vm.analysisProgress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val app = state.app ?: return
    val c = LocalContext.current
    val resources = LocalResources.current
    val report = state.report
    val latest by vm.latestJob.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val summary by vm.importSummary.collectAsStateWithLifecycle()
    val parts by vm.splitParts.collectAsStateWithLifecycle()
    val chosen by vm.splitSelection.collectAsStateWithLifecycle()
    var tab by remember(app.basePath) { mutableIntStateOf(vm.requestedTab.also { vm.requestedTab = 0 }) }
    var menu by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    if (confirmInstall) {
        val strong = state.risk?.findings.orEmpty().filter { it.severity == Severity.STRONG }
        val titles = strong.map { "• " + resources.getStringArray(riskTexts(it.id))[0] }
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            icon = { Icon(PackIcons.GppMaybe, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.install_risk_title)) },
            text = { Text(stringResource(R.string.install_risk_body, titles.joinToString("\n"))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmInstall = false
                        vm.install()
                    }
                ) {
                    Text(stringResource(R.string.install_anyway), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Button(onClick = { confirmInstall = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val collapse = remember { CollapseState() }
    Column(Modifier.fillMaxSize().nestedScroll(collapse.connection)) {
        CollapsingHeader(collapse) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${app.version} · ${sizeText(app.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                    )
                    Tag("API ${app.targetSdk}")
                }
            }
            val risk = state.risk
            if (risk != null) RiskBadge(risk) else if (report != null) ScoreBadge(report.score)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!app.fromFile)
                Button(
                    onClick = { backup(listOf(app)) },
                    enabled = latest?.state !in listOf("QUEUED", "RUNNING"),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(PackIcons.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.extract))
                }
            else
                Button(
                    enabled = !installing && report != null && app.minSdk <= Build.VERSION.SDK_INT,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (
                            Build.VERSION.SDK_INT >= 26 &&
                                !c.packageManager.canRequestPackageInstalls()
                        )
                            c.launchSafe(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${c.packageName}"),
                                )
                            )
                        else if ((state.risk?.count(Severity.STRONG) ?: 0) > 0) confirmInstall = true
                        else vm.install()
                    },
                ) {
                    Icon(PackIcons.InstallMobile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.install_backup))
                }
            FilledTonalIconButton(onClick = { vm.saveIcon(app) }) {
                Icon(PackIcons.Image, stringResource(R.string.save_icon))
            }
            Box {
                FilledTonalIconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!app.fromFile) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_app)) },
                            leadingIcon = { Icon(PackIcons.OpenInNew, null) },
                            onClick = {
                                menu = false
                                c.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                                    c.launchSafe(it)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_info)) },
                            leadingIcon = { Icon(Icons.Rounded.Info, null) },
                            onClick = {
                                menu = false
                                c.launchSafe(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${app.packageName}"),
                                    )
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.uninstall)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = {
                                menu = false
                                c.launchSafe(
                                    Intent(
                                        Intent.ACTION_DELETE,
                                        Uri.parse("package:${app.packageName}"),
                                    )
                                )
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_store)) },
                        leadingIcon = { Icon(Icons.Rounded.ShoppingCart, null) },
                        onClick = {
                            menu = false
                            c.launchSafe(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        "https://play.google.com/store/apps/details?id=${app.packageName}"
                                    ),
                                )
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_package)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            menu = false
                            c.copyText(app.packageName)
                        },
                    )
                }
            }
        }
        }
        val tabs =
            listOf(
                R.string.overview,
                R.string.permissions,
                R.string.components,
                R.string.certificates,
                R.string.technology,
            )
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 16.dp,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            val counts =
                mapOf(
                    R.string.permissions to report?.permissions?.size,
                    R.string.components to report?.components?.size,
                )
            tabs.forEachIndexed { i, title ->
                val count = counts[title]
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            if (count == null) stringResource(title)
                            else stringResource(R.string.tab_count, stringResource(title), count)
                        )
                    },
                )
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { failure ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(errorText(failure)),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                TextButton(onClick = { vm.inspect(app) }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        if (state.risk == null && state.loading && state.error == null) item { AnalysisProgress(progress) }
                        state.risk?.let { risk ->
                            item {
                                SectionTitle(stringResource(R.string.concerns))
                                RiskSummary(risk)
                            }
                            if (offersDeepAnalysis(risk))
                                item { DeepAnalysisButton(deepAnalysis) }
                            items(risk.findings, key = { "risk:${it.id}" }) {
                                FindingCard(it, codeGaps = risk.coverage.code?.gaps?.isNotEmpty() == true)
                            }
                            item { CoverageCard(risk.coverage) { openUsageAccess(c) } }
                        }
                        state.signerChangedSince?.let { previous ->
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.large,
                                ) {
                                    Row(Modifier.padding(16.dp)) {
                                        Icon(
                                            PackIcons.GppMaybe,
                                            null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Column(Modifier.padding(start = 12.dp)) {
                                            Text(
                                                stringResource(R.string.signer_changed_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.signer_changed_body,
                                                    previous.version,
                                                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                                                        .format(Date(previous.created)),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val obb = summary?.second?.takeIf { app.fromFile } ?: 0
                        if (obb > 0)
                            item {
                                Text(
                                    stringResource(R.string.obb_notice, obb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        if (report != null && app.fromFile)
                            item { ReadinessCard(app, report) }
                        if (app.fromFile && parts.isNotEmpty())
                            item { SplitCard(parts, chosen, vm::toggleSplit, vm::resetSplits) }
                        item {
                            RowGroup(title = stringResource(R.string.app_info)) {
                                InfoLine(
                                    stringResource(R.string.package_name),
                                    app.packageName,
                                    onClick = { c.copyText(app.packageName) },
                                )
                                GroupDivider()
                                InfoLine(
                                    stringResource(R.string.version),
                                    "${app.version} (${app.versionCode})",
                                )
                                GroupDivider()
                                InfoLine(
                                    stringResource(R.string.min_android),
                                    AndroidVersions.label(app.minSdk),
                                )
                                GroupDivider()
                                InfoLine(
                                    stringResource(R.string.target_android),
                                    AndroidVersions.label(app.targetSdk),
                                )
                                GroupDivider()
                                InfoLine(
                                    stringResource(R.string.archive_format),
                                    (summary?.first?.takeIf { app.fromFile }?.name
                                        ?: ".${FileNames.extension(app, settings?.naming ?: NamingConfig())}") +
                                        " · " +
                                        "${app.paths.size} APK · ${sizeText(app.size)}",
                                )
                                GroupDivider()
                                InfoLine(
                                    stringResource(R.string.source),
                                    if (app.fromFile) stringResource(R.string.local_file)
                                    else sourceName(app.source),
                                )
                                if (!app.fromFile) {
                                    GroupDivider()
                                    val installerName =
                                        remember(app.installer) {
                                            app.installer.takeIf { it.isNotBlank() }?.let {
                                                vm.owner.apps.label(it)
                                            }
                                        }
                                    InfoLine(
                                        stringResource(R.string.installer),
                                        when {
                                            installerName == null -> resources.getString(R.string.unknown)
                                            installerName == app.installer -> app.installer
                                            else ->
                                                stringResource(
                                                    R.string.installer_value,
                                                    installerName,
                                                    app.installer,
                                                )
                                        },
                                    )
                                    GroupDivider()
                                    InfoLine(
                                        stringResource(R.string.first_installed),
                                        DateFormat.getDateTimeInstance(
                                                DateFormat.MEDIUM,
                                                DateFormat.SHORT,
                                            )
                                            .format(Date(app.installed)),
                                    )
                                    GroupDivider()
                                    InfoLine(
                                        stringResource(R.string.last_update),
                                        DateFormat.getDateTimeInstance(
                                                DateFormat.MEDIUM,
                                                DateFormat.SHORT,
                                            )
                                            .format(Date(app.updated)),
                                    )
                                    GroupDivider()
                                    InfoLine("UID", app.uid.toString())
                                }
                            }
                        }
                        if (report != null)
                            item {
                                RowGroup(
                                    title =
                                        "${stringResource(R.string.old_score_title)} · ${report.score}/100"
                                ) {
                                    Text(
                                        stringResource(R.string.audit_disclaimer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                    if (report.findings.isEmpty())
                                        ActionRow(
                                            stringResource(R.string.no_findings),
                                            icon = Icons.Rounded.CheckCircle,
                                        )
                                    report.findings.forEach { f ->
                                        GroupDivider()
                                        ActionRow(
                                            stringResource(findingTitle(f.code)),
                                            stringResource(findingBody(f.code), f.count),
                                            Icons.Rounded.Warning,
                                        )
                                    }
                                }
                            }
                    }
                    1 -> {
                        item {
                            Text(
                                stringResource(R.string.permission_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (report?.permissions?.isEmpty() == true)
                            item { EmptyState(PackIcons.Shield, stringResource(R.string.no_permissions)) }
                        val permissions =
                            report?.permissions.orEmpty().sortedWith(
                                compareBy({ it.category != "DANGEROUS" }, { it.name })
                            )
                        if (permissions.isNotEmpty())
                            item {
                                RowGroup {
                                    permissions.forEachIndexed { index, p ->
                                        if (index > 0) GroupDivider()
                                        PermissionLine(p)
                                    }
                                }
                            }
                    }
                    2 -> {
                        item {
                            Text(
                                stringResource(R.string.component_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val components = report?.components.orEmpty()
                        if (components.isNotEmpty())
                            item {
                                RowGroup {
                                    components.forEachIndexed { index, p ->
                                        if (index > 0) GroupDivider()
                                        ComponentLine(p)
                                    }
                                }
                            }
                    }
                    3 ->
                        if (report != null) {
                            item {
                                RowGroup(title = stringResource(R.string.signature_verification)) {
                                    InfoLine(
                                        stringResource(R.string.signature_verification),
                                        stringResource(
                                            when (report.signatureValid) {
                                                true -> R.string.signature_valid
                                                false -> R.string.signature_invalid
                                                else -> R.string.unknown
                                            }
                                        ),
                                    )
                                    GroupDivider()
                                    InfoLine(
                                        stringResource(R.string.signature_schemes),
                                        report.schemes.joinToString().ifBlank {
                                            resources.getString(R.string.unknown)
                                        },
                                    )
                                    if (report.signerMatch != null) {
                                        GroupDivider()
                                        InfoLine(
                                            stringResource(R.string.installed_signer),
                                            stringResource(
                                                if (report.signerMatch) R.string.signer_matches
                                                else R.string.signer_differs
                                            ),
                                        )
                                    }
                                    Text(
                                        stringResource(R.string.signature_scope),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                            items(report.certificates) { cert ->
                                RowGroup(title = stringResource(R.string.certificates)) {
                                    InfoLine(stringResource(R.string.subject), cert.subject)
                                    GroupDivider()
                                    InfoLine(stringResource(R.string.issuer), cert.issuer)
                                    GroupDivider()
                                    InfoLine(
                                        stringResource(R.string.validity),
                                        "${cert.validFrom} – ${cert.validTo}\n${stringResource(if (cert.expired) R.string.cert_expired else R.string.cert_current)}",
                                    )
                                    GroupDivider()
                                    InfoLine(stringResource(R.string.algorithm), cert.algorithm)
                                    GroupDivider()
                                    InfoLine(stringResource(R.string.serial), cert.serial)
                                    GroupDivider()
                                    InfoLine("SHA-256", cert.sha256, { c.copyText(cert.sha256) })
                                    GroupDivider()
                                    InfoLine("SHA-1", cert.sha1, { c.copyText(cert.sha1) })
                                    GroupDivider()
                                    InfoLine("MD5", cert.md5, { c.copyText(cert.md5) })
                                }
                            }
                        }
                    4 ->
                        if (report != null) {
                            item {
                                RowGroup {
                                    InfoLine(
                                        stringResource(R.string.framework),
                                        report.frameworks.joinToString().ifBlank {
                                            resources.getString(R.string.android_native)
                                        },
                                    )
                                    GroupDivider()
                                    InfoLine(
                                        stringResource(R.string.architectures),
                                        report.abis.joinToString().ifBlank {
                                            resources.getString(R.string.no_native_libs)
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.tech_disclaimer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                            if (report.libraries.isNotEmpty())
                                item {
                                    RowGroup(
                                        title =
                                            stringResource(
                                                R.string.native_libraries,
                                                report.libraries.size,
                                            )
                                    ) {
                                        report.libraries.forEachIndexed { index, lib ->
                                            if (index > 0) GroupDivider()
                                            Text(
                                                lib,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier =
                                                    Modifier.padding(
                                                        horizontal = 16.dp,
                                                        vertical = 10.dp,
                                                    ),
                                            )
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
private fun ScoreBadge(score: Int) {
    val (container, content) = scoreColors(score)
    Surface(color = container, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(score.toString(), style = MaterialTheme.typography.titleLarge, color = content)
            Text(
                stringResource(R.string.score_short),
                style = MaterialTheme.typography.labelSmall,
                color = content,
            )
        }
    }
}

@Composable
private fun PermissionLine(p: PermissionRow) {
    val resources = LocalResources.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            p.name.substringAfterLast('.'),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            p.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(
                resources.getString(
                    when (p.category) {
                        "DANGEROUS" -> R.string.dangerous_permission
                        "SIGNATURE" -> R.string.signature_permission
                        else -> R.string.normal_permission
                    }
                ),
                if (p.category == "DANGEROUS") MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            )
            if (p.granted != null)
                Tag(
                    resources.getString(if (p.granted) R.string.granted else R.string.not_granted),
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                )
        }
        if (p.description.isNotBlank())
            Text(
                p.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
    }
}

@Composable
private fun ComponentLine(p: ComponentRow) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(p.name.substringAfterLast('.'), style = MaterialTheme.typography.bodyLarge)
        Text(
            p.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(p.kind.lowercase().replaceFirstChar { it.uppercase() })
            Tag(
                stringResource(if (p.exported) R.string.exported else R.string.internal),
                if (p.exported && p.permission.isBlank()) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (!p.enabled)
                Tag(
                    stringResource(R.string.disabled),
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                )
        }
        if (p.permission.isNotBlank())
            Text(
                "${stringResource(R.string.permission)}: ${p.permission}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
    }
}

@Composable
private fun SplitCard(
    parts: List<SplitPart>,
    selected: Set<String>,
    toggle: (String) -> Unit,
    reset: () -> Unit,
) {
    val kept = remember(parts, selected) { SplitPlanner.resolve(parts, selected) }
    val missingAbi = remember(parts, selected) { SplitPlanner.missingAbi(parts, selected) }
    OutlinedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.split_section), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.split_summary,
                    kept.size,
                    parts.size,
                    sizeText(parts.filter { it.name in kept }.sumOf { it.bytes }),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.split_hint), style = MaterialTheme.typography.bodySmall)
            if (missingAbi)
                Text(
                    stringResource(R.string.split_missing_abi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            SplitKind.entries.forEach { kind ->
                val group = parts.filter { it.kind == kind }
                if (group.isEmpty()) return@forEach
                Text(
                    stringResource(splitKindTitle(kind)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                group.forEach { part ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = part.name in kept,
                            onCheckedChange = { toggle(part.name) },
                            // Config parts of a feature follow that feature's checkbox.
                            enabled =
                                kind != SplitKind.BASE &&
                                    (kind == SplitKind.FEATURE ||
                                        part.module.isEmpty() ||
                                        parts.any {
                                            it.kind == SplitKind.FEATURE &&
                                                it.module == part.module &&
                                                it.name in kept
                                        }),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    kind == SplitKind.BASE -> "base.apk"
                                    part.module.isEmpty() -> part.value.ifBlank { part.name }
                                    part.value.isBlank() -> part.module
                                    else -> "${part.value} · ${part.module}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                buildString {
                                    append(sizeText(part.bytes))
                                    if (part.recommended && kind != SplitKind.BASE)
                                        append(" · ").append(stringResource(R.string.split_recommended))
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = reset) { Text(stringResource(R.string.split_reset)) }
        }
    }
}

private fun splitKindTitle(kind: SplitKind): Int =
    when (kind) {
        SplitKind.BASE -> R.string.split_kind_base
        SplitKind.ABI -> R.string.split_kind_abi
        SplitKind.DENSITY -> R.string.split_kind_density
        SplitKind.LOCALE -> R.string.split_kind_locale
        SplitKind.FEATURE -> R.string.split_kind_feature
        SplitKind.OTHER -> R.string.split_kind_other
    }

@Composable
private fun ReadinessCard(app: AppRecord, report: AuditReport) {
    val device = Build.SUPPORTED_ABIS.toList()
    val issues =
        remember(app, report) {
            readiness(
                ReadinessInput(
                    app.minSdk,
                    app.targetSdk,
                    Build.VERSION.SDK_INT,
                    report.abis,
                    device,
                    report.signatureValid,
                    report.signerMatch,
                    app.versionCode,
                    report.installedVersionCode,
                    report.missingFeatures,
                )
            )
        }
    val ok = issues.isEmpty()
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (ok) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
            )
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    !ok -> stringResource(R.string.ready_warning)
                    report.installedVersionCode == null -> stringResource(R.string.ready_new)
                    else ->
                        stringResource(
                            R.string.ready_update,
                            report.installedVersion.orEmpty(),
                            app.version,
                        )
                },
                style = MaterialTheme.typography.titleLarge,
            )
            issues.forEach { issue ->
                Text(
                    when (issue) {
                        ReadinessIssue.SIGNATURE_INVALID ->
                            stringResource(R.string.ready_signature_invalid)
                        ReadinessIssue.SDK ->
                            stringResource(R.string.ready_sdk, app.minSdk, Build.VERSION.SDK_INT)
                        ReadinessIssue.TARGET ->
                            stringResource(
                                R.string.ready_target_min,
                                app.targetSdk,
                                InstallErrors.minTargetSdk(Build.VERSION.SDK_INT) ?: 0,
                            )
                        ReadinessIssue.ABI ->
                            stringResource(
                                R.string.ready_abi,
                                report.abis.joinToString(),
                                device.joinToString(),
                            )
                        ReadinessIssue.FEATURES ->
                            stringResource(
                                R.string.ready_features,
                                report.missingFeatures.joinToString(),
                            )
                        ReadinessIssue.SIGNER -> stringResource(R.string.ready_signer)
                        ReadinessIssue.DOWNGRADE ->
                            stringResource(
                                R.string.ready_downgrade,
                                app.versionCode,
                                report.installedVersionCode ?: 0L,
                            )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.ready_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun sourceName(source: String): String =
    stringResource(
        when (source) {
            "PLAY" -> R.string.play_store
            "SYSTEM" -> R.string.system_apps
            "SIDE" -> R.string.unknown_source
            else -> R.string.other_store
        }
    )

fun findingTitle(code: FindingCode): Int =
    when (code) {
        FindingCode.DEBUGGABLE -> R.string.find_debug
        FindingCode.BACKUP -> R.string.find_backup
        FindingCode.CLEARTEXT -> R.string.find_cleartext
        FindingCode.OLD_TARGET -> R.string.find_target
        FindingCode.EXPORTED -> R.string.find_exported
        FindingCode.DANGEROUS -> R.string.find_permissions
        FindingCode.SIDELOAD -> R.string.find_source
        FindingCode.DEBUG_CERT -> R.string.find_debug_cert
    }

fun findingBody(code: FindingCode): Int =
    when (code) {
        FindingCode.DEBUGGABLE -> R.string.find_debug_body
        FindingCode.BACKUP -> R.string.find_backup_body
        FindingCode.CLEARTEXT -> R.string.find_cleartext_body
        FindingCode.OLD_TARGET -> R.string.find_target_body
        FindingCode.EXPORTED -> R.string.find_exported_body
        FindingCode.DANGEROUS -> R.string.find_permissions_body
        FindingCode.SIDELOAD -> R.string.find_source_body
        FindingCode.DEBUG_CERT -> R.string.find_debug_cert_body
    }

/** Header that folds away while the list scrolls up and returns once the list is back on top. */
private class CollapseState {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)

    val connection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0) return Offset.Zero
                val next = (offset + available.y).coerceIn(-height, 0f)
                val used = next - offset
                offset = next
                return Offset(0f, used)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0) return Offset.Zero
                val next = (offset + available.y).coerceIn(-height, 0f)
                val used = next - offset
                offset = next
                return Offset(0f, used)
            }

            // A fling that reaches the top of the list opens the header fully.
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y > 0) offset = 0f
                return Velocity.Zero
            }
        }
}

@Composable
private fun CollapsingHeader(state: CollapseState, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            state.height = placeable.height.toFloat()
            val visible = (placeable.height + state.offset).toInt().coerceAtLeast(0)
            layout(placeable.width, visible) { placeable.place(0, state.offset.toInt()) }
        },
        content = content,
    )
}

/** Entry to the deep analysis, offered when the code gave evidence or could not be read fully. */
@Composable
fun DeepAnalysisButton(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PackIcons.Policy, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.deep_button_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.deep_button_body), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}
