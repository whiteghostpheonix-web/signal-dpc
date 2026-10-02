package ug.co.signalelectronics.devicefinance

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Runs every 6 hours (or however often WorkManager schedules it).
 * Enforces the cached schedule locally — no internet required.
 */
class EnforceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Log.i("SignalDPC.Worker", "EnforceWorker running")
        try {
            ScheduleEnforcer.enforceFromCache(applicationContext)
            return Result.success()
        } catch (e: Exception) {
            Log.e("SignalDPC.Worker", "Enforce failed", e)
            return Result.retry()
        }
    }
}
