package com.packpeek.app.engine

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import com.packpeek.app.PackPeekApp
import com.packpeek.app.model.InstallErrors
import com.packpeek.app.model.InstallResult
import java.io.File
import kotlinx.coroutines.*

private const val EXTRA_TARGET = "com.packpeek.app.extra.TARGET_PACKAGE"

suspend fun installPackage(context: Context, pkg: String, files: List<File>) =
    withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(pkg)
                setSize(files.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= 31)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                files.forEachIndexed { index, file ->
                    ensureActive()
                    file.inputStream().use { input ->
                        session
                            .openWrite(
                                if (index == 0) "base.apk" else "split_$index.apk",
                                0,
                                file.length(),
                            )
                            .use { output ->
                                val b = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(b)
                                    if (n < 0) break
                                    output.write(b, 0, n)
                                }
                                session.fsync(output)
                            }
                    }
                }
                val intent =
                    Intent(context, InstallStatusActivity::class.java)
                        .setAction("${context.packageName}.INSTALL_RESULT.$id")
                        .putExtra(EXTRA_TARGET, pkg)
                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(
                    PendingIntent.getActivity(context, id, intent, flags, creatorMayStart())
                        .intentSender
                )
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

/**
 * The system installer reports through this PendingIntent while PackPeek is on screen. From
 * Android 15 (target SDK 35+) the creator must opt in, or the launch of [InstallStatusActivity],
 * and with it the confirmation prompt, is blocked as a background activity start.
 */
@Suppress("DEPRECATION")
private fun creatorMayStart(): Bundle? =
    when {
        Build.VERSION.SDK_INT >= 36 ->
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                )
                .toBundle()
        Build.VERSION.SDK_INT >= 34 ->
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
        else -> null
    }

class InstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg =
            intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                ?: intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val result =
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm =
                        if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null && runCatching { startActivity(confirm) }.isSuccess) null
                    else InstallResult(pkg, InstallErrors.classify(status, message), message)
                }
                PackageInstaller.STATUS_SUCCESS -> InstallResult(pkg)
                else -> InstallResult(pkg, InstallErrors.classify(status, message), message)
            }
        // The main screen shows the result, including after it is reopened.
        if (result != null) (application as PackPeekApp).installResult.value = result
        finish()
    }

}
