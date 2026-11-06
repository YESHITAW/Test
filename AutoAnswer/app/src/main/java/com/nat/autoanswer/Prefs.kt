package com.nat.autoanswer

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("autoanswer", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Seconds to let the phone ring before answering. */
    var ringDelaySec: Int
        get() = sp.getInt("ringDelaySec", 15)
        set(v) = sp.edit().putInt("ringDelaySec", v).apply()

    /** Hard limit on a single recorded message, in seconds. */
    var maxMessageSec: Int
        get() = sp.getInt("maxMessageSec", 60)
        set(v) = sp.edit().putInt("maxMessageSec", v).apply()

    var greeting: String
        get() = sp.getString("greeting", DEFAULT_GREETING) ?: DEFAULT_GREETING
        set(v) = sp.edit().putString("greeting", v).apply()

    /** Hang up automatically when the caller stops talking. */
    var hangUpOnSilence: Boolean
        get() = sp.getBoolean("hangUpOnSilence", true)
        set(v) = sp.edit().putBoolean("hangUpOnSilence", v).apply()

    companion object {
        const val DEFAULT_GREETING =
            "Hello, you've reached my voicemail. I can't take your call right now. " +
                "Please leave your name and message after the tone, and I'll get back to you."
    }
}
