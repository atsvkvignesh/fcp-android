package com.fibrocoir.app.husk

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Raw material check: our husk loads (company form) against the vendor's slips (vendor form).
 * A straight port of the husk-compare script, so the numbers come out the same; the
 * golden test (tools/golden/husk.js) runs the script itself and compares.
 */

/** One of our loads (a company form row). */
@Serializable
data class OurLoad(
    val k: String, val row: Int, val dk: String, val time: String, val tm: Int,
    val truck: String, val driver: String, val place: String, val unload: String, val note: String,
    val vendor: String, val vk: String,
    val green: Double, val brown: Double, val gp: Double, val bp: Double, val count: Double,
    val cost: Double, val cft: Double?, val cftCost: Double?,
    val status: String? = null, val gid: String? = null,
)

/** One vendor slip (a vendor form row). */
@Serializable
data class VendorSlip(
    val k: String, val row: Int, val dk: String, val tm: Int, val entered: String,
    val place: String, val truck: String, val type: String, val tk: String,
    val count: Double, val rate: Double, val total: Double,
    val vendor: String, val vk: String, val note: String,
    val status: String? = null, val gid: String? = null,
)

/** A decision saved in the "RM Matches" tab, as stored. */
@Serializable
data class SavedGroup(
    val id: String, val status: String, val dk0: String, val vendor: String, val note: String,
    val cks: List<String>, val vks: List<String>, val created: String, val by: String,
)

/** Everything read from the two sheets; filters are applied on the phone, so changing them is instant. */
@Serializable
data class RmRaw(
    val loads: List<OurLoad>,
    val slips: List<VendorSlip>,
    val groups: List<SavedGroup>,
    val hasVendorCol: Boolean,
    val companyTab: String,
    val vendorTab: String,
)

@Serializable
data class Metrics(
    val loads: Double = 0.0, val entries: Double = 0.0, val driver: Double = 0.0, val green: Double = 0.0, val brown: Double = 0.0,
    val cft: Double = 0.0, val driverCft: Double = 0.0, val costCft: Double = 0.0, val noCft: Double = 0.0,
    val ourCost: Double = 0.0, val cftCost: Double = 0.0, val vendor: Double = 0.0, val vGreen: Double = 0.0,
    val vBrown: Double = 0.0, val vendorCost: Double = 0.0,
    val dV: Double = 0.0, val dC: Double = 0.0, val vC: Double = 0.0, val cV: Double = 0.0, val cC: Double = 0.0, val vCc: Double = 0.0,
)

/** A saved decision with its rows looked up. */
@Serializable
data class RmGroup(
    val id: String, val status: String, val dk: String, val vendor: String, val note: String,
    val created: String, val by: String, val cks: List<String>, val vks: List<String>, val lost: Int, val m: Metrics,
    @Transient val vKey: String = "",
    @Transient val cs: List<OurLoad> = emptyList(),
    @Transient val vs: List<VendorSlip> = emptyList(),
)

/** count / husk / amount */
@Serializable
data class Pile(val n: Int = 0, val husk: Double = 0.0, val cost: Double = 0.0) {
    fun add(h: Double, c: Double) = Pile(n + 1, husk + h, Rm.r2(cost + c))
}

@Serializable
data class VendorSum(
    val key: String, val name: String, val groups: Int, val m: Metrics,
    val missV: Pile, val missF: Pile, val pendC: Pile, val pendV: Pile,
)

@Serializable
data class RmSummary(
    val groups: Int, val missV: Pile, val missF: Pile, val ign: Pile, val pendC: Pile, val pendV: Pile,
    val ourAll: Double, val vendorAll: Double, val m: Metrics, val byVendor: List<VendorSum>,
)

@Serializable
data class VendorOpt(val key: String, val name: String, val pending: Int)

@Serializable
data class RmMeta(
    val hasVendorCol: Boolean, val companyTab: String, val vendorTab: String, val pin: Boolean = false,
    val from: String, val to: String, val vendorKey: String, val vendor: String,
)

/** What the screen shows for one set of filters (same as the script's api_load). */
@Serializable
data class RmState(
    val loads: List<OurLoad>, val entries: List<VendorSlip>, val vendors: List<VendorOpt>,
    val summary: RmSummary, val meta: RmMeta, val groups: List<RmGroup>,
)

data class RmFilter(val from: String = "", val to: String = "", val vendor: String = "")

object Rm {
    const val MATCHED = "MATCHED"
    const val MISS_V = "MISSING_AT_VENDOR"
    const val MISS_F = "MISSING_AT_FCP"
    const val IGN = "IGNORED"
    val ST_LIST = listOf(MATCHED, MISS_V, MISS_F, IGN)
    val LABEL = mapOf(MATCHED to "Matched", MISS_V to "Missing at vendor", MISS_F to "Missing at our end", IGN to "Ignored")

    const val MATCH_TAB = "RM Matches"
    const val EXPORT_TAB = "RM Check Export"
    const val TZ = "Asia/Kolkata"
    const val RATE_MAX = 20.0
    /** spelling fixes, 'as typed in lowercase' to 'Name to show' */
    val VENDOR_ALIASES = mapOf<String, String>()

    val MHEAD = listOf(
        "Group ID", "Status", "Load date", "Vendor", "Our loads", "Vendor entries",
        "Driver count", "CFT count", "Vendor count", "Our cost", "Vendor cost",
        "Driver − Vendor", "Company − Vendor cost", "Note", "Company keys", "Vendor keys", "Created", "By",
    )

    // ------------------------------------------------------------------ small helpers (JS behaviour)

    fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> jsNum(v)
        is Float -> jsNum(v.toDouble())
        is Number -> v.toString()
        else -> v.toString()
    }.trim()

    private fun jsNum(d: Double): String =
        if (d == floor(d) && abs(d) < 1e21) d.roundToLong().toString() else d.toString()

    fun norm(v: Any?) = str(v).lowercase().replace(Regex("\\s+"), " ")

    /** Math.round(n * 100) / 100 */
    fun r2(n: Double): Double = if (n.isNaN()) 0.0 else floor(n * 100 + 0.5) / 100

    private val FLOAT = Regex("^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)")

    /** JavaScript parseFloat: the longest number at the start, else NaN. */
    fun parseFloat(s: String): Double {
        val m = FLOAT.find(s.trimStart()) ?: return Double.NaN
        val t = m.value
        return when {
            t.endsWith("Infinity") -> if (t.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            else -> t.toDoubleOrNull() ?: Double.NaN
        }
    }

    fun num(v: Any?): Double {
        if (v is Double) return if (v.isFinite()) v else 0.0
        val n = parseFloat(str(v).replace(Regex("[,₹\\s]"), ""))
        return if (n.isFinite()) n else 0.0          // "No", blanks, #VALUE! -> 0
    }

    fun numOrNull(v: Any?): Double? {
        if (v is Double) return if (v.isFinite()) v else null
        val s = str(v).replace(Regex("[,\\s]"), "")
        if (s.isEmpty() || s[0] == '#') return null
        val n = parseFloat(s)
        return if (n.isFinite()) n else null
    }

    /** Find a column by header text: exact match first, then "starts with". */
    fun col(hdr: List<Any?>, names: List<String>): Int {
        val h = hdr.map(::norm)
        for (n in names) { val i = h.indexOf(n); if (i >= 0) return i }
        for (n in names) { val i = h.indexOfFirst { it.startsWith(n) }; if (i >= 0) return i }
        return -1
    }

    fun cell(row: List<Any?>, i: Int): Any? = if (i < 0) "" else row.getOrNull(i)

    // ------------------------------------------------------------------ dates

    /**
     * A date cell. `local` is the wall-clock time in the sheet's zone written as if it were UTC
     * (so formatting it in UTC shows what the sheet shows); `epoch` is the real instant.
     */
    data class When(val local: Long, val epoch: Long)

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun fromSerial(serial: Double, tz: TimeZone): When {
        val local = ((serial - 25569.0) * 86_400_000.0).roundToLong()
        return When(local, local - tz.getOffset(local - tz.rawOffset))
    }

    private fun fromDay(y: Int, mo: Int, d: Int, tz: TimeZone): When {
        val c = Calendar.getInstance(UTC).apply { clear(); isLenient = true; set(y, mo - 1, d) }
        val local = c.timeInMillis
        return When(local, local - tz.getOffset(local - tz.rawOffset))
    }

    /** Date cells come as day numbers; text dates are read the way the script reads them. */
    fun toDate(v: Any?, dmy: Boolean, tz: TimeZone): When? {
        if (v is Double) return if (v.isFinite()) fromSerial(v, tz) else null
        val s = str(v)
        if (s.isEmpty()) return null
        Regex("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})").find(s)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            var d = if (dmy) a else b; var mo = if (dmy) b else a
            if (mo > 12 && d <= 12) { val t = d; d = mo; mo = t }
            return fromDay(y, mo, d, tz)
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s)?.let { m ->
            return fromDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), tz)
        }
        for (p in listOf("d MMM yyyy", "MMM d, yyyy", "d MMMM yyyy", "MMMM d, yyyy")) {
            try {
                val t = SimpleDateFormat(p, Locale.US).apply { timeZone = UTC; isLenient = false }.parse(s)
                if (t != null) return When(t.time, t.time - tz.getOffset(t.time - tz.rawOffset))
            } catch (_: Exception) { }
        }
        return null
    }

    fun fmt(local: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(Date(local))

    fun dkey(w: When) = fmt(w.local, "yyyy-MM-dd")

    data class TimeInfo(val txt: String, val tm: Int)

    fun timeInfo(v: Any?, tz: TimeZone): TimeInfo {
        if (v is Double && v.isFinite()) {
            val w = fromSerial(v, tz)
            val inDay = Math.floorMod(w.local, 86_400_000L)
            return TimeInfo(fmt(w.local, "h:mm a"), (inDay / 3_600_000L * 60 + inDay % 3_600_000L / 60_000L).toInt())
        }
        val s = str(v)
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(s) ?: return TimeInfo(s, 0)
        var h = m.groupValues[1].toInt()
        val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return TimeInfo(s, h * 60 + m.groupValues[2].toInt())
    }

    data class VName(val name: String, val key: String)

    fun vendorName(v: Any?): VName {
        val name = str(v).replace(Regex("\\s+"), " ")
        val key = norm(name)
        if (key.isEmpty()) return VName("", "")
        VENDOR_ALIASES[key]?.let { return VName(it, norm(it)) }
        return VName(name.substring(0, 1).uppercase() + name.substring(1), key)
    }

    private fun uniqKey(seen: MutableMap<String, Int>, base: String): String {
        val n = seen[base]
        if (n != null) { seen[base] = n + 1; return base + "_" + (n + 1) }
        seen[base] = 1
        return base
    }

    // ------------------------------------------------------------------ finding the tabs

    fun isCompanyHeader(h: List<Any?>) = col(h, listOf("number of green husk")) >= 0 && col(h, listOf("driver name")) >= 0
    fun isVendorHeader(h: List<Any?>) = col(h, listOf("husk type")) >= 0 && col(h, listOf("number of husk")) >= 0

    // ------------------------------------------------------------------ readers

    /** Our loads; `v` is the whole tab (header row first), `dmy` = the sheet isn't en_US. */
    fun readCompany(v: List<List<Any?>>, tz: TimeZone, dmy: Boolean): List<OurLoad> {
        val h = v.firstOrNull().orEmpty()
        val cTs = col(h, listOf("timestamp"))
        val cTruck = col(h, listOf("truck number", "vehicle number"))
        val cDriver = col(h, listOf("driver name"))
        val cTime = col(h, listOf("time"))
        val cPlace = col(h, listOf("place"))
        val cGreen = col(h, listOf("number of green husk"))
        val cBrown = col(h, listOf("number of brown husk"))
        val cGp = col(h, listOf("green husk price"))
        val cBp = col(h, listOf("brown husk price"))
        val cVendor = col(h, listOf("vendor name"))
        val cNote = col(h, listOf("note"))
        val cDate = col(h, listOf("date"))
        val cUnload = col(h, listOf("un-loading point", "unloading point"))
        val cCft = col(h, listOf("number of husk"))       // husk estimated from truck CFT

        val rows = ArrayList<OurLoad>()
        val seen = HashMap<String, Int>()
        for (r in 1 until v.size) {
            val row = v[r]
            val green = num(cell(row, cGreen)); val brown = num(cell(row, cBrown))
            val ts = if (cTs >= 0) toDate(row.getOrNull(cTs), dmy, tz) else null
            val d = (if (cDate >= 0) toDate(row.getOrNull(cDate), dmy, tz) else null) ?: ts ?: continue
            if (ts == null && green == 0.0 && brown == 0.0 && str(cell(row, cDriver)).isEmpty()) continue

            val gp = num(cell(row, cGp)); val bp = num(cell(row, cBp))
            val count = green + brown
            val cost = green * gp + brown * bp
            val cftRaw = if (cCft >= 0) numOrNull(row.getOrNull(cCft)) else null
            val cft = cftRaw?.let { floor(it + 0.5) }
            val rate = if (count > 0) cost / count else (if (gp != 0.0) gp else if (bp != 0.0) bp else 0.0)
            val vn = vendorName(cell(row, cVendor))
            val ti = timeInfo(cell(row, cTime), tz)
            rows += OurLoad(
                k = uniqKey(seen, "C" + (ts?.epoch?.toString() ?: "r${r + 1}")),
                row = r + 1, dk = dkey(d), time = ti.txt, tm = ti.tm,
                truck = str(cell(row, cTruck)), driver = str(cell(row, cDriver)),
                place = str(cell(row, cPlace)), unload = str(cell(row, cUnload)), note = str(cell(row, cNote)),
                vendor = vn.name, vk = vn.key,
                green = green, brown = brown, gp = gp, bp = bp, count = count,
                cost = r2(cost), cft = cft, cftCost = cft?.let { r2(it * rate) },
            )
        }
        return rows
    }

    data class VendorRead(val rows: List<VendorSlip>, val hasVendor: Boolean)

    /** Vendor slips. */
    fun readVendor(v: List<List<Any?>>, tz: TimeZone, dmy: Boolean): VendorRead {
        val h = v.firstOrNull().orEmpty()
        val cTs = col(h, listOf("timestamp"))
        val cDate = col(h, listOf("date"))
        val cPlace = col(h, listOf("place"))
        val cType = col(h, listOf("husk type"))
        val cCount = col(h, listOf("number of husk", "husk count"))
        val cCost = col(h, listOf("cost", "amount", "total cost"))
        val cVendor = col(h, listOf("vendor name", "vendor"))
        val cNote = col(h, listOf("note", "notes", "remarks"))
        val cTruck = col(h, listOf("truck number", "vehicle number"))

        val rows = ArrayList<VendorSlip>()
        val seen = HashMap<String, Int>()
        for (r in 1 until v.size) {
            val row = v[r]
            val count = num(cell(row, cCount))
            val raw = num(cell(row, cCost))
            val ts = if (cTs >= 0) toDate(row.getOrNull(cTs), dmy, tz) else null
            val d = (if (cDate >= 0) toDate(row.getOrNull(cDate), dmy, tz) else null) ?: ts
            if (d == null || (count == 0.0 && raw == 0.0)) continue

            val rate: Double; val total: Double
            if (raw > 0 && raw <= RATE_MAX && count > 0) { rate = raw; total = raw * count }
            else { total = raw; rate = if (count > 0) raw / count else 0.0 }

            val type = str(cell(row, cType))
            val vn = vendorName(cell(row, cVendor))
            rows += VendorSlip(
                k = uniqKey(seen, "V" + (ts?.epoch?.toString() ?: "r${r + 1}")),
                row = r + 1, dk = dkey(d), tm = if (ts != null) timeInfo(serialOf(ts), tz).tm else 0,
                entered = if (ts != null) fmt(ts.local, "d MMM, h:mm a") else "",
                place = str(cell(row, cPlace)), truck = str(cell(row, cTruck)),
                type = type.ifEmpty { "Husk" },
                tk = when {
                    type.contains("brown", ignoreCase = true) -> "brown"
                    type.contains("green", ignoreCase = true) -> "green"
                    else -> "other"
                },
                count = count, rate = r2(rate), total = r2(total),
                vendor = vn.name, vk = vn.key, note = str(cell(row, cNote)),
            )
        }
        return VendorRead(rows, cVendor >= 0)
    }

    private fun serialOf(w: When): Double = w.local / 86_400_000.0 + 25569.0

    /** The "RM Matches" tab, header row first. */
    fun readGroups(v: List<List<Any?>>, tz: TimeZone): List<SavedGroup> {
        fun split(s: Any?) = str(s).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        fun truthy(x: Any?) = when (x) { null -> false; is Double -> x != 0.0 && !x.isNaN(); is Boolean -> x; else -> x.toString().isNotEmpty() }
        return v.drop(1).map { r -> List(MHEAD.size) { r.getOrNull(it) ?: "" } }
            .filter { truthy(it[0]) && truthy(it[1]) }
            .map { r ->
                SavedGroup(
                    id = r[0].let { if (it is Double) jsNum(it) else it.toString() }, status = r[1].toString(),
                    dk0 = (r[2] as? Double)?.let { dkey(fromSerial(it, tz)) } ?: str(r[2]),
                    vendor = str(r[3]), note = str(r[13]),
                    cks = split(r[14]), vks = split(r[15]),
                    created = (r[16] as? Double)?.let { fmt(fromSerial(it, tz).local, "d MMM yyyy, h:mm a") } ?: str(r[16]),
                    by = str(r[17]),
                )
            }
    }

    // ------------------------------------------------------------------ maths

    fun metrics(cs: List<OurLoad>, vs: List<VendorSlip>): Metrics {
        var driver = 0.0; var green = 0.0; var brown = 0.0; var ourCost = 0.0
        var cft = 0.0; var cftCost = 0.0; var driverCft = 0.0; var costCft = 0.0; var noCft = 0.0
        var vendor = 0.0; var vendorCost = 0.0; var vGreen = 0.0; var vBrown = 0.0
        cs.forEach { x ->
            driver += x.count; green += x.green; brown += x.brown; ourCost += x.cost
            if (x.cft == null) noCft++
            else { cft += x.cft; cftCost += x.cftCost ?: 0.0; driverCft += x.count; costCft += x.cost }
        }
        vs.forEach { x ->
            vendor += x.count; vendorCost += x.total
            if (x.tk == "green") vGreen += x.count else if (x.tk == "brown") vBrown += x.count
        }
        return Metrics(
            loads = r2(cs.size.toDouble()), entries = r2(vs.size.toDouble()), driver = r2(driver), green = r2(green), brown = r2(brown),
            cft = r2(cft), driverCft = r2(driverCft), costCft = r2(costCft), noCft = r2(noCft),
            ourCost = r2(ourCost), cftCost = r2(cftCost), vendor = r2(vendor), vGreen = r2(vGreen), vBrown = r2(vBrown),
            vendorCost = r2(vendorCost),
            dV = r2(driver - vendor), dC = r2(driverCft - cft), vC = r2(vendor - cft),
            cV = r2(ourCost - vendorCost), cC = r2(costCft - cftCost), vCc = r2(vendorCost - cftCost),
        )
    }

    private val collator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.TERTIARY }
    private fun cmp(a: String, b: String) = collator.compare(a, b)

    fun summarize(groups: List<RmGroup>, loads: List<OurLoad>, entries: List<VendorSlip>): RmSummary {
        class B(val key: String, val name: String) {
            val cs = ArrayList<OurLoad>(); val vs = ArrayList<VendorSlip>(); var groups = 0
            var missV = Pile(); var missF = Pile(); var pendC = Pile(); var pendV = Pile()
        }
        val byV = LinkedHashMap<String, B>()
        fun bv(k: String, name: String) = byV.getOrPut(k) { B(k, name.ifEmpty { "(no vendor)" }) }
        var nGroups = 0
        var missV = Pile(); var missF = Pile(); var ign = Pile(); var pendC = Pile(); var pendV = Pile()
        var ourAll = 0.0; var vendorAll = 0.0
        val mcs = ArrayList<OurLoad>(); val mvs = ArrayList<VendorSlip>()

        groups.forEach { g ->
            val b = bv(g.vKey, g.vendor)
            when (g.status) {
                MATCHED -> { nGroups++; b.groups++; g.cs.forEach { mcs += it; b.cs += it }; g.vs.forEach { mvs += it; b.vs += it } }
                MISS_V -> g.cs.forEach { missV = missV.add(it.count, it.cost); b.missV = b.missV.add(it.count, it.cost) }
                MISS_F -> g.vs.forEach { missF = missF.add(it.count, it.total); b.missF = b.missF.add(it.count, it.total) }
                else -> { g.cs.forEach { ign = ign.add(it.count, it.cost) }; g.vs.forEach { ign = ign.add(it.count, it.total) } }
            }
        }
        loads.forEach { x ->
            if (x.gid == null) { pendC = pendC.add(x.count, x.cost); val b = bv(x.vk, x.vendor); b.pendC = b.pendC.add(x.count, x.cost) }
            if (x.status != IGN) ourAll += x.cost
        }
        entries.forEach { x ->
            if (x.gid == null) { pendV = pendV.add(x.count, x.total); val b = bv(x.vk, x.vendor); b.pendV = b.pendV.add(x.count, x.total) }
            if (x.status != IGN) vendorAll += x.total
        }
        return RmSummary(
            groups = nGroups, missV = missV, missF = missF, ign = ign, pendC = pendC, pendV = pendV,
            ourAll = r2(ourAll), vendorAll = r2(vendorAll), m = metrics(mcs, mvs),
            byVendor = byV.values.map { b -> VendorSum(b.key, b.name, b.groups, metrics(b.cs, b.vs), b.missV, b.missF, b.pendC, b.pendV) }
                .sortedWith { a, b -> cmp(a.name, b.name) },
        )
    }

    // ------------------------------------------------------------------ the whole picture

    fun build(raw: RmRaw, f: RmFilter): RmState {
        val from = f.from; val to = f.to; val vf = norm(f.vendor)

        // one display name per vendor key
        val disp = LinkedHashMap<String, String>()
        raw.loads.forEach { if (it.vk.isNotEmpty() && it.vk !in disp) disp[it.vk] = it.vendor }
        raw.slips.forEach { if (it.vk.isNotEmpty() && it.vk !in disp) disp[it.vk] = it.vendor }
        val gKeys = raw.groups.map { g ->
            val vn = vendorName(g.vendor)
            if (vn.key.isNotEmpty() && vn.key !in disp) disp[vn.key] = vn.name
            vn.key
        }

        // who each row belongs to (the last group wins, like the script)
        val owner = HashMap<String, SavedGroup>()
        val cKeys = raw.loads.map { it.k }.toHashSet(); val vKeys = raw.slips.map { it.k }.toHashSet()
        raw.groups.forEach { g ->
            g.cks.filter { it in cKeys }.forEach { owner[it] = g }
            g.vks.filter { it in vKeys }.forEach { owner[it] = g }
        }
        val C = raw.loads.map { x -> owner[x.k].let { g -> x.copy(vendor = disp[x.vk] ?: "", status = g?.status, gid = g?.id) } }
        val V = raw.slips.map { x -> owner[x.k].let { g -> x.copy(vendor = disp[x.vk] ?: "", status = g?.status, gid = g?.id) } }
        val cMap = HashMap<String, OurLoad>().apply { C.forEach { put(it.k, it) } }
        val vMap = HashMap<String, VendorSlip>().apply { V.forEach { put(it.k, it) } }

        val G = raw.groups.mapIndexed { i, g ->
            val cs = g.cks.mapNotNull { cMap[it] }
            val vs = g.vks.mapNotNull { vMap[it] }
            val dks = (cs.map { it.dk } + vs.map { it.dk }).sorted()
            RmGroup(
                id = g.id, status = g.status, dk = dks.firstOrNull()?.ifEmpty { null } ?: g.dk0.ifEmpty { "" },
                vendor = disp[gKeys[i]] ?: g.vendor, note = g.note, created = g.created, by = g.by,
                cks = cs.map { it.k }, vks = vs.map { it.k },
                lost = (g.cks.size + g.vks.size) - (cs.size + vs.size), m = metrics(cs, vs),
                vKey = gKeys[i], cs = cs, vs = vs,
            )
        }

        fun inR(dk: String) = (from.isEmpty() || dk >= from) && (to.isEmpty() || dk <= to)
        fun vOk(k: String) = vf.isEmpty() || k == vf
        val groups = G.filter { inR(it.dk) && vOk(it.vKey) }
        val shown = groups.map { it.id }.toHashSet()
        val loads = C.filter { x -> if (x.gid != null) x.gid in shown else inR(x.dk) && vOk(x.vk) }
        val entries = V.filter { x -> if (x.gid != null) x.gid in shown else inR(x.dk) && (!raw.hasVendorCol || vOk(x.vk)) }

        val vendors = disp.keys.sortedWith { a, b -> cmp(disp[a]!!, disp[b]!!) }.map { k ->
            VendorOpt(k, disp[k]!!, C.count { it.gid == null && it.vk == k } + V.count { it.gid == null && it.vk == k })
        }
        return RmState(
            loads = loads, entries = entries, vendors = vendors,
            summary = summarize(groups, loads, entries),
            meta = RmMeta(raw.hasVendorCol, raw.companyTab, raw.vendorTab, false, from, to, vf,
                if (vf.isNotEmpty()) disp[vf] ?: f.vendor else ""),
            groups = groups,
        )
    }

    // ------------------------------------------------------------------ saving

    /** The check the script does before saving; null when it's fine. */
    fun checkSave(status: String, cks: List<String>, vks: List<String>): String? = when {
        status !in ST_LIST -> "Unknown action."
        cks.isEmpty() && vks.isEmpty() -> "Select at least one load."
        status == MATCHED && (cks.isEmpty() || vks.isEmpty()) -> "To match, pick at least one of our loads and one vendor entry."
        status == MISS_V && (vks.isNotEmpty() || cks.isEmpty()) -> "\"Missing at vendor\" is only for our loads."
        status == MISS_F && (cks.isNotEmpty() || vks.isEmpty()) -> "\"Missing at our end\" is only for vendor entries."
        else -> null
    }

    /** One row for the RM Matches tab (Group ID and Created are filled in by the caller). */
    data class NewRow(
        val status: String, val dk: String, val vendor: String, val loads: Int, val entries: Int,
        val driver: Double, val cft: Double, val vendorCount: Double, val ourCost: Double, val vendorCost: Double,
        val dV: Double, val cV: Double, val note: String, val cks: String, val vks: String,
    ) {
        fun cells(): List<Any> = listOf(status, dk, vendor, loads, entries, driver, cft, vendorCount, ourCost, vendorCost, dV, cV, note, cks, vks)
    }

    /**
     * The rows to add, worked out from fresh sheet data. Throws with the script's message if a
     * row has gone or was saved by someone else in the meantime.
     */
    fun rowsToSave(
        status: String, cks: List<String>, vks: List<String>, note: String, vendorName: String,
        loads: List<OurLoad>, slips: List<VendorSlip>, saved: List<SavedGroup>,
    ): List<NewRow> {
        checkSave(status, cks, vks)?.let { throw IllegalArgumentException(it) }
        val cMap = loads.associateBy { it.k }; val vMap = slips.associateBy { it.k }
        val used = HashMap<String, String>()
        saved.forEach { g -> (g.cks + g.vks).forEach { used[it] = g.id } }
        val cs = cks.map { cMap[it] }; val vs = vks.map { vMap[it] }
        if (cs.any { it == null } || vs.any { it == null }) throw IllegalStateException("Some rows changed in the sheet since you loaded. Tap Refresh and select again.")
        (cks + vks).firstOrNull { it in used }?.let { throw IllegalStateException("One of these is already saved (group ${used[it]}). Tap Refresh.") }

        fun mk(gc: List<OurLoad>, gv: List<VendorSlip>): NewRow {
            val m = metrics(gc, gv)
            val dk = (gc.map { it.dk } + gv.map { it.dk }).sorted().first()
            val cnt = LinkedHashMap<String, Int>()
            gc.forEach { if (it.vendor.isNotEmpty()) cnt[it.vendor] = (cnt[it.vendor] ?: 0) + 1 }
            val vendor = cnt.entries.sortedByDescending { it.value }.firstOrNull()?.key
                ?: gv.firstOrNull { it.vendor.isNotEmpty() }?.vendor ?: vendorName.trim()
            return NewRow(status, dk, vendor, gc.size, gv.size, m.driver, m.cft, m.vendor, m.ourCost, m.vendorCost,
                m.dV, m.cV, note.trim(), gc.joinToString(",") { it.k }, gv.joinToString(",") { it.k })
        }
        val c = cs.filterNotNull(); val v = vs.filterNotNull()
        return if (status == MATCHED) listOf(mk(c, v))
        else c.map { mk(listOf(it), emptyList()) } + v.map { mk(emptyList(), listOf(it)) }   // one row each, so each can be undone alone
    }

    fun newId(now: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("yyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone(TZ) }
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val rnd = (1..3).map { chars[(Math.random() * chars.length).toInt()] }.joinToString("")
        return "G" + f.format(Date(now)) + "-" + rnd
    }
}
