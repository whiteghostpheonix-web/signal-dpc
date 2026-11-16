package ug.co.signalelectronics.devicefinance

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val TAG = "SignalDPC.Update"
    private const val SESSION_ID = 41
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun checkAndUpdate(context: Context) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("signal_dpc", Context.MODE_PRIVATE)
                val manifest = JSONObject(prefs.getString("update_manifest", "{}") ?: "{}")
                if (!manifest.optBoolean("enabled", false)) return@Thread
                val versionCode = manifest.optLong("version_code", 0L)
                val sha256 = manifest.optString("sha256", "").lowercase()
                val apkUrl = manifest.optString("apk_url", "")
                val currentCode = if (Build.VERSION.SDK_INT >= 28) {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
                }
                val uri = Uri.parse(apkUrl)
                if (versionCode <= currentCode || !sha256.matches(Regex("[a-f0-9]{64}"))
                    || uri.scheme != "https" || uri.host != Uri.parse(SignalApp.API_BASE).host
                    || !uri.path.orEmpty().startsWith("/download/apk/")) {
                    Log.w(TAG, "Update manifest invalid or not newer; refusing install")
                    return@Thread
                }
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    Log.i(TAG, "Not Device Owner; automatic installation unavailable")
                    return@Thread
                }
                downloadAndInstall(context, apkUrl, sha256)
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
            }
        }.start()
    }

    private fun downloadAndInstall(context: Context, apkUrl: String, expectedSha256: String) {
        val apkFile = File(context.cacheDir, "signal-dpc-update.apk")
        try {
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Update download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Empty update response")
                if (body.contentLength() > 100L * 1024L * 1024L) throw IllegalStateException("Update package exceeds size limit")
                apkFile.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(apkFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (digest != expectedSha256) throw SecurityException("Update checksum mismatch")

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("signal-dpc-update.apk", 0, apkFile.length()).use { output ->
                    apkFile.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent("ug.co.signalelectronics.devicefinance.INSTALL_DONE")
                val pending = PendingIntent.getBroadcast(context, SESSION_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "Verified DPC update submitted to Android PackageInstaller")
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall failed", e)
        } finally {
            apkFile.delete()
        }
    }
}
