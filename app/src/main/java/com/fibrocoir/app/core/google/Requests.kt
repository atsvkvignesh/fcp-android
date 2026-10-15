package com.fibrocoir.app.core.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Small builders for Sheets batchUpdate requests (formats, tabs, charts, rows).
 * Rows and columns here are 0-based and end-exclusive, as the API wants.
 */
object Req {

    fun color(hex: String): JsonObject {
        val h = hex.removePrefix("#")
        return buildJsonObject {
            put("red", h.substring(0, 2).toInt(16) / 255.0)
            put("green", h.substring(2, 4).toInt(16) / 255.0)
            put("blue", h.substring(4, 6).toInt(16) / 255.0)
        }
    }

    fun range(sheetId: Int, r0: Int, r1: Int, c0: Int, c1: Int) = buildJsonObject {
        put("sheetId", sheetId)
        put("startRowIndex", r0); put("endRowIndex", r1)
        put("startColumnIndex", c0); put("endColumnIndex", c1)
    }

    /** One cell to write: text, number, formula or nothing (clears it). */
    sealed class Cell(val pattern: String? = null, val type: String? = null) {
        class Text(val v: String, pattern: String? = null, type: String? = null) : Cell(pattern, type)
        class Num(val v: Double, pattern: String? = null, type: String? = null) : Cell(pattern, type)
        class Formula(val v: String) : Cell()
        object Empty : Cell()
    }

    private fun JsonObjectBuilder.cellJson(c: Cell) {
        when (c) {
            is Cell.Text -> putJsonObject("userEnteredValue") { put("stringValue", c.v) }
            is Cell.Num -> putJsonObject("userEnteredValue") { put("numberValue", c.v) }
            is Cell.Formula -> putJsonObject("userEnteredValue") { put("formulaValue", c.v) }
            Cell.Empty -> {}
        }
        if (c.type != null) putJsonObject("userEnteredFormat") {
            putJsonObject("numberFormat") { put("type", c.type); if (c.pattern != null) put("pattern", c.pattern) }
        }
    }

    /** Writes a block of cells (values and number formats) starting at row/col. */
    fun updateCells(sheetId: Int, row: Int, col: Int, rows: List<List<Cell>>) = buildJsonObject {
        putJsonObject("updateCells") {
            putJsonObject("start") { put("sheetId", sheetId); put("rowIndex", row); put("columnIndex", col) }
            putJsonArray("rows") {
                rows.forEach { r -> add(buildJsonObject { putJsonArray("values") { r.forEach { c -> add(buildJsonObject { cellJson(c) }) } } }) }
            }
            put("fields", "userEnteredValue,userEnteredFormat.numberFormat")
        }
    }

    /** Bold / fill / text colour / italic / number format on a range. */
    fun format(
        range: JsonObject, bold: Boolean? = null, bg: String? = null, fg: String? = null,
        italic: Boolean? = null, numberType: String? = null, pattern: String? = null,
    ) = buildJsonObject {
        val fields = ArrayList<String>()
        putJsonObject("repeatCell") {
            put("range", range)
            putJsonObject("cell") {
                putJsonObject("userEnteredFormat") {
                    if (bg != null) { put("backgroundColor", color(bg)); fields.add("userEnteredFormat.backgroundColor") }
                    if (bold != null || fg != null || italic != null) putJsonObject("textFormat") {
                        if (bold != null) { put("bold", bold); fields.add("userEnteredFormat.textFormat.bold") }
                        if (italic != null) { put("italic", italic); fields.add("userEnteredFormat.textFormat.italic") }
                        if (fg != null) { put("foregroundColor", color(fg)); fields.add("userEnteredFormat.textFormat.foregroundColor") }
                    }
                    if (numberType != null) {
                        putJsonObject("numberFormat") { put("type", numberType); if (pattern != null) put("pattern", pattern) }
                        fields.add("userEnteredFormat.numberFormat")
                    }
                }
            }
            put("fields", fields.joinToString(","))
        }
    }

    fun border(range: JsonObject) = buildJsonObject {
        putJsonObject("updateBorders") {
            put("range", range)
            val solid = buildJsonObject { put("style", "SOLID") }
            put("top", solid); put("bottom", solid); put("left", solid); put("right", solid)
        }
    }

    fun addSheet(title: String, index: Int, rows: Int = 1000, cols: Int = 32, sheetId: Int? = null) = buildJsonObject {
        putJsonObject("addSheet") {
            putJsonObject("properties") {
                put("title", title); put("index", index)
                if (sheetId != null) put("sheetId", sheetId)
                putJsonObject("gridProperties") { put("rowCount", rows); put("columnCount", cols) }
            }
        }
    }

    fun freezeRows(sheetId: Int, n: Int) = buildJsonObject {
        putJsonObject("updateSheetProperties") {
            putJsonObject("properties") { put("sheetId", sheetId); putJsonObject("gridProperties") { put("frozenRowCount", n) } }
            put("fields", "gridProperties.frozenRowCount")
        }
    }

    private fun columns(sheetId: Int, c0: Int, c1: Int, props: JsonObject, fields: String) = buildJsonObject {
        putJsonObject("updateDimensionProperties") {
            putJsonObject("range") { put("sheetId", sheetId); put("dimension", "COLUMNS"); put("startIndex", c0); put("endIndex", c1) }
            put("properties", props); put("fields", fields)
        }
    }

    fun hideColumns(sheetId: Int, c0: Int, c1: Int) = columns(sheetId, c0, c1, buildJsonObject { put("hiddenByUser", true) }, "hiddenByUser")
    fun columnWidth(sheetId: Int, c: Int, px: Int) = columns(sheetId, c, c + 1, buildJsonObject { put("pixelSize", px) }, "pixelSize")

    fun autoResize(sheetId: Int, c0: Int, c1: Int) = buildJsonObject {
        putJsonObject("autoResizeDimensions") {
            putJsonObject("dimensions") { put("sheetId", sheetId); put("dimension", "COLUMNS"); put("startIndex", c0); put("endIndex", c1) }
        }
    }

    fun deleteRows(sheetId: Int, r0: Int, r1: Int) = buildJsonObject {
        putJsonObject("deleteDimension") {
            putJsonObject("range") { put("sheetId", sheetId); put("dimension", "ROWS"); put("startIndex", r0); put("endIndex", r1) }
        }
    }

    fun appendRows(sheetId: Int, n: Int) = buildJsonObject {
        putJsonObject("appendDimension") { put("sheetId", sheetId); put("dimension", "ROWS"); put("length", n) }
    }

    fun deleteChart(chartId: Int) = buildJsonObject { putJsonObject("deleteEmbeddedObject") { put("objectId", chartId) } }

    fun setFilter(range: JsonObject) = buildJsonObject { putJsonObject("setBasicFilter") { putJsonObject("filter") { put("range", range) } } }

    /** Red cell when the formula is true, e.g. =AND($D2="Belt",$F2>$AB$1). */
    fun redWhen(range: JsonObject, formula: String, index: Int) = buildJsonObject {
        putJsonObject("addConditionalFormatRule") {
            put("index", index)
            putJsonObject("rule") {
                putJsonArray("ranges") { add(range) }
                putJsonObject("booleanRule") {
                    putJsonObject("condition") {
                        put("type", "CUSTOM_FORMULA")
                        putJsonArray("values") { add(buildJsonObject { put("userEnteredValue", formula) }) }
                    }
                    putJsonObject("format") {
                        put("backgroundColor", color("#F4C7C3"))
                        putJsonObject("textFormat") { put("foregroundColor", color("#9C1C11")) }
                    }
                }
            }
        }
    }

    /** One line of a chart: a column of the tab, its colour, dashed or not. */
    class Line(val col: Int, val color: String, val dashed: Boolean = false)

    /** A line chart on the tab, domain column first, anchored at row/col. */
    fun lineChart(sheetId: Int, title: String, domainCol: Int, lines: List<Line>, anchorRow: Int, anchorCol: Int, rows: Int = 1000) = buildJsonObject {
        fun src(c: Int) = buildJsonObject {
            putJsonObject("sourceRange") { putJsonArray("sources") { add(range(sheetId, 0, rows, c, c + 1)) } }
        }
        putJsonObject("addChart") {
            putJsonObject("chart") {
                putJsonObject("spec") {
                    put("title", title)
                    putJsonObject("basicChart") {
                        put("chartType", "LINE")
                        put("legendPosition", "BOTTOM_LEGEND")
                        put("headerCount", 1)
                        put("interpolateNulls", true)
                        putJsonArray("axis") {
                            add(buildJsonObject { put("position", "BOTTOM_AXIS"); put("title", "Sample no") })
                            add(buildJsonObject {
                                put("position", "LEFT_AXIS"); put("title", "EC")
                                putJsonObject("viewWindowOptions") { put("viewWindowMin", 0); put("viewWindowMode", "EXPLICIT") }
                            })
                        }
                        putJsonArray("domains") { add(buildJsonObject { put("domain", src(domainCol)) }) }
                        putJsonArray("series") {
                            lines.forEach { l ->
                                add(buildJsonObject {
                                    put("series", src(l.col))
                                    put("targetAxis", "LEFT_AXIS")
                                    putJsonObject("colorStyle") { put("rgbColor", color(l.color)) }
                                    if (l.dashed) putJsonObject("lineStyle") { put("type", "MEDIUM_DASHED"); put("width", 2) }
                                    else putJsonObject("pointStyle") { put("shape", "CIRCLE"); put("size", 5) }
                                })
                            }
                        }
                    }
                }
                putJsonObject("position") {
                    putJsonObject("overlayPosition") {
                        putJsonObject("anchorCell") { put("sheetId", sheetId); put("rowIndex", anchorRow); put("columnIndex", anchorCol) }
                    }
                }
            }
        }
    }

    fun list(vararg r: JsonObject): List<JsonObject> = r.toList()
    val empty: JsonArray = buildJsonArray { }
}
