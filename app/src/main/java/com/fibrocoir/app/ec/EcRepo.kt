package com.fibrocoir.app.ec

import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.core.Store
import com.fibrocoir.app.core.google.Cells
import com.fibrocoir.app.core.google.Cells.cell
import com.fibrocoir.app.core.google.Cells.number
import com.fibrocoir.app.core.google.Cells.text
import com.fibrocoir.app.core.google.Req
import com.fibrocoir.app.core.google.Req.Cell
import com.fibrocoir.app.core.google.Row
import com.fibrocoir.app.core.google.SheetInfo
import com.fibrocoir.app.core.google.Sheets
import com.fibrocoir.app.core.google.Sheets.Companion.a1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.random.Random

/** What EC Log needs when it opens. */
@Serializable
data class EcBoot(
    val date: String,
    val tab: EcTab?,              // today's tab for this person, if any
    val spots: List<String>,      // yard spots used lately
    val operators: List<String>,  // people who logged lately
    val limits: Limits,           // limits a new tab starts with (the Limits tab)
)

/** One reading typed in EC Log (kept on the phone until it reaches the sheet). */
@Serializable
data class Entry(
    val operator: String, val point: String, val spot: String, val cond: String,
    val sno: String, val ec: Double, val clientTs: Long,
)

class EcRepo(private val sheets: Sheets, private val store: Store) {
    private val id = FcpSheets.EC

    // columns, 0-based (A = 0)
    private companion object {
        const val SNOX = 7; const val BELT = 8; const val BLOCK = 9; const val BELTLIM = 10; const val BLOCKLIM = 11
        const val YARD0 = 12; const val YLIM = 23; const val LIMLBL = 26; const val LIMVAL = 27; const val CHART = 30
        const val MAX_YARDS = YLIM - YARD0      // 11 yard spots per tab
        const val HEADER_BG = "#F3E4C0"
        const val SIX_HOURS = 6 * 3_600_000L
    }

    private var infoAt = 0L
    private var infoCache: SheetInfo? = null

    suspend fun info(fresh: Boolean = false): SheetInfo {
        val c = infoCache
        if (!fresh && c != null && System.currentTimeMillis() - infoAt < 30_000) return c
        return sheets.info(id).also { infoCache = it; infoAt = System.currentTimeMillis() }
    }

    // ================================================================== limits

    /** The limits a new day tab starts with (Limits tab, A2:B). */
    suspend fun defaultLimits(): Limits = parseDefaultLimits(sheets.read(id, "Limits!A2:B10"))

    private fun parseDefaultLimits(rows: List<Row>): Limits {
        var l = Limits()
        rows.forEach { r ->
            val k = r.cell(0).text().trim().lowercase(); val v = r.cell(1).number() ?: return@forEach
            if (v <= 0) return@forEach
            l = when (k) { "belt" -> l.copy(belt = v); "block" -> l.copy(block = v); "yard" -> l.copy(yard = v); else -> l }
        }
        return l
    }

    /**
     * Limits set in EC Viewer (Limits tab, D2:E4). They replace the tabs' own limits in the
     * viewer only — EC Log and the sheet's red cells keep the tab limits. Null when not set.
     */
    suspend fun viewerLimits(): ViewerLimits? {
        val rows = sheets.read(id, "Limits!D2:E4")
        val v = (0..2).map { rows.getOrNull(it)?.cell(1).number()?.takeIf { n -> n > 0 } }
        return if (v.all { it == null }) null else ViewerLimits(v[0], v[1], v[2])
    }

    /** Blank values keep the sheet's limit for that point. null clears them all. */
    suspend fun setViewerLimits(belt: Double?, block: Double?, yard: Double?) {
        if (belt == null && block == null && yard == null) {
            sheets.clear(id, "Limits!D1:E4"); return
        }
        listOf(belt, block, yard).forEach { require(it == null || (it > 0 && it <= 20)) { "Limits must be numbers between 0 and 20." } }
        sheets.write(id, "Limits!D1:E4", listOf(
            listOf("Viewer limits (EC Viewer only)", ""),
            listOf("Belt", belt ?: ""), listOf("Block", block ?: ""), listOf("Yard", yard ?: ""),
        ))
    }

    // ================================================================== reading tabs

    private fun key(name: String) = "ec.tab.$name"

    /**
     * Tabs between two dates (and one person, or everyone). Days that are over and were read
     * in the last six hours come from the phone; today's tabs are always read fresh.
     */
    suspend fun tabsBetween(from: String, to: String, who: String): List<EcTab> {
        val info = info(fresh = true)
        val today = Ec.today()
        val wanted = info.tabs.map { it.title }
            .filter { Ec.isLogTab(it) && Ec.tabDate(it) in from..to && (who == "all" || Ec.tabWho(it) == who) }
            .sorted().take(120)
        val out = LinkedHashMap<String, EcTab>()
        val toRead = ArrayList<String>()
        wanted.forEach { name ->
            val saved = store.get(key(name), EcTab.serializer())
            val closed = Ec.tabDate(name) < today
            if (saved != null && closed && System.currentTimeMillis() - saved.at < SIX_HOURS) out[name] = saved.value
            else toRead.add(name)
        }
        if (toRead.isNotEmpty()) {
            val defaults = Limits()
            sheets.read(id, toRead.map { a1(it, "A1:AB") }).forEachIndexed { i, values ->
                val tab = Ec.parseTab(toRead[i], values, info.timeZone, defaults)
                store.put(key(toRead[i]), EcTab.serializer(), tab)
                out[toRead[i]] = tab
            }
        }
        return wanted.mapNotNull { out[it] }
    }

    /** Everyone who has a log tab, newest tabs first. */
    suspend fun people(): List<String> =
        info().tabs.map { it.title }.filter(Ec::isLogTab).sortedDescending().map(Ec::tabWho).distinct()

    // ================================================================== EC Log

    suspend fun boot(operator: String): EcBoot {
        val info = info(fresh = true)
        val date = Ec.today()
        val op = Ec.cleanName(operator)
        val mine = if (op.isNotEmpty()) "$date $op" else null
        // newest tabs are inserted first, so the first 14 by position are the recent ones
        val recent = info.tabs.map { it.title }.filter(Ec::isLogTab).take(14)
        val ranges = ArrayList<String>()
        ranges.add("Limits!A2:B10")
        recent.forEach { ranges.add(a1(it, "C2:E")) }
        val hasMine = mine != null && info.tab(mine) != null
        if (hasMine) ranges.add(a1(mine!!, "A1:AB"))
        val res = sheets.read(id, ranges)

        val limits = parseDefaultLimits(res[0])
        val spots = ArrayList<String>(); val ops = ArrayList<String>()
        val seenS = HashSet<String>(); val seenO = HashSet<String>()
        recent.forEachIndexed { i, name ->
            val fromName = name.drop(11).trim()
            if (fromName.isNotEmpty() && seenO.add(fromName.lowercase())) ops.add(fromName)
            res[1 + i].forEach { r ->
                val s = r.cell(2).text().trim()
                if (s.isNotEmpty() && s != "Belt" && s != "Block" && seenS.add(s.lowercase())) spots.add(s)
                val o = r.cell(0).text().trim()
                if (o.isNotEmpty() && seenO.add(o.lowercase())) ops.add(o)
            }
        }
        val tab = if (hasMine) Ec.parseTab(mine!!, res.last(), info.timeZone, limits) else null
        return EcBoot(date, tab, spots.take(14), ops.take(12), limits)
    }

    /**
     * Saves one reading into today's tab for that person, making the tab (with its limit box,
     * red-cell rules, hidden chart helpers and charts) if it is new — exactly as the script did.
     * A reading sent twice (e.g. retried after a lost answer) is only written once.
     */
    suspend fun add(e: Entry): EcTab {
        val operator = Ec.cleanName(e.operator)
        validate(operator, e.point, e.spot, e.cond, e.sno, e.ec)
        val point = e.point
        val spot = if (point == "Yard") e.spot.trim() else point
        val cond = if (point == "Yard") e.cond.trim().take(40) else ""
        val date = Ec.today()
        val name = "$date $operator"

        val info = info(fresh = true)
        var sheetId = info.tab(name)?.id
        val limits: Limits
        val values: List<Row>
        if (sheetId == null) {
            limits = defaultLimits()
            sheetId = createTab(name, limits)
            values = listOf(emptyList())
        } else {
            values = sheets.read(id, a1(name, "A1:AB"))
            limits = Ec.parseTab(name, values, info.timeZone).limits
        }

        // the same reading sent again must not land twice
        val tz = info.timeZone
        values.takeLast(40).forEach { r ->
            val t = with(Cells) { r.cell(0).millis(tz) } ?: return@forEach
            if (abs(t - e.clientTs) < 1000 && r.cell(1).text() == e.sno.trim() && r.cell(3).text() == point) {
                return Ec.parseTab(name, values, tz, limits)
            }
        }

        val rowNo = maxOf(values.size + 1, 2)
        val head = values.firstOrNull().orEmpty()
        val yard = if (point == "Yard") yardCol(head, spot) else null
        val reqs = ArrayList<JsonObject>()
        reqs.add(Req.updateCells(sheetId, rowNo - 1, 0, listOf(
            listOf(Cell.Num(Cells.millisToSerial(e.clientTs, tz), "dd-mm-yyyy hh:mm:ss", "DATE_TIME")) +
                textCell(e.sno.trim()) + listOf(Cell.Text(operator), Cell.Text(point), Cell.Text(spot)) +
                Cell.Num(e.ec, "0.00", "NUMBER") + Cell.Text(cond) + helpers(rowNo, point, yard?.first, e.ec, e.sno)
        )))
        if (yard != null && yard.second) reqs.addAll(newYardHeader(sheetId, yard.first, spot))
        sheets.batchUpdate(id, reqs)
        if (yard != null && yard.second) rebuildCharts(sheetId, head, yard.first, spot)

        val tab = Ec.parseTab(name, values, tz, limits)
        val added = Reading(name, rowNo, e.clientTs, date, Ec.fmt("HH:mm", e.clientTs), e.sno.trim(), operator, point, spot, cond, e.ec)
        return tab.copy(rows = tab.rows + added).also { store.put(key(name), EcTab.serializer(), it) }
    }

    /** Fix a reading: keeps its time and person, rewrites the rest (Head QC only, checked in the app). */
    suspend fun update(date: String, operator: String, row: Int, oldSno: String, p: Entry): EcTab {
        validate(Ec.cleanName(operator), p.point, p.spot, p.cond, p.sno, p.ec)
        val name = "$date ${Ec.cleanName(operator)}"
        val info = info(fresh = true)
        val sheetId = info.tab(name)?.id ?: error("No readings for that day.")
        val values = sheets.read(id, a1(name, "A1:AB"))
        if (values.getOrNull(row - 1)?.cell(1).text() != oldSno) error("The list moved on. Refresh and try the edit again.")
        val point = p.point
        val spot = if (point == "Yard") p.spot.trim() else point
        val cond = if (point == "Yard") p.cond.trim().take(40) else ""
        val head = values.firstOrNull().orEmpty()
        val yard = if (point == "Yard") yardCol(head, spot) else null
        val reqs = ArrayList<JsonObject>()
        reqs.add(Req.updateCells(sheetId, row - 1, 1, listOf(listOf(textCell(p.sno.trim())))))
        reqs.add(Req.updateCells(sheetId, row - 1, 3, listOf(
            listOf(Cell.Text(point), Cell.Text(spot), Cell.Num(p.ec, "0.00", "NUMBER"), Cell.Text(cond)) +
                helpers(row, point, yard?.first, p.ec, p.sno)
        )))
        if (yard != null && yard.second) reqs.addAll(newYardHeader(sheetId, yard.first, spot))
        sheets.batchUpdate(id, reqs)
        if (yard != null && yard.second) rebuildCharts(sheetId, head, yard.first, spot)
        return readTab(name)
    }

    suspend fun remove(date: String, operator: String, row: Int, sno: String): EcTab {
        val name = "$date ${Ec.cleanName(operator)}"
        val sheetId = info(fresh = true).tab(name)?.id ?: error("No readings for that day.")
        val cur = sheets.read(id, a1(name, "B$row"))
        if (cur.firstOrNull()?.cell(0).text() != sno) error("The list moved on. Refresh and try again.")
        sheets.batchUpdate(id, listOf(Req.deleteRows(sheetId, row - 1, row)))
        return readTab(name)
    }

    suspend fun readTab(name: String): EcTab {
        val info = info()
        return Ec.parseTab(name, sheets.read(id, a1(name, "A1:AB")), info.timeZone)
            .also { store.put(key(name), EcTab.serializer(), it) }
    }

    private fun validate(operator: String, point: String, spot: String, cond: String, sno: String, ec: Double) {
        require(operator.isNotEmpty()) { "Enter who is taking the reading." }
        require(point in Ec.POINTS) { "Pick Belt, Block or Yard." }
        if (point == "Yard") {
            require(spot.isNotBlank()) { "Pick the yard spot." }
            require(cond.isNotBlank()) { "Pick the material condition." }
        }
        require(sno.isNotBlank()) { "Enter the sample no." }
        require(ec.isFinite() && ec >= 0 && ec <= 100) { "Enter a valid EC value." }
    }

    // ------------------------------------------------------------------ tab building (as the script)

    private fun textCell(s: String) = Cell.Text(s, "@", "TEXT")

    /** H..X for one row: sample no as a number (the chart axis), the value in its column, limit formulas. */
    private fun helpers(rowNo: Int, point: String, yardCol: Int?, ec: Double, sno: String): List<Cell> {
        val out = MutableList<Cell>(YLIM - SNOX + 1) { Cell.Empty }
        val x = sno.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
        out[0] = Cell.Num(if (x != null && x != 0.0) x else (rowNo - 1).toDouble())
        when (point) {
            "Belt" -> out[BELT - SNOX] = Cell.Num(ec)
            "Block" -> out[BLOCK - SNOX] = Cell.Num(ec)
            "Yard" -> if (yardCol != null) out[yardCol - SNOX] = Cell.Num(ec)
        }
        out[BELTLIM - SNOX] = Cell.Formula("=\$AB\$1")
        out[BLOCKLIM - SNOX] = Cell.Formula("=\$AB\$2")
        out[YLIM - SNOX] = Cell.Formula("=\$AB\$3")
        return out
    }

    /** The yard's helper column, adding the spot to the header if it is new. */
    private fun yardCol(head: Row, spot: String): Pair<Int, Boolean> {
        for (i in 0 until MAX_YARDS) if (head.cell(YARD0 + i).text().trim().equals(spot, ignoreCase = true)) return (YARD0 + i) to false
        for (i in 0 until MAX_YARDS) if (head.cell(YARD0 + i).text().trim().isEmpty()) return (YARD0 + i) to true
        error("This tab already has $MAX_YARDS yard spots.")
    }

    private fun newYardHeader(sheetId: Int, col: Int, spot: String) = listOf(
        Req.updateCells(sheetId, 0, col, listOf(listOf(Cell.Text(spot)))),
        Req.format(Req.range(sheetId, 0, 1, col, col + 1), bold = true, bg = HEADER_BG),
    )

    private suspend fun createTab(name: String, lim: Limits): Int {
        val sid = Random.nextInt(100_000_000, 2_000_000_000)
        val reqs = ArrayList<JsonObject>()
        reqs.add(Req.addSheet(name, 0, sheetId = sid))   // our own id, so the requests below can refer to it
        val headA = listOf("Date time", "Sample no", "Operator", "Point", "Spot", "EC value", "Condition").map { Cell.Text(it) }
        val headH = listOf("Sample no", "Belt", "Block", "Belt limit", "Block limit").map { Cell.Text(it) }
        reqs.add(Req.updateCells(sid, 0, 0, listOf(headA + headH)))
        reqs.add(Req.updateCells(sid, 0, YLIM, listOf(listOf(Cell.Text("Yard limit")))))
        reqs.add(Req.updateCells(sid, 0, LIMLBL, listOf(
            listOf(Cell.Text("Belt limit"), Cell.Num(lim.belt, "0.00", "NUMBER")),
            listOf(Cell.Text("Block limit"), Cell.Num(lim.block, "0.00", "NUMBER")),
            listOf(Cell.Text("Yard limit"), Cell.Num(lim.yard, "0.00", "NUMBER")),
        )))
        reqs.add(Req.updateCells(sid, 4, LIMLBL, listOf(listOf(Cell.Text("Change a limit here — the red cells and the charts follow it.")))))
        reqs.add(Req.format(Req.range(sid, 0, 1, 0, 7), bold = true, bg = HEADER_BG))
        reqs.add(Req.format(Req.range(sid, 0, 1000, 1, 2), numberType = "TEXT", pattern = "@"))
        reqs.add(Req.format(Req.range(sid, 0, 1, SNOX, YLIM + 1), bold = true, bg = HEADER_BG))
        reqs.add(Req.format(Req.range(sid, 0, 3, LIMLBL, LIMLBL + 1), bold = true, bg = HEADER_BG))
        reqs.add(Req.format(Req.range(sid, 0, 3, LIMVAL, LIMVAL + 1), bg = "#FFF8E6", numberType = "NUMBER", pattern = "0.00"))
        reqs.add(Req.border(Req.range(sid, 0, 3, LIMVAL, LIMVAL + 1)))
        reqs.add(Req.format(Req.range(sid, 4, 5, LIMLBL, LIMLBL + 1), italic = true, fg = "#7A6A50"))
        reqs.add(Req.columnWidth(sid, 0, 150))
        reqs.add(Req.columnWidth(sid, LIMLBL, 200))
        reqs.add(Req.freezeRows(sid, 1))
        val ecCol = Req.range(sid, 1, 1000, 5, 6)
        reqs.add(Req.redWhen(ecCol, "=AND(\$D2=\"Belt\",\$F2>\$AB\$1)", 0))
        reqs.add(Req.redWhen(ecCol, "=AND(\$D2=\"Block\",\$F2>\$AB\$2)", 1))
        reqs.add(Req.redWhen(ecCol, "=AND(\$D2=\"Yard\",\$F2>\$AB\$3)", 2))
        reqs.add(Req.hideColumns(sid, SNOX, YLIM + 1))
        reqs.add(beltBlockChart(sid))
        val replies = sheets.batchUpdate(id, reqs)
        infoCache = null
        return replies.firstOrNull()?.jsonObject?.get("addSheet")?.jsonObject?.get("properties")?.jsonObject
            ?.get("sheetId")?.jsonPrimitive?.int ?: sid
    }

    private fun beltBlockChart(sid: Int) = Req.lineChart(
        sid, "Belt and Block EC", SNOX,
        listOf(Req.Line(BELT, "#1F6FB2"), Req.Line(BLOCK, "#2E8B57"), Req.Line(BELTLIM, "#1F6FB2", true), Req.Line(BLOCKLIM, "#2E8B57", true)),
        anchorRow = 1, anchorCol = CHART,
    )

    /** A new yard spot: redraw the tab's charts so it gets its own. */
    private suspend fun rebuildCharts(sid: Int, head: Row, newCol: Int, newSpot: String) {
        val names = (0 until MAX_YARDS).map { i -> if (YARD0 + i == newCol) newSpot else head.cell(YARD0 + i).text().trim() }
        val reqs = ArrayList<JsonObject>()
        sheets.chartIds(id, sid).forEach { reqs.add(Req.deleteChart(it)) }
        reqs.add(beltBlockChart(sid))
        var anchor = 21
        names.forEachIndexed { i, n ->
            if (n.isEmpty()) return@forEachIndexed
            reqs.add(Req.lineChart(sid, "Yard — $n", SNOX, listOf(Req.Line(YARD0 + i, "#B8860B"), Req.Line(YLIM, "#C0392B", true)), anchor, CHART))
            anchor += 20
        }
        reqs.add(Req.hideColumns(sid, SNOX, YLIM + 1))
        sheets.batchUpdate(id, reqs)
    }

    // ================================================================== EC Viewer export

    /** Writes the readings into the "Export view" tab; returns the link to it. */
    suspend fun export(rows: List<Reading>, over: (Reading) -> Boolean): String {
        val tabName = "Export view"
        var sid = info(fresh = true).tab(tabName)?.id
        if (sid == null) {
            val r = sheets.batchUpdate(id, listOf(Req.addSheet(tabName, 999, 1000, 12)))
            sid = r.first().jsonObject["addSheet"]!!.jsonObject["properties"]!!.jsonObject["sheetId"]!!.jsonPrimitive.int
            infoCache = null
        }
        sheets.clear(id, a1(tabName))
        val head = listOf("Date", "Time", "Sample no", "Operator", "Point", "Spot", "Condition", "EC value", "Over limit")
        sheets.write(id, a1(tabName, "A1"), listOf(head) + rows.map {
            listOf(it.date, it.hhmm, it.sno, it.op, it.point, it.spot, it.cond, it.ec, if (over(it)) "Yes" else "")
        })
        sheets.batchUpdate(id, listOf(
            Req.format(Req.range(sid, 0, 1, 0, head.size), bold = true, bg = HEADER_BG),
            Req.setFilter(Req.range(sid, 0, rows.size + 1, 0, head.size)),
            Req.freezeRows(sid, 1),
            Req.autoResize(sid, 0, head.size),
        ))
        return FcpSheets.url(id, sid)
    }
}
