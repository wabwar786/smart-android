package com.smartcrm.monitor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Transparent activity jo MediaProjection permission maangti hai.
 * Ek baar approve karne ke baad result ScreenStreamService ko bheja jata hai.
 */
class ScreenPermissionActivity : Activity() {

    companion object {
        const val REQUEST_CODE = 1001
        var pendingCmdId: Int = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingCmdId = intent.getIntExtra("cmd_id", 0)

        val prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        val savedResult = prefs.getString("projection_data", null)

        if (savedResult != null) {
            // Already approved before — directly start streaming
            startStreamService(Activity.RESULT_OK, intent)
            finish()
            return
        }

        // Ask user for permission
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            // Save approval flag
            getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean("projection_approved", true)
                .apply()
            startStreamService(resultCode, data)
        }
        finish()
    }

    private fun startStreamService(resultCode: Int, data: Intent?) {
        val i = Intent(this, ScreenStreamService::class.java)
        i.putExtra("result_code", resultCode)
        i.putExtra("result_data", data)
        i.putExtra("cmd_id", pendingCmdId)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }
}
