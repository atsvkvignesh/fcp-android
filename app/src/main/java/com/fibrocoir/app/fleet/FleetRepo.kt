package com.fibrocoir.app.fleet

import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.core.PickedFile
import com.fibrocoir.app.core.Uploads
import com.fibrocoir.app.core.google.Cells
import com.fibrocoir.app.core.google.Req
import com.fibrocoir.app.core.google.Sheets
import com.fibrocoir.app.core.google.Sheets.Companion.a1
import com.fibrocoir.app.core.google.Sheets.Companion.col
import kotlinx.serialization.json.JsonObject

/**
 * Loads the whole Fleet DB sheet (it is small), runs a change on a FleetBook and then makes the
 * same row writes and deletes in the sheet, in the same order. Values go in as typed text, the
 * way the script's appendRow / setValues put them, so dates stay dates.
 * Reminder emails are still sent by the Fleet script's daily trigger.
 */
class FleetRepo(private val sheets: Sheets, private val uploads: Uploads) {
    private val id = FcpSheets.FLEET
    private val tabs = FleetBook.HEADERS.keys.toList()

    suspend fun load(): FleetRaw {
        val info = sheets.info(id)
        val have = tabs.filter { info.tab(it) != null }
        val got = sheets.read(id, have.map { a1(it, "A1:" + col(FleetBook.HEADERS[it]!!.size)) })
        return FleetRaw(have.indices.associate { have[it] to got[it] })
    }

    fun book(raw: FleetRaw, user: String, now: Long = System.currentTimeMillis()): FleetBook =
        FleetBook.from(raw.tabs.mapValues { (_, rows) -> rows.map(Cells::plain) }, now, { FleetBook.newId(it, now, Math.random()) }, user)

    /** Fresh copy of the sheet, the change on it, then the same writes to the sheet. */
    suspend fun <T> change(user: String, action: suspend (FleetBook) -> T): Pair<T, FleetRaw> {
        val b = book(load(), user)
        val r = action(b)
        execute(b.ops)
        return r to load()
    }

    /** Sends a bill or certificate to Drive (Fleet Manager folder / Bills or Documents). */
    suspend fun upload(b: FleetBook, file: PickedFile, subfolder: String, label: String): String {
        val root = b.getSetting("driveFolderId")
        val ext = file.name.substringAfterLast('.', "").ifEmpty { if (file.mime == "application/pdf") "pdf" else "jpg" }
        val name = label.replace(Regex("[\\\\/:*?\"<>|]"), "-") + "." + ext
        val r = uploads.upload(file, root.ifEmpty { null }, subfolder = subfolder,
            newFolderName = if (root.isEmpty()) "Fleet Manager — " + b.getSetting("companyName", "Files") else null, name = name)
        if (r.createdFolderId.isNotEmpty()) b.saveSettings(mapOf("driveFolderId" to r.createdFolderId))
        return r.url
    }

    private fun cell(v: Any?): Any? = when (v) {
        null -> ""
        is FDate -> FleetBook.fmt(v.ms, "yyyy-MM-dd HH:mm:ss")
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> v
        else -> v.toString()
    }

    private suspend fun execute(ops: List<FleetBook.Op>) {
        if (ops.isEmpty()) return
        val info = sheets.info(id)
        val rows = HashMap<String, Int>(); val ids = HashMap<String, Int>()
        info.tabs.forEach { rows[it.title] = it.rows; ids[it.title] = it.id }
        var i = 0
        while (i < ops.size) {
            if (ops[i] is FleetBook.Op.Set) {
                val batch = ArrayList<Pair<String, List<List<Any?>>>>()
                val grow = ArrayList<JsonObject>()
                while (i < ops.size && ops[i] is FleetBook.Op.Set) {
                    val s = ops[i] as FleetBook.Op.Set
                    val have = rows[s.tab] ?: throw IllegalStateException("The Fleet sheet has no \"${s.tab}\" tab.")
                    if (s.row > have) { grow += Req.appendRows(ids[s.tab]!!, s.row - have + 50); rows[s.tab] = s.row + 50 }
                    val last = col(s.col + s.values.size - 1)
                    batch += a1(s.tab, "${col(s.col)}${s.row}:$last${s.row}") to listOf(s.values.map(::cell))
                    i++
                }
                if (grow.isNotEmpty()) sheets.batchUpdate(id, grow)
                sheets.writeMany(id, batch, userEntered = true)
            } else {
                val dels = ArrayList<JsonObject>()
                while (i < ops.size && ops[i] is FleetBook.Op.Del) {
                    val d = ops[i] as FleetBook.Op.Del
                    dels += Req.deleteRows(ids[d.tab] ?: throw IllegalStateException("No \"${d.tab}\" tab."), d.row - 1, d.row)
                    rows[d.tab] = (rows[d.tab] ?: 1) - 1
                    i++
                }
                sheets.batchUpdate(id, dels)
            }
        }
    }
}
