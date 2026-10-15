package com.fibrocoir.app.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * JavaScript's rules for turning sheet values into text and numbers. The ported Apps Script
 * logic leans on them (String(v), Number(v), "a || b"), so the app behaves the same.
 */
object Js {
    /** String(v): 12.0 -> "12", 2.5 -> "2.5", null -> "". */
    fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> num(v)
        is Float -> num(v.toDouble())
        is Int, is Long -> v.toString()
        is Boolean -> v.toString()
        else -> v.toString()
    }

    fun num(d: Double): String = when {
        d.isNaN() -> "NaN"
        d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
        d == floor(d) && abs(d) < 1e21 -> d.roundToLong().toString()
        else -> d.toString()
    }

    /** norm(): String(v).trim().toLowerCase() */
    fun norm(v: Any?): String = str(v).trim().lowercase()

    /** Number(v): "" -> 0, " 12 " -> 12, "12a" -> NaN, true -> 1. */
    fun number(v: Any?): Double = when (v) {
        null -> 0.0
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> {
            val s = v.toString().trim()
            when {
                s.isEmpty() -> 0.0
                s == "Infinity" || s == "+Infinity" -> Double.POSITIVE_INFINITY
                s == "-Infinity" -> Double.NEGATIVE_INFINITY
                Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s) -> s.toDouble()
                Regex("^0[xX][0-9a-fA-F]+$").matches(s) -> s.substring(2).toLong(16).toDouble()
                else -> Double.NaN
            }
        }
    }

    /** JavaScript truthiness of a sheet value. */
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Double -> v != 0.0 && !v.isNaN()
        is Boolean -> v
        is String -> v.isNotEmpty()
        else -> true
    }

    /** "a || b" for display values. */
    fun or(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    /** Text that looks like a number becomes one, as Sheets would read it when typed. */
    fun cellValue(v: Any?): Any? = when (v) {
        is String -> if (Regex("^-?(\\d+\\.?\\d*|\\.\\d+)$").matches(v.trim()) && !Regex("^-?0\\d").containsMatchIn(v.trim())) v.trim().toDouble() else v
        else -> v
    }
}
