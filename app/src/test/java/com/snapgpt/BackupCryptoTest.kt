package com.snapgpt

import com.snapgpt.backup.BackupAccount
import com.snapgpt.backup.BackupCrypto
import com.snapgpt.backup.BackupException
import com.snapgpt.backup.BackupFailure
import com.snapgpt.backup.BackupSettings
import com.snapgpt.backup.ConfigCodec
import com.snapgpt.backup.ConfigPayload
import com.snapgpt.backup.ConfigSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    // Low iteration count keeps the tests fast; production uses BackupCrypto.DEFAULT_ITERATIONS (stored in the header).
    private val fast = 2_000
    private val pw = "correct horse battery".toCharArray()

    private fun payload(vararg accounts: BackupAccount) = ConfigPayload(
        schemaVersion = ConfigSchema.CURRENT, appVersion = "1.0.0", createdAtMillis = 1_700_000_000_000L,
        accounts = accounts.toList(), settings = BackupSettings(autoFailover = true, maxRetriesPerAccount = 3),
    )

    private fun acct(id: String, key: String?, provider: String = "openrouter", priority: Int = 0) = BackupAccount(
        id = id, name = "Name $id", provider = provider, endpoint = "https://example.test/v1", model = "m-$id",
        enabled = true, priority = priority, imageOverride = -1, apiKey = key,
    )

    private fun encrypted(p: ConfigPayload, password: CharArray = pw) =
        BackupCrypto.encrypt(ConfigCodec.encode(p), password, "1.0.0", p.schemaVersion, p.createdAtMillis, iterations = fast)

    private fun failure(block: () -> Unit): BackupFailure {
        try { block() } catch (e: BackupException) { return e.failure }
        fail("expected BackupException")
        error("unreachable")
    }

    @Test fun roundTripKeepsEveryAccountSettingAndKey() {
        val p = payload(
            acct("a1", "sk-or-AAAA1111", "openrouter", 0), acct("a2", "AIzaBBBB2222", "gemini", 1),
            acct("a3", "sk-ant-CCCC3333", "anthropic", 2), acct("a4", "sk-DDDD4444", "custom", 3), acct("a5", null, "groq", 4),
        )
        val file = encrypted(p)
        val back = ConfigCodec.decode(BackupCrypto.decrypt(file, pw).plaintext)
        assertEquals(p.accounts, back.accounts)
        assertEquals(true, back.settings.autoFailover)
        assertEquals(3, back.settings.maxRetriesPerAccount)
        assertEquals(1, back.schemaVersion)
    }

    @Test fun exportedFileNeverContainsPlainKeysOrPassword() {
        val file = encrypted(payload(acct("a1", "sk-SECRET-KEY-VALUE-123")))
        val text = String(file, Charsets.ISO_8859_1)
        assertFalse(text.contains("sk-SECRET-KEY-VALUE-123"))
        assertFalse(text.contains("correct horse"))
        assertFalse(text.contains("openrouter")) // account data is encrypted, only the header is readable
    }

    @Test fun wrongPasswordIsRejectedWithoutRevealingAnything() {
        val file = encrypted(payload(acct("a1", "sk-1")))
        assertEquals(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, failure { BackupCrypto.decrypt(file, "not the password".toCharArray()) })
    }

    @Test fun flippedCiphertextByteIsDetected() {
        val file = encrypted(payload(acct("a1", "sk-1")))
        file[file.size - 20] = (file[file.size - 20].toInt() xor 0x01).toByte()
        assertEquals(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, failure { BackupCrypto.decrypt(file, pw) })
    }

    @Test fun tamperedHeaderIsDetectedBecauseItIsAuthenticated() {
        val file = encrypted(payload(acct("a1", "sk-1")))
        // Change the app version text inside the JSON header ("1.0.0" -> "1.0.1"): still valid JSON, but no longer what was sealed.
        val s = String(file, Charsets.ISO_8859_1).replaceFirst("\"appVersion\":\"1.0.0\"", "\"appVersion\":\"1.0.1\"")
        val tampered = s.toByteArray(Charsets.ISO_8859_1)
        assertEquals(file.size, tampered.size)
        assertEquals(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, failure { BackupCrypto.decrypt(tampered, pw) })
    }

    @Test fun truncatedFileIsReportedAsCorrupt() {
        val file = encrypted(payload(acct("a1", "sk-1")))
        assertEquals(BackupFailure.CORRUPT, failure { BackupCrypto.decrypt(file.copyOf(14), pw) })
        // Cut off in the ciphertext: authentication fails.
        assertEquals(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, failure { BackupCrypto.decrypt(file.copyOf(file.size - 10), pw) })
    }

    @Test fun randomOrEmptyFilesAreNotBackups() {
        assertEquals(BackupFailure.NOT_A_BACKUP, failure { BackupCrypto.decrypt(ByteArray(0), pw) })
        assertEquals(BackupFailure.NOT_A_BACKUP, failure { BackupCrypto.decrypt("just some text file".toByteArray(), pw) })
        assertEquals(BackupFailure.NOT_A_BACKUP, failure { BackupCrypto.readHeader(ByteArray(1000) { it.toByte() }) })
    }

    @Test fun hugeFileIsRefusedBeforeAnyWork() {
        assertEquals(BackupFailure.TOO_LARGE, failure { BackupCrypto.decrypt(ByteArray(BackupCrypto.MAX_FILE_BYTES + 1), pw) })
    }

    @Test fun headerIsReadableWithoutPasswordAndCarriesVersions() {
        val h = BackupCrypto.readHeader(encrypted(payload(acct("a1", "k"))))
        assertEquals(BackupCrypto.FORMAT_VERSION, h.formatVersion)
        assertEquals("PBKDF2WithHmacSHA256", h.kdf)
        assertEquals("AES-256-GCM", h.cipher)
        assertEquals(fast, h.iterations)
        assertEquals("1.0.0", h.appVersion)
        assertEquals(1, h.schemaVersion)
    }

    @Test fun twoExportsOfTheSameDataDiffer() {
        val p = payload(acct("a1", "sk-1"))
        assertFalse(encrypted(p).contentEquals(encrypted(p))) // fresh salt and IV every time
    }

    @Test fun newerFormatVersionIsRefusedWithAClearReason() {
        val file = encrypted(payload(acct("a1", "k")))
        val s = String(file, Charsets.ISO_8859_1).replaceFirst("\"formatVersion\":1", "\"formatVersion\":9")
        assertEquals(BackupFailure.NEWER_VERSION, failure { BackupCrypto.decrypt(s.toByteArray(Charsets.ISO_8859_1), pw) })
    }

    @Test fun passwordPolicy() {
        assertNotNull(BackupCrypto.passwordProblem("short".toCharArray()))
        assertNotNull(BackupCrypto.passwordProblem("longenough1".toCharArray(), "different11".toCharArray()))
        assertNull(BackupCrypto.passwordProblem("longenough1".toCharArray(), "longenough1".toCharArray()))
    }

    @Test fun accountToStringNeverShowsTheKey() {
        val a = acct("a1", "sk-VERY-SECRET")
        assertFalse(a.toString().contains("sk-VERY-SECRET"))
        assertFalse(payload(a).toString().contains("sk-VERY-SECRET"))
    }

    // ---------------------------------------------------------------- codec and schema migration

    @Test fun unknownFieldsAreIgnoredSoNewerMinorAdditionsStillImport() {
        val json = """{"schemaVersion":1,"appVersion":"9","createdAt":5,"futureThing":{"x":1},
            "settings":{"autoFailover":false,"somethingNew":true},
            "accounts":[{"id":"a1","name":"A","provider":"groq","endpoint":"","model":"m","enabled":false,"priority":2,"imageOverride":1,"apiKey":"k","extra":"x"}]}"""
        val p = ConfigCodec.decode(json.toByteArray())
        assertEquals(1, p.accounts.size)
        assertEquals(false, p.accounts[0].enabled)
        assertEquals(false, p.settings.autoFailover)
        assertNull(p.settings.maxRetriesPerAccount)
    }

    @Test fun accountsWithUnsafeOrDuplicateIdsAreDropped() {
        val json = """{"schemaVersion":1,"accounts":[
            {"id":"ok-1","name":"A","provider":"groq","model":"m"},
            {"id":"bad id with spaces","name":"B","provider":"groq","model":"m"},
            {"id":"../escape","name":"C","provider":"groq","model":"m"},
            {"id":"ok-1","name":"dup","provider":"groq","model":"m"}]}"""
        val p = ConfigCodec.decode(json.toByteArray())
        assertEquals(listOf("ok-1"), p.accounts.map { it.id })
    }

    @Test fun garbageContentIsCorruptNotACrash() {
        assertEquals(BackupFailure.CORRUPT, failure { ConfigCodec.decode("not json".toByteArray()) })
        assertEquals(BackupFailure.CORRUPT, failure { ConfigCodec.decode("""{"accounts":[]}""".toByteArray()) })
    }

    @Test fun newerSchemaIsRefusedAndOlderSchemaIsMigrated() {
        assertEquals(BackupFailure.NEWER_VERSION, failure { ConfigCodec.decode("""{"schemaVersion":99,"accounts":[]}""".toByteArray()) })

        // A future schema 2 could rename a field; the registered step upgrades old files so they stay importable.
        val migrations: Map<Int, (JsonObject) -> JsonObject> = mapOf(
            1 to { o -> JsonObject(o + ("appVersion" to JsonPrimitive("migrated"))) },
        )
        val migrated = ConfigSchema.migrate(
            JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "appVersion" to JsonPrimitive("old"))), target = 2, migrations = migrations,
        )
        assertEquals("migrated", (migrated["appVersion"] as JsonPrimitive).content)
        assertEquals(2, (migrated["schemaVersion"] as JsonPrimitive).content.toInt())
    }

    @Test fun missingMigrationStepIsReportedNotSkipped() {
        val obj = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1)))
        assertEquals(BackupFailure.CORRUPT, failure { ConfigSchema.migrate(obj, target = 3, migrations = emptyMap()) })
    }

    @Test fun encodedPayloadDecodesToTheSameAccountsInPlainRoundTrip() {
        val p = payload(acct("a1", "k1"), acct("a2", null, "gemini", 1))
        assertArrayEquals(ConfigCodec.encode(p), ConfigCodec.encode(ConfigCodec.decode(ConfigCodec.encode(p))))
        assertTrue(ConfigCodec.decode(ConfigCodec.encode(p)).accounts[1].apiKey == null)
    }
}
