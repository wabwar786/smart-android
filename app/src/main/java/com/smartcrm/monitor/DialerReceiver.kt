package com.smartcrm.monitor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

class DialerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.NEW_OUTGOING_CALL") return

        val number  = resultData
            ?: intent.getStringExtra("android.intent.extra.PHONE_NUMBER")
            ?: return
        val cleaned = number.replace("\\s".toRegex(), "").replace("-", "")

        if (cleaned == "*#9999#" || cleaned == "9999") {
            // Cancel the call
            resultData = null

            // Re-enable the launcher ALIAS (not MainActivity)
            try {
                val aliasName = ComponentName(context.packageName, "${context.packageName}.MainLauncher")
                context.packageManager.setComponentEnabledSetting(
                    aliasName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {}

            // Launch MainActivity after short delay
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val i = Intent(context, MainActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(i)
                } catch (e: Exception) {}
            }, 500)
        }
    }
}