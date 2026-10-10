@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.packpeek.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.engine.HashLookup
import com.packpeek.app.engine.VirusTotal
import com.packpeek.app.engine.risk.*
import com.packpeek.app.model.AppRecord

/** Whether details should offer the deep analysis: code evidence, unread code or a strong pattern. */
fun offersDeepAnalysis(report: RiskReport): Boolean =
    report.coverage.code != null &&
        (report.count(Severity.STRONG) > 0 ||
            report.coverage.code.gaps.isNotEmpty() ||
            report.findings.any { f ->
                f.evidence.any { (it.level == EvidenceLevel.CODE || it.level == EvidenceLevel.FLOW) && it.library == null }
            })

@Composable
fun DeepScreen(vm: MainViewModel, openSettings: () -> Unit) {
    val state by vm.deep.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.analyzeDeep() }
    val app = state.app ?: return
    DeepContent(state, app, vm::lookUpHash, openSettings)
}

/** Stateless body, also rendered by the JVM screen tests. */
@Composable
fun DeepContent(state: DeepState, app: AppRecord, lookUp: () -> Unit, openSettings: () -> Unit) {
    val report = state.report
    val deep = state.code?.deep
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DeepHeader(app, state) }
        if (report == null || deep == null) return@LazyColumn
        item {
            RiskSummary(report)
        }
        // The full report section sits at the bottom of a long screen; offer it up front too.
        item { QuickReport(app, report, state) }
        items(report.findings.filter { it.severity == Severity.STRONG }, key = { "deep:${it.id}" }) {
            FindingCard(it, codeGaps = report.coverage.code?.gaps?.isNotEmpty() == true)
        }
        item { ChainsSection(deep.chains) }
        item { DecodedSection(deep.decoded) }
        item { PayloadsSection(deep.payloads) }
        if (deep.listings.isNotEmpty()) item { CodeSection(deep.listings) }
        item { ActionsSection(app, report) }
        item { ReportSection(app, report, state) }
        item { LookupSection(deep.sha256, state.lookup, lookUp, openSettings) }
    }
}

@Composable
private fun DeepHeader(app: AppRecord, state: DeepState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val deep = state.code?.deep
            Text(
                when {
                    state.running -> stringResource(R.string.deep_running)
                    state.failed || deep == null -> stringResource(R.string.deep_failed)
                    else ->
                        stringResource(
                            R.string.deep_done,
                            ((deep.millis + 999) / 1000).toInt(),
                            deep.chains.size,
                            deep.decoded.size,
                            deep.payloads.size,
                        )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
}

@Composable
private fun Section(title: String, note: String, content: @Composable ColumnScope.() -> Unit) {
    RowGroup(title = title) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Empty(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ChainsSection(chains: List<CodeChain>) {
    Section(stringResource(R.string.section_chains), stringResource(R.string.chains_note)) {
        if (chains.isEmpty()) Empty(stringResource(R.string.chains_none))
        chains.forEach { chain ->
            val (container, content) =
                severityColors(if (chain.sink in Capability.hiddenDestinations) Severity.STRONG else Severity.ATTENTION)
            Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LevelTag(EvidenceLevel.FLOW)
                        Text(
                            stringResource(R.string.chain_title, capabilityName(chain.source), capabilityName(chain.sink)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    // One path: the start, the steps to the reading code, then on to the sending code.
                    val steps = (chain.toSource + chain.toSink.drop(1)).distinctBy { it.method }
                    steps.forEachIndexed { index, step ->
                        Row(Modifier.padding(top = 8.dp)) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(20.dp),
                            )
                            Column {
                                Text(step.method, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text(
                                    stringResource(
                                        when (step.via) {
                                            StepVia.START -> R.string.via_start
                                            StepVia.CALL -> R.string.via_call
                                            StepVia.INTENT -> R.string.via_intent
                                            StepVia.OBJECT -> R.string.via_object
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.chain_read, chain.sourceApi) + "\n" + stringResource(R.string.chain_send, chain.sinkApi),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DecodedSection(decoded: List<DecodedText>) {
    val res = LocalResources.current
    Section(stringResource(R.string.section_decoded), stringResource(R.string.decoded_note)) {
        if (decoded.isEmpty()) Empty(stringResource(R.string.decoded_none))
        decoded.forEach { d ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelTag(EvidenceLevel.CODE)
                    Text(
                        EvidenceReport.decodingText(res, d) + (d.capability?.let { " · " + capabilityName(it) } ?: ""),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                SelectionContainer {
                    Text(d.decoded, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
                Text(d.method, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PayloadsSection(payloads: List<PayloadScan>) {
    val res = LocalResources.current
    Section(stringResource(R.string.section_payloads), stringResource(R.string.payloads_note)) {
        if (payloads.isEmpty()) Empty(stringResource(R.string.payloads_none))
        payloads.forEach { p ->
            Row {
                Icon(
                    if (p.kind == PayloadKind.ENCRYPTED) Icons.Rounded.Lock else Icons.Rounded.Warning,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.padding(start = 8.dp)) {
                    Text(p.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Text(EvidenceReport.payloadText(res, p), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CodeSection(listings: Map<String, List<String>>) {
    Section(stringResource(R.string.section_code), stringResource(R.string.code_note)) {
        listings.entries.sortedByDescending { (_, lines) -> lines.count { it.startsWith("⚠") } }.forEach { (method, lines) ->
            var open by rememberSaveable(method) { mutableStateOf(false) }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            method,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(PackIcons.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f))
                    }
                    if (open)
                        Column(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                            lines.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (line.startsWith("⚠")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    softWrap = false,
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ActionsSection(app: AppRecord, report: RiskReport) {
    val c = LocalContext.current
    val ids = report.findings.filter { it.severity != Severity.INFO }.map { it.id }.toSet()
    var secure by remember { mutableStateOf(false) }
    if (secure)
        AlertDialog(
            onDismissRequest = { secure = false },
            title = { Text(stringResource(R.string.action_secure_accounts)) },
            text = { Text(stringResource(R.string.secure_accounts_body)) },
            confirmButton = { TextButton(onClick = { secure = false }) { Text(stringResource(R.string.close)) } },
        )
    val accounts = ids.any { it in setOf(RiskId.OTP_THEFT, RiskId.DATA_THEFT, RiskId.SCREEN_SPYING, RiskId.CALL_FORWARDING) }
    RowGroup(title = stringResource(R.string.section_actions)) {
        if (app.fromFile)
            ActionRow(stringResource(R.string.action_dont_install), icon = Icons.Rounded.Warning)
        else {
            ActionRow(stringResource(R.string.action_uninstall), icon = Icons.Rounded.Delete, onClick = {
                c.launchSafe(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            })
            GroupDivider()
            ActionRow(stringResource(R.string.action_app_info), icon = Icons.Rounded.Info, onClick = { openSystemInfo(c, app.packageName) })
            if (RiskId.ACCESSIBILITY in ids || RiskId.SCREEN_SPYING in ids) {
                GroupDivider()
                ActionRow(stringResource(R.string.action_accessibility), icon = Icons.Rounded.Settings, onClick = {
                    c.launchSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                })
            }
            if (RiskId.NOTIFICATIONS in ids) {
                GroupDivider()
                ActionRow(stringResource(R.string.action_notification_access), icon = Icons.Rounded.Notifications, onClick = {
                    c.launchSafe(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                })
            }
            if (RiskId.DEVICE_ADMIN in ids) {
                GroupDivider()
                ActionRow(stringResource(R.string.action_admin), icon = Icons.Rounded.Lock, onClick = {
                    c.launchSafe(Intent(Settings.ACTION_SECURITY_SETTINGS))
                })
            }
        }
        if (accounts) {
            GroupDivider()
            ActionRow(stringResource(R.string.action_call_forwarding), icon = Icons.Rounded.Call, onClick = {
                c.launchSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#21#"))))
            })
            GroupDivider()
            ActionRow(stringResource(R.string.action_secure_accounts), icon = Icons.Rounded.Lock, onClick = { secure = true })
        }
    }
}

@Composable
private fun QuickReport(app: AppRecord, report: RiskReport, state: DeepState) {
    val c = LocalContext.current
    val res = LocalResources.current
    FilledTonalButton(
        onClick = { EvidenceReport.sharePdf(c, EvidenceReport.text(res, app, report, state.code, state.lookup), app.packageName) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.report_share_quick))
    }
}

@Composable
private fun ReportSection(app: AppRecord, report: RiskReport, state: DeepState) {
    val c = LocalContext.current
    val res = LocalResources.current
    fun text() = EvidenceReport.text(res, app, report, state.code, state.lookup)
    Section(stringResource(R.string.section_report), stringResource(R.string.report_note)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { EvidenceReport.sharePdf(c, text(), app.packageName) }) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.report_share_pdf))
            }
            OutlinedButton(onClick = { EvidenceReport.shareText(c, text()) }) {
                Text(stringResource(R.string.report_share_text))
            }
        }
    }
}

@Composable
private fun LookupSection(sha256: String, lookup: HashLookup?, lookUp: () -> Unit, openSettings: () -> Unit) {
    val c = LocalContext.current
    val res = LocalResources.current
    Section(stringResource(R.string.section_lookup), stringResource(R.string.lookup_note)) {
        SelectionContainer {
            Text("SHA-256\n$sha256", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        if (lookup != null && lookup != HashLookup.Running) {
            val danger = lookup is HashLookup.Found && lookup.malicious > 0
            Surface(
                color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(EvidenceReport.lookupText(res, lookup), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
            }
            if (lookup == HashLookup.NoKey || (lookup is HashLookup.Failed && lookup.reason == HashLookup.Reason.KEY))
                TextButton(onClick = openSettings) { Text(stringResource(R.string.settings)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = lookUp, enabled = sha256.isNotBlank() && lookup != HashLookup.Running) {
                if (lookup == HashLookup.Running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(PackIcons.Policy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (lookup == HashLookup.Running) R.string.lookup_running else R.string.lookup_button))
            }
            OutlinedButton(onClick = { c.launchSafe(Intent(Intent.ACTION_VIEW, Uri.parse(VirusTotal.guiUrl(sha256)))) }, enabled = sha256.isNotBlank()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lookup_browser))
            }
            TextButton(onClick = { c.copyText(sha256) }) { Text(stringResource(R.string.copy_sha)) }
        }
    }
}
