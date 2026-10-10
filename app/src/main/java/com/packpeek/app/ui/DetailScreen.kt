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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.model.*
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailScreen(vm: MainViewModel, backup: (List<AppRecord>) -> Unit) {
    val state by vm.detail.collectAsStateWithLifecycle()
    val app = state.app ?: return
    val c = LocalContext.current
    val resources = LocalResources.current
    val report = state.report
    val latest by vm.latestJob.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    var tab by remember(app.basePath) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(app.label, style = MaterialTheme.typography.headlineSmall)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "v${app.version} · ${sizeText(app.size)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!app.fromFile)
                Button(
                    onClick = { backup(listOf(app)) },
                    enabled = latest?.state !in listOf("QUEUED", "RUNNING"),
                ) {
                    Text(stringResource(R.string.extract))
                }
            else
                Button(
                    enabled = !installing && report != null && app.minSdk <= Build.VERSION.SDK_INT,
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
                        else vm.install()
                    },
                ) {
                    Text(stringResource(R.string.install_backup))
                }
            OutlinedButton(onClick = { vm.saveIcon(app) }) {
                Text(stringResource(R.string.save_icon))
            }
            Box {
                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!app.fromFile) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_app)) },
                            onClick = {
                                menu = false
                                c.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                                    c.launchSafe(it)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_info)) },
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
                        onClick = {
                            menu = false
                            c.copyText(app.packageName)
                        },
                    )
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
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(stringResource(title)) },
                )
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.error?.let { failure ->
                    item {
                        Text(
                            stringResource(errorText(failure)),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { vm.inspect(app) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        item {
                            InfoRow(stringResource(R.string.package_name), app.packageName) {
                                c.copyText(app.packageName)
                            }
                        }
                        item {
                            InfoRow(
                                stringResource(R.string.version),
                                "${app.version} (${app.versionCode})",
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) {
                                    InfoRow(stringResource(R.string.min_sdk), app.minSdk.toString())
                                }
                                Box(Modifier.weight(1f)) {
                                    InfoRow(
                                        stringResource(R.string.target_sdk),
                                        app.targetSdk.toString(),
                                    )
                                }
                            }
                        }
                        item {
                            InfoRow(
                                stringResource(R.string.archive_format),
                                ".${app.format} · ${app.paths.size} APK · ${sizeText(app.size)}",
                            )
                        }
                        item {
                            InfoRow(
                                stringResource(R.string.source),
                                if (app.fromFile) stringResource(R.string.local_file)
                                else sourceName(app.source),
                            )
                        }
                        if (!app.fromFile) {
                            item {
                                InfoRow(
                                    stringResource(R.string.installer),
                                    app.installer.ifBlank { resources.getString(R.string.unknown) },
                                )
                            }
                            item {
                                InfoRow(
                                    stringResource(R.string.last_update),
                                    DateFormat.getDateTimeInstance().format(Date(app.updated)),
                                )
                            }
                            item { InfoRow("UID", app.uid.toString()) }
                        }
                        if (report != null) {
                            item {
                                Card(
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.secondaryContainer
                                        )
                                ) {
                                    Column(Modifier.padding(18.dp)) {
                                        Text(
                                            stringResource(R.string.heuristic_score, report.score),
                                            style = MaterialTheme.typography.headlineSmall,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            stringResource(R.string.audit_disclaimer),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            if (report.findings.isEmpty())
                                item { Text(stringResource(R.string.no_findings)) }
                            items(report.findings) { f ->
                                InfoRow(
                                    stringResource(findingTitle(f.code)),
                                    stringResource(findingBody(f.code), f.count),
                                )
                            }
                        }
                    }
                    1 -> {
                        item {
                            Text(
                                stringResource(R.string.permission_explanation),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (report?.permissions?.isEmpty() == true)
                            item { Text(stringResource(R.string.no_permissions)) }
                        items(report?.permissions.orEmpty()) { p ->
                            InfoRow(
                                p.name,
                                buildString {
                                    append(
                                        resources.getString(
                                            when (p.category) {
                                                "DANGEROUS" -> R.string.dangerous_permission
                                                "SIGNATURE" -> R.string.signature_permission
                                                else -> R.string.normal_permission
                                            }
                                        )
                                    )
                                    append(" · ")
                                    append(
                                        resources.getString(
                                            when (p.granted) {
                                                true -> R.string.granted
                                                false -> R.string.not_granted
                                                else -> R.string.not_applicable
                                            }
                                        )
                                    )
                                    if (p.description.isNotBlank()) {
                                        append("\n")
                                        append(p.description)
                                    }
                                },
                            )
                        }
                    }
                    2 -> {
                        item {
                            Text(
                                stringResource(R.string.component_explanation),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        items(report?.components.orEmpty()) { p ->
                            InfoRow(
                                p.name,
                                "${p.kind} · ${stringResource(if(p.exported)R.string.exported else R.string.internal)} · ${stringResource(if(p.enabled)R.string.enabled else R.string.disabled)}\n${stringResource(R.string.permission)}: ${p.permission.ifBlank {resources.getString(R.string.none)}}",
                            )
                        }
                    }
                    3 ->
                        if (report != null) {
                            item {
                                InfoRow(
                                    stringResource(R.string.signature_verification),
                                    stringResource(
                                        when (report.signatureValid) {
                                            true -> R.string.signature_valid
                                            false -> R.string.signature_invalid
                                            else -> R.string.unknown
                                        }
                                    ),
                                )
                            }
                            item {
                                InfoRow(
                                    stringResource(R.string.signature_schemes),
                                    report.schemes.joinToString().ifBlank {
                                        resources.getString(R.string.unknown)
                                    },
                                )
                            }
                            item {
                                Text(
                                    stringResource(R.string.signature_scope),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (report.signerMatch != null)
                                item {
                                    InfoRow(
                                        stringResource(R.string.installed_signer),
                                        stringResource(
                                            if (report.signerMatch) R.string.signer_matches
                                            else R.string.signer_differs
                                        ),
                                    )
                                }
                            items(report.certificates) { cert ->
                                OutlinedCard {
                                    Column(
                                        Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        InfoRow(stringResource(R.string.subject), cert.subject)
                                        InfoRow(stringResource(R.string.issuer), cert.issuer)
                                        InfoRow(
                                            stringResource(R.string.validity),
                                            "${cert.validFrom}\n${cert.validTo}\n${stringResource(if(cert.expired)R.string.cert_expired else R.string.cert_current)}",
                                        )
                                        InfoRow(stringResource(R.string.algorithm), cert.algorithm)
                                        InfoRow(stringResource(R.string.serial), cert.serial)
                                        InfoRow("SHA-256", cert.sha256) { c.copyText(cert.sha256) }
                                        InfoRow("SHA-1", cert.sha1) { c.copyText(cert.sha1) }
                                        InfoRow("MD5", cert.md5) { c.copyText(cert.md5) }
                                    }
                                }
                            }
                        }
                    4 ->
                        if (report != null) {
                            item {
                                InfoRow(
                                    stringResource(R.string.framework),
                                    report.frameworks.joinToString().ifBlank {
                                        resources.getString(R.string.android_native)
                                    },
                                )
                            }
                            item {
                                Text(
                                    stringResource(R.string.tech_disclaimer),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            item {
                                InfoRow(
                                    stringResource(R.string.architectures),
                                    report.abis.joinToString().ifBlank {
                                        resources.getString(R.string.no_native_libs)
                                    },
                                )
                            }
                            item {
                                Text(
                                    stringResource(
                                        R.string.native_libraries,
                                        report.libraries.size,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            items(report.libraries) {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                }
            }
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
