package com.smartcrm.monitor

import android.content.Context
import android.content.SharedPreferences
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val HEADERS = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
        .add("Accept", "application/json")
        .build()

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString("token", null)
        if (token.isNullOrEmpty()) {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val raw = "${android.os.Build.MODEL}_${deviceId}_smartcrm_android"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            token = digest.joinToString("") { "%02x".format(it) }.take(48)
            prefs.edit().putString("token", token).apply()
        }
        return token
    }

    fun getDeviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    fun post(endpoint: String, data: JSONObject, context: Context): JSONObject? {
        return try {
            data.put("token", getToken(context))
            data.put("pc_name", getDeviceName())
            data.put("device_type", "android")

            val body = data.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("${Config.SERVER_URL}/$endpoint")
                .headers(HEADERS)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null
            JSONObject(responseBody)
        } catch (e: Exception) {
            null
        }
    }

    fun get(endpoint: String, params: Map<String, String>, context: Context): JSONObject? {
        return try {
            val urlBuilder = StringBuilder("${Config.SERVER_URL}/$endpoint?")
            urlBuilder.append("token=${getToken(context)}")
            params.forEach { (k, v) -> urlBuilder.append("&$k=$v") }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .headers(HEADERS)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null
            JSONObject(responseBody)
        } catch (e: Exception) {
            null
        }
    }
}
