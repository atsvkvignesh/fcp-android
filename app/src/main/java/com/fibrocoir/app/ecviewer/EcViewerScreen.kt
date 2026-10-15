package com.fibrocoir.app.ecviewer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fibrocoir.app.ec.Ec
import com.fibrocoir.app.ec.EcView
import com.fibrocoir.app.ec.Filters
import com.fibrocoir.app.ec.Range
import com.fibrocoir.app.ec.Reading
import com.fibrocoir.app.ec.Stats
import com.fibrocoir.app.ec.records
import com.fibrocoir.app.hub.fcpField
import com.fibrocoir.app.ui.Card
import com.fibrocoir.app.ui.ChoiceRow
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.GhostButton
import com.fibrocoir.app.ui.Limit
import com.fibrocoir.app.ui.LineChart
import com.fibrocoir.app.ui.LoadBox
import com.fibrocoir.app.ui.LocalSay
import com.fibrocoir.app.ui.Segments
import com.fibrocoir.app.ui.Series
import com.fibrocoir.app.ui.Tag
import com.fibrocoir.app.ui.num
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val BELT = Color(0xFFF2C255)
private val BLOCK = Color(0xFF7FB6D9)
private val YARD = Color(0xFF8FBF6A)

@Composable
fun EcViewerScreen(onBack: () -> Unit, vm: EcViewerViewModel = viewModel()) {
    val load by vm.data.state.collectAsState()
    val ui by vm.ui.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    var limitsOpen by remember { mutableStateOf(false) }
    var pickDates by remember { mutableStateOf(false) }

    FcpScreen(
        title = "EC readings", subtitle = vm.rangeLabel(), onBack = onBack,
        load = load, onRefresh = { vm.load(force = true) },
        actions = {
            TextButton(onClick = { limitsOpen = true }) {
                Text(if (load.data?.viewerLimits != null) "Limits (yours)" else "Limits", color = Fcp.fibre)
            }
            IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") }
        },
    ) {
        LoadBox(load, onRetry = { vm.load() }) { data ->
            val view = remember(data) { EcView(data.tabs, data.viewerLimits) }
            val say = LocalSay.current
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Segments(listOf(Side.HALL to "Machine hall", Side.YARD to "Yards"), ui.side, { s -> vm.set({ it.copy(side = s, show = 50) }) })
                }
                item {
                    ChoiceRow(Range.entries.map { it to it.label }, ui.range, { r ->
                        if (r == Range.CUSTOM) pickDates = true else vm.set({ it.copy(range = r) }, reload = true)
                    })
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dropdown("Logged by", ui.who, listOf("all" to "Everyone") + data.people.map { it to it }, Modifier.weight(1f)) { w ->
                            vm.set({ it.copy(who = w) }, reload = true)
                        }
                        if (ui.side == Side.YARD) {
                            Spacer(Modifier.width(10.dp))
                            Dropdown("Yard", ui.yard, listOf("all" to "All yards") + view.yards.map { it to it }, Modifier.weight(1f)) { y ->
                                vm.set({ it.copy(yard = y, show = 50) })
                            }
                        }
                    }
                }
                if (ui.side == Side.HALL) hall(view, ui) else yards(view, ui) { y -> vm.set({ it.copy(yard = y, show = 50) }) }

                // ---------- readings list
                val f = Filters(
                    point = if (ui.side == Side.HALL) ui.hallPoint else "Yard",
                    spot = if (ui.side == Side.YARD) ui.yard else "all",
                    overOnly = ui.overOnly, text = ui.text,
                )
                val recs = view.records(f)
                item {
                    Text(
                        if (ui.side == Side.HALL) "Machine hall readings" else if (ui.yard == "all") "Yard readings" else "${ui.yard} readings",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (ui.side == Side.HALL) item {
                    ChoiceRow(listOf("Hall" to "Belt and Block", "Belt" to "Belt", "Block" to "Block"), ui.hallPoint, { p -> vm.set({ it.copy(hallPoint = p, show = 50) }) })
                }
                item {
                    fcpField(ui.text, { t -> vm.set({ it.copy(text = t, show = 50) }) }, "Search sample, spot, condition, person")
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(ui.overOnly, { o -> vm.set({ it.copy(overOnly = o, show = 50) }) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Fcp.husk, checkedTrackColor = Fcp.bad))
                        Spacer(Modifier.width(8.dp))
                        Text("Only over limit", color = Fcp.cream, modifier = Modifier.weight(1f))
                        GhostButton("Export to sheet", enabled = !busy && recs.isNotEmpty()) {
                            vm.export(recs.take(400), view::over) { url, err ->
                                if (url != null) {
                                    say("Exported ${minOf(recs.size, 400)} rows to the \"Export view\" tab.")
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
                                } else say(err ?: "Couldn't export.")
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (recs.isEmpty()) "No readings match." else "${recs.size} reading${if (recs.size == 1) "" else "s"}, newest first",
                        style = MaterialTheme.typography.labelMedium, color = Fcp.muted,
                    )
                }
                items(recs.take(ui.show), key = { it.tab + ":" + it.row }) { r -> ReadingRow(r, view, multiDay = data.tabs.map { it.date }.distinct().size > 1) }
                if (recs.size > ui.show) item {
                    GhostButton("Show ${minOf(50, recs.size - ui.show)} more", Modifier.fillMaxWidth()) { vm.set({ it.copy(show = it.show + 50) }) }
                }
            }
        }
    }

    if (limitsOpen) LimitsDialog(load.data?.let { EcView(it.tabs, null).sheetLimits }, load.data?.viewerLimits, busy,
        onClose = { limitsOpen = false }) { b, k, y ->
        vm.setLimits(b, k, y) { err -> if (err == null) limitsOpen = false }
    }
    if (pickDates) DateRangeDialog(ui.from, ui.to, onClose = { pickDates = false }) { a, b ->
        pickDates = false
        vm.set({ it.copy(range = Range.CUSTOM, from = a, to = b) }, reload = true)
    }
}

// ---------------------------------------------------------------------- machine hall

private fun androidx.compose.foundation.lazy.LazyListScope.hall(view: EcView, ui: ViewerUi) {
    val hall = view.readings.filter { it.hall }
    val belt = view.stats(hall.filter { it.point == "Belt" })
    val block = view.stats(hall.filter { it.point == "Block" })
    item {
        Card {
            Headline(belt.n + block.n, belt.over + block.over)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PointTile("Belt", belt, view.limits.belt, BELT, Modifier.weight(1f))
                PointTile("Block", block, view.limits.block, BLOCK, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(lastText(view.last(hall), view, multiDay(view)), style = MaterialTheme.typography.bodyMedium, color = Fcp.muted)
            WhoLine(view.by(hall) { it.op })
        }
    }
    item {
        Card {
            Text("Belt and Block, every reading", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (hall.isEmpty()) Text("No Belt or Block readings in this range yet.", color = Fcp.muted, style = MaterialTheme.typography.bodySmall)
            LineChart(
                labels = if (hall.isEmpty()) listOf("") else timeLabels(hall, multiDay(view)),
                subLabels = hall.map { "#" + it.sno },
                series = listOf(
                    Series("Belt", BELT, hall.map { if (it.point == "Belt") it.ec else null }),
                    Series("Block", BLOCK, hall.map { if (it.point == "Block") it.ec else null }),
                ),
                limits = listOf(Limit("Belt limit", view.limits.belt, BELT.copy(alpha = 0.8f)), Limit("Block limit", view.limits.block, BLOCK.copy(alpha = 0.8f))),
            )
        }
    }
}

// ---------------------------------------------------------------------- yards

private fun androidx.compose.foundation.lazy.LazyListScope.yards(view: EcView, ui: ViewerUi, pickYard: (String) -> Unit) {
    val all = view.readings.filter { it.point == "Yard" }
    val shown = if (ui.yard == "all") all else all.filter { it.spot == ui.yard }
    val st = view.stats(shown)
    item {
        Card {
            Headline(st.n, st.over)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Small("Average", num(st.avg)); Small("Highest", num(st.max)); Small("Limit", num(view.limits.yard))
            }
            Spacer(Modifier.height(10.dp))
            Text(lastText(view.last(shown), view, multiDay(view)), style = MaterialTheme.typography.bodyMedium, color = Fcp.muted)
            WhoLine(view.by(shown) { it.op })
        }
    }
    // worst first: most over-limit share, then most readings
    val spots = view.by(shown) { it.spot }.entries.sortedWith(
        compareByDescending<Map.Entry<String, Stats>> { it.value.over.toDouble() / it.value.n }.thenByDescending { it.value.n }
    )
    if (spots.isEmpty()) item {
        Card { Text("No yard readings in this range yet.", color = Fcp.muted) }
    }
    items(spots, key = { "y:" + it.key }) { (spot, d) ->
        val pts = shown.filter { it.spot == spot }
        val pct = if (d.n > 0) Math.round(d.over * 100.0 / d.n) else 0
        Card(onClick = { pickYard(if (ui.yard == spot) "all" else spot) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spot, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(if (d.over > 0) "$pct% over" else "all within limit", if (d.over > 0) Fcp.bad else Fcp.good)
            }
            Text("${d.n} reading${if (d.n == 1) "" else "s"}, average ${num(d.avg)}, highest ${num(d.max)}",
                style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
            Spacer(Modifier.height(6.dp))
            LineChart(
                labels = timeLabels(pts, multiDay(view)), subLabels = pts.map { "#" + it.sno },
                series = listOf(Series("Reading", YARD, pts.map { it.ec })),
                limits = listOf(Limit("Limit", view.limits.yard, Fcp.bad)), height = 180.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------- pieces

private fun multiDay(view: EcView) = view.tabs.map { it.date }.distinct().size > 1

private fun timeLabels(pts: List<Reading>, multi: Boolean) =
    pts.map { (if (multi) EcViewerViewModel.dmy(it.date) + " " else "") + it.hhmm }

private fun lastText(r: Reading?, view: EcView, multi: Boolean): String {
    if (r == null) return "Nothing logged in this range."
    val where = if (r.point == "Yard") r.spot else r.point
    val whenTxt = (if (multi) EcViewerViewModel.dmy(r.date) + " " else "") + r.hhmm
    return "Last reading: $where ${num(r.ec)}${if (view.over(r)) " (over)" else ""}${if (whenTxt.isNotBlank()) " at $whenTxt" else ""}, by ${r.op}"
}

@Composable
private fun Headline(n: Int, over: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column { Text("$n", style = MaterialTheme.typography.displaySmall); Text("readings", style = MaterialTheme.typography.labelMedium, color = Fcp.muted) }
        Spacer(Modifier.width(28.dp))
        Column {
            Text("$over", style = MaterialTheme.typography.displaySmall, color = if (over > 0) Fcp.bad else Fcp.cream)
            Text("over limit", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        }
    }
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(
        progress = { if (n == 0) 0f else over.toFloat() / n },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = Fcp.bad, trackColor = Fcp.husk4, drawStopIndicator = {},
    )
}

@Composable
private fun PointTile(name: String, d: Stats, limit: Double, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Fcp.husk3).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(16.dp).background(color))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("limit ${num(limit)}", style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(num(d.avg), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(4.dp))
            Text("avg", style = MaterialTheme.typography.labelSmall, color = Fcp.muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("${d.n} taken, ${d.over} over", style = MaterialTheme.typography.bodySmall, color = if (d.over > 0) Fcp.bad else Fcp.good)
        Text("highest ${num(d.max)}", style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
    }
}

@Composable
private fun Small(label: String, value: String) {
    Column { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelSmall, color = Fcp.muted) }
}

@Composable
private fun WhoLine(map: Map<String, Stats>) {
    if (map.isEmpty()) return
    val names = map.entries.sortedByDescending { it.value.n }
    Text(
        "Logged by " + names.joinToString(", ") { (p, d) -> "$p ${d.n}" + if (d.over > 0) " (${d.over} over)" else "" },
        style = MaterialTheme.typography.bodySmall, color = Fcp.muted, modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ReadingRow(r: Reading, view: EcView, multiDay: Boolean) {
    val over = view.over(r)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fcp.husk2).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (r.point == "Yard") r.spot else r.point) + "  ·  #" + r.sno,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull((if (multiDay) EcViewerViewModel.dmy(r.date) + " " else "") + r.hhmm, r.cond.ifBlank { null }, r.op).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = Fcp.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(num(r.ec), style = MaterialTheme.typography.titleLarge, color = if (over) Fcp.bad else Fcp.cream, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fcp.husk2).clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
            Text(options.firstOrNull { it.first == value }?.second ?: value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
private fun LimitsDialog(
    sheet: com.fibrocoir.app.ec.Limits?, mine: com.fibrocoir.app.ec.ViewerLimits?, busy: Boolean,
    onClose: () -> Unit, onSave: (Double?, Double?, Double?) -> Unit,
) {
    var b by remember { mutableStateOf(mine?.belt?.toString() ?: "") }
    var k by remember { mutableStateOf(mine?.block?.toString() ?: "") }
    var y by remember { mutableStateOf(mine?.yard?.toString() ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    fun parse(s: String) = s.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onClose, containerColor = Fcp.husk2,
        title = { Text("Limits") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Used for the graphs, over-limit counts, the list and the export, for everyone using EC Viewer. " +
                    "A blank box keeps the sheet's limit. EC Log and the sheet are not changed.",
                    style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
                fcpField(b, { b = it }, "Belt (sheet: ${num(sheet?.belt)})", keyboard = KeyboardType.Decimal)
                fcpField(k, { k = it }, "Block (sheet: ${num(sheet?.block)})", keyboard = KeyboardType.Decimal)
                fcpField(y, { y = it }, "Yard (sheet: ${num(sheet?.yard)})", keyboard = KeyboardType.Decimal)
                err?.let { Text(it, color = Fcp.bad) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val v = listOf(b, k, y).map { if (it.isBlank()) null else parse(it) ?: -1.0 }
                if (v.any { it != null && (it <= 0 || it > 20) }) err = "Limits must be numbers between 0 and 20."
                else if (v.all { it == null }) err = "Enter at least one limit."
                else onSave(v[0], v[1], v[2])
            }) { Text("Save", color = Fcp.fibre) }
        },
        dismissButton = {
            Row {
                if (mine != null) TextButton(enabled = !busy, onClick = { onSave(null, null, null) }) { Text("Use the sheet's", color = Fcp.muted) }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(from: String?, to: String?, onClose: () -> Unit, onPick: (String, String) -> Unit) {
    val utc = TimeZone.getTimeZone("UTC")
    val f = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc } }
    var first by remember { mutableStateOf<String?>(null) }
    val state = rememberDatePickerState(initialSelectedDateMillis = (if (first == null) from else to)?.let { f.parse(it)?.time })
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { f.format(Date(it)) } ?: return@TextButton
                if (first == null) { first = picked; state.selectedDateMillis = null }
                else onPick(first!!, picked)
            }) { Text(if (first == null) "Next: end date" else "Show", color = Fcp.fibre) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    ) {
        Text(if (first == null) "  Pick the first day" else "  From ${EcViewerViewModel.dmy(first!!)} — now pick the last day",
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
        DatePicker(state, title = null, headline = null, showModeToggle = false)
    }
}
