package com.fibrocoir.app.fleet

import com.fibrocoir.app.core.Js
import com.fibrocoir.app.testutil.Golden
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Fleet against the Fleet script itself (tools/golden/fleet.js): same answers, same sheet changes. */
class FleetModelTest {
    private val g = Golden.load("fleet.json")
    private val now = g["now"]!!.jsonPrimitive.content.toDouble().toLong()
    private var seed = 0.0
    private fun rand(): Double { seed = (seed * 1103515245.0 + 12345.0) % 2147483648.0; return seed / 2147483648.0 }
    private val book = FleetBook.from(
        g["tabs"]!!.jsonObject.mapValues { (_, v) -> Golden.rows(v) }, now,
        { prefix -> FleetBook.newId(prefix, now, rand()) }, "me@fcp",
    )
    private val json = Json { encodeDefaults = true }

    private fun el(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is FDate -> buildJsonObject { put("d", FleetBook.fmt(v.ms, "yyyy-MM-dd HH:mm:ss")) }
        is Number -> JsonPrimitive(v.toDouble())
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to el(it.value) })
        is List<*> -> JsonArray(v.map(::el))
        else -> JsonPrimitive(v.toString())
    }

    private fun s(o: JsonObject, k: String): String = o[k]?.let { e ->
        val p = e as? JsonPrimitive ?: return@let ""
        if (p is JsonNull) "" else if (p.isString) p.content else p.booleanOrNull?.toString() ?: Js.num(p.content.toDouble())
    } ?: ""

    private fun opsJson(ops: List<FleetBook.Op>) = JsonArray(ops.map { op ->
        when (op) {
            is FleetBook.Op.Set -> buildJsonObject { put("op", "set"); put("tab", op.tab); put("row", op.row); put("col", op.col); put("values", el(op.values)) }
            is FleetBook.Op.Del -> buildJsonObject { put("op", "del"); put("tab", op.tab); put("row", op.row) }
        }
    })

    @Test fun everyCaseMatchesTheScript() {
        g["cases"]!!.jsonArray.forEachIndexed { n, c ->
            val o = c.jsonObject
            val kind = o["kind"]!!.jsonPrimitive.content
            val args = o["args"]!!.jsonArray
            val err = o["error"]?.jsonPrimitive?.content
            val out = o["out"]
            seed = o["seed"]!!.jsonPrimitive.content.toDouble()
            val before = book.ops.size
            val a = args.firstOrNull() as? JsonObject
            val files = o["files"]!!.jsonArray
            fun fileName(label: String) = files.single().jsonObject["name"]!!.jsonPrimitive.content.substringBeforeLast('.').let { assertEquals("file name", it, label) }
            var result: JsonElement = JsonNull
            try {
                when (kind) {
                    "boot" -> result = json.encodeToJsonElement(book.bootstrap())
                    "logs" -> result = el(book.logs(args[0].jsonPrimitive.content, args[1].jsonPrimitive.content, args[2].jsonPrimitive.content.toDouble().toInt()))
                    "history" -> result = JsonArray(book.reminderHistory().map { (id, reg, rest) ->
                        buildJsonObject { put("id", id); put("regNo", reg); put("name", rest.first); put("completedDate", rest.second) } })
                    "saveOdometer" -> { book.saveOdometer(s(a!!, "id"), s(a, "vehicleId"), s(a, "date"), s(a, "reading"), s(a, "notes"), s(a, "allowLower") == "true") }
                    "saveFuel" -> {
                        val url = if (a!!["file"] != null) {
                            fileName(book.fuelLabel(s(a, "vehicleId"), s(a, "date")))
                            "https://drive.google.com/file/d/" + java.net.URLEncoder.encode(files.single().jsonObject["name"]!!.jsonPrimitive.content, "UTF-8").replace("+", "%20") + "/view"
                        } else s(a, "billUrl")
                        book.saveFuel(s(a, "id"), s(a, "vehicleId"), s(a, "date"), s(a, "odometer"), s(a, "fuelType"), s(a, "litres"),
                            s(a, "rate"), s(a, "total"), s(a, "station"), s(a, "notes"), url)
                    }
                    "saveService" -> {
                        val url = if (a!!["file"] != null) {
                            fileName(book.serviceLabel(s(a, "vehicleId"), s(a, "date")))
                            "https://drive.google.com/file/d/" + java.net.URLEncoder.encode(files.single().jsonObject["name"]!!.jsonPrimitive.content, "UTF-8").replace("+", "%20") + "/view"
                        } else s(a, "billUrl")
                        val parts = a["parts"]!!.jsonArray.map { p -> val po = p.jsonObject; Part(s(po, "name"), s(po, "number"), s(po, "qty"), s(po, "cost")) }
                        val id = book.saveService(s(a, "id"), s(a, "vehicleId"), s(a, "date"), s(a, "odometer"), s(a, "category"), s(a, "summary"),
                            s(a, "cost"), s(a, "vendor"), s(a, "nextDate"), s(a, "nextOdo"), s(a, "notes"), parts, url)
                        assertEquals(out!!.jsonObject["id"]!!.jsonPrimitive.content, id)
                    }
                    "saveDocument" -> {
                        val (_, label) = book.checkDocument(s(a!!, "vehicleId"), s(a, "docType"), s(a, "customType"), s(a, "expiryDate"))
                        val url = if (a["file"] != null) {
                            fileName(label)
                            "https://drive.google.com/file/d/" + java.net.URLEncoder.encode(files.single().jsonObject["name"]!!.jsonPrimitive.content, "UTF-8").replace("+", "%20") + "/view"
                        } else s(a, "fileUrl")
                        book.saveDocument(s(a, "id"), s(a, "vehicleId"), s(a, "docType"), s(a, "customType"), s(a, "docNumber"), s(a, "issueDate"),
                            s(a, "expiryDate"), s(a, "provider"), s(a, "cost"), s(a, "notes"), url)
                    }
                    "saveServiceReminder" -> book.saveServiceReminder(s(a!!, "id"), s(a, "vehicleId"), s(a, "name"), s(a, "dueDate"), s(a, "dueOdo"),
                        s(a, "repeatDays"), s(a, "repeatKm"), s(a, "notes"))
                    "completeServiceReminder" -> {
                        val rep = book.completeServiceReminder(args[0].jsonPrimitive.content)
                        assertEquals(out!!.jsonObject["repeated"]!!.jsonPrimitive.content.toBoolean(), rep)
                    }
                    "deleteServiceReminder" -> book.deleteServiceReminder(args[0].jsonPrimitive.content)
                    "saveVehicle" -> {
                        val id = book.saveVehicle(s(a!!, "id"), s(a, "regNo"), s(a, "name"), s(a, "type"), s(a, "fuelType"), s(a, "tank"),
                            s(a, "driver"), s(a, "odo"), s(a, "status"), s(a, "notes"))
                        assertEquals(out!!.jsonObject["id"]!!.jsonPrimitive.content, id)
                    }
                    "deleteLog" -> book.deleteLog(args[0].jsonPrimitive.content, args[1].jsonPrimitive.content)
                    "deleteVehicle" -> book.deleteVehicle(args[0].jsonPrimitive.content)
                    "saveSettings" -> book.saveSettings(a!!.keys.associateWith { s(a, it) })
                    else -> fail("unknown case $kind")
                }
                if (err != null) fail("case $n $kind: script said \"$err\", we didn't")
            } catch (x: FleetException) {
                if (err == null) fail("case $n $kind $args: we said \"${x.message}\", the script didn't")
                assertEquals("case $n $kind", err, x.message)
            }
            if (kind in listOf("boot", "logs", "history")) Golden.same(out, result, "case $n $kind")
            val want = JsonArray(o["ops"]!!.jsonArray.map { w ->
                val wo = w.jsonObject
                if (wo["op"]!!.jsonPrimitive.content == "set" && wo["col"] == null) JsonObject(wo + ("col" to JsonPrimitive(1))) else wo
            })
            Golden.same(want, opsJson(book.ops.subList(before, book.ops.size)), "case $n $kind ops")
        }
    }
}
