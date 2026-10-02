package ug.co.signalelectronics.devicefinance

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ScheduleEnforcer {
    private const val TAG = "SignalDPC.Enforcer"
    private const val PREFS = "signal_dpc"
    private const val KEY_SCHEDULE = "cached_schedule"
    private const val KEY_LAST_CHECK = "last_enforce_check"

    /**
     * Store the schedule from the heartbeat response.
     */
    fun cacheSchedule(context: Context, scheduleJson: String, deviceStatus: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCHEDULE, scheduleJson)
            .putString("cached_device_status", deviceStatus)
            .apply()
    }

    /**
     * Called on every app open, every heartbeat response, and by WorkManager.
     * Decides whether the device should be locked based on the cached schedule
     * and the device's local date.
     *
     * NO INTERNET REQUIRED.
     */
    fun enforceFromCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scheduleJson = prefs.getString(KEY_SCHEDULE, null)

        if (scheduleJson.isNullOrEmpty()) {
            Log.i(TAG, "No cached schedule — nothing to enforce")
            return
        }

        try {
            val schedule = JSONArray(scheduleJson)
            val today = LocalDate.now()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE

            var anyOverdue = false
            var firstOverdueInfo = ""

            for (i in 0 until schedule.length()) {
                val item = schedule.getJSONObject(i)
                val status = item.optString("status", "pending")
                if (status == "paid") continue

                val dueDateStr = item.optString("due_date", "")
                if (dueDateStr.isEmpty()) continue

                val dueDate = LocalDate.parse(dueDateStr, fmt)
                val graceDays = item.optInt("grace_days", 3)
                val lockDate = dueDate.plusDays(graceDays.toLong())

                if (today.isAfter(lockDate) || today.isEqual(lockDate)) {
                    anyOverdue = true
                    val installNum = item.optInt("installment_number", 0)
                    val amount = item.optLong("amount_due_ugx", 0)
                    firstOverdueInfo = "Installment #$installNum overdue since $dueDateStr"
                    break
                }
            }

            val currentlyLocked = prefs.getString("last_lock_state", "unknown") == "locked"
            val isDeviceOwner = KioskController.isDeviceOwner(context)

            Log.i(TAG, "enforceFromCache: anyOverdue=$anyOverdue currentlyLocked=$currentlyLocked isDeviceOwner=$isDeviceOwner")

            when {
                anyOverdue && !currentlyLocked -> {
                    // Self-lock: no server call needed
                    if (isDeviceOwner) {
                        KioskController.enterFullKiosk(context)
                        DeviceController.lockDevice(context)
                    }
                    prefs.edit().putString("last_lock_state", "locked").apply()

                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("locked", true)
                        putExtra("reason", "schedule_overdue_local")
                        putExtra("overdue_info", firstOverdueInfo)
                    }
                    context.startActivity(intent)

                    Log.i(TAG, "Self-locked: $firstOverdueInfo")
                }

                !anyOverdue && currentlyLocked -> {
                    // Server said we're clear, exit kiosk
                    if (isDeviceOwner) {
                        KioskController.exitFullKiosk(context)
                    }
                    prefs.edit().putString("last_lock_state", "active").apply()

                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("locked", false)
                        putExtra("reason", "schedule_cleared_local")
                    }
                    context.startActivity(intent)

                    Log.i(TAG, "Self-unlocked: schedule clear")
                }
            }

            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "enforceFromCache failed", e)
        }
    }
}
