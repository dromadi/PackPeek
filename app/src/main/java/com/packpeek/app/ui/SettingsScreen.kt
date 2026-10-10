@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.packpeek.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packpeek.app.BuildConfig
import com.packpeek.app.R
import com.packpeek.app.model.*

@Composable
fun SettingsScreen(vm: MainViewModel, folder: () -> Unit, about: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val c = LocalContext.current
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.backups), style = MaterialTheme.typography.titleLarge) }
        item { InfoRow(stringResource(R.string.backup_folder), folderName(c, s.folder), folder) }
        item {
            ToggleSetting(stringResource(R.string.confirm_each_time), s.confirm) {
                vm.flag("confirm", it)
            }
        }
        item {
            Text(
                stringResource(R.string.filename_parts),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            ToggleSetting(stringResource(R.string.app_label), s.naming.name) { vm.flag("name", it) }
            ToggleSetting(stringResource(R.string.package_name), s.naming.pkg) {
                vm.flag("pkg", it)
            }
            ToggleSetting(stringResource(R.string.version), s.naming.version) {
                vm.flag("version", it)
            }
            ToggleSetting(stringResource(R.string.version_code), s.naming.code) {
                vm.flag("code", it)
            }
        }
        item {
            ChoiceMenu(
                stringResource(R.string.split_format),
                s.naming.split.name,
                listOf(
                    SplitFormat.APKS.name to R.string.split_format_apks,
                    SplitFormat.XAPK.name to R.string.split_format_xapk,
                    SplitFormat.ZIP.name to R.string.split_format_zip,
                ),
            ) {
                vm.text("split_format", it)
            }
        }
        item {
            InfoRow(stringResource(R.string.preview), FileNames.format(sample, s.naming))
            InfoRow(
                stringResource(R.string.preview_split),
                FileNames.format(sample.copy(splitPaths = listOf("split.apk")), s.naming),
            )
            Text(
                stringResource(R.string.format_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            HorizontalDivider()
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu(
                    stringResource(R.string.theme),
                    s.theme,
                    listOf(
                        "SYSTEM" to R.string.follow_system,
                        "LIGHT" to R.string.light_theme,
                        "DARK" to R.string.dark_theme,
                    ),
                ) {
                    vm.text("theme", it)
                }
                ChoiceMenu(
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
                ) {
                    vm.text("accent", it)
                }
                ChoiceMenu(
                    stringResource(R.string.language),
                    AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                    listOf(
                        "" to R.string.follow_system,
                        "en" to R.string.english,
                        "id" to R.string.indonesian,
                    ),
                ) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
                }
            }
        }
        item {
            HorizontalDivider()
            InfoRow(stringResource(R.string.about), "PackPeek ${BuildConfig.VERSION_NAME}", about)
        }
        item {
            Text(stringResource(R.string.privacy_local))
            Text(
                stringResource(R.string.backup_disclaimer),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleSetting(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!value) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = null)
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
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(
                BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            Text(stringResource(R.string.about_body))
            Text(stringResource(R.string.alpha_notice), style = MaterialTheme.typography.bodySmall)
        }
        item { InfoRow(stringResource(R.string.privacy), stringResource(R.string.privacy_local)) }
        item {
            Button(
                onClick = {
                    if (BuildConfig.SUPPORT_EMAIL.isNotBlank())
                        c.launchSafe(
                            Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}"),
                                )
                                .putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    resources.getString(R.string.send_feedback),
                                )
                                .putExtra(Intent.EXTRA_TEXT, diagnosis)
                        )
                    else c.copyText(diagnosis)
                }
            ) {
                Text(
                    stringResource(
                        if (BuildConfig.SUPPORT_EMAIL.isBlank()) R.string.copy_diagnostics
                        else R.string.send_feedback
                    )
                )
            }
        }
        item {
            OutlinedButton(
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
                }
            ) {
                Text(stringResource(R.string.share_app))
            }
        }
        if (BuildConfig.SHOW_RATE)
            item {
                OutlinedButton(
                    onClick = {
                        c.launchSafe(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://play.google.com/store/apps/details?id=com.packpeek.app"
                                ),
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.rate_app))
                }
            }
        item {
            TextButton(onClick = { licenses = !licenses }) {
                Text(stringResource(R.string.licenses))
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
