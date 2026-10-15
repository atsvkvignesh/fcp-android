package com.fibrocoir.app.inventory

import com.fibrocoir.app.core.Js
import com.fibrocoir.app.testutil.Golden
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stores inventory against the Inventory script itself (tools/golden/inventory.js). The cases
 * run in order on one store; after each one the script's own writes are applied, so every
 * case sees the same sheet the script saw.
 */
class InvModelTest {
    private val g = Golden.load("inventory.json")
    private val tabs: MutableMap<String, MutableList<MutableList<Any?>>> = g["tabs"]!!.jsonObject.mapValues { (_, v) ->
        Golden.rows(v).map { it.toMutableList() }.toMutableList()
    }.toMutableMap()

    private fun data() = Inv.parse(tabs)
    private fun p(o: JsonObject, k: String): String = o[k]?.let { if (it is JsonNull) "" else Js.str(plain(it)) } ?: ""
    private fun plain(e: JsonElement?): Any? = (e as? JsonPrimitive)?.let { if (it is JsonNull) null else if (it.isString) it.content else it.booleanOrNull ?: it.doubleOrNull }
    private fun json(v: Any?): JsonElement = when (v) { null -> JsonNull; is Number -> JsonPrimitive(v.toDouble()); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }

    /** An item the way the script's itemFromRow() returns it. */
    private fun js(it: InvItem) = buildJsonObject {
        put("id", it.id); put("name", it.name); put("tamil", it.tamil); put("cat", it.cat); put("std", it.std); put("unit", it.unit)
        put("region", it.region); put("bin", it.bin); put("machines", it.machines); put("photo", it.photo); put("photo2", it.photo2)
        put("dim1", it.dim1); put("dim2", it.dim2); put("dim3", it.dim3); put("dims", it.dims); put("material", it.material); put("swap", it.swap)
        put("reorder", it.reorder?.let { JsonPrimitive(it) } ?: JsonPrimitive("")); put("lead", it.lead?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
        put("onHand", it.onHand); put("min", it.min?.let { JsonPrimitive(it) } ?: JsonNull); put("status", it.status); put("activeStatus", it.activeStatus)
    }

    /** Applies the script's writes to our copy of the sheet. */
    private fun apply(writes: JsonArray) {
        writes.forEach { w ->
            val o = w.jsonObject
            val tab = tabs.getOrPut(o["tab"]!!.jsonPrimitive.content) { mutableListOf() }
            val r0 = o["row"]!!.jsonPrimitive.content.toInt() - 1; val c0 = o["col"]!!.jsonPrimitive.content.toInt() - 1
            val values = o["values"]?.jsonArray ?: return@forEach   // formulas don't change what is read back here
            values.forEachIndexed { i, row ->
                while (tab.size <= r0 + i) tab.add(mutableListOf())
                val target = tab[r0 + i]
                row.jsonArray.forEachIndexed { j, v ->
                    while (target.size <= c0 + j) target.add("")
                    target[c0 + j] = plain(v).let { if (it == "DATE") 46310.5 else it }
                }
            }
        }
    }

    private fun cellsOf(writes: JsonArray, tab: String, col: Int) =
        writes.map { it.jsonObject }.filter { it["tab"]!!.jsonPrimitive.content == tab && it["col"]!!.jsonPrimitive.content.toInt() == col && it["values"] != null }

    private fun same(want: JsonElement?, got: List<Any?>, what: String, skip: Set<Int> = emptySet()) {
        val w = want!!.jsonArray
        assertEquals("$what width", w.size, got.size)
        w.forEachIndexed { i, x -> if (i !in skip) Golden.same(x, json(got[i]), "$what[$i]") }
    }

    @Test fun everyCaseMatchesTheScript() {
        var cases = 0
        g["cases"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            val kind = o["kind"]!!.jsonPrimitive.content
            val args = o["args"]!!.jsonArray
            val err = o["error"]?.jsonPrimitive?.content
            val out = o["out"]
            val writes = o["writes"]!!.jsonArray
            val d = data()
            fun check(block: () -> Unit) {
                try {
                    block()
                    if (err != null) fail("$kind $args: script said \"$err\", we didn't")
                } catch (x: InvException) {
                    if (err == null) fail("$kind $args: we said \"${x.message}\", the script didn't")
                    assertEquals("$kind $args", err, x.message)
                }
            }
            when (kind) {
                "search" -> {
                    val f = Inv.search(d, args[0].jsonPrimitive.content, args[1].jsonPrimitive.content, args[2].jsonPrimitive.content, 60)
                    assertEquals("total $args", out!!.jsonObject["total"]!!.jsonPrimitive.content.toInt(), f.total)
                    Golden.same(out.jsonObject["items"], JsonArray(f.items.map(::js)), "search $args")
                }
                "item" -> Golden.same(out, Inv.getItem(d, args[0].jsonPrimitive.content)?.let(::js) ?: JsonNull, "item $args")
                "similar" -> Golden.same(out, JsonArray(Inv.similar(d, args[0].jsonPrimitive.content, args[1].jsonPrimitive.content, args[2].jsonPrimitive.content).map(::js)), "similar $args")
                "nextItem" -> assertEquals(out!!.jsonPrimitive.content, Inv.nextItemId(d))
                "nextPerson" -> assertEquals(out!!.jsonPrimitive.content, Inv.nextPersonId(d))
                "boot" -> {
                    val b = out!!.jsonObject
                    assertEquals(b["company"]!!.jsonPrimitive.content, d.company)
                    Golden.same(b["people"], JsonArray(d.activePeople.map { buildJsonObject {
                        put("id", it.id); put("name", it.name); put("tamil", it.tamil); put("section", it.section); put("photo", it.photo)
                        put("canReceive", it.canReceive); put("sort", it.sort) } }), "boot people")
                    Golden.same(b["machines"], JsonArray(d.activeMachines.map { buildJsonObject {
                        put("id", it.id); put("name", it.name); put("tamil", it.tamil); put("photo", it.photo) } }), "boot machines")
                    Golden.same(b["regions"], JsonObject(d.regions.associate { it.code to buildJsonObject {
                        put("colour", it.colour); put("desc", it.desc); put("board", it.board) } }), "boot regions")
                    assertEquals(b["allowNegative"]!!.jsonPrimitive.content.toBoolean(), d.allowNegative)
                    assertEquals(b["defaultReason"]!!.jsonPrimitive.content, d.defaultReason)
                }
                "newBoot" -> {
                    val b = out!!.jsonObject
                    Golden.same(b["people"], JsonArray(d.creators.map { buildJsonObject { put("id", it.id); put("name", it.name); put("photo", it.photo) } }), "creators")
                    Golden.same(b["vendors"], JsonArray(d.vendors.map { buildJsonObject { put("id", it.id); put("name", it.name) } }), "vendors")
                    Golden.same(b["machines"], JsonArray(d.activeMachines.map { buildJsonObject { put("id", it.id); put("name", it.name) } }), "machines")
                    assertEquals(b["nextId"]!!.jsonPrimitive.content, Inv.nextItemId(d))
                }
                "peopleAdmin" -> Golden.same(out!!.jsonObject["people"], JsonArray(d.people.filter { it.id.isNotEmpty() }.map { buildJsonObject {
                    put("id", it.id); put("name", it.name); put("tamil", it.tamil); put("section", it.section); put("photo", it.photo)
                    put("canReceive", it.canReceive); put("canCreate", it.canCreate); put("active", it.active) } }), "people admin")
                "pin" -> check {
                    val who = Inv.requirePin(d, args[0].jsonPrimitive.content, args[1].jsonPrimitive.content, args[2].jsonPrimitive.content == "cancreateitem")
                    assertEquals(out!!.jsonPrimitive.content, who.id)
                }
                "move" -> check {
                    val a = args[0].jsonObject
                    val m = Inv.planMove(d, Inv.Move(p(a, "type"), p(a, "itemId"), Js.number(plain(a["qty"])), p(a, "personId"), p(a, "machineId"),
                        p(a, "reason"), p(a, "pin"), p(a, "vendorId"), p(a, "invoiceNo"), p(a, "unitPrice"), p(a, "note"), p(a, "device")))
                    val head = cellsOf(writes, "Movements", 1).single()
                    val row = head["row"]!!.jsonPrimitive.content.toInt()
                    same(head["values"]!!.jsonArray[0], listOf("DATE", Inv.movementSeq(row), m.type, m.itemId, m.qty), "move head", setOf(0))
                    same(cellsOf(writes, "Movements", 7).single()["values"]!!.jsonArray[0], m.rest, "move rest")
                    assertEquals(row, Inv.nextRow(tabs["Movements"]!!, 4))
                    val r = out!!.jsonObject
                    assertEquals(r["itemName"]!!.jsonPrimitive.content, m.item.name)
                    assertEquals(r["personName"]!!.jsonPrimitive.content, m.person.name)
                }
                "create" -> check {
                    val a = args[0].jsonObject
                    val ni = Inv.NewItem(p(a, "name"), p(a, "tamil"), p(a, "std"), p(a, "category"), p(a, "unit"), p(a, "region"), p(a, "bin"),
                        a["machines"]!!.jsonArray.map { it.jsonPrimitive.content }, p(a, "dim1"), p(a, "dim2"), p(a, "dim3"), p(a, "material"),
                        p(a, "swap"), p(a, "minLevel"), p(a, "reorderQty"), p(a, "leadTime"), p(a, "openingQty"), p(a, "vendorId"), p(a, "brand"),
                        p(a, "price"), p(a, "vendorPartNo"), p(a, "photo").isNotEmpty(), p(a, "personId"), p(a, "pin"))
                    val person = Inv.checkNew(d, ni)
                    val id = Inv.nextItemId(d)
                    assertEquals(out!!.jsonObject["itemId"]!!.jsonPrimitive.content, id)
                    val body = cellsOf(writes, "Items", 1).single()
                    val url1 = "https://drive.google.com/file/d/FILE-$id-main.jpg/view"
                    val url2 = if (p(a, "photo2").isNotEmpty()) "https://drive.google.com/file/d/FILE-$id-detail.jpg/view" else ""
                    same(body["values"]!!.jsonArray[0], Inv.newItemCells(id, ni, url1, url2), "new item")
                    same(cellsOf(writes, "Items", 21).single()["values"]!!.jsonArray[0], Inv.newItemTail(ni, person.id), "new item tail", setOf(3))
                    val qr = writes.map { it.jsonObject }.single { it["formula"] != null }["formula"]!!.jsonPrimitive.content
                    assertEquals(qr, Inv.qrFormula(d.webapp, id))
                    assertEquals(out.jsonObject["qrUrl"]!!.jsonPrimitive.content, Inv.qrUrl(d.webapp, id))
                    val opening = cellsOf(writes, "Movements", 7)
                    if (Js.number(ni.openingQty) > 0) same(opening.single()["values"]!!.jsonArray[0], Inv.openingRest(ni, person.id), "opening")
                    else assertEquals(0, opening.size)
                    val iv = cellsOf(writes, "ItemVendors", 1)
                    val mine = Inv.itemVendorCells(id, ni)
                    if (mine == null) assertEquals(0, iv.size) else same(iv.single()["values"]!!.jsonArray[0], mine, "item vendor", setOf(5))
                }
                "edit" -> check {
                    val a = args[0].jsonObject
                    fun o(k: String): String? = a[k]?.let { if (it is JsonNull) null else Js.str(plain(it)) }
                    val plan = Inv.planEdit(d, Inv.Edit(
                        p(a, "itemId"), o("name"), o("tamil"), o("std"), o("category"), o("unit"), o("region"), o("bin"), o("dim1"), o("dim2"),
                        o("dim3"), o("material"), o("swap"), o("minLevel"), o("reorderQty"), o("leadTime"), o("status"),
                        a["machines"]?.jsonArray?.map { it.jsonPrimitive.content }, o("countedQty"), o("photo").orEmpty().isNotEmpty(),
                        p(a, "personId"), p(a, "pin")))
                    val r = out!!.jsonObject
                    Golden.same(r["changed"], JsonArray(plan.changed.map { JsonPrimitive(it) }), "changed")
                    val itemWrites = writes.map { it.jsonObject }.filter { it["tab"]!!.jsonPrimitive.content == "Items" && it["values"] != null }
                    val cellWrites = itemWrites.filter { it["col"]!!.jsonPrimitive.content.toInt() != plan.photoCol }
                    assertEquals("edit cells", cellWrites.size, plan.cells.size)
                    cellWrites.forEachIndexed { i, w ->
                        assertEquals(w["col"]!!.jsonPrimitive.content.toInt(), plan.cells[i].first)
                        Golden.same(w["values"]!!.jsonArray[0].jsonArray[0], json(plan.cells[i].second), "edit cell $i")
                    }
                    val adj = r["adjusted"]
                    if (adj == null || adj is JsonNull) assertEquals(null, plan.adjust)
                    else {
                        val a3 = plan.adjust!!
                        Golden.same(adj, buildJsonObject { put("from", a3.first); put("to", a3.second); put("diff", a3.third) }, "adjusted")
                        same(cellsOf(writes, "Movements", 7).single()["values"]!!.jsonArray[0], Inv.adjustRest(plan.person.id, a3.first, a3.second), "adjust rest")
                    }
                }
                "person" -> check {
                    val a = args[0].jsonObject
                    val np = Inv.NewPerson(p(a, "byPersonId"), p(a, "pin"), p(a, "name"), p(a, "tamil"), p(a, "section"),
                        a["canReceive"]!!.jsonPrimitive.content.toBoolean(), a["canCreate"]!!.jsonPrimitive.content.toBoolean(), p(a, "newPin"), p(a, "sortOrder"))
                    val (by, id) = Inv.checkPerson(d, np)
                    val r = out!!.jsonObject
                    assertEquals(r["addedBy"]!!.jsonPrimitive.content, by)
                    assertEquals(r["id"]!!.jsonPrimitive.content, id)
                    val photo = if (p(a, "photo").isNotEmpty()) "https://drive.google.com/file/d/FILE-$id-face.jpg/view" else ""
                    same(cellsOf(writes, "People", 1).single()["values"]!!.jsonArray[0], Inv.personCells(d, id, np, photo), "person row")
                }
                "active" -> check {
                    val a = args[0].jsonObject
                    Inv.requireAdmin(d, p(a, "byPersonId"), p(a, "pin"))
                    assertEquals("EMP-02", d.people.first { it.id == p(a, "personId") }.id)
                }
                else -> fail("unknown case $kind")
            }
            apply(writes)
            cases++
        }
        assertEquals(g["cases"]!!.jsonArray.size, cases)
    }
}
