package com.snapgpt.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys one by one. Every account gets its OWN AES-256-GCM key inside the Android Keystore (alias derived from
 * the account id), so a key blob can only be opened by that entry, and deleting an account destroys its Keystore key as well.
 * Key material never leaves the Keystore (hardware-backed where the device supports it); only "iv:ciphertext" text is stored.
 *
 * Limitation (also in the README): the app must decrypt a key to call the provider, so someone with a rooted device or a
 * debugger can still read it from the running process. For a public release, route requests through a backend proxy.
 */
class KeyCipher {

    fun encrypt(accountId: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(accountId, create = true)!!)
        val ct = cipher.doFinal(plain.trim().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** The decrypted key, or null when there is none or it can no longer be decrypted (Keystore entry lost, data corrupted). */
    fun decrypt(accountId: String, blob: String?): String? {
        if (blob.isNullOrBlank()) return null
        val parts = blob.split(":")
        if (parts.size != 2) return null
        return try {
            val key = secretKey(accountId, create = false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Destroys the Keystore key of one account (used when the account or its key is deleted). */
    fun delete(accountId: String) {
        runCatching { keyStore().deleteEntry(alias(accountId)) }
    }

    private fun alias(id: String) = "snapgpt_acct_$id"

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(id: String, create: Boolean): SecretKey? {
        (keyStore().getKey(alias(id), null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** "gsk_abcd••••wxyz" style preview that never reveals a whole key. */
fun maskKey(key: String?): String = when {
    key.isNullOrEmpty() -> ""
    key.length <= 8 -> "••••"
    else -> key.take(4) + "••••••••" + key.takeLast(4)
}
