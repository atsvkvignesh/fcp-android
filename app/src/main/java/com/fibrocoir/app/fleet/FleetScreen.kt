package com.fibrocoir.app.fleet

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fibrocoir.app.core.Js
import com.fibrocoir.app.core.PickedFile
import com.fibrocoir.app.ecviewer.Dropdown
import com.fibrocoir.app.hub.fcpField
import com.fibrocoir.app.ui.Card
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Flow
import com.fibrocoir.app.ui.GhostButton
import com.fibrocoir.app.ui.LoadBox
import com.fibrocoir.app.ui.LocalSay
import com.fibrocoir.app.ui.Pill
import com.fibrocoir.app.ui.PrimaryButton
import com.fibrocoir.app.ui.SectionTitle
import com.fibrocoir.app.ui.Segments
import com.fibrocoir.app.ui.Tag
import com.fibrocoir.app.ui.rememberFilePicker
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------- forms (kept while switching tabs)

data class OdoForm(val id: String = "", val date: String = "", val reading: String = "", val note: String = "", val lower: Boolean = false)
data class FuelForm(val id: String = "", val date: String = "", val odo: String = "", val type: String = "Diesel", val litres: String = "",
    val rate: String = "", val total: String = "", val station: String = "", val note: String = "", val billUrl: String = "", val file: PickedFile? = null)
data class PartRow(val name: String = "", val number: String = "", val qty: String = "", val cost: String = "")
data class SvcForm(val id: String = "", val date: String = "", val odo: String = "", val cat: String = "Routine service", val cost: String = "",
    val summary: String = "", val parts: List<PartRow> = emptyList(), val vendor: String = "", val next: String = "", val nextOdo: String = "",
    val note: String = "", val billUrl: String = "", val file: PickedFile? = null)
data class RemForm(val id: String = "", val name: String = "", val date: String = "", val odo: String = "", val rdays: String = "", val rkm: String = "", val note: String = "")
data class DocForm(val id: String = "", val type: String = "FC", val custom: String = "", val number: String = "", val issue: String = "",
    val exp: String = "", val provider: String = "", val cost: String = "", val note: String = "", val fileUrl: String = "", val file: PickedFile? = null)
data class VehForm(val id: String = "", val reg: String = "", val name: String = "", val type: String = "Lorry", val fuel: String = "Diesel",
    val tank: String = "", val driver: String = "", val odo: String = "", val status: String = "Active", val note: String = "")

class FleetForms {
    val odo = mutableStateOf(OdoForm()); val fuel = mutableStateOf(FuelForm()); val svc = mutableStateOf(SvcForm())
    val rem = mutableStateOf(RemForm()); val doc = mutableStateOf(DocForm()); val veh = mutableStateOf(VehForm())
    val logTab = mutableStateOf("odo"); val svcTab = mutableStateOf("svc")
}

private val FUELS = listOf("Diesel", "Petrol", "CNG", "LPG", "Electric")
private val WORK = listOf("Routine service", "Breakdown repair", "Tyres", "Body work", "Electrical", "Engine", "Brakes and suspension", "Accessory fitting", "Other")
private val TYPES = listOf("Lorry", "Truck", "Tractor", "Trailer", "Loader", "Van", "Car", "Two wheeler", "Forklift", "Other")
private val STATUSES = listOf("Active", "In workshop", "Idle", "Retired")

private fun km(n: Double?) = if (n == null) "—" else NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }.format(n)
private fun FleetView.money(n: Any?): String {
    val v = if (n == null || n == "") return "—" else if (n is Double) n else book.num(n)
    return boot.currency + " " + km(v)
}
private fun human(d: Any?): String {
    val s = Js.str(d).take(10)
    val p = s.split('-'); if (p.size != 3) return Js.str(d)
    val m = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${p[2]} ${m.getOrElse((p[1].toIntOrNull() ?: 1) - 1) { "" }} ${p[0]}"
}
private fun stateColor(s: String) = when (s) {
    "expired", "overdue" -> Fcp.bad
    "critical" -> Color(0xFFFF9F43)
    "due" -> Fcp.fibre
    "missing" -> Fcp.faint
    else -> Fcp.good
}
private fun str(m: Map<String, Any?>, k: String) = Js.str(m[k])
private fun numText(m: Map<String, Any?>, k: String) = m[k].let { if (it is Double) Js.num(it) else Js.str(it) }

private fun navIcon(d: String): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    .build()

private val NAV = listOf(
    FleetTab.BOARD to "M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z",
    FleetTab.LOG to "M12 14.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4M3.5 18.5a9.5 9.5 0 1 1 17 0M13.6 10.4 17.5 6.5",
    FleetTab.SERVICE to "M14.8 6.2a4.2 4.2 0 0 1-5.6 5.6L4 17v3h3l5.2-5.2a4.2 4.2 0 0 1 5.6-5.6l-2.7 2.7-2.6-.4-.4-2.6z",
    FleetTab.DOCS to "M14 3H7.5A1.5 1.5 0 0 0 6 4.5v15A1.5 1.5 0 0 0 7.5 21h9a1.5 1.5 0 0 0 1.5-1.5V7zM14 3v4.5H18M9.5 13h5M9.5 16.5h3.5",
    FleetTab.GARAGE to "M5 17h14M6.5 17v1.5M17.5 17v1.5M4.5 13.5 6 9.2A2 2 0 0 1 7.9 8h8.2a2 2 0 0 1 1.9 1.2l1.5 4.3M4.5 13.5V17h15v-3.5zM7.5 15.2h.01M16.5 15.2h.01",
)

@Composable
fun FleetScreen(onBack: () -> Unit, vm: FleetViewModel = viewModel()) {
    val load by vm.data.state.collectAsState()
    val view by vm.view.collectAsState()
    val tab by vm.tab.collectAsState()
    val forms = remember { FleetForms() }
    val icons = remember { NAV.associate { (t, d) -> t to navIcon(d) } }
    var menu by remember { mutableStateOf(false) }
    FcpScreen(
        title = tab.title,
        subtitle = view?.boot?.company,
        onBack = onBack,
        load = load,
        onRefresh = vm::refresh,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Garage") }, onClick = { menu = false; vm.tab.value = FleetTab.GARAGE })
                    DropdownMenuItem(text = { Text("Reminder settings") }, onClick = { menu = false; vm.tab.value = FleetTab.SETTINGS })
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Fcp.husk2) {
                NAV.forEach { (t, _) ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { vm.tab.value = t },
                        icon = { Icon(icons[t]!!, t.title) }, label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Fcp.husk, selectedTextColor = Fcp.fibre,
                            indicatorColor = Fcp.fibre, unselectedIconColor = Fcp.muted, unselectedTextColor = Fcp.muted),
                    )
                }
            }
        },
    ) {
        LoadBox(load, onRetry = vm::refresh) {
            val v = view ?: return@LoadBox
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    FleetTab.BOARD -> Board(v, vm, forms)
                    FleetTab.LOG -> LogTab(v, vm, forms)
                    FleetTab.SERVICE -> ServiceTab(v, vm, forms)
                    FleetTab.DOCS -> DocsTab(v, vm, forms)
                    FleetTab.GARAGE -> GarageTab(v, vm, forms)
                    FleetTab.SETTINGS -> SettingsTab(v, vm)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------- board

@Composable
private fun Board(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val b = v.boot
    var over = 0; var soon = 0
    b.compliance.forEach { c -> c.types.forEach { t -> when (c.docs[t]?.state) { "expired" -> over++; "critical" -> soon++ } } }
    b.serviceReminders.forEach { r -> when (r.state) { "overdue" -> over++; "critical" -> soon++ } }
    Text(when {
        over > 0 -> "$over ${if (over == 1) "thing needs" else "things need"} attention right now."
        soon > 0 -> "$soon ${if (soon == 1) "item is" else "items are"} coming up shortly."
        else -> "Everything is in order."
    }, style = MaterialTheme.typography.titleMedium, color = if (over > 0) Fcp.bad else Fcp.cream)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.weight(1f), padding = 12.dp) {
            Text(v.money(b.stats.fuelCost), style = MaterialTheme.typography.titleLarge, color = Fcp.cream)
            Text("Fuel this month", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        }
        Card(Modifier.weight(1f), padding = 12.dp) {
            Text(v.money(b.stats.serviceCost), style = MaterialTheme.typography.titleLarge, color = Fcp.cream)
            Text("Service this month", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        }
    }
    val due = b.serviceReminders.filter { it.state != "ok" }
    if (due.isNotEmpty()) {
        SectionTitle("Service due")
        due.forEach { r ->
            Card(padding = 12.dp, onClick = { forms.svcTab.value = "rem"; vm.tab.value = FleetTab.SERVICE }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(r.state)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.regNo, style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                        Text(r.name, style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
                    }
                    Text(remWhen(r), style = MaterialTheme.typography.labelMedium, color = stateColor(r.state))
                }
            }
        }
    }
    SectionTitle("Vehicles and papers")
    if (b.compliance.isEmpty()) Card { Text("No vehicles yet. Add one from the Garage and its papers appear here.", color = Fcp.muted) }
    b.compliance.forEach { c ->
        val veh = v.vehicle(c.vehicleId)
        Card(padding = 12.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(c.regNo, style = MaterialTheme.typography.titleMedium, color = Fcp.cream, fontWeight = FontWeight.Bold)
                    if (c.name.isNotEmpty()) Text(c.name, style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (veh != null && veh.odo != 0.0) "${km(veh.odo)} km" else "No reading", style = MaterialTheme.typography.labelLarge, color = Fcp.cream)
                    if (veh != null && veh.odo != 0.0) Text(human(veh.lastOdoDate), style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
                }
            }
            Spacer(Modifier.height(8.dp))
            Flow(gap = 6.dp) {
                c.types.forEach { t ->
                    val d = c.docs[t] ?: return@forEach
                    val col = stateColor(d.state)
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = 0.16f))
                            .clickable {
                                vm.setPick("doc", c.vehicleId)
                                forms.doc.value = DocForm(type = t, number = if (!d.missing) d.docNumber else "")
                                vm.tab.value = FleetTab.DOCS
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(t, style = MaterialTheme.typography.labelLarge, color = col, fontWeight = FontWeight.Bold)
                        Text(if (d.missing) "Not set" else if ((d.days ?: 0) < 0) "${km(-(d.days ?: 0).toDouble())}d over" else "${km((d.days ?: 0).toDouble())} days",
                            style = MaterialTheme.typography.labelSmall, color = Fcp.cream)
                    }
                }
            }
        }
    }
}

private fun remWhen(r: SvcReminder): String {
    val bits = ArrayList<String>()
    r.days?.let { bits += if (it < 0) "${km(-it.toDouble())}d late" else "in ${km(it.toDouble())}d" }
    r.km?.let { bits += if (it < 0) "${km(-it)} km past" else "in ${km(it)} km" }
    return bits.joinToString(" · ").ifEmpty { "—" }
}

// ---------------------------------------------------------------------- small pieces

@Composable
private fun VehiclePick(form: String, v: FleetView, vm: FleetViewModel): String? {
    val picks by vm.pick.collectAsState()
    val cur = picks[form]?.takeIf { id -> v.live.any { it.id == id } } ?: v.live.firstOrNull()?.id
    if (v.live.isEmpty()) { Text("Add a vehicle first.", color = Fcp.muted); return null }
    Flow(gap = 8.dp) { v.live.forEach { veh -> Pill(veh.regNo + if (veh.name.isNotEmpty()) " · ${veh.name}" else "", veh.id == cur) { vm.setPick(form, veh.id) } } }
    return cur
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val utc = TimeZone.getTimeZone("UTC")
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Fcp.husk2).clickable { open = true }.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
        Text(if (value.isEmpty()) "—" else human(value), style = MaterialTheme.typography.bodyLarge, color = Fcp.cream)
    }
    if (open) {
        val f = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc } }
        val state = rememberDatePickerState(initialSelectedDateMillis = value.takeIf { it.isNotEmpty() }?.let { f.parse(it)?.time })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(state.selectedDateMillis?.let { f.format(Date(it)) } ?: ""); open = false }) { Text("OK", color = Fcp.fibre) } },
            dismissButton = { TextButton(onClick = { onChange(""); open = false }) { Text("Clear") } },
        ) { DatePicker(state, showModeToggle = false) }
    }
}

@Composable
private fun Attach(label: String, name: String, file: PickedFile?, existing: String, onPick: (PickedFile?) -> Unit) {
    val say = LocalSay.current
    val picker = rememberFilePicker(name, { f, _ -> onPick(f) }) { say(it) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fcp.husk2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        Text(when {
            file != null -> "📎 ${file.name} · ${file.bytes.size / 1024} KB (sent when you save)"
            existing.isNotEmpty() -> "📎 already attached"
            else -> "Nothing attached"
        }, style = MaterialTheme.typography.bodySmall, color = Fcp.cream)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Take photo", Modifier.weight(1f)) { picker.camera() }
            GhostButton("Choose file", Modifier.weight(1f)) { picker.document() }
            if (file != null) GhostButton("Remove", Modifier.weight(0.8f), color = Fcp.muted) { onPick(null) }
        }
    }
}

@Composable
private fun Entry(reg: String, date: String, body: @Composable () -> Unit, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?, link: String = "", linkLabel: String = "") {
    val ctx = LocalContext.current
    Card(padding = 12.dp) {
        Row {
            Text(reg, style = MaterialTheme.typography.titleSmall, color = Fcp.cream, modifier = Modifier.weight(1f))
            Text(human(date), style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
        }
        Spacer(Modifier.height(4.dp))
        body()
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (link.isNotEmpty()) Text(linkLabel, color = Fcp.info, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: Exception) { }
            })
            if (onEdit != null) Text("Edit", color = Fcp.fibre, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onEdit))
            if (onDelete != null) Text("Delete", color = Fcp.bad, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onDelete))
        }
    }
}

@Composable private fun Line(text: String, strong: Boolean = false) =
    Text(text, style = if (strong) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, color = if (strong) Fcp.cream else Fcp.muted,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)

@Composable private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Fcp.faint)

/** Asks before deleting. */
@Composable
private fun rememberConfirm(): MutableState<Pair<String, () -> Unit>?> {
    val ask = remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    ask.value?.let { (q, yes) ->
        AlertDialog(onDismissRequest = { ask.value = null }, containerColor = Fcp.husk2,
            title = { Text(q) },
            confirmButton = { TextButton(onClick = { ask.value = null; yes() }) { Text("Delete", color = Fcp.bad) } },
            dismissButton = { TextButton(onClick = { ask.value = null }) { Text("Cancel") } })
    }
    return ask
}

private val num = KeyboardType.Decimal

// ---------------------------------------------------------------------- log: odometer and fuel

@Composable
private fun LogTab(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    var t by forms.logTab
    Segments(listOf("odo" to "Odometer", "fuel" to "Refuelling"), t, { t = it })
    if (t == "odo") OdoForm(v, vm, forms) else FuelFormView(v, vm, forms)
}

@Composable
private fun OdoForm(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    var f by forms.odo
    val confirm = rememberConfirm()
    val vid = VehiclePick("odo", v, vm)
    val date = f.date.ifEmpty { v.boot.today }
    DateField("Date", date) { f = f.copy(date = it) }
    fcpField(f.reading, { f = f.copy(reading = it) }, "Reading (km)", keyboard = KeyboardType.Number)
    fcpField(f.note, { f = f.copy(note = it) }, "Note", placeholder = "Trip, driver, anything worth keeping")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(f.lower, { f = f.copy(lower = it) }, colors = CheckboxDefaults.colors(checkedColor = Fcp.fibre))
        Text("Allow a lower reading", color = Fcp.cream)
    }
    v.vehicle(vid)?.let { veh -> Note(if (veh.odo != 0.0) "Last recorded ${km(veh.odo)} km on ${human(veh.lastOdoDate)}." else "No reading recorded for this vehicle yet.") }
    PrimaryButton(if (f.id.isEmpty()) "Save reading" else "Update reading", Modifier.fillMaxWidth(), busy = busy) {
        if (vid == null) return@PrimaryButton say("Pick a vehicle first.")
        if (f.reading.isBlank()) return@PrimaryButton say("Enter the reading in km.")
        val x = f
        vm.save(if (x.id.isEmpty()) "Reading saved." else "Reading updated.", { m, bad -> say(m); if (!bad) f = OdoForm() }) { b ->
            b.saveOdometer(x.id, vid, date, x.reading, x.note, x.lower)
        }
    }
    SectionTitle("Recent readings")
    if (v.odo.isEmpty()) Note("The first entry you save shows up here.")
    v.odo.forEach { r ->
        Entry(str(r, "RegNo"), str(r, "Date"), {
            Line("${km(v.book.num(r["Reading"]))} km" + (if (str(r, "KmSinceLast").isNotEmpty()) " · ${km(v.book.num(r["KmSinceLast"]))} km since last" else ""), true)
            if (str(r, "Notes").isNotEmpty()) Line(str(r, "Notes"))
        }, onEdit = {
            vm.setPick("odo", str(r, "VehicleID"))
            f = OdoForm(str(r, "LogID"), str(r, "Date"), numText(r, "Reading"), str(r, "Notes"), true)
        }, onDelete = {
            confirm.value = "Delete this reading? It is removed from the sheet for good." to {
                vm.save("Deleted.", { m, _ -> say(m) }) { b -> b.deleteLog("odo", str(r, "LogID")) }
            }
        })
    }
}

@Composable
private fun FuelFormView(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    var f by forms.fuel
    val confirm = rememberConfirm()
    val vid = VehiclePick("fuel", v, vm)
    val date = f.date.ifEmpty { v.boot.today }
    fun calc(n: FuelForm): FuelForm {
        val l = n.litres.toDoubleOrNull(); val r = n.rate.toDoubleOrNull()
        return if (l != null && r != null && l != 0.0 && r != 0.0) n.copy(total = Js.num(Math.round(l * r * 100) / 100.0)) else n
    }
    DateField("Date", date) { f = f.copy(date = it) }
    fcpField(f.odo, { f = f.copy(odo = it) }, "Odometer (km)", keyboard = KeyboardType.Number)
    Text("Fuel", style = MaterialTheme.typography.labelLarge, color = Fcp.muted)
    val fuelType = f.type.ifEmpty { v.vehicle(vid)?.fuelType ?: "Diesel" }
    Flow(gap = 8.dp) { FUELS.forEach { x -> Pill(x, x == fuelType) { f = f.copy(type = x) } } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fcpField(f.litres, { f = calc(f.copy(litres = it)) }, "Litres", Modifier.weight(1f), keyboard = num)
        fcpField(f.rate, { f = calc(f.copy(rate = it)) }, "Rate / litre", Modifier.weight(1f), keyboard = num)
    }
    fcpField(f.total, { f = f.copy(total = it) }, "Total paid", keyboard = num)
    fcpField(f.station, { f = f.copy(station = it) }, "Filling station", placeholder = "Optional")
    Attach("Bill photo or PDF", "bill", f.file, f.billUrl) { f = f.copy(file = it) }
    fcpField(f.note, { f = f.copy(note = it) }, "Note", placeholder = "Optional")
    Note("Enter the odometer here and the app works out km per litre against your last fill.")
    PrimaryButton(if (f.id.isEmpty()) "Save refuelling" else "Update refuelling", Modifier.fillMaxWidth(), busy = busy) {
        if (vid == null) return@PrimaryButton say("Pick a vehicle first.")
        if (f.litres.isBlank()) return@PrimaryButton say("Enter how many litres went in.")
        val x = f
        vm.save(if (x.id.isEmpty()) "Refuelling saved." else "Refuelling updated.", { m, bad -> say(m); if (!bad) f = FuelForm() }) { b ->
            val url = if (x.file != null) upload(b, x.file, "Bills", b.fuelLabel(vid, date)) else x.billUrl
            b.saveFuel(x.id, vid, date, x.odo, fuelType, x.litres, x.rate, x.total, x.station, x.note, url)
        }
    }
    SectionTitle("Recent fills")
    if (v.fuel.isEmpty()) Note("The first entry you save shows up here.")
    v.fuel.forEach { r ->
        Entry(str(r, "RegNo"), str(r, "Date"), {
            Line("${numText(r, "Litres")} L ${str(r, "FuelType")} · ${v.money(r["TotalCost"])}" + (if (str(r, "RatePerLitre").isNotEmpty()) " at ${v.money(r["RatePerLitre"])}/L" else ""), true)
            if (str(r, "Mileage").isNotEmpty()) Line("${numText(r, "Mileage")} km/L over that tank")
            if (str(r, "Station").isNotEmpty()) Line(str(r, "Station"))
        }, onEdit = {
            vm.setPick("fuel", str(r, "VehicleID"))
            f = FuelForm(str(r, "LogID"), str(r, "Date"), numText(r, "Odometer"), str(r, "FuelType").ifEmpty { "Diesel" }, numText(r, "Litres"),
                numText(r, "RatePerLitre"), numText(r, "TotalCost"), str(r, "Station"), str(r, "Notes"), str(r, "BillURL"))
        }, onDelete = {
            confirm.value = "Delete this fill? It is removed from the sheet for good." to {
                vm.save("Deleted.", { m, _ -> say(m) }) { b -> b.deleteLog("fuel", str(r, "LogID")) }
            }
        }, link = str(r, "BillURL"), linkLabel = "Bill")
    }
}

// ---------------------------------------------------------------------- service

@Composable
private fun ServiceTab(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    var t by forms.svcTab
    Segments(listOf("svc" to "Work done", "rem" to "Reminders"), t, { t = it })
    if (t == "svc") WorkForm(v, vm, forms) else Reminders(v, vm, forms)
}

@Composable
private fun WorkForm(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    var f by forms.svc
    val confirm = rememberConfirm()
    val vid = VehiclePick("svc", v, vm)
    val date = f.date.ifEmpty { v.boot.today }
    DateField("Date", date) { f = f.copy(date = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fcpField(f.odo, { f = f.copy(odo = it) }, "Odometer (km)", Modifier.weight(1f), keyboard = KeyboardType.Number)
        fcpField(f.cost, { f = f.copy(cost = it) }, "Total cost", Modifier.weight(1f), keyboard = num)
    }
    Dropdown("Type of work", f.cat, WORK.map { it to it }, Modifier.fillMaxWidth()) { f = f.copy(cat = it) }
    fcpField(f.summary, { f = f.copy(summary = it) }, "What was done", singleLine = false, placeholder = "Oil and filter changed, front brake pads replaced.")
    SectionTitle("Parts replaced")
    f.parts.forEachIndexed { i, p ->
        Card(padding = 10.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fcpField(p.name, { x -> f = f.copy(parts = f.parts.toMutableList().also { it[i] = p.copy(name = x) }) }, "Part", Modifier.weight(1.4f))
                fcpField(p.number, { x -> f = f.copy(parts = f.parts.toMutableList().also { it[i] = p.copy(number = x) }) }, "Part no.", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                fcpField(p.qty, { x -> f = f.copy(parts = f.parts.toMutableList().also { it[i] = p.copy(qty = x) }) }, "Qty", Modifier.weight(1f), keyboard = num)
                fcpField(p.cost, { x -> f = f.copy(parts = f.parts.toMutableList().also { it[i] = p.copy(cost = x) }) }, "Cost", Modifier.weight(1f), keyboard = num)
                TextButton(onClick = { f = f.copy(parts = f.parts.filterIndexed { j, _ -> j != i }) }) { Text("Remove", color = Fcp.bad) }
            }
        }
    }
    GhostButton("Add a part", Modifier.fillMaxWidth(), color = Fcp.fibre) { f = f.copy(parts = f.parts + PartRow()) }
    Note("Part numbers are saved to their own tab, so you can search one across the whole fleet later.")
    SectionTitle("Details")
    fcpField(f.vendor, { f = f.copy(vendor = it) }, "Workshop", placeholder = "Optional")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField("Next due on", f.next, Modifier.weight(1f)) { f = f.copy(next = it) }
        fcpField(f.nextOdo, { f = f.copy(nextOdo = it) }, "Next due at (km)", Modifier.weight(1f), keyboard = KeyboardType.Number)
    }
    Attach("Bill photo or PDF", "bill", f.file, f.billUrl) { f = f.copy(file = it) }
    fcpField(f.note, { f = f.copy(note = it) }, "Note", placeholder = "Optional")
    PrimaryButton(if (f.id.isEmpty()) "Save service record" else "Update service record", Modifier.fillMaxWidth(), busy = busy) {
        if (vid == null) return@PrimaryButton say("Pick a vehicle first.")
        if (f.summary.isBlank()) return@PrimaryButton say("Write a line on what was done.")
        val x = f
        vm.save(if (x.id.isEmpty()) "Service record saved." else "Service record updated.", { m, bad -> say(m); if (!bad) f = SvcForm() }) { b ->
            val url = if (x.file != null) upload(b, x.file, "Bills", b.serviceLabel(vid, date)) else x.billUrl
            b.saveService(x.id, vid, date, x.odo, x.cat, x.summary, x.cost, x.vendor, x.next, x.nextOdo, x.note,
                x.parts.filter { it.name.isNotEmpty() || it.number.isNotEmpty() }.map { Part(it.name, it.number, it.qty, it.cost) }, url)
        }
    }
    SectionTitle("Recent work")
    if (v.service.isEmpty()) Note("The first entry you save shows up here.")
    v.service.forEach { r ->
        Entry(str(r, "RegNo"), str(r, "Date"), {
            Line("${str(r, "Category")} · ${v.money(r["Cost"])}", true)
            Line(str(r, "Summary"))
            if (str(r, "PartsSummary").isNotEmpty()) Line("Parts: " + str(r, "PartsSummary"))
            if (str(r, "Vendor").isNotEmpty()) Line(str(r, "Vendor"))
            if (str(r, "NextServiceDate").isNotEmpty()) Line("Next due " + human(str(r, "NextServiceDate")))
        }, onEdit = {
            vm.setPick("svc", str(r, "VehicleID"))
            @Suppress("UNCHECKED_CAST")
            val parts = (r["parts"] as? List<Map<String, Any?>>).orEmpty().map { p ->
                PartRow(Js.str(p["name"]), Js.str(p["number"]), numText(p, "qty"), numText(p, "cost"))
            }
            f = SvcForm(str(r, "ServiceID"), str(r, "Date"), numText(r, "Odometer"), str(r, "Category").ifEmpty { "Other" }, numText(r, "Cost"),
                str(r, "Summary"), parts, str(r, "Vendor"), str(r, "NextServiceDate"), numText(r, "NextServiceOdo"), str(r, "Notes"), str(r, "BillURL"))
        }, onDelete = {
            confirm.value = "Delete this service record and its parts?" to {
                vm.save("Deleted.", { m, _ -> say(m) }) { b -> b.deleteLog("service", str(r, "ServiceID")) }
            }
        }, link = str(r, "BillURL"), linkLabel = "Bill")
    }
}

@Composable
private fun Reminders(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    var f by forms.rem
    val confirm = rememberConfirm()
    val open = v.boot.serviceReminders
    if (open.isEmpty()) Card { Text("Nothing scheduled. Add a reminder below and name it whatever you call it.", color = Fcp.muted) }
    else SectionTitle("Open")
    open.forEach { r ->
        val repeat = listOfNotNull(r.repeatDays?.let { "${km(it)} days" }, r.repeatKm?.let { "${km(it)} km" }).joinToString(" or ")
        Card(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(r.state)))
                Spacer(Modifier.width(8.dp))
                Text(r.name, style = MaterialTheme.typography.titleSmall, color = Fcp.cream, modifier = Modifier.weight(1f))
                Text(r.regNo, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
            }
            Line(remWhen(r) + (if (r.dueDate.isNotEmpty()) " · due ${human(r.dueDate)}" else "") + (r.dueOdo?.let { " · at ${km(it)} km" } ?: ""))
            if (repeat.isNotEmpty()) Line("Repeats every $repeat")
            if (r.notes.isNotEmpty()) Line(r.notes)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("Mark done", color = Fcp.good, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                    vm.save("Marked done.", { m, _ -> say(m) }) { b -> if (b.completeServiceReminder(r.id)) say("Done. The next one is scheduled.") }
                })
                Text("Edit", color = Fcp.fibre, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                    vm.setPick("rem", r.vehicleId)
                    f = RemForm(r.id, r.name, r.dueDate, r.dueOdo?.let(Js::num).orEmpty(), r.repeatDays?.let(Js::num).orEmpty(), r.repeatKm?.let(Js::num).orEmpty(), r.notes)
                })
                Text("Delete", color = Fcp.bad, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                    confirm.value = "Delete this reminder?" to { vm.save("Deleted.", { m, _ -> say(m) }) { b -> b.deleteServiceReminder(r.id) } }
                })
            }
        }
    }
    SectionTitle(if (f.id.isEmpty()) "New reminder" else "Edit reminder")
    val vid = VehiclePick("rem", v, vm)
    fcpField(f.name, { f = f.copy(name = it) }, "What needs doing", placeholder = "Call it whatever you call it")
    val names = open.map { it.name }.distinct().filter { it.isNotEmpty() && it != f.name }
    if (names.isNotEmpty() && f.name.isEmpty()) Flow(gap = 6.dp) { names.forEach { n -> Pill(n, false) { f = f.copy(name = n) } } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField("Due on", f.date, Modifier.weight(1f)) { f = f.copy(date = it) }
        fcpField(f.odo, { f = f.copy(odo = it) }, "Due at (km)", Modifier.weight(1f), keyboard = KeyboardType.Number)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fcpField(f.rdays, { f = f.copy(rdays = it) }, "Repeat every (days)", Modifier.weight(1f), keyboard = KeyboardType.Number)
        fcpField(f.rkm, { f = f.copy(rkm = it) }, "Repeat every (km)", Modifier.weight(1f), keyboard = KeyboardType.Number)
    }
    fcpField(f.note, { f = f.copy(note = it) }, "Note", placeholder = "Optional")
    Note("A date, an odometer figure, or both — whichever arrives first is what you get warned about. Fill in a repeat and marking it done schedules the next one for you.")
    PrimaryButton(if (f.id.isEmpty()) "Add reminder" else "Update reminder", Modifier.fillMaxWidth(), busy = busy) {
        if (vid == null) return@PrimaryButton say("Pick a vehicle first.")
        if (f.name.isBlank()) return@PrimaryButton say("Give it a name so you know what it is.")
        if (f.date.isEmpty() && f.odo.isBlank()) return@PrimaryButton say("Set a date, an odometer figure, or both.")
        val x = f
        vm.save(if (x.id.isEmpty()) "Reminder added." else "Reminder updated.", { m, bad -> say(m); if (!bad) f = RemForm() }) { b ->
            b.saveServiceReminder(x.id, vid, x.name, x.date, x.odo, x.rdays, x.rkm, x.note)
        }
    }
    SectionTitle("Completed")
    if (v.history.isEmpty()) Note("Nothing completed yet.")
    v.history.forEach { (_, reg, rest) ->
        Card(padding = 12.dp) {
            Row { Text(rest.first, color = Fcp.cream, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(human(rest.second), color = Fcp.muted, style = MaterialTheme.typography.labelMedium) }
            Line(reg)
        }
    }
}

// ---------------------------------------------------------------------- papers

@Composable
private fun DocsTab(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    var f by forms.doc
    val confirm = rememberConfirm()
    Note("FC, insurance, tax and pollution are watched for every vehicle. Add anything else you need to keep an eye on.")
    val vid = VehiclePick("doc", v, vm)
    val known = v.boot.docTypes
    val typeValue = if (f.type in known) f.type else "Other"
    Dropdown("Document", typeValue, known.map { it to (FleetBook.DOC_LABELS[it] ?: it) } + ("Other" to "Something else…"), Modifier.fillMaxWidth()) {
        f = f.copy(type = it, custom = if (it == "Other") f.custom else "")
    }
    if (typeValue == "Other") fcpField(if (f.type !in known && f.type != "Other") f.type else f.custom,
        { f = f.copy(type = "Other", custom = it) }, "Name of this document", placeholder = "Permit, green card, RC book…")
    fcpField(f.number, { f = f.copy(number = it) }, "Number", placeholder = "Certificate or policy no.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField("Issued on", f.issue, Modifier.weight(1f)) { f = f.copy(issue = it) }
        DateField("Valid until", f.exp, Modifier.weight(1f)) { f = f.copy(exp = it) }
    }
    fcpField(f.provider, { f = f.copy(provider = it) }, "Issued by", placeholder = "RTO, insurer…")
    fcpField(f.cost, { f = f.copy(cost = it) }, "Amount paid", keyboard = num)
    Attach("Certificate scan or photo", "document", f.file, f.fileUrl) { f = f.copy(file = it) }
    fcpField(f.note, { f = f.copy(note = it) }, "Note", placeholder = "Optional")
    Note("The valid-until date is what drives every reminder, so get that one right.")
    PrimaryButton(if (f.id.isEmpty()) "Save document" else "Update document", Modifier.fillMaxWidth(), busy = busy) {
        if (vid == null) return@PrimaryButton say("Pick a vehicle first.")
        val type = if (typeValue == "Other") "Other" else typeValue
        val custom = if (typeValue == "Other") (if (f.type !in known && f.type != "Other") f.type else f.custom) else ""
        if (type == "Other" && custom.isBlank()) return@PrimaryButton say("Type a name for this document.")
        if (f.exp.isEmpty()) return@PrimaryButton say("The valid-until date is what triggers reminders. Add it.")
        val x = f
        vm.save(if (x.id.isEmpty()) "Saved. Reminders are set." else "Document updated.", { m, bad -> say(m); if (!bad) f = DocForm() }) { b ->
            val (_, label) = b.checkDocument(vid, type, custom, x.exp)
            val url = if (x.file != null) upload(b, x.file, "Documents", label) else x.fileUrl
            b.saveDocument(x.id, vid, type, custom, x.number, x.issue, x.exp, x.provider, x.cost, x.note, url)
        }
    }
    SectionTitle("On file")
    if (v.docs.isEmpty()) Note("The first document you save shows up here.")
    v.docs.forEach { r ->
        Entry(str(r, "RegNo"), str(r, "ExpiryDate"), {
            Line(str(r, "DocType") + (if (str(r, "DocNumber").isNotEmpty()) " · " + str(r, "DocNumber") else ""), true)
            Line("Valid until ${human(str(r, "ExpiryDate"))}" + (if (str(r, "Provider").isNotEmpty()) " · " + str(r, "Provider") else ""))
        }, onEdit = {
            vm.setPick("doc", str(r, "VehicleID"))
            val t = str(r, "DocType")
            f = DocForm(str(r, "DocID"), if (t in known) t else "Other", if (t in known) "" else t, str(r, "DocNumber"), str(r, "IssueDate"),
                str(r, "ExpiryDate"), str(r, "Provider"), numText(r, "Cost"), str(r, "Notes"), str(r, "FileURL"))
        }, onDelete = {
            confirm.value = "Delete this document? It is removed from the sheet for good." to {
                vm.save("Deleted.", { m, _ -> say(m) }) { b -> b.deleteLog("docs", str(r, "DocID")) }
            }
        }, link = str(r, "FileURL"), linkLabel = "Certificate")
    }
}

// ---------------------------------------------------------------------- garage

@Composable
private fun GarageTab(v: FleetView, vm: FleetViewModel, forms: FleetForms) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    var f by forms.veh
    val confirm = rememberConfirm()
    Text(if (f.id.isEmpty()) "Add a vehicle" else f.reg, style = MaterialTheme.typography.titleLarge, color = Fcp.cream)
    fcpField(f.reg, { f = f.copy(reg = it.uppercase()) }, "Registration", placeholder = "TN 41 AB 1234")
    fcpField(f.name, { f = f.copy(name = it) }, "Known as", placeholder = "Coco peat lorry")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown("Type", f.type, TYPES.map { it to it }, Modifier.weight(1f)) { f = f.copy(type = it) }
        Dropdown("Fuel", f.fuel, FUELS.map { it to it }, Modifier.weight(1f)) { f = f.copy(fuel = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fcpField(f.tank, { f = f.copy(tank = it) }, "Tank size (litres)", Modifier.weight(1f), keyboard = num)
        if (f.id.isEmpty()) fcpField(f.odo, { f = f.copy(odo = it) }, "Opening odometer", Modifier.weight(1f), keyboard = KeyboardType.Number)
    }
    fcpField(f.driver, { f = f.copy(driver = it) }, "Regular driver", placeholder = "Optional")
    Dropdown("Status", f.status, STATUSES.map { it to it }, Modifier.fillMaxWidth()) { f = f.copy(status = it) }
    fcpField(f.note, { f = f.copy(note = it) }, "Note", placeholder = "Optional")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(if (f.id.isEmpty()) "Save vehicle" else "Update vehicle", Modifier.weight(1f), busy = busy) {
            if (f.reg.isBlank()) return@PrimaryButton say("The registration number is required.")
            val x = f
            val reg = x.reg.trim().uppercase()
            vm.save(if (x.id.isEmpty()) "$reg is on the fleet." else "Vehicle updated.", { m, bad -> say(m); if (!bad) f = VehForm() }) { b ->
                b.saveVehicle(x.id, reg, x.name, x.type, x.fuel, x.tank, x.driver, x.odo, x.status, x.note)
            }
        }
        GhostButton("Clear", Modifier.width(96.dp)) { f = VehForm() }
    }
    SectionTitle("Your fleet")
    if (v.boot.vehicles.isEmpty()) Note("Fill in the form above to add your first vehicle.")
    v.boot.vehicles.forEach { veh ->
        Card(padding = 12.dp) {
            Row { Text(veh.regNo, style = MaterialTheme.typography.titleSmall, color = Fcp.cream, modifier = Modifier.weight(1f)); Tag(veh.status, if (veh.status == "Active") Fcp.good else Fcp.muted) }
            Line(listOf(veh.name, veh.type, veh.fuelType).filter { it.isNotEmpty() }.joinToString(" · "))
            if (veh.driver.isNotEmpty()) Line("Driver: " + veh.driver)
            if (veh.odo != 0.0) Line("${km(veh.odo)} km as of ${human(veh.lastOdoDate)}")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("Edit", color = Fcp.fibre, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                    f = VehForm(veh.id, veh.regNo, veh.name, veh.type.ifEmpty { "Lorry" }, veh.fuelType.ifEmpty { "Diesel" },
                        if (veh.tank != 0.0) Js.num(veh.tank) else "", veh.driver, if (veh.odo != 0.0) Js.num(veh.odo) else "", veh.status.ifEmpty { "Active" }, veh.notes)
                })
                Text("Delete", color = Fcp.bad, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable {
                    confirm.value = "Remove ${veh.regNo}? Its past logs stay in the sheet." to {
                        vm.save("Vehicle removed.", { m, _ -> say(m) }) { b -> b.deleteVehicle(veh.id) }
                    }
                })
            }
        }
    }
}

// ---------------------------------------------------------------------- reminder settings

@Composable
private fun SettingsTab(v: FleetView, vm: FleetViewModel) {
    val say = LocalSay.current
    val busy by vm.busy.collectAsState()
    val s = v.boot.settings
    var rec by remember(s) { mutableStateOf(s["recipients"].orEmpty()) }
    var days by remember(s) { mutableStateOf(s["reminderDays"].orEmpty()) }
    var kmw by remember(s) { mutableStateOf(s["serviceKmWarn"].orEmpty()) }
    var exp by remember(s) { mutableStateOf(s["expiredRepeat"].orEmpty().ifEmpty { "DAILY" }) }
    var nudge by remember(s) { mutableStateOf(s["odoNudge"].orEmpty().ifEmpty { "WEEKLY" }) }
    var stale by remember(s) { mutableStateOf(s["odoStaleDays"].orEmpty()) }
    SectionTitle("Who gets them")
    fcpField(rec, { rec = it }, "Email addresses, comma separated", singleLine = false)
    Note("Emails are sent by the Fleet script every day at ${s["reminderHour"].orEmpty().ifEmpty { "7" }}:00, from the Google account that owns the sheet.")
    SectionTitle("Timing")
    fcpField(days, { days = it }, "Warn days ahead", placeholder = "60,30,15,7,3,1,0")
    fcpField(kmw, { kmw = it }, "Warn km ahead", placeholder = "1000,500,200,0")
    Text("Chase overdue", style = MaterialTheme.typography.labelLarge, color = Fcp.muted)
    Flow(gap = 8.dp) { listOf("DAILY", "WEEKLY", "OFF").forEach { x -> Pill(x.lowercase().replaceFirstChar(Char::uppercase), exp == x) { exp = x } } }
    Text("Chase missing readings", style = MaterialTheme.typography.labelLarge, color = Fcp.muted)
    Flow(gap = 8.dp) { listOf("DAILY", "WEEKLY", "OFF").forEach { x -> Pill(x.lowercase().replaceFirstChar(Char::uppercase), nudge == x) { nudge = x } } }
    fcpField(stale, { stale = it }, "Reading stale after (days)", keyboard = KeyboardType.Number)
    PrimaryButton("Save settings", Modifier.fillMaxWidth(), busy = busy) {
        vm.save("Settings saved.", { m, _ -> say(m) }) { b ->
            b.saveSettings(mapOf("recipients" to rec, "reminderDays" to days, "serviceKmWarn" to kmw, "expiredRepeat" to exp, "odoNudge" to nudge, "odoStaleDays" to stale))
        }
    }
}
