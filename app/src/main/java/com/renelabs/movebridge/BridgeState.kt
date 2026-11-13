package com.renelabs.movebridge

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** What the bridge is doing right now. */
enum class Phase { IDLE, ARMED, STREAMING, TONE, ERROR }

data class BridgeState(
    val phase: Phase,
    val title: String,
    val detail: String,
    val stats: String = "",
    val level: Float = 0f,
    /** Direct mode: Android plays to the Move itself, so there is no level to show. */
    val direct: Boolean = false,
)

@Suppress("DEPRECATION")
fun usbDevice(i: Intent): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

fun UsbDevice.isMove() = vendorId == NativeBridge.MOVE_VID && productId == NativeBridge.MOVE_PID

/** True when [d] is a Move that is really plugged in right now (not just a parcel someone sent us). */
fun UsbManager.isAttachedMove(d: UsbDevice): Boolean =
    d.isMove() && deviceList.values.any { it.deviceName == d.deviceName && it.isMove() }
