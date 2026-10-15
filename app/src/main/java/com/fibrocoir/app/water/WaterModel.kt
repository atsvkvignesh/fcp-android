package com.fibrocoir.app.water

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/** Tankers tab: Card UID | Tanker Name | Place */
@Serializable
data class Tanker(val uid: String, val name: String, val place: String)

/** One card tap from the Log tab. ev is "IN" or "OUT". */
@Serializable
data class Tap(val t: Long, val uid: String, val name: String, val place: String, val ev: String)

/**
 * What the app keeps of the Water Load sheet.
 * lastRow  the last Log row already read, so a refresh only reads the new taps
 * fullAt   when the whole window was last read again (picks up edits and deleted rows)
 */
@Serializable
data class WaterData(
    val tankers: List<Tanker>,
    val taps: List<Tap>,
    val tz: String,
    val lastRow: Int = 0,
    val fullAt: Long = 0,
)

/** A tanker's IN tap paired with its OUT tap. Minutes are real minutes (with seconds). */
data class Trip(
    val uid: String,
    val name: String,
    val tin: Long,
    val tout: Long,
    val mins: Double,
    val day: String,
    val test: Boolean,
    val parked: Boolean,
    val back: Double?,
)

data class TankerNow(val uid: String, val name: String, val place: String, val last: Tap?, val lastTrip: Trip?)

data class DaySummary(
    val loads: Int,
    val tests: Int,
    val avgFill: Double?,
    val avgBack: Double?,
    val fastest: Double?,
    val slowest: Double?,
    val quickestBack: Double?,
)

/** The same rules as the web page, so the numbers match. */
object Water {
    const val TEST_TAP_MIN = 1.0       // IN→OUT shorter than this is a test tap, not counted
    const val NEW_DAY_GAP_H = 6.0      // a longer gap isn't a "comeback" (overnight etc.)
    const val PARKED_MIN = 180.0       // inside longer than this = parked, not a fill time
    const val DAYS_BACK = 90
    const val MAX_ROWS = 5000
    const val FILLING_FOR_MS = 12 * 3_600_000L

    // ------------------------------------------------------------------ the sheet rows

    /** One Log row (Date | Time | Place | Card UID | Tanker | Event | Minutes | Timestamp) to a tap, or null. */
    fun tapOf(row: List<Any?>, tz: TimeZone, tankers: Map<String, Tanker>, cutoff: Long): Tap? {
        val ev = str(row.getOrNull(5)).uppercase()
        if (ev != "IN" && ev != "OUT") return null
        val t = timeOf(row, tz) ?: return null
        if (t < cutoff) return null
        val uid = str(row.getOrNull(3)).uppercase()
        val name = str(row.getOrNull(4)).ifEmpty { tankers[uid]?.name ?: uid }
        return Tap(t, uid, name, str(row.getOrNull(2)), ev)
    }

    /** Column H first, then Date + Time. Numbers are Sheets day numbers. */
    fun timeOf(row: List<Any?>, tz: TimeZone): Long? {
        val h = row.getOrNull(7)
        if (h is Number) return serialToMillis(h.toDouble(), tz)
        val hs = str(h)
        if (hs.isNotEmpty()) {
            for (p in listOf("M/d/yyyy HH:mm:ss", "d/M/yyyy HH:mm:ss", "yyyy-MM-dd HH:mm:ss")) {
                parse(hs, p, tz)?.let { return it }
            }
        }
        val a = row.getOrNull(0); val b = row.getOrNull(1)
        val dayPart: Double = when {
            a is Number -> floor(a.toDouble())
            str(a).isNotEmpty() -> parse(str(a), "yyyy-MM-dd", TimeZone.getTimeZone("UTC"))?.let { it / 86_400_000.0 + 25569.0 } ?: return null
            else -> return null
        }
        val timePart: Double = when {
            b is Number -> b.toDouble() - floor(b.toDouble())
            str(b).isNotEmpty() -> parse(str(b), "HH:mm:ss", TimeZone.getTimeZone("UTC"))?.let { (it % 86_400_000L) / 86_400_000.0 } ?: return null
            else -> 0.0
        }
        return serialToMillis(dayPart + timePart, tz)
    }

    private fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }.trim()

    private fun parse(s: String, pattern: String, tz: TimeZone): Long? = try {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz; isLenient = false }.parse(s)?.time
    } catch (_: Exception) { null }

    fun serialToMillis(serial: Double, tz: TimeZone): Long {
        val local = ((serial - 25569.0) * 86_400_000.0).roundToLong()
        return local - tz.getOffset(local - tz.rawOffset)
    }

    // ------------------------------------------------------------------ trips

    fun dayKey(t: Long, tz: TimeZone): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(t))

    fun shiftDay(key: String, n: Int): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val d = f.parse(key) ?: return key
        return f.format(Date(d.time + n * 86_400_000L))
    }

    /** Pairs IN → OUT per card. A load counts on the day the tanker leaves. */
    fun trips(taps: List<Tap>, tz: TimeZone): List<Trip> {
        val sorted = taps.sortedBy { it.t }
        val open = HashMap<String, Tap>()
        val out = ArrayList<Trip>()
        val prevOut = HashMap<String, Long>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        for (e in sorted) {
            if (e.ev == "IN") { open[e.uid] = e; continue }
            val i = open.remove(e.uid) ?: continue
            val mins = (e.t - i.t) / 60_000.0
            val test = mins < TEST_TAP_MIN
            var back: Double? = null
            if (!test) {
                val p = prevOut[e.uid]
                if (p != null && (i.t - p) / 3_600_000.0 <= NEW_DAY_GAP_H) back = (i.t - p) / 60_000.0
                prevOut[e.uid] = e.t
            }
            out += Trip(e.uid, i.name.ifEmpty { e.name }, i.t, e.t, mins, dayFmt.format(Date(e.t)), test, mins >= PARKED_MIN, back)
        }
        return out
    }

    /** Every tanker on the Tankers tab plus any card seen in the log, with its last tap and last real trip. */
    fun roster(tankers: List<Tanker>, taps: List<Tap>, trips: List<Trip>): List<TankerNow> {
        val tk = LinkedHashMap<String, TankerNow>()
        tankers.forEach { tk[it.uid] = TankerNow(it.uid, it.name, it.place, null, null) }
        taps.sortedBy { it.t }.forEach { e ->
            val cur = tk[e.uid] ?: TankerNow(e.uid, e.name.ifEmpty { e.uid }, e.place, null, null)
            tk[e.uid] = cur.copy(last = e)
        }
        trips.forEach { t -> if (!t.test) tk[t.uid]?.let { tk[t.uid] = it.copy(lastTrip = t) } }
        return tk.values.toList()
    }

    fun summary(day: List<Trip>): DaySummary {
        val real = day.filter { !it.test }
        val fills = real.filter { !it.parked }.map { it.mins }
        val backs = real.mapNotNull { it.back }
        return DaySummary(
            loads = real.size, tests = day.size - real.size,
            avgFill = fills.takeIf { it.isNotEmpty() }?.average(),
            avgBack = backs.takeIf { it.isNotEmpty() }?.average(),
            fastest = fills.minOrNull(), slowest = fills.maxOrNull(), quickestBack = backs.minOrNull(),
        )
    }

    // ------------------------------------------------------------------ words

    fun dur(mins: Double): String {
        val s = max(0L, (mins * 60).roundToLong())
        val h = s / 3600; val m = s % 3600 / 60; val r = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${if (r < 10) "0" else ""}${r}s"
            else -> "${r}s"
        }
    }

    fun clock(ms: Long): String {
        val s = max(0L, ms / 1000)
        val h = s / 3600; val m = s % 3600 / 60; val r = s % 60
        fun p(x: Long) = (if (x < 10) "0" else "") + x
        return if (h > 0) "$h:${p(m)}:${p(r)}" else "${p(m)}:${p(r)}"
    }

    fun agoText(ms: Long): String {
        val m = ms / 60_000
        if (m < 1) return "just now"
        if (m < 60) return "$m min ago"
        val h = m / 60
        if (h < 24) return "${h}h ${m % 60}m ago"
        val d = h / 24
        return "$d ${if (d == 1L) "day" else "days"} ago"
    }

    fun time(t: Long, tz: TimeZone): String =
        SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(Date(t)).lowercase(Locale.US)

    fun niceDate(key: String, today: String): String = when (key) {
        today -> "Today"
        shiftDay(today, -1) -> "Yesterday"
        else -> {
            val utc = TimeZone.getTimeZone("UTC")
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.parse(key)
            if (d == null) key else SimpleDateFormat("EEEE, d MMM yyyy", Locale.US).apply { timeZone = utc }.format(d)
        }
    }

    private fun <T> pick(list: List<T>, seed: Long): T = list[(abs(floor(seed / 1000.0)).toLong() % list.size).toInt()]

    fun fillQuip(m: Double, seed: Long): String = when {
        m < 5 -> pick(listOf("⚡ Pit-stop speed!", "⚡ Zoom zoom fill."), seed)
        m < 10 -> pick(listOf("😎 Smooth and speedy.", "🏎️ Quick fill, zero drama."), seed)
        m < 15 -> pick(listOf("👌 Textbook fill.", "💯 Right on schedule."), seed)
        m < 25 -> pick(listOf("🐢 Taking it easy.", "🎶 Relaxed tempo."), seed)
        m < 60 -> pick(listOf("🐌 Chai break suspected ☕", "🐌 Was there a vadai involved?"), seed)
        else -> "😴 Over an hour. Did someone fall asleep?"
    }

    fun insideQuip(ms: Long): String {
        val m = ms / 60_000.0
        return when {
            m < 2 -> "🚿 Just rolled in. Hose engaged."
            m < 10 -> "💦 Glug glug glug…"
            m < 20 -> "🦗 Filling nicely. Patience…"
            m < 40 -> "🤨 Hmm, taking a while…"
            else -> "😴 Someone go check on them!"
        }
    }

    fun roadQuip(ms: Long): String {
        val m = ms / 60_000.0
        return when {
            m < 5 -> "💨 Just left. Vroom vroom!"
            m < 20 -> "🛣️ Out delivering the goods."
            m < 45 -> "🎵 Cruising… windows down."
            m < 120 -> "🧭 Taking the scenic route?"
            else -> "📡 Hello? Tanker, come in…"
        }
    }
}
