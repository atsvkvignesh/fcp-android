package com.fibrocoir.app.water

import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.core.google.Row
import com.fibrocoir.app.core.google.Sheets
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.TimeZone

/**
 * Reads the Water Load sheet. Never writes to it (the ESP32 card reader owns it).
 *
 * The first read takes the latest rows of the Log tab; after that only the rows added since
 * are read, so the 5-minute refresh costs almost nothing. Every half hour (or on pull to
 * refresh) the whole window is read again, which picks up rows someone edited or deleted.
 */
class WaterRepo(private val sheets: Sheets) {
    private val id = FcpSheets.WATER

    suspend fun load(saved: WaterData?, full: Boolean): WaterData {
        val now = System.currentTimeMillis()
        val cutoff = now - Water.DAYS_BACK * 86_400_000L
        val incremental = !full && saved != null && saved.lastRow > 1 && now - saved.fullAt < FULL_EVERY_MS

        if (incremental) {
            val tz = TimeZone.getTimeZone(saved!!.tz)
            // start at the last row already read: a range past the end of the grid is refused
            val start = saved.lastRow
            val got = try {
                sheets.read(id, listOf("'$TANKERS'!A2:C", "'$LOG'!A$start:H"))
            } catch (e: com.fibrocoir.app.core.google.OfflineException) {
                throw e
            } catch (e: Exception) {
                null   // rows were removed from the sheet: read the whole window again below
            }
            if (got != null) {
                val tankers = tankersOf(got[0])
                val byUid = tankers.associateBy { it.uid }
                val newRows = got[1].drop(1)
                val fresh = newRows.mapNotNull { Water.tapOf(plain(it), tz, byUid, cutoff) }
                return saved.copy(
                    tankers = tankers,
                    taps = saved.taps.filter { it.t >= cutoff } + fresh,
                    lastRow = saved.lastRow + newRows.size,
                )
            }
        }

        val info = sheets.info(id)
        val log = info.tab(LOG) ?: throw IllegalStateException("The Water Load sheet has no \"$LOG\" tab.")
        val tz = info.timeZone
        // the grid can be a little longer than the data; read a margin so we still get MAX_ROWS taps
        val start = maxOf(2, log.rows - Water.MAX_ROWS - 1000 + 1)
        val (tRows, lRows) = sheets.read(id, listOf("'$TANKERS'!A2:C", "'$LOG'!A$start:H")).let { it[0] to it[1] }
        val tankers = tankersOf(tRows)
        val byUid = tankers.associateBy { it.uid }
        val last = lRows.takeLast(Water.MAX_ROWS)
        val taps = last.mapNotNull { Water.tapOf(plain(it), tz, byUid, cutoff) }
        return WaterData(tankers, taps, tz.id, lastRow = start + lRows.size - 1, fullAt = now)
    }

    private fun tankersOf(rows: List<Row>): List<Tanker> {
        val seen = LinkedHashMap<String, Tanker>()
        rows.forEach { r ->
            val p = plain(r)
            val uid = text(p.getOrNull(0)).uppercase()
            if (uid.isNotEmpty()) seen[uid] = Tanker(uid, text(p.getOrNull(1)).ifEmpty { uid }, text(p.getOrNull(2)))
        }
        return seen.values.toList()
    }

    private fun text(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }.trim()

    /** Sheet cells as plain values: numbers stay numbers (dates are day numbers), the rest is text. */
    private fun plain(r: Row): List<Any?> = r.map { c ->
        val p = c as? JsonPrimitive
        when {
            p == null -> null
            p.isString -> p.content
            else -> p.doubleOrNull ?: p.content
        }
    }

    companion object {
        const val LOG = "Log"
        const val TANKERS = "Tankers"
        const val FULL_EVERY_MS = 30 * 60_000L
    }
}
