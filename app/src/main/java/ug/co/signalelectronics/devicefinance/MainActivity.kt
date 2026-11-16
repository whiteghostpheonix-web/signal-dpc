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

        // A payment return URL is not proof of payment. Refresh the account
        // from the backend and only report that the status was checked.
        if (intent?.data?.scheme == "signaldpc") {
            refreshAfterPaymentReturn()
        }

        isLocked = intent.getBooleanExtra("locked", false)
            || getSharedPreferences("signal_dpc", MODE_PRIVATE).getBoolean("was_locked", false)
        val reason = intent.getStringExtra("reason") ?: ""

        val title = findViewById<TextView>(R.id.statusTitle)
        val message = findViewById<TextView>(R.id.statusMessage)
        val payBtn = findViewById<Button>(R.id.payButton)
        val supportBtn = findViewById<Button>(R.id.supportButton)

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
                putExtra("amount", getSharedPreferences("signal_dpc", MODE_PRIVATE).getLong("next_due_amount_ugx", 0L).toString())
                putExtra("intent_id", "")
            }
            startActivity(i)
        }

        // Support button — dial the number
        supportBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_NUMBER"))
            startActivity(i)
        }


        initialize()
        renderDashboard()
    }


    private fun refreshAfterPaymentReturn() {
        Thread {
            val prefs = getSharedPreferences("signal_dpc", MODE_PRIVATE)
            val imei = prefs.getString("imei", null)
            val refreshed = !imei.isNullOrBlank() && ApiClient.heartbeat(this, imei)
            runOnUiThread {
                if (refreshed) {
                    applyVerifiedServerState()
                    UpdateChecker.checkAndUpdate(this@MainActivity)
                }
                renderDashboard()
                val message = if (refreshed) "Account status refreshed from Signal Electronics." else "Payment status is still being checked. Please check again shortly or contact support."
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme == "signaldpc") refreshAfterPaymentReturn()
    }

    private fun applyVerifiedServerState() {
        val prefs = getSharedPreferences("signal_dpc", MODE_PRIVATE)
        val serverStatus = prefs.getString("server_device_status", "active") ?: "active"
        if (serverStatus == "active" && prefs.getBoolean("unlock_authorized", false)) {
            isLocked = false
            prefs.edit().putBoolean("was_locked", false).apply()
            try { KioskController.exitFullKiosk(this); KioskController.stopLockTask(this) } catch (_: Exception) {}
        } else if (serverStatus == "locked") {
            isLocked = true
            prefs.edit().putBoolean("was_locked", true).apply()
            try { KioskController.enterFullKiosk(this) } catch (_: Exception) {}
            try { KioskController.startLockTask(this) } catch (_: Exception) {}
        }
    }

    private fun renderDashboard() {
        val prefs = getSharedPreferences("signal_dpc", MODE_PRIVATE)
        val title = findViewById<TextView>(R.id.statusTitle) ?: return
        val message = findViewById<TextView>(R.id.statusMessage) ?: return
        val planAmount = findViewById<TextView>(R.id.planAmount)
        val planSummary = findViewById<TextView>(R.id.planSummary)
        val balance = prefs.getLong("outstanding_balance_ugx", -1L)
        val nextAmount = prefs.getLong("next_due_amount_ugx", 0L)
        val dueDate = prefs.getString("next_due_date", "") ?: ""
        val serverStatus = prefs.getString("server_device_status", "unknown") ?: "unknown"
        val money = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "UG")).format(maxOf(0L, balance))
        title.text = if (serverStatus == "locked" || isLocked) "Device Restricted" else "Device Protected"
        message.text = when {
            serverStatus == "locked" || isLocked -> "Your device has a payment restriction. Make the required payment below; access is restored after Signal Electronics verifies it."
            balance < 0 -> "Connecting securely to Signal Electronics to load your finance plan."
            balance == 0L -> "Your device finance balance is clear. Your device management protection remains active."
            else -> "Outstanding balance: UGX $money\nNext due: ${if (dueDate.isBlank()) "Not scheduled" else dueDate}"
        }
        planAmount?.text = if (balance < 0) "Loading plan…" else "UGX $money"
        planSummary?.text = if (balance < 0) "Waiting for a secure account refresh." else if (balance == 0L) "No outstanding installments." else "Next payment: UGX ${java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "UG")).format(nextAmount)}${if (dueDate.isBlank()) "" else " · Due $dueDate"}"
        val securityStatus = findViewById<TextView>(R.id.securityStatus)
        val approvedAppsText = findViewById<TextView>(R.id.approvedAppsText)
        val security = try { org.json.JSONObject(prefs.getString("security_config", "{}")) } catch (_: Exception) { org.json.JSONObject() }
        val knox = security.optJSONObject("knox") ?: org.json.JSONObject()
        val transsion = security.optJSONObject("transsion") ?: org.json.JSONObject()
        val knoxState = if (knox.optBoolean("configured", false)) "configured" else "awaiting official vendor credentials/support"
        val transsionState = if (transsion.optBoolean("configured", false)) "configured" else "awaiting official vendor credentials/support"
        securityStatus?.text = "Samsung Knox: $knoxState\nTranssion: $transsionState\nBase Android device protections: ${if (KioskController.isDeviceOwner(this)) "Device Owner active" else "Device Owner not active"}"
        val approved = try { org.json.JSONArray(prefs.getString("allowed_apps", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        val appNames = (0 until approved.length()).map { approved.optString(it) }.filter { it.isNotBlank() }
        approvedAppsText?.text = if (appNames.isEmpty()) "Approved apps will appear after the device receives its policy." else "Allowed apps: " + appNames.joinToString(", ")

        val history = findViewById<android.widget.LinearLayout>(R.id.historyContainer)
        history?.removeAllViews()
        val rows = try { org.json.JSONArray(prefs.getString("payment_history", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        if (rows.length() == 0) {
            val empty = TextView(this).apply { text = "No confirmed payments yet"; textSize = 13f; setTextColor(android.graphics.Color.DKGRAY) }
            history?.addView(empty)
        } else {
            for (i in 0 until minOf(rows.length(), 5)) {
                val item = rows.getJSONObject(i)
                val amount = item.optLong("amount_ugx", 0L)
                val ref = item.optString("reference", "")
                val row = TextView(this).apply {
                    text = "UGX ${java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "UG")).format(amount)}  ·  ${item.optString("paid_at", "")}\n$ref"
                    textSize = 12f
                    setTextColor(android.graphics.Color.DKGRAY)
                    setPadding(0, 8, 0, 8)
                }
                history?.addView(row)
            }
        }
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

        val enrollmentToken = readProvisioningExtra("enrollment_token")
        if (!enrollmentToken.isNullOrBlank() && enrollmentToken.matches(Regex("[a-fA-F0-9]{64}"))) {
            try {
                DeviceCredentialStore.save(this, enrollmentToken.lowercase())
                prefs.edit().remove("provisioning_enrollment_token").apply()
            } catch (e: Exception) {
                Log.e(TAG, "Could not secure device enrollment credential", e)
            }
        }
        Thread {
            val ok = ApiClient.heartbeat(this@MainActivity, currentImei)
            runOnUiThread {
                if (ok) {
                    applyVerifiedServerState()
                    UpdateChecker.checkAndUpdate(this@MainActivity)
                }
                renderDashboard()
            }
        }.start()

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                prefs.edit().putString("fcm_token", token).apply()
                Thread {
                    val ok = ApiClient.heartbeat(this@MainActivity, currentImei, token)
                    runOnUiThread {
                        if (ok) {
                            applyVerifiedServerState()
                            UpdateChecker.checkAndUpdate(this@MainActivity)
                        }
                        renderDashboard()
                    }
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
