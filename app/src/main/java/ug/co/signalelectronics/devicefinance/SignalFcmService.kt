package ug.co.signalelectronics.devicefinance

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SignalFcmService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "SignalDPC.FCM"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val action = data["action"] ?: return
        val reason = data["reason"] ?: "unknown"
        val commandId = data["command_id"]?.toIntOrNull()

        Log.i(TAG, "FCM received: action=$action reason=$reason id=$commandId")

        when (action) {
            "LOCK" -> {
                Notifier.push(this, "LOCK command received", "reason=$reason")
                handleLock(reason, commandId)
            }
            "UNLOCK" -> {
                Notifier.push(this, "UNLOCK command received", "reason=$reason")
                handleUnlock(reason, commandId)
            }
            "REFRESH_POLICY" -> {
                val imei = getSharedPreferences("signal_dpc", MODE_PRIVATE).getString("imei", null)
                if (!imei.isNullOrBlank()) Thread {
                    val ok = ApiClient.heartbeat(this, imei)
                    if (ok && getSharedPreferences("signal_dpc", MODE_PRIVATE).getBoolean("was_locked", false)) {
                        KioskController.enterFullKiosk(this)
                    }
                    commandId?.let { ApiClient.acknowledgeCommand(this, it) }
                }.start()
            }
            "TEST" -> {
                Notifier.push(this, "TEST push received", "reason=$reason")
            }
            else -> {
                Notifier.push(this, "FCM: unknown action", "action=$action")
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("signal_dpc", MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        val imei = getSharedPreferences("signal_dpc", MODE_PRIVATE).getString("imei", null)
        if (!imei.isNullOrEmpty()) {
            Thread { ApiClient.updateFcmToken(this, imei, token) }.start()
        }
    }

    private fun handleLock(reason: String, commandId: Int?) {
        // Persist lock state so BootReceiver can re-apply after reboot
        getSharedPreferences("signal_dpc", MODE_PRIVATE)
            .edit().putBoolean("was_locked", true).apply()

        KeepAliveService.stop(this)

        // Apply full kiosk restrictions
        KioskController.enterFullKiosk(this)

        // Lock the screen
        DeviceController.lockDevice(this)

        // Bring our lock screen activity to the foreground
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra("locked", true)
            putExtra("reason", reason)
        }
        startActivity(intent)

        commandId?.let { Thread { ApiClient.acknowledgeCommand(this, it) }.start() }
    }

    private fun handleUnlock(reason: String, commandId: Int?) {
        val prefs = getSharedPreferences("signal_dpc", MODE_PRIVATE)
        val imei = prefs.getString("imei", null)
        if (imei.isNullOrBlank()) {
            Log.w(TAG, "Ignoring unlock: device identifier unavailable")
            return
        }
        Thread {
            val refreshed = ApiClient.heartbeat(this, imei)
            val current = getSharedPreferences("signal_dpc", MODE_PRIVATE)
            val authorized = refreshed
                && current.getString("server_device_status", "locked") == "active"
                && current.getBoolean("unlock_authorized", false)
            if (!authorized) {
                Log.w(TAG, "Unlock command held: backend has not authorized device access")
                return@Thread
            }
            current.edit().putBoolean("was_locked", false).apply()
            KioskController.exitFullKiosk(this)
            KeepAliveService.start(this)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("locked", false)
                putExtra("reason", reason)
            }
            startActivity(intent)
            commandId?.let { ApiClient.acknowledgeCommand(this, it) }
        }.start()
    }
}
