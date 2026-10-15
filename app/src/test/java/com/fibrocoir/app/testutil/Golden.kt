package com.fibrocoir.app.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.fail
import kotlin.math.abs

/** Helpers for tests that compare the Kotlin port with what the Apps Script code produced. */
object Golden {
    fun load(name: String): JsonObject =
        Json.parseToJsonElement(Golden::class.java.classLoader!!.getResource("golden/$name")!!.readText()) as JsonObject

    /** Sheet rows as the app sees them: numbers are Double, text is String. */
    fun rows(e: JsonElement?): List<List<Any?>> = (e as? JsonArray).orEmpty().map { r ->
        (r as JsonArray).map { c ->
            val p = c as? JsonPrimitive
            when {
                p == null || p is JsonNull -> null
                p.isString -> p.content
                p.booleanOrNull != null -> p.booleanOrNull
                else -> p.doubleOrNull
            }
        }
    }

    /**
     * Every field the script returned must be in ours with the same value (numbers to 1e-6).
     * Extra fields on our side are fine; a missing field counts as null.
     */
    fun same(want: JsonElement?, got: JsonElement?, path: String = "$") {
        val w = want ?: JsonNull; val g = got ?: JsonNull
        when (w) {
            is JsonNull -> if (g !is JsonNull) fail("$path: want null, got $g")
            is JsonObject -> {
                if (g !is JsonObject) fail("$path: want an object, got $g")
                w.forEach { (k, v) -> same(v, (g as JsonObject)[k], "$path.$k") }
            }
            is JsonArray -> {
                if (g !is JsonArray) fail("$path: want a list, got $g")
                g as JsonArray
                if (w.size != g.size) fail("$path: want ${w.size} items, got ${g.size}")
                w.forEachIndexed { i, v -> same(v, g[i], "$path[$i]") }
            }
            is JsonPrimitive -> {
                if (g !is JsonPrimitive || g is JsonNull) fail("$path: want $w, got $g")
                g as JsonPrimitive
                val wn = if (w.isString) null else w.doubleOrNull
                val gn = if (g.isString) null else g.doubleOrNull
                if (wn != null && gn != null) { if (abs(wn - gn) > 1e-6) fail("$path: want $wn, got $gn") }
                else if (w.content != g.content) fail("$path: want \"${w.content}\", got \"${g.content}\"")
            }
        }
    }
}
