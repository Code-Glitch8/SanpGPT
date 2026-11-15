package com.snapgpt.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Metadata stored in clear text at the front of a .snapgpt file (it is authenticated, so tampering is detected). */
data class BackupHeader(
    val formatVersion: Int,
    val kdf: String,
    val iterations: Int,
    val cipher: String,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAtMillis: Long,
)

class DecryptedBackup(val header: BackupHeader, val plaintext: ByteArray)

/**
 * The portable .snapgpt container, independent of any key stored on the original device:
 *
 *   "SNAPGPT1" (8 bytes) | header length (4 bytes, big endian) | header JSON (UTF-8) | AES-256-GCM ciphertext + 16-byte tag
 *
 * The encryption key is derived from the user's backup password with PBKDF2-HMAC-SHA256 and a random salt. The magic
 * bytes and the header are passed to GCM as associated data, so changing any of them makes decryption fail.
 * The password itself is never stored.
 */
object BackupCrypto {
    const val FORMAT_VERSION = 1
    const val KDF_NAME = "PBKDF2WithHmacSHA256"
    const val CIPHER_NAME = "AES-256-GCM"

    /** Work factor for new backups. It is stored in the header, so it can be raised later without breaking old files. */
    const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_HEADER_BYTES = 4096
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    const val MIN_PASSWORD_LENGTH = 8

    private val MAGIC = "SNAPGPT1".toByteArray(Charsets.US_ASCII)
    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the password is acceptable, otherwise the message to show. */
    fun passwordProblem(password: CharArray, confirmation: CharArray? = null): String? = when {
        password.size < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters for the backup password."
        confirmation != null && !password.contentEquals(confirmation) -> "The two passwords do not match."
        else -> null
    }

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        appVersion: String,
        schemaVersion: Int,
        createdAtMillis: Long,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(password.isNotEmpty()) { "A password is required" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported iteration count" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            put("kdf", KDF_NAME)
            put("iterations", iterations)
            put("salt", b64(salt))
            put("cipher", CIPHER_NAME)
            put("iv", b64(iv))
            put("tagBits", TAG_BITS)
            put("appVersion", appVersion)
            put("schemaVersion", schemaVersion)
            put("createdAt", createdAtMillis)
        }.toString().toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER_BYTES) { "Header too large" }

        val prefix = ByteBuffer.allocate(MAGIC.size + 4 + header.size)
            .put(MAGIC).putInt(header.size).put(header).array()

        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(prefix)
        val ct = cipher.doFinal(plaintext)
        return prefix + ct
    }

    /** Reads only the clear-text header (no password needed). Throws [BackupException] if this is not a SnapGPT backup. */
    fun readHeader(file: ByteArray): BackupHeader = parse(file).header

    fun decrypt(file: ByteArray, password: CharArray): DecryptedBackup {
        val p = parse(file)
        val key = try {
            deriveKey(password, p.salt, p.header.iterations)
        } catch (e: Exception) {
            throw BackupException(BackupFailure.CORRUPT, "The backup could not be opened.", e)
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, p.iv))
            cipher.updateAAD(file, 0, p.prefixLength)
            DecryptedBackup(p.header, cipher.doFinal(file, p.prefixLength, file.size - p.prefixLength))
        } catch (e: AEADBadTagException) {
            throw BackupException(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, "Wrong password, or the backup file is damaged.", e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw BackupException(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, "Wrong password, or the backup file is damaged.", e)
        }
    }

    private class Parsed(val header: BackupHeader, val salt: ByteArray, val iv: ByteArray, val prefixLength: Int)

    private fun parse(file: ByteArray): Parsed {
        if (file.size > MAX_FILE_BYTES) throw BackupException(BackupFailure.TOO_LARGE, "That file is too large to be a SnapGPT backup.")
        if (file.size < MAGIC.size || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException(BackupFailure.NOT_A_BACKUP, "That file is not a SnapGPT API backup.")
        }
        if (file.size < MAGIC.size + 4) throw BackupException(BackupFailure.CORRUPT, "The backup file is incomplete.")
        val headerLen = ByteBuffer.wrap(file, MAGIC.size, 4).int
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.")
        val prefixLength = MAGIC.size + 4 + headerLen
        // A GCM tag alone is 16 bytes, so anything not longer than the prefix + tag cannot be valid.
        if (file.size < prefixLength + TAG_BITS / 8) throw BackupException(BackupFailure.CORRUPT, "The backup file is incomplete.")

        val h: JsonObject = try {
            json.parseToJsonElement(String(file, MAGIC.size + 4, headerLen, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.", e)
        }
        fun str(k: String) = (h[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun int(k: String) = h[k]?.jsonPrimitive?.intOrNull
        val format = int("formatVersion") ?: throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.")
        if (format > FORMAT_VERSION) throw BackupException(BackupFailure.NEWER_VERSION, "This backup was made by a newer version of SnapGPT. Update the app to import it.")
        if (format < 1) throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.")
        val kdf = str("kdf")
        val cipher = str("cipher")
        if (kdf != KDF_NAME || cipher != CIPHER_NAME) throw BackupException(BackupFailure.NEWER_VERSION, "This backup uses encryption settings this version does not support.")
        val iterations = int("iterations")
        if (iterations == null || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.")
        val salt = try { Base64.getDecoder().decode(str("salt") ?: "") } catch (e: IllegalArgumentException) { ByteArray(0) }
        val iv = try { Base64.getDecoder().decode(str("iv") ?: "") } catch (e: IllegalArgumentException) { ByteArray(0) }
        if (salt.size != SALT_BYTES || iv.size != IV_BYTES) throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.")
        if (int("tagBits") != TAG_BITS) throw BackupException(BackupFailure.CORRUPT, "The backup header is damaged.")

        val header = BackupHeader(
            formatVersion = format,
            kdf = kdf,
            iterations = iterations,
            cipher = cipher,
            appVersion = str("appVersion").orEmpty(),
            schemaVersion = int("schemaVersion") ?: 0,
            createdAtMillis = h["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
        return Parsed(header, salt, iv, prefixLength)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF_NAME).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
