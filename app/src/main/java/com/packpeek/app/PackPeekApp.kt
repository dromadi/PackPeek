package com.packpeek.app

import android.app.Application
import android.os.Build
import androidx.room.Room
import com.packpeek.app.data.*
import com.packpeek.app.engine.AuditEngine
import com.packpeek.app.engine.risk.DeviceStateReader
import com.packpeek.app.engine.risk.RiskEngine
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class PackPeekApp : Application() {
    val settings by lazy { SettingsRepository(this) }
    val database by lazy {
        Room.databaseBuilder(this, BackupDatabase::class.java, "packpeek.db")
            .addMigrations(BackupDatabase.MIGRATION_1_2)
            .build()
    }
    val apps by lazy { AppRepository(this) }
    val audit by lazy { AuditEngine(apps) }
    val deviceState by lazy { DeviceStateReader(this, apps) }
    val risk by lazy { RiskEngine(deviceState) }

    val crashFile
        get() = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "imports").deleteRecursively()
        // Keeps the last crash on the device only, so testers can copy it from the next launch.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                crashFile.writeText(
                    "PackPeek ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" +
                        trace.toString().take(32 * 1024)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
