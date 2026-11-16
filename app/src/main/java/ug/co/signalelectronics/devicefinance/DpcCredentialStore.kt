package ug.co.signalelectronics.devicefinance

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the per-device bearer credential encrypted with a non-exportable Android Keystore key. */
object DpcCredentialStore {
    private const val ALIAS = "signal_dpc_device_credential_v1"
    private const val PREFS = "signal_dpc_secure"
    private const val IV_KEY = "credential_iv"
    private const val DATA_KEY = "credential_ciphertext"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, token: String) {
        require(token.matches(Regex("[a-f0-9]{64}"))) { "Invalid device credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val iv = prefs.getString(IV_KEY, null) ?: return null
            val data = prefs.getString(DATA_KEY, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(ALIAS)
        } catch (_: Exception) { }
    }
}
