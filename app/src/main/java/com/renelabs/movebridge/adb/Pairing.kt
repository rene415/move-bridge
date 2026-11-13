package com.renelabs.movebridge.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.R

/**
 * One-time pairing with Wireless debugging, done from a notification so the user can type the
 * code while Android's "Pair device with pairing code" dialog stays open (it closes if you leave it).
 * After pairing it immediately starts the shell helper.
 */
object Pairing {
    private const val TAG = "MoveBridge"
    private const val CHANNEL = "setup"
    private const val NOTIF_ID = 2
    private const val SERVICE_TYPE = "_adb-tls-pairing._tcp"
    const val KEY_CODE = "code"
    private const val TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile private var host: String? = null
    @Volatile private var port = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())

    /** Opens Developer options (scrolled to Wireless debugging where supported). */
    fun developerSettingsIntent() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts listening for the pairing dialog and shows the instructions notification. */
    fun begin(c: Context) {
        val app = c.applicationContext
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.setup_channel), NotificationManager.IMPORTANCE_HIGH))
        host = null; port = 0
        notify(app, app.getString(R.string.pair_waiting_title), app.getString(R.string.pair_waiting_body), input = false)
        discover(app)
        main.removeCallbacksAndMessages(this)
        main.postAtTime({ stopDiscovery(app) }, this, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
    }

    private fun discover(app: Context) {
        stopDiscovery(app)
        val nsd = app.getSystemService(NsdManager::class.java)
        val l = object : NsdManager.DiscoveryListener {
            @Suppress("DEPRECATION")
            override fun onServiceFound(info: NsdServiceInfo) = nsd.resolveService(info, resolver(app))
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "pair discovery failed $error") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    /** The pairing dialog was found: remember where it is and ask for the code. */
    private fun resolver(app: Context) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(i: NsdServiceInfo, error: Int) { Log.w(TAG, "pair resolve failed $error") }
        override fun onServiceResolved(i: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            host = i.host?.hostAddress ?: "127.0.0.1"
            port = i.port
            Log.i(TAG, "pairing service at $host:$port")
            notify(app, app.getString(R.string.pair_found_title), app.getString(R.string.pair_found_body), input = true)
        }
    }

    private fun stopDiscovery(app: Context) {
        listener?.let { l -> runCatching { app.getSystemService(NsdManager::class.java).stopServiceDiscovery(l) } }
        listener = null
    }

    /** The user typed the code into the notification. Runs the pairing and starts the helper. */
    internal fun onCode(app: Context, code: String, finished: () -> Unit) {
        val h = host
        val p = port
        if (h == null || p == 0) {
            notify(app, app.getString(R.string.pair_failed_title), app.getString(R.string.pair_waiting_body), input = false)
            return finished()
        }
        notify(app, app.getString(R.string.pair_working), "", input = false)
        Thread {
            if (pairWith(app, h, p, code)) startAfterPairing(app)
            finished()
        }.start()
    }

    private fun pairWith(app: Context, h: String, p: Int, code: String): Boolean {
        val paired = runCatching { ShellStarter.pair(app, h, p, code) }
            .onFailure { Log.e(TAG, "pairing failed", it) }.getOrDefault(false)
        if (!paired) notify(app, app.getString(R.string.pair_failed_title), app.getString(R.string.pair_failed_body), input = true)
        return paired
    }

    private fun startAfterPairing(app: Context) {
        Prefs.setAdbPaired(app, true)
        main.post { stopDiscovery(app) }
        notify(app, app.getString(R.string.pair_starting), "", input = false)
        val r = ShellStarter.start(app, grantSecureSettings = Prefs.autoRestart(app))
        if (r is ShellStarter.Result.Started)
            notify(app, app.getString(R.string.pair_done_title), app.getString(R.string.pair_done_body), input = false, done = true)
        else notify(app, app.getString(R.string.pair_start_failed), r.toString(), input = false, done = true)
    }

    private fun notify(app: Context, title: String, body: String, input: Boolean, done: Boolean = false) {
        val b = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setColor(app.getColor(R.color.accent))
            .setOnlyAlertOnce(!input)
            .setOngoing(!done)
            .setAutoCancel(done)
        if (input) {
            val pi = PendingIntent.getBroadcast(app, 0, Intent(app, PairingReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val ri = RemoteInput.Builder(KEY_CODE).setLabel(app.getString(R.string.pair_code_label)).build()
            b.addAction(Notification.Action.Builder(null, app.getString(R.string.pair_enter_code), pi).addRemoteInput(ri).build())
        }
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
    }
}

/** Receives the pairing code typed into the setup notification. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Pairing.KEY_CODE)?.toString()
            ?.filter { it.isDigit() } ?: return
        val pending = goAsync()
        Pairing.onCode(c.applicationContext, code) { pending.finish() }
    }
}
