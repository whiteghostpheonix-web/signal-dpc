package ug.co.signalelectronics.devicefinance

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    private const val TAG = "SignalDPC.Notifier"

    fun push(context: Context, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(context, SignalApp.NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                System.currentTimeMillis().toInt(),
                notif
            )
        } catch (e: SecurityException) {
            // Permission not granted; nothing else to do
        }
    }
}
