package com.snapgpt.backup

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.snapgpt.ai.providers.ConnectionStatus
import com.snapgpt.ai.providers.ProviderType
import com.snapgpt.database.AccountDao
import com.snapgpt.database.AccountEntity
import com.snapgpt.security.KeyCipher
import com.snapgpt.settings.AccountRepository
import com.snapgpt.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the internal backup with a Keystore key that never leaves this device. Output: 12-byte IV followed by the ciphertext. */
class KeystoreSecretBox(private val alias: String = "snapgpt_backup_master") : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key(create = true)!!)
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES + 16) { "too short" }
        val k = key(create = false) ?: throw IllegalStateException("backup key missing")
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

data class ApplyResult(val added: Int, val kept: Int, val removed: Int, val keysFilled: Int)

/**
 * Keeps the API configuration safe: automatic encrypted internal backup, startup recovery, and the password-protected
 * portable export/import. The API keys are only ever held in memory while a backup is built or applied; they are never
 * logged and never leave the device unless the user exports a password-encrypted file.
 */
class BackupManager(
    private val context: Context,
    private val accounts: AccountRepository,
    private val dao: AccountDao,
    private val settings: SettingsRepository,
    private val cipher: KeyCipher,
    private val scope: CoroutineScope,
    private val store: InternalBackupStore = InternalBackupStore(File(context.filesDir, "api_backup"), KeystoreSecretBox()),
) {
    private val appVersion: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    // ------------------------------------------------------------------ snapshot

    private fun AccountEntity.toBackup() = BackupAccount(
        id = id, name = name, provider = provider, endpoint = endpoint, model = model, enabled = enabled,
        priority = priority, imageOverride = imageOverride, apiKey = cipher.decrypt(id, keyBlob),
    )

    private suspend fun backupSettings(): BackupSettings = settings.current().let {
        BackupSettings(it.autoFailover, it.maxRetriesPerAccount, it.verificationEnabled, it.verifyDifferentAccount)
    }

    suspend fun snapshot(): ConfigPayload = withContext(Dispatchers.IO) {
        ConfigPayload(ConfigSchema.CURRENT, appVersion, System.currentTimeMillis(), dao.all().map { it.toBackup() }, backupSettings())
    }

    // ------------------------------------------------------------------ automatic internal backup + startup recovery

    private var started = false

    /** Call once at app start: repairs the configuration from the internal backup if needed, then keeps the backup current. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            runCatching { recoverIfNeeded() }
            // Only changes to the configuration trigger a write (not request counters, cooldowns or UI settings).
            val accountConfig = dao.observeAll().map { list ->
                list.map { listOf(it.id, it.name, it.provider, it.endpoint, it.model, it.enabled, it.priority, it.imageOverride, it.keyBlob) }
            }.distinctUntilChanged()
            val apiSettings = settings.flow.map { BackupSettings(it.autoFailover, it.maxRetriesPerAccount, it.verificationEnabled, it.verifyDifferentAccount) }.distinctUntilChanged()
            var hadAccountsThisRun = false
            combine(accountConfig, apiSettings) { a, s -> a.size to s }.debounce(1500).collect { (count, _) ->
                // An empty database at launch must never overwrite a good backup (it may be a failed or reset database).
                // An empty list after accounts existed in this run is the user deleting them on purpose.
                if (count > 0) hadAccountsThisRun = true
                if (count == 0 && !hadAccountsThisRun && store.hasBackup()) return@collect
                runCatching { writeInternal() }
            }
        }
    }

    suspend fun writeInternal(): Boolean = withContext(Dispatchers.IO) {
        accounts.withStoreLock { store.write(ConfigCodec.encode(snapshot())) }
    }

    /** Backup to compare with when the configuration looks damaged. Null when there is none or it cannot be opened. */
    suspend fun internalBackup(): ConfigPayload? = withContext(Dispatchers.IO) {
        val bytes = store.readLatest() ?: return@withContext null
        runCatching { ConfigCodec.decode(bytes) }.getOrNull()
    }

    fun internalBackupTime(): Long = store.lastModified()

    /**
     * 1. No accounts but a backup exists (reset or failed database): restore everything.
     * 2. A saved key cannot be decrypted any more (lost Keystore entry): re-save it from the backup.
     * Accounts are never removed or overwritten here.
     */
    private suspend fun recoverIfNeeded() {
        val backup = internalBackup() ?: return
        val current = dao.all()
        if (current.isEmpty()) {
            if (backup.accounts.isNotEmpty()) apply(backup, ImportMode.REPLACE, applySettings = false)
            return
        }
        val byId = backup.accounts.associateBy { it.id }
        accounts.withStoreLock {
            current.forEach { e ->
                if (!e.keyBlob.isNullOrBlank() && cipher.decrypt(e.id, e.keyBlob) == null) {
                    byId[e.id]?.apiKey?.let { key -> dao.upsert(e.copy(keyBlob = cipher.encrypt(e.id, key))) }
                }
            }
        }
    }

    /** Privacy helper: after the user deletes all keys, no older internal copy of them may remain. */
    suspend fun forgetStoredKeys() {
        withContext(Dispatchers.IO) { store.deleteAll() }
        writeInternal()
    }

    // ------------------------------------------------------------------ export / import (portable file)

    suspend fun exportTo(uri: Uri, password: CharArray): Int = withContext(Dispatchers.Default) {
        val payload = snapshot()
        val plain = ConfigCodec.encode(payload)
        val file = BackupCrypto.encrypt(plain, password, appVersion, payload.schemaVersion, payload.createdAtMillis)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file); it.flush() }
                ?: throw java.io.IOException("Could not open the chosen location for writing.")
            // Read it back and make sure it opens: never tell the user a backup exists unless it really does.
            val written = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("Could not read the backup back.")
            if (!written.contentEquals(file)) throw java.io.IOException("The saved file did not match what was written. Choose another location.")
        }
        payload.accounts.size
    }

    /** Reads, authenticates and validates a backup file. Throws [BackupException] with a user-readable reason. */
    suspend fun readFile(uri: Uri, password: CharArray): ConfigPayload = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw BackupException(BackupFailure.CORRUPT, "That file could not be opened.")
        val decrypted = withContext(Dispatchers.Default) { BackupCrypto.decrypt(bytes, password) }
        ConfigCodec.decode(decrypted.plaintext)
    }

    /** Header check without a password, so a wrong file type is reported before the user is asked for anything. */
    suspend fun checkFile(uri: Uri): BackupHeader = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw BackupException(BackupFailure.CORRUPT, "That file could not be opened.")
        BackupCrypto.readHeader(bytes)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > BackupCrypto.MAX_FILE_BYTES) throw BackupException(BackupFailure.TOO_LARGE, "That file is too large to be a SnapGPT backup.")
        }
        return out.toByteArray()
    }

    suspend fun preview(payload: ConfigPayload): ImportPreview = ConfigPlanner.preview(snapshot().accounts, payload.accounts)

    // ------------------------------------------------------------------ applying a configuration

    /**
     * Writes [payload] into the live account store. MERGE keeps every existing account untouched and adds what is
     * missing; REPLACE makes the accounts equal to the backup (the caller has already shown how many would be removed).
     * The state before the change is saved as the internal backup first, so it can be recovered.
     */
    suspend fun apply(payload: ConfigPayload, mode: ImportMode, applySettings: Boolean): ApplyResult = withContext(Dispatchers.IO) {
        if (dao.all().isNotEmpty()) runCatching { writeInternal() }
        val result = accounts.withStoreLock {
            val existing = dao.all()
            val byId = existing.associateBy { it.id }
            val existingBackup = existing.map { it.toBackup() }
            var filled = 0
            val finalList = when (mode) {
                ImportMode.MERGE -> ConfigPlanner.merge(existingBackup, payload.accounts).also { filled = it.keysFilled }.accounts
                ImportMode.REPLACE -> ConfigPlanner.replace(payload.accounts)
            }
            var added = 0
            val upserts = finalList.map { a ->
                val e = byId[a.id]
                when {
                    e == null -> {
                        added++
                        newEntity(a)
                    }
                    mode == ImportMode.MERGE -> {
                        val needsKey = e.keyBlob.isNullOrBlank() && a.apiKey != null
                        e.copy(priority = a.priority, keyBlob = if (needsKey) cipher.encrypt(a.id, a.apiKey!!) else e.keyBlob)
                    }
                    else -> e.copy(
                        name = a.name.ifBlank { ProviderType.fromKey(a.provider).displayName }, provider = ProviderType.fromKey(a.provider).key,
                        endpoint = a.endpoint, model = a.model, enabled = a.enabled, priority = a.priority, imageOverride = a.imageOverride,
                        keyBlob = a.apiKey?.let { cipher.encrypt(a.id, it) }.also { if (it == null) cipher.delete(a.id) },
                        status = ConnectionStatus.UNTESTED.name, lastError = null, cooldownUntil = 0, cooldownReason = null,
                    )
                }
            }
            var removed = 0
            if (mode == ImportMode.REPLACE) {
                val keep = finalList.map { it.id }.toSet()
                existing.filter { it.id !in keep }.forEach { cipher.delete(it.id); dao.delete(it.id); removed++ }
            }
            dao.upsertAll(upserts)
            ApplyResult(added = added, kept = finalList.size - added, removed = removed, keysFilled = filled)
        }
        if (applySettings) {
            val s = payload.settings
            settings.update {
                it.copy(
                    autoFailover = s.autoFailover ?: it.autoFailover,
                    maxRetriesPerAccount = s.maxRetriesPerAccount?.coerceIn(0, 10) ?: it.maxRetriesPerAccount,
                    verificationEnabled = s.verificationEnabled ?: it.verificationEnabled,
                    verifyDifferentAccount = s.verifyDifferentAccount ?: it.verifyDifferentAccount,
                )
            }
        }
        runCatching { writeInternal() }
        result
    }

    private fun newEntity(a: BackupAccount) = AccountEntity(
        id = a.id, name = a.name.ifBlank { ProviderType.fromKey(a.provider).displayName }, provider = ProviderType.fromKey(a.provider).key,
        endpoint = a.endpoint, model = a.model, enabled = a.enabled, priority = a.priority, imageOverride = a.imageOverride,
        status = ConnectionStatus.UNTESTED.name, lastError = null, lastSuccessAt = 0, cooldownUntil = 0, cooldownReason = null, rateNote = null,
        keyBlob = a.apiKey?.let { cipher.encrypt(a.id, it) },
    )

}
