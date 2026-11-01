package com.snapgpt.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the locally entered provider key with a non-exportable Android Keystore AES key.
 * A determined attacker controlling a rooted/running device can still extract runtime secrets;
 * this is local-at-rest protection, not a substitute for a server-side proxy.
 */
object SecureKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "snapgpt_local_api_key_v1"
    private const val PREFS = "snapgpt_secure"
    private const val VALUE = "groq_api_key"

    fun save(context: android.content.Context, plainText: String) {
        require(plainText.isNotBlank()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(VALUE, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun read(context: android.content.Context): String? {
        val encoded = context.getSharedPreferences(PREFS, 0).getString(VALUE, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= 12) return null
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun delete(context: android.content.Context) {
        context.getSharedPreferences(PREFS, 0).edit().remove(VALUE).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
