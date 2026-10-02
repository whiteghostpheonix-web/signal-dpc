package ug.co.signalelectronics.devicefinance

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SignalApp : Application() {
    companion object {
        const val TAG = "SignalDPC"
        const val NOTIF_CHANNEL_ID = "device_status"
        const val API_BASE = "https://device-finance-api.whiteghostpheonix.workers.dev"

        // Cloudflare Access Service Token — Android app
        const val CF_ACCESS_CLIENT_ID = "d188c41a7b853686013b955ee9094796.access"
        const val CF_ACCESS_CLIENT_SECRET = "cfast_REiYmDg61wcFo8BVPmAFHJ9AduQftvR1wMomA34V9acde9d0"

        const val WORK_ENFORCE = "signal_enforce_work"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SignalApp started")
        createNotificationChannel()
        scheduleEnforcement()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun scheduleEnforcement() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)  // runs offline too
            .build()

        val request = PeriodicWorkRequestBuilder<EnforceWorker>(
            6, TimeUnit.HOURS,
            15, TimeUnit.MINUTES  // flex
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_ENFORCE,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        Log.i(TAG, "Enforcement work scheduled every 6 hours")
    }
}
