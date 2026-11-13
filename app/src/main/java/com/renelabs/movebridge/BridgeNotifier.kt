package com.renelabs.movebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.widget.RemoteViews

/** The service's ongoing notification and its foreground-service type. */
class BridgeNotifier(private val svc: Service) {
    private val nm = svc.getSystemService(NotificationManager::class.java)
    private val stopPi = PendingIntent.getService(svc, 0,
        BridgeService.intent(svc, BridgeService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    private val openPi = PendingIntent.getActivity(svc, 0,
        Intent(svc, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, svc.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    }

    /** [projection]: MediaProjection capture is open. [usb]: the Move is (being) connected. */
    fun goForeground(projection: Boolean, usb: Boolean) {
        var type = 0
        if (projection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (usb || type == 0) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        svc.startForeground(ID, build(svc.getString(R.string.app_name), R.string.btn_stop), type)
    }

    /** [stopLabel]: what the Stop button says, e.g. "Stop sending audio". */
    fun show(text: String, stopLabel: Int = R.string.btn_stop) = nm.notify(ID, build(text, stopLabel))

    fun remove() = svc.stopForeground(Service.STOP_FOREGROUND_REMOVE)

    /**
     * Custom body with a labeled Stop button that shows without expanding (a plain low-importance
     * notification hides its buttons until expanded). Two layouts, because a collapsed
     * notification only has room for about one line: there the button is the whole body; expanded,
     * the status line sits above it.
     */
    private fun build(text: String, stopLabel: Int): Notification =
        Notification.Builder(svc, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)  // for accessibility and the lock screen
            .setContentIntent(openPi)
            .setOngoing(true)
            .setColor(svc.getColor(R.color.accent))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(body(R.layout.notif_bridge_small, text, stopLabel))
            .setCustomBigContentView(body(R.layout.notif_bridge, text, stopLabel))
            .build()

    private fun body(layout: Int, text: String, stopLabel: Int) = RemoteViews(svc.packageName, layout).apply {
        if (layout == R.layout.notif_bridge) setTextViewText(R.id.notifTitle, text)
        setTextViewText(R.id.notifStop, svc.getString(stopLabel))
        setContentDescription(R.id.notifStop, text + ". " + svc.getString(stopLabel))
        setOnClickPendingIntent(R.id.notifStop, stopPi)
    }

    private companion object {
        const val CHANNEL = "bridge"
        const val ID = 1
    }
}
