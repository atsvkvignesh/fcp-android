package com.fibrocoir.app.hub

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.Topics
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
import com.fibrocoir.app.ui.Tag

@Composable
fun fcpField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
    ime: ImeAction = ImeAction.Next, onDone: (() -> Unit)? = null, placeholder: String? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        placeholder = placeholder?.let { { Text(it, color = Fcp.faint) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Fcp.fibre, unfocusedBorderColor = Fcp.edge, focusedLabelColor = Fcp.fibre,
            unfocusedLabelColor = Fcp.muted, cursorColor = Fcp.fibre, focusedContainerColor = Fcp.husk2,
            unfocusedContainerColor = Fcp.husk2,
        ),
    )
}

// ====================================================================== sign in

@Composable
fun LoginScreen(vm: HubViewModel) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsState()
    fun go() {
        if (user.isBlank() || pw.isEmpty()) { err = "Enter your user name and password."; return }
        vm.signIn(user, pw) { e -> err = e; if (e == null) pw = "" }
    }
    Column(
        Modifier.fillMaxSize().background(Fcp.husk).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Fibro Coir Products", style = MaterialTheme.typography.displaySmall, color = Fcp.cream)
        Text("Works hub", style = MaterialTheme.typography.titleMedium, color = Fcp.fibre)
        Spacer(Modifier.height(36.dp))
        fcpField(user, { user = it; err = null }, "User name")
        Spacer(Modifier.height(12.dp))
        fcpField(pw, { pw = it; err = null }, "Password", password = true, ime = ImeAction.Go, onDone = ::go)
        if (err != null) {
            Spacer(Modifier.height(10.dp))
            Text(err!!, color = Fcp.bad, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Sign in", Modifier.fillMaxWidth(), busy = busy, onClick = ::go)
        if (!FcpApplication.instance.auth.configured) {
            Spacer(Modifier.height(16.dp))
            Text("This copy of the app has no Google key, so it can't reach the sheets.", color = Fcp.bad, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ====================================================================== tiles

@Composable
fun HubHome(vm: HubViewModel, openScreen: (Screen) -> Unit, openManage: () -> Unit) {
    val load by vm.hub.state.collectAsState()
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(false) }
    val me = load.data?.users?.firstOrNull { it.id == vm.session.userId }
    FcpScreen(
        title = "Hello, ${vm.session.name.ifBlank { "there" }}",
        subtitle = if (vm.session.role == "admin") "Admin" else "Data entry",
        load = load, onRefresh = vm::refresh,
        actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, "Settings") } },
    ) {
        LoadBox(load, onRetry = vm::refresh) { data ->
            val tiles = data.apps.filter { me?.sees(it) ?: (vm.session.role == "admin") }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (tiles.isEmpty()) item(span = { GridItemSpan(2) }) {
                    Text(
                        if (vm.session.role == "admin") "No apps yet. Add the first one from Manage." else "No apps yet. Ask the office to add one.",
                        color = Fcp.muted, modifier = Modifier.padding(24.dp),
                    )
                }
                items(tiles, key = { it.id }) { app ->
                    val screen = Screen.of(app.screen)
                    Tile(app, screen) {
                        if (screen != null) openScreen(screen)
                        else if (app.url.isNotBlank()) {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url))) } catch (_: Exception) { }
                        }
                    }
                }
                if (vm.session.role == "admin") item(span = { GridItemSpan(2) }) {
                    GhostButton("Manage apps and people", Modifier.fillMaxWidth().padding(top = 8.dp), onClick = openManage)
                }
            }
        }
    }
    if (settings) SettingsDialog(vm) { settings = false }
}

@Composable
private fun Tile(app: HubApp, screen: Screen?, onClick: () -> Unit) {
    Card(Modifier.aspectRatio(1.05f), onClick = onClick, padding = 14.dp) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Fcp.husk3),
            contentAlignment = Alignment.Center,
        ) { Text(app.icon.ifBlank { app.name.take(1).uppercase() }, fontSize = 22.sp, color = Fcp.fibre) }
        Spacer(Modifier.weight(1f))
        Text(app.name, style = MaterialTheme.typography.titleMedium, color = Fcp.cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (screen != null) "In the app" else "Opens " + (Uri.parse(app.url).host?.removePrefix("www.") ?: "link"),
            style = MaterialTheme.typography.labelSmall, color = if (screen != null) Fcp.good else Fcp.muted, maxLines = 1,
        )
    }
}

@Composable
private fun SettingsDialog(vm: HubViewModel, close: () -> Unit) {
    val ctx = LocalContext.current
    var groups by remember { mutableStateOf(Topics.subscribed(ctx)) }
    AlertDialog(
        onDismissRequest = close, containerColor = Fcp.husk2,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Notification groups", style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                Topics.OPTIONAL.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val on = key !in groups
                        Topics.set(ctx, key, on); groups = Topics.subscribed(ctx)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(key in groups, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Fcp.fibre, checkmarkColor = Fcp.husk))
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Fcp.cream)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("App account for the sheets:", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
                Text(FcpApplication.instance.auth.accountEmail ?: "not set up", style = MaterialTheme.typography.bodySmall, color = Fcp.cream)
                Text("Version " + com.fibrocoir.app.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall, color = Fcp.faint)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done", color = Fcp.fibre) } },
        dismissButton = { TextButton(onClick = { close(); vm.signOut() }) { Text("Sign out", color = Fcp.bad) } },
    )
}

// ====================================================================== manage (admin)

@Composable
fun ManageScreen(vm: HubViewModel, onBack: () -> Unit) {
    val load by vm.hub.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val say = LocalSay.current
    var editApp by remember { mutableStateOf<HubApp?>(null) }
    var addUser by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var drafts by remember { mutableStateOf(mapOf<String, List<String>?>()) }

    fun run(ok: String, what: suspend HubRepo.(HubData) -> Unit) = vm.change(what) { e -> say(e ?: ok) }

    FcpScreen("Manage", "Apps and people", onBack = onBack, load = load, onRefresh = vm::refresh) {
        LoadBox(load, vm::refresh) { data ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SectionTitle("Apps on the hub") { TextButton(onClick = { editApp = HubApp("", "") }) { Text("+ Add", color = Fcp.fibre) } } }
                items(data.apps, key = { "a" + it.id }) { app ->
                    val i = data.apps.indexOf(app)
                    Card(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.icon.ifBlank { "•" }, fontSize = 20.sp, modifier = Modifier.width(34.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, style = MaterialTheme.typography.titleSmall, color = Fcp.cream)
                                Text(Screen.of(app.screen)?.title?.let { "Built-in: $it" } ?: app.url,
                                    style = MaterialTheme.typography.labelSmall, color = Fcp.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { editApp = app }) { Text("Edit", color = Fcp.fibre) }
                            TextButton(enabled = i > 0 && !busy, onClick = {
                                val l = data.apps.toMutableList(); l.add(i - 1, l.removeAt(i)); run("Moved.") { reorder(l) }
                            }) { Text("Up") }
                            TextButton(enabled = i < data.apps.size - 1 && !busy, onClick = {
                                val l = data.apps.toMutableList(); l.add(i + 1, l.removeAt(i)); run("Moved.") { reorder(l) }
                            }) { Text("Down") }
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = {
                                confirm = "Remove ${app.name}? It disappears from the hub for everyone." to { run("Removed.") { deleteApp(app, it) } }
                            }) { Text("Remove", color = Fcp.bad) }
                        }
                    }
                }

                item { SectionTitle("People") { TextButton(onClick = { addUser = true }) { Text("+ Add", color = Fcp.fibre) } } }
                items(data.users, key = { "u" + it.id }) { u ->
                    UserCard(u, data, drafts[u.id], busy,
                        onDraft = { d -> drafts = drafts + (u.id to d) },
                        onSaveAccess = { pick -> run("App access saved.") { writeAccess(u, pick) }; drafts = drafts - u.id },
                        onPassword = { pw -> run("Password set.") { setPassword(u, pw) } },
                        onRemove = {
                            confirm = "Remove ${u.name}? They won't be able to sign in any more." to { run("Removed.") { deleteUser(u, it) } }
                        },
                        isMe = u.id == vm.session.userId,
                    )
                }
            }
        }
    }

    editApp?.let { a -> AppDialog(a, onClose = { editApp = null }) { saved -> editApp = null; run("Saved.") { saveApp(saved, it) } } }
    if (addUser) AddUserDialog(onClose = { addUser = false }) { login, name, role, pw ->
        addUser = false; run("Person added.") { addUser(login, name, role, pw, it) }
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = Fcp.husk2,
            text = { Text(text, color = Fcp.cream) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Remove", color = Fcp.bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UserCard(
    u: HubUser, data: HubData, draft: List<String>?, busy: Boolean, isMe: Boolean,
    onDraft: (List<String>?) -> Unit, onSaveAccess: (List<String>?) -> Unit, onPassword: (String) -> Unit, onRemove: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val chosen: List<String>? = if (touched) draft else u.apps
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(u.name, style = MaterialTheme.typography.titleMedium, color = Fcp.cream, modifier = Modifier.weight(1f))
            Tag(if (u.admin) "Admin" else "Data entry")
        }
        Text("Signs in as ${u.login}", style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
        if (!u.admin) {
            Spacer(Modifier.height(10.dp))
            Text("Apps this person can open", style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
            Flow(Modifier.padding(top = 6.dp)) {
                Pill("All apps", chosen == null) { touched = true; onDraft(if (chosen == null) emptyList() else null) }
                data.apps.forEach { a ->
                    val on = chosen != null && a.id in chosen
                    Pill((a.icon + " " + a.name).trim(), on) {
                        touched = true
                        val base = chosen ?: data.apps.map { it.id }
                        onDraft(if (on) base - a.id else base + a.id)
                    }
                }
            }
            if (touched) TextButton(enabled = !busy, onClick = { onSaveAccess(chosen); touched = false }) { Text("Save app access", color = Fcp.fibre) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            fcpField(pw, { pw = it }, "New password", Modifier.weight(1f))
            TextButton(enabled = pw.isNotEmpty() && !busy, onClick = { onPassword(pw); pw = "" }) { Text("Set", color = Fcp.fibre) }
        }
        if (!isMe) TextButton(enabled = !busy, onClick = onRemove) { Text("Remove person", color = Fcp.bad) }
    }
}

@Composable
private fun AppDialog(app: HubApp, onClose: () -> Unit, onSave: (HubApp) -> Unit) {
    var name by remember { mutableStateOf(app.name) }
    var icon by remember { mutableStateOf(app.icon) }
    var url by remember { mutableStateOf(app.url) }
    var screen by remember { mutableStateOf(app.screen) }
    var menu by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Fcp.husk2,
        title = { Text(if (app.row == 0) "Add an app" else "Edit ${app.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fcpField(name, { name = it }, "Button name")
                fcpField(icon, { icon = it.take(3) }, "Icon (an emoji, optional)")
                Box {
                    GhostButton("Opens: " + (Screen.of(screen)?.title ?: "the link below"), Modifier.fillMaxWidth()) { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("The link below") }, onClick = { screen = ""; menu = false })
                        Screen.entries.forEach { s -> DropdownMenuItem(text = { Text("Built-in: " + s.title) }, onClick = { screen = s.key; menu = false }) }
                    }
                }
                if (Screen.of(screen) == null) fcpField(url, { url = it }, "Link", keyboard = KeyboardType.Uri, placeholder = "https://...")
                err?.let { Text(it, color = Fcp.bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> err = "Enter a name for the app."
                    name.length > 40 -> err = "Keep the name under 40 characters."
                    Screen.of(screen) == null && !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url.trim()) -> err = "Enter a full link starting with https://"
                    else -> onSave(app.copy(name = name.trim(), icon = icon.trim(), url = url.trim(), screen = screen))
                }
            }) { Text("Save", color = Fcp.fibre) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun AddUserDialog(onClose: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var login by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Fcp.husk2,
        title = { Text("Add a person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fcpField(login, { login = it }, "User name for signing in")
                fcpField(name, { name = it }, "Display name")
                fcpField(pw, { pw = it }, "Password")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("Data entry", !admin) { admin = false }
                    Spacer(Modifier.width(8.dp))
                    Pill("Admin", admin) { admin = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(login, name, if (admin) "admin" else "user", pw) }) { Text("Add", color = Fcp.fibre) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
