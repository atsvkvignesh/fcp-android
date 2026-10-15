package com.fibrocoir.app.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.fibrocoir.app.hub.fcpField
import com.fibrocoir.app.ui.Card
import com.fibrocoir.app.ui.ChoiceRow
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Flow
import com.fibrocoir.app.ui.GhostButton
import com.fibrocoir.app.ui.LoadBox
import com.fibrocoir.app.ui.Message
import com.fibrocoir.app.ui.Pill
import com.fibrocoir.app.ui.PhotoSlot
import com.fibrocoir.app.ui.PrimaryButton
import com.fibrocoir.app.ui.ScreenPad
import com.fibrocoir.app.ui.SectionTitle
import com.fibrocoir.app.ui.Tag

private fun hex(s: String, fallback: Color = Color(0xFF5A6B78)): Color = try {
    Color(android.graphics.Color.parseColor(s.trim().let { if (it.startsWith("#")) it else "#$it" }))
} catch (_: Exception) { fallback }

private fun InvData.colour(region: String) = hex(region(region)?.colour ?: "")

@Composable
fun InventoryScreen(onBack: () -> Unit, vm: InventoryViewModel = viewModel()) {
    val load by vm.data.state.collectAsState()
    val stack by vm.stack.collectAsState()
    val page = stack.last()
    BackHandler { if (!vm.back()) onBack() }

    val d = load.data
    val title = when (page) {
        InvPage.SEARCH -> d?.company ?: "Stores"
        InvPage.ITEM -> vm.itemId.collectAsState().value ?: "Item"
        InvPage.PERSON -> personTitle(vm)
        InvPage.QTY -> when (vm.act.collectAsState().value) { "TAKE" -> "How many taken?"; "RETURN" -> "How many returned?"; else -> "How many received?" }
        InvPage.MACHINE -> "Which machine?"
        InvPage.PIN -> "Enter PIN"
        InvPage.CONFIRM -> "Check and confirm"
        InvPage.DONE -> ""
        InvPage.NEW -> "New item"
        InvPage.DUPES -> "Is it one of these?"
        InvPage.EDIT -> "Edit item"
        InvPage.PEOPLE -> "People"
    }
    FcpScreen(
        title = title,
        subtitle = if (page == InvPage.SEARCH) "Tools & spares" else null,
        onBack = { if (!vm.back()) onBack() },
        load = if (page == InvPage.SEARCH) load else null,
        onRefresh = if (page == InvPage.SEARCH) vm::refresh else null,
        actions = {
            if (page == InvPage.SEARCH && d != null) {
                IconButton(onClick = vm::openNew) { Icon(Icons.Filled.Add, "New item") }
                IconButton(onClick = vm::openPeople) { Icon(Icons.Filled.Person, "People") }
            }
        },
    ) {
        var sheetSet by remember { mutableStateOf(com.fibrocoir.app.core.FcpSheets.isSet(com.fibrocoir.app.core.FcpSheets.INVENTORY)) }
        if (!sheetSet) {
            Column(Modifier.padding(16.dp)) {
                com.fibrocoir.app.hub.SheetLinkSetup("Which sheet is the store?", "Paste the link of the Stores inventory sheet (the one with Items, Movements and People).") { link ->
                    if (com.fibrocoir.app.core.FcpSheets.remember("inventory", link)) { sheetSet = true; vm.refresh(); null } else "That isn't a Google Sheets link."
                }
            }
            return@FcpScreen
        }
        LoadBox(load, onRetry = vm::refresh) { data ->
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { p ->
                when (p) {
                    InvPage.SEARCH -> SearchPage(vm, data)
                    InvPage.ITEM -> ItemPage(vm, data)
                    InvPage.PERSON -> PersonPage(vm, data)
                    InvPage.QTY -> QtyPage(vm, data)
                    InvPage.MACHINE -> MachinePage(vm, data)
                    InvPage.PIN -> PinPage(vm)
                    InvPage.CONFIRM -> ConfirmPage(vm, data)
                    InvPage.DONE -> DonePage(vm, data)
                    InvPage.NEW -> NewPage(vm, data)
                    InvPage.DUPES -> DupesPage(vm)
                    InvPage.EDIT -> EditPage(vm, data)
                    InvPage.PEOPLE -> PeoplePage(vm, data)
                }
            }
        }
    }
}

@Composable
private fun personTitle(vm: InventoryViewModel): String {
    val mode by vm.mode.collectAsState()
    val act by vm.act.collectAsState()
    return when (mode) {
        InvMode.MOVE -> when (act) { "TAKE" -> "Who is taking it?"; "RETURN" -> "Who is returning it?"; else -> "Who is booking it in?" }
        InvMode.NEW -> "Who is adding it?"
        InvMode.EDIT -> "Who is making the change?"
        else -> "Who is allowing this?"
    }
}

// ---------------------------------------------------------------------- search

@Composable
private fun SearchPage(vm: InventoryViewModel, d: InvData) {
    val q by vm.query.collectAsState()
    val cat by vm.cat.collectAsState()
    val reg by vm.reg.collectAsState()
    val found = remember(d, q, cat, reg) { Inv.search(d, q, reg, cat) }
    LazyColumn(contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "q") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fcpField(q, { vm.query.value = it }, "Search a part…")
                ChoiceRow(listOf("" to "Everything", "SPARE" to "Spares", "CONSUMABLE" to "Consumables", "TOOL" to "Tools"), cat, { vm.cat.value = it })
                Flow(gap = 6.dp) {
                    d.regions.sortedBy { it.code }.forEach { r ->
                        val on = r.code == reg
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(hex(r.colour))
                                .border(if (on) 3.dp else 0.dp, if (on) Fcp.cream else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { vm.reg.value = if (on) "" else r.code },
                            contentAlignment = Alignment.Center,
                        ) { Text(r.code, color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        if (found.items.isEmpty()) item(key = "none") {
            Card { Text("Nothing matches", style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                Text("Try the part number, or tap a colour above to see one region.", style = MaterialTheme.typography.bodySmall, color = Fcp.muted) }
        }
        items(found.items, key = { it.id + it.row }) { item -> ItemRow(item, d) { vm.openItem(item.id) } }
    }
}

@Composable
private fun ItemRow(it: InvItem, d: InvData, onClick: () -> Unit) {
    Card(padding = 10.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(it.photo, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.name, style = MaterialTheme.typography.titleSmall, color = Fcp.cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (it.tamil.isNotEmpty()) Text(it.tamil, style = MaterialTheme.typography.bodySmall, color = Fcp.muted)
                Text(listOf(it.std, if (it.bin.isNotEmpty()) "Bin ${it.bin}" else "", it.id).filter { s -> s.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(stockColour(it.status)))
                    Spacer(Modifier.width(5.dp))
                    Text(Inv.qtyText(it.onHand), style = MaterialTheme.typography.titleMedium, color = Fcp.cream)
                }
                Text(it.unit, style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
            }
            Spacer(Modifier.width(10.dp))
            RegionBadge(it.region, d, 34)
        }
    }
}

private fun stockColour(s: String) = when (s) { "out" -> Fcp.bad; "low" -> Fcp.fibreHi; else -> Fcp.good }

@Composable
private fun Thumb(url: String, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Fcp.husk3)) {
        if (url.isNotEmpty()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun RegionBadge(code: String, d: InvData, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(d.colour(code)), contentAlignment = Alignment.Center) {
        Text(code.ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2).sp)
    }
}

// ---------------------------------------------------------------------- item

@Composable
private fun ItemPage(vm: InventoryViewModel, d: InvData) {
    val id by vm.itemId.collectAsState()
    val it = id?.let { d.item(it) }
    if (it == null) { Message("Not found", "No item $id in the sheet."); return }
    val where = listOfNotNull(d.region(it.region)?.board?.ifEmpty { null }, it.bin.ifEmpty { null }?.let { b -> "Bin $b" }, it.std.ifEmpty { null }, it.dims.ifEmpty { null })
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (it.photo.isNotEmpty()) {
            AsyncImage(com.fibrocoir.app.core.Uploads.thumb(it.photoUrl, 800), it.name, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).background(Fcp.husk3), contentScale = ContentScale.Crop)
        }
        Text(it.name, style = MaterialTheme.typography.headlineSmall, color = Fcp.cream)
        if (it.tamil.isNotEmpty()) Text(it.tamil, style = MaterialTheme.typography.titleMedium, color = Fcp.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Inv.qtyText(it.onHand), style = MaterialTheme.typography.displayMedium, color = stockColour(it.status))
                Text(it.unit + when (it.status) { "out" -> " · out of stock"; "low" -> " · low"; else -> "" }, style = MaterialTheme.typography.labelLarge, color = Fcp.muted)
            }
            RegionBadge(it.region, d, 64)
        }
        if (where.isNotEmpty()) Text(where.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Fcp.muted)
        ActionButton("↓", "Take", if (it.tool) "borrow it — bring it back" else "out of the store", Fcp.bad) { vm.startAction("TAKE") }
        ActionButton("↑", "Return", "put it back", Fcp.good) { vm.startAction("RETURN") }
        ActionButton("+", "Add stock", "new purchase — PIN needed", Fcp.info) { vm.startAction("RECEIVE") }
        ActionButton("✎", "Edit details", "name, region, minimum, stock count — PIN needed", Fcp.muted) { vm.openEdit() }
    }
}

@Composable
private fun ActionButton(icon: String, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), color = Fcp.husk2, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Text(icon, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Fcp.cream)
                Text(sub, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
            }
        }
    }
}

// ---------------------------------------------------------------------- who / how many / machine / PIN

@Composable
private fun PersonPage(vm: InventoryViewModel, d: InvData) {
    val mode by vm.mode.collectAsState()
    val list = vm.peopleFor(d)
    val supervisorTile = mode == InvMode.PERSON || mode == InvMode.ACTIVE
    if (list.isEmpty() && !supervisorTile) {
        Message("No one listed", if (mode == InvMode.MOVE) "Add rows to the People tab first." else "Nobody in People has CanCreateItem set to Y.")
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { p -> Face(p.photo, p.name, p.tamil) { vm.pickPerson(p) } }
        if (supervisorTile) item(key = "sup") { Face("", "Supervisor PIN", "", icon = "🔑") { vm.pickPerson(null) } }
    }
}

@Composable
private fun Face(photo: String, name: String, sub: String, icon: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Fcp.husk2).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Fcp.husk3), contentAlignment = Alignment.Center) {
            if (photo.isNotEmpty()) AsyncImage(photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(icon ?: name.take(1).uppercase(), fontSize = 34.sp, color = Fcp.fibre, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = Fcp.cream, textAlign = TextAlign.Center, maxLines = 2)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = Fcp.muted, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun QtyPage(vm: InventoryViewModel, d: InvData) {
    val id by vm.itemId.collectAsState()
    val it = id?.let { d.item(it) } ?: return
    val q by vm.qty.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { vm.bump(-1, it.unit) }
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Inv.qtyText(q), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Fcp.cream)
                Text(it.unit, style = MaterialTheme.typography.titleMedium, color = Fcp.muted)
            }
            StepButton("+") { vm.bump(1, it.unit) }
        }
        Flow(gap = 10.dp) {
            (Inv.PRESETS[it.unit] ?: listOf(1.0, 2.0, 5.0, 10.0)).forEach { n -> Pill(Inv.qtyText(n), q == n) { vm.qty.value = n } }
        }
        Text("In stock now: ${Inv.qtyText(it.onHand)} ${it.unit}", style = MaterialTheme.typography.bodyMedium, color = Fcp.muted)
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", Modifier.fillMaxWidth()) { vm.afterQty() }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Fcp.husk3).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 36.sp, color = Fcp.fibre)
    }
}

@Composable
private fun MachinePage(vm: InventoryViewModel, d: InvData) {
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(d.activeMachines, key = { it.id }) { m -> Face(m.photo, m.name, "", icon = m.id.filter(Char::isDigit).ifEmpty { "⚙" }) { vm.pickMachine(m) } }
        }
        GhostButton("Not for a machine", Modifier.fillMaxWidth().padding(16.dp)) { vm.pickMachine(null) }
    }
}

@Composable
private fun PinPage(vm: InventoryViewModel) {
    val pin by vm.pin.collectAsState()
    val p by vm.person.collectAsState()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p?.let { "${it.name}’s PIN" } ?: "Supervisor PIN from the Config tab", style = MaterialTheme.typography.titleMedium, color = Fcp.muted)
        Spacer(Modifier.height(16.dp))
        Text(if (busy) "saving…" else pin.map { '•' }.joinToString(" ").ifEmpty { " " }, fontSize = 36.sp, color = Fcp.cream, modifier = Modifier.height(52.dp))
        Spacer(Modifier.height(16.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("back", "0", "ok")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).background(if (k == "ok") Fcp.fibre else Fcp.husk2)
                            .clickable(enabled = !busy) { vm.key(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(when (k) { "back" -> "←"; "ok" -> "✓"; else -> k }, fontSize = 28.sp,
                            color = if (k == "ok") Fcp.husk else Fcp.cream, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmPage(vm: InventoryViewModel, d: InvData) {
    val id by vm.itemId.collectAsState()
    val it = id?.let { d.item(it) } ?: return
    val q by vm.qty.collectAsState()
    val p by vm.person.collectAsState()
    val m by vm.machine.collectAsState()
    val act by vm.act.collectAsState()
    val busy by vm.busy.collectAsState()
    val verb = when (act) { "TAKE" -> "Taking"; "RETURN" -> "Returning"; else -> "Adding" }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (it.photo.isNotEmpty()) Thumb(it.photo, 110)
            if (p?.photo?.isNotEmpty() == true) Thumb(p!!.photo, 110)
        }
        Text(it.name, style = MaterialTheme.typography.headlineSmall, color = Fcp.cream, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Inv.qtyText(q), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = Fcp.cream)
            Spacer(Modifier.width(8.dp))
            Text(it.unit, style = MaterialTheme.typography.titleLarge, color = Fcp.muted, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(listOfNotNull(verb, p?.name, m?.name).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = Fcp.muted)
        Spacer(Modifier.weight(1f))
        PrimaryButton("Confirm", Modifier.fillMaxWidth(), busy = busy) { vm.send() }
    }
}

@Composable
private fun DonePage(vm: InventoryViewModel, d: InvData) {
    val r by vm.done.collectAsState()
    val done = r ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(if (done.ok) Fcp.good else Fcp.bad), contentAlignment = Alignment.Center) {
            Text(if (done.ok) "✓" else "!", fontSize = 44.sp, color = Fcp.husk, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (done.itemPhoto.isNotEmpty()) Thumb(done.itemPhoto, 96)
            if (done.personPhoto.isNotEmpty()) Thumb(done.personPhoto, 96)
        }
        Text(done.title, style = MaterialTheme.typography.headlineSmall, color = Fcp.cream, textAlign = TextAlign.Center)
        done.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = Fcp.muted, textAlign = TextAlign.Center) }
        if (done.region.isNotEmpty() && done.ok && done.qrUrl.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(d.colour(done.region)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (done.putBack) "Put it back here" else "You will find it here", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(done.region, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
                d.region(done.region)?.board?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White) }
            }
        }
        if (done.qrUrl.isNotEmpty()) {
            Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
                AsyncImage(done.qrUrl, "QR", Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Done", Modifier.fillMaxWidth()) { vm.finish() }
    }
}

// ---------------------------------------------------------------------- new / edit forms

@Composable
private fun Options(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Flow(gap = 8.dp) { options.forEach { (k, v) -> Pill(v, k == selected) { onPick(k) } } }
}

@Composable
private fun MultiOptions(options: List<Pair<String, String>>, selected: List<String>, onToggle: (String) -> Unit) {
    Flow(gap = 8.dp) { options.forEach { (k, v) -> Pill(v, k in selected) { onToggle(k) } } }
}

@Composable
private fun RegionOptions(d: InvData, selected: String, onPick: (String) -> Unit) {
    Flow(gap = 8.dp) {
        d.regions.sortedBy { it.code }.forEach { r ->
            val on = r.code == selected
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(hex(r.colour))
                    .border(if (on) 3.dp else 0.dp, if (on) Fcp.cream else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onPick(r.code) },
                contentAlignment = Alignment.Center,
            ) { Text(r.code, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
    d.region(selected)?.desc?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Fcp.muted) }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Fcp.muted, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Fcp.faint)

@Composable
private fun ErrorLine(vm: InventoryViewModel) {
    val e by vm.formError.collectAsState()
    e?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fcp.bad, modifier = Modifier.padding(vertical = 4.dp)) }
}

private val num = KeyboardType.Decimal

@Composable
private fun NewPage(vm: InventoryViewModel, d: InvData) {
    val f by vm.newForm.collectAsState()
    val p1 by vm.newPhoto1.collectAsState()
    val p2 by vm.newPhoto2.collectAsState()
    fun set(x: (Inv.NewItem) -> Inv.NewItem) { vm.newForm.value = x(vm.newForm.value) }
    val next = remember(d) { Inv.nextItemId(d) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(next, style = MaterialTheme.typography.labelLarge, color = Fcp.fibre)
        ErrorLine(vm)
        SectionTitle("Photo")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhotoSlot("The part", "$next-main.jpg", p1?.uri, required = true, modifier = Modifier.weight(1f)) { file, uri -> vm.newPhoto1.value = Photo(file, uri) }
            PhotoSlot("Close-up of any stamped number", "$next-detail.jpg", p2?.uri, modifier = Modifier.weight(1f)) { file, uri -> vm.newPhoto2.value = Photo(file, uri) }
        }
        Hint("Put the part alone on a clean surface and lay a steel rule or a coin beside it. Without something for scale a 6205 and a 6308 look identical in a photo.")
        SectionTitle("What it is")
        fcpField(f.name, { v -> set { it.copy(name = v) } }, "Name in English *", placeholder = "Bearing 6205-2RS")
        fcpField(f.tamil, { v -> set { it.copy(tamil = v) } }, "Name in Tamil")
        fcpField(f.std, { v -> set { it.copy(std = v) } }, "Standard number, if it has one", placeholder = "6205-2RS · B-64 · 35x52x7")
        Hint("Bearings, belts, seals, chains and contactors all carry a standard number. Any brand with that number fits.")
        Label("Type *"); Options(Inv.CATEGORIES, f.category) { v -> set { it.copy(category = v) } }
        Label("Unit *"); Options(Inv.UNITS, f.unit) { v -> set { it.copy(unit = v) } }
        SectionTitle("Where it lives")
        Label("Region *"); RegionOptions(d, f.region) { v -> set { it.copy(region = v) } }
        fcpField(f.bin, { v -> set { it.copy(bin = v) } }, "Bin or hook number", placeholder = "A-14")
        Label("Fits which machines")
        if (d.activeMachines.isEmpty()) Hint("No machines listed yet.")
        MultiOptions(d.activeMachines.map { it.id to it.name.ifEmpty { it.id } }, f.machines) { m -> set { it.copy(machines = if (m in it.machines) it.machines - m else it.machines + m) } }
        SectionTitle("Size and material")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fcpField(f.dim1, { v -> set { it.copy(dim1 = v) } }, "Outer ⌀", Modifier.weight(1f))
            fcpField(f.dim2, { v -> set { it.copy(dim2 = v) } }, "Inner ⌀", Modifier.weight(1f))
            fcpField(f.dim3, { v -> set { it.copy(dim3 = v) } }, "Length", Modifier.weight(1f))
        }
        fcpField(f.material, { v -> set { it.copy(material = v) } }, "Material", placeholder = "MS · SS304 · rubber")
        fcpField(f.swap, { v -> set { it.copy(swap = v) } }, "Can be used in place of", placeholder = "ITM-0007, ITM-0012")
        SectionTitle("Stock control")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fcpField(f.openingQty, { v -> set { it.copy(openingQty = v) } }, "Opening stock", Modifier.weight(1f), keyboard = num)
            fcpField(f.minLevel, { v -> set { it.copy(minLevel = v) } }, "Minimum", Modifier.weight(1f), keyboard = num)
            fcpField(f.reorderQty, { v -> set { it.copy(reorderQty = v) } }, "Reorder qty", Modifier.weight(1f), keyboard = num)
        }
        fcpField(f.leadTime, { v -> set { it.copy(leadTime = v) } }, "Lead time in days", keyboard = KeyboardType.Number, placeholder = "1 for local, 45 for imported")
        Hint("Lead time drives the minimum. A part you can buy in Pollachi the same morning barely needs stock; an imported one needs enough to cover the wait.")
        SectionTitle("Where you bought it")
        Label("Supplier")
        if (d.vendors.isEmpty()) Hint("No suppliers listed yet.")
        Options(d.vendors.map { it.id to it.name.ifEmpty { it.id } }, f.vendorId) { v -> set { it.copy(vendorId = if (it.vendorId == v) "" else v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fcpField(f.brand, { v -> set { it.copy(brand = v) } }, "Brand", Modifier.weight(1f), placeholder = "SKF · HCH")
            fcpField(f.price, { v -> set { it.copy(price = v) } }, "Price each", Modifier.weight(1f), keyboard = num)
        }
        fcpField(f.vendorPartNo, { v -> set { it.copy(vendorPartNo = v) } }, "Their part number on the bill")
        ErrorLine(vm)
        PrimaryButton("Continue", Modifier.fillMaxWidth()) { vm.checkNew() }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun DupesPage(vm: InventoryViewModel) {
    val list by vm.similar.collectAsState()
    LazyColumn(contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Hint("If the part is already in the store, add stock to it instead of creating it again. Two rows for the same part means both look low.") }
        items(list, key = { it.id }) { x ->
            Card(padding = 10.dp, onClick = { vm.openItem(x.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(x.photo, 52); Spacer(Modifier.width(10.dp))
                    Column { Text(x.name, color = Fcp.cream, style = MaterialTheme.typography.titleSmall)
                        Text("${x.id} · ${Inv.qtyText(x.onHand)} ${x.unit}", color = Fcp.muted, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        item { PrimaryButton("No, this is a new part", Modifier.fillMaxWidth().padding(top = 8.dp)) { vm.newPickPerson() } }
    }
}

@Composable
private fun EditPage(vm: InventoryViewModel, d: InvData) {
    val f by vm.editForm.collectAsState()
    val ph by vm.editPhoto.collectAsState()
    val id by vm.itemId.collectAsState()
    val it = id?.let { d.item(it) } ?: return
    fun set(x: (EditForm) -> EditForm) { vm.editForm.value = x(vm.editForm.value) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(it.id, style = MaterialTheme.typography.labelLarge, color = Fcp.fibre)
        ErrorLine(vm)
        SectionTitle("Stock count")
        Hint("Only fill this in after physically counting. The difference is written to the ledger as an adjustment, with your name on it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Label("System says"); Text("${Inv.qtyText(it.onHand)} ${it.unit}", style = MaterialTheme.typography.titleLarge, color = Fcp.cream) }
            fcpField(f.counted, { v -> set { x -> x.copy(counted = v) } }, "You counted", Modifier.weight(1f), keyboard = num, placeholder = "leave blank")
        }
        SectionTitle("Replace the photo")
        PhotoSlot("current photo", "${it.id}-main.jpg", ph?.uri, existingUrl = it.photo, modifier = Modifier.fillMaxWidth(0.6f)) { file, uri -> vm.editPhoto.value = Photo(file, uri) }
        Hint("Leave it alone to keep the existing photo. The old file stays in Drive.")
        SectionTitle("Details")
        fcpField(f.name, { v -> set { x -> x.copy(name = v) } }, "Name in English")
        fcpField(f.tamil, { v -> set { x -> x.copy(tamil = v) } }, "Name in Tamil")
        fcpField(f.std, { v -> set { x -> x.copy(std = v) } }, "Standard number")
        Label("Type"); Options(Inv.CATEGORIES, f.cat) { v -> set { x -> x.copy(cat = v) } }
        Label("Unit"); Options(Inv.UNITS, f.unit) { v -> set { x -> x.copy(unit = v) } }
        Label("Region"); RegionOptions(d, f.region) { v -> set { x -> x.copy(region = v) } }
        fcpField(f.bin, { v -> set { x -> x.copy(bin = v) } }, "Bin or hook number")
        Label("Fits which machines")
        MultiOptions(d.activeMachines.map { m -> m.id to m.name.ifEmpty { m.id } }, f.machines) { m -> set { x -> x.copy(machines = if (m in x.machines) x.machines - m else x.machines + m) } }
        SectionTitle("Size and material")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fcpField(f.dim1, { v -> set { x -> x.copy(dim1 = v) } }, "Outer ⌀", Modifier.weight(1f))
            fcpField(f.dim2, { v -> set { x -> x.copy(dim2 = v) } }, "Inner ⌀", Modifier.weight(1f))
            fcpField(f.dim3, { v -> set { x -> x.copy(dim3 = v) } }, "Length", Modifier.weight(1f))
        }
        fcpField(f.material, { v -> set { x -> x.copy(material = v) } }, "Material")
        fcpField(f.swap, { v -> set { x -> x.copy(swap = v) } }, "Can be used in place of")
        SectionTitle("Stock control")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fcpField(f.min, { v -> set { x -> x.copy(min = v) } }, "Minimum", Modifier.weight(1f), keyboard = num)
            fcpField(f.reorder, { v -> set { x -> x.copy(reorder = v) } }, "Reorder qty", Modifier.weight(1f), keyboard = num)
            fcpField(f.lead, { v -> set { x -> x.copy(lead = v) } }, "Lead days", Modifier.weight(1f), keyboard = KeyboardType.Number)
        }
        Label("Status"); Options(listOf("ACTIVE" to "Active", "INACTIVE" to "Inactive"), f.status) { v -> set { x -> x.copy(status = v) } }
        Hint("Set it to inactive to hide it from search without losing its history.")
        ErrorLine(vm)
        PrimaryButton("Save changes", Modifier.fillMaxWidth()) { vm.editPickPerson() }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------------------------------------------------------------------- people

@Composable
private fun PeoplePage(vm: InventoryViewModel, d: InvData) {
    val f by vm.personForm.collectAsState()
    val ph by vm.personPhoto.collectAsState()
    fun set(x: (Inv.NewPerson) -> Inv.NewPerson) { vm.personForm.value = x(vm.personForm.value) }
    val next = remember(d) { Inv.nextPersonId(d) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.master.isEmpty()) Text("Config › SUPERVISOR_PIN is blank. Put a number there in the sheet first, or nothing can be saved from this screen.",
            style = MaterialTheme.typography.bodySmall, color = Fcp.bad)
        ErrorLine(vm)
        SectionTitle("Already listed")
        val all = d.people.filter { it.id.isNotEmpty() }
        if (all.isEmpty()) Hint("Nobody yet. The first person has to be added with the supervisor PIN.")
        all.forEach { p ->
            Card(padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Fcp.husk3), contentAlignment = Alignment.Center) {
                        if (p.photo.isNotEmpty()) AsyncImage(p.photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(p.name.take(1), color = Fcp.fibre, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = if (p.active) Fcp.cream else Fcp.faint, style = MaterialTheme.typography.titleSmall)
                        Text(p.id + (if (p.canCreate) " · admin" else if (p.canReceive) " · stock" else "") + (if (p.active) "" else " · off"),
                            color = Fcp.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    GhostButton(if (p.active) "Turn off" else "Turn on", color = if (p.active) Fcp.muted else Fcp.good) { vm.toggleActive(p) }
                }
            }
        }
        SectionTitle("Add someone")
        Text(next, style = MaterialTheme.typography.labelLarge, color = Fcp.fibre)
        PhotoSlot("face photo", "$next-face.jpg", ph?.uri, modifier = Modifier.fillMaxWidth(0.55f)) { file, uri -> vm.personPhoto.value = Photo(file, uri) }
        Hint("A clear face against a plain wall. This is what people tap to identify themselves, so it matters more than anything else on this screen.")
        fcpField(f.name, { v -> set { it.copy(name = v) } }, "Name *")
        fcpField(f.tamil, { v -> set { it.copy(tamil = v) } }, "Name in Tamil")
        Label("Section"); Options(Inv.SECTIONS.map { it to it.lowercase().replaceFirstChar(Char::uppercase) }, f.section) { v -> set { it.copy(section = v) } }
        Label("Allowed to")
        Flow(gap = 8.dp) {
            Pill("Add stock", f.canReceive) { set { it.copy(canReceive = !it.canReceive) } }
            Pill("Create & edit items", f.canCreate) { set { it.copy(canCreate = !it.canCreate) } }
        }
        fcpField(f.newPin, { v -> set { it.copy(newPin = v.filter(Char::isDigit).take(6)) } }, "Their own PIN, only if allowed above", keyboard = KeyboardType.NumberPassword, placeholder = "4 digits")
        Hint("Leave both permissions off for ordinary workers. They can still take and return without any PIN — that is the whole point of the photo grid.")
        ErrorLine(vm)
        PrimaryButton("Add person", Modifier.fillMaxWidth()) { vm.addPersonPick() }
        Spacer(Modifier.height(30.dp))
    }
}

