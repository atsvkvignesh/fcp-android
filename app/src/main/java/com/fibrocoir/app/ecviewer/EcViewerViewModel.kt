package com.fibrocoir.app.ecviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import com.fibrocoir.app.ec.Ec
import com.fibrocoir.app.ec.EcRepo
import com.fibrocoir.app.ec.EcTab
import com.fibrocoir.app.ec.Range
import com.fibrocoir.app.ec.Reading
import com.fibrocoir.app.ec.ViewerLimits
import com.fibrocoir.app.ec.bounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class EcData(val tabs: List<EcTab>, val viewerLimits: ViewerLimits? = null, val people: List<String> = emptyList())

enum class Side { HALL, YARD }

data class ViewerUi(
    val range: Range = Range.TODAY,
    val from: String? = null,
    val to: String? = null,
    val who: String = "all",
    val side: Side = Side.HALL,
    val yard: String = "all",          // narrows the Yards tab to one yard
    val hallPoint: String = "Hall",    // readings list: Hall (Belt + Block), Belt or Block
    val text: String = "",
    val overOnly: Boolean = false,
    val show: Int = 50,
)

class EcViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = EcRepo(fcp.sheets, fcp.store)

    val ui = MutableStateFlow(ViewerUi())
    val data = Loader(viewModelScope, fcp.store, EcData.serializer())
    val busy = MutableStateFlow(false)

    init { load() }

    fun load(force: Boolean = false) {
        val u = ui.value
        val (from, to) = try { bounds(u.range, u.from, u.to) } catch (e: Exception) { return }
        data.load("ecv.$from.$to.${u.who}") {
            if (force) fcp.store.forget("ec.tab.")
            val tabs = repo.tabsBetween(from, to, u.who)
            EcData(tabs, repo.viewerLimits(), repo.people())
        }
    }

    fun set(change: (ViewerUi) -> ViewerUi, reload: Boolean = false) {
        ui.update { change(it).let { n -> if (reload) n.copy(show = 50) else n } }
        if (reload) load()
    }

    fun setLimits(belt: Double?, block: Double?, yard: Double?, done: (String?) -> Unit) {
        busy.value = true
        viewModelScope.launch {
            val err = try { repo.setViewerLimits(belt, block, yard); null } catch (e: Exception) { e.message }
            busy.value = false
            done(err)
            if (err == null) load()
        }
    }

    fun export(rows: List<Reading>, over: (Reading) -> Boolean, done: (String?, String?) -> Unit) {
        busy.value = true
        viewModelScope.launch {
            try { done(repo.export(rows, over), null) } catch (e: Exception) { done(null, e.message) }
            busy.value = false
        }
    }

    fun rangeLabel(): String {
        val u = ui.value
        val (from, to) = try { bounds(u.range, u.from, u.to) } catch (e: Exception) { return "pick both dates" }
        return (if (from == to) dmy(from) else dmy(from) + " to " + dmy(to)) + if (u.who == "all") ", everyone" else ", ${u.who}"
    }

    companion object {
        fun dmy(d: String): String { val p = d.split("-"); return if (p.size == 3) "${p[2]}/${p[1]}" else d }
        fun today() = Ec.today()
    }
}
