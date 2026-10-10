package com.packpeek.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.R
import com.packpeek.app.model.AppRecord

@Composable
fun DashboardScreen(vm: MainViewModel, inspect: (AppRecord) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val tech by vm.technologies.collectAsStateWithLifecycle()
    val scores by vm.riskScores.collectAsStateWithLifecycle()
    val checking by vm.checkingOverview.collectAsStateWithLifecycle()
    val source = remember(apps) { apps.groupingBy { it.source }.eachCount() }
    val sdk = remember(apps) { apps.groupingBy { it.targetSdk }.eachCount().toSortedMap() }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.device_snapshot),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    InfoRow(stringResource(R.string.apps), apps.size.toString())
                }
                Box(Modifier.weight(1f)) {
                    InfoRow(stringResource(R.string.apk_storage), sizeText(apps.sumOf { it.size }))
                }
            }
        }
        item {
            InfoRow(
                stringResource(R.string.app_type),
                stringResource(
                    R.string.type_counts,
                    apps.count { !it.system },
                    apps.count { it.system },
                    apps.count { it.split },
                ),
            )
        }
        item { Text(stringResource(R.string.source), style = MaterialTheme.typography.titleLarge) }
        items(source.toList()) { (key, count) -> CountBar(sourceName(key), count, apps.size) }
        item {
            Text(stringResource(R.string.target_sdk), style = MaterialTheme.typography.titleLarge)
        }
        items(sdk.toList()) { (key, count) -> CountBar("SDK $key", count, apps.size) }
        item {
            Text(stringResource(R.string.largest_apps), style = MaterialTheme.typography.titleLarge)
        }
        items(
            apps.sortedByDescending { it.size }.take(10),
            key = { "largest:${it.packageName}" },
        ) { app ->
            InfoRow(app.label, sizeText(app.size)) { inspect(app) }
        }
        item {
            Text(
                stringResource(R.string.technology_and_risk),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.overview_scan_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Button(
                onClick = { if (checking) vm.cancelOverview() else vm.scanOverview() },
                enabled = apps.isNotEmpty(),
            ) {
                Text(stringResource(if (checking) R.string.cancel_scan else R.string.scan_overview))
            }
            Text(
                stringResource(R.string.scan_coverage, scores.size, apps.size),
                style = MaterialTheme.typography.labelMedium,
            )
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        items(tech.values.groupingBy { it }.eachCount().toList()) { (framework, count) ->
            CountBar(framework, count, tech.size)
        }
        items(
            apps
                .filter { it.packageName in scores }
                .sortedByDescending { scores[it.packageName] }
                .take(10),
            key = { "risk:${it.packageName}" },
        ) { app ->
            InfoRow(
                app.label,
                stringResource(R.string.heuristic_score, scores[app.packageName] ?: 0),
            ) {
                inspect(app)
            }
        }
        item {
            Text(
                stringResource(R.string.audit_disclaimer),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CountBar(label: String, count: Int, total: Int) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else count.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
