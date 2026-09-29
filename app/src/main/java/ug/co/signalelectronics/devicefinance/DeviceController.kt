package ug.co.signalelectronics.devicefinance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

object DeviceController {
    private const val TAG = "SignalDPC.DeviceController"

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, SignalDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /**
     * Lock the device. Requires device owner or admin privileges.
     */
    fun lockDevice(context: Context): Boolean {
        val dpm = dpm(context)
        return try {
            if (isDeviceOwner(context)) {
                dpm.lockNow()
                Log.i(TAG, "Device locked via DPM")
                true
            } else {
                Log.w(TAG, "Not device owner — cannot lock")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "lockDevice failed", e)
            false
        }
    }

    /**
     * Enter kiosk mode locked to our own app.
     */
    fun enterKiosk(context: Context) {
        val dpm = dpm(context)
        try {
            if (isDeviceOwner(context)) {
                dpm.setLockTaskPackages(adminComponent(context), arrayOf(context.packageName))
                Log.i(TAG, "Kiosk packages set")
            }
        } catch (e: Exception) {
            Log.e(TAG, "enterKiosk failed", e)
        }
    }

    /**
     * Exit kiosk mode.
     */
    fun exitKiosk(context: Context) {
        val dpm = dpm(context)
        try {
            if (isDeviceOwner(context)) {
                dpm.setLockTaskPackages(adminComponent(context), emptyArray())
                Log.i(TAG, "Kiosk disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "exitKiosk failed", e)
        }
    }

    /**
     * Block uninstall of this app. Only works if we're device owner.
     */
    fun blockUninstall(context: Context) {
        try {
            if (isDeviceOwner(context)) {
                dpm(context).setUninstallBlocked(adminComponent(context), context.packageName, true)
                Log.i(TAG, "Uninstall blocked for ${context.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "blockUninstall failed", e)
        }
    }
}
