package com.fibrocoir.app.husk

import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.core.Store
import com.fibrocoir.app.core.google.Cells
import com.fibrocoir.app.core.google.OfflineException
import com.fibrocoir.app.core.google.Req
import com.fibrocoir.app.core.google.Sheets
import com.fibrocoir.app.core.google.Sheets.Companion.a1
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reads our loads and the vendor slips, and saves match decisions to "RM Matches".
 * The two form tabs are never edited.
 */
class HuskRepo(private val sheets: Sheets, private val store: Store) {
    private val companyId = FcpSheets.HUSK_COMPANY
    private val vendorId = FcpSheets.HUSK_VENDOR
    private val matchId = FcpSheets.HUSK_VENDOR

    /** Where the data sits, found once from the headers and kept. */
    @Serializable
    data class Layout(
        val companyTab: String, val vendorTab: String,
        val companyTz: String, val vendorTz: String,
        val companyDmy: Boolean, val vendorDmy: Boolean,
    )

    private suspend fun detect(): Layout = coroutineScope {
        val ci = async { sheets.info(companyId) }
        val vi = async { sheets.info(vendorId) }
        val c = ci.await(); val v = vi.await()
        val cHeads = sheets.read(companyId, c.tabs.map { a1(it.title, "1:1") })
        val vTabs = v.tabs.filter { it.title != Rm.MATCH_TAB && it.title != Rm.EXPORT_TAB }
        val vHeads = sheets.read(vendorId, vTabs.map { a1(it.title, "1:1") })
        val cTab = c.tabs.indices.firstOrNull { Rm.isCompanyHeader(Cells.plain(cHeads[it].firstOrNull().orEmpty())) }?.let { c.tabs[it].title }
            ?: throw IllegalStateException("Could not find the loads tab in the company sheet (needs \"Number of Green Husk\" and \"Driver Name\" headers).")
        val vTab = vTabs.indices.firstOrNull { Rm.isVendorHeader(Cells.plain(vHeads[it].firstOrNull().orEmpty())) }?.let { vTabs[it].title }
            ?: throw IllegalStateException("Could not find the vendor tab (needs \"Husk Type\" and \"Number of Husk\" headers).")
        Layout(cTab, vTab, c.timeZone.id, v.timeZone.id, !c.locale.startsWith("en_US"), !v.locale.startsWith("en_US"))
            .also { store.put(LAYOUT, Layout.serializer(), it) }
    }

    private class Tabs(val company: List<List<Any?>>, val vendor: List<List<Any?>>, val matches: List<List<Any?>>)

    private suspend fun readTabs(l: Layout): Tabs = coroutineScope {
        val c = async { sheets.read(companyId, a1(l.companyTab)) }
        val v = async { sheets.read(vendorId, listOf(a1(l.vendorTab), a1(Rm.MATCH_TAB, "A1:R"))) }
        val vv = v.await()
        Tabs(c.await().map(Cells::plain), vv[0].map(Cells::plain), vv[1].map(Cells::plain))
    }

    /** Fresh data from both sheets (2 requests once the tabs are known). */
    suspend fun load(): RmRaw {
        var l = store.get(LAYOUT, Layout.serializer())?.value
        var t: Tabs? = null
        if (l != null) {
            t = try { readTabs(l) } catch (e: OfflineException) { throw e } catch (e: Exception) { null }
            // a tab was renamed or its headers changed: look again
            if (t != null && (!Rm.isCompanyHeader(t.company.firstOrNull().orEmpty()) || !Rm.isVendorHeader(t.vendor.firstOrNull().orEmpty()))) t = null
        }
        if (t == null) { l = detect(); t = readTabs(l) }
        val cTz = TimeZone.getTimeZone(l!!.companyTz); val vTz = TimeZone.getTimeZone(l.vendorTz)
        val vr = Rm.readVendor(t.vendor, vTz, l.vendorDmy)
        return RmRaw(
            loads = Rm.readCompany(t.company, cTz, l.companyDmy),
            slips = vr.rows,
            groups = Rm.readGroups(t.matches, TimeZone.getTimeZone(Rm.TZ)),
            hasVendorCol = vr.hasVendor,
            companyTab = l.companyTab, vendorTab = l.vendorTab,
        )
    }

    /** Saves one decision. Returns the fresh data and the new group ids (for Undo). */
    suspend fun save(status: String, cks: List<String>, vks: List<String>, note: String, vendorName: String, by: String): Pair<RmRaw, List<String>> {
        Rm.checkSave(status, cks, vks)?.let { throw IllegalArgumentException(it) }
        val raw = load()
        val rows = Rm.rowsToSave(status, cks, vks, note, vendorName, raw.loads, raw.slips, raw.groups)
        ensureMatchTab()
        val now = System.currentTimeMillis()
        val created = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone(Rm.TZ) }.format(Date(now))
        val ids = rows.map { Rm.newId(now) }.let { l -> if (l.toSet().size == l.size) l else l.mapIndexed { i, s -> "$s$i" } }
        val values = rows.mapIndexed { i, r ->
            listOf<Any?>(ids[i]) + r.cells().map { if (it is String) safeText(it) else it } + listOf(created, safeText(by))
        }
        sheets.append(matchId, a1(Rm.MATCH_TAB, "A1:R"), values, userEntered = true)
        val createdShown = Rm.fmt(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(created)!!.time, "d MMM yyyy, h:mm a")
        val added = rows.mapIndexed { i, r ->
            SavedGroup(ids[i], r.status, r.dk, r.vendor, r.note, r.cks.split(',').filter { it.isNotEmpty() },
                r.vks.split(',').filter { it.isNotEmpty() }, createdShown, by)
        }
        return raw.copy(groups = raw.groups + added) to ids
    }

    /** Removes saved decisions; their rows go back to "To check". */
    suspend fun undo(ids: List<String>): RmRaw {
        if (ids.isEmpty()) throw IllegalArgumentException("Nothing to undo.")
        val info = sheets.info(matchId)
        val tab = info.tab(Rm.MATCH_TAB) ?: return load()
        val col = sheets.read(matchId, a1(Rm.MATCH_TAB, "A2:A"))
        val reqs = ArrayList<JsonObject>()
        for (i in col.indices.reversed()) {
            val id = Cells.plain(col[i]).firstOrNull()?.let { Rm.str(it) } ?: continue
            if (id in ids) reqs += Req.deleteRows(tab.id, i + 1, i + 2)    // row i+2, 0-based i+1
        }
        sheets.batchUpdate(matchId, reqs)
        return load()
    }

    /** Writes the summary and details to the "RM Check Export" tab; returns its link. */
    suspend fun export(raw: RmRaw, f: RmFilter): Pair<String, Int> {
        val b = Rm.build(raw, f)
        val s = b.summary; val m = s.m
        val w = 17
        val out = ArrayList<List<Any?>>(); val heads = ArrayList<Int>()
        fun row(vararg a: Any?) { out += List(w) { a.getOrNull(it) ?: "" } }
        fun head(vararg a: Any?) { heads += out.size; row(*a) }
        val now = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).apply { timeZone = TimeZone.getTimeZone(Rm.TZ) }.format(Date())

        head("FCP raw material check")
        row("Vendor", b.meta.vendor.ifEmpty { "All vendors" })
        row("From", f.from.ifEmpty { "start" }, "To", f.to.ifEmpty { "latest" })
        row("Generated", now)
        row()
        head("Matched loads", "Value")
        row("Matched groups", s.groups)
        row("Driver count", m.driver)
        row("Vendor count", m.vendor)
        row("CFT count", m.cft, if (m.noCft > 0) "${m.noCft.toInt()} load(s) without CFT" else "")
        row("Driver − Vendor", m.dV)
        row("Driver − CFT", m.dC)
        row("Vendor − CFT", m.vC)
        row("Company total", m.ourCost)
        row("Vendor total", m.vendorCost)
        row("CFT value", m.cftCost)
        row("Company − Vendor", m.cV)
        row("Company − CFT value", m.cC)
        row("Vendor − CFT value", m.vCc)
        row()
        head("Other", "Count", "Husk", "Amount")
        row("Missing at vendor (our loads)", s.missV.n, s.missV.husk, s.missV.cost)
        row("Missing at our end (vendor entries)", s.missF.n, s.missF.husk, s.missF.cost)
        row("Still to check: ours", s.pendC.n, s.pendC.husk, s.pendC.cost)
        row("Still to check: vendor", s.pendV.n, s.pendV.husk, s.pendV.cost)
        row("Vendor claim (all entries)", "", "", s.vendorAll)
        row("Our record (all loads)", "", "", s.ourAll)
        row("Our record − vendor claim", "", "", Rm.r2(s.ourAll - s.vendorAll))
        row()
        head("Date", "Vendor", "Our loads", "Vendor entries", "Driver count", "CFT count", "Vendor count",
            "Driver − Vendor", "Driver − CFT", "Vendor − CFT", "Company total", "Vendor total", "CFT value",
            "Company − Vendor", "Company − CFT", "Vendor − CFT", "Note")
        b.groups.filter { it.status == Rm.MATCHED }.sortedBy { it.dk }.forEach { g ->
            val x = g.m
            row(g.dk, g.vendor,
                g.cs.joinToString(" | ") { c -> listOf(c.driver, c.truck, c.place).filter { it.isNotEmpty() }.joinToString(" ") },
                g.vs.joinToString(" | ") { v -> listOf(v.place, v.type).filter { it.isNotEmpty() }.joinToString(" ") },
                x.driver, x.cft, x.vendor, x.dV, x.dC, x.vC, x.ourCost, x.vendorCost, x.cftCost, x.cV, x.cC, x.vCc, g.note)
        }
        row()
        head("Missing at vendor", "Vendor", "Driver", "Truck", "Place", "Unloading", "Green", "Brown", "Husk", "Our cost", "Note")
        b.groups.filter { it.status == Rm.MISS_V }.sortedBy { it.dk }.forEach { g -> g.cs.forEach { c ->
            row(c.dk, c.vendor, c.driver, c.truck, c.place, c.unload, c.green, c.brown, c.count, c.cost, g.note.ifEmpty { c.note })
        } }
        row()
        head("Missing at our end", "Vendor", "Place", "Type", "Husk", "Rate", "Vendor cost", "Note")
        b.groups.filter { it.status == Rm.MISS_F }.sortedBy { it.dk }.forEach { g -> g.vs.forEach { v ->
            row(v.dk, v.vendor, v.place, v.type, v.count, v.rate, v.total, g.note.ifEmpty { v.note })
        } }

        val info = sheets.info(matchId)
        var tab = info.tab(Rm.EXPORT_TAB)
        if (tab == null) {
            sheets.batchUpdate(matchId, listOf(Req.addSheet(Rm.EXPORT_TAB, info.tabs.size)))
            tab = sheets.info(matchId).tab(Rm.EXPORT_TAB) ?: throw IllegalStateException("Couldn't add the export tab.")
        }
        val sid = tab.id
        sheets.clear(matchId, a1(Rm.EXPORT_TAB))
        sheets.batchUpdate(matchId, listOf(
            Req.format(Req.range(sid, 0, tab.rows, 0, minOf(w, tab.cols)), bold = false, bg = "#FFFFFF"),
        ))
        sheets.write(matchId, a1(Rm.EXPORT_TAB, "A1"), out)
        sheets.batchUpdate(matchId, heads.map { r -> Req.format(Req.range(sid, r, r + 1, 0, w), bold = true, bg = "#F6EEDC") } +
            Req.columnWidth(sid, 0, 230))
        return FcpSheets.url(matchId, sid) to out.size
    }

    private suspend fun ensureMatchTab() {
        val info = sheets.info(matchId)
        if (info.tab(Rm.MATCH_TAB) != null) return
        sheets.batchUpdate(matchId, listOf(Req.addSheet(Rm.MATCH_TAB, info.tabs.size)))
        val sid = sheets.info(matchId).tab(Rm.MATCH_TAB)!!.id
        sheets.write(matchId, a1(Rm.MATCH_TAB, "A1"), listOf(Rm.MHEAD))
        sheets.batchUpdate(matchId, listOf(Req.format(Req.range(sid, 0, 1, 0, Rm.MHEAD.size), bold = true), Req.freezeRows(sid, 1)))
    }

    /** Text that Sheets would read as a formula gets a leading apostrophe. */
    private fun safeText(s: String) = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s

    companion object {
        const val LAYOUT = "husk.layout"
    }
}
