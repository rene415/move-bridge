package com.renelabs.movebridge

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * After a phone restart or an app update, schedules [HelperJob] so the shell helper is back
 * before the Move is plugged in (otherwise the first plug-in has to wait for it).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.advanced(c) && Prefs.adbPaired(c)) HelperJob.schedule(c)
    }
}

/**
 * Starts the shell helper in the background once the phone is on Wi-Fi (Wireless debugging,
 * which starts the helper, only works on Wi-Fi). Does nothing unless the one-time setup is done and
 * the helper can be started without the user: Wireless debugging is on, or "Start automatically"
 * lets Move Bridge switch it on for a moment.
 */
class HelperJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // A helper that is already running hands over its binder within a second of our process
        // starting: wait for that, or we would replace a perfectly good helper.
        ShellLink.await(BINDER_WAIT_MS) { alive ->
            if (alive || !shouldStart(applicationContext)) jobFinished(params, false) else startInBackground(params)
        }
        return true
    }

    private fun startInBackground(params: JobParameters) {
        val app = applicationContext
        Thread {
            val ok = runCatching { AudioOnly.startHelperBlocking(app) }.getOrDefault(false)
            Diag.i("background helper start: $ok")
            jobFinished(params, !ok && ++tries < MAX_TRIES)
        }.start()
    }

    override fun onStopJob(params: JobParameters) = false

    /**
     * Only on the Wi-Fi network where the helper was last started: on any other network Android
     * would pop up its "allow wireless debugging on this network?" question out of nowhere.
     */
    private fun shouldStart(c: Context): Boolean {
        if (!Prefs.advanced(c) || ShellLink.alive() || AudioOnly.status(c) != AudioOnly.Status.CAN_START) return false
        val home = Prefs.homeWifi(c)
        return home == null || home == WifiId.current(c)
    }

    companion object {
        private const val JOB_ID = 1
        private const val MAX_TRIES = 3
        private const val BACKOFF_MS = 30_000L
        private const val BINDER_WAIT_MS = 1500L
        @Volatile private var tries = 0

        fun schedule(c: Context) {
            tries = 0
            val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, HelperJob::class.java))
                .setRequiredNetwork(wifi)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()
            c.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}

/**
 * A scrambled fingerprint of the Wi-Fi network the phone is on (from its gateway and DNS
 * addresses), or null when not on Wi-Fi. Only compared on this phone; it is never sent anywhere.
 */
object WifiId {
    fun current(c: Context): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        if (cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        val link = cm.getLinkProperties(net) ?: return null
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = link.dnsServers.mapNotNull { it.hostAddress }.sorted()
        return Integer.toHexString("$gateway|$dns".hashCode())
    }
}
