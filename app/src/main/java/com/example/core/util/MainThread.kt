package com.example.core.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs a short View-related operation on the Android main thread.
 * Never blocks the main thread waiting for itself.
 */
inline fun <T> onMainBlocking(crossinline block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    return runBlocking(Dispatchers.Main.immediate) {
        withTimeout(2_000L) { block() }
    }
}

/** Posts UI work to the main thread and records failures rather than hiding them. */
fun runOnMain(block: () -> Unit) {
    val action = Runnable {
        try {
            block()
        } catch (e: Exception) {
            Log.e("Aura", "Main-thread UI operation failed", e)
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) action.run()
    else Handler(Looper.getMainLooper()).post(action)
}
