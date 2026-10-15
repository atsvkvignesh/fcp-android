package com.fibrocoir.app.husk

import com.fibrocoir.app.testutil.Golden
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.TimeZone

/** Raw material check against the husk-compare script itself (tools/golden/husk.js). */
class HuskModelTest {
    private val g = Golden.load("husk.json")
    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private val company = Golden.rows(g["company"]!!.jsonObject["Form Responses 1"])
    private val vendorTab = Golden.rows(g["vendor"]!!.jsonObject["Form Responses 1"])
    private val matches = Golden.rows(g["vendor"]!!.jsonObject["RM Matches"])
    private val vr = Rm.readVendor(vendorTab, tz, dmy = false)
    private val raw = RmRaw(Rm.readCompany(company, tz, dmy = false), vr.rows, Rm.readGroups(matches, tz), vr.hasVendor,
        "Form Responses 1", "Form Responses 1")
    private val json = Json { encodeDefaults = true }

    private fun strs(e: Any?) = (e as JsonArray).map { it.jsonPrimitive.content }

    @Test fun findsTheTabsByTheirHeaders() {
        assertTrue(Rm.isCompanyHeader(company[0]))
        assertTrue(Rm.isVendorHeader(vendorTab[0]))
        assertTrue(!Rm.isVendorHeader(company[0]) || Rm.isCompanyHeader(company[0]))
    }

    @Test fun everyFilterMatchesTheScript() {
        g["loads"]!!.jsonArray.forEach { l ->
            val f = l.jsonObject["filter"]!!.jsonObject
            fun s(k: String) = f[k]?.jsonPrimitive?.content ?: ""
            val state = Rm.build(raw, RmFilter(s("from"), s("to"), s("vendor")))
            Golden.same(l.jsonObject["out"], json.encodeToJsonElement(state), "filter $f")
        }
    }

    @Test fun savedRowsMatchTheScript() {
        val saved = raw.groups.toMutableList()
        g["saved"]!!.jsonArray.forEachIndexed { i, s ->
            val p = s.jsonObject["p"]!!.jsonObject
            val rows = Rm.rowsToSave(p["status"]!!.jsonPrimitive.content, strs(p["cks"]), strs(p["vks"]),
                p["note"]?.jsonPrimitive?.content ?: "", "", raw.loads, raw.slips, saved)
            val want = s.jsonObject["rows"]!!.jsonArray
            assertEquals("save $i rows", want.size, rows.size)
            rows.forEachIndexed { j, r ->
                val got = JsonArray(r.cells().map { c -> if (c is String) JsonPrimitive(c) else JsonPrimitive((c as Number).toDouble()) })
                Golden.same(want[j], got, "save $i row $j")
                saved += SavedGroup("S$i-$j", r.status, r.dk, r.vendor, r.note,
                    r.cks.split(',').filter { it.isNotEmpty() }, r.vks.split(',').filter { it.isNotEmpty() }, "", "")
            }
        }
        g["errors"]!!.jsonArray.forEach { e ->
            val p = e.jsonObject["p"]!!.jsonObject
            val want = e.jsonObject["error"]!!.jsonPrimitive.content
            try {
                Rm.rowsToSave(p["status"]!!.jsonPrimitive.content, strs(p["cks"]), strs(p["vks"]), "", "", raw.loads, raw.slips, saved)
                fail("expected: $want")
            } catch (x: Exception) {
                if (want.startsWith("One of these is already saved")) assertTrue(x.message!!, x.message!!.startsWith("One of these is already saved"))
                else assertEquals(want, x.message)
            }
        }
    }

    @Test fun jsNumberRules() {
        assertEquals(12.0, Rm.num("12abc"), 0.0)
        assertEquals(0.0, Rm.num("No"), 0.0)
        assertEquals(1500.0, Rm.num("₹1,500"), 0.0)
        assertEquals(null, Rm.numOrNull("#VALUE!"))
        assertEquals(2.45, Rm.r2(2.449999), 0.0)
        assertEquals("Kajamoideen", Rm.vendorName("  kajamoideen ").name)
        assertEquals("selvam traders", Rm.vendorName("Selvam   Traders").key)
    }

    @Test fun keysUseTheExactTimestamp() {
        // the script keys a row by its timestamp in milliseconds; a sheet day number must give the same
        val serial = (1790249059083L + 330 * 60_000L) / 86_400_000.0 + 25569.0
        assertEquals(1790249059083L, Rm.fromSerial(serial, tz).epoch)
        assertEquals("24 Sep, 4:54 PM", Rm.fmt(Rm.fromSerial(serial, tz).local, "d MMM, h:mm a"))
    }
}
