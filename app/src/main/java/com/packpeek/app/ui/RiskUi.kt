@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.packpeek.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.packpeek.app.R
import com.packpeek.app.engine.risk.*

/** Title, impact, context, not yet proven, action for each finding. */
fun riskTexts(id: RiskId): Int =
    when (id) {
        RiskId.OTP_THEFT -> R.array.risk_otp_theft
        RiskId.SCREEN_SPYING -> R.array.risk_screen_spying
        RiskId.ACCESSIBILITY -> R.array.risk_accessibility
        RiskId.NOTIFICATIONS -> R.array.risk_notifications
        RiskId.DEVICE_ADMIN -> R.array.risk_device_admin
        RiskId.SMS -> R.array.risk_sms
        RiskId.SMS_SEND -> R.array.risk_sms_send
        RiskId.CONTACTS_CALLS -> R.array.risk_contacts_calls
        RiskId.PHONE_IDENTITY -> R.array.risk_phone_identity
        RiskId.CAMERA_MIC -> R.array.risk_camera_mic
        RiskId.LOCATION -> R.array.risk_location
        RiskId.SCREEN_CAPTURE -> R.array.risk_screen_capture
        RiskId.OVERLAY -> R.array.risk_overlay
        RiskId.PERSISTENCE -> R.array.risk_persistence
        RiskId.HIDDEN_ICON -> R.array.risk_hidden_icon
        RiskId.INSTALL_APPS -> R.array.risk_install_apps
        RiskId.ALL_FILES -> R.array.risk_all_files
        RiskId.CLEARTEXT -> R.array.risk_cleartext
        RiskId.NETWORK_USAGE -> R.array.risk_network_usage
        RiskId.UNTRUSTED_SIGNER -> R.array.risk_untrusted_signer
        RiskId.DATA_THEFT -> R.array.risk_data_theft
        RiskId.HIDDEN_DESTINATION -> R.array.risk_hidden_destination
        RiskId.CALL_FORWARDING -> R.array.risk_call_forwarding
        RiskId.DYNAMIC_CODE -> R.array.risk_dynamic_code
        RiskId.SHELL_COMMAND -> R.array.risk_shell_command
        RiskId.CLIPBOARD -> R.array.risk_clipboard
        RiskId.WEB_LOGIN -> R.array.risk_web_login
        RiskId.AD_SDKS -> R.array.risk_ad_sdks
        RiskId.DESTINATIONS -> R.array.risk_destinations
    }

fun capabilityName(res: Resources, capability: Capability): String =
    res.getString(
        when (capability) {
            Capability.SMS_READ -> R.string.cap_sms_read
            Capability.CONTACTS -> R.string.cap_contacts
            Capability.CALL_LOG -> R.string.cap_call_log
            Capability.CLIPBOARD -> R.string.cap_clipboard
            Capability.NOTIFICATION_READ -> R.string.cap_notification_read
            Capability.SCREEN_READ -> R.string.cap_screen_read
            Capability.COOKIES -> R.string.cap_cookies
            Capability.PHONE_ID -> R.string.cap_phone_id
            Capability.ACCOUNTS -> R.string.cap_accounts
            Capability.LOCATION -> R.string.cap_location
            Capability.SMS_SEND -> R.string.cap_sms_send
            Capability.TELEGRAM -> R.string.cap_telegram
            Capability.DISCORD -> R.string.cap_discord
            else -> R.string.cap_network
        }
    )

@Composable
fun capabilityName(capability: Capability): String = capabilityName(LocalResources.current, capability)


fun strengthText(res: Resources, level: EvidenceLevel, traced: Boolean = false): String =
    res.getString(
        when (level) {
            EvidenceLevel.DECLARED -> R.string.strength_t1
            EvidenceLevel.CODE -> R.string.strength_t2
            EvidenceLevel.FLOW -> if (traced) R.string.strength_t3_traced else R.string.strength_t3
            EvidenceLevel.OBSERVED -> R.string.strength_t4
        }
    )

@Composable
fun strengthText(level: EvidenceLevel, traced: Boolean = false): String =
    strengthText(LocalResources.current, level, traced)


@Composable
fun severityColors(severity: Severity): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (severity) {
        Severity.STRONG -> scheme.errorContainer to scheme.onErrorContainer
        Severity.ATTENTION -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Severity.INFO -> scheme.surfaceContainerLow to scheme.onSurface
    }
}

private fun severityIcon(severity: Severity): ImageVector =
    when (severity) {
        Severity.STRONG -> PackIcons.GppMaybe
        Severity.ATTENTION -> Icons.Rounded.Warning
        Severity.INFO -> Icons.Rounded.Info
    }

@Composable
fun levelName(level: EvidenceLevel): String =
    stringResource(
        when (level) {
            EvidenceLevel.DECLARED -> R.string.level_declared
            EvidenceLevel.CODE -> R.string.level_code
            EvidenceLevel.FLOW -> R.string.level_flow
            EvidenceLevel.OBSERVED -> R.string.level_observed
        }
    )

/** Compact badge for the detail header: strongest severity and number of concerns. */
@Composable
fun RiskBadge(report: RiskReport) {
    val strong = report.count(Severity.STRONG)
    val attention = report.count(Severity.ATTENTION)
    val (container, content) =
        when {
            strong > 0 -> scoreColors(100)
            attention > 0 -> scoreColors(30)
            else -> scoreColors(0)
        }
    Surface(color = container, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (strong + attention == 0) stringResource(R.string.risk_badge_ok)
                else (strong + attention).toString(),
                style = MaterialTheme.typography.titleLarge,
                color = content,
            )
            Text(
                stringResource(R.string.risk_badge_label),
                style = MaterialTheme.typography.labelSmall,
                color = content,
            )
        }
    }
}

@Composable
fun RiskSummary(report: RiskReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(Severity.STRONG, Severity.ATTENTION, Severity.INFO).forEach { s ->
                val n = report.count(s)
                if (n > 0) {
                    val (container, content) = severityColors(s)
                    Surface(color = container, shape = MaterialTheme.shapes.small) {
                        Text(
                            pluralStringResource(
                                when (s) {
                                    Severity.STRONG -> R.plurals.severity_strong_count
                                    Severity.ATTENTION -> R.plurals.severity_attention_count
                                    Severity.INFO -> R.plurals.severity_info_count
                                },
                                n,
                                n,
                            ),
                            color = content,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (report.findings.isEmpty())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.concerns_none),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        Text(
            stringResource(R.string.concerns_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FindingCard(finding: RiskFinding, codeGaps: Boolean = false) {
    val texts = stringArrayResource(riskTexts(finding.id))
    var open by rememberSaveable(finding.id) { mutableStateOf(finding.severity == Severity.STRONG) }
    val (container, content) = severityColors(finding.severity)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable { open = !open }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(severityIcon(finding.severity), null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(texts[0], style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            when (finding.severity) {
                                Severity.STRONG -> R.string.severity_strong
                                Severity.ATTENTION -> R.string.severity_attention
                                Severity.INFO -> R.string.severity_info
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Icon(PackIcons.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f))
            }
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                finding.levels.forEach { LevelTag(it) }
            }
            if (finding.severity == Severity.STRONG)
                Text(
                    stringResource(R.string.evidence_strength, strengthText(finding.strongestLevel, finding.evidence.any(RiskRules::isTraced))),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            if (!open)
                Text(
                    texts[1],
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            else {
                Part(stringResource(R.string.risk_found)) {
                    finding.evidence.forEach { EvidenceLine(it) }
                }
                Part(stringResource(R.string.risk_impact)) { Body(texts[1]) }
                Part(stringResource(R.string.risk_context)) {
                    Body(
                        when (finding.note) {
                            ContextNote.DEFAULT_SMS_APP -> stringResource(R.string.context_default_sms)
                            ContextNote.SYSTEM_APP -> stringResource(R.string.context_system_app)
                            ContextNote.NONE -> texts[2]
                        }
                    )
                }
                Part(stringResource(R.string.risk_unproven)) {
                    Body(
                        when (finding.code) {
                            null,
                            CodeState.NOT_ANALYZED -> texts[3]
                            CodeState.FLOW ->
                                stringResource(
                                    if (finding.evidence.any(RiskRules::isTraced)) R.string.unproven_flow
                                    else R.string.unproven_flow_untraced
                                )
                            CodeState.CALLS -> stringResource(R.string.unproven_calls)
                            CodeState.NOT_FOUND -> stringResource(R.string.unproven_none)
                        }
                    )
                    if (codeGaps && finding.code != null && finding.code != CodeState.NOT_ANALYZED)
                        Body(stringResource(R.string.unproven_gaps))
                }
                Part(stringResource(R.string.risk_action)) { Body(texts[4]) }
            }
        }
    }
}

@Composable
private fun Part(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun LevelTag(level: EvidenceLevel) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            levelName(level),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EvidenceLine(e: Evidence) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            e.level.tag,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(28.dp),
        )
        Column {
            Text(evidenceText(e), style = MaterialTheme.typography.bodyMedium)
            val origin =
                when {
                    e.kind !in listOf(EvidenceKind.CODE, EvidenceKind.STRING, EvidenceKind.FLOW) -> null
                    e.library != null -> stringResource(R.string.ev_origin_library, e.library)
                    e.location.isNotBlank() -> stringResource(R.string.ev_origin_app)
                    else -> null
                }
            val where =
                if (e.kind == EvidenceKind.STRING && e.location in GapKind.entries.map { it.name }) ""
                else listOfNotNull(e.location.takeIf { it.isNotBlank() }, origin).joinToString(" · ")
            if (where.isNotBlank())
                Text(
                    where,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
        }
    }
}

fun evidenceText(res: Resources, e: Evidence): String =
    when (e.stateKey) {
        StateKey.ACCESSIBILITY_ENABLED -> res.getString(R.string.ev_accessibility_enabled)
        StateKey.NOTIFICATION_LISTENER_ENABLED -> res.getString(R.string.ev_notification_enabled)
        StateKey.DEVICE_ADMIN_ACTIVE -> res.getString(R.string.ev_admin_active)
        StateKey.DEFAULT_SMS_APP -> res.getString(R.string.ev_default_sms)
        StateKey.PERMISSION_GRANTED -> res.getString(R.string.ev_granted, shortName(e.detail))
        StateKey.OVERLAY_ALLOWED -> res.getString(R.string.ev_overlay_allowed)
        StateKey.BATTERY_EXEMPT -> res.getString(R.string.ev_battery_exempt)
        StateKey.SIDELOADED -> res.getString(R.string.ev_sideloaded)
        StateKey.FILE_NOT_INSTALLED -> res.getString(R.string.ev_file)
        StateKey.NO_LAUNCHER -> res.getString(R.string.ev_no_launcher)
        StateKey.DEBUG_CERTIFICATE -> res.getString(R.string.ev_debug_cert)
        StateKey.NEW_CERTIFICATE -> res.getString(R.string.ev_new_cert, e.detail)
        StateKey.LURE_LABEL -> res.getString(R.string.ev_lure_label, e.detail)
        StateKey.CLEARTEXT -> res.getString(R.string.ev_cleartext)
        StateKey.INTERNET -> res.getString(R.string.ev_permission, "INTERNET")
        null ->
            when (e.kind) {
                EvidenceKind.PERMISSION -> res.getString(R.string.ev_permission, shortName(e.detail))
                EvidenceKind.COMPONENT -> res.getString(R.string.ev_component, e.detail)
                EvidenceKind.CONFIG ->
                    res.getString(
                        when (e.detail) {
                            "canRetrieveWindowContent" -> R.string.ev_config_retrieve
                            "canPerformGestures" -> R.string.ev_config_gestures
                            else -> R.string.ev_config_all_events
                        }
                    )
                EvidenceKind.NETWORK -> {
                    val parts = e.detail.split('/').mapNotNull { it.toLongOrNull() }
                    res.getString(
                        R.string.ev_network,
                        sizeText(parts.getOrElse(0) { 0 }),
                        sizeText(parts.getOrElse(1) { 0 }),
                    )
                }
                EvidenceKind.CODE -> res.getString(R.string.ev_code_call, e.detail)
                EvidenceKind.STRING ->
                    when (e.location) {
                        GapKind.HIDDEN_CODE.name -> res.getString(R.string.ev_hidden_code, e.detail)
                        GapKind.PACKER.name -> res.getString(R.string.ev_packer, e.detail)
                        else -> res.getString(R.string.ev_code_string, e.detail)
                    }
                EvidenceKind.FLOW -> {
                    val parts = e.detail.split('|')
                    val (source, sink) =
                        parts[0].split('>').map { runCatching { Capability.valueOf(it) }.getOrDefault(Capability.NETWORK) }
                    val hosts = RiskRules.destinations(e)
                    when {
                        hosts.isNotEmpty() ->
                            res.getString(R.string.ev_flow_traced_to, capabilityName(res, source), capabilityName(res, sink), hosts.joinToString(", "))
                        parts.getOrNull(3) == "v" ->
                            res.getString(R.string.ev_flow_traced, capabilityName(res, source), capabilityName(res, sink))
                        else -> res.getString(R.string.ev_flow, capabilityName(res, source), capabilityName(res, sink))
                    } + if (parts.size >= 3) "\n${parts[1]} → ${parts[2]}" else ""
                }
                EvidenceKind.DESTINATION -> {
                    val parts = e.detail.split('|')
                    val host = parts[0]
                    when {
                        parts.getOrNull(2) == "true" -> res.getString(R.string.ev_destination_ip, host)
                        parts.getOrNull(1) == "true" -> res.getString(R.string.ev_destination_cleartext, host)
                        else -> res.getString(R.string.ev_destination, host)
                    }
                }
                else -> e.detail
            }
    }

@Composable
fun evidenceText(e: Evidence): String = evidenceText(LocalResources.current, e)


private fun shortName(permission: String) = permission.substringAfterLast('.')

@Composable
fun CoverageCard(coverage: Coverage, onGrantUsage: () -> Unit) {
    RowGroup(title = stringResource(R.string.coverage)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverageLine(true, pluralStringResource(R.plurals.coverage_apks, coverage.apks, coverage.apks))
            if (coverage.unreadable.isNotEmpty())
                CoverageLine(false, stringResource(R.string.coverage_unreadable, coverage.unreadable.joinToString()))
            if (coverage.unresolvedConfigs > 0)
                CoverageLine(
                    false,
                    pluralStringResource(
                        R.plurals.coverage_configs,
                        coverage.unresolvedConfigs,
                        coverage.unresolvedConfigs,
                    ),
                )
            val code = coverage.code
            if (code == null) CoverageLine(false, stringResource(R.string.coverage_code_failed))
            else {
                CoverageLine(true, stringResource(R.string.coverage_code, code.dexFiles, code.classes, code.appClasses))
                code.gaps.distinctBy { it.kind to (if (it.kind == GapKind.LIMIT) "" else it.detail) }.take(6).forEach { gap ->
                    CoverageLine(
                        false,
                        when (gap.kind) {
                            GapKind.HIDDEN_CODE -> stringResource(R.string.coverage_gap_hidden, gap.detail.substringAfter('!'))
                            GapKind.PACKER -> stringResource(R.string.coverage_gap_packer, gap.detail)
                            GapKind.CROSS_PLATFORM -> stringResource(R.string.coverage_gap_cross, gap.detail)
                            GapKind.UNREADABLE -> stringResource(R.string.coverage_gap_unreadable, gap.detail)
                            GapKind.LIMIT -> stringResource(R.string.coverage_gap_limit)
                        },
                    )
                }
            }
            CoverageLine(
                coverage.deviceStateRead,
                stringResource(if (coverage.deviceStateRead) R.string.coverage_device else R.string.coverage_file),
            )
            when (coverage.usageAccess) {
                true -> CoverageLine(true, stringResource(R.string.coverage_usage_on))
                false ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            CoverageLine(false, stringResource(R.string.coverage_usage_off))
                        }
                        TextButton(onClick = onGrantUsage) {
                            Text(stringResource(R.string.grant_usage_access))
                        }
                    }
                null -> Unit
            }
            Text(
                stringResource(R.string.levels_explained),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverageLine(done: Boolean, text: String) {
    Row {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
            null,
            Modifier.size(18.dp),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
