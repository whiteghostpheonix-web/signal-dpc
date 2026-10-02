package ug.co.signalelectronics.devicefinance

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class SignalDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "SignalDPC.AdminReceiver"

        // Applied at provisioning time. These are always on.
        private val BASE_RESTRICTIONS = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_DATA_ROAMING,
            UserManager.DISALLOW_CONFIG_TETHERING
        )
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — applying base restrictions")

        // Capture the provisioning extras (imei, pending_plan_id, start_date)
        // that the enrollment QR embedded in PROVISIONING_ADMIN_EXTRAS_BUNDLE.
        try {
            val extras = intent.getBundleExtra("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
            if (extras != null) {
                val prefs = context.getSharedPreferences("signal_dpc", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                for (key in extras.keySet()) {
                    val value = extras.getString(key)
                    if (value != null) {
                        editor.putString("provisioning_$key", value)
                        Log.i(TAG, "Provisioning extra: $key = $value")
                    }
                }
                editor.apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read provisioning extras", e)
        }

        applyBaseRestrictions(context)
        DeviceController.blockUninstall(context)
        KeepAliveService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "Lock task entering for $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "Lock task exiting")
    }

    private fun applyBaseRestrictions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SignalDeviceAdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner — skipping base restrictions")
            return
        }

        for (r in BASE_RESTRICTIONS) {
            try {
                dpm.addUserRestriction(admin, r)
                Log.i(TAG, "Applied base restriction: $r")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply $r: ${e.message}")
            }
        }

        // Block status bar only when locked — leave it enabled by default
        try {
            dpm.setStatusBarDisabled(admin, false)
        } catch (_: Exception) {}

        // Enable lock task package whitelisting but don't enter lock task yet
        try {
            dpm.setLockTaskPackages(admin, emptyArray())
        } catch (_: Exception) {}

        Log.i(TAG, "Base restrictions applied: ${BASE_RESTRICTIONS.size}")
    }
}
