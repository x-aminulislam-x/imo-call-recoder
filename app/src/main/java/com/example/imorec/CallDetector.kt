package com.example.imorec

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Detects that *some* app has taken the phone into a voice call.
 *
 * We poll AudioManager.getMode() rather than using the API 31 mode-change
 * listener, because polling behaves identically on every version we support and
 * a one-second granularity is irrelevant next to the ring-to-answer delay.
 *
 * MODE_IN_COMMUNICATION is what VoIP apps (IMO, WhatsApp, Signal) set.
 * MODE_IN_CALL is the cellular telephony mode; we watch it too so the app is
 * useful on carrier calls, but nothing here is telephony-specific.
 */
class CallDetector(
    context: Context,
    private val pollMs: Long = 1000L,
    private val onChange: (inCall: Boolean) -> Unit
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var last = false

    private val tick = object : Runnable {
        override fun run() {
            val now = isInCall()
            if (now != last) {
                last = now
                onChange(now)
            }
            handler.postDelayed(this, pollMs)
        }
    }

    fun start() {
        last = isInCall()
        handler.post(tick)
        if (last) onChange(true)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    fun isInCall(): Boolean {
        val m = audio.mode
        return m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_IN_CALL
    }
}
