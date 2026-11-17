package com.phonebuttons.control.phone_volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.provider.Settings

/**
 * Restores the floating button after a reboot/quick-boot when it was enabled.
 * Starting FGS from BOOT_COMPLETED is restricted on newer Android versions, so if
 * the system refuses, we post a tap-to-restore notification instead of crashing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = context.getSharedPreferences(VolumeOverlayService.PREF, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(VolumeOverlayService.KEY_ENABLED, false)
        if (!enabled) return
        if (!Settings.canDrawOverlays(context)) return

        try {
            context.startForegroundService(Intent(context, VolumeOverlayService::class.java))
        } catch (_: SecurityException) {
            postTapToRestoreNotification(context)
        } catch (_: IllegalStateException) {
            postTapToRestoreNotification(context)
        }
    }

    private fun postTapToRestoreNotification(context: Context) {
        val channelId = "volume_overlay_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Floating volume button",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)

        val openIntent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            1,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle("Volume Buttons")
            .setContentText("Tap to restore the floating volume button")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(RestoreNotificationId, notification)
    }

    private companion object {
        const val RestoreNotificationId = 7002
    }
}
