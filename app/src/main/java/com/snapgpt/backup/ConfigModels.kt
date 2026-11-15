package com.snapgpt.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Why reading or writing a backup failed. The UI turns each kind into a plain message. */
enum class BackupFailure {
    /** The file does not start with the SnapGPT backup marker. */
    NOT_A_BACKUP,

    /** The file was made by a newer SnapGPT than this one understands. */
    NEWER_VERSION,

    /** The structure is damaged (truncated, bad lengths, unreadable header or content). */
    CORRUPT,

    /** Authentication failed: either the password is wrong or the file was modified or damaged. The two cannot be told apart. */
    WRONG_PASSWORD_OR_CORRUPT,

    /** The file is far larger than any real backup. */
    TOO_LARGE,
}

class BackupException(val failure: BackupFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One API account as stored in a backup. [apiKey] is null when the account has no key (or the key could not be read);
 * it is plain text ONLY inside the encrypted payload and never in logs or in the exported file's header.
 */
data class BackupAccount(
    val id: String,
    val name: String,
    /** ProviderType.key (for example "groq", "gemini", "openrouter", "anthropic", "custom"). Unknown values are kept as-is. */
    val provider: String,
    val endpoint: String,
    val model: String,
    val enabled: Boolean,
    /** Failover order: lower is tried first. */
    val priority: Int,
    /** -1 = built-in knowledge, 0 = user says no images, 1 = images work. */
    val imageOverride: Int,
    val apiKey: String?,
) {
    // Never print the key, even by accident (for example in a log line or a failed assertion).
    override fun toString(): String = "BackupAccount(id=$id, name=$name, provider=$provider, model=$model, key=${if (apiKey.isNullOrEmpty()) "none" else "set"})"
}

/** API-related app settings that belong with the accounts. A null field means "not in this backup". */
data class BackupSettings(
    val autoFailover: Boolean? = null,
    val maxRetriesPerAccount: Int? = null,
    val verificationEnabled: Boolean? = null,
    val verifyDifferentAccount: Boolean? = null,
)

data class ConfigPayload(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val accounts: List<BackupAccount>,
    val settings: BackupSettings,
) {
    override fun toString(): String = "ConfigPayload(schema=$schemaVersion, app=$appVersion, accounts=${accounts.size})"
}

/**
 * Versioned JSON shape of the configuration. [CURRENT] is bumped whenever a new API setting is added; older payloads
 * are upgraded step by step by [migrate], so old backup files stay importable.
 */
object ConfigSchema {
    const val CURRENT = 1

    /** One entry per upgrade step: key N turns a schema-N object into a schema-(N+1) object. Empty until schema 2 exists. */
    val defaultMigrations: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    fun migrate(
        obj: JsonObject,
        target: Int = CURRENT,
        migrations: Map<Int, (JsonObject) -> JsonObject> = defaultMigrations,
    ): JsonObject {
        var version = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw BackupException(BackupFailure.CORRUPT, "The backup has no schema version.")
        if (version > target) throw BackupException(BackupFailure.NEWER_VERSION, "This backup was made by a newer version of SnapGPT. Update the app to import it.")
        if (version < 1) throw BackupException(BackupFailure.CORRUPT, "The backup has an invalid schema version.")
        var current = obj
        while (version < target) {
            val step = migrations[version]
                ?: throw BackupException(BackupFailure.CORRUPT, "No migration from backup schema $version.")
            current = step(current)
            version++
            current = JsonObject(current + ("schemaVersion" to JsonPrimitive(version)))
        }
        return current
    }
}

object ConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Provider ids become Android Keystore aliases and file-safe values, so only plain characters are accepted. */
    private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,100}$")
    const val MAX_ACCOUNTS = 500

    fun encode(p: ConfigPayload): ByteArray {
        val obj = buildJsonObject {
            put("schemaVersion", p.schemaVersion)
            put("appVersion", p.appVersion)
            put("createdAt", p.createdAtMillis)
            put("settings", buildJsonObject {
                p.settings.autoFailover?.let { put("autoFailover", it) }
                p.settings.maxRetriesPerAccount?.let { put("maxRetriesPerAccount", it) }
                p.settings.verificationEnabled?.let { put("verificationEnabled", it) }
                p.settings.verifyDifferentAccount?.let { put("verifyDifferentAccount", it) }
            })
            put("accounts", buildJsonArray {
                p.accounts.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        put("name", a.name)
                        put("provider", a.provider)
                        put("endpoint", a.endpoint)
                        put("model", a.model)
                        put("enabled", a.enabled)
                        put("priority", a.priority)
                        put("imageOverride", a.imageOverride)
                        if (a.apiKey != null) put("apiKey", a.apiKey) else put("apiKey", JsonNull)
                    })
                }
            })
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses, migrates and validates. Accounts with unusable ids are dropped rather than failing the whole file. */
    fun decode(bytes: ByteArray, migrations: Map<Int, (JsonObject) -> JsonObject> = ConfigSchema.defaultMigrations): ConfigPayload {
        val root: JsonObject = try {
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw BackupException(BackupFailure.CORRUPT, "The backup content could not be read.", e)
        }
        val migrated = ConfigSchema.migrate(root, ConfigSchema.CURRENT, migrations)
        try {
            val accountsJson: JsonArray = migrated["accounts"]?.jsonArray ?: JsonArray(emptyList())
            if (accountsJson.size > MAX_ACCOUNTS) throw BackupException(BackupFailure.CORRUPT, "The backup contains an unreasonable number of accounts.")
            val seen = HashSet<String>()
            val accounts = accountsJson.mapNotNull { el -> accountOrNull(el, seen) }
            val s = migrated["settings"] as? JsonObject
            return ConfigPayload(
                schemaVersion = migrated["schemaVersion"]!!.jsonPrimitive.intOrFail(),
                appVersion = migrated["appVersion"]?.str() ?: "",
                createdAtMillis = migrated["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                accounts = accounts,
                settings = BackupSettings(
                    autoFailover = s?.get("autoFailover")?.jsonPrimitive?.booleanOrNull,
                    maxRetriesPerAccount = s?.get("maxRetriesPerAccount")?.jsonPrimitive?.intOrNull,
                    verificationEnabled = s?.get("verificationEnabled")?.jsonPrimitive?.booleanOrNull,
                    verifyDifferentAccount = s?.get("verifyDifferentAccount")?.jsonPrimitive?.booleanOrNull,
                ),
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupFailure.CORRUPT, "The backup content is not in the expected format.", e)
        }
    }

    private fun JsonPrimitive.intOrFail(): Int = intOrNull ?: throw BackupException(BackupFailure.CORRUPT, "Invalid number in backup.")

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun accountOrNull(el: JsonElement, seen: MutableSet<String>): BackupAccount? {
        val o = el as? JsonObject ?: return null
        val id = o["id"]?.str()?.trim().orEmpty()
        if (!SAFE_ID.matches(id) || !seen.add(id)) return null
        return BackupAccount(
            id = id,
            name = o["name"]?.str().orEmpty().take(200),
            provider = o["provider"]?.str().orEmpty().take(50),
            endpoint = o["endpoint"]?.str().orEmpty().take(2000),
            model = o["model"]?.str().orEmpty().take(300),
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            priority = o["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE,
            imageOverride = (o["imageOverride"]?.jsonPrimitive?.intOrNull ?: -1).let { if (it in -1..1) it else -1 },
            apiKey = o["apiKey"]?.str()?.takeIf { it.isNotBlank() },
        )
    }
}
