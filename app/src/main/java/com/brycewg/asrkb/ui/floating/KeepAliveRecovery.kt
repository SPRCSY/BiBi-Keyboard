package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * Event-driven recovery at a visible Activity or the current IME.
 * Never starts the InputMethodService itself, records audio, or requests permissions.
 */
internal object KeepAliveRecovery {
    fun ensureRunning(context: Context, source: String) {
        try {
            if (!Prefs(context).floatingKeepAliveEnabled || FloatingKeepAliveService.isRunning) return
            FloatingKeepAliveService.start(context)
        } catch (e: RuntimeException) {
            // Android/OEM policy can reject even an otherwise eligible start. Retry only
            // on the next lifecycle event; a timer/alarm cannot grant an FGS exemption.
            Log.w("KeepAliveRecovery", "Keep-alive recovery rejected: $source", e)
            DebugLogManager.logWarning(context, "keepalive", "recovery_rejected_$source", e)
        }
    }
}
