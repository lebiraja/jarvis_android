package com.jarvis.jarvis.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.jarvis.R
import com.jarvis.jarvis.service.VoiceListenerService
import com.jarvis.jarvis.util.OEMBatteryHelper

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBatteryAndStartService()
        } else {
            Toast.makeText(this, "Permissions required for Jarvis to work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            if (!hasAllPermissions()) {
                requestPermissionLauncher.launch(requiredPermissions)
            } else {
                checkBatteryAndStartService()
                Toast.makeText(this, "All permissions granted & Service Running!", Toast.LENGTH_SHORT).show()
            }
        }

        if (hasAllPermissions()) {
            checkBatteryAndStartService()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkBatteryAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                OEMBatteryHelper.requestBatteryOptimizationExemption(this)
            }
        }
        startVoiceService()
    }

    private fun startVoiceService() {
        val serviceIntent = Intent(this, VoiceListenerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
