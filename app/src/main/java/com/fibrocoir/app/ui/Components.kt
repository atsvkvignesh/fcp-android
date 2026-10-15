package com.fibrocoir.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fibrocoir.app.core.Load
import com.fibrocoir.app.core.ago
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Short messages at the bottom of the screen ("Saved", errors). */
class Say(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    operator fun invoke(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message)
        }
    }
}

val LocalSay = staticCompositionLocalOf<Say> { error("no Say") }

/**
 * The frame every screen uses: title bar (with back arrow when `onBack` is given), the
 * "showing saved data" note, pull to refresh, and the message bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FcpScreen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    load: Load<*>? = null,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say = remember(host) { Say(host, scope) }
    CompositionLocalProvider(LocalSay provides say) {
        Scaffold(
            containerColor = Fcp.husk,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!subtitle.isNullOrBlank()) {
                                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Fcp.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        navigationIcon = {
                            if (onBack != null) IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = actions,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Fcp.husk, titleContentColor = Fcp.cream,
                            navigationIconContentColor = Fcp.cream, actionIconContentColor = Fcp.fibre,
                        ),
                    )
                    // a thin bar while a refresh is running, without hiding what is on screen
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        if (load?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Fcp.fibre, trackColor = Color.Transparent)
                    }
                }
            },
            bottomBar = bottomBar,
            floatingActionButton = floating,
            snackbarHost = { SnackbarHost(host) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (load != null) StaleNote(load)
                Box(Modifier.weight(1f)) {
                    if (onRefresh != null) {
                        PullToRefreshBox(
                            isRefreshing = load?.loading == true && load.data != null,
                            onRefresh = onRefresh,
                        ) { content(PaddingValues(0.dp)) }
                    } else {
                        content(PaddingValues(0.dp))
                    }
                }
            }
        }
    }
}

/** "Offline · showing data from 10:42" when the last refresh failed but older data is shown. */
@Composable
fun StaleNote(load: Load<*>) {
    AnimatedVisibility(load.stale, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.fillMaxWidth().background(Fcp.husk3).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (load.offline) Fcp.muted else Fcp.bad))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (load.offline) "Offline" else "Couldn't refresh") + " · showing data from " + ago(load.at),
                style = MaterialTheme.typography.labelMedium, color = Fcp.muted,
            )
        }
    }
}

/** Whole-screen states: first load, nothing yet, or an error with nothing saved to show. */
@Composable
fun <T> LoadBox(load: Load<T>, onRetry: () -> Unit, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val data = load.data
    when {
        data != null -> content(data)
        load.error != null -> Message(
            title = if (load.offline) "No connection" else "Couldn't load",
            body = load.error,
            action = "Try again", onAction = onRetry, modifier = modifier,
        )
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Fcp.fibre, strokeWidth = 3.dp)
        }
    }
}

@Composable
fun Message(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Fcp.cream)
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Fcp.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, onClick = onAction)
        }
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clip(shape).clickable(onClick = onClick) else it },
        shape = shape,
        color = Fcp.husk2,
        border = BorderStroke(1.dp, accent ?: Fcp.edge),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Fcp.cream, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A row of choices where exactly one is picked (ranges, tabs, filters). */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            Pill(label, value == selected) { onSelect(value) }
        }
    }
}

@Composable
fun Pill(label: String, on: Boolean, color: Color = Fcp.fibre, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.clip(shape)
            .background(if (on) color else Fcp.husk2)
            .border(1.dp, if (on) color else Fcp.edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Fcp.husk else Fcp.cream)
    }
}

/** Two big segments, e.g. Machine hall / Yards. */
@Composable
fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(Fcp.husk2).border(1.dp, Fcp.edge, shape).padding(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) Fcp.fibre else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Fcp.husk else Fcp.muted)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy, modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Fcp.fibre, contentColor = Fcp.husk,
            disabledContainerColor = Fcp.husk4, disabledContentColor = Fcp.muted),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Fcp.husk, strokeWidth = 2.dp)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Fcp.cream, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Fcp.edge),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** A number with a small label under it. */
@Composable
fun Stat(value: String, label: String, color: Color = Fcp.cream, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Fcp.muted)
    }
}

@Composable
fun Tag(text: String, color: Color = Fcp.fibre) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold) }
}

/** Items that wrap onto more lines when they don't fit (chips, tags). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Flow(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) { content() }
}

/** Standard padding for scrolling screen content. */
val ScreenPad = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)

fun num(v: Double?, digits: Int = 2): String = if (v == null) "—" else String.format(java.util.Locale.US, "%.${digits}f", v)

fun inr(v: Double?): String {
    if (v == null) return "—"
    val n = Math.round(v)
    val nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN"))
    return "₹" + nf.format(n)
}

fun count(v: Double?): String {
    if (v == null) return "—"
    return java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(Math.round(v))
}
