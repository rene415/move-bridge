package com.renelabs.movebridge

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

/** Registers a receiver that other apps can't send to. */
@SuppressLint("InlinedApi")  // RECEIVER_NOT_EXPORTED is a compile-time constant; Android 12 and older ignore it
fun Context.registerNotExported(r: BroadcastReceiver, f: IntentFilter) {
    registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
}
