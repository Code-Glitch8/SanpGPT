package com.snapgpt.backup

import java.io.File
import java.io.FileOutputStream

/** Seals bytes with a key that never leaves this device (Android Keystore in the app, a plain test key in unit tests). */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** Throws if the data was modified, truncated or sealed with another key. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The automatic internal backup: one current file plus the previous one, both sealed with a [SecretBox].
 *
 * A write never touches the good files until the new data is fully on disk and has been read back and verified:
 *   1. seal and write to a temp file, force it to disk
 *   2. open the temp file again and compare with what was meant to be written
 *   3. move the current file to "previous", then move the temp file to "current"
 * A crash (or failed disk write) at any step leaves at least one valid file, and [readLatest] picks the newest valid one.
 */
class InternalBackupStore(private val dir: File, private val box: SecretBox) {
    private val current = File(dir, "api_config.bak")
    private val previous = File(dir, "api_config.prev.bak")
    private val temp = File(dir, "api_config.tmp")

    /** Returns true when the new backup is safely in place. On false the previous backups are untouched. */
    @Synchronized
    fun write(payload: ByteArray): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            temp.delete()
            val sealed = box.seal(payload)
            FileOutputStream(temp).use { out ->
                out.write(sealed)
                out.flush()
                out.fd.sync()
            }
            val verified = try { box.open(temp.readBytes()).contentEquals(payload) } catch (_: Exception) { false }
            if (!verified) { temp.delete(); return false }

            if (current.exists()) {
                if (readValid(current) != null) {
                    previous.delete()
                    if (!current.renameTo(previous)) { temp.delete(); return false }
                } else {
                    current.delete() // a damaged "current" must not replace a good "previous"
                }
            }
            if (!temp.renameTo(current)) {
                // Put things back so a valid backup is still named "current".
                if (!current.exists() && previous.exists()) previous.renameTo(current)
                return false
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /** The newest backup that opens and authenticates, or null if there is none. */
    @Synchronized
    fun readLatest(): ByteArray? = readValid(temp) ?: readValid(current) ?: readValid(previous)

    @Synchronized
    fun hasBackup(): Boolean = readLatest() != null

    /** Modification time of the newest existing backup file, or 0. */
    @Synchronized
    fun lastModified(): Long = listOf(temp, current, previous).filter { it.exists() && readValid(it) != null }.maxOfOrNull { it.lastModified() } ?: 0L

    @Synchronized
    fun deleteAll() {
        temp.delete(); current.delete(); previous.delete()
    }

    private fun readValid(f: File): ByteArray? {
        if (!f.isFile) return null
        return try { box.open(f.readBytes()) } catch (_: Exception) { null }
    }
}
