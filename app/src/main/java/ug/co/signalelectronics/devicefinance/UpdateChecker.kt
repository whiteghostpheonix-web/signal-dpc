package ug.co.signalelectronics.devicefinance

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
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
    private const val SESSION_ID = 1
    private const val API_HOST = "device-finance-api.whiteghostpheonix.workers.dev"

    fun checkAndUpdate(context: Context) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url("${SignalApp.API_BASE}/api/apk/latest").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Version check HTTP ${resp.code}")
                        return@Thread
                    }
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val remoteCode = json.optLong("version_code", 0L)
                    val expectedSha256 = json.optString("sha256", "").lowercase()
                    val apkUrl = json.optString("apk_url", "")
                    val uri = android.net.Uri.parse(apkUrl)
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentCode = versionCode(packageInfo)

                    Log.i(TAG, "Current code=$currentCode; published code=$remoteCode")
                    if (remoteCode <= currentCode) return@Thread
                    if (expectedSha256.length != 64 || !expectedSha256.matches(Regex("[a-f0-9]{64}"))) return@Thread
                    if (uri.scheme != "https" || uri.host != API_HOST || !uri.path.orEmpty().startsWith("/download/apk/")) return@Thread
                    downloadAndInstall(context, apkUrl, expectedSha256, remoteCode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun downloadAndInstall(context: Context, apkUrl: String, expectedSha256: String, expectedVersionCode: Long) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(apkUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("APK download HTTP ${resp.code}")
                val apkFile = File(context.cacheDir, "signal-dpc-update.apk")
                apkFile.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                if (!apkFile.isFile || apkFile.length() < 1024) throw IllegalStateException("Downloaded APK is empty")

                val digest = MessageDigest.getInstance("SHA-256").digest(apkFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
                if (!digest.equals(expectedSha256, ignoreCase = true)) throw SecurityException("APK checksum verification failed")

                val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    ?: throw IllegalStateException("Downloaded file is not a valid Android package")
                val archiveCode = versionCode(archive)
                if (archive.packageName != context.packageName || archiveCode != expectedVersionCode) {
                    throw SecurityException("APK identity or version does not match release metadata")
                }

                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    Log.w(TAG, "Not device owner — cannot silently install DPC update")
                    return
                }

                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("signal-dpc-update.apk", 0, apkFile.length()).use { output ->
                        apkFile.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                    session.commit(PendingIntent.getBroadcast(
                        context,
                        SESSION_ID,
                        Intent("ug.co.signalelectronics.devicefinance.INSTALL_DONE"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    ).intentSender)
                }
                Log.i(TAG, "Verified DPC update submitted to PackageInstaller")
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall failed", e)
        }
    }
}
