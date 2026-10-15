package com.fibrocoir.app.core.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.TimeZone

/** One row of cells as Google sends them: numbers, strings, booleans; short rows are padded by [cell]. */
typealias Row = List<JsonElement>

/** A tab of a spreadsheet. */
data class Tab(val id: Int, val title: String, val index: Int, val rows: Int, val cols: Int, val hidden: Boolean)

data class SheetInfo(val title: String, val timeZone: TimeZone, val tabs: List<Tab>, val locale: String = "en_US") {
    fun tab(title: String): Tab? = tabs.firstOrNull { it.title == title }
}

/**
 * The Google Sheets API (v4), straight over HTTPS. Reads ask for raw values (numbers stay
 * numbers, dates come as day numbers — see [Cells]); writes are RAW unless said otherwise.
 */
class Sheets(private val auth: GoogleAuth, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://sheets.googleapis.com/v4/spreadsheets"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------ reading

    /** Title, time zone and tabs of a spreadsheet. */
    suspend fun info(id: String): SheetInfo {
        val url = "$base/$id".toHttpUrl().newBuilder()
            .addQueryParameter(
                "fields",
                "properties(title,timeZone,locale),sheets(properties(sheetId,title,index,hidden,gridProperties(rowCount,columnCount)))"
            ).build().toString()
        val o = call("GET", url, null).jsonObject
        val props = o["properties"]?.jsonObject
        val tabs = o["sheets"]?.jsonArray.orEmpty().map { s ->
            val p = s.jsonObject.getValue("properties").jsonObject
            val g = p["gridProperties"]?.jsonObject
            Tab(
                id = p["sheetId"]?.jsonPrimitive?.intOrNull ?: 0,
                title = p["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                index = p["index"]?.jsonPrimitive?.intOrNull ?: 0,
                rows = g?.get("rowCount")?.jsonPrimitive?.intOrNull ?: 0,
                cols = g?.get("columnCount")?.jsonPrimitive?.intOrNull ?: 0,
                hidden = p["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.sortedBy { it.index }
        return SheetInfo(
            title = props?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty(),
            timeZone = TimeZone.getTimeZone(props?.get("timeZone")?.jsonPrimitive?.contentOrNull ?: "Asia/Kolkata"),
            tabs = tabs,
            locale = props?.get("locale")?.jsonPrimitive?.contentOrNull ?: "en_US",
        )
    }

    /** The ids of the charts on one tab. */
    suspend fun chartIds(id: String, sheetId: Int): List<Int> {
        val url = "$base/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "sheets(properties(sheetId),charts(chartId))").build().toString()
        val sheet = call("GET", url, null).jsonObject["sheets"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["properties"]?.jsonObject?.get("sheetId")?.jsonPrimitive?.intOrNull == sheetId }
        return sheet?.get("charts")?.jsonArray.orEmpty().mapNotNull { it.jsonObject["chartId"]?.jsonPrimitive?.intOrNull }
    }

    /** Several ranges in one request. Result has one list of rows per range, in order. */
    suspend fun read(id: String, ranges: List<String>, formatted: Boolean = false): List<List<Row>> {
        if (ranges.isEmpty()) return emptyList()
        val out = ArrayList<List<Row>>(ranges.size)
        // Long URLs are refused, so very many ranges go in a few requests
        for (chunk in ranges.chunked(60)) {
            val b = "$base/$id/values:batchGet".toHttpUrl().newBuilder()
                .addQueryParameter("valueRenderOption", if (formatted) "FORMATTED_VALUE" else "UNFORMATTED_VALUE")
                .addQueryParameter("dateTimeRenderOption", "SERIAL_NUMBER")
                .addQueryParameter("majorDimension", "ROWS")
            chunk.forEach { b.addQueryParameter("ranges", it) }
            val o = call("GET", b.build().toString(), null).jsonObject
            o["valueRanges"]?.jsonArray.orEmpty().forEach { vr ->
                out.add(vr.jsonObject["values"]?.jsonArray.orEmpty().map { it.jsonArray.toList() })
            }
        }
        return out
    }

    suspend fun read(id: String, range: String, formatted: Boolean = false): List<Row> =
        read(id, listOf(range), formatted).firstOrNull().orEmpty()

    // ------------------------------------------------------------------ writing

    /** Write a block of values. userEntered = true lets Sheets parse formulas and typed text. */
    suspend fun write(id: String, range: String, values: List<List<Any?>>, userEntered: Boolean = false) {
        writeMany(id, listOf(range to values), userEntered)
    }

    suspend fun writeMany(id: String, data: List<Pair<String, List<List<Any?>>>>, userEntered: Boolean = false) {
        if (data.isEmpty()) return
        val body = buildJsonObject {
            put("valueInputOption", if (userEntered) "USER_ENTERED" else "RAW")
            put("data", buildJsonArray {
                data.forEach { (range, values) ->
                    add(buildJsonObject {
                        put("range", range)
                        put("majorDimension", "ROWS")
                        put("values", toJson(values))
                    })
                }
            })
        }
        call("POST", "$base/$id/values:batchUpdate", body)
    }

    /** Add rows after the last row of the table that starts in `range`. Returns the A1 range written. */
    suspend fun append(id: String, range: String, values: List<List<Any?>>, userEntered: Boolean = false, insertRows: Boolean = true): String {
        val url = "$base/$id/values/${enc(range)}:append".toHttpUrl().newBuilder()
            .addQueryParameter("valueInputOption", if (userEntered) "USER_ENTERED" else "RAW")
            .addQueryParameter("insertDataOption", if (insertRows) "INSERT_ROWS" else "OVERWRITE")
            .build().toString()
        val o = call("POST", url, buildJsonObject { put("values", toJson(values)) }).jsonObject
        return o["updates"]?.jsonObject?.get("updatedRange")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun clear(id: String, range: String) {
        call("POST", "$base/$id/values/${enc(range)}:clear", JsonObject(emptyMap()))
    }

    /** Structural changes (add tab, formats, charts, delete rows...). Returns the replies. */
    suspend fun batchUpdate(id: String, requests: List<JsonObject>): JsonArray {
        if (requests.isEmpty()) return JsonArray(emptyList())
        val body = buildJsonObject { put("requests", JsonArray(requests)) }
        return call("POST", "$base/$id:batchUpdate", body).jsonObject["replies"]?.jsonArray ?: JsonArray(emptyList())
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun call(method: String, url: String, body: JsonElement?): JsonElement = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val token = auth.accessToken()
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .method(method, body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) })
                .build()
            val (code, text) = try {
                http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw OfflineException()
            }
            if (code in 200..299) {
                return@withContext if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
            }
            if (code == 401 && attempt == 0) { auth.invalidate(); attempt++; continue }
            if ((code == 429 || code >= 500) && attempt < 3) {
                attempt++
                delay(600L * attempt * attempt)       // Google asks us to slow down; try again shortly
                continue
            }
            throw SheetsException(explain(code, text), code)
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    private fun explain(code: Int, text: String): String {
        val msg = try {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) { null } ?: text.take(200)
        return when (code) {
            403 -> "The app can't open this sheet. Share it with ${auth.accountEmail ?: "the app's account"} as Editor."
            404 -> "Sheet not found. $msg"
            else -> "Google Sheets error $code: $msg"
        }
    }

    private fun enc(range: String) = java.net.URLEncoder.encode(range, "UTF-8").replace("+", "%20")

    private fun toJson(values: List<List<Any?>>): JsonArray = buildJsonArray {
        values.forEach { row ->
            add(buildJsonArray {
                row.forEach { v ->
                    add(
                        when (v) {
                            null -> JsonPrimitive("")
                            is Number -> JsonPrimitive(v)
                            is Boolean -> JsonPrimitive(v)
                            is JsonElement -> v
                            else -> JsonPrimitive(v.toString())
                        }
                    )
                }
            })
        }
    }

    companion object {
        /** 'Tab name'!A1:B2 with the quoting Sheets needs for spaces and quotes. */
        fun a1(tab: String, cells: String = ""): String {
            val q = "'" + tab.replace("'", "''") + "'"
            return if (cells.isEmpty()) q else "$q!$cells"
        }

        /** Column number (1 = A) to letters. */
        fun col(n: Int): String {
            var x = n
            val sb = StringBuilder()
            while (x > 0) { val r = (x - 1) % 26; sb.insert(0, ('A' + r)); x = (x - 1) / 26 }
            return sb.toString()
        }

        /** "Sheet1!A5:G5" -> 5, the first row number of an A1 range. */
        fun firstRow(a1: String): Int =
            Regex("![A-Z]+(\\d+)").find(a1)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

/** Reading single cells out of a [Row]. */
object Cells {
    fun Row.cell(i: Int): JsonElement = if (i in indices) this[i] else JsonPrimitive("")

    fun JsonElement?.text(): String {
        val p = this as? JsonPrimitive ?: return ""
        if (p.isString) return p.content
        val d = p.doubleOrNull
        if (d != null) {
            // 12.0 -> "12", keep real decimals
            return if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        return p.contentOrNull ?: ""
    }

    fun JsonElement?.number(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (!p.isString) return p.doubleOrNull
        val s = p.content.trim().replace(",", "")
        return if (s.isEmpty()) null else s.toDoubleOrNull()
    }

    fun JsonElement?.isBlank(): Boolean = text().isBlank()

    /** A date/time cell (Sheets day number, in the sheet's time zone) to epoch millis, or null. */
    fun JsonElement?.millis(tz: TimeZone): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        val serial = p.doubleOrNull ?: return null
        return serialToMillis(serial, tz)
    }

    fun serialToMillis(serial: Double, tz: TimeZone): Long {
        val local = Math.round((serial - 25569.0) * 86_400_000.0)      // as if the sheet's clock were UTC
        return local - tz.getOffset(local - tz.rawOffset)
    }

    fun millisToSerial(millis: Long, tz: TimeZone): Double =
        (millis + tz.getOffset(millis)) / 86_400_000.0 + 25569.0

    /** A row as plain values: numbers (dates are day numbers) as Double, booleans, the rest as text. */
    fun plain(r: Row): List<Any?> = r.map { c ->
        val p = c as? JsonPrimitive
        when {
            p == null || p is kotlinx.serialization.json.JsonNull -> null
            p.isString -> p.content
            p.booleanOrNull != null -> p.booleanOrNull
            else -> p.doubleOrNull ?: p.content
        }
    }

    val JsonElement.int: Int? get() = (this as? JsonPrimitive)?.let { if (it.isString) it.content.toIntOrNull() else it.intOrNull }
}
