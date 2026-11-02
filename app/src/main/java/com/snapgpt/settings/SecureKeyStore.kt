package com.snapgpt.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Groq API key encrypted with an AES-256-GCM key that lives in the Android Keystore (hardware-backed where the
 * device supports it). The key material never leaves the Keystore; only ciphertext is written to app-private storage.
 *
 * Limitation (also documented in the README): the app must decrypt the key to call Groq, so a determined attacker with a
 * rooted device or a debugger can still read it from the running process. For a public release use a backend proxy.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("snapgpt_secure", Context.MODE_PRIVATE)

    fun hasKey(): Boolean = prefs.contains(CT)

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    /** Returns the decrypted key, or null if none is saved or it can no longer be decrypted (then it is wiped). */
    fun load(): String? {
        val iv = prefs.getString(IV, null) ?: return null
        val ct = prefs.getString(CT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            delete() // Keystore entry lost or data corrupted: the user simply enters the key again.
            null
        }
    }

    fun delete() {
        prefs.edit().remove(IV).remove(CT).apply()
    }

    /** "gsk_...abcd" style preview that never reveals the full key. */
    fun masked(): String {
        val k = load() ?: return ""
        return if (k.length <= 8) "••••" else k.take(4) + "••••••••" + k.takeLast(4)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "snapgpt_groq_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = "iv"
        const val CT = "ct"
    }
}
