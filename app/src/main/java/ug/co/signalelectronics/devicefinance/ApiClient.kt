package ug.co.signalelectronics.devicefinance

import android.content.Context
import android.net.Uri
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
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun deviceRequest(context: Context, url: String, imei: String, body: JSONObject): Request? {
        val token = DpcCredentialStore.load(context) ?: return null
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Signal-Device", imei)
            .post(body.toString().toRequestBody(JSON)).build()
    }

    /** Exchanges the one-time provisioning credential for a device-specific bearer token. */
    fun enrollDevice(context: Context, imei: String, enrollmentToken: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("enrollment_token", enrollmentToken)
                put("dpc_version", BuildConfig.VERSION_NAME)
            }
            val request = Request.Builder().url("${SignalApp.API_BASE}/dpc/enroll")
                .post(body.toString().toRequestBody(JSON)).build()
            client.newCall(request).execute().use { response ->
                val payload = JSONObject(response.body?.string().orEmpty())
                val token = payload.optString("device_token")
                if (!response.isSuccessful || token.isBlank()) {
                    Log.e(TAG, "enrollDevice failed: HTTP ${response.code}")
                    return false
                }
                DpcCredentialStore.save(context, token)
                Log.i(TAG, "Device enrollment completed")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "enrollDevice failed", e)
            false
        }
    }

    fun registerDevice(context: Context, imei: String, fcmToken: String?): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                if (!fcmToken.isNullOrBlank()) put("fcm_token", fcmToken)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sdk", Build.VERSION.SDK_INT)
                put("dpc_version", BuildConfig.VERSION_NAME)
            }
            val request = deviceRequest(context, "${SignalApp.API_BASE}/dpc/register", imei, body)
                ?: return false
            client.newCall(request).execute().use { response ->
                Log.i(TAG, "registerDevice -> ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice failed", e)
            false
        }
    }

    fun updateFcmToken(context: Context, imei: String, fcmToken: String): Boolean =
        registerDevice(context, imei, fcmToken)

    fun acknowledgeCommand(context: Context, imei: String, commandId: Int): Boolean {
        return try {
            val body = JSONObject().apply { put("imei", imei); put("command_id", commandId) }
            val request = deviceRequest(context, "${SignalApp.API_BASE}/dpc/ack", imei, body)
                ?: return false
            client.newCall(request).execute().use { response ->
                Log.i(TAG, "acknowledgeCommand($commandId) -> ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "acknowledgeCommand failed", e)
            false
        }
    }

    fun createPaymentSession(context: Context, imei: String, callback: (String?) -> Unit) {
        Thread {
            val result = try {
                val body = JSONObject().apply { put("imei", imei) }
                val request = deviceRequest(context, "${SignalApp.API_BASE}/dpc/payment-session", imei, body)
                    ?: return@Thread callback(null)
                client.newCall(request).execute().use { response ->
                    val payload = JSONObject(response.body?.string().orEmpty())
                    if (response.isSuccessful) {
                        val candidate = payload.optString("payment_url")
                        val uri = Uri.parse(candidate)
                        candidate.takeIf {
                            uri.scheme == "https" && uri.host == "device-finance-api.whiteghostpheonix.workers.dev" && uri.path == "/pay"
                        }
                    } else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "createPaymentSession failed", e)
                null
            }
            callback(result)
        }.start()
    }

    fun heartbeat(context: Context, imei: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("sdk", Build.VERSION.SDK_INT)
                put("dpc_version", BuildConfig.VERSION_NAME)
            }
            val request = deviceRequest(context, "${SignalApp.API_BASE}/dpc/heartbeat", imei, body)
                ?: return false
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val payload = JSONObject(response.body?.string().orEmpty())
                val schedule = payload.optJSONArray("schedule")
                val deviceStatus = payload.optString("device_status", "active")
                if (schedule != null) {
                    ScheduleEnforcer.cacheSchedule(context, schedule.toString(), deviceStatus)
                    ScheduleEnforcer.enforceFromCache(context)
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed", e)
            false
        }
    }
}
