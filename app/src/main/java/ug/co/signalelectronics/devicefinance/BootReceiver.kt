package ug.co.signalelectronics.devicefinance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SignalDPC.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Boot received: ${intent.action}")

        // If device was locked before reboot, re-apply full kiosk + lock
        val wasLocked = context.getSharedPreferences("signal_dpc", Context.MODE_PRIVATE)
            .getBoolean("was_locked", false)

        if (wasLocked) {
            Log.i(TAG, "Device was locked before reboot — re-applying kiosk")
            KioskController.enterFullKiosk(context)
            DeviceController.lockDevice(context)

            // Bring our lock screen up
            val lockIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra("locked", true)
                putExtra("reason", "reboot_restore")
            }
            context.startActivity(lockIntent)
        } else {
            // Not locked — keep the foreground service alive
            KeepAliveService.start(context)
        }
    }
}
