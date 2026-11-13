package com.renelabs.movebridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: start and stop sending from the pull-down panel.
 *  - sending (or ready)          -> tap stops
 *  - Move plugged in, not sending -> tap starts, without opening the app
 *  - anything that needs a screen (no Move, a permission, the screen-share prompt) -> tap opens the app
 */
class BridgeTile : TileService() {
    private val usb by lazy { getSystemService(UsbManager::class.java) }

    override fun onStartListening() {
        BridgeService.tileListener = ::refresh
        refresh()
    }

    override fun onStopListening() {
        BridgeService.tileListener = null
    }

    override fun onClick() {
        val move = findMove()
        Diag.i("tile tapped: live=${isLive()} move=${move != null} access=${move != null && usb.hasPermission(move)}")
        when {
            isLive() -> runCatching { startService(BridgeService.intent(this, BridgeService.ACTION_STOP)) }
            move == null || !usb.hasPermission(move) -> openApp()
            // Starting from here only works when no prompt is needed: the helper is there, or capture is already open.
            else -> AudioOnly.whenKnown(this) { ok -> if (ok || BridgeService.captureOpen) start(move) else openApp() }
        }
    }

    private fun start(move: UsbDevice) {
        runCatching { startForegroundService(BridgeService.intent(this, BridgeService.ACTION_ATTACH, move)) }
            .onFailure {
                Diag.w("tile: couldn't start from the background ($it); opening the app")
                openApp()
            }
    }

    // Before Android 14 the Intent form is the only one there is; from 14 on the PendingIntent form is used.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val live = isLive()
        tile.state = if (live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(when {
            live -> R.string.tile_sending
            findMove() != null -> R.string.tile_ready
            else -> R.string.tile_no_move
        })
        tile.updateTile()
    }

    private fun findMove(): UsbDevice? = usb.deviceList.values.firstOrNull { it.isMove() }

    private fun isLive() = BridgeService.state.phase in setOf(Phase.STREAMING, Phase.TONE, Phase.ARMED)
}
