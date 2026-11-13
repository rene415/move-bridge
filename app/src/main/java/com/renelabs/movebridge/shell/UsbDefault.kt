package com.renelabs.movebridge.shell

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.renelabs.movebridge.NativeBridge

/**
 * Makes Move Bridge the default app for the plugged-in Move: the same thing as ticking "Always
 * open Move Bridge when Ableton Move is connected" in Android's dialog. Without it, Android asks
 * which app to open on every plug-in whenever another app (any MIDI app) also accepts the Move.
 * Needs MANAGE_USB, which the shell user has.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object UsbDefault {
    /** False when no Move is plugged in. Throws if Android refuses. */
    fun set(pkg: String, userId: Int): Boolean {
        val um = ShellIdentity.context().getSystemService(UsbManager::class.java) ?: return false
        val move = ShellIdentity.asOrdinaryProcess {
            um.deviceList.values.firstOrNull { it.vendorId == NativeBridge.MOVE_VID && it.productId == NativeBridge.MOVE_PID }
        } ?: return false
        val service = UsbManager::class.java.getDeclaredField("mService").apply { isAccessible = true }.get(um)!!
        Reflect.callPublic(service, "setDevicePackage", UsbDevice::class.java to move,
            String::class.java to pkg, Int::class.javaPrimitiveType!! to userId)
        return true
    }
}
