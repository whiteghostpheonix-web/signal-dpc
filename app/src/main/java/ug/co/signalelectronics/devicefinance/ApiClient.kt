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

    /**
     * Register this device with the backend. Called on first launch.
     */
    fun registerDevice(imei: String, customerName: String, customerPhone: String, fcmToken: String?): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("customer_name", customerName)
                put("customer_phone", customerPhone)
                if (!fcmToken.isNullOrEmpty()) put("fcm_token", fcmToken)
            }.toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/devices")
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
                .url("${SignalApp.API_BASE}/api/commands/$commandId/acknowledge")
                .post(body)
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
    fun heartbeat(imei: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("imei", imei)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("sdk", Build.VERSION.SDK_INT)
            }.toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${SignalApp.API_BASE}/api/devices/$imei/heartbeat")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed", e)
            false
        }
    }
}
