package ug.co.signalelectronics.devicefinance

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "SignalDPC.ApiClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun deviceToken(context: Context): String? = DeviceCredentialStore.get(context)

    /** Authenticated device heartbeat. Returns true only after the server accepts the credential. */
    fun heartbeat(context: Context, imei: String, fcmToken: String? = null): Boolean {
        return try {
            val token = deviceToken(context) ?: run {
                Log.w(TAG, "No per-device enrollment credential available")
                return false
            }
            val body = JSONObject().apply {
                put("imei", imei)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("sdk", Build.VERSION.SDK_INT)
                put("dpc_version", BuildConfig.VERSION_NAME)
                if (!fcmToken.isNullOrBlank()) put("fcm_token", fcmToken)
            }.toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/device/heartbeat")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "heartbeat rejected: ${resp.code}")
                    return@use false
                }
                val json = JSONObject(resp.body?.string() ?: return@use false)
                val prefs = context.getSharedPreferences("signal_dpc", Context.MODE_PRIVATE)
                val schedule = json.optJSONArray("schedule")
                val status = json.optString("device_status", "active")
                prefs.edit()
                    .putString("server_device_status", status)
                    .putBoolean("unlock_authorized", json.optBoolean("unlock_authorized", false))
                    .putLong("outstanding_balance_ugx", json.optLong("outstanding_balance_ugx", 0L))
                    .putString("next_due_date", json.optString("next_due_date", ""))
                    .putLong("next_due_amount_ugx", json.optLong("next_due_amount_ugx", 0L))
                    .putString("allowed_apps", json.optJSONArray("allowed_apps")?.toString() ?: "[]")
                    .putString("payment_history", json.optJSONArray("payment_history")?.toString() ?: "[]")
                    .putString("security_config", json.optJSONObject("security_config")?.toString() ?: "{}")
                    .putString("update_manifest", json.optJSONObject("update")?.toString() ?: "{}")
                    .apply()
                if (schedule != null) {
                    ScheduleEnforcer.cacheSchedule(context, schedule.toString(), status)
                    ScheduleEnforcer.enforceFromCache(context)
                }
                Log.i(TAG, "Heartbeat accepted; device_status=$status")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed", e)
            false
        }
    }

    fun createPaymentSession(context: Context): String? {
        return try {
            val token = deviceToken(context) ?: return null
            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/device/payment-session")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JSONObject(resp.body?.string() ?: return@use null).optString("session_token").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPaymentSession failed", e)
            null
        }
    }

    fun updateFcmToken(context: Context, imei: String, fcmToken: String): Boolean =
        heartbeat(context, imei, fcmToken)

    /** Acknowledge only a command belonging to this authenticated device. */
    fun acknowledgeCommand(context: Context, commandId: Int): Boolean {
        return try {
            val token = deviceToken(context) ?: return false
            val body = JSONObject().put("command_id", commandId).toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/device/ack")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "acknowledgeCommand($commandId) failed", e)
            false
        }
    }
}
