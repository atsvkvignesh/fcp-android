package com.fibrocoir.app.ec

import com.fibrocoir.app.testutil.Golden
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** EC Viewer numbers against the ec-view script's api_summary (tools/golden/ec.js). */
class EcViewTest {
    private val g = Golden.load("ec.json")
    private val all: List<EcTab> = g["tabs"]!!.jsonObject.entries.filter { Ec.isLogTab(it.key) }
        .map { (name, rows) -> Ec.parseTab(name, rows.jsonArray.map { it.jsonArray.toList() }) }

    private fun pack(s: Stats, min: Boolean = true) = buildJsonObject {
        put("n", s.n); put("over", s.over); put("avg", s.avg?.let { JsonPrimitive(it) } ?: JsonNull)
        if (min) put("min", s.min?.let { JsonPrimitive(it) } ?: JsonNull)
        put("max", s.max?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    private fun <K> packAll(m: Map<K, Stats>) = JsonObject(m.entries.associate { it.key.toString() to pack(it.value) })
    private fun lim(l: Limits) = buildJsonObject { put("Belt", l.belt); put("Block", l.block); put("Yard", l.yard) }
    private fun rec(v: EcView, r: Reading?): JsonElement = r?.let {
        buildJsonObject { put("key", it.key); put("date", it.date); put("hhmm", it.hhmm); put("point", it.point); put("spot", it.spot)
            put("ec", it.ec); put("op", it.op); put("over", v.over(it)) }
    } ?: JsonNull

    @Test fun summariesMatchTheScript() {
        g["cases"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            val a = o["args"]!!.jsonArray.map { it.jsonPrimitive.content }
            val want = o["out"]!!.jsonObject
            val saved = (o["saved"] as? JsonObject)?.let {
                ViewerLimits(it["Belt"]?.jsonPrimitive?.doubleOrNull, it["Block"]?.jsonPrimitive?.doubleOrNull, it["Yard"]?.jsonPrimitive?.doubleOrNull)
            }
            val range = Range.valueOf(a[0].uppercase())
            val (from, to) = bounds(range, a[1].ifEmpty { null }, a[2].ifEmpty { null }, today = "2026-10-15")
            val tabs = all.filter { it.date in from..to && (a[3] == "all" || it.who == a[3]) }
            val v = EcView(tabs, saved)
            val spot = a[4]
            val list = v.readings.filter { spot == "all" || (it.point == "Yard" && it.spot == spot) }
            val what = "case $a"
            assertEquals(what, want["from"]!!.jsonPrimitive.content, from)
            assertEquals(what, want["to"]!!.jsonPrimitive.content, to)
            assertEquals(what, want["tabs"]!!.jsonPrimitive.content.toInt(), tabs.size)
            Golden.same(want["spotsAll"], JsonArray(v.yards.map { JsonPrimitive(it) }), "$what spotsAll")
            Golden.same(want["total"], pack(v.stats(list), min = false), "$what total")
            Golden.same(want["byPoint"], packAll(v.by(list) { it.point }), "$what byPoint")
            Golden.same(want["bySpot"], packAll(v.by(list.filter { it.point == "Yard" }) { it.spot }), "$what bySpot")
            Golden.same(want["byPerson"], packAll(v.by(list) { it.op }), "$what byPerson")
            Golden.same(want["byCond"], packAll(v.by(list.filter { it.point == "Yard" }) { it.cond.ifEmpty { "(blank)" } }), "$what byCond")
            Golden.same(want["byPersonGroup"], buildJsonObject {
                put("Hall", packAll(v.by(list.filter { it.hall }) { it.op })); put("Yard", packAll(v.by(list.filter { it.point == "Yard" }) { it.op }))
            }, "$what byPersonGroup")
            Golden.same(want["limits"], lim(v.limits), "$what limits")
            Golden.same(want["sheetLimits"], lim(v.sheetLimits), "$what sheetLimits")
            Golden.same(want["last"], rec(v, v.last(list)), "$what last")
            Golden.same(want["lastBy"], buildJsonObject {
                put("Hall", rec(v, v.last(list.filter { it.hall }))); put("Yard", rec(v, v.last(list.filter { it.point == "Yard" })))
            }, "$what lastBy")
            val days = list.groupBy { it.date }.toSortedMap()
            Golden.same(want["series"], JsonArray(days.map { (d, rs) ->
                fun p(point: String) = v.stats(rs.filter { it.point == point }).let { s ->
                    buildJsonObject { put("n", s.n); put("avg", s.avg?.let { JsonPrimitive(it) } ?: JsonNull); put("max", s.max?.let { JsonPrimitive(it) } ?: JsonNull); put("over", s.over) } }
                buildJsonObject {
                    put("date", d); put("Belt", p("Belt")); put("Block", p("Block")); put("Yard", p("Yard"))
                    put("spots", JsonObject(v.by(rs.filter { it.point == "Yard" }) { it.spot }.entries.associate { (k, s) ->
                        k to buildJsonObject { put("n", s.n); put("over", s.over); put("avg", s.avg?.let { JsonPrimitive(it) } ?: JsonNull); put("max", s.max?.let { JsonPrimitive(it) } ?: JsonNull) } }))
                }
            }), "$what series")
        }
    }
}
