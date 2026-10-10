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
    }

@Composable
private fun severityColors(severity: Severity): Pair<Color, Color> {
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
fun FindingCard(finding: RiskFinding) {
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
                Part(stringResource(R.string.risk_unproven)) { Body(texts[3]) }
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
            if (e.location.isNotBlank())
                Text(
                    e.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
        }
    }
}

@Composable
fun evidenceText(e: Evidence): String =
    when (e.stateKey) {
        StateKey.ACCESSIBILITY_ENABLED -> stringResource(R.string.ev_accessibility_enabled)
        StateKey.NOTIFICATION_LISTENER_ENABLED -> stringResource(R.string.ev_notification_enabled)
        StateKey.DEVICE_ADMIN_ACTIVE -> stringResource(R.string.ev_admin_active)
        StateKey.DEFAULT_SMS_APP -> stringResource(R.string.ev_default_sms)
        StateKey.PERMISSION_GRANTED -> stringResource(R.string.ev_granted, shortName(e.detail))
        StateKey.OVERLAY_ALLOWED -> stringResource(R.string.ev_overlay_allowed)
        StateKey.BATTERY_EXEMPT -> stringResource(R.string.ev_battery_exempt)
        StateKey.SIDELOADED -> stringResource(R.string.ev_sideloaded)
        StateKey.FILE_NOT_INSTALLED -> stringResource(R.string.ev_file)
        StateKey.NO_LAUNCHER -> stringResource(R.string.ev_no_launcher)
        StateKey.DEBUG_CERTIFICATE -> stringResource(R.string.ev_debug_cert)
        StateKey.NEW_CERTIFICATE -> stringResource(R.string.ev_new_cert, e.detail)
        StateKey.LURE_LABEL -> stringResource(R.string.ev_lure_label, e.detail)
        StateKey.CLEARTEXT -> stringResource(R.string.ev_cleartext)
        StateKey.INTERNET -> stringResource(R.string.ev_permission, "INTERNET")
        null ->
            when (e.kind) {
                EvidenceKind.PERMISSION -> stringResource(R.string.ev_permission, shortName(e.detail))
                EvidenceKind.COMPONENT -> stringResource(R.string.ev_component, e.detail)
                EvidenceKind.CONFIG ->
                    stringResource(
                        when (e.detail) {
                            "canRetrieveWindowContent" -> R.string.ev_config_retrieve
                            "canPerformGestures" -> R.string.ev_config_gestures
                            else -> R.string.ev_config_all_events
                        }
                    )
                EvidenceKind.NETWORK -> {
                    val parts = e.detail.split('/').mapNotNull { it.toLongOrNull() }
                    stringResource(
                        R.string.ev_network,
                        sizeText(parts.getOrElse(0) { 0 }),
                        sizeText(parts.getOrElse(1) { 0 }),
                    )
                }
                else -> e.detail
            }
    }

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
            CoverageLine(coverage.codeAnalyzed, stringResource(R.string.coverage_no_code))
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
