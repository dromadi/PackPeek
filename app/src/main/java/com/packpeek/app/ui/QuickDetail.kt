@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.packpeek.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.packpeek.app.R
import com.packpeek.app.data.ComponentCounts
import com.packpeek.app.model.AndroidVersions
import com.packpeek.app.model.AppRecord
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun openStore(context: Context, pkg: String) =
    context.launchSafe(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
    )

fun openSystemInfo(context: Context, pkg: String) =
    context.launchSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

/** Starts the app; false when it has no launcher entry. */
fun launchApp(context: Context, pkg: String): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    context.launchSafe(intent)
    return true
}

/**
 * Half-screen summary of an installed app with its main actions, for quick look-ups without
 * leaving the list. [full] opens the complete detail page on the given tab.
 */
@Composable
fun QuickDetailSheet(
    vm: MainViewModel,
    app: AppRecord,
    extractEnabled: Boolean,
    onDismiss: () -> Unit,
    onExtract: () -> Unit,
    full: (tab: Int) -> Unit,
) {
    val c = LocalContext.current
    val counts by
        produceState<ComponentCounts?>(null, app.packageName) {
            value = withContext(Dispatchers.IO) { vm.owner.apps.counts(app.packageName) }
        }
    val installer by
        produceState<String?>(null, app.installer) {
            value =
                app.installer.takeIf { it.isNotBlank() }?.let {
                    withContext(Dispatchers.IO) { vm.owner.apps.label(it) }
                }
        }
    val canLaunch = remember(app.packageName) { c.packageManager.getLaunchIntentForPackage(app.packageName) != null }
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, Modifier.size(56.dp))
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
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag("API ${app.targetSdk}")
                        if (app.split) Tag(stringResource(R.string.tag_split))
                        if (app.system)
                            Tag(
                                stringResource(R.string.tag_system),
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            val rows =
                listOf(
                    stringResource(R.string.version) to "${app.version} (${app.versionCode})",
                    stringResource(R.string.min_android) to AndroidVersions.label(app.minSdk),
                    stringResource(R.string.target_android) to AndroidVersions.label(app.targetSdk),
                    stringResource(R.string.first_installed) to dates.format(Date(app.installed)),
                    stringResource(R.string.last_update) to dates.format(Date(app.updated)),
                    stringResource(R.string.installer) to (installer ?: stringResource(R.string.unknown)),
                    "UID" to app.uid.toString(),
                    stringResource(R.string.size) to "${sizeText(app.size)} · ${app.paths.size} APK",
                )
            rows.forEach { (label, value) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f).padding(end = 12.dp),
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
                }
            }
            counts?.let { n ->
                FlowRow(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            Triple(R.string.permissions, n.permissions, DETAIL_TAB_PERMISSIONS),
                            Triple(R.string.activities, n.activities, DETAIL_TAB_COMPONENTS),
                            Triple(R.string.services, n.services, DETAIL_TAB_COMPONENTS),
                            Triple(R.string.receivers, n.receivers, DETAIL_TAB_COMPONENTS),
                            Triple(R.string.providers, n.providers, DETAIL_TAB_COMPONENTS),
                        )
                        .forEach { (title, count, tab) ->
                            AssistChip(
                                onClick = { full(tab) },
                                label = {
                                    Text(stringResource(R.string.tab_count, stringResource(title), count))
                                },
                            )
                        }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onExtract, enabled = extractEnabled) {
                    Icon(PackIcons.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.extract))
                }
                if (canLaunch)
                    OutlinedButton(onClick = { launchApp(c, app.packageName) }) {
                        Icon(PackIcons.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.open_app))
                    }
                OutlinedButton(onClick = { openStore(c, app.packageName) }) {
                    Icon(Icons.Rounded.ShoppingCart, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.play_store))
                }
                FilledTonalButton(onClick = { full(0) }) { Text(stringResource(R.string.full_details)) }
            }
        }
    }
}

const val DETAIL_TAB_PERMISSIONS = 1
const val DETAIL_TAB_COMPONENTS = 2
