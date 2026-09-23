package com.example.imorec

import android.content.Context
import android.os.Environment
import java.io.File

private const val PREFS_NAME = "imorec"
private const val KEY_IMO_ONLY = "imo_only"
private const val KEY_FORCE_SPEAKER = "force_speaker"
private const val KEY_MONITOR = "monitor_enabled"

private fun Context.prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

// Top-level extensions, not members of Prefs: an extension declared inside an
// object is a member-extension and cannot be imported or used elsewhere.

/** Record only calls identified as IMO. Requires notification access. */
var Context.imoOnly: Boolean
    get() = prefs().getBoolean(KEY_IMO_ONLY, false)
    set(v) {
        prefs().edit().putBoolean(KEY_IMO_ONLY, v).apply()
    }

/** Route call audio to the loudspeaker so the mic can hear the far end. */
var Context.forceSpeaker: Boolean
    get() = prefs().getBoolean(KEY_FORCE_SPEAKER, true)
    set(v) {
        prefs().edit().putBoolean(KEY_FORCE_SPEAKER, v).apply()
    }

/** Whether the user wants the watcher running. Survives reboot. */
var Context.monitorEnabled: Boolean
    get() = prefs().getBoolean(KEY_MONITOR, false)
    set(v) {
        prefs().edit().putBoolean(KEY_MONITOR, v).apply()
    }

object Prefs {
    fun recordingsDir(c: Context): File {
        val base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: c.filesDir
        val dir = File(base, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
