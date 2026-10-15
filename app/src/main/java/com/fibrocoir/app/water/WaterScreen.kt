package com.fibrocoir.app.water

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fibrocoir.app.ui.BarChart
import com.fibrocoir.app.ui.Card
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Flow
import com.fibrocoir.app.ui.LoadBox
import com.fibrocoir.app.ui.LocalSay
import com.fibrocoir.app.ui.ScreenPad
import com.fibrocoir.app.ui.SectionTitle
import com.fibrocoir.app.ui.Tag
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private const val HOUR = 3_600_000L

@Composable
fun WaterScreen(onBack: () -> Unit, vm: WaterViewModel = viewModel()) {
    val load by vm.data.state.collectAsState()
    val picked by vm.date.collectAsState()
    var picking by remember { mutableStateOf(false) }

    // a fresh look every 5 minutes while the screen is open (only the new taps are read)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (System.currentTimeMillis() >= vm.nextLoad) vm.refresh()
                delay(5_000)
            }
        }
    }
    // states like "parked" depend on the clock, so look again every few seconds
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(10_000); value = System.currentTimeMillis() } }

    FcpScreen(
        title = "Water record",
        subtitle = if (load.at > 0) "Updated " + Water.agoText(now - load.at) else "Tanker loads from the card reader",
        onBack = onBack,
        load = load,
        onRefresh = { vm.refresh(full = true) },
    ) {
        val say = LocalSay.current
        LaunchedEffect(Unit) { vm.news.collect { say(it) } }

        LoadBox(load, onRetry = { vm.refresh(full = true) }) { d ->
            val v = remember(d) { WaterView(d) }
            val today = v.today(now)
            val day = picked?.takeIf { it < today } ?: today
            val trips = remember(v, day) { v.day(day) }
            val sum = remember(trips) { Water.summary(trips) }
            val week = remember(v, today) { v.loadsPerDay(today, 14) }

            LazyColumn(contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SectionTitle("Where is the tanker?") }
                if (v.roster.isEmpty()) item { Empty("🫥", "No tankers registered yet.") }
                items(v.roster, key = { "t-" + it.uid }) { TankerCard(it, v.tz, now, today) }

                item { SectionTitle("Loads") }
                item {
                    DayCard(
                        day = day, today = today, sum = sum,
                        onMove = { n -> vm.pick(Water.shiftDay(day, n), today) },
                        onToday = { vm.pick(null, today) },
                        onPick = { picking = true },
                    )
                }
                item {
                    Card {
                        Text("Last 14 days", style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                        Text("Tap a day to see its trips", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
                        Spacer(Modifier.height(10.dp))
                        BarChart(
                            labels = week.map { it.first.takeLast(2) },
                            values = week.map { it.second.toDouble() },
                            color = Fcp.info,
                            height = 150.dp,
                            selected = week.indexOfFirst { it.first == day }.takeIf { it >= 0 },
                            onTap = { i -> vm.pick(week[i].first, today) },
                        )
                    }
                }

                item {
                    SectionTitle("Trip by trip") {
                        Text(
                            if (sum.tests > 0) "${sum.tests} test ${if (sum.tests == 1) "tap" else "taps"} not counted" else "fill & comeback time",
                            style = MaterialTheme.typography.labelMedium, color = Fcp.muted,
                        )
                    }
                }
                val inside = v.roster.filter { t ->
                    val e = t.last
                    e != null && e.ev == "IN" && now - e.t < Water.FILLING_FOR_MS && Water.dayKey(e.t, v.tz) == day
                }
                items(inside, key = { "in-" + it.uid }) { FillingRow(it, v.tz) }
                val rows = trips.asReversed()
                if (rows.isEmpty() && inside.isEmpty()) item { Empty("🦗", "No loads on this day.") }
                var n = sum.loads
                val numbered = rows.map { t -> t to (if (t.test) 0 else n--) }
                items(numbered, key = { "trip-" + it.first.uid + it.first.tin }) { (t, num) -> TripRow(t, num, v.tz) }
            }

            if (picking) DayPickerDialog(day, today, onClose = { picking = false }) { vm.pick(it, today); picking = false }
        }
    }
}

// ---------------------------------------------------------------------- where is the tanker

@Composable
private fun TankerCard(t: TankerNow, tz: TimeZone, now: Long, today: String) {
    val e = t.last
    val lt = t.lastTrip
    val filling = e != null && e.ev == "IN" && now - e.t < Water.FILLING_FOR_MS
    val parked = e != null && e.ev == "OUT" && now - e.t > Water.NEW_DAY_GAP_H * HOUR
    val road = e != null && e.ev == "OUT" && !parked
    Card(accent = when { filling -> Fcp.info; road -> Fcp.good.copy(alpha = 0.6f); else -> null }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, color = Fcp.cream)
                Text("${t.place.ifEmpty { "—" }} · card ${t.uid}", style = MaterialTheme.typography.labelMedium, color = Fcp.faint)
            }
            if (filling) Tank(since = e!!.t)
            else Text(
                when { e == null -> "😶"; e.ev == "IN" -> "🤔"; parked -> "💤"; else -> "🚚" },
                fontSize = 34.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            e == null -> {
                Tag("NO TAPS YET", Fcp.muted)
                Quip("Shy tanker. Never seen at the gate.")
            }
            filling -> {
                Tag("IN — FILLING", Fcp.info)
                Ticking(e!!.t) { ms ->
                    Text(Water.clock(ms), style = MaterialTheme.typography.displaySmall, color = Fcp.info)
                    Meta("Came in at ${Water.time(e.t, tz)}" +
                        (if (lt != null && lt.tout <= e.t && (e.t - lt.tout) <= Water.NEW_DAY_GAP_H * HOUR) " · came back in ${Water.dur((e.t - lt.tout) / 60_000.0)}" else ""))
                    Quip(Water.insideQuip(ms))
                }
            }
            e.ev == "IN" -> {
                Tag("IN SINCE " + Water.agoText(now - e.t).removeSuffix(" ago").uppercase(Locale.US), Fcp.bad)
                Quip("Tapped IN at ${Water.time(e.t, tz)} (${Water.niceDate(Water.dayKey(e.t, tz), today)}) and never tapped OUT. Forgot the card?")
            }
            parked -> {
                Tag("OUT — PARKED", Fcp.muted)
                Quip("Last left ${Water.time(e.t, tz)}, ${Water.niceDate(Water.dayKey(e.t, tz), today)}. Napping till the next shift.")
            }
            else -> {
                Tag("OUT — ON THE ROAD", Fcp.good)
                Ticking(e.t) { ms ->
                    Text(Water.clock(ms), style = MaterialTheme.typography.displaySmall, color = Fcp.good)
                    Meta("Left at ${Water.time(e.t, tz)}" + when {
                        lt != null && lt.tout == e.t && lt.parked -> " · was parked inside ${Water.dur(lt.mins)}"
                        lt != null && lt.tout == e.t -> " · last fill took ${Water.dur(lt.mins)}"
                        else -> ""
                    })
                    Quip(Water.roadQuip(ms))
                }
            }
        }
    }
}

/** Re-draws its content every second with the time since `since`, without touching the rest of the screen. */
@Composable
private fun Ticking(since: Long, content: @Composable (Long) -> Unit) {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Column { content(now - since) }
}

/** A little tank that fills up while the tanker is inside (full at 15 minutes). */
@Composable
private fun Tank(since: Long) {
    val wave by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val now by produceState(System.currentTimeMillis(), since) { while (true) { value = System.currentTimeMillis(); delay(5000) } }
    val level = (0.12f + 0.88f * min(1f, (now - since) / (15 * 60_000f)))
    Canvas(Modifier.size(width = 46.dp, height = 54.dp)) {
        val r = CornerRadius(10.dp.toPx())
        val body = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 6.dp.toPx(), size.width, size.height, r))
        }
        clipPath(body) {
            val top = size.height - (size.height - 6.dp.toPx()) * level
            val p = Path().apply {
                moveTo(0f, size.height)
                var x = 0f
                while (x <= size.width) {
                    lineTo(x, top + sin((x / size.width + wave) * 2 * PI).toFloat() * 3.dp.toPx())
                    x += 2f
                }
                lineTo(size.width, size.height); close()
            }
            drawPath(p, Fcp.info)
        }
        drawRoundRect(Fcp.cream.copy(alpha = 0.6f), Offset(0f, 6.dp.toPx()), Size(size.width, size.height - 6.dp.toPx()), r, style = Stroke(2.dp.toPx()))
        drawRect(Fcp.info.copy(alpha = 0.8f), Offset(size.width / 2 - 3.dp.toPx(), 0f), Size(6.dp.toPx(), 10.dp.toPx()))
    }
}

// ---------------------------------------------------------------------- loads

@Composable
private fun DayCard(day: String, today: String, sum: DaySummary, onMove: (Int) -> Unit, onToday: () -> Unit, onPick: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Day before", tint = Fcp.fibre) }
            Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(onClick = onPick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Water.niceDate(day, today), style = MaterialTheme.typography.titleMedium, color = Fcp.cream)
                Text(dmy(day) + " · tap to pick a day", style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
            }
            IconButton(onClick = { onMove(1) }, enabled = day < today) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", tint = if (day < today) Fcp.fibre else Fcp.husk4)
            }
        }
        if (day != today) {
            TextButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to today", color = Fcp.fibre) }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Figure(sum.loads.toString(), if (sum.loads == 1) "load" else "loads", Fcp.fibre, Modifier.weight(1f), big = true)
            Figure(sum.avgFill?.let { Water.dur(it) } ?: "—", "avg fill time", Fcp.info, Modifier.weight(1f))
            Figure(sum.avgBack?.let { Water.dur(it) } ?: "—", "avg comeback", Fcp.good, Modifier.weight(1f))
        }
        if (sum.fastest != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "⚡ Fastest fill ${Water.dur(sum.fastest)} · 🐌 slowest ${Water.dur(sum.slowest ?: 0.0)}" +
                    (sum.quickestBack?.let { " · 🏁 quickest comeback ${Water.dur(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Fcp.muted,
            )
        }
    }
}

@Composable
private fun Figure(value: String, label: String, color: Color, modifier: Modifier, big: Boolean = false) {
    Column(modifier) {
        Text(value, style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
    }
}

@Composable
private fun FillingRow(t: TankerNow, tz: TimeZone) {
    val e = t.last ?: return
    Card(padding = 12.dp, accent = Fcp.info) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge("💦", Fcp.info)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${t.name} · in ${Water.time(e.t, tz)} → filling now…", style = MaterialTheme.typography.bodyMedium, color = Fcp.cream)
                Ticking(e.t) { ms -> Text("💦 filling ${Water.clock(ms)}", style = MaterialTheme.typography.labelMedium, color = Fcp.info) }
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, num: Int, tz: TimeZone) {
    if (t.test) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("👻", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text("${Water.time(t.tin, tz)} → ${Water.time(t.tout, tz)} · ${Water.dur(t.mins)} · quick test tap, not counted",
                style = MaterialTheme.typography.labelMedium, color = Fcp.faint)
        }
        return
    }
    Card(padding = 12.dp) {
        Row {
            Badge(num.toString(), Fcp.fibre)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${t.name} · ${Water.time(t.tin, tz)} → ${Water.time(t.tout, tz)}", style = MaterialTheme.typography.bodyMedium, color = Fcp.cream)
                Spacer(Modifier.height(6.dp))
                Flow(gap = 6.dp) {
                    if (t.parked) Tag("🅿️ parked inside ${Water.dur(t.mins)}", Fcp.muted) else Tag("💦 filled in ${Water.dur(t.mins)}", Fcp.info)
                    if (t.back != null) Tag("🛣️ came back in ${Water.dur(t.back)}", Fcp.good) else Tag("🌅 first trip", Fcp.fibre)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (t.parked) "💤 Filled and parked, left at ${Water.time(t.tout, tz)}. Not in the fill-time average." else Water.fillQuip(t.mins, t.tin),
                    style = MaterialTheme.typography.bodySmall, color = Fcp.muted,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Meta(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = Fcp.muted, modifier = Modifier.padding(top = 2.dp))

@Composable
private fun Quip(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Fcp.cream.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Empty(icon: String, text: String) {
    Card {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 32.sp)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Fcp.muted)
        }
    }
}

private fun dmy(d: String): String { val p = d.split("-"); return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else d }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(day: String, today: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    val f = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val max = remember(today) { f.parse(today)?.time ?: Long.MAX_VALUE }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = f.parse(day)?.time,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= max
        },
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(f.format(Date(it))) } }) { Text("Show", color = Fcp.fibre) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    ) { DatePicker(state, showModeToggle = false) }
}
