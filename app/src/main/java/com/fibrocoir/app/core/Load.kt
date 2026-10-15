package com.fibrocoir.app.core

import com.fibrocoir.app.core.google.OfflineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

/**
 * What a screen shows for one piece of data.
 * data      the latest we have (saved copy or fresh), null if nothing yet
 * at        when that data was fetched from the sheet
 * loading   a refresh is running
 * error     the last refresh failed (data, if any, is the older copy)
 */
data class Load<T>(
    val data: T? = null,
    val at: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
) {
    val stale: Boolean get() = error != null && data != null
}

/**
 * Saved copy first, then fresh data. Only the newest load() call updates the state, so quickly
 * switching filters never shows an older answer.
 */
class Loader<T>(
    private val scope: CoroutineScope,
    private val store: Store,
    private val serializer: KSerializer<T>,
) {
    val state = MutableStateFlow(Load<T>())
    private var job: Job? = null
    private var seq = 0

    /**
     * key       where the saved copy lives (include every filter that changes the answer)
     * maxAgeMs  if the saved copy is younger than this, don't fetch again
     */
    fun load(key: String, maxAgeMs: Long = 0, fetch: suspend () -> T): Job {
        val my = ++seq
        job?.cancel()
        val saved = store.get(key, serializer)
        state.value = if (saved != null) Load(saved.value, saved.at, loading = true) else Load(loading = true)
        if (saved != null && maxAgeMs > 0 && System.currentTimeMillis() - saved.at < maxAgeMs) {
            state.update { it.copy(loading = false) }
            return Job().also { it.complete() }
        }
        return scope.launch {
            try {
                val fresh = fetch()
                if (my != seq) return@launch
                store.put(key, serializer, fresh)
                state.value = Load(fresh, System.currentTimeMillis())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (my != seq) return@launch
                state.update { it.copy(loading = false, error = e.message ?: "Something went wrong.", offline = e is OfflineException) }
            }
        }.also { job = it }
    }

    /** Show this value now (e.g. what a save returned) and keep it as the saved copy. */
    fun set(key: String, value: T) {
        seq++
        job?.cancel()
        store.put(key, serializer, value)
        state.value = Load(value, System.currentTimeMillis())
    }
}

/** "just now", "5 min ago", "2 h ago", "yesterday 18:40", "12 Oct 09:15" */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val d = now - ms
    if (d < 45_000) return "just now"
    if (d < 3_600_000) return "${d / 60_000} min ago"
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    return when {
        ms >= today -> "${d / 3_600_000} h ago"
        ms >= today - 86_400_000 -> "yesterday $hm"
        else -> java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    }
}
