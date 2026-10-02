package ug.co.signalelectronics.devicefinance

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        private const val SUPPORT_NUMBER = "+256706352822"
    }

    private var isLocked = false
    private var currentImei = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        isLocked = intent.getBooleanExtra("locked", false)
            || getSharedPreferences("signal_dpc", MODE_PRIVATE).getBoolean("was_locked", false)
        val reason = intent.getStringExtra("reason") ?: ""

        val title = findViewById<TextView>(R.id.statusTitle)
        val message = findViewById<TextView>(R.id.statusMessage)
        val payBtn = findViewById<Button>(R.id.payButton)
        val supportBtn = findViewById<Button>(R.id.supportButton)
        val actionBtn = findViewById<Button>(R.id.actionButton)

        if (isLocked) {
            title.text = "Device Locked"
            message.text = getString(R.string.lock_message)
            if (reason.isNotEmpty()) message.append("\nReason: $reason")

            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )

            KioskController.startLockTask(this)
        } else {
            title.text = "Signal Electronics"

            val owner = KioskController.isDeviceOwner(this)
            val restrictions = KioskController.activeRestrictions(this)
            message.text = buildString {
                append("Device management active.\n\n")
                append("Device Owner: ")
                append(if (owner) "✅ YES" else "❌ NO")
                append("  •  Restrictions: ")
                append(restrictions.size)
            }

            try { KioskController.stopLockTask(this) } catch (_: Exception) {}
        }

        // Pay button
        payBtn.setOnClickListener {
            val i = Intent(this, PaymentActivity::class.java).apply {
                putExtra("imei", currentImei)
                putExtra("amount", "0")
                putExtra("intent_id", "")
            }
            startActivity(i)
        }

        // Support button — dial the number
        supportBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_NUMBER"))
            startActivity(i)
        }

        // Action button
        actionBtn.setOnClickListener {
            if (!isLocked) {
                // No-op
            }
        }

        initialize()
    }

    override fun onBackPressed() {
        if (isLocked) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // Enforce cached schedule on every app open — works offline
        try { ScheduleEnforcer.enforceFromCache(this) } catch (_: Exception) {}
        if (isLocked) {
            try { KioskController.startLockTask(this) } catch (_: Exception) {}
        }
    }

    private fun initialize() {
        val prefs = getSharedPreferences("signal_dpc", MODE_PRIVATE)
        currentImei = getDeviceIdentifier()
        prefs.edit().putString("imei", currentImei).apply()

        // Read provisioning extras that came from the enrollment QR
        // (imei hint + pending_plan_id + start_date baked in by the dashboard)
        val pendingImei = readProvisioningExtra("imei")
        val pendingPlanId = readProvisioningExtra("pending_plan_id")
        val startDate = readProvisioningExtra("start_date")

        // Persist for later use
        if (!pendingImei.isNullOrEmpty()) prefs.edit().putString("pending_imei", pendingImei).apply()
        if (!pendingPlanId.isNullOrEmpty()) prefs.edit().putString("pending_plan_id", pendingPlanId).apply()
        if (!startDate.isNullOrEmpty()) prefs.edit().putString("pending_start_date", startDate).apply()

        val effectiveImei = if (!pendingImei.isNullOrEmpty()) pendingImei else currentImei
        if (!pendingImei.isNullOrEmpty()) {
            currentImei = pendingImei
            prefs.edit().putString("imei", currentImei).apply()
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                prefs.edit().putString("fcm_token", token).apply()
                val customerName = prefs.getString("customer_name", "Unknown") ?: "Unknown"
                val customerPhone = prefs.getString("customer_phone", "") ?: ""
                Thread {
                    ApiClient.registerDevice(
                        currentImei,
                        customerName,
                        customerPhone,
                        token,
                        prefs.getString("pending_plan_id", null),
                        prefs.getString("pending_start_date", null)
                    )
                    // Clear the pending flag once sent
                    prefs.edit().remove("pending_plan_id").remove("pending_start_date").apply()
                    ApiClient.heartbeat(this@MainActivity, currentImei)
                }.start()
            }
        }

        DeviceController.blockUninstall(this)

        // Ensure base restrictions are applied even if onEnabled fired before
        if (KioskController.isDeviceOwner(this)) {
            try { applyBaseRestrictions() } catch (_: Exception) {}
        }

        if (!isLocked) {
            KeepAliveService.start(this)
        }
    }

    /**
     * Read a value the enrollment QR placed in the Device Owner
     * PROVISIONING_ADMIN_EXTRAS_BUNDLE. Returns null if not set.
     */
    private fun readProvisioningExtra(key: String): String? {
        // The DeviceAdminReceiver stores provisioning extras to SharedPreferences
        // under the key "provisioning_<original_key>" when the device is provisioned.
        return try {
            getSharedPreferences("signal_dpc", MODE_PRIVATE)
                .getString("provisioning_$key", null)
        } catch (e: Exception) {
            android.util.Log.w("SignalDPC.Main", "readProvisioningExtra($key) failed", e)
            null
        }
    }


    private fun applyBaseRestrictions() {
        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(this, SignalDeviceAdminReceiver::class.java)

        val restrictions = listOf(
            android.os.UserManager.DISALLOW_FACTORY_RESET,
            android.os.UserManager.DISALLOW_SAFE_BOOT,
            android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
            android.os.UserManager.DISALLOW_ADD_USER,
            android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS,
            android.os.UserManager.DISALLOW_UNINSTALL_APPS,
            android.os.UserManager.DISALLOW_APPS_CONTROL,
            android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            android.os.UserManager.DISALLOW_CONFIG_DATE_TIME,
            android.os.UserManager.DISALLOW_NETWORK_RESET,
            android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            android.os.UserManager.DISALLOW_DATA_ROAMING,
            android.os.UserManager.DISALLOW_CONFIG_TETHERING
        )

        for (r in restrictions) {
            try { dpm.addUserRestriction(admin, r) } catch (_: Exception) {}
        }
    }

    private fun getDeviceIdentifier(): String {
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
