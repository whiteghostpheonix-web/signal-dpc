package ug.co.signalelectronics.devicefinance

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

object KioskController {
    private const val TAG = "SignalDPC.Kiosk"

    // Allowed apps for the customer kiosk
    private val WHITELIST = listOf(
        "ug.co.signalelectronics.devicefinance",
        "com.android.chrome",
        "com.android.browser",
        "com.android.settings",
        "com.huawei.android.settings",
        "com.samsung.android.settings",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.contacts",
        "com.android.calculator2",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.android.providers.contacts"
    )

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context) =
        ComponentName(ctx, SignalDeviceAdminReceiver::class.java)

    fun isDeviceOwner(ctx: Context): Boolean =
        dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun kioskPackages(ctx: Context): Array<String> {
        return (listOf(ctx.packageName) + WHITELIST).toTypedArray()
    }

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_NETWORK_RESET,
        UserManager.DISALLOW_CONFIG_TETHERING,
        UserManager.DISALLOW_DATA_ROAMING,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
        UserManager.DISALLOW_CREATE_WINDOWS,
        UserManager.DISALLOW_ADJUST_VOLUME
    )

    fun enterFullKiosk(ctx: Context) {
        if (!isDeviceOwner(ctx)) {
            Log.w(TAG, "Not device owner — kiosk calls no-op")
            return
        }

        try {
            val d = dpm(ctx)
            val a = admin(ctx)

            d.setLockTaskPackages(a, kioskPackages(ctx))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                d.setLockTaskFeatures(
                    a,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                        or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                        or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
                )
            }

            d.setStatusBarDisabled(a, false)

            for (r in RESTRICTIONS) {
                try {
                    d.addUserRestriction(a, r)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed restriction $r: ${e.message}")
                }
            }

            Log.i(TAG, "Whitelist kiosk enforced")
        } catch (e: Exception) {
            Log.e(TAG, "enterFullKiosk failed", e)
        }
    }

    fun exitFullKiosk(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        try {
            val d = dpm(ctx)
            val a = admin(ctx)

            d.setLockTaskPackages(a, emptyArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            d.setStatusBarDisabled(a, false)

            for (r in RESTRICTIONS) {
                try { d.clearUserRestriction(a, r) } catch (_: Exception) {}
            }

            Log.i(TAG, "Kiosk exited")
        } catch (e: Exception) {
            Log.e(TAG, "exitFullKiosk failed", e)
        }
    }

    fun startLockTask(activity: Activity) {
        try { activity.startLockTask() } catch (e: Exception) {
            Log.e(TAG, "startLockTask failed", e)
        }
    }

    fun stopLockTask(activity: Activity) {
        try { activity.stopLockTask() } catch (e: Exception) {
            Log.e(TAG, "stopLockTask failed", e)
        }
    }

    fun activeRestrictions(ctx: Context): List<String> {
        if (!isDeviceOwner(ctx)) return emptyList()
        val d = dpm(ctx)
        val a = admin(ctx)
        return RESTRICTIONS.filter { r ->
            try { d.getUserRestrictions(a).getBoolean(r, false) } catch (_: Exception) { false }
        }
    }
}
