package com.snapgpt.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.snapgpt.ai.TokenUsage
import com.snapgpt.ai.UsageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.usageStore by preferencesDataStore(name = "snapgpt_usage")

/** Persists request and token counters. "Actual" comes from Groq's `usage` object, "estimated" is computed locally. */
class UsageRepository(private val context: Context) {
    private object K {
        val requests = intPreferencesKey("requests")
        val successes = intPreferencesKey("successes")
        val failures = intPreferencesKey("failures")
        val cacheHits = intPreferencesKey("cache_hits")
        val actualIn = longPreferencesKey("actual_in")
        val actualOut = longPreferencesKey("actual_out")
        val actualTotal = longPreferencesKey("actual_total")
        val estIn = longPreferencesKey("est_in")
        val estOut = longPreferencesKey("est_out")
    }

    val flow: Flow<UsageStats> = context.usageStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map {
            UsageStats(
                requests = it[K.requests] ?: 0,
                successes = it[K.successes] ?: 0,
                failures = it[K.failures] ?: 0,
                cacheHits = it[K.cacheHits] ?: 0,
                actualInputTokens = it[K.actualIn] ?: 0,
                actualOutputTokens = it[K.actualOut] ?: 0,
                actualTotalTokens = it[K.actualTotal] ?: 0,
                estimatedInputTokens = it[K.estIn] ?: 0,
                estimatedOutputTokens = it[K.estOut] ?: 0,
            )
        }

    suspend fun current(): UsageStats = flow.first()

    private suspend fun update(transform: (UsageStats) -> UsageStats) {
        val next = transform(current())
        context.usageStore.edit {
            it[K.requests] = next.requests
            it[K.successes] = next.successes
            it[K.failures] = next.failures
            it[K.cacheHits] = next.cacheHits
            it[K.actualIn] = next.actualInputTokens
            it[K.actualOut] = next.actualOutputTokens
            it[K.actualTotal] = next.actualTotalTokens
            it[K.estIn] = next.estimatedInputTokens
            it[K.estOut] = next.estimatedOutputTokens
        }
    }

    suspend fun recordSuccess(actual: TokenUsage?, estimatedIn: Long, estimatedOut: Long) = update { it.recordSuccess(actual, estimatedIn, estimatedOut) }
    suspend fun recordFailure(estimatedIn: Long) = update { it.recordFailure(estimatedIn) }
    suspend fun recordCacheHit() = update { it.recordCacheHit() }
    suspend fun reset() = context.usageStore.edit { it.clear() }
}
