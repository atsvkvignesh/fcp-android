package com.fibrocoir.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fibrocoir.app.core.google.Cells
import com.fibrocoir.app.ec.Ec
import com.fibrocoir.app.ec.EcBoot
import com.fibrocoir.app.ec.Limits
import com.fibrocoir.app.ecviewer.EcData
import com.fibrocoir.app.fleet.FleetRaw
import com.fibrocoir.app.hub.HubApp
import com.fibrocoir.app.hub.HubData
import com.fibrocoir.app.hub.HubRepo
import com.fibrocoir.app.hub.HubUser
import com.fibrocoir.app.hub.Session
import com.fibrocoir.app.husk.Rm
import com.fibrocoir.app.husk.RmRaw
import com.fibrocoir.app.inventory.Inv
import com.fibrocoir.app.inventory.InvData
import com.fibrocoir.app.water.Tap
import com.fibrocoir.app.water.Tanker
import com.fibrocoir.app.water.WaterData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/**
 * Opens every screen with the golden test data as the saved copy (no Google key, so nothing is
 * fetched) and saves a screenshot of each. CI pulls them for a look; a crash fails the run.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun golden(name: String): JsonObject =
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("golden/$name").bufferedReader().readText()) as JsonObject

    private fun plain(e: kotlinx.serialization.json.JsonElement): List<List<Any?>> = e.jsonArray.map { r ->
        r.jsonArray.map { c -> val p = c as? JsonPrimitive; when { p == null || p is JsonNull -> null; p.isString -> p.content; p.booleanOrNull != null -> p.booleanOrNull; else -> p.doubleOrNull } }
    }

    private fun seed() {
        val app = ctx.applicationContext as FcpApplication
        val store = app.store
        store.forget()
        // signed in as an admin, with a tile for every screen
        val admin = HubUser("u1", "vignesh", "Vignesh", "admin", "s", HubRepo.hash("x", "s"))
        Session(ctx).signIn(admin)
        val apps = listOf(
            HubApp("a1", "EC Log", "💧", screen = "ec-log"), HubApp("a2", "EC Viewer", "📈", screen = "ec-viewer"),
            HubApp("a3", "Water record", "🚚", screen = "water-load"), HubApp("a4", "Raw material check", "🥥", screen = "husk-check"),
            HubApp("a5", "Stores", "🔧", screen = "inventory"), HubApp("a6", "Fleet", "🚛", screen = "fleet"),
        )
        store.put("hub", HubData.serializer(), HubData(listOf(admin), apps))
        ctx.getSharedPreferences("fcp_sheets", Context.MODE_PRIVATE).edit()
            .putString("hub", "1TESTHUBTESTHUBTESTHUBTESTHUB0").putString("inventory", "1TESTSTORESTESTSTORESTESTST0").apply()

        // EC: the golden days moved so the newest one is today
        val ec = golden("ec.json")
        val today = Ec.today()
        val shift = daysBetween("2026-10-15", today)
        val tabs = ec["tabs"]!!.jsonObject.entries.filter { Ec.isLogTab(it.key) }.map { (name, rows) ->
            val newName = Ec.shiftDays(name.take(10), shift) + name.drop(10)
            val moved = rows.jsonArray.mapIndexed { i, r -> r.jsonArray.mapIndexed { c, v -> if (i > 0 && c == 0 && v is JsonPrimitive && !v.isString) JsonPrimitive(v.content.toDouble() + shift) else v } }
            Ec.parseTab(newName, moved)
        }
        val people = tabs.map { it.who }.distinct()
        store.put("ecv.$today.$today.all", EcData.serializer(), EcData(tabs.filter { it.date == today }, null, people))
        ctx.getSharedPreferences("ec_log", Context.MODE_PRIVATE).edit().putString("op", "Selvarani").apply()
        store.put("eclog.boot.Selvarani", EcBoot.serializer(), EcBoot(today, tabs.firstOrNull { it.date == today && it.who == "Selvarani" },
            tabs.flatMap { t -> t.rows.filter { it.point == "Yard" }.map { it.spot } }.distinct(), people, Limits()))

        // Water: last golden day becomes today
        val w = golden("water.json")
        val events = w["events"]!!.jsonArray.map { it.jsonObject }
        val lastT = events.maxOf { it["t"]!!.jsonPrimitive.long }
        val move = System.currentTimeMillis() - 2 * 3_600_000L - lastT
        val taps = events.map { e -> Tap(e["t"]!!.jsonPrimitive.long + move, e["uid"]!!.jsonPrimitive.content, e["name"]!!.jsonPrimitive.content,
            e["place"]!!.jsonPrimitive.content, e["ev"]!!.jsonPrimitive.content) }
        store.put("water", WaterData.serializer(), WaterData(listOf(Tanker("11CE2D07", "Tuck Tanker", "FCP"), Tanker("A1B2C3D4", "Big Blue", "FCP")),
            taps, "Asia/Kolkata", lastRow = 300, fullAt = System.currentTimeMillis()))

        // Raw material check
        val h = golden("husk.json")
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val vr = Rm.readVendor(plain(h["vendor"]!!.jsonObject["Form Responses 1"]!!), tz, false)
        store.put("husk", RmRaw.serializer(), RmRaw(Rm.readCompany(plain(h["company"]!!.jsonObject["Form Responses 1"]!!), tz, false), vr.rows,
            Rm.readGroups(plain(h["vendor"]!!.jsonObject["RM Matches"]!!), tz), vr.hasVendor, "Form Responses 1", "Form Responses 1"))

        // Stores
        val inv = golden("inventory.json")
        store.put("inventory", InvData.serializer(), Inv.parse(inv["tabs"]!!.jsonObject.mapValues { (_, v) -> plain(v) }))

        // Fleet
        val f = golden("fleet.json")
        store.put("fleet", FleetRaw.serializer(), FleetRaw(f["tabs"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonArray.toList() } }))
    }

    private fun daysBetween(a: String, b: String): Int {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return ((f.parse(b)!!.time - f.parse(a)!!.time) / 86_400_000L).toInt()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun tap(text: String, substring: Boolean = false) {
        compose.waitUntil(8_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(text, substring = substring))[0].performClick()
    }

    private fun back() { Espresso.pressBack(); compose.waitForIdle() }

    /** Scrolls whichever list holds the text until it is on screen. */
    private fun scrollTo(text: String) {
        val n = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
        for (i in 0 until n) {
            try { compose.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText(text, substring = true)); return } catch (_: Throwable) { }
        }
    }

    @Test fun everyScreen() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("01-hub")
            tap("EC Log"); shot("02-ec-log"); back()
            tap("EC Viewer"); shot("03-ec-viewer"); tap("Yards"); shot("04-ec-viewer-yards"); back()
            tap("Water record"); shot("05-water"); back()
            tap("Raw material check"); shot("06-husk-match")
            try { scrollTo("Velingiri"); tap("Velingiri"); shot("07-husk-picked"); tap("Clear") } catch (_: Throwable) { shot("07-husk-nopick") }
            scrollTo("Summary"); tap("Summary"); shot("08-husk-summary"); tap("History"); shot("09-husk-history"); back()
            tap("Stores"); shot("10-stores")
            tap("Bearing 6308"); shot("11-stores-item"); tap("Take"); shot("12-stores-who"); back(); back(); back()
            tap("Fleet"); shot("13-fleet-board")
            tap("Log"); shot("14-fleet-log"); tap("Service"); shot("15-fleet-service"); tap("Papers"); shot("16-fleet-papers"); tap("Garage"); shot("17-fleet-garage"); back()
            tap("Manage apps and people"); shot("18-hub-manage"); back()
        }
    }
}
