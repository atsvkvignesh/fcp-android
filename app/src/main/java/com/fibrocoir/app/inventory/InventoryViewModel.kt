package com.fibrocoir.app.inventory

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import com.fibrocoir.app.core.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InvPage { SEARCH, ITEM, PERSON, QTY, MACHINE, PIN, CONFIRM, DONE, NEW, DUPES, EDIT, PEOPLE }
enum class InvMode { MOVE, NEW, EDIT, PERSON, ACTIVE }

/** The edit form, as typed. */
data class EditForm(
    val name: String = "", val tamil: String = "", val std: String = "", val cat: String = "", val unit: String = "",
    val region: String = "", val bin: String = "", val machines: List<String> = emptyList(), val dim1: String = "",
    val dim2: String = "", val dim3: String = "", val material: String = "", val swap: String = "", val min: String = "",
    val reorder: String = "", val lead: String = "", val status: String = "ACTIVE", val counted: String = "",
)

class Photo(val file: PickedFile, val uri: Uri)

class InventoryViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = InvRepo(fcp.sheets, fcp.uploads)

    val data = Loader(viewModelScope, fcp.store, InvData.serializer())
    val stack = MutableStateFlow(listOf(InvPage.SEARCH))
    val query = MutableStateFlow("")
    val cat = MutableStateFlow("")
    val reg = MutableStateFlow("")

    val itemId = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(InvMode.MOVE)
    val act = MutableStateFlow("TAKE")
    val person = MutableStateFlow<InvPerson?>(null)
    val supervisor = MutableStateFlow(false)
    val qty = MutableStateFlow(1.0)
    val machine = MutableStateFlow<InvMachine?>(null)
    val pin = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val formError = MutableStateFlow<String?>(null)
    val done = MutableStateFlow<InvDone?>(null)

    val newForm = MutableStateFlow(Inv.NewItem())
    val newPhoto1 = MutableStateFlow<Photo?>(null)
    val newPhoto2 = MutableStateFlow<Photo?>(null)
    val similar = MutableStateFlow<List<InvItem>>(emptyList())

    val editForm = MutableStateFlow(EditForm())
    val editPhoto = MutableStateFlow<Photo?>(null)

    val personForm = MutableStateFlow(Inv.NewPerson())
    val personPhoto = MutableStateFlow<Photo?>(null)
    val activeTarget = MutableStateFlow<Pair<InvPerson, Boolean>?>(null)

    init { refresh() }

    fun refresh() { data.load(KEY) { repo.load() } }

    private val d get() = data.state.value.data

    // ------------------------------------------------------------------ pages

    fun go(p: InvPage) = stack.update { it + p }
    /** Back one page; false when already on the first page. */
    fun back(): Boolean {
        val s = stack.value
        if (s.size <= 1) return false
        stack.value = if (s.last() == InvPage.DONE) home() else s.dropLast(1)
        return true
    }
    private fun home() = if (itemId.value != null && mode.value == InvMode.MOVE || mode.value == InvMode.EDIT) listOf(InvPage.SEARCH, InvPage.ITEM) else listOf(InvPage.SEARCH)
    fun finish() { val h = home(); mode.value = InvMode.MOVE; stack.value = h }

    fun openItem(id: String) { itemId.value = id; mode.value = InvMode.MOVE; stack.value = listOf(InvPage.SEARCH, InvPage.ITEM) }

    // ------------------------------------------------------------------ take / return / add stock

    fun startAction(a: String) {
        mode.value = InvMode.MOVE; act.value = a; person.value = null; supervisor.value = false
        machine.value = null; pin.value = ""; qty.value = 1.0
        go(InvPage.PERSON)
    }

    /** People offered on the person page for what is being done. */
    fun peopleFor(d: InvData): List<InvPerson> = when (mode.value) {
        InvMode.MOVE -> d.activePeople.filter { act.value != "RECEIVE" || it.canReceive }
        else -> d.creators
    }

    fun pickPerson(p: InvPerson?) {
        person.value = p; supervisor.value = p == null; pin.value = ""
        when (mode.value) {
            InvMode.MOVE -> { qty.value = 1.0; go(InvPage.QTY) }
            else -> go(InvPage.PIN)
        }
    }

    fun bump(dir: Int, unit: String) {
        val step = if (unit == "KG" || unit == "L") 0.5 else 1.0
        qty.update { Math.max(step, Math.round((it + dir * step) * 100) / 100.0) }
    }

    fun afterQty() {
        val it = itemId.value?.let { id -> d?.item(id) } ?: return
        when {
            act.value == "RECEIVE" -> go(InvPage.PIN)
            act.value == "TAKE" && it.cat == "SPARE" && d?.activeMachines?.isNotEmpty() == true -> go(InvPage.MACHINE)
            else -> go(InvPage.CONFIRM)
        }
    }

    fun pickMachine(m: InvMachine?) { machine.value = m; go(InvPage.CONFIRM) }

    fun key(k: String) {
        when (k) {
            "back" -> pin.update { it.dropLast(1) }
            "ok" -> if (pin.value.length >= 4) pinDone()
            else -> if (pin.value.length < 6) pin.update { it + k }
        }
    }

    private fun pinDone() {
        when (mode.value) {
            InvMode.MOVE -> go(InvPage.CONFIRM)
            InvMode.NEW -> saveNew()
            InvMode.EDIT -> saveEdit()
            InvMode.PERSON -> savePerson()
            InvMode.ACTIVE -> saveActive()
        }
    }

    fun send() {
        val id = itemId.value ?: return
        val p = person.value ?: return
        run({
            repo.move(Inv.Move(act.value, id, qty.value, p.id, machine.value?.id.orEmpty(),
                if (act.value == "TAKE") "GENERAL" else "", pin.value, device = Build.MANUFACTURER + " " + Build.MODEL))
        })
    }

    // ------------------------------------------------------------------ new part

    fun openNew() {
        mode.value = InvMode.NEW; formError.value = null
        newForm.value = Inv.NewItem(); newPhoto1.value = null; newPhoto2.value = null
        go(InvPage.NEW)
    }

    fun checkNew() {
        val f = newForm.value
        formError.value = when {
            newPhoto1.value == null -> "Take a photo of the part first."
            f.name.isBlank() -> "Give the item a name in English."
            f.category.isEmpty() -> "Pick spare, consumable or tool."
            f.unit.isEmpty() -> "Pick a unit."
            f.region.isEmpty() -> "Pick a region."
            else -> null
        }
        if (formError.value != null) return
        val dd = d ?: return
        similar.value = Inv.similar(dd, f.name, f.std, f.region)
        if (similar.value.isEmpty()) newPickPerson() else go(InvPage.DUPES)
    }

    fun newPickPerson() { mode.value = InvMode.NEW; person.value = null; go(InvPage.PERSON) }

    private fun saveNew() = run({
        repo.create(newForm.value.copy(personId = person.value?.id.orEmpty(), pin = pin.value), newPhoto1.value?.file, newPhoto2.value?.file)
    }) { itemId.value = it.itemId.ifEmpty { null } }

    // ------------------------------------------------------------------ edit

    fun openEdit() {
        val it = itemId.value?.let { id -> d?.item(id) } ?: return
        mode.value = InvMode.EDIT; formError.value = null; editPhoto.value = null
        editForm.value = EditForm(
            it.name, it.tamil, it.std, it.cat, it.unit, it.region, it.bin,
            it.machines.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() },
            it.dim1, it.dim2, it.dim3, it.material, it.swap,
            it.min?.let(Inv::qtyText).orEmpty(), it.reorder?.let(Inv::qtyText).orEmpty(), it.lead?.let(Inv::qtyText).orEmpty(),
            it.activeStatus.ifEmpty { "ACTIVE" }, "",
        )
        go(InvPage.EDIT)
    }

    fun editPickPerson() {
        if (editForm.value.name.isBlank()) { formError.value = "Name cannot be blank."; return }
        formError.value = null; mode.value = InvMode.EDIT; person.value = null; go(InvPage.PERSON)
    }

    private fun saveEdit() {
        val f = editForm.value
        val id = itemId.value ?: return
        run({
            repo.edit(Inv.Edit(id, f.name, f.tamil, f.std, f.cat, f.unit, f.region, f.bin, f.dim1, f.dim2, f.dim3, f.material, f.swap,
                f.min, f.reorder, f.lead, f.status, f.machines, f.counted, false, person.value?.id.orEmpty(), pin.value), editPhoto.value?.file)
        })
    }

    // ------------------------------------------------------------------ people

    fun openPeople() { mode.value = InvMode.PERSON; personForm.value = Inv.NewPerson(); personPhoto.value = null; formError.value = null; go(InvPage.PEOPLE) }

    fun addPersonPick() {
        val f = personForm.value
        formError.value = when {
            f.name.isBlank() -> "Name is needed"
            (f.canReceive || f.canCreate) && f.newPin.isBlank() -> "Give them a PIN, or remove the permissions"
            else -> null
        }
        if (formError.value != null) return
        mode.value = InvMode.PERSON; person.value = null; go(InvPage.PERSON)
    }

    private fun savePerson() = run({
        repo.addPerson(personForm.value.copy(byPersonId = person.value?.id.orEmpty(), pin = pin.value), personPhoto.value?.file)
    }) { personForm.value = Inv.NewPerson(); personPhoto.value = null }

    fun toggleActive(p: InvPerson) {
        activeTarget.value = p to !p.active; mode.value = InvMode.ACTIVE; person.value = null; go(InvPage.PERSON)
    }

    private fun saveActive() {
        val (target, on) = activeTarget.value ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                data.set(KEY, repo.setActive(person.value?.id.orEmpty(), pin.value, target.id, on))
                stack.value = listOf(InvPage.SEARCH, InvPage.PEOPLE); mode.value = InvMode.PERSON
                formError.value = "${target.name} is now ${if (on) "on" else "off"}."
            } catch (e: Exception) {
                done.value = InvDone(false, "Not saved", listOf(e.message ?: "Something went wrong.", "Nothing changed. Go back and try again."))
                go(InvPage.DONE)
            } finally { busy.value = false; pin.value = "" }
        }
    }

    // ------------------------------------------------------------------ saving

    private fun run(work: suspend () -> Pair<InvDone, InvData>, after: (InvDone) -> Unit = {}) {
        busy.value = true
        viewModelScope.launch {
            try {
                val (r, fresh) = work()
                data.set(KEY, fresh)
                after(r)
                done.value = r
            } catch (e: Exception) {
                done.value = InvDone(false, "Not saved", listOf(e.message ?: "Something went wrong.", "Nothing changed. Go back and try again."))
            } finally {
                busy.value = false; pin.value = ""
                go(InvPage.DONE)
            }
        }
    }

    companion object { const val KEY = "inventory" }
}
