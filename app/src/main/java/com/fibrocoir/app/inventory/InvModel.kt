package com.fibrocoir.app.inventory

import com.fibrocoir.app.core.Js
import com.fibrocoir.app.core.Uploads
import kotlinx.serialization.Serializable
import java.text.Collator
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * Stores inventory: a port of the Inventory Apps Script (Items, Movements, People, ...).
 * Reading and the rules are here; InvRepo does the writing. tools/golden/inventory.js runs
 * the script itself and the unit test checks these functions give the same answers.
 */

@Serializable
data class InvItem(
    val id: String, val name: String, val tamil: String, val cat: String, val std: String, val unit: String,
    val region: String, val bin: String, val machines: String, val photoUrl: String, val photo2Url: String,
    val dim1: String, val dim2: String, val dim3: String, val dims: String, val material: String, val swap: String,
    val reorder: Double?, val lead: Double?, val onHand: Double, val min: Double?,
    /** ok, low, out */
    val status: String,
    val activeStatus: String,
    val row: Int,
    /** every cell as the script's String(value), for "did it change" checks */
    val raw: Map<String, String>,
) {
    val photo get() = Uploads.thumb(photoUrl, 400)
    val photo2 get() = Uploads.thumb(photo2Url, 600)
    val tool get() = cat == "TOOL"
}

@Serializable
data class InvPerson(
    val id: String, val name: String, val tamil: String, val section: String, val photoUrl: String,
    val canReceive: Boolean, val canCreate: Boolean, val pin: String, val active: Boolean, val sort: Double, val row: Int,
) {
    val photo get() = Uploads.thumb(photoUrl, 200)
}

@Serializable
data class InvMachine(val id: String, val name: String, val tamil: String, val photoUrl: String, val active: Boolean) {
    val photo get() = Uploads.thumb(photoUrl, 200)
}

@Serializable
data class InvRegion(val code: String, val colour: String, val desc: String, val board: String)

@Serializable
data class InvVendor(val id: String, val name: String)

@Serializable
data class InvData(
    val items: List<InvItem>,           // every Items row, with or without an id
    val people: List<InvPerson>,        // every People row
    val machines: List<InvMachine>,
    val regions: List<InvRegion>,
    val config: Map<String, String>,
    val vendors: List<InvVendor>,
    val itemsHead: Map<String, Int>,    // header key -> 1-based column
    val peopleNextRow: Int,
    val configRows: Map<String, Int> = emptyMap(),
) {
    val company get() = config["COMPANY_NAME"].orEmpty().ifEmpty { "Stores" }
    val allowNegative get() = Js.norm(config["ALLOW_NEGATIVE_STOCK"]) == "y"
    val defaultReason get() = config["DEFAULT_REASON"].orEmpty().ifEmpty { "GENERAL" }
    val webapp get() = config["WEBAPP_URL"].orEmpty().trim()
    val master get() = config["SUPERVISOR_PIN"].orEmpty().trim()

    /** Active people for the photo grid, in the sheet's sort order. */
    val activePeople: List<InvPerson> get() = people.filter { it.id.isNotEmpty() && it.active }
        .sortedWith(compareBy<InvPerson> { it.sort }.thenComparator { a, b -> Inv.collator.compare(a.name, b.name) })
    val activeMachines: List<InvMachine> get() = machines.filter { it.id.isNotEmpty() && it.active }
    val creators: List<InvPerson> get() = people.filter { it.id.isNotEmpty() && it.active && it.canCreate }
    fun region(code: String) = regions.firstOrNull { it.code == code }
    fun item(id: String) = Inv.getItem(this, id)
}

class InvException(message: String) : Exception(message)

object Inv {
    val collator: Collator = Collator.getInstance(Locale.ENGLISH)
    val CATEGORIES = listOf("SPARE" to "Spare", "CONSUMABLE" to "Consumable", "TOOL" to "Tool")
    val UNITS = listOf("PCS" to "pcs", "FT" to "feet", "M" to "metre", "KG" to "kg", "L" to "litre", "SET" to "set", "BOX" to "box")
    val SECTIONS = listOf("MILL", "YARD", "PRESS", "ELECTRICAL", "MAINTENANCE", "OFFICE")
    val PRESETS = mapOf("PCS" to listOf(1.0, 2.0, 5.0, 10.0), "SET" to listOf(1.0, 2.0, 3.0), "BOX" to listOf(1.0, 2.0, 5.0),
        "FT" to listOf(5.0, 10.0, 20.0, 50.0), "M" to listOf(1.0, 5.0, 10.0, 20.0), "KG" to listOf(0.5, 1.0, 5.0, 10.0), "L" to listOf(0.5, 1.0, 5.0, 20.0))
    const val SUPERVISOR = "SUPERVISOR"

    private fun s(v: Any?) = Js.str(v)
    private fun n(v: Any?) = Js.norm(v)
    private fun t(v: Any?) = Js.str(v).trim()

    // ------------------------------------------------------------------ reading

    /** readTab(): one map per row, keyed by lower-cased header, plus its row number. */
    fun rowsOf(values: List<List<Any?>>): List<Pair<Int, Map<String, Any?>>> {
        if (values.size < 2) return emptyList()
        val head = values[0].map(::n)
        return (1 until values.size).map { r ->
            val o = HashMap<String, Any?>()
            head.forEachIndexed { c, h -> if (h.isNotEmpty()) o[h] = values[r].getOrNull(c) ?: "" }
            (r + 1) to o
        }
    }

    fun headOf(values: List<List<Any?>>): Map<String, Int> {
        val m = HashMap<String, Int>()
        values.firstOrNull()?.forEachIndexed { i, h -> val k = n(h); if (k.isNotEmpty()) m[k] = i + 1 }
        return m
    }

    /** The row after the last one with something in column `col` (1-based), like nextRow(). */
    fun nextRow(values: List<List<Any?>>, col: Int): Int {
        var last = 1
        values.forEachIndexed { i, r -> if (s(r.getOrNull(col - 1)).trim().isNotEmpty()) last = i + 1 }
        return last + 1
    }

    fun itemFrom(row: Int, r: Map<String, Any?>): InvItem {
        val onHand = Js.number(r["onhand"]).let { if (it.isNaN()) 0.0 else it }
        val minRaw = r["minlevel"]
        val min = if (minRaw == null || minRaw == "") null else Js.number(minRaw).takeIf { !it.isNaN() }
        val status = when {
            onHand <= 0 -> "out"
            min != null && onHand <= min -> "low"
            else -> "ok"
        }
        fun opt(k: String): Double? { val v = r[k]; return if (v == null || v == "") null else Js.number(v).takeIf { !it.isNaN() } }
        val d1 = s(r["dim1"]); val d2 = s(r["dim2"]); val d3 = s(r["dim3"])
        return InvItem(
            id = t(r["itemid"]), name = t(Js.or(r["itemname"], "")), tamil = t(Js.or(r["itemnametamil"], "")),
            cat = t(Js.or(r["category"], "")).uppercase(), std = t(Js.or(r["stddesignation"], "")),
            unit = t(Js.or(r["unit"], "PCS")).uppercase(), region = t(Js.or(r["region"], "")).uppercase(),
            bin = t(Js.or(r["bin"], "")), machines = t(Js.or(r["machineids"], "")),
            photoUrl = t(r["photourl"]), photo2Url = t(r["photo2url"]),
            dim1 = d1, dim2 = d2, dim3 = d3, dims = listOf(d1, d2, d3).filter { it.isNotEmpty() }.joinToString(" × "),
            material = t(Js.or(r["material"], "")), swap = t(Js.or(r["interchangeablewith"], "")),
            reorder = opt("reorderqty"), lead = opt("leadtimedays"),
            onHand = onHand, min = min, status = status,
            activeStatus = t(Js.or(r["status"], "ACTIVE")).uppercase(),
            row = row, raw = r.mapValues { s(it.value) },
        )
    }

    fun parse(tabs: Map<String, List<List<Any?>>>): InvData {
        val itemsV = tabs["Items"].orEmpty()
        val peopleV = tabs["People"].orEmpty()
        val items = rowsOf(itemsV).map { (row, r) -> itemFrom(row, r) }
        val people = rowsOf(peopleV).map { (row, r) ->
            InvPerson(
                id = t(r["personid"]), name = t(Js.or(r["name"], "")), tamil = t(Js.or(r["nametamil"], "")),
                section = t(Js.or(r["section"], "")), photoUrl = t(r["photourl"]),
                canReceive = n(r["canreceive"]) == "y", canCreate = n(r["cancreateitem"]) == "y",
                pin = t(r["pin"]), active = n(r["active"]) != "n",
                sort = Js.number(r["sortorder"]).let { if (it.isNaN() || it == 0.0) 999.0 else it }, row = row,
            )
        }
        val machines = rowsOf(tabs["Machines"].orEmpty()).map { (_, r) ->
            InvMachine(t(r["machineid"]), t(Js.or(r["machinename"], "")), t(Js.or(r["nametamil"], "")), t(r["photourl"]), n(r["active"]) != "n")
        }
        val regions = LinkedHashMap<String, InvRegion>()
        rowsOf(tabs["Regions"].orEmpty()).forEach { (_, r) ->
            if (!Js.truthy(r["region"])) return@forEach
            val code = t(r["region"]).uppercase()
            regions[code] = InvRegion(code, t(Js.or(r["colour"], "#555")), t(Js.or(r["description"], "")), t(Js.or(r["boardlocation"], "")))
        }
        val config = LinkedHashMap<String, String>()
        val configRows = HashMap<String, Int>()
        rowsOf(tabs["Config"].orEmpty()).forEach { (row, r) ->
            if (Js.truthy(r["key"])) { config[t(r["key"])] = t(r["value"]); configRows[t(r["key"])] = row }
        }
        val vendors = rowsOf(tabs["Vendors"].orEmpty()).filter { Js.truthy(it.second["vendorid"]) }
            .map { (_, r) -> InvVendor(t(r["vendorid"]), t(Js.or(r["vendorname"], ""))) }
        return InvData(items, people, machines, regions.values.toList(), config, vendors, headOf(itemsV), nextRow(peopleV, 1), configRows)
    }

    // ------------------------------------------------------------------ search

    data class Found(val total: Int, val items: List<InvItem>)

    /** searchItems(): every word must appear; exact id / standard number first, then in stock, then by name. */
    fun search(d: InvData, query: String, region: String, category: String, limit: Int = Int.MAX_VALUE): Found {
        val tokens = n(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = d.items.filter { it.id.isNotEmpty() && it.activeStatus.lowercase() != "inactive" }.filter { r ->
            if (region.isNotEmpty() && n(r.raw["region"]) != n(region)) return@filter false
            if (category.isNotEmpty() && n(r.raw["category"]) != n(category)) return@filter false
            if (tokens.isNotEmpty()) {
                val hay = n(listOf("itemid", "itemname", "itemnametamil", "stddesignation", "bin", "material", "interchangeablewith", "machineids")
                    .joinToString(" ") { r.raw[it] ?: "" })
                if (tokens.any { !hay.contains(it) }) return@filter false
            }
            true
        }
        val q = n(query)
        val sorted = out.sortedWith { a, b ->
            val ax = if (n(a.id) == q || n(a.std) == q) 0 else 1
            val bx = if (n(b.id) == q || n(b.std) == q) 0 else 1
            when {
                ax != bx -> ax - bx
                (a.onHand > 0) != (b.onHand > 0) -> if (a.onHand > 0) -1 else 1
                else -> collator.compare(a.name, b.name)
            }
        }
        return Found(sorted.size, sorted.take(limit))
    }

    fun getItem(d: InvData, id: String): InvItem? = d.items.firstOrNull { n(it.raw["itemid"]) == n(id) }

    /** Parts that look like the one being added (same standard number or shared words). */
    fun similar(d: InvData, name: String, std: String, region: String): List<InvItem> {
        val words = n(name).split(Regex("\\s+")).filter { it.length >= 3 }
        val sn = n(std)
        val hits = ArrayList<Pair<Double, InvItem>>()
        d.items.filter { Js.truthy(it.raw["itemid"]) }.forEach { r ->
            val hay = n(listOf("itemname", "itemnametamil", "stddesignation", "itemid").joinToString(" ") { r.raw[it] ?: "" })
            var score = 0.0
            if (sn.isNotEmpty() && n(r.raw["stddesignation"]) == sn) score += 10
            words.forEach { w -> if (hay.contains(w)) score += 1 }
            if (region.isNotEmpty() && n(r.raw["region"]) == n(region) && score > 0) score += 0.5
            if (score >= 1) hits += score to r
        }
        return hits.sortedByDescending { it.first }.take(6).map { it.second }
    }

    fun nextItemId(d: InvData): String {
        val prefix = d.config["ITEM_PREFIX"].orEmpty().ifEmpty { "ITM" }.uppercase()
        var mx = 0
        d.items.forEach { r -> Regex("(\\d+)\\s*$").find(r.raw["itemid"] ?: "")?.let { mx = max(mx, it.groupValues[1].toInt()) } }
        return prefix + "-" + (mx + 1).toString().padStart(4, '0')
    }

    fun nextPersonId(d: InvData): String {
        var mx = 0
        d.people.forEach { p -> Regex("(\\d+)\\s*$").find(p.id)?.let { mx = max(mx, it.groupValues[1].toInt()) } }
        return "EMP-" + (mx + 1).toString().padStart(2, '0')
    }

    // ------------------------------------------------------------------ PINs

    /** requirePin(): the person's own PIN (if they have the permission) or the supervisor PIN. */
    fun requirePin(d: InvData, personId: String, pin: String, canCreate: Boolean): InvPerson {
        val person = d.people.firstOrNull { n(it.id) == n(personId) } ?: throw InvException("Person not found")
        val supplied = pin.trim()
        val allowed = if (canCreate) person.canCreate else person.canReceive
        val okOwn = allowed && person.pin.isNotEmpty() && supplied == person.pin
        val okMaster = d.master.isNotEmpty() && supplied == d.master
        if (!okOwn && !okMaster) throw InvException("Wrong PIN")
        return person
    }

    /** requireAdmin(): supervisor PIN, or a person who may add items, with their PIN. Returns who. */
    fun requireAdmin(d: InvData, personId: String, pin: String): String {
        if (d.master.isNotEmpty() && pin.trim() == d.master) return SUPERVISOR
        if (personId.isNotEmpty()) {
            try { return requirePin(d, personId, pin, canCreate = true).id } catch (_: InvException) { }
        }
        throw InvException("Wrong PIN. If Config > SUPERVISOR_PIN is blank, set it in the sheet first.")
    }

    // ------------------------------------------------------------------ movements

    val MOVE_TYPES = listOf("TAKE", "RETURN", "RECEIVE", "SCRAP", "LOST", "ADJUST")
    private val PIN_TYPES = listOf("RECEIVE", "SCRAP", "LOST", "ADJUST")

    data class Move(
        val type: String, val itemId: String, val qty: Double, val personId: String, val machineId: String = "",
        val reason: String = "", val pin: String = "", val vendorId: String = "", val invoiceNo: String = "",
        val unitPrice: String = "", val note: String = "", val device: String = "",
    )

    /** A movement ready to write: columns A..E (the time is added when writing) and G..N. */
    data class MoveRow(val type: String, val itemId: String, val qty: Double, val rest: List<Any?>, val item: InvItem, val person: InvPerson)

    fun movementSeq(row: Int) = "MOV-" + (row - 1).toString().padStart(6, '0')

    /** submitMovement()'s checks; throws with the script's message. */
    fun planMove(d: InvData, p: Move): MoveRow {
        val type = p.type.uppercase()
        val qty = p.qty
        if (type !in MOVE_TYPES) throw InvException("Unknown action")
        if (p.itemId.isEmpty()) throw InvException("No item")
        if (p.personId.isEmpty()) throw InvException("Nobody selected")
        if (!(qty > 0)) throw InvException("Quantity must be more than zero")
        val item = getItem(d, p.itemId) ?: throw InvException("Item not in the list: ${p.itemId}")
        val person = if (type in PIN_TYPES) requirePin(d, p.personId, p.pin, canCreate = false)
        else d.people.firstOrNull { n(it.id) == n(p.personId) } ?: throw InvException("Person not found")
        if (type in listOf("TAKE", "SCRAP", "LOST") && !d.allowNegative && qty > item.onHand) {
            throw InvException("Only ${Js.num(item.onHand)} ${item.unit} in stock")
        }
        return MoveRow(type, item.id, qty, listOf(
            person.id, p.machineId, p.reason.ifEmpty { d.defaultReason },
            p.vendorId, p.invoiceNo, p.unitPrice, p.note, p.device.take(120),
        ), item, person)
    }

    // ------------------------------------------------------------------ new item

    data class NewItem(
        val name: String = "", val tamil: String = "", val std: String = "", val category: String = "", val unit: String = "",
        val region: String = "", val bin: String = "", val machines: List<String> = emptyList(),
        val dim1: String = "", val dim2: String = "", val dim3: String = "", val material: String = "", val swap: String = "",
        val minLevel: String = "", val reorderQty: String = "", val leadTime: String = "", val openingQty: String = "",
        val vendorId: String = "", val brand: String = "", val price: String = "", val vendorPartNo: String = "",
        val hasPhoto: Boolean = false, val personId: String = "", val pin: String = "",
    )

    private fun optNum(v: String): Any = if (v.isEmpty()) "" else Js.number(v)

    /** The checks createItem() makes before writing anything. */
    fun checkNew(d: InvData, p: NewItem): InvPerson {
        if (p.name.trim().isEmpty()) throw InvException("Item name is needed")
        if (p.category.isEmpty()) throw InvException("Pick spare, consumable or tool")
        if (p.unit.isEmpty()) throw InvException("Pick a unit")
        if (p.region.isEmpty()) throw InvException("Pick a region")
        if (!p.hasPhoto) throw InvException("Take a photo of the part")
        val person = requirePin(d, p.personId, p.pin, canCreate = true)
        d.items.firstOrNull { n(it.raw["itemname"]) == n(p.name) }?.let {
            throw InvException("\"${t(it.raw["itemname"])}\" already exists as ${t(it.raw["itemid"])}")
        }
        return person
    }

    /** Items columns A..S for a new part. */
    fun newItemCells(id: String, p: NewItem, url1: String, url2: String): List<Any?> = listOf(
        id, p.name.trim(), p.tamil.trim(), p.category.uppercase(), p.std.trim(), p.unit.uppercase(), p.region.uppercase(),
        p.bin.trim(), p.machines.joinToString(","), url1, url2, p.dim1, p.dim2, p.dim3,
        p.material.trim(), p.swap.trim(), optNum(p.minLevel), optNum(p.reorderQty), optNum(p.leadTime),
    )

    /** Items columns U..Y (V = last price, X = created on is filled in when writing). */
    fun newItemTail(p: NewItem, personId: String): List<Any?> = listOf("ACTIVE", optNum(p.price), "", null, personId)

    fun qrFormula(webapp: String, id: String) =
        "=IMAGE(\"https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=\"&ENCODEURL(\"$webapp?id=$id\"))"

    fun qrUrl(webapp: String, id: String) =
        "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" + java.net.URLEncoder.encode("$webapp?id=$id", "UTF-8")
            .replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    /** Opening stock movement (G..N), when an opening quantity was given. */
    fun openingRest(p: NewItem, personId: String): List<Any?> =
        listOf(personId, "", "GENERAL", p.vendorId, "", p.price, "opening stock at item creation", "")

    /** ItemVendors row (the date at F is filled in when writing). */
    fun itemVendorCells(id: String, p: NewItem): List<Any?>? =
        if (p.vendorId.isEmpty() && p.brand.isEmpty()) null
        else listOf(id, p.vendorId, p.brand.trim(), p.vendorPartNo.trim(), optNum(p.price), null, optNum(p.leadTime), "Y", "")

    // ------------------------------------------------------------------ edit item

    /** null = leave it alone (the script skips fields it wasn't sent). */
    data class Edit(
        val itemId: String,
        val name: String? = null, val tamil: String? = null, val std: String? = null, val category: String? = null,
        val unit: String? = null, val region: String? = null, val bin: String? = null, val dim1: String? = null,
        val dim2: String? = null, val dim3: String? = null, val material: String? = null, val swap: String? = null,
        val minLevel: String? = null, val reorderQty: String? = null, val leadTime: String? = null, val status: String? = null,
        val machines: List<String>? = null, val countedQty: String? = null, val hasPhoto: Boolean = false,
        val personId: String = "", val pin: String = "",
    )

    data class EditPlan(
        val person: InvPerson, val item: InvItem,
        /** 1-based column -> value */
        val cells: List<Pair<Int, Any?>>,
        val changed: List<String>,
        val photoCol: Int?, val qr: Pair<Int, String>?,
        /** physical count: from, to, diff */
        val adjust: Triple<Double, Double, Double>?,
    )

    fun planEdit(d: InvData, p: Edit): EditPlan {
        val person = requirePin(d, p.personId, p.pin, canCreate = true)
        val target = d.items.firstOrNull { n(it.raw["itemid"]) == n(p.itemId) } ?: throw InvException("Item not found: ${p.itemId}")
        val counted = p.countedQty?.takeIf { it.trim().isNotEmpty() }?.let {
            Js.number(it).also { c -> if (c.isNaN()) throw InvException("Counted quantity is not a number") }
        }
        val col = d.itemsHead
        val cells = ArrayList<Pair<Int, Any?>>(); val changed = ArrayList<String>()
        val fields = listOf(
            "itemname" to p.name, "itemnametamil" to p.tamil, "stddesignation" to p.std, "category" to p.category,
            "unit" to p.unit, "region" to p.region, "bin" to p.bin, "dim1" to p.dim1, "dim2" to p.dim2, "dim3" to p.dim3,
            "material" to p.material, "interchangeablewith" to p.swap, "minlevel" to p.minLevel, "reorderqty" to p.reorderQty,
            "leadtimedays" to p.leadTime, "status" to p.status,
        )
        for ((key, v) in fields) {
            if (v == null) continue
            val c = col[key] ?: continue
            var out: Any? = v
            if (key == "minlevel" || key == "reorderqty" || key == "leadtimedays") out = if (v.trim().isEmpty()) "" else Js.number(v)
            if (key in listOf("category", "unit", "region", "status")) out = v.uppercase()
            if ((target.raw[key] ?: "").trim() != s(out).trim()) { cells += c to out; changed += key }
        }
        if (p.machines != null && col["machineids"] != null) {
            val joined = p.machines.joinToString(",")
            if ((target.raw["machineids"] ?: "") != joined) { cells += col["machineids"]!! to joined; changed += "machines" }
        }
        var photoCol: Int? = null
        if (p.hasPhoto) { photoCol = col["photourl"]; changed += "photo" }
        var qr: Pair<Int, String>? = null
        if (d.webapp.isNotEmpty() && col["qr"] != null && (target.raw["qr"] ?: "").trim().isEmpty()) {
            qr = col["qr"]!! to qrFormula(d.webapp, p.itemId); changed += "QR"
        }
        var adjust: Triple<Double, Double, Double>? = null
        if (counted != null) {
            val diff = Math.round((counted - target.onHand) * 1000) / 1000.0
            if (diff != 0.0) { adjust = Triple(target.onHand, counted, diff); changed += "stock count" }
        }
        return EditPlan(person, target, cells, changed, photoCol, qr, adjust)
    }

    fun adjustRest(personId: String, from: Double, to: Double): List<Any?> =
        listOf(personId, "", "GENERAL", "", "", "", "physical count: system ${Js.num(from)}, counted ${Js.num(to)}", "")

    // ------------------------------------------------------------------ people

    data class NewPerson(
        val byPersonId: String = "", val pin: String = "", val name: String = "", val tamil: String = "", val section: String = "",
        val canReceive: Boolean = false, val canCreate: Boolean = false, val newPin: String = "", val sortOrder: String = "",
    )

    /** addPerson()'s checks; returns who approved it and the new id. */
    fun checkPerson(d: InvData, p: NewPerson): Pair<String, String> {
        val by = requireAdmin(d, p.byPersonId, p.pin)
        if (p.name.trim().isEmpty()) throw InvException("Name is needed")
        if ((p.canReceive || p.canCreate) && p.newPin.trim().isEmpty()) throw InvException("Give them a PIN, or remove the permissions")
        d.people.firstOrNull { n(it.name) == n(p.name) }?.let { throw InvException("\"${it.name}\" is already listed as ${it.id}") }
        return by to nextPersonId(d)
    }

    fun personCells(d: InvData, id: String, p: NewPerson, photoUrl: String): List<Any?> {
        val sort = Js.number(p.sortOrder).let { if (it.isNaN() || it == 0.0) (d.peopleNextRow - 1).toDouble() else it }
        return listOf(id, p.name.trim(), p.tamil.trim(), p.section.uppercase(), photoUrl, "",
            if (p.canReceive) "Y" else "N", if (p.canCreate) "Y" else "N", p.newPin.trim(), "Y", sort)
    }

    // ------------------------------------------------------------------ words

    fun qtyText(v: Double): String = if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else Js.num(v)
}
