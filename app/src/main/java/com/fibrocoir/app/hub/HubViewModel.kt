package com.fibrocoir.app.hub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HubViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = HubRepo(fcp.sheets)
    val session = Session(app)

    val hub = Loader(viewModelScope, fcp.store, HubData.serializer())
    val signedIn = MutableStateFlow(session.userId != null)
    val busy = MutableStateFlow(false)

    init { refresh() }

    fun refresh() {
        hub.load(KEY) { repo.load() }.invokeOnCompletion {
            // someone removed this person (or changed them to data entry): follow the sheet
            val data = hub.state.value.data ?: return@invokeOnCompletion
            val id = session.userId ?: return@invokeOnCompletion
            val me = data.users.firstOrNull { it.id == id }
            if (me == null) signOut() else if (me.role != session.role || me.name != session.name) session.signIn(me)
        }
    }

    fun me(): HubUser? = hub.state.value.data?.users?.firstOrNull { it.id == session.userId }

    /** Returns an error message, or null when signed in. */
    fun signIn(login: String, password: String, done: (String?) -> Unit) {
        val lock = session.lockedFor()
        if (lock > 0) { done("Too many attempts. Wait ${lock / 60_000 + 1} minutes and try again."); return }
        busy.value = true
        viewModelScope.launch {
            // check against the newest list if we can, else the saved one (works offline)
            val data = try { repo.load().also { hub.set(KEY, it) } } catch (e: Exception) {
                hub.state.value.data ?: run { busy.value = false; done(e.message); return@launch }
            }
            val user = data.users.firstOrNull { it.login == login.trim().lowercase() }
            busy.value = false
            if (user == null || HubRepo.hash(password, user.salt) != user.hash) {
                session.failed(); done("Wrong user name or password."); return@launch
            }
            session.signIn(user); signedIn.value = true; done(null)
        }
    }

    fun signOut() {
        session.signOut(); signedIn.value = false
    }

    /** Runs an admin change, then re-reads the sheet. */
    fun change(what: suspend HubRepo.(HubData) -> Unit, done: (String?) -> Unit) {
        val data = hub.state.value.data ?: return done("Still loading.")
        busy.value = true
        viewModelScope.launch {
            val err = try { repo.what(data); null } catch (e: Exception) { e.message ?: "Couldn't save." }
            try { hub.set(KEY, repo.load()) } catch (_: Exception) { }
            busy.value = false
            done(err)
        }
    }

    companion object { const val KEY = "hub" }
}
