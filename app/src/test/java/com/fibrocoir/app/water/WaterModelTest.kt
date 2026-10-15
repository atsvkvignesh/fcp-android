package com.fibrocoir.app.water

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** Checks the trip rules against what the web page's own code works out (tools/golden/water.js). */
class WaterModelTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("golden/water.json")!!.readText()
    ).jsonObject
    private val tz = TimeZone.getTimeZone(golden["tz"]!!.jsonPrimitive.content)
    private val taps = golden["events"]!!.jsonArray.map { e ->
        val o = e.jsonObject
        Tap(o["t"]!!.jsonPrimitive.long, o["uid"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
            o["place"]!!.jsonPrimitive.content, o["ev"]!!.jsonPrimitive.content)
    }

    private fun close(what: String, want: Double?, got: Double?) {
        if (want == null) { assertNull(what, got); return }
        assertEquals(what, want, got!!, 1e-9)
    }

    @Test fun tripsMatchTheWebPage() {
        val want = golden["trips"]!!.jsonArray
        val got = Water.trips(taps.shuffled(java.util.Random(7)), tz)
        assertEquals(want.size, got.size)
        want.forEachIndexed { i, w ->
            val o = w.jsonObject; val g = got[i]
            assertEquals("uid $i", o["uid"]!!.jsonPrimitive.content, g.uid)
            assertEquals("name $i", o["name"]!!.jsonPrimitive.content, g.name)
            assertEquals("in $i", o["tin"]!!.jsonPrimitive.long, g.tin)
            assertEquals("out $i", o["tout"]!!.jsonPrimitive.long, g.tout)
            close("mins $i", o["mins"]!!.jsonPrimitive.double, g.mins)
            assertEquals("day $i", o["day"]!!.jsonPrimitive.content, g.day)
            assertEquals("test $i", o["test"]!!.jsonPrimitive.boolean, g.test)
            assertEquals("parked $i", o["parked"]!!.jsonPrimitive.boolean, g.parked)
            close("back $i", o["back"]!!.jsonPrimitive.doubleOrNull, g.back)
        }
    }

    @Test fun daySummariesMatchTheWebPage() {
        val trips = Water.trips(taps, tz)
        golden["summary"]!!.jsonObject.forEach { (day, w) ->
            val o = w.jsonObject
            val s = Water.summary(trips.filter { it.day == day })
            assertEquals("loads $day", o["loads"]!!.jsonPrimitive.int, s.loads)
            assertEquals("tests $day", o["tests"]!!.jsonPrimitive.int, s.tests)
            close("avgFill $day", o["avgFill"]!!.jsonPrimitive.doubleOrNull, s.avgFill)
            close("avgBack $day", o["avgBack"]!!.jsonPrimitive.doubleOrNull, s.avgBack)
            close("fastest $day", o["fastest"]!!.jsonPrimitive.doubleOrNull, s.fastest)
            close("slowest $day", o["slowest"]!!.jsonPrimitive.doubleOrNull, s.slowest)
            close("quickestBack $day", o["quickestBack"]!!.jsonPrimitive.doubleOrNull, s.quickestBack)
        }
    }

    @Test fun rosterKnowsWhoIsInside() {
        val trips = Water.trips(taps, tz)
        val r = Water.roster(listOf(Tanker("11CE2D07", "Tuck Tanker", "FCP"), Tanker("NEVER", "Spare", "")), taps, trips)
        assertEquals(listOf("11CE2D07", "NEVER", "A1B2C3D4", "FFEE0011"), r.map { it.uid })
        assertEquals("IN", r[0].last!!.ev)
        assertNull(r[1].last)
        assertEquals("FFEE0011", r[3].name)
    }

    @Test fun readsTheTimeLikeTheScript() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        // 15 Oct 2026 09:30:00 IST = 04:00 UTC
        val want = java.util.Date.UTC(126, 9, 15, 4, 0, 0)
        val serial = 46310 + 9.5 / 24   // the sheet's day number for 2026-10-15 09:30
        assertEquals(want, Water.timeOf(listOf("", "", "", "", "", "IN", "", serial), ist))
        assertEquals(want, Water.timeOf(listOf(46310.0, 9.5 / 24, "", "", "", "IN", "", ""), ist))
        assertEquals(want, Water.timeOf(listOf("2026-10-15", "09:30:00", "", "", "", "IN", "", ""), ist))
        assertEquals(want, Water.timeOf(listOf("", "", "", "", "", "IN", "", "10/15/2026 09:30:00"), ist))
        assertEquals(want, Water.timeOf(listOf("", "", "", "", "", "IN", "", "2026-10-15 09:30:00"), ist))
        assertNull(Water.timeOf(listOf("", "", "", "", "", "IN", "", ""), ist))
    }

    @Test fun wordsLikeThePage() {
        assertEquals("45s", Water.dur(0.75))
        assertEquals("12m 05s", Water.dur(12 + 5 / 60.0))
        assertEquals("3h 20m", Water.dur(200.0))
        assertEquals("04:09", Water.clock(249_000))
        assertEquals("1:02:03", Water.clock(3_723_000))
        assertEquals("Yesterday", Water.niceDate("2026-10-14", "2026-10-15"))
        assertEquals("Monday, 12 Oct 2026", Water.niceDate("2026-10-12", "2026-10-15"))
    }
}
