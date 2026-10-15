package com.fibrocoir.app.husk

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fibrocoir.app.ecviewer.DateRangeDialog
import com.fibrocoir.app.ecviewer.Dropdown
import com.fibrocoir.app.hub.fcpField
import com.fibrocoir.app.ui.Card
import com.fibrocoir.app.ui.ChoiceRow
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Flow
import com.fibrocoir.app.ui.GhostButton
import com.fibrocoir.app.ui.LoadBox
import com.fibrocoir.app.ui.LocalSay
import com.fibrocoir.app.ui.PrimaryButton
import com.fibrocoir.app.ui.ScreenPad
import com.fibrocoir.app.ui.SectionTitle
import com.fibrocoir.app.ui.Segments
import com.fibrocoir.app.ui.Tag
import com.fibrocoir.app.ui.count
import com.fibrocoir.app.ui.inr
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// ---------------------------------------------------------------------- number words (same as the web page)

private fun n0(v: Double?) = count(v ?: 0.0)
private fun rs(v: Double?) = inr(v ?: 0.0)
private fun sgn(v: Double): String { val r = v.roundToLong(); return (if (r > 0) "+" else if (r < 0) "−" else "") + count(abs(r).toDouble()) }
private fun rsg(v: Double): String { val r = v.roundToLong(); return (if (r > 0) "+" else if (r < 0) "−" else "") + inr(abs(r).toDouble()) }
private fun tone(v: Double): Color { val r = v.roundToLong(); return if (r < 0) Fcp.bad else if (r > 0) Fcp.good else Fcp.cream }
private fun pct(v: Double, base: Double) = if (base != 0.0) String.format(Locale.US, "%.1f%%", abs(v) / base * 100) else ""
private fun plural(n: Int, one: String, many: String) = HuskViewModel.plural(n, one, many)

private val UTC = TimeZone.getTimeZone("UTC")
private fun day(dk: String, pattern: String): String {
    if (dk.isEmpty()) return "—"
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = UTC }.parse(dk) ?: return dk
    return SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(d)
}
private fun dfmt(dk: String) = day(dk, "EEE, d MMM")
private fun dshort(dk: String) = day(dk, "d MMM")

private fun statusColor(s: String?) = when (s) {
    Rm.MATCHED -> Fcp.good
    Rm.MISS_V, Rm.MISS_F -> Fcp.bad
    else -> Fcp.muted
}

@Composable
fun HuskScreen(onBack: () -> Unit, vm: HuskViewModel = viewModel()) {
    val load by vm.raw.state.collectAsState()
    val st by vm.state.collectAsState()
    val u by vm.ui.collectAsState()
    val busy by vm.busy.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun open(url: String) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { } }

    // only rows that are still to check can stay picked
    val mapC = remember(st) { st?.loads?.associateBy { it.k }.orEmpty() }
    val mapV = remember(st) { st?.entries?.associateBy { it.k }.orEmpty() }
    val selC = remember(u.selC, mapC) { u.selC.filter { mapC[it]?.gid == null && it in mapC }.toSet() }
    val selV = remember(u.selV, mapV) { u.selV.filter { mapV[it]?.gid == null && it in mapV }.toSet() }

    val f = u.filter
    val range = if (f.from.isEmpty() && f.to.isEmpty()) "All dates" else "${if (f.from.isNotEmpty()) dfmt(f.from) else "Start"} to ${if (f.to.isNotEmpty()) dfmt(f.to) else "latest"}"

    FcpScreen(
        title = "Raw material check",
        subtitle = (st?.meta?.vendor?.ifEmpty { null } ?: "All vendors") + " · " + range,
        onBack = onBack,
        load = load,
        onRefresh = vm::refresh,
        bottomBar = {
            AnimatedVisibility(u.tab == RmTab.MATCH && (selC.isNotEmpty() || selV.isNotEmpty()), enter = expandVertically(), exit = shrinkVertically()) {
                val say = LocalSay.current
                Scale(selC.mapNotNull { mapC[it] }, selV.mapNotNull { mapV[it] }, busy,
                    onClear = vm::clearSel,
                    onAct = { status, note ->
                        vm.act(status, note) { msg, ids, failed ->
                            if (failed || ids.isEmpty()) say(msg)
                            else say.action(msg, "Undo") { vm.undo(ids) { say(it) } }
                        }
                    })
            }
        },
    ) {
        val say = LocalSay.current
        LoadBox(load, onRetry = vm::refresh) {
            val s = st
            if (s == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Fcp.fibre) }
                return@LoadBox
            }
            val pc = s.loads.count { it.gid == null }; val pv = s.entries.count { it.gid == null }
            LazyColumn(contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dropdown(
                            "Vendor", f.vendor,
                            listOf("" to "All vendors") + s.vendors.map { it.key to (it.name + if (it.pending > 0) " (${it.pending} to check)" else "") },
                            Modifier.fillMaxWidth(),
                        ) { vm.vendor(it) }
                        ChoiceRow(
                            listOf("7" to "7 days", "30" to "30 days", "all" to "All", "pick" to if (u.preset == "pick") "${dshort(f.from)} – ${dshort(f.to)}" else "Pick dates"),
                            u.preset,
                            { p -> if (p == "pick") picking = true else vm.preset(p) },
                        )
                        Segments(
                            listOf(RmTab.MATCH to ("Match" + if (pc + pv > 0) " · ${pc + pv}" else ""), RmTab.SUMMARY to "Summary", RmTab.HISTORY to "History"),
                            u.tab, { t -> vm.set { it.copy(tab = t) } },
                        )
                        if (!s.meta.hasVendorCol) {
                            Text("The vendor form has no \"Vendor Name\" question yet, so every vendor entry shows under every vendor.",
                                style = MaterialTheme.typography.bodySmall, color = Fcp.bad)
                        }
                    }
                }
                when (u.tab) {
                    RmTab.MATCH -> matchTab(s, u, selC, selV, pc, pv, vm)
                    RmTab.SUMMARY -> summaryTab(s, range, busy, mapC, mapV) {
                        vm.export { url, msg -> if (url != null) say.action(msg, "Open sheet") { open(url) } else say(msg) }
                    }
                    RmTab.HISTORY -> historyTab(s, u, mapC, mapV, busy, vm) { id -> vm.undo(listOf(id)) { say(it) } }
                }
            }
        }
    }
    if (picking) {
        DateRangeDialog(f.from.ifEmpty { null }, f.to.ifEmpty { null }, onClose = { picking = false }) { a, b -> vm.dates(a, b); picking = false }
    }
}

// ---------------------------------------------------------------------- Match

private sealed class Line {
    data class Day(val dk: String, val n: Int) : Line()
    data class Ours(val x: OurLoad) : Line()
    data class Theirs(val x: VendorSlip) : Line()
}

private fun LazyListScope.matchTab(s: RmState, u: RmUi, selC: Set<String>, selV: Set<String>, pc: Int, pv: Int, vm: HuskViewModel) {
    item(key = "match-bar") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segments(listOf(true to "To check", false to "All"), u.pendingOnly, { v -> vm.set { it.copy(pendingOnly = v) } })
            fcpField(u.q, { q -> vm.set { it.copy(q = q) } }, "Search driver, truck, place or note")
            Segments(listOf(true to "Our loads ($pc)", false to "Vendor ($pv)"), u.ours, { v -> vm.set { it.copy(ours = v) } })
        }
    }
    val q = u.q.trim().lowercase()
    val lines = ArrayList<Line>()
    if (u.ours) {
        val list = s.loads.filter { x ->
            (!u.pendingOnly || x.gid == null) &&
                (q.isEmpty() || listOf(x.driver, x.truck, x.place, x.note, x.unload, x.vendor).joinToString(" ").lowercase().contains(q))
        }.sortedWith(compareBy({ it.dk }, { it.tm }))
        val per = list.groupingBy { it.dk }.eachCount()
        var last = ""
        list.forEach { x -> if (x.dk != last) { lines += Line.Day(x.dk, per[x.dk] ?: 0); last = x.dk }; lines += Line.Ours(x) }
    } else {
        val list = s.entries.filter { x ->
            (!u.pendingOnly || x.gid == null) &&
                (q.isEmpty() || listOf(x.place, x.type, x.note, x.truck, x.vendor).joinToString(" ").lowercase().contains(q))
        }.sortedWith(compareBy({ it.dk }, { it.tm }))
        val per = list.groupingBy { it.dk }.eachCount()
        var last = ""
        list.forEach { x -> if (x.dk != last) { lines += Line.Day(x.dk, per[x.dk] ?: 0); last = x.dk }; lines += Line.Theirs(x) }
    }
    if (lines.isEmpty()) {
        item(key = "empty") {
            Empty(when {
                q.isNotEmpty() -> if (u.ours) "No loads match your search." else "No vendor entries match your search."
                u.ours -> if (s.loads.isNotEmpty()) "Every one of our loads in this range is checked." else "No loads from our side in this range."
                else -> if (s.entries.isNotEmpty()) "Every vendor entry in this range is checked." else "No vendor entries in this range yet. They show here once the vendor form is filled."
            })
        }
        return
    }
    val datesC = s.loads.filter { it.k in selC }.map { it.dk }.toSet()
    val datesV = s.entries.filter { it.k in selV }.map { it.dk }.toSet()
    val showVendor = u.filter.vendor.isEmpty()
    items(lines, key = { l -> when (l) { is Line.Day -> "d" + l.dk; is Line.Ours -> l.x.k; is Line.Theirs -> l.x.k } }) { l ->
        when (l) {
            is Line.Day -> Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp, end = 2.dp)) {
                Text(dfmt(l.dk), style = MaterialTheme.typography.labelLarge, color = Fcp.muted, modifier = Modifier.weight(1f))
                Text(l.n.toString(), style = MaterialTheme.typography.labelLarge, color = Fcp.muted)
            }
            is Line.Ours -> OurCard(l.x, l.x.k in selC, l.x.dk in datesV, showVendor) { vm.toggleC(l.x.k) }
            is Line.Theirs -> SlipCard(l.x, l.x.k in selV, l.x.dk in datesC, showVendor) { vm.toggleV(l.x.k) }
        }
    }
}

@Composable
private fun Pick(on: Boolean, hint: Boolean, done: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).let { if (done) it else it.clickable(onClick = onClick) },
        shape = shape,
        color = if (on) Fcp.husk3 else Fcp.husk2,
        border = BorderStroke(if (on) 2.dp else 1.dp, if (on || hint) Fcp.fibre.copy(alpha = if (on) 1f else 0.55f) else Fcp.edge),
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(
                Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape)
                    .background(if (on) Fcp.fibre else Color.Transparent)
                    .border(2.dp, if (on) Fcp.fibre else if (done) Fcp.husk4 else Fcp.faint, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (on) Text("✓", style = MaterialTheme.typography.labelSmall, color = Fcp.husk, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).let { if (done) it.background(Color.Transparent) else it }) { content() }
        }
    }
}

@Composable
private fun Nums(vararg pairs: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        pairs.forEach { (k, v) ->
            Column {
                Text(k, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
                Text(v, style = MaterialTheme.typography.bodyMedium, color = Fcp.cream, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OurCard(x: OurLoad, on: Boolean, hint: Boolean, showVendor: Boolean, onClick: () -> Unit) {
    val done = x.gid != null
    Pick(on, hint, done, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(x.driver.ifEmpty { "No driver" }, style = MaterialTheme.typography.titleSmall, color = if (done) Fcp.muted else Fcp.cream)
            Spacer(Modifier.width(8.dp))
            Text(x.truck, style = MaterialTheme.typography.labelMedium, color = Fcp.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(x.time, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        }
        Flow(Modifier.padding(top = 4.dp), gap = 6.dp) {
            Text(x.place.ifEmpty { "No place" }, style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
            if (x.unload.isNotEmpty()) Tag(x.unload, Fcp.info)
            if (showVendor && x.vendor.isNotEmpty()) Tag(x.vendor, Fcp.fibre)
        }
        val counts = buildList {
            if (x.green != 0.0 || x.brown == 0.0) add("Green" to n0(x.green))
            if (x.brown != 0.0) add("Brown" to n0(x.brown))
            add("Driver total" to n0(x.count)); add("CFT" to (x.cft?.let { n0(it) } ?: "—")); add("Cost" to rs(x.cost))
        }
        Nums(*counts.toTypedArray())
        if (x.note.isNotEmpty()) Text(x.note, style = MaterialTheme.typography.bodySmall, color = Fcp.muted, modifier = Modifier.padding(top = 6.dp))
        if (done) Box(Modifier.padding(top = 6.dp)) { Tag(Rm.LABEL[x.status] ?: x.status ?: "", statusColor(x.status)) }
    }
}

@Composable
private fun SlipCard(x: VendorSlip, on: Boolean, hint: Boolean, showVendor: Boolean, onClick: () -> Unit) {
    val done = x.gid != null
    Pick(on, hint, done, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(x.place.ifEmpty { "No place" }, style = MaterialTheme.typography.titleSmall, color = if (done) Fcp.muted else Fcp.cream,
                modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Tag(x.type, when (x.tk) { "green" -> Fcp.good; "brown" -> Fcp.fibre; else -> Fcp.muted })
            Spacer(Modifier.weight(1f))
            Text(x.entered, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        }
        if (x.truck.isNotEmpty() || (showVendor && x.vendor.isNotEmpty())) {
            Flow(Modifier.padding(top = 4.dp), gap = 6.dp) {
                if (x.truck.isNotEmpty()) Text(x.truck, style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
                if (showVendor && x.vendor.isNotEmpty()) Tag(x.vendor, Fcp.fibre)
            }
        }
        Nums("Husk" to n0(x.count), "Rate" to "₹" + String.format(Locale.US, "%.2f", x.rate), "Amount" to rs(x.total))
        if (x.note.isNotEmpty()) Text(x.note, style = MaterialTheme.typography.bodySmall, color = Fcp.muted, modifier = Modifier.padding(top = 6.dp))
        if (done) Box(Modifier.padding(top = 6.dp)) { Tag(Rm.LABEL[x.status] ?: x.status ?: "", statusColor(x.status)) }
    }
}

/** The weighing scale at the bottom: what is picked on each side and what to do with it. */
@Composable
private fun Scale(cs: List<OurLoad>, vs: List<VendorSlip>, busy: Boolean, onClear: () -> Unit, onAct: (String, String) -> Unit) {
    var note by remember { mutableStateOf("") }
    val driver = cs.sumOf { it.count }; val our = cs.sumOf { it.cost }
    val cft = cs.mapNotNull { it.cft }.sum()
    val vc = vs.sumOf { it.count }; val ven = vs.sumOf { it.total }
    val both = cs.isNotEmpty() && vs.isNotEmpty()
    val tiltTo = if (both) max(-7.0, min(7.0, (ven - our) / max(max(our, ven), 1.0) * 60)).toFloat() else if (cs.isNotEmpty()) -5f else 5f
    val tilt by animateFloatAsState(tiltTo, spring(dampingRatio = 0.45f), label = "tilt")

    Surface(color = Fcp.husk, shadowElevation = 12.dp, border = BorderStroke(1.dp, Fcp.edge)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            // tall enough that the tilted beam never pokes out over the list above
            Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                val cx = size.width / 2; val y = 16.dp.toPx(); val half = size.width * 0.40f
                rotate(tilt, Offset(cx, y)) {
                    drawLine(Fcp.fibre, Offset(cx - half, y), Offset(cx + half, y), 3.dp.toPx(), StrokeCap.Round)
                    drawCircle(Fcp.fibre, 5.dp.toPx(), Offset(cx - half, y))
                    drawCircle(Fcp.fibre, 5.dp.toPx(), Offset(cx + half, y))
                }
                val p = Path().apply { moveTo(cx, y + 1.dp.toPx()); lineTo(cx - 7.dp.toPx(), y + 11.dp.toPx()); lineTo(cx + 7.dp.toPx(), y + 11.dp.toPx()); close() }
                drawPath(p, Fcp.fibre)
            }
            Row(verticalAlignment = Alignment.Top) {
                Pan("Ours", if (cs.isNotEmpty()) plural(cs.size, "load", "loads") else "None picked",
                    if (cs.isNotEmpty()) "${n0(driver)} husk\nCFT ${if (cft > 0) n0(cft) else "—"}\n${rs(our)}" else "", Modifier.weight(1f))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (both) {
                        Diff("Driver − Vendor", sgn(driver - vc), tone(driver - vc))
                        Diff("Company − Vendor", rsg(our - ven), tone(our - ven))
                    } else {
                        Text(if (cs.isNotEmpty()) "Only our loads picked" else "Only vendor entries picked",
                            style = MaterialTheme.typography.labelMedium, color = Fcp.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Pan("Vendor", if (vs.isNotEmpty()) plural(vs.size, "entry", "entries") else "None picked",
                    if (vs.isNotEmpty()) "${n0(vc)} husk\n${rs(ven)}" else "", Modifier.weight(1f), end = true)
            }
            Spacer(Modifier.height(8.dp))
            fcpField(note, { note = it }, "Note (optional)")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val primary = when {
                    both -> Rm.MATCHED to "Match"
                    cs.isNotEmpty() -> Rm.MISS_V to "Missing at vendor"
                    else -> Rm.MISS_F to "Missing at our end"
                }
                PrimaryButton(primary.second, Modifier.weight(1.7f), busy = busy) { onAct(primary.first, note); note = "" }
                GhostButton("Ignore", Modifier.weight(1f), enabled = !busy) { onAct(Rm.IGN, note); note = "" }
                GhostButton("Clear", Modifier.weight(0.9f), enabled = !busy, color = Fcp.muted) { onClear() }
            }
        }
    }
}

@Composable
private fun Pan(who: String, big: String, small: String, modifier: Modifier, end: Boolean = false) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(who, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
        Text(big, style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
        if (small.isNotEmpty()) Text(small, style = MaterialTheme.typography.labelSmall, color = Fcp.muted,
            textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
    }
}

@Composable
private fun Diff(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

// ---------------------------------------------------------------------- Summary

private fun LazyListScope.summaryTab(
    s: RmState, range: String, busy: Boolean, mapC: Map<String, OurLoad>, mapV: Map<String, VendorSlip>, onExport: () -> Unit,
) {
    val sum = s.summary; val m = sum.m
    item(key = "sum-head") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.meta.vendor.ifEmpty { "All vendors" }, style = MaterialTheme.typography.titleLarge, color = Fcp.cream)
                Text(range, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
            }
            GhostButton(if (busy) "Exporting…" else "Export to sheet", enabled = !busy, color = Fcp.fibre) { onExport() }
        }
    }
    item(key = "tiles") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Matched", n0(sum.groups.toDouble()), "${plural(m.loads.toInt(), "our load", "our loads")}, ${plural(m.entries.toInt(), "vendor entry", "vendor entries")}", false, Modifier.weight(1f))
                Tile("Still to check", n0((sum.pendC.n + sum.pendV.n).toDouble()), "${sum.pendC.n} ours, ${sum.pendV.n} vendor", sum.pendC.n + sum.pendV.n > 0, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Missing at vendor", n0(sum.missV.n.toDouble()), "${n0(sum.missV.husk)} husk, ${rs(sum.missV.cost)}", false, Modifier.weight(1f))
                Tile("Missing at our end", n0(sum.missF.n.toDouble()), "${n0(sum.missF.husk)} husk, ${rs(sum.missF.cost)}", sum.missF.n > 0, Modifier.weight(1f))
            }
        }
    }
    item(key = "husk-count") {
        Panel("Husk count", "Matched loads only") {
            Total("Driver count", n0(m.driver))
            Total("Vendor count", n0(m.vendor))
            Total("CFT count", n0(m.cft), if (m.noCft > 0) "${plural(m.noCft.toInt(), "load has", "loads have")} no CFT reading and are left out of the CFT lines" else "Estimated from truck size")
            DiffRow("Driver − Vendor", m.dV, "Vendor counted less", "Vendor counted more", ::sgn, m.driver, true)
            DiffRow("Driver − CFT", m.dC, "Driver above CFT estimate", "Driver below CFT estimate", ::sgn, m.driverCft)
            DiffRow("Vendor − CFT", m.vC, "Vendor above CFT estimate", "Vendor below CFT estimate", ::sgn, m.cft)
        }
    }
    item(key = "cost") {
        Panel("Cost", "Matched loads only") {
            Total("Company total", rs(m.ourCost), "Driver count at our price")
            Total("Vendor total", rs(m.vendorCost))
            Total("CFT value", rs(m.cftCost), "CFT count at our price for each load")
            DiffRow("Company − Vendor", m.cV, "Vendor billed less", "Vendor billed more", ::rsg, m.ourCost, true)
            DiffRow("Company − CFT value", m.cC, "We pay above CFT value", "We pay below CFT value", ::rsg, m.costCft)
            DiffRow("Vendor − CFT value", m.vCc, "Vendor above CFT value", "Vendor below CFT value", ::rsg, m.cftCost)
        }
    }
    item(key = "whole") {
        val gap = sum.ourAll - sum.vendorAll
        Panel("Whole period", "Every entry in this range, including loads still to check and missing ones. Ignored rows are left out.") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Big("Vendor's slips", rs(sum.vendorAll), Fcp.cream, Modifier.weight(1f))
                Big("Our loads", rs(sum.ourAll), Fcp.cream, Modifier.weight(1f))
            }
            Big("Our record − vendor claim", rsg(gap), tone(gap), Modifier.padding(top = 10.dp))
        }
    }
    if (s.meta.vendorKey.isEmpty() && sum.byVendor.size > 1) {
        item(key = "by-vendor-title") { SectionTitle("By vendor") }
        items(sum.byVendor, key = { "bv-" + it.key }) { b ->
            Card(padding = 12.dp) {
                Text(b.name, style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                Nums("Matched" to b.groups.toString(), "Driver − Vendor" to sgn(b.m.dV), "Company − Vendor" to rsg(b.m.cV))
                Nums(
                    "Missing at vendor" to (if (b.missV.n > 0) "${b.missV.n} (${rs(b.missV.cost)})" else "0"),
                    "Missing at our end" to (if (b.missF.n > 0) "${b.missF.n} (${rs(b.missF.cost)})" else "0"),
                    "To check" to "${b.pendC.n} ours, ${b.pendV.n} vendor",
                )
            }
        }
    }
    val big = s.groups.filter { it.status == Rm.MATCHED && it.m.cV.roundToLong() != 0L }.sortedByDescending { abs(it.m.cV) }.take(10)
    if (big.isNotEmpty()) {
        item(key = "big-title") {
            Column { SectionTitle("Biggest differences"); Text("Matched loads, largest cost gap first", style = MaterialTheme.typography.labelMedium, color = Fcp.muted) }
        }
        items(big, key = { "big-" + it.id }) { g ->
            Card(padding = 12.dp) {
                Row {
                    Text(dshort(g.dk), style = MaterialTheme.typography.labelLarge, color = Fcp.fibre)
                    Spacer(Modifier.width(8.dp))
                    Text(whoC(g, mapC), style = MaterialTheme.typography.bodyMedium, color = Fcp.cream, modifier = Modifier.weight(1f))
                }
                Nums("Driver" to n0(g.m.driver), "Vendor" to n0(g.m.vendor), "CFT" to n0(g.m.cft))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Diff("Driver − Vendor", sgn(g.m.dV), tone(g.m.dV))
                    Diff("Company − Vendor", rsg(g.m.cV), tone(g.m.cV))
                }
            }
        }
    }
    val missV = s.groups.filter { it.status == Rm.MISS_V }
    val missF = s.groups.filter { it.status == Rm.MISS_F }
    if (missV.isNotEmpty() || missF.isNotEmpty()) {
        item(key = "miss") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel("Missing at vendor", "Our loads the vendor has no slip for") {
                    val rows = missV.flatMap { g -> g.cks.mapNotNull { mapC[it] } }
                    if (rows.isEmpty()) Text("None", color = Fcp.muted)
                    rows.forEach { x -> MissRow(dshort(x.dk), "${x.driver} ${x.truck}".trim(), x.place, n0(x.count), rs(x.cost)) }
                }
                Panel("Missing at our end", "Vendor entries with no load on our side") {
                    val rows = missF.flatMap { g -> g.vks.mapNotNull { mapV[it] } }
                    if (rows.isEmpty()) Text("None", color = Fcp.muted)
                    rows.forEach { x -> MissRow(dshort(x.dk), x.place, x.type, n0(x.count), rs(x.total)) }
                }
            }
        }
    }
}

private fun whoC(g: RmGroup, mapC: Map<String, OurLoad>) =
    g.cks.mapNotNull { mapC[it] }.joinToString(" + ") { x -> listOf(x.driver, x.place).filter { it.isNotEmpty() }.joinToString(", ") }.ifEmpty { "—" }
private fun whoV(g: RmGroup, mapV: Map<String, VendorSlip>) =
    g.vks.mapNotNull { mapV[it] }.joinToString(" + ") { x -> listOf(x.place, x.type).filter { it.isNotEmpty() }.joinToString(", ") }.ifEmpty { "—" }

@Composable
private fun Tile(title: String, big: String, sub: String, alert: Boolean, modifier: Modifier) {
    Card(modifier, padding = 12.dp, accent = if (alert) Fcp.bad.copy(alpha = 0.7f) else null) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        Text(big, style = MaterialTheme.typography.displaySmall, color = if (alert) Fcp.bad else Fcp.cream)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
    }
}

@Composable
private fun Panel(title: String, hint: String, content: @Composable () -> Unit) {
    Card(padding = 14.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Fcp.cream)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Total(k: String, v: String, why: String = "") {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(k, style = MaterialTheme.typography.bodyMedium, color = Fcp.cream, modifier = Modifier.weight(1f))
            Text(v, style = MaterialTheme.typography.bodyMedium, color = Fcp.cream, fontWeight = FontWeight.SemiBold)
        }
        if (why.isNotEmpty()) Text(why, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
    }
}

@Composable
private fun DiffRow(k: String, v: Double, pos: String, neg: String, fmt: (Double) -> String, base: Double, colored: Boolean = false) {
    val r = v.roundToLong()
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(k, style = MaterialTheme.typography.bodyMedium, color = Fcp.muted, modifier = Modifier.weight(1f))
            Text(fmt(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (colored) tone(v) else Fcp.cream)
        }
        Text((if (r > 0) pos else if (r < 0) neg else "Same") + if (r != 0L && base != 0.0) " (${pct(v, base)})" else "",
            style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
    }
}

@Composable
private fun Big(k: String, v: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(k, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
        Text(v, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

@Composable
private fun MissRow(date: String, main: String, sub: String, husk: String, amount: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(date, style = MaterialTheme.typography.labelMedium, color = Fcp.fibre, modifier = Modifier.width(52.dp))
        Column(Modifier.weight(1f)) {
            Text(main.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, color = Fcp.cream)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(husk, style = MaterialTheme.typography.bodySmall, color = Fcp.cream)
            Text(amount, style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
        }
    }
}

// ---------------------------------------------------------------------- History

private fun LazyListScope.historyTab(
    s: RmState, u: RmUi, mapC: Map<String, OurLoad>, mapV: Map<String, VendorSlip>, busy: Boolean, vm: HuskViewModel, onUndo: (String) -> Unit,
) {
    val all = s.groups
    val counts = all.groupingBy { it.status }.eachCount()
    item(key = "hist-bar") {
        ChoiceRow(
            listOf("ALL" to "All ${all.size}") + Rm.ST_LIST.map { it to "${Rm.LABEL[it]} ${counts[it] ?: 0}" },
            u.hist, { h -> vm.set { it.copy(hist = h) } },
        )
    }
    val list = all.filter { u.hist == "ALL" || it.status == u.hist }
        .sortedWith(compareByDescending<RmGroup> { it.dk }.thenByDescending { it.id })
    if (list.isEmpty()) item(key = "hist-empty") { Empty("Nothing saved in this range yet. Pick loads on the Match tab to start.") }
    items(list, key = { "g-" + it.id }) { g ->
        val m = g.m
        Card(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(Rm.LABEL[g.status] ?: g.status, statusColor(g.status))
                Spacer(Modifier.width(8.dp))
                Text(dfmt(g.dk), style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                if (g.vendor.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(g.vendor, style = MaterialTheme.typography.labelMedium, color = Fcp.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            if (g.cks.isNotEmpty()) Text("Ours: " + whoC(g, mapC), style = MaterialTheme.typography.bodySmall, color = Fcp.cream, modifier = Modifier.padding(top = 6.dp))
            if (g.vks.isNotEmpty()) Text("Vendor: " + whoV(g, mapV), style = MaterialTheme.typography.bodySmall, color = Fcp.cream, modifier = Modifier.padding(top = 2.dp))
            when (g.status) {
                Rm.MATCHED -> {
                    Nums("Driver" to n0(m.driver), "Vendor" to n0(m.vendor), "CFT" to n0(m.cft))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Diff("Driver − Vendor", sgn(m.dV), tone(m.dV)); Diff("Company − Vendor", rsg(m.cV), tone(m.cV))
                    }
                }
                Rm.MISS_V -> Nums("Husk" to n0(m.driver), "Our cost" to rs(m.ourCost))
                Rm.MISS_F -> Nums("Husk" to n0(m.vendor), "Vendor cost" to rs(m.vendorCost))
            }
            if (g.note.isNotEmpty()) Text(g.note, style = MaterialTheme.typography.bodySmall, color = Fcp.muted, modifier = Modifier.padding(top = 6.dp))
            if (g.lost > 0) Text("${plural(g.lost, "row was", "rows were")} deleted from the sheet after saving.",
                style = MaterialTheme.typography.bodySmall, color = Fcp.bad, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Saved ${g.created}${if (g.by.isNotEmpty()) " by " + g.by else ""}", style = MaterialTheme.typography.labelSmall, color = Fcp.faint, modifier = Modifier.weight(1f))
                GhostButton("Undo", enabled = !busy, color = Fcp.fibre) { onUndo(g.id) }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Card { Text(text, style = MaterialTheme.typography.bodyMedium, color = Fcp.muted) }
}
