package com.example.imorec

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Tells us *which* app is in the call, which AudioManager.getMode() cannot.
 *
 * IMO posts an ongoing notification for the duration of a call, so an ongoing
 * notification from an IMO package plus MODE_IN_COMMUNICATION is a reliable
 * "this is an IMO call" signal without any privileged permission.
 *
 * This is optional. With notification access off, the service still records
 * every voice call; it just cannot label or filter them by app.
 */
class CallNotificationListener : NotificationListenerService() {

    companion object {
        /** Play, beta and HD builds all ship under different package names. */
        val IMO_PACKAGES = setOf(
            "com.imo.android.imoim",
            "com.imo.android.imoimbeta",
            "com.imo.android.imoimhd"
        )

        @Volatile
        var connected = false
            private set

        @Volatile
        var imoCallOngoing = false
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(context, CallNotificationListener::class.java)
            return flat.split(":").any {
                val cn = ComponentName.unflattenFromString(it)
                cn != null && cn.packageName == me.packageName
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        connected = false
        imoCallOngoing = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        imoCallOngoing = try {
            activeNotifications?.any { sbn ->
                sbn.packageName in IMO_PACKAGES && isOngoing(sbn)
            } ?: false
        } catch (t: Throwable) {
            // activeNotifications throws if the listener is not bound yet.
            false
        }
    }

    private fun isOngoing(sbn: StatusBarNotification): Boolean =
        sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
}
