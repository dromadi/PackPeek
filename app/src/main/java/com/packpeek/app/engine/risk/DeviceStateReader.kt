package com.packpeek.app.engine.risk

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import com.packpeek.app.data.AppRepository

/** Packages with powerful services switched on right now, for the overview screen. */
data class ActiveServices(
    val accessibility: Set<String>,
    val notificationListeners: Set<String>,
    val deviceAdmins: Set<String>,
    val defaultSms: String?,
)

/**
 * Reads what this device says about installed apps. Everything here is readable by a normal app;
 * network usage additionally needs the user to grant "Usage access" (optional).
 */
class DeviceStateReader(private val context: Context, private val apps: AppRepository) {
    private val appOps = context.getSystemService(AppOpsManager::class.java)

    fun usageAccessGranted(): Boolean = opAllowed(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)

    fun activeServices(): ActiveServices =
        ActiveServices(
            accessibility = enabledAccessibility().map { it.packageName }.toSet(),
            notificationListeners =
                runCatching { NotificationManagerCompat.getEnabledListenerPackages(context) }
                    .getOrDefault(emptySet()),
            deviceAdmins =
                runCatching {
                        context
                            .getSystemService(DevicePolicyManager::class.java)
                            .activeAdmins
                            .orEmpty()
                            .map { it.packageName }
                            .toSet()
                    }
                    .getOrDefault(emptySet()),
            defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
        )

    fun read(pkg: String): DeviceState {
        val active = activeServices()
        val info = runCatching { apps.packageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
        val uid = info?.applicationInfo?.uid ?: -1
        val usage = usageAccessGranted()
        return DeviceState(
            accessibilityEnabled =
                enabledAccessibility().filter { it.packageName == pkg }.map { it.className }.toSet(),
            notificationListenerEnabled = pkg in active.notificationListeners,
            deviceAdminActive = pkg in active.deviceAdmins,
            defaultSmsApp = active.defaultSms == pkg,
            overlayAllowed = uid >= 0 && opAllowed(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg),
            batteryExempt =
                runCatching {
                        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
                    }
                    .getOrDefault(false),
            granted = info?.let(::grantedPermissions).orEmpty(),
            usageAccess = usage,
            network = if (usage && uid >= 0) networkUsage(uid) else null,
        )
    }

    private fun enabledAccessibility(): List<ComponentName> =
        runCatching {
                Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    )
                    .orEmpty()
                    .split(':')
                    .mapNotNull { ComponentName.unflattenFromString(it) }
            }
            .getOrDefault(emptyList())

    private fun grantedPermissions(info: PackageInfo): Set<String> {
        val names = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)
        return names
            .filterIndexed { i, _ ->
                (flags.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }
            .toSet()
    }

    @Suppress("DEPRECATION")
    private fun opAllowed(op: String, uid: Int, pkg: String): Boolean =
        runCatching {
                val mode =
                    if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(op, uid, pkg)
                    else appOps.checkOpNoThrow(op, uid, pkg)
                mode == AppOpsManager.MODE_ALLOWED
            }
            .getOrDefault(false)

    /** Bytes sent and received over the last [DAYS] days, split by foreground and background. */
    @Suppress("DEPRECATION")
    private fun networkUsage(uid: Int): NetworkUsage? =
        runCatching {
                val manager = context.getSystemService(NetworkStatsManager::class.java)
                val end = System.currentTimeMillis()
                val start = end - DAYS * 24L * 60 * 60 * 1000
                var foreground = 0L
                var background = 0L
                for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                    val stats =
                        runCatching { manager.querySummary(type, null, start, end) }.getOrNull()
                            ?: continue
                    stats.use {
                        val bucket = NetworkStats.Bucket()
                        while (it.hasNextBucket()) {
                            it.getNextBucket(bucket)
                            if (bucket.uid != uid) continue
                            val bytes = bucket.rxBytes + bucket.txBytes
                            if (bucket.state == NetworkStats.Bucket.STATE_FOREGROUND) foreground += bytes
                            else background += bytes
                        }
                    }
                }
                NetworkUsage(foreground, background, DAYS)
            }
            .getOrNull()

    companion object {
        const val DAYS = 30
    }
}
