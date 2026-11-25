package com.helper.shizukuautostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the helper after the phone boots (and after the helper itself updates). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = context.getSharedPreferences("helper", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return
        Log.i("ShizukuHelper", "boot/replace: starting helper service")
        runCatching { RestartService.start(context) }
            .onFailure { Log.w("ShizukuHelper", "could not start helper service", it) }
    }
}
