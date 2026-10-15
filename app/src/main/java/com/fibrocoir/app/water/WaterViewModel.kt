package com.fibrocoir.app.water

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Everything the screen draws, worked out once per refresh. */
class WaterView(val data: WaterData) {
    val tz: TimeZone = TimeZone.getTimeZone(data.tz)
    val trips: List<Trip> = Water.trips(data.taps, tz)
    val roster: List<TankerNow> = Water.roster(data.tankers, data.taps, trips)
    private val byDay: Map<String, List<Trip>> = trips.groupBy { it.day }

    fun today(now: Long = System.currentTimeMillis()) = Water.dayKey(now, tz)
    fun day(key: String): List<Trip> = byDay[key].orEmpty()

    /** Real loads per day for the last `n` days ending on `last`. */
    fun loadsPerDay(last: String, n: Int): List<Pair<String, Int>> =
        (n - 1 downTo 0).map { i -> Water.shiftDay(last, -i).let { d -> d to day(d).count { !it.test } } }
}

class WaterViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = WaterRepo(fcp.sheets)

    val data = Loader(viewModelScope, fcp.store, WaterData.serializer())
    /** The day being looked at; null means today. */
    val date = MutableStateFlow<String?>(null)
    /** "Load done!" style messages when something new shows up. */
    val news = MutableSharedFlow<String>(extraBufferCapacity = 4)
    var nextLoad = 0L
        private set

    private var newestOut = 0L
    private var newestIn = 0L

    init { refresh() }

    /** full = read the whole window again (pull to refresh); otherwise only the new rows. */
    fun refresh(full: Boolean = false) {
        if (data.state.value.loading && data.state.value.data != null) return
        nextLoad = System.currentTimeMillis() + REFRESH_MS
        data.load(KEY) {
            val saved = fcp.store.get(KEY, WaterData.serializer())?.value
            repo.load(saved, full)
        }.invokeOnCompletion { cause ->
            if (cause != null) return@invokeOnCompletion
            val st = data.state.value
            if (st.error != null) { nextLoad = System.currentTimeMillis() + 60_000; return@invokeOnCompletion }
            viewModelScope.launch { st.data?.let { announce(it) } }
        }
    }

    private suspend fun announce(d: WaterData) {
        val v = WaterView(d)
        val lastTrip = v.trips.lastOrNull()
        val lastIn = d.taps.filter { it.ev == "IN" }.maxByOrNull { it.t }
        if (newestOut > 0 && lastTrip != null && lastTrip.tout > newestOut && !lastTrip.test) {
            news.emit(
                if (lastTrip.parked) "🚚 ${lastTrip.name} left with a load after parking ${Water.dur(lastTrip.mins)}"
                else "💦 Load done! ${lastTrip.name} filled in ${Water.dur(lastTrip.mins)}"
            )
        } else if (newestIn > 0 && lastIn != null && lastIn.t > newestIn) {
            news.emit("🚚 ${lastIn.name} just rolled in. Filling time!")
        }
        newestOut = lastTrip?.tout ?: 1
        newestIn = lastIn?.t ?: 1
    }

    fun pick(day: String?, today: String) {
        date.value = if (day == null || day >= today) null else day
    }

    companion object {
        const val KEY = "water"
        const val REFRESH_MS = 5 * 60_000L
    }
}
