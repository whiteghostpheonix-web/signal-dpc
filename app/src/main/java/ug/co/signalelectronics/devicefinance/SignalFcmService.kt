package ug.co.signalelectronics.devicefinance

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SignalFcmService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "SignalDPC.FCM"
        const val ACTION_LOCK = "ug.co.signalelectronics.devicefinance.LOCK"
        const val ACTION_UNLOCK = "ug.co.signalelectronics.devicefinance.UNLOCK"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val action = data["action"] ?: return
        val reason = data["reason"] ?: "unknown"
        val commandId = data["command_id"]?.toIntOrNull()

        Log.i(TAG, "FCM received: action=$action reason=$reason id=$commandId")

        when (action) {
            "LOCK" -> handleLock(reason, commandId)
            "UNLOCK" -> handleUnlock(reason, commandId)
            "TEST" -> Log.i(TAG, "Test push received")
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM token: $token")
        // Persist locally
        getSharedPreferences("signal_dpc", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
        // Send to backend if we have IMEI
        val imei = getSharedPreferences("signal_dpc", MODE_PRIVATE).getString("imei", null)
        if (!imei.isNullOrEmpty()) {
            Thread { ApiClient.updateFcmToken(imei, token) }.start()
        }
    }

    private fun handleLock(reason: String, commandId: Int?) {
        KeepAliveService.stop(this)
        DeviceController.enterKiosk(this)
        DeviceController.lockDevice(this)

        // Bring up the lock screen activity
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("locked", true)
            putExtra("reason", reason)
        }
        startActivity(intent)

        commandId?.let { Thread { ApiClient.acknowledgeCommand(it) }.start() }
    }

    private fun handleUnlock(reason: String, commandId: Int?) {
        DeviceController.exitKiosk(this)
        KeepAliveService.start(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("locked", false)
            putExtra("reason", reason)
        }
        startActivity(intent)

        commandId?.let { Thread { ApiClient.acknowledgeCommand(it) }.start() }
    }
}
