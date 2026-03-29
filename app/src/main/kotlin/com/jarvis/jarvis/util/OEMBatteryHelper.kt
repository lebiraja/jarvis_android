package com.jarvis.jarvis.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object OEMBatteryHelper {

    fun requestBatteryOptimizationExemption(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            Log.i("OEMBatteryHelper", "Launched Battery Optimization Settings")
        } catch (e: Exception) {
            Log.e("OEMBatteryHelper", "Could not open battery settings", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Log.e("OEMBatteryHelper", "Fallback battery settings also failed", ex)
            }
        }
    }

    fun initDeviceSpecificHandling(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        Log.i("OEMBatteryHelper", "Running on manufacturer: $manufacturer")
        // Hook for launching OEM specific intents if needed (Xiaomi, Huawei, etc.)
    }
}
