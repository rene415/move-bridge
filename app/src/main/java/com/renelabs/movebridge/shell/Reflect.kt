package com.renelabs.movebridge.shell

import android.annotation.SuppressLint

/**
 * Small reflection helpers for the shell helper. Hidden-API restrictions don't apply in a
 * shell-uid app_process, which is the only place this code runs.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
internal object Reflect {
    fun cls(name: String): Class<*> = Class.forName(name)

    fun int(cls: Class<*>, name: String): Int = cls.getField(name).getInt(null)

    fun set(target: Any?, cls: Class<*>, name: String, value: Any?) =
        cls.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    fun newInstance(cls: Class<*>, vararg args: Pair<Class<*>, Any?>): Any =
        cls.getDeclaredConstructor(*args.map { it.first }.toTypedArray())
            .apply { isAccessible = true }
            .newInstance(*args.map { it.second }.toTypedArray())

    /** Calls a (possibly hidden) method; [args] are (parameter type, value) pairs. */
    fun call(target: Any?, cls: Class<*>, name: String, vararg args: Pair<Class<*>, Any?>): Any? =
        cls.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
            .apply { isAccessible = true }
            .invoke(target, *args.map { it.second }.toTypedArray())

    /** Calls a public method, looked up through the class hierarchy (builders and proxies). */
    fun callPublic(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Any? =
        target.javaClass.getMethod(name, *args.map { it.first }.toTypedArray())
            .invoke(target, *args.map { it.second }.toTypedArray())
}
