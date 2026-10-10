@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.packpeek.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.BuildConfig
import com.packpeek.app.R
import com.packpeek.app.model.*

@Composable
fun SettingsScreen(vm: MainViewModel, folder: () -> Unit, about: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val c = LocalContext.current
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refreshDeviceState()
        onPauseOrDispose {}
    }
    val sample =
        AppRecord(
            "com.example.app",
            stringResource(R.string.sample_app),
            "1.0",
            1,
            24,
            36,
            false,
            "",
            emptyList(),
            0,
            0,
            0,
            "",
            0,
        )
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RowGroup(title = stringResource(R.string.backups)) {
                ActionRow(
                    stringResource(R.string.backup_folder),
                    folderName(c, s.folder),
                    PackIcons.Folder,
                    onClick = folder,
                )
                GroupDivider()
                ChoiceRow(
                    stringResource(R.string.split_format),
                    s.naming.split.name,
                    listOf(
                        SplitFormat.APKS.name to R.string.split_format_apks,
                        SplitFormat.XAPK.name to R.string.split_format_xapk,
                        SplitFormat.ZIP.name to R.string.split_format_zip,
                        SplitFormat.APK.name to R.string.split_format_apk,
                    ),
                    PackIcons.Layers,
                ) {
                    vm.text("split_format", it)
                }
                if (s.naming.split == SplitFormat.APK)
                    Text(
                        stringResource(R.string.merged_apk_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    )
                GroupDivider()
                ChoiceRow(
                    stringResource(R.string.keep_versions),
                    s.keep.toString(),
                    listOf(
                        "0" to R.string.keep_all,
                        "1" to R.string.keep_1,
                        "3" to R.string.keep_3,
                        "5" to R.string.keep_5,
                    ),
                    PackIcons.History,
                    supporting =
                        if (s.keep > 0) stringResource(R.string.keep_versions_explanation)
                        else null,
                ) {
                    vm.number("keep", it.toInt())
                }
                GroupDivider()
                SwitchRow(
                    stringResource(R.string.confirm_each_time),
                    s.confirm,
                    icon = Icons.Rounded.CheckCircle,
                ) {
                    vm.flag("confirm", it)
                }
            }
        }
        item {
            RowGroup(title = stringResource(R.string.risk_analysis)) {
                ActionRow(
                    stringResource(R.string.usage_access),
                    stringResource(
                        if (usageAccess) R.string.usage_access_on else R.string.usage_access_off
                    ),
                    PackIcons.Insights,
                    onClick = { openUsageAccess(c) },
                ) {
                    Switch(checked = usageAccess, onCheckedChange = null)
                }
            }
        }
        item {
            RowGroup(title = stringResource(R.string.filename_parts)) {
                SwitchRow(stringResource(R.string.app_label), s.naming.name) {
                    vm.flag("name", it)
                }
                SwitchRow(stringResource(R.string.package_name), s.naming.pkg) {
                    vm.flag("pkg", it)
                }
                SwitchRow(stringResource(R.string.version), s.naming.version) {
                    vm.flag("version", it)
                }
                SwitchRow(stringResource(R.string.version_code), s.naming.code) {
                    vm.flag("code", it)
                }
                GroupDivider()
                InfoLine(stringResource(R.string.preview), FileNames.format(sample, s.naming))
                InfoLine(
                    stringResource(R.string.preview_split),
                    FileNames.format(sample.copy(splitPaths = listOf("split.apk")), s.naming),
                )
                Text(
                    stringResource(R.string.format_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
        item {
            RowGroup(title = stringResource(R.string.appearance)) {
                ChoiceRow(
                    stringResource(R.string.theme),
                    s.theme,
                    listOf(
                        "SYSTEM" to R.string.follow_system,
                        "LIGHT" to R.string.light_theme,
                        "DARK" to R.string.dark_theme,
                    ),
                    PackIcons.Palette,
                ) {
                    vm.text("theme", it)
                }
                GroupDivider()
                ChoiceRow(
                    stringResource(R.string.accent),
                    s.accent,
                    listOf(
                        "DYNAMIC" to R.string.dynamic_color,
                        "TEAL" to R.string.teal,
                        "BLUE" to R.string.blue,
                        "PURPLE" to R.string.purple,
                        "ORANGE" to R.string.orange,
                        "RED" to R.string.red,
                        "GRAY" to R.string.gray,
                    ),
                    PackIcons.Tune,
                ) {
                    vm.text("accent", it)
                }
                GroupDivider()
                ChoiceRow(
                    stringResource(R.string.language),
                    AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                    listOf(
                        "" to R.string.follow_system,
                        "en" to R.string.english,
                        "id" to R.string.indonesian,
                    ),
                    PackIcons.Language,
                ) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
                }
            }
        }
        item {
            RowGroup {
                ActionRow(
                    stringResource(R.string.about),
                    "PackPeek ${BuildConfig.VERSION_NAME}",
                    Icons.Rounded.Info,
                    onClick = about,
                )
            }
            Text(
                stringResource(R.string.privacy_local),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun AboutScreen() {
    val c = LocalContext.current
    val resources = LocalResources.current
    var licenses by remember { mutableStateOf(false) }
    val diagnosis =
        "PackPeek ${BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}\n${Build.SUPPORTED_ABIS.joinToString()}"
    val feedback = {
        if (BuildConfig.SUPPORT_EMAIL.isNotBlank())
            c.launchSafe(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}"))
                    .putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.send_feedback))
                    .putExtra(Intent.EXTRA_TEXT, diagnosis)
            )
        else c.copyText(diagnosis)
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(80.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            PackIcons.Inventory2,
                            null,
                            Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            RowGroup {
                Text(
                    stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    stringResource(R.string.alpha_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
        item {
            RowGroup {
                ActionRow(
                    stringResource(R.string.privacy),
                    stringResource(R.string.privacy_local),
                    PackIcons.Shield,
                )
                GroupDivider()
                ActionRow(
                    stringResource(
                        if (BuildConfig.SUPPORT_EMAIL.isBlank()) R.string.copy_diagnostics
                        else R.string.send_feedback
                    ),
                    icon = Icons.Rounded.Email,
                    onClick = feedback,
                )
                GroupDivider()
                ActionRow(
                    stringResource(R.string.share_app),
                    icon = Icons.Rounded.Share,
                    onClick = {
                        c.launchSafe(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(
                                        Intent.EXTRA_TEXT,
                                        resources.getString(R.string.share_app_text),
                                    ),
                                resources.getString(R.string.share),
                            )
                        )
                    },
                )
                if (BuildConfig.SHOW_RATE) {
                    GroupDivider()
                    ActionRow(
                        stringResource(R.string.rate_app),
                        icon = Icons.Rounded.Star,
                        onClick = {
                            c.launchSafe(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        "https://play.google.com/store/apps/details?id=com.packpeek.app"
                                    ),
                                )
                            )
                        },
                    )
                }
                GroupDivider()
                ActionRow(
                    stringResource(R.string.licenses),
                    icon = Icons.Rounded.Info,
                    onClick = { licenses = !licenses },
                )
            }
        }
        if (licenses)
            item {
                Text(
                    remember { c.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}
