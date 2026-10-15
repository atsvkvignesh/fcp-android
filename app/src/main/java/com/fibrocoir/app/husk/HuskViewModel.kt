package com.fibrocoir.app.husk

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import com.fibrocoir.app.hub.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class RmTab { MATCH, SUMMARY, HISTORY }

data class RmUi(
    val preset: String = "30",          // 7, 30, all, pick
    val filter: RmFilter = RmFilter(),
    val tab: RmTab = RmTab.MATCH,
    val pendingOnly: Boolean = true,
    val ours: Boolean = true,           // which list is showing: our loads or vendor slips
    val q: String = "",
    val hist: String = "ALL",
    val selC: Set<String> = emptySet(),
    val selV: Set<String> = emptySet(),
)

class HuskViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = HuskRepo(fcp.sheets, fcp.store)
    private val session = Session(app)

    val raw = Loader(viewModelScope, fcp.store, RmRaw.serializer())
    val ui = MutableStateFlow(RmUi(filter = presetFilter("30")))
    val busy = MutableStateFlow(false)

    /** The filtered picture, worked out off the main thread whenever the data or a filter changes. */
    val state: StateFlow<RmState?> = combine(raw.state, ui) { l, u -> l.data?.let { Rm.build(it, u.filter) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { refresh() }

    fun refresh() { raw.load(KEY) { repo.load() } }

    fun set(change: (RmUi) -> RmUi) = ui.update(change)

    fun preset(p: String) = ui.update { it.copy(preset = p, filter = if (p == "pick") it.filter else presetFilter(p).copy(vendor = it.filter.vendor)) }
    fun dates(from: String, to: String) = ui.update {
        val (a, b) = if (from <= to) from to to else to to from
        it.copy(preset = "pick", filter = it.filter.copy(from = a, to = b))
    }
    fun vendor(key: String) = ui.update { it.copy(filter = it.filter.copy(vendor = key), selC = emptySet(), selV = emptySet()) }

    fun toggleC(k: String) = ui.update { it.copy(selC = if (k in it.selC) it.selC - k else it.selC + k) }
    fun toggleV(k: String) = ui.update { it.copy(selV = if (k in it.selV) it.selV - k else it.selV + k) }
    fun clearSel() = ui.update { it.copy(selC = emptySet(), selV = emptySet()) }

    /** Saves the picked rows as one decision. done(message, ids to undo, failed) */
    fun act(status: String, note: String, done: (String, List<String>, Boolean) -> Unit) {
        val u = ui.value
        val st = state.value ?: return
        val nc = u.selC.size; val nv = u.selV.size
        val vname = if (u.filter.vendor.isNotEmpty()) st.vendors.firstOrNull { it.key == u.filter.vendor }?.name ?: "" else ""
        busy.value = true
        viewModelScope.launch {
            try {
                val (fresh, ids) = repo.save(status, u.selC.toList(), u.selV.toList(), note, vname, session.name)
                raw.set(KEY, fresh)
                clearSel()
                done(when (status) {
                    Rm.MATCHED -> "Matched ${plural(nc, "load", "loads")} with ${plural(nv, "vendor entry", "vendor entries")}."
                    Rm.MISS_V -> "Marked ${plural(nc, "load", "loads")} missing at vendor."
                    Rm.MISS_F -> "Marked ${plural(nv, "entry", "entries")} missing at our end."
                    else -> "Ignored ${plural(nc + nv, "row", "rows")}."
                }, ids, false)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't save.", emptyList(), true)
            } finally { busy.value = false }
        }
    }

    fun undo(ids: List<String>, done: (String) -> Unit) {
        busy.value = true
        viewModelScope.launch {
            try { raw.set(KEY, repo.undo(ids)); done("Undone. Those rows are back in To check.") }
            catch (e: Exception) { done(e.message ?: "Couldn't undo.") }
            finally { busy.value = false }
        }
    }

    fun export(done: (url: String?, msg: String) -> Unit) {
        val r = raw.state.value.data ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                val (url, rows) = repo.export(r, ui.value.filter)
                done(url, "Exported $rows rows to the \"${Rm.EXPORT_TAB}\" tab.")
            } catch (e: Exception) { done(null, e.message ?: "Couldn't export.") }
            finally { busy.value = false }
        }
    }

    companion object {
        const val KEY = "husk"
        fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

        private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone(Rm.TZ) }.format(Date(ms))

        fun presetFilter(p: String): RmFilter {
            if (p == "all") return RmFilter()
            val days = p.toIntOrNull() ?: 30
            val now = System.currentTimeMillis()
            return RmFilter(from = iso(now - (days - 1) * 86_400_000L), to = iso(now))
        }
    }
}
