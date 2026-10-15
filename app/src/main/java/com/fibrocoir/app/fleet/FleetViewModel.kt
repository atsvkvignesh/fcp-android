package com.fibrocoir.app.fleet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import com.fibrocoir.app.core.PickedFile
import com.fibrocoir.app.hub.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FleetTab(val title: String) { BOARD("Fleet"), LOG("Log"), SERVICE("Service"), DOCS("Papers"), GARAGE("Garage"), SETTINGS("Reminders") }

/** Everything the screens draw, worked out from one copy of the sheet. */
class FleetView(val book: FleetBook) {
    val boot = book.bootstrap()
    val odo = book.logs("odo", "", 60)
    val fuel = book.logs("fuel", "", 60)
    val service = book.logs("service", "", 60)
    val docs = book.logs("docs", "", 60)
    val history = book.reminderHistory()
    fun vehicle(id: String?) = boot.vehicles.firstOrNull { it.id == id }
    val live get() = boot.vehicles.filter { it.status != "Retired" }
}

class FleetViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = FleetRepo(fcp.sheets, fcp.uploads)
    private val user get() = Session(getApplication()).name.ifEmpty { "app" }

    val data = Loader(viewModelScope, fcp.store, FleetRaw.serializer())
    val tab = MutableStateFlow(FleetTab.BOARD)
    val busy = MutableStateFlow(false)

    val view: StateFlow<FleetView?> = data.state.map { l -> l.data?.let { FleetView(repo.book(it, user)) } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // what each form is editing (empty = a new entry) and which vehicle is picked
    val pick = MutableStateFlow<Map<String, String>>(emptyMap())
    val editing = MutableStateFlow<Map<String, String>>(emptyMap())
    /** a value the Papers form should start with, e.g. tapping a badge on the board */
    val docPreset = MutableStateFlow<Pair<String, String>?>(null)

    init { refresh() }

    fun refresh() { data.load(KEY) { repo.load() } }

    fun pickFor(form: String, v: FleetView): String? {
        val cur = pick.value[form]
        return if (cur != null && v.live.any { it.id == cur }) cur else v.live.firstOrNull()?.id
    }
    fun setPick(form: String, id: String) { pick.value = pick.value + (form to id) }
    fun setEditing(form: String, id: String?) { editing.value = if (id == null) editing.value - form else editing.value + (form to id) }

    /** Runs a change; done(message or null, failed). */
    fun save(okMessage: String, done: (String, Boolean) -> Unit = { _, _ -> }, action: suspend FleetRepo.(FleetBook) -> Unit) {
        busy.value = true
        viewModelScope.launch {
            try {
                val (_, fresh) = repo.change(user) { b -> repo.action(b) }
                data.set(KEY, fresh)
                done(okMessage, false)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't save.", true)
            } finally { busy.value = false }
        }
    }

    suspend fun upload(b: FleetBook, file: PickedFile?, subfolder: String, label: String, keep: String = ""): String =
        if (file == null) keep else repo.upload(b, file, subfolder, label)

    companion object { const val KEY = "fleet" }
}
