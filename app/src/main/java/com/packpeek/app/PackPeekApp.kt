package com.packpeek.app

import android.app.Application
import androidx.room.Room
import com.packpeek.app.data.*
import com.packpeek.app.engine.AuditEngine
import com.packpeek.app.model.InstallResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class PackPeekApp : Application() {
    val settings by lazy { SettingsRepository(this) }
    val database by lazy {
        Room.databaseBuilder(this, BackupDatabase::class.java, "packpeek.db").build()
    }
    val apps by lazy { AppRepository(this) }
    val audit by lazy { AuditEngine(apps) }
    /** Latest result reported by the system installer, shown once by the UI. */
    val installResult = MutableStateFlow<InstallResult?>(null)

    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "imports").deleteRecursively()
    }
}
