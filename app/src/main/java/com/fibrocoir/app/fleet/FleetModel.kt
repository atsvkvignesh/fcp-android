package com.fibrocoir.app.fleet

import com.fibrocoir.app.core.Js
import com.fibrocoir.app.core.google.Cells
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Fleet manager: a line-by-line port of the Fleet Apps Script. The sheet is loaded into a
 * FleetBook in memory; each action runs against it exactly like the script ran against the
 * sheet, and records the row writes / deletes it made. FleetRepo then makes the same changes
 * in the real sheet. tools/golden/fleet.js records the script's own changes for the test.
 */

/** A date cell (the script got these as Date objects). */
data class FDate(val ms: Long)

/** The sheet as read (kept on the phone between visits). */
@Serializable
data class FleetRaw(val tabs: Map<String, List<List<JsonElement>>>)

@Serializable data class Vehicle(val id: String, val regNo: String, val name: String, val type: String, val fuelType: String,
    val tank: Double, val driver: String, val status: String, val odo: Double, val lastOdoDate: String, val notes: String)
@Serializable data class DocState(val docId: String = "", val docNumber: String = "", val issueDate: String = "", val expiryDate: String = "",
    val fileUrl: String = "", val provider: String = "", val cost: Double = 0.0, val notes: String = "", val days: Long? = null,
    val core: Boolean = true, val state: String, val missing: Boolean = false)
@Serializable data class Compliance(val vehicleId: String, val regNo: String, val name: String, val types: List<String>, val docs: Map<String, DocState>)
@Serializable data class SvcReminder(val id: String, val vehicleId: String, val regNo: String, val name: String, val dueDate: String,
    val dueOdo: Double?, val repeatDays: Double?, val repeatKm: Double?, val notes: String, val days: Long?, val km: Double?, val state: String)
@Serializable data class FleetStats(val monthLabel: String, val fuelCost: Double, val litres: Double, val serviceCost: Double, val staleCount: Int)
@Serializable data class FleetBoot(
    val company: String, val currency: String, val user: String, val today: String, val vehicles: List<Vehicle>,
    val compliance: List<Compliance>, val docTypes: List<String>, val coreDocTypes: List<String>,
    val serviceReminders: List<SvcReminder>, val stats: FleetStats, val settings: Map<String, String>,
)
data class Part(val name: String, val number: String, val qty: String, val cost: String)

class FleetException(message: String) : Exception(message)

class FleetBook(
    private val tabs: MutableMap<String, MutableList<MutableList<Any?>>>,
    private val now: Long,
    private val newIdFn: (String) -> String,
    private val user: String,
) {
    sealed class Op {
        /** row is 1-based; values start at column `col` */
        data class Set(val tab: String, val row: Int, val col: Int, val values: List<Any?>) : Op()
        data class Del(val tab: String, val row: Int) : Op()
    }
    val ops = ArrayList<Op>()

    companion object {
        val TZ: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
        const val VEHICLES = "Vehicles"; const val ODO = "Odometer"; const val FUEL = "Fuel"; const val SERVICE = "Service"
        const val PARTS = "Parts"; const val DOCS = "Documents"; const val SVCREM = "ServiceReminders"; const val REMINDERS = "ReminderLog"
        const val SETTINGS = "Settings"
        val HEADERS = mapOf(
            VEHICLES to listOf("VehicleID", "RegNo", "Name", "Type", "FuelType", "TankCapacity", "Driver", "Status", "CurrentOdo", "LastOdoDate", "Notes", "UpdatedAt"),
            ODO to listOf("LogID", "Timestamp", "VehicleID", "RegNo", "Date", "Reading", "KmSinceLast", "EnteredBy", "Notes"),
            FUEL to listOf("LogID", "Timestamp", "VehicleID", "RegNo", "Date", "Odometer", "FuelType", "Litres", "RatePerLitre", "TotalCost", "Station", "Mileage", "BillURL", "EnteredBy", "Notes"),
            SERVICE to listOf("ServiceID", "Timestamp", "VehicleID", "RegNo", "Date", "Odometer", "Category", "Summary", "PartsSummary", "Cost", "Vendor", "NextServiceDate", "NextServiceOdo", "BillURL", "EnteredBy", "Notes"),
            PARTS to listOf("PartRowID", "ServiceID", "VehicleID", "RegNo", "Date", "PartName", "PartNumber", "Qty", "Cost"),
            DOCS to listOf("DocID", "Timestamp", "VehicleID", "RegNo", "DocType", "DocNumber", "IssueDate", "ExpiryDate", "Provider", "Cost", "FileURL", "Notes"),
            SVCREM to listOf("ReminderID", "Timestamp", "VehicleID", "RegNo", "Name", "DueDate", "DueOdo", "RepeatDays", "RepeatKm", "Status", "CompletedDate", "Notes"),
            REMINDERS to listOf("Timestamp", "Key", "RegNo", "DocType", "ExpiryDate", "DaysLeft", "SentTo"),
            SETTINGS to listOf("Key", "Value", "Description"),
        )
        /** Columns that hold dates: numbers there are Sheets day numbers. */
        val DATE_COLS = setOf("Timestamp", "LastOdoDate", "UpdatedAt", "Date", "NextServiceDate", "IssueDate", "ExpiryDate", "DueDate", "CompletedDate")
        val CORE_DOC_TYPES = listOf("FC", "Insurance", "Tax", "Pollution")
        val DOC_LABELS = mapOf("FC" to "FC (fitness certificate)", "Insurance" to "Insurance", "Tax" to "Road tax", "Pollution" to "Pollution (PUC)")

        /** The sheet's cells (numbers, text) to the book's values, with date columns as FDate. */
        fun from(raw: Map<String, List<List<Any?>>>, now: Long, newId: (String) -> String, user: String): FleetBook {
            val tabs = HashMap<String, MutableList<MutableList<Any?>>>()
            HEADERS.forEach { (name, head) ->
                val rows = raw[name].orEmpty().map { r ->
                    r.mapIndexed { c, v -> if (v is Double && head.getOrNull(c) in DATE_COLS) FDate(Cells.serialToMillis(v, TZ)) else v }.toMutableList()
                }.toMutableList()
                if (rows.isEmpty()) rows += head.toMutableList<Any?>()
                tabs[name] = rows
            }
            return FleetBook(tabs, now, newId, user)
        }

        /** JS: prefix-<time in base 36><0..46655 in base 36>, upper case. */
        fun newId(prefix: String, now: Long, random: Double): String =
            prefix + "-" + now.toString(36).uppercase() + floor(random * 46656).toLong().toString(36).uppercase()

        fun fmt(ms: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TZ }.format(Date(ms))
    }

    // ------------------------------------------------------------------ sheet helpers (same names as the script)

    class Rec(val row: Int, val v: Map<String, Any?>) {
        operator fun get(k: String): Any? = v[k]
    }

    private fun rows(name: String) = tabs.getOrPut(name) { mutableListOf(HEADERS[name]!!.toMutableList<Any?>()) }

    fun readTable(name: String): List<Rec> {
        val sh = rows(name)
        val last = sh.size
        if (last < 2) return emptyList()
        val head = HEADERS[name]!!
        return (1 until last).mapNotNull { i ->
            val r = sh[i]
            if (Js.str(r.getOrNull(0)).trim().isEmpty()) null
            else Rec(i + 1, head.mapIndexed { c, h -> h to (r.getOrNull(c) ?: "") }.toMap())
        }
    }

    private fun appendRow(name: String, obj: Map<String, Any?>): Any? {
        val head = HEADERS[name]!!
        val row = head.map { obj[it] ?: "" }
        val sh = rows(name)
        ops += Op.Set(name, sh.size + 1, 1, row)
        sh += row.toMutableList()
        return row[0]
    }

    private fun findRow(name: String, id: Any?): Int {
        val sh = rows(name)
        val last = sh.size
        if (last < 2) return 0
        for (i in 1 until last) if (Js.str(sh[i].getOrNull(0)).trim() == Js.str(id).trim()) return i + 1
        return 0
    }

    private fun updateRow(name: String, id: Any?, obj: Map<String, Any?>) {
        val row = findRow(name, id)
        if (row == 0) throw FleetException("Could not find record ${Js.str(id)} in $name.")
        val head = HEADERS[name]!!
        val sh = rows(name)
        val current = head.indices.map { sh[row - 1].getOrNull(it) ?: "" }.toMutableList()
        head.forEachIndexed { c, h -> if (obj.containsKey(h)) current[c] = obj[h] }
        ops += Op.Set(name, row, 1, current)
        while (sh[row - 1].size < current.size) sh[row - 1].add("")
        current.forEachIndexed { c, v -> sh[row - 1][c] = v }
    }

    private fun deleteRow(name: String, id: Any?) {
        val row = findRow(name, id)
        if (row == 0) throw FleetException("Could not find record ${Js.str(id)}.")
        delete(name, row)
    }

    private fun delete(name: String, row: Int) { ops += Op.Del(name, row); rows(name).removeAt(row - 1) }

    fun newId(prefix: String) = newIdFn(prefix)

    fun getSetting(key: String, fallback: String = ""): String {
        for (r in readTable(SETTINGS)) {
            if (Js.str(r["Key"]).trim() == key) {
                val v = Js.str(r["Value"]).trim()
                if (v != "") return v
            }
        }
        return fallback
    }

    private fun setSetting(key: String, value: Any?) {
        val row = findRow(SETTINGS, key)
        if (row != 0) {
            ops += Op.Set(SETTINGS, row, 2, listOf(value))
            val r = rows(SETTINGS)[row - 1]; while (r.size < 2) r.add(""); r[1] = value
        } else {
            val sh = rows(SETTINGS)
            ops += Op.Set(SETTINGS, sh.size + 1, 1, listOf(key, value, ""))
            sh += mutableListOf<Any?>(key, value, "")
        }
    }

    // ------------------------------------------------------------------ dates (the script's helpers)

    private fun localDate(y: Int, m: Int, d: Int, h: Int = 12): FDate {
        val c = Calendar.getInstance(TZ).apply { clear(); set(y, m - 1, d, h, 0, 0) }
        return FDate(c.timeInMillis)
    }

    /** 'yyyy-MM-dd' -> that day at noon (avoids time zone drift in the sheet); '' when blank. */
    fun toDate(s: Any?): Any {
        if (s == null || s == "" ) return ""
        if (s is FDate) return s
        val p = Js.str(s).split('-')
        if (p.size != 3) return ""
        val y = Js.number(p[0]); val m = Js.number(p[1]); val d = Js.number(p[2])
        if (y.isNaN() || m.isNaN() || d.isNaN()) return ""
        return localDate(y.toInt(), m.toInt(), d.toInt())
    }

    /** new Date(x) for what a cell can hold. */
    private fun jsDate(v: Any?): Long? = when (v) {
        is FDate -> v.ms
        is Double -> v.roundToLong()
        null, "" -> null
        else -> parseDateText(Js.str(v))
    }

    private fun parseDateText(s: String): Long? {
        val t = s.trim()
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(t)?.let { m ->   // ISO date only = UTC midnight
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt()) }
            return c.timeInMillis
        }
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "M/d/yyyy HH:mm:ss", "M/d/yyyy", "MMM d, yyyy", "d MMM yyyy")) {
            try { SimpleDateFormat(p, Locale.US).apply { timeZone = TZ; isLenient = false }.parse(t)?.let { return it.time } } catch (_: Exception) { }
        }
        return null
    }

    fun fmtDate(d: Any?): String {
        if (!Js.truthy(d) && d !is FDate) return ""
        val ms = jsDate(d) ?: return ""
        return fmt(ms, "yyyy-MM-dd")
    }

    private fun todayDate(): FDate {
        val c = Calendar.getInstance(TZ).apply { timeInMillis = now }
        return localDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun daysBetween(a: Long, b: Long): Long = floor((a - b) / 86_400_000.0 + 0.5).toLong()

    fun num(v: Any?): Double {
        val s = Js.str(if (v is FDate) Date(v.ms).toString() else v).replace(Regex("[^0-9.\\-]"), "")
        val n = com.fibrocoir.app.husk.Rm.parseFloat(s)
        return if (n.isNaN()) 0.0 else n
    }

    private fun stamp() = FDate(now)
    private fun s(v: Any?) = if (v == null) "" else if (v is FDate) v else v
    private fun text(v: Any?): String = if (v is FDate) fmtDate(v) else Js.str(v)

    // ------------------------------------------------------------------ reads

    fun bootstrap(): FleetBoot {
        val vehicles = readTable(VEHICLES).map { v ->
            Vehicle(text(v["VehicleID"]), text(v["RegNo"]), text(v["Name"]), text(v["Type"]), text(v["FuelType"]), num(v["TankCapacity"]),
                text(v["Driver"]), text(Js.or(v["Status"], "Active")), num(v["CurrentOdo"]), fmtDate(v["LastOdoDate"]), text(v["Notes"]))
        }
        return FleetBoot(
            company = getSetting("companyName", "Fleet"), currency = getSetting("currency", "Rs."), user = user,
            today = fmtDate(todayDate()), vehicles = vehicles, compliance = compliance(), docTypes = allDocTypes(),
            coreDocTypes = CORE_DOC_TYPES, serviceReminders = serviceReminders(), stats = stats(),
            settings = listOf("recipients", "reminderDays", "serviceKmWarn", "reminderHour", "expiredRepeat", "odoNudge", "odoStaleDays")
                .associateWith { getSetting(it) },
        )
    }

    /** Latest document of each type per vehicle, with days remaining. */
    fun compliance(): List<Compliance> {
        val vehicles = readTable(VEHICLES)
        val docs = readTable(DOCS)
        val t = todayDate().ms
        data class L(val rec: Rec, val exp: Long)
        val latest = LinkedHashMap<String, L>()
        val extra = HashMap<String, LinkedHashSet<String>>()
        docs.forEach { d ->
            val type = Js.str(d["DocType"]).trim()
            if (type.isEmpty()) return@forEach
            val exp = jsDate(d["ExpiryDate"]) ?: return@forEach
            if (type !in CORE_DOC_TYPES) extra.getOrPut(Js.str(d["VehicleID"])) { LinkedHashSet() } += type
            val key = Js.str(d["VehicleID"]) + "|" + type
            val cur = latest[key]
            if (cur == null || exp > cur.exp) latest[key] = L(d, exp)
        }
        return vehicles.filter { Js.str(Js.or(it["Status"], "Active")).lowercase() != "retired" }.map { v ->
            val vid = Js.str(v["VehicleID"])
            val types = CORE_DOC_TYPES + extra[vid].orEmpty().sorted()
            val docsOut = LinkedHashMap<String, DocState>()
            types.forEach { dt ->
                val rec = latest["$vid|$dt"]
                if (rec != null) {
                    val days = daysBetween(rec.exp, t)
                    val d = rec.rec
                    docsOut[dt] = DocState(
                        docId = text(d["DocID"]), docNumber = text(d["DocNumber"]), issueDate = fmtDate(d["IssueDate"]),
                        expiryDate = fmt(rec.exp, "yyyy-MM-dd"), fileUrl = text(d["FileURL"]), provider = text(d["Provider"]),
                        cost = num(d["Cost"]), notes = text(d["Notes"]), days = days, core = dt in CORE_DOC_TYPES,
                        state = if (days < 0) "expired" else if (days <= 15) "critical" else if (days <= 45) "due" else "ok",
                    )
                } else docsOut[dt] = DocState(missing = true, state = "missing", core = true)
            }
            Compliance(vid, text(v["RegNo"]), text(v["Name"]), types, docsOut)
        }
    }

    fun allDocTypes(): List<String> {
        val seen = LinkedHashSet<String>(CORE_DOC_TYPES)
        readTable(DOCS).forEach { d -> Js.str(d["DocType"]).trim().takeIf { it.isNotEmpty() }?.let { seen += it } }
        return seen.toList()
    }

    fun serviceReminders(): List<SvcReminder> {
        val t = todayDate().ms
        val odoBy = HashMap<String, Double>()
        readTable(VEHICLES).forEach { odoBy[Js.str(it["VehicleID"])] = num(it["CurrentOdo"]) }
        val order = mapOf("overdue" to 0, "critical" to 1, "due" to 2, "ok" to 3)
        return readTable(SVCREM).filter { Js.str(Js.or(it["Status"], "Open")).lowercase() == "open" }.map { r ->
            var days: Long? = null; var km: Double? = null
            if (Js.truthy(r["DueDate"]) || r["DueDate"] is FDate) jsDate(r["DueDate"])?.let { days = daysBetween(it, t) }
            if (num(r["DueOdo"]) != 0.0) km = num(r["DueOdo"]) - (odoBy[Js.str(r["VehicleID"])] ?: 0.0)
            val dd = days; val kk = km
            val state = when {
                (dd != null && dd < 0) || (kk != null && kk < 0) -> "overdue"
                (dd != null && dd <= 7) || (kk != null && kk <= 200) -> "critical"
                (dd != null && dd <= 30) || (kk != null && kk <= 1000) -> "due"
                else -> "ok"
            }
            SvcReminder(text(r["ReminderID"]), text(r["VehicleID"]), text(r["RegNo"]), text(r["Name"]), fmtDate(r["DueDate"]),
                num(r["DueOdo"]).takeIf { it != 0.0 }, num(r["RepeatDays"]).takeIf { it != 0.0 }, num(r["RepeatKm"]).takeIf { it != 0.0 },
                text(r["Notes"]), dd, kk, state)
        }.sortedBy { order[it.state] }
    }

    fun reminderHistory(): List<Triple<String, String, Pair<String, String>>> =
        readTable(SVCREM).filter { Js.str(it["Status"]).lowercase() == "done" }
            .map { Triple(text(it["ReminderID"]), text(it["RegNo"]), text(it["Name"]) to fmtDate(it["CompletedDate"])) }
            .reversed().take(60)

    fun stats(): FleetStats {
        val t = todayDate()
        val c = Calendar.getInstance(TZ).apply { timeInMillis = t.ms }
        val monthStart = localDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, 1, 0).ms
        var fuelCost = 0.0; var litres = 0.0; var svcCost = 0.0
        readTable(FUEL).forEach { f -> val d = jsDate(f["Date"]); if (d != null && d >= monthStart) { fuelCost += num(f["TotalCost"]); litres += num(f["Litres"]) } }
        readTable(SERVICE).forEach { sv -> val d = jsDate(sv["Date"]); if (d != null && d >= monthStart) svcCost += num(sv["Cost"]) }
        return FleetStats(fmt(t.ms, "MMMM yyyy"), floor(fuelCost + 0.5), floor(litres * 100 + 0.5) / 100, floor(svcCost + 0.5), staleOdo().size)
    }

    fun staleOdo(): List<Vehicle> {
        val limit = getSetting("odoStaleDays", "3").toIntOrNull()?.takeIf { it != 0 } ?: 3
        val t = todayDate().ms
        return readTable(VEHICLES).filter { v ->
            if (Js.str(Js.or(v["Status"], "Active")).lowercase() != "active") return@filter false
            if (!Js.truthy(v["LastOdoDate"]) && v["LastOdoDate"] !is FDate) return@filter true
            val d = jsDate(v["LastOdoDate"]) ?: return@filter true
            daysBetween(t, d) >= limit
        }.map { v -> Vehicle(text(v["VehicleID"]), text(v["RegNo"]), text(v["Name"]), "", "", 0.0, "", "", num(v["CurrentOdo"]), fmtDate(v["LastOdoDate"]), "") }
    }

    /** Recent rows for the history lists: every column, dates as yyyy-MM-dd; service rows carry their parts. */
    fun logs(kind: String, vehicleId: String = "", limit: Int = 150): List<Map<String, Any?>> {
        val name = mapOf("odo" to ODO, "fuel" to FUEL, "service" to SERVICE, "docs" to DOCS)[kind] ?: throw FleetException("Unknown log type.")
        var list = readTable(name)
        if (vehicleId.isNotEmpty()) list = list.filter { Js.str(it["VehicleID"]) == vehicleId }
        fun key(r: Rec): Long? = jsDate(if (r["Date"] is FDate || Js.truthy(r["Date"])) r["Date"] else r["Timestamp"])
        list = list.sortedWith { a, b ->
            val ka = key(a); val kb = key(b)
            when {
                ka == null || kb == null -> 0      // NaN in the script: leave the order alone
                else -> kb.compareTo(ka)
            }
        }
        return list.take(limit).map { r ->
            val o = LinkedHashMap<String, Any?>()
            HEADERS[name]!!.forEach { h -> o[h] = r[h].let { if (it is FDate) fmtDate(it) else it } }
            if (name == SERVICE) o["parts"] = partsFor(Js.str(o["ServiceID"]))
            o
        }
    }

    fun partsFor(serviceId: String): List<Map<String, Any?>> = readTable(PARTS).filter { Js.str(it["ServiceID"]) == serviceId }.map { p ->
        mapOf("id" to p["PartRowID"], "name" to p["PartName"], "number" to p["PartNumber"], "qty" to num(p["Qty"]), "cost" to num(p["Cost"]))
    }

    fun vehicle(id: String): Rec = readTable(VEHICLES).firstOrNull { Js.str(it["VehicleID"]) == id }
        ?: throw FleetException("Vehicle not found. Refresh the app and try again.")

    // ------------------------------------------------------------------ writes

    fun saveVehicle(id: String, regNo: String, name: String, type: String, fuelType: String, tank: String, driver: String,
                    odo: String, status: String, notes: String): String {
        val vid = id.ifEmpty { newId("VEH") }
        val payload = linkedMapOf<String, Any?>(
            "VehicleID" to vid, "RegNo" to regNo.uppercase().trim(), "Name" to name, "Type" to type, "FuelType" to fuelType,
            "TankCapacity" to num(tank), "Driver" to driver, "Status" to status.ifEmpty { "Active" }, "Notes" to notes, "UpdatedAt" to stamp(),
        )
        if (id.isNotEmpty() && findRow(VEHICLES, id) != 0) updateRow(VEHICLES, id, payload)
        else {
            payload["CurrentOdo"] = num(odo)
            payload["LastOdoDate"] = if (Js.truthy(odo)) todayDate() else ""
            appendRow(VEHICLES, payload)
        }
        return vid
    }

    fun deleteVehicle(id: String) = deleteRow(VEHICLES, id)

    fun saveOdometer(id: String, vehicleId: String, date: String, reading: String, notes: String, allowLower: Boolean) {
        val v = vehicle(vehicleId)
        val r = num(reading)
        val prev = num(v["CurrentOdo"])
        val d = toDate(date).let { if (it == "") todayDate() else it }
        if (id.isEmpty() && prev != 0.0 && r < prev && !allowLower) {
            throw FleetException("Reading ${Js.num(r)} km is below the last recorded ${Js.num(prev)} km for ${text(v["RegNo"])}. Fix the number, or tick \"allow correction\".")
        }
        val payload = linkedMapOf<String, Any?>(
            "Timestamp" to stamp(), "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"], "Date" to d,
            "Reading" to r, "KmSinceLast" to (if (prev != 0.0 && r >= prev) r - prev else ""),
            "EnteredBy" to user, "Notes" to notes,
        )
        if (id.isNotEmpty()) updateRow(ODO, id, payload)
        else { payload["LogID"] = newId("ODO"); appendRow(ODO, payload) }
        refreshVehicleOdo(Js.str(v["VehicleID"]))
    }

    /** What the bill file is called in Drive. */
    fun fuelLabel(vehicleId: String, date: String) = text(vehicle(vehicleId)["RegNo"]) + " fuel " + fmtDate(toDate(date).let { if (it == "") todayDate() else it })
    fun serviceLabel(vehicleId: String, date: String) = text(vehicle(vehicleId)["RegNo"]) + " service " + fmtDate(toDate(date).let { if (it == "") todayDate() else it })

    fun saveFuel(id: String, vehicleId: String, date: String, odometer: String, fuelType: String, litres: String, rate: String,
                 total: String, station: String, notes: String, billUrl: String) {
        val v = vehicle(vehicleId)
        val l = num(litres)
        var rt = num(rate)
        val tot = num(total).let { if (it != 0.0) it else l * rt }
        if (rt == 0.0 && l != 0.0 && tot != 0.0) rt = floor((tot / l) * 100 + 0.5) / 100
        val odo = num(odometer)
        val d = toDate(date).let { if (it == "") todayDate() else it }
        val payload = linkedMapOf<String, Any?>(
            "Timestamp" to stamp(), "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"], "Date" to d,
            "Odometer" to (if (odo != 0.0) odo else ""), "FuelType" to fuelType.ifEmpty { Js.str(v["FuelType"]) }, "Litres" to l,
            "RatePerLitre" to rt, "TotalCost" to floor(tot * 100 + 0.5) / 100,
            "Station" to station, "Mileage" to mileageFor(Js.str(v["VehicleID"]), odo, l, id),
            "BillURL" to billUrl, "EnteredBy" to user, "Notes" to notes,
        )
        if (id.isNotEmpty()) updateRow(FUEL, id, payload)
        else {
            payload["LogID"] = newId("FUE")
            appendRow(FUEL, payload)
            if (odo != 0.0) saveOdometerSilent(v, odo, d, "From fuel entry")
        }
    }

    private fun mileageFor(vehicleId: String, odo: Double, litres: Double, excludeId: String): Any {
        if (odo == 0.0 || litres == 0.0) return ""
        val list = readTable(FUEL).filter { Js.str(it["VehicleID"]) == vehicleId && num(it["Odometer"]) > 0 && num(it["Odometer"]) < odo && Js.str(it["LogID"]) != excludeId }
        if (list.isEmpty()) return ""
        val prev = list.reduce { a, b -> if (num(a["Odometer"]) > num(b["Odometer"])) a else b }
        val km = odo - num(prev["Odometer"])
        if (km <= 0) return ""
        return floor((km / litres) * 100 + 0.5) / 100
    }

    fun saveService(id: String, vehicleId: String, date: String, odometer: String, category: String, summary: String, cost: String,
                    vendor: String, nextDate: String, nextOdo: String, notes: String, parts: List<Part>, billUrl: String): String {
        val v = vehicle(vehicleId)
        val d = toDate(date).let { if (it == "") todayDate() else it }
        val serviceId = id.ifEmpty { newId("SVC") }
        val partsSummary = parts.joinToString("; ") { p ->
            p.name + (if (p.number.isNotEmpty()) " [${p.number}]" else "") + (if (num(p.qty) > 1) " x" + Js.num(num(p.qty)) else "")
        }
        val payload = linkedMapOf<String, Any?>(
            "Timestamp" to stamp(), "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"], "Date" to d,
            "Odometer" to (num(odometer).takeIf { it != 0.0 } ?: ""), "Category" to category.ifEmpty { "General" },
            "Summary" to summary, "PartsSummary" to partsSummary, "Cost" to num(cost), "Vendor" to vendor,
            "NextServiceDate" to toDate(nextDate), "NextServiceOdo" to (num(nextOdo).takeIf { it != 0.0 } ?: ""),
            "BillURL" to billUrl, "EnteredBy" to user, "Notes" to notes,
        )
        if (id.isNotEmpty()) {
            updateRow(SERVICE, id, payload)
            readTable(PARTS).filter { Js.str(it["ServiceID"]) == serviceId }.sortedByDescending { it.row }.forEach { delete(PARTS, it.row) }
        } else {
            payload["ServiceID"] = serviceId
            appendRow(SERVICE, payload)
            if (num(odometer) != 0.0) saveOdometerSilent(v, num(odometer), d, "From service entry")
        }
        parts.forEach { p ->
            if (p.name.isEmpty() && p.number.isEmpty()) return@forEach
            appendRow(PARTS, linkedMapOf(
                "PartRowID" to newId("PRT"), "ServiceID" to serviceId, "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"], "Date" to d,
                "PartName" to p.name, "PartNumber" to p.number, "Qty" to (num(p.qty).takeIf { it != 0.0 } ?: 1.0), "Cost" to num(p.cost),
            ))
        }
        return serviceId
    }

    /** The checks saveDocument() makes before the upload; returns the document type and the file label. */
    fun checkDocument(vehicleId: String, docType: String, customType: String, expiryDate: String): Pair<String, String> {
        val v = vehicle(vehicleId)
        val expiry = toDate(expiryDate)
        if (expiry == "") throw FleetException("An expiry date is required so the reminder can be scheduled.")
        var type = docType.trim()
        if (type == "Other" || type == "") {
            type = customType.trim()
            if (type.isEmpty()) throw FleetException("Type a name for this document.")
        }
        return type to (text(v["RegNo"]) + " " + type + " to " + fmtDate(expiry))
    }

    fun saveDocument(id: String, vehicleId: String, docType: String, customType: String, docNumber: String, issueDate: String,
                     expiryDate: String, provider: String, cost: String, notes: String, fileUrl: String) {
        val v = vehicle(vehicleId)
        val (type, _) = checkDocument(vehicleId, docType, customType, expiryDate)
        val payload = linkedMapOf<String, Any?>(
            "Timestamp" to stamp(), "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"],
            "DocType" to type, "DocNumber" to docNumber, "IssueDate" to toDate(issueDate),
            "ExpiryDate" to toDate(expiryDate), "Provider" to provider, "Cost" to num(cost), "FileURL" to fileUrl, "Notes" to notes,
        )
        if (id.isNotEmpty()) updateRow(DOCS, id, payload)
        else { payload["DocID"] = newId("DOC"); appendRow(DOCS, payload) }
    }

    fun deleteLog(kind: String, id: String) {
        val name = mapOf("odo" to ODO, "fuel" to FUEL, "service" to SERVICE, "docs" to DOCS)[kind] ?: throw FleetException("Unknown record type.")
        if (kind == "service") readTable(PARTS).filter { Js.str(it["ServiceID"]) == id }.sortedByDescending { it.row }.forEach { delete(PARTS, it.row) }
        deleteRow(name, id)
        if (kind == "odo") readTable(VEHICLES).forEach { refreshVehicleOdo(Js.str(it["VehicleID"])) }
    }

    /** Only the keys given are written; the daily check time itself is changed in the Fleet script. */
    fun saveSettings(s: Map<String, String>) = s.forEach { (k, v) -> setSetting(k, v) }

    fun saveServiceReminder(id: String, vehicleId: String, name: String, dueDate: String, dueOdo: String, repeatDays: String, repeatKm: String, notes: String) {
        val v = vehicle(vehicleId)
        if (name.trim().isEmpty()) throw FleetException("Give the reminder a name so you know what it is.")
        if (dueDate.isEmpty() && num(dueOdo) == 0.0) throw FleetException("Set a date, an odometer figure, or both.")
        val payload = linkedMapOf<String, Any?>(
            "Timestamp" to stamp(), "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"], "Name" to name.trim(),
            "DueDate" to toDate(dueDate), "DueOdo" to (num(dueOdo).takeIf { it != 0.0 } ?: ""),
            "RepeatDays" to (num(repeatDays).takeIf { it != 0.0 } ?: ""), "RepeatKm" to (num(repeatKm).takeIf { it != 0.0 } ?: ""),
            "Status" to "Open", "CompletedDate" to "", "Notes" to notes,
        )
        if (id.isNotEmpty()) updateRow(SVCREM, id, payload)
        else { payload["ReminderID"] = newId("REM"); appendRow(SVCREM, payload) }
    }

    /** Mark done. If it repeats, the next one is created straight away. Returns whether it repeated. */
    fun completeServiceReminder(id: String): Boolean {
        val r = readTable(SVCREM).firstOrNull { Js.str(it["ReminderID"]) == id } ?: throw FleetException("That reminder is no longer there.")
        updateRow(SVCREM, id, mapOf("Status" to "Done", "CompletedDate" to todayDate()))
        val rd = num(r["RepeatDays"]); val rk = num(r["RepeatKm"])
        if (rd == 0.0 && rk == 0.0) return false
        val v = vehicle(Js.str(r["VehicleID"]))
        val nextDate: Any = if (rd != 0.0) FDate(todayDate().ms + (rd * 86_400_000).roundToLong()) else ""
        val nextOdo: Any = if (rk != 0.0) num(v["CurrentOdo"]) + rk else ""
        appendRow(SVCREM, linkedMapOf(
            "ReminderID" to newId("REM"), "Timestamp" to stamp(), "VehicleID" to r["VehicleID"], "RegNo" to r["RegNo"],
            "Name" to r["Name"], "DueDate" to nextDate, "DueOdo" to nextOdo, "RepeatDays" to (if (rd != 0.0) rd else ""),
            "RepeatKm" to (if (rk != 0.0) rk else ""), "Status" to "Open", "CompletedDate" to "", "Notes" to r["Notes"],
        ))
        return true
    }

    fun deleteServiceReminder(id: String) = deleteRow(SVCREM, id)

    private fun saveOdometerSilent(v: Rec, reading: Double, date: Any, note: String) {
        val same = readTable(ODO).filter { r -> Js.str(r["VehicleID"]) == Js.str(v["VehicleID"]) && fmtDate(r["Date"]) == fmtDate(date) && num(r["Reading"]) == reading }
        if (same.isNotEmpty()) return
        val prev = num(v["CurrentOdo"])
        appendRow(ODO, linkedMapOf(
            "LogID" to newId("ODO"), "Timestamp" to stamp(), "VehicleID" to v["VehicleID"], "RegNo" to v["RegNo"],
            "Date" to date, "Reading" to reading, "KmSinceLast" to (if (prev != 0.0 && reading >= prev) reading - prev else ""),
            "EnteredBy" to user, "Notes" to note,
        ))
        refreshVehicleOdo(Js.str(v["VehicleID"]))
    }

    private fun refreshVehicleOdo(vehicleId: String) {
        val list = readTable(ODO).filter { Js.str(it["VehicleID"]) == vehicleId }
        if (list.isEmpty()) { updateRow(VEHICLES, vehicleId, mapOf("CurrentOdo" to "", "LastOdoDate" to "")); return }
        val best = list.reduce { a, b -> if (num(a["Reading"]) >= num(b["Reading"])) a else b }
        val latest = list.reduce { a, b ->
            val da = jsDate(a["Date"]); val db = jsDate(b["Date"])
            if (da != null && db != null && da >= db) a else b      // an unreadable date never wins, like NaN in the script
        }["Date"]
        updateRow(VEHICLES, vehicleId, mapOf("CurrentOdo" to num(best["Reading"]), "LastOdoDate" to (latest as? FDate ?: jsDate(latest)?.let { FDate(it) } ?: "")))
    }
}
