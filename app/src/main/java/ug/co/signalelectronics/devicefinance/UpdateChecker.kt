package ug.co.signalelectronics.devicefinance

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val TAG = "SignalDPC.Update"
    private const val SESSION_ID = 1

    fun checkAndUpdate(context: Context) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .build()

                val req = Request.Builder()
                    .url("${SignalApp.API_BASE}/api/apk/latest")
                    .build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: return@Thread
                val json = JSONObject(body)

                val remoteVersion = json.optString("version", "")
                val apkUrl = json.optString("apk_url", "")
                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName

                Log.i(TAG, "Current=$currentVersion  Remote=$remoteVersion")

                if (remoteVersion.isNotEmpty() && remoteVersion != currentVersion && apkUrl.isNotEmpty()) {
                    downloadAndInstall(context, apkUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
            }
        }.start()
    }

    private fun downloadAndInstall(context: Context, apkUrl: String) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val req = Request.Builder().url(apkUrl).build()
            val resp = client.newCall(req).execute()
            val apkFile = File(context.cacheDir, "update.apk")
            apkFile.outputStream().use { out ->
                resp.body?.byteStream()?.copyTo(out)
            }

            Log.i(TAG, "Downloaded APK: ${apkFile.length()} bytes")

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not device owner — cannot install")
                return
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.openWrite("update.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().copyTo(out)
                session.fsync(out)
            }
            session.commit(PendingIntent.getBroadcast(
                context,
                SESSION_ID,
                Intent("ug.co.signalelectronics.devicefinance.INSTALL_DONE"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            ).intentSender)

            Log.i(TAG, "APK install committed")

        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall failed", e)
        }
    }
}
