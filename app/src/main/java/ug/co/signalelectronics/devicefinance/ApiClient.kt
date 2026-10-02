package ug.co.signalelectronics.devicefinance

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
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun cfHeaders(): Map<String, String> = mapOf(
        "CF-Access-Client-Id" to SignalApp.CF_ACCESS_CLIENT_ID,
        "CF-Access-Client-Secret" to SignalApp.CF_ACCESS_CLIENT_SECRET
    )

    /**
     * Register this device with the backend. Called on first launch.
     */
    fun registerDevice(
        imei: String,
        customerName: String,
        customerPhone: String,
        fcmToken: String?,
        pendingPlanId: String? = null,
        startDate: String? = null
    ): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("customer_name", customerName)
                put("customer_phone", customerPhone)
                if (!fcmToken.isNullOrEmpty()) put("fcm_token", fcmToken)
                if (!pendingPlanId.isNullOrEmpty()) put("pending_plan_id", pendingPlanId.toIntOrNull() ?: pendingPlanId)
                if (!startDate.isNullOrEmpty()) put("start_date", startDate)
            }.toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/devices")
                .apply { cfHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                Log.i(TAG, "registerDevice -> ${resp.code} $ok")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice failed", e)
            false
        }
    }

    /**
     * Send FCM token update.
     */
    fun updateFcmToken(imei: String, fcmToken: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("fcm_token", fcmToken)
            }.toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/devices/$imei/fcm")
                .apply { cfHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .put(body)
                .build()

            client.newCall(req).execute().use { resp ->
                Log.i(TAG, "updateFcmToken -> ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateFcmToken failed", e)
            false
        }
    }

    /**
     * Acknowledge a command after executing it.
     */
    fun acknowledgeCommand(commandId: Int): Boolean {
        return try {
            val body = JSONObject().toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/device/ack")
                .apply { cfHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(JSONObject().apply {
                    put("command_id", commandId)
                }.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { resp ->
                Log.i(TAG, "acknowledgeCommand($commandId) -> ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "acknowledgeCommand failed", e)
            false
        }
    }

    /**
     * Send a heartbeat — useful for offline detection later.
     */
    fun heartbeat(context: android.content.Context, imei: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("sdk", Build.VERSION.SDK_INT)
            }.toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/devices/$imei/heartbeat")
                .apply { cfHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false

                val respBody = resp.body?.string() ?: return@use false
                val json = JSONObject(respBody)

                // Cache the schedule so local enforcement works offline
                val schedule = json.optJSONArray("schedule")
                val deviceStatus = json.optString("device_status", "active")
                if (schedule != null) {
                    ScheduleEnforcer.cacheSchedule(context, schedule.toString(), deviceStatus)
                    Log.i(TAG, "Cached ${schedule.length()} schedule entries, status=$deviceStatus")
                }

                // Immediately enforce (in case we just came online and are overdue)
                ScheduleEnforcer.enforceFromCache(context)

                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed", e)
            false
        }
    }
}
