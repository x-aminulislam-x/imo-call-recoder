package com.example.imorec

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.imorec.monitorEnabled

/**
 * Restarts the watcher after a reboot or an app update.
 *
 * Caveat worth knowing: on Android 14 and newer a microphone-type foreground
 * service is not allowed to start from BOOT_COMPLETED at all. The restart below
 * will be rejected there, and the app has to be opened once by hand after a
 * reboot. Targeting SDK 33 avoids this on most builds, but some OEMs enforce it
 * regardless, so we never assume the restart succeeded.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!context.monitorEnabled) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            RecorderService.start(context)
        } catch (t: Throwable) {
            // Android 14+ throws here for mic-type services started at boot.
            Log.w("BootReceiver", "could not auto-start after " + action, t)
        }
    }
}
