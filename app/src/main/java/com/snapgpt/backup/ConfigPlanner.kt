package com.snapgpt.backup

enum class ImportMode { MERGE, REPLACE }

data class ImportPreview(
    val total: Int,
    /** Backup accounts that match an account already on this device. */
    val alreadyPresent: Int,
    val brandNew: Int,
    /** Accounts currently on the device that would be removed by REPLACE and are not in the backup. */
    val wouldBeRemovedByReplace: Int,
)

data class MergeResult(val accounts: List<BackupAccount>, val added: Int, val skipped: Int, val keysFilled: Int)

/**
 * Decides what an import does. Pure functions over [BackupAccount] lists, so the rules are unit-tested without Android.
 * Nothing is ever deleted silently: MERGE keeps every existing account untouched, REPLACE is only used after the user
 * has been told how many accounts it removes.
 */
object ConfigPlanner {

    private fun endpointKey(e: String) = e.trim().trimEnd('/').lowercase()

    /** The same account if the id matches, or if it is the same provider, endpoint and key (a re-entered duplicate). */
    fun matches(existing: BackupAccount, incoming: BackupAccount): Boolean {
        if (existing.id == incoming.id) return true
        val k = existing.apiKey
        return k != null && k == incoming.apiKey &&
            existing.provider == incoming.provider && endpointKey(existing.endpoint) == endpointKey(incoming.endpoint)
    }

    fun preview(existing: List<BackupAccount>, incoming: List<BackupAccount>): ImportPreview {
        val present = incoming.count { inc -> existing.any { matches(it, inc) } }
        val removed = existing.count { ex -> incoming.none { matches(ex, it) } }
        return ImportPreview(incoming.size, present, incoming.size - present, removed)
    }

    /**
     * Keeps all [existing] accounts exactly as they are (same order, same settings, same keys) and appends the
     * incoming accounts that are not already present. An existing account that has no key gets the backup's key.
     */
    fun merge(existing: List<BackupAccount>, incoming: List<BackupAccount>): MergeResult {
        val result = existing.sortedBy { it.priority }.toMutableList()
        var added = 0
        var skipped = 0
        var filled = 0
        for (inc in incoming.sortedBy { it.priority }) {
            val idx = result.indexOfFirst { matches(it, inc) }
            if (idx >= 0) {
                skipped++
                val cur = result[idx]
                if (cur.apiKey == null && inc.apiKey != null && cur.id == inc.id) {
                    result[idx] = cur.copy(apiKey = inc.apiKey)
                    filled++
                }
            } else {
                result += inc
                added++
            }
        }
        return MergeResult(renumber(result), added, skipped, filled)
    }

    /** The backup's accounts, in the backup's own failover order. */
    fun replace(incoming: List<BackupAccount>): List<BackupAccount> = renumber(incoming.sortedBy { it.priority })

    private fun renumber(list: List<BackupAccount>): List<BackupAccount> = list.mapIndexed { i, a -> a.copy(priority = i) }
}
