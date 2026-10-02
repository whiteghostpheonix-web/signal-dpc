package ug.co.signalelectronics.devicefinance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

object DeviceController {
    private const val TAG = "SignalDPC.DeviceController"

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, SignalDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

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

    fun blockUninstall(context: Context) {
        try {
            if (isDeviceOwner(context)) {
                dpm(context).setUninstallBlocked(adminComponent(context), context.packageName, true)
                Log.i(TAG, "Uninstall blocked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "blockUninstall failed", e)
        }
    }
}
