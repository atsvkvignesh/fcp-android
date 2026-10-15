package com.fibrocoir.app.eclog

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.ec.Ec
import com.fibrocoir.app.ec.EcBoot
import com.fibrocoir.app.ec.Entry
import com.fibrocoir.app.ec.Reading
import com.fibrocoir.app.hub.fcpField
import com.fibrocoir.app.ui.Card
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Flow
import com.fibrocoir.app.ui.GhostButton
import com.fibrocoir.app.ui.Limit
import com.fibrocoir.app.ui.LineChart
import com.fibrocoir.app.ui.LoadBox
import com.fibrocoir.app.ui.LocalSay
import com.fibrocoir.app.ui.Pill
import com.fibrocoir.app.ui.PrimaryButton
import com.fibrocoir.app.ui.Series
import com.fibrocoir.app.ui.num
import kotlinx.coroutines.launch

private const val NEW = "__new"

@Composable
fun EcLogScreen(onBack: () -> Unit, vm: EcLogViewModel = viewModel()) {
    val op by vm.operator.collectAsState()
    if (op.isBlank()) OperatorPick(vm, onBack) else LogView(vm, onBack)
}

// ====================================================================== who is entering

@Composable
private fun OperatorPick(vm: EcLogViewModel, onBack: () -> Unit) {
    val load by vm.boot.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    FcpScreen("EC log", "Who is taking the readings?", onBack = onBack, load = load) {
        Column(Modifier.fillMaxSize().padding(16.dp).imePadding()) {
            Flow { (load.data?.operators ?: emptyList()).forEach { o -> Pill(o, name == o) { name = o } } }
            Spacer(Modifier.height(16.dp))
            fcpField(name, { name = it; err = false }, "Name", placeholder = "Type your name", ime = ImeAction.Go,
                onDone = { if (name.trim().length >= 2) vm.start(name) else err = true })
            if (err) Text("Type your name first.", color = Fcp.bad, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Start", Modifier.fillMaxWidth()) { if (name.trim().length >= 2) vm.start(name) else err = true }
        }
    }
}

// ====================================================================== logging

@Composable
private fun LogView(vm: EcLogViewModel, onBack: () -> Unit) {
    val load by vm.boot.state.collectAsState()
    val op by vm.operator.collectAsState()
    val queue by vm.queue.collectAsState()
    val saving by vm.saving.collectAsState()
    val unlocked by vm.unlocked.collectAsState()
    val cleared by vm.cleared.collectAsState()
    val online by vm.online.collectAsState()
    val ctx = LocalContext.current
    var charts by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // the form
    var point by remember { mutableStateOf("") }
    var spot by remember { mutableStateOf("") }
    var spotNew by remember { mutableStateOf("") }
    var cond by remember { mutableStateOf("") }
    var condNew by remember { mutableStateOf("") }
    var sno by remember { mutableStateOf("") }
    var ec by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Reading?>(null) }

    fun needUnlock(why: String, then: () -> Unit) { if (unlocked) { vm.touch(); then() } else askPin = why to then }

    BackHandler(enabled = charts) { charts = false }

    if (charts) { ChartsView(load.data, onBack = { charts = false }); return }

    FcpScreen(
        title = op, subtitle = load.data?.date ?: Ec.today(), onBack = onBack, load = load, onRefresh = vm::refresh,
        actions = {
            TextButton(onClick = { if (unlocked) vm.lock() else needUnlock("Unlocks edit, remove, clear and charts.") {} }) {
                Text(if (unlocked) "🔓 Unlocked" else "🔒 Locked", color = if (unlocked) Fcp.fibre else Fcp.muted)
            }
            TextButton(onClick = {
                if (queue.isNotEmpty()) confirm = "${queue.size} reading(s) are still waiting to reach the sheet. Change who is entering anyway?" to vm::switchOperator
                else vm.switchOperator()
            }) { Text("Change", color = Fcp.muted) }
        },
    ) {
        val say = LocalSay.current
        LoadBox(load, onRetry = vm::refresh) { b ->
            val list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val rows = b.tab?.rows.orEmpty()
            val visible = rows.filter { cleared == 0L || it.tsMs == 0L || it.tsMs > cleared }
            val hidden = rows.size - visible.size

            fun save() {
                if (point.isEmpty()) { say("Pick Belt, Block or Yard."); return }
                val s = if (point == "Yard") (if (spot == NEW) spotNew.trim() else spot) else point
                if (point == "Yard" && (s.isBlank() || s == NEW)) { say("Pick or name the yard spot."); return }
                val c = if (point == "Yard") (if (cond == NEW) condNew.trim() else cond) else ""
                if (point == "Yard" && (c.isBlank() || c == NEW)) { say("Pick the material condition."); return }
                if (sno.isBlank()) { say("Enter the sample no."); return }
                val v = ec.replace(',', '.').toDoubleOrNull()
                if (v == null || !v.isFinite()) { say("Enter the EC value."); return }
                val entry = Entry(op, point, s, c, sno.trim(), v, System.currentTimeMillis())
                val e = edit
                if (e != null) {
                    vm.update(e.row, e.sno, entry) { ok, msg -> say(msg); if (ok) { edit = null; sno = ""; ec = "" } }
                } else {
                    vm.save(entry) { ok, msg -> say(msg); if (ok) { sno = ""; ec = "" } }
                }
            }

            LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { StatusLine(queue.size, online) }
                item {
                    Card {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Belt" to "on the conveyor", "Block" to "finished block", "Yard" to "drying yard").forEach { (p, sub) ->
                                PointButton(p, sub, point == p, Modifier.weight(1f)) { point = p }
                            }
                        }
                        if (point == "Yard") {
                            Spacer(Modifier.height(14.dp))
                            Text("Yard spot", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
                            Spacer(Modifier.height(6.dp))
                            val spots = (EcLogViewModel.YARD_SPOTS + listOfNotNull(spot.takeIf { it.isNotEmpty() && it != NEW })).distinct()
                            Flow { spots.forEach { s -> Pill(s, spot == s) { spot = s } }; Pill("Other", spot == NEW) { spot = NEW } }
                            if (spot == NEW) fcpField(spotNew, { spotNew = it }, "Spot name", Modifier.padding(top = 8.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Material condition", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
                            Spacer(Modifier.height(6.dp))
                            val conds = (EcLogViewModel.CONDITIONS + listOfNotNull(cond.takeIf { it.isNotEmpty() && it != NEW })).distinct()
                            Flow { conds.forEach { c -> Pill(c, cond == c) { cond = c } }; Pill("Other", cond == NEW) { cond = NEW } }
                            if (cond == NEW) fcpField(condNew, { condNew = it }, "Condition", Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            fcpField(sno, { sno = it }, "Sample no.", Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "e.g. 12")
                            fcpField(ec, { ec = it }, "EC value", Modifier.weight(1f), keyboard = KeyboardType.Decimal, placeholder = "e.g. 0.85",
                                ime = ImeAction.Done, onDone = ::save)
                        }
                        edit?.let { Text("Fixing sample ${it.sno} taken at ${it.hhmm}", color = Fcp.fibre, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(if (edit != null) "Save changes" else "Save reading", Modifier.weight(1f), busy = saving, onClick = ::save)
                            if (edit != null) GhostButton("Cancel") { edit = null; sno = ""; ec = "" }
                        }
                    }
                }
                item {
                    Text("Today", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
                    val n = visible.size
                    Text(
                        if (n > 0) "$n reading${if (n > 1) "s" else ""} in the sheet" + (if (queue.isNotEmpty()) ", ${queue.size} waiting" else "") +
                            (if (hidden > 0) " · $hidden older one${if (hidden > 1) "s" else ""} cleared from this list, still in the sheet" else "")
                        else if (hidden > 0) "List cleared. The $hidden earlier reading${if (hidden > 1) "s are" else " is"} still in the sheet."
                        else if (queue.isNotEmpty()) "${queue.size} waiting to send." else "No readings yet.",
                        style = MaterialTheme.typography.bodySmall, color = Fcp.muted,
                    )
                }
                items(queue.reversed(), key = { "q" + it.clientTs }) { q ->
                    RowLine(Ec.fmt("HH:mm", q.clientTs), q.sno, q.point, q.spot, q.cond, q.ec, q.operator, over = q.ec > b.limitsFor(q.point), pending = true)
                }
                items(visible.reversed(), key = { "r" + it.row + ":" + it.tsMs }) { r ->
                    RowLine(r.hhmm, r.sno, r.point, r.spot, r.cond, r.ec, r.op, over = r.ec > (b.tab?.limits ?: b.limits).of(r.point),
                        onEdit = {
                            needUnlock("Needed to fix a reading.") {
                                edit = r; point = r.point; spot = if (r.point == "Yard") r.spot else ""
                                cond = if (r.point == "Yard") r.cond else ""; sno = r.sno; ec = num(r.ec)
                                scope.launch { list.animateScrollToItem(1) }
                            }
                        },
                        onRemove = if (unlocked) ({
                            confirm = "Remove sample ${r.sno}? This deletes the row from the Google Sheet." to { vm.remove(r.row, r.sno) { say(it) } }
                        }) else null,
                    )
                }
                item {
                    Flow(Modifier.padding(top = 8.dp)) {
                        GhostButton("Charts") { needUnlock("Needed to see the charts.") { charts = true } }
                        GhostButton("Open the sheet") {
                            needUnlock("Needed to open the sheet.") {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FcpSheets.url(FcpSheets.EC)))) } catch (_: Exception) { }
                            }
                        }
                        GhostButton("Clear list") {
                            needUnlock("Needed to clear the list.") {
                                if (queue.isNotEmpty()) say("Send the waiting readings first.")
                                else confirm = "Clear the list? This only clears what you see on this phone. Every row stays in the Google Sheet." to {
                                    vm.clearList(); edit = null; say("List cleared. The sheet still has everything.")
                                }
                            }
                        }
                        if (hidden > 0) GhostButton("Show all of today") { vm.showAll() }
                        if (queue.isNotEmpty()) GhostButton("Send waiting readings", color = Fcp.fibre) { vm.sendWaiting { say(it) } }
                    }
                }
            }
        }

        askPin?.let { (why, then) ->
            PinDialog(why, onClose = { askPin = null }) { pin ->
                if (vm.unlock(pin)) { askPin = null; say("Unlocked. Tap the lock when you are done."); then() } else say("Wrong password.")
            }
        }
        confirm?.let { (text, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null }, containerColor = Fcp.husk2,
                text = { Text(text, color = Fcp.cream) },
                confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Yes", color = Fcp.bad) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }
    }
}

private fun EcBoot.limitsFor(point: String) = (tab?.limits ?: limits).of(point)

@Composable
private fun StatusLine(waiting: Int, online: Boolean) {
    val (color, text) = when {
        waiting > 0 -> Fcp.fibre to "$waiting reading${if (waiting > 1) "s" else ""} waiting to send"
        !online -> Fcp.muted to "No connection — readings will be held on this phone"
        else -> Fcp.good to "Saving straight to the sheet"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
    }
}

@Composable
private fun PointButton(name: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(if (on) Fcp.fibre else Fcp.husk3)
            .border(1.dp, if (on) Fcp.fibre else Fcp.edge, shape).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = if (on) Fcp.husk else Fcp.cream)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = if (on) Fcp.husk.copy(alpha = 0.75f) else Fcp.muted)
    }
}

@Composable
private fun RowLine(
    time: String, sno: String, point: String, spot: String, cond: String, ec: Double, by: String,
    over: Boolean, pending: Boolean = false, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (over) Fcp.bad.copy(alpha = 0.12f) else Fcp.husk2)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("#$sno  ·  " + if (point == "Yard") spot else point, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(time, cond.ifBlank { null }, by, if (pending) "waiting" else null).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = if (pending) Fcp.fibre else Fcp.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(num(ec), style = MaterialTheme.typography.titleLarge, color = if (over) Fcp.bad else Fcp.cream, fontWeight = FontWeight.Bold)
        if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit", color = Fcp.fibre) }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = Fcp.bad) }
    }
}

@Composable
private fun PinDialog(why: String, onClose: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Fcp.husk2,
        title = { Text("Head QC password") },
        text = { Column { Text(why, color = Fcp.muted); Spacer(Modifier.height(10.dp)); fcpField(pin, { pin = it }, "Password", password = true, ime = ImeAction.Go, onDone = { onPin(pin) }) } },
        confirmButton = { TextButton(onClick = { onPin(pin) }) { Text("Unlock", color = Fcp.fibre) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ====================================================================== charts of today

@Composable
private fun ChartsView(b: EcBoot?, onBack: () -> Unit) {
    val rows = b?.tab?.rows.orEmpty()
    val lim = b?.tab?.limits ?: b?.limits ?: com.fibrocoir.app.ec.Limits()
    FcpScreen("Charts", (b?.date ?: "") + " · tap a line to read a sample", onBack = onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val hall = rows.filter { it.hall }
            if (hall.isNotEmpty()) item {
                Card {
                    Text("Belt and Block", style = MaterialTheme.typography.titleMedium)
                    val xs = bySample(hall)
                    LineChart(
                        labels = xs, format = { num(it) },
                        series = listOf(
                            Series("Belt", Color(0xFFF2C255), xs.map { x -> hall.lastOrNull { it.point == "Belt" && it.sno == x }?.ec }, join = false),
                            Series("Block", Color(0xFF8FBF6A), xs.map { x -> hall.lastOrNull { it.point == "Block" && it.sno == x }?.ec }, join = false),
                        ),
                        limits = listOf(Limit("Belt limit", lim.belt, Color(0xFFF2C255)), Limit("Block limit", lim.block, Color(0xFF8FBF6A))),
                    )
                }
            }
            val spots = rows.filter { it.point == "Yard" }.map { it.spot }.distinct()
            items(spots) { s ->
                val pts = rows.filter { it.point == "Yard" && it.spot == s }
                val xs = bySample(pts)
                Card {
                    Text("Yard — $s", style = MaterialTheme.typography.titleMedium)
                    LineChart(labels = xs, series = listOf(Series(s, Color(0xFF7FB6D9), xs.map { x -> pts.lastOrNull { it.sno == x }?.ec }, join = false)),
                        limits = listOf(Limit("Yard limit", lim.yard, Color(0xFF7FB6D9))))
                }
            }
            if (rows.isEmpty()) item { Text("Nothing to chart yet today.", color = Fcp.muted) }
        }
    }
}

/** Sample numbers along the axis, in number order when they are all numbers. */
private fun bySample(rows: List<Reading>): List<String> {
    val xs = rows.map { it.sno }.distinct()
    return if (xs.all { it.toDoubleOrNull() != null }) xs.sortedBy { it.toDouble() } else xs
}
