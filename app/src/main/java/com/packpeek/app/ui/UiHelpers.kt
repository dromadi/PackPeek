package com.packpeek.app.ui

import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.packpeek.app.PackPeekApp
import com.packpeek.app.R
import com.packpeek.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decimal units (1 MB = 1,000,000 bytes), as Android settings and file managers show them. */
fun sizeText(bytes: Long): String =
    when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000L -> "%.0f kB".format(bytes / 1e3)
        else -> "$bytes B"
    }

fun errorText(code: ErrorCode) =
    when (code) {
        ErrorCode.FOLDER -> R.string.error_folder
        ErrorCode.STORAGE -> R.string.error_storage
        ErrorCode.SOURCE -> R.string.error_source
        ErrorCode.ARCHIVE -> R.string.error_archive
        ErrorCode.SIGNATURE -> R.string.error_signature
        ErrorCode.ABI -> R.string.error_abi
        ErrorCode.MERGE -> R.string.error_merge
        else -> R.string.error_unknown
    }

fun Context.launchSafe(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
    }
}

fun Context.copyText(value: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("PackPeek", value))
}

fun shareFiles(context: Context, uris: List<Uri>, mime: String = "*/*") {
    if (uris.isEmpty()) return
    val intent =
        Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            .setType(mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first())
    else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    intent.clipData =
        ClipData.newUri(context.contentResolver, "PackPeek", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    context.launchSafe(Intent.createChooser(intent, context.getString(R.string.share)))
}

fun folderName(context: Context, value: String): String {
    if (value.isBlank()) return context.getString(R.string.choose_folder)
    val uri = Uri.parse(value)
    runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }
    // Some providers (e.g. the Downloads root) report no display name for the tree itself.
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
    val root = id.substringBefore(':')
    val path = id.substringAfter(':', "").trimEnd('/')
    return when {
        path.isNotBlank() -> path.substringAfterLast('/')
        root == "downloads" -> "Download"
        root == "primary" -> context.getString(R.string.internal_storage)
        root.isNotBlank() -> context.getString(R.string.folder_root, root)
        else -> context.getString(R.string.folder_selected)
    }
}

fun openFolder(context: Context, folder: String) {
    if (folder.isNotBlank())
        context.launchSafe(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(folder), "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
}

fun loadAppDrawable(context: Context, app: AppRecord): Drawable? = runCatching {
    val repo = (context.applicationContext as PackPeekApp).apps
    val info: ApplicationInfo =
        if (app.fromFile)
            requireNotNull(repo.archiveInfo(app.basePath)?.applicationInfo).apply {
                sourceDir = app.basePath
                publicSourceDir = app.basePath
            }
        else requireNotNull(repo.packageInfo(app.packageName).applicationInfo)
    if (info.icon != 0)
        repo.pm.getResourcesForApplication(info).getDrawableForDensity(info.icon, 640, null)
    else info.loadIcon(repo.pm)
}
    .getOrNull()

private val icons =
    object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

@Composable
fun AppIcon(app: AppRecord, modifier: Modifier = Modifier.size(48.dp)) {
    val context = LocalContext.current
    val key = "${app.packageName}:${app.versionCode}:${app.updated}:${app.basePath}"
    val bitmap by
        produceState<Bitmap?>(icons.get(key), key) {
            value =
                withContext(Dispatchers.IO) {
                    icons.get(key)
                        ?: loadAppDrawable(context, app)?.let { d ->
                            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
                                d.setBounds(0, 0, 128, 128)
                                d.draw(Canvas(b))
                                icons.put(key, b)
                            }
                        }
                }
        }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier)
    else
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        )
}

@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
