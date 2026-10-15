package com.fibrocoir.app.ec

import com.fibrocoir.app.core.google.Cells
import com.fibrocoir.app.core.google.Cells.cell
import com.fibrocoir.app.core.google.Cells.number
import com.fibrocoir.app.core.google.Cells.text
import com.fibrocoir.app.core.google.Row
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.round

/**
 * EC readings, as kept in the EC sheet: one tab per day per person ("2026-09-14 Selvarani"),
 * A Date time | B Sample no | C Operator | D Point | E Spot | F EC value | G Condition,
 * and the tab's own limits in AB1 (Belt), AB2 (Block), AB3 (Yard).
 */
@Serializable
data class Limits(val belt: Double = 0.70, val block: Double = 1.10, val yard: Double = 0.30) {
    fun of(point: String) = when (point) { "Belt" -> belt; "Block" -> block; else -> yard }
}

/** Limits set in EC Viewer; a null keeps the tab's own limit for that point. */
@Serializable
data class ViewerLimits(val belt: Double? = null, val block: Double? = null, val yard: Double? = null) {
    fun over(l: Limits) = Limits(belt ?: l.belt, block ?: l.block, yard ?: l.yard)
}

@Serializable
data class Reading(
    val tab: String,       // the tab it lives in
    val row: Int,
    val tsMs: Long,
    val date: String,      // the tab's day, yyyy-MM-dd
    val hhmm: String,
    val sno: String,
    val op: String,
    val point: String,
    val spot: String,
    val cond: String,
    val ec: Double,
) {
    /** Sorts correctly across months, unlike dd-MM-yyyy. */
    val key: String get() = "$date $hhmm"
    val hall: Boolean get() = point == "Belt" || point == "Block"
}

@Serializable
data class EcTab(val name: String, val date: String, val who: String, val limits: Limits, val rows: List<Reading>)

object Ec {
    val TZ: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    val POINTS = listOf("Belt", "Block", "Yard")

    fun isLogTab(name: String) = Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(name)
    fun tabDate(name: String) = name.take(10)
    fun tabWho(name: String) = name.drop(11).trim().ifEmpty { "(unnamed)" }

    fun fmt(pattern: String, ms: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TZ }.format(Date(ms))

    fun today(now: Long = System.currentTimeMillis()) = fmt("yyyy-MM-dd", now)

    fun shiftDays(date: String, n: Int): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TZ }
        val c = Calendar.getInstance(TZ).apply { time = f.parse(date)!!; add(Calendar.DAY_OF_MONTH, n) }
        return f.format(c.time)
    }

    /** Same as the scripts' cleanName_: no characters Sheets can't have in a tab name. */
    fun cleanName(s: String) = s.replace(Regex("[\\\\/?*\\[\\]:]"), " ").replace(Regex("\\s+"), " ").trim().take(40)

    /**
     * A tab's values (from A1, up to AB) to readings. Rows without a point or a number are
     * skipped, like the viewer did. `tz` is the sheet's time zone.
     */
    fun parseTab(name: String, values: List<Row>, tz: TimeZone = TZ, defaults: Limits = Limits()): EcTab {
        val date = tabDate(name)
        val who = tabWho(name)
        fun lim(i: Int, fallback: Double): Double {
            val v = values.getOrNull(i)?.cell(27).number()
            return if (v != null && v > 0) v else fallback
        }
        val limits = Limits(lim(0, defaults.belt), lim(1, defaults.block), lim(2, defaults.yard))
        val rows = ArrayList<Reading>()
        for (r in 1 until values.size) {
            val row = values[r]
            val point = row.cell(3).text().trim()
            val ec = row.cell(5).number()
            if (point.isEmpty() || ec == null || ec.isNaN()) continue
            val tsCell = row.cell(0)
            val ms = with(Cells) { tsCell.millis(tz) } ?: 0L
            rows.add(
                Reading(
                    tab = name, row = r + 1, tsMs = ms, date = date,
                    hhmm = if (ms > 0) fmt("HH:mm", ms) else "",
                    sno = row.cell(1).text(), op = row.cell(2).text().ifBlank { who },
                    point = point, spot = row.cell(4).text().ifBlank { point },
                    cond = row.cell(6).text(), ec = ec,
                )
            )
        }
        return EcTab(name, date, who, limits, rows)
    }
}

// ====================================================================== viewer maths

/** count, over-limit count, average (3 decimals), lowest, highest — like the viewer script. */
@Serializable
data class Stats(val n: Int = 0, val over: Int = 0, val sum: Double = 0.0, val min: Double? = null, val max: Double? = null) {
    val avg: Double? get() = if (n == 0) null else round(sum / n * 1000) / 1000
    fun add(ec: Double, isOver: Boolean) = Stats(
        n + 1, over + if (isOver) 1 else 0, sum + ec,
        if (min == null || ec < min) ec else min, if (max == null || ec > max) ec else max,
    )
}

fun List<Reading>.stats(limits: (Reading) -> Double): Stats =
    fold(Stats()) { acc, r -> acc.add(r.ec, r.ec > limits(r)) }

enum class Range(val label: String) { TODAY("Today"), WEEK("7 days"), MONTH("30 days"), CUSTOM("Pick dates") }

/** The viewer's date ranges: today, last 7 days, last 30 days, or two picked dates (any order). */
fun bounds(range: Range, from: String?, to: String?, today: String = Ec.today()): Pair<String, String> = when (range) {
    Range.TODAY -> today to today
    Range.WEEK -> Ec.shiftDays(today, -6) to today
    Range.MONTH -> Ec.shiftDays(today, -29) to today
    Range.CUSTOM -> {
        val ok = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        require(from != null && to != null && ok.matches(from) && ok.matches(to)) { "Pick both dates." }
        if (from <= to) from to to else to to from
    }
}

/**
 * Everything the viewer shows, from the tabs in range.
 * viewerLimits: the limits set in the viewer (replace the tab's own ones when given).
 */
class EcView(tabs: List<EcTab>, private val viewerLimits: ViewerLimits?) {
    /** oldest first */
    val tabs = tabs.sortedBy { it.name }

    /** the newest tab's limits (the sheet's), before the viewer's are applied */
    val sheetLimits: Limits = this.tabs.lastOrNull()?.limits ?: Limits()

    /** the limits in force: the newest tab's, replaced by the viewer's where set */
    val limits: Limits = viewerLimits?.over(sheetLimits) ?: sheetLimits

    private val limitOf = HashMap<String, Limits>().apply {
        this@EcView.tabs.forEach { put(it.name, viewerLimits?.over(it.limits) ?: it.limits) }
    }

    /** every reading, oldest first, with the limit that applied to it */
    val readings: List<Reading> = this.tabs.flatMap { it.rows }.sortedBy { it.key }

    fun limitFor(r: Reading): Double = (limitOf[r.tab] ?: limits).of(r.point)
    fun over(r: Reading) = r.ec > limitFor(r)

    val people: List<String> = this.tabs.map { it.who }.distinct()
    val yards: List<String> = readings.filter { it.point == "Yard" && it.spot.isNotEmpty() }.map { it.spot }.distinct().sorted()

    fun stats(list: List<Reading>) = list.stats(::limitFor)

    /** stats per key, in first-seen order */
    fun <K> by(list: List<Reading>, key: (Reading) -> K): LinkedHashMap<K, Stats> {
        val out = LinkedHashMap<K, Stats>()
        list.forEach { r -> val k = key(r); out[k] = (out[k] ?: Stats()).add(r.ec, over(r)) }
        return out
    }

    fun last(list: List<Reading>): Reading? = list.maxWithOrNull(compareBy({ it.key }, { it.tsMs }))
}

/** The readings list filters. point: all, Hall (Belt + Block), Belt, Block or Yard. */
data class Filters(
    val point: String = "all", val spot: String = "all", val cond: String = "all",
    val overOnly: Boolean = false, val text: String = "",
)

fun EcView.records(f: Filters): List<Reading> = readings.filter { r ->
    (f.point == "all" || (f.point == "Hall" && r.hall) || r.point == f.point) &&
        (f.spot == "all" || r.spot == f.spot) &&
        (f.cond == "all" || r.cond == f.cond) &&
        (!f.overOnly || over(r)) &&
        (f.text.isBlank() || "${r.sno} ${r.spot} ${r.cond} ${r.op}".lowercase().contains(f.text.trim().lowercase()))
}.sortedWith(compareByDescending<Reading> { it.date + it.hhmm }.thenByDescending { it.tsMs })
