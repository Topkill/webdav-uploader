package com.webdav.uploader.keepalive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.webdav.uploader.data.WebDavConfig

data class KeepAliveStatus(
    val batteryOptimizationIgnored: Boolean,
    val notificationGranted: Boolean,
    val canRequestBatteryOptimization: Boolean,
)

object KeepAliveHelper {
    fun status(context: Context): KeepAliveStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
        val notificationGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return KeepAliveStatus(
            batteryOptimizationIgnored = ignored,
            notificationGranted = notificationGranted,
            canRequestBatteryOptimization = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
        )
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching { activity.startActivity(intent) }
    }

    fun openAppDetails(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching { activity.startActivity(intent) }
    }

    fun openAppBatterySettings(activity: Activity) {
        openAppDetails(activity)
    }

    fun applyEnabledOptions(activity: Activity, config: WebDavConfig) {
        if (config.keepAliveBatteryOptimization) {
            requestIgnoreBatteryOptimizations(activity)
        }
        if (config.keepAliveUnrestrictedBattery) {
            openAppBatterySettings(activity)
        }
    }
}