package com.aivicventures.subtitletranslator.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Decides, before each job, how hard the engine may work. Read fresh every time (a sticky
 * broadcast and two PowerManager getters, all cheap), so plugging in or cooling down takes
 * effect on the next job.
 *
 * Saving (half the threads, greedy M2M100, no background work past the look-ahead target):
 * the app's Battery saver setting, the system Power Saver, battery at or below 15 % while not
 * charging, or the phone reporting itself hot (thermal status SEVERE). At CRITICAL and
 * above, one thread and a pause between jobs.
 *
 * Warm (thermal status MODERATE, or any saving): Whisper does less work per chunk, with no
 * thread limit yet, to put off the point where the phone slows itself down.
 */
class PowerMonitor(private val context: Context, private val batterySaverSetting: Boolean) {
    data class State(val saving: Boolean, val critical: Boolean, val reason: String, val warm: Boolean = saving) {
        /** Threads for the next job, from the user's setting. */
        fun threads(base: Int): Int = when {
            critical -> 1
            saving -> (base / 2).coerceAtLeast(1)
            else -> base
        }
    }

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun state(): State {
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && scale > 0) 100 * l / scale else -1
        } ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return evaluate(batterySaverSetting, pm.isPowerSaveMode, level, charging, thermal)
    }

    companion object {
        const val LOW_BATTERY = 15
        // PowerManager.THERMAL_STATUS_SEVERE / _CRITICAL (API 29 constants, inlined for minSdk 26).
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3
        const val THERMAL_CRITICAL = 4

        fun evaluate(setting: Boolean, systemSaver: Boolean, level: Int, charging: Boolean, thermal: Int): State {
            val critical = thermal >= THERMAL_CRITICAL
            val reason = when {
                critical -> "phone is very hot"
                thermal >= THERMAL_SEVERE -> "phone is hot"
                setting -> "battery saver setting"
                systemSaver -> "system power saver"
                level in 0..LOW_BATTERY && !charging -> "battery $level %"
                else -> ""
            }
            val saving = reason.isNotEmpty()
            return State(saving = saving, critical = critical, reason = reason, warm = saving || thermal >= THERMAL_MODERATE)
        }
    }
}
