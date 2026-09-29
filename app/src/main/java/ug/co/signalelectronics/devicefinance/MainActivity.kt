package ug.co.signalelectronics.devicefinance

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SignalDPC.Main"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val isLocked = intent.getBooleanExtra("locked", false)
        val reason = intent.getStringExtra("reason") ?: ""

        val title = findViewById<TextView>(R.id.statusTitle)
        val message = findViewById<TextView>(R.id.statusMessage)
        val action = findViewById<Button>(R.id.actionButton)

        if (isLocked) {
            title.text = "Device Locked"
            message.text = getString(R.string.lock_message)
            if (reason.isNotEmpty()) message.append("\nReason: $reason")
            action.text = "Contact Support"
            DeviceController.enterKiosk(this)
        } else {
            title.text = "Signal Electronics"
            message.text = "Device management active."
            action.text = "OK"
        }

        action.setOnClickListener {
            if (!isLocked) {
                // No-op or navigate to a settings screen
            }
        }

        initialize()
    }

    private fun initialize() {
        // Register device if not already
        val prefs = getSharedPreferences("signal_dpc", MODE_PRIVATE)
        val imei = getDeviceIdentifier()

        prefs.edit().putString("imei", imei).apply()

        // Fetch FCM token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.i(TAG, "FCM token: $token")
                prefs.edit().putString("fcm_token", token).apply()

                // Register or update
                val customerName = prefs.getString("customer_name", "Unknown") ?: "Unknown"
                val customerPhone = prefs.getString("customer_phone", "") ?: ""
                Thread {
                    ApiClient.registerDevice(imei, customerName, customerPhone, token)
                    ApiClient.heartbeat(imei)
                }.start()
            }
        }

        // Block uninstall if device owner
        DeviceController.blockUninstall(this)
    }

    private fun getDeviceIdentifier(): String {
        // Try to get IMEI; on Android 10+ this will fail for non-system apps
        // Fallback: use Android ID as identifier
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                tm.deviceId ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            } else {
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            }
        } catch (e: Exception) {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }
    }
}
