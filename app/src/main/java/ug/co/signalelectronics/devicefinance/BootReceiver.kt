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
        KeepAliveService.start(context)

        // If device was locked before reboot, re-lock
        val wasLocked = context.getSharedPreferences("signal_dpc", Context.MODE_PRIVATE)
            .getBoolean("was_locked", false)
        if (wasLocked) {
            DeviceController.enterKiosk(context)
            DeviceController.lockDevice(context)
        }
    }
}
