package com.packpeek.app.engine

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.packpeek.app.R
import java.io.File
import kotlinx.coroutines.*

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
                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getActivity(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
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
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
                else Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(this, R.string.install_success, Toast.LENGTH_LONG).show()
            else -> Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
