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
 * Stores one encrypted API secret per API account. AES-256-GCM uses a key held by Android Keystore;
 * only ciphertext and its IV are stored in app-private SharedPreferences.
 * A rooted device/debugger can still inspect a key while the app is using it; public releases should
 * use a backend proxy instead of shipping users' provider keys in a client app.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("snapgpt_secure", Context.MODE_PRIVATE)

    fun hasKey(accountId: String): Boolean = prefs.contains(ctName(accountId)) || (accountId == LEGACY_DEFAULT_ID && prefs.contains(LEGACY_CT))

    fun save(accountId: String, apiKey: String) {
        require(accountId.isNotBlank()) { "Account ID is required" }
        require(apiKey.isNotBlank()) { "API key is required" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(ivName(accountId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ctName(accountId), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun load(accountId: String): String? {
        val iv = prefs.getString(ivName(accountId), null)
        val ct = prefs.getString(ctName(accountId), null)
        if (iv != null && ct != null) return decrypt(iv, ct, accountId)

        // Migrate the single-key format used by earlier SnapGPT builds to the original Groq account.
        if (accountId == LEGACY_DEFAULT_ID) {
            val legacyIv = prefs.getString(LEGACY_IV, null)
            val legacyCt = prefs.getString(LEGACY_CT, null)
            if (legacyIv != null && legacyCt != null) {
                val migrated = decrypt(legacyIv, legacyCt, null)
                if (!migrated.isNullOrBlank()) {
                    save(accountId, migrated)
                    prefs.edit().remove(LEGACY_IV).remove(LEGACY_CT).apply()
                    return migrated
                }
            }
        }
        return null
    }

    fun delete(accountId: String) {
        prefs.edit().remove(ivName(accountId)).remove(ctName(accountId)).apply()
        if (accountId == LEGACY_DEFAULT_ID) prefs.edit().remove(LEGACY_IV).remove(LEGACY_CT).apply()
    }

    fun hasAnyKey(): Boolean {
        if (prefs.contains(LEGACY_CT)) return true
        return prefs.all.keys.any { it.startsWith(CT_PREFIX) }
    }

    fun masked(accountId: String): String = mask(load(accountId))

    /** Compatibility helpers for the first Groq-only version. */
    fun hasKey(): Boolean = hasKey(LEGACY_DEFAULT_ID)
    fun save(apiKey: String) = save(LEGACY_DEFAULT_ID, apiKey)
    fun load(): String? = load(LEGACY_DEFAULT_ID)
    fun delete() = delete(LEGACY_DEFAULT_ID)
    fun masked(): String = masked(LEGACY_DEFAULT_ID)

    private fun decrypt(iv: String, ct: String, accountId: String?): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) {
        if (accountId == null) prefs.edit().remove(LEGACY_IV).remove(LEGACY_CT).apply()
        else delete(accountId)
        null
    }

    private fun mask(key: String?): String {
        val k = key ?: return ""
        return if (k.length <= 8) "••••" else k.take(4) + "••••••••" + k.takeLast(4)
    }

    private fun safeId(id: String) = Base64.encodeToString(id.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun ivName(id: String) = IV_PREFIX + safeId(id)
    private fun ctName(id: String) = CT_PREFIX + safeId(id)

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
        const val IV_PREFIX = "api_iv_"
        const val CT_PREFIX = "api_ct_"
        const val LEGACY_DEFAULT_ID = "groq-primary"
        const val LEGACY_IV = "iv"
        const val LEGACY_CT = "ct"
    }
}
