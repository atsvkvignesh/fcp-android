package com.fibrocoir.app.inventory

import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.core.Js
import com.fibrocoir.app.core.PickedFile
import com.fibrocoir.app.core.Uploads
import com.fibrocoir.app.core.google.Cells
import com.fibrocoir.app.core.google.Sheets
import com.fibrocoir.app.core.google.Sheets.Companion.a1
import com.fibrocoir.app.core.google.Sheets.Companion.col
import java.util.TimeZone

/** What the done screen shows after a save. */
data class InvDone(
    val ok: Boolean,
    val title: String,
    val lines: List<String> = emptyList(),
    val itemPhoto: String = "",
    val personPhoto: String = "",
    val region: String = "",
    val putBack: Boolean = false,
    val qrUrl: String = "",
    val itemId: String = "",
)

/**
 * Reads the Stores sheet and writes movements, new parts, edits and people.
 * The Items OnHand column and the Movements SignedQty / Cat / ToolOutQty columns are sheet
 * formulas, so they are never written; new rows go after the last filled row like the script.
 */
class InvRepo(private val sheets: Sheets, private val uploads: Uploads) {
    private val id get() = FcpSheets.INVENTORY
    private val tz = TimeZone.getTimeZone(FcpSheets.TZ)
    private val TABS = listOf("Items", "People", "Machines", "Regions", "Config", "Vendors")

    suspend fun load(): InvData {
        if (id.contains("_SHEET_ID")) throw IllegalStateException("The Stores sheet isn't set in this build yet.")
        val got = sheets.read(id, TABS.map { a1(it) })
        return Inv.parse(TABS.indices.associate { TABS[it] to got[it].map(Cells::plain) })
    }

    private fun now() = Cells.millisToSerial(System.currentTimeMillis(), tz)

    /** Adds a row after the last filled row (by column A) and returns its row number. */
    private suspend fun addRow(tab: String, values: List<Any?>): Int {
        val range = sheets.append(id, a1(tab, "A:A"), listOf(values.map(Js::cellValue)), userEntered = false, insertRows = false)
        return Sheets.firstRow(range).takeIf { it > 0 } ?: throw IllegalStateException("The sheet didn't say where the row went.")
    }

    private suspend fun onHand(d: InvData, row: Int): Double? {
        val c = d.itemsHead["onhand"] ?: return null
        val v = sheets.read(id, a1("Items", "${col(c)}$row")).firstOrNull()?.let { Cells.plain(it).firstOrNull() }
        return Js.number(v).takeIf { !it.isNaN() }
    }

    /** A movement row: A..E then the seq number and G..N once the row is known. */
    private suspend fun writeMovement(type: String, itemId: String, qty: Double, rest: List<Any?>): Pair<Int, String> {
        val row = addRow("Movements", listOf(now(), "", type, itemId, qty))
        val seq = Inv.movementSeq(row)
        sheets.writeMany(id, listOf(
            a1("Movements", "B$row") to listOf(listOf(seq)),
            a1("Movements", "G$row:N$row") to listOf(rest.map(Js::cellValue)),
        ))
        return row to seq
    }

    private suspend fun upload(d: InvData, file: PickedFile, name: String): String {
        val folder = d.config["PHOTO_FOLDER_ID"].orEmpty()
        val r = uploads.upload(file, folder.ifEmpty { null }, newFolderName = if (folder.isEmpty()) "Fibro Stores — Photos" else null, name = name)
        if (r.createdFolderId.isNotEmpty()) {
            d.configRows["PHOTO_FOLDER_ID"]?.let { row -> sheets.write(id, a1("Config", "B$row"), listOf(listOf(r.createdFolderId))) }
        }
        return "https://drive.google.com/file/d/${r.id}/view"
    }

    // ------------------------------------------------------------------ actions

    suspend fun move(p: Inv.Move): Pair<InvDone, InvData> {
        val d = load()
        val m = Inv.planMove(d, p)
        writeMovement(m.type, m.itemId, m.qty, m.rest)
        val after = onHand(d, m.item.row)
        val back = m.type == "RETURN" || m.type == "RECEIVE"
        val done = InvDone(
            ok = true, title = m.item.name,
            lines = listOfNotNull(
                "Now in stock: ${after?.let { Inv.qtyText(it) } ?: "?"} ${m.item.unit}",
                when (m.type) { "TAKE" -> "Taken by ${m.person.name}"; "RETURN" -> "Returned by ${m.person.name}"; else -> "Added by ${m.person.name}" },
            ),
            itemPhoto = m.item.photo, personPhoto = m.person.photo, region = m.item.region, putBack = back, itemId = m.item.id,
        )
        return done to load()
    }

    suspend fun create(p: Inv.NewItem, photo: PickedFile?, photo2: PickedFile?): Pair<InvDone, InvData> {
        val d = load()
        val person = Inv.checkNew(d, p.copy(hasPhoto = photo != null))
        val itemId = Inv.nextItemId(d)
        val url1 = upload(d, photo!!, "$itemId-main.jpg")
        val url2 = if (photo2 != null) upload(d, photo2, "$itemId-detail.jpg") else ""
        val row = addRow("Items", Inv.newItemCells(itemId, p, url1, url2))
        val tail = Inv.newItemTail(p, person.id).toMutableList().also { it[3] = now() }
        sheets.writeMany(id, listOf(a1("Items", "U$row:Y$row") to listOf(tail.map(Js::cellValue))))
        val head = sheets.info(id).tab("Items")
        if (head != null) sheets.batchUpdate(id, listOf(
            com.fibrocoir.app.core.google.Req.format(com.fibrocoir.app.core.google.Req.range(head.id, row - 1, row, 23, 24),
                numberType = "DATE_TIME", pattern = "yyyy-mm-dd hh:mm:ss"),
        ))
        if (d.webapp.isNotEmpty()) sheets.write(id, a1("Items", "W$row"), listOf(listOf(Inv.qrFormula(d.webapp, itemId))), userEntered = true)
        val open = Js.number(p.openingQty)
        if (open > 0) writeMovement("OPENING", itemId, open, Inv.openingRest(p, person.id))
        Inv.itemVendorCells(itemId, p)?.let { cells ->
            addRow("ItemVendors", cells.toMutableList().also { it[5] = now() })
        }
        val done = InvDone(
            ok = true, title = p.name.trim(), lines = listOfNotNull("$itemId · region ${p.region.uppercase()}",
                if (d.webapp.isEmpty()) "Config > WEBAPP_URL is empty, so no QR was made. Fill it in and the QR appears by itself." else "Print this QR on the label"),
            itemPhoto = Uploads.thumb(url1, 400), region = p.region.uppercase(),
            qrUrl = if (d.webapp.isNotEmpty()) Inv.qrUrl(d.webapp, itemId) else "", itemId = itemId,
        )
        return done to load()
    }

    suspend fun edit(p: Inv.Edit, photo: PickedFile?): Pair<InvDone, InvData> {
        val d = load()
        val plan = Inv.planEdit(d, p.copy(hasPhoto = photo != null))
        val row = plan.item.row
        val data = ArrayList<Pair<String, List<List<Any?>>>>()
        plan.cells.forEach { (c, v) -> data += a1("Items", "${col(c)}$row") to listOf(listOf(Js.cellValue(v))) }
        if (photo != null && plan.photoCol != null) {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply { timeZone = tz }.format(java.util.Date())
            data += a1("Items", "${col(plan.photoCol)}$row") to listOf(listOf(upload(d, photo, "${p.itemId}-main-$stamp.jpg")))
        }
        sheets.writeMany(id, data)
        plan.qr?.let { (c, f) -> sheets.write(id, a1("Items", "${col(c)}$row"), listOf(listOf(f)), userEntered = true) }
        plan.adjust?.let { (from, to, diff) -> writeMovement("ADJUST", plan.item.id, diff, Inv.adjustRest(plan.person.id, from, to)) }
        val after = onHand(d, row)
        val fresh = load()
        val it = Inv.getItem(fresh, p.itemId)
        val done = InvDone(
            ok = true, title = it?.name ?: p.itemId,
            lines = listOfNotNull(
                if (plan.changed.isEmpty()) "Nothing was different, so nothing was written." else "Updated: " + plan.changed.joinToString(", "),
                plan.adjust?.let { (f, t, df) -> "Count corrected from ${Inv.qtyText(f)} to ${Inv.qtyText(t)} (${if (df > 0) "+" else ""}${Js.num(df)})" },
                "In stock: ${after?.let { a -> Inv.qtyText(a) } ?: "?"} ${it?.unit.orEmpty()}",
            ),
            itemPhoto = it?.photo.orEmpty(), itemId = p.itemId,
        )
        return done to fresh
    }

    suspend fun addPerson(p: Inv.NewPerson, photo: PickedFile?): Pair<InvDone, InvData> {
        val d = load()
        val (by, pid) = Inv.checkPerson(d, p)
        val url = if (photo != null) upload(d, photo, "$pid-face.jpg") else ""
        addRow("People", Inv.personCells(d, pid, p, url))
        val done = InvDone(true, p.name.trim(), listOf("$pid · added by ${if (by == Inv.SUPERVISOR) "supervisor PIN" else by}"), personPhoto = Uploads.thumb(url, 200))
        return done to load()
    }

    suspend fun setActive(byPersonId: String, pin: String, personId: String, active: Boolean): InvData {
        val d = load()
        Inv.requireAdmin(d, byPersonId, pin)
        val person = d.people.firstOrNull { Js.norm(it.id) == Js.norm(personId) } ?: throw InvException("Person not found")
        sheets.write(id, a1("People", "J${person.row}"), listOf(listOf(if (active) "Y" else "N")))
        return load()
    }
}
