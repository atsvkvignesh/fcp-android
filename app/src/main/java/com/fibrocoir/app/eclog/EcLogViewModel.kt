package com.fibrocoir.app.eclog

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibrocoir.app.FcpApplication
import com.fibrocoir.app.core.Loader
import com.fibrocoir.app.core.google.OfflineException
import com.fibrocoir.app.ec.EcBoot
import com.fibrocoir.app.ec.EcRepo
import com.fibrocoir.app.ec.EcTab
import com.fibrocoir.app.ec.Entry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * EC Log: the person picks their name, then every reading goes into today's tab for them.
 * With no signal, readings wait on the phone and go up by themselves when it is back.
 */
class EcLogViewModel(app: Application) : AndroidViewModel(app) {
    private val fcp = app as FcpApplication
    private val repo = EcRepo(fcp.sheets, fcp.store)
    private val prefs = app.getSharedPreferences("ec_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val operator = MutableStateFlow(prefs.getString("op", "") ?: "")
    val boot = Loader(viewModelScope, fcp.store, EcBoot.serializer())
    val queue = MutableStateFlow(loadQueue())
    val saving = MutableStateFlow(false)
    val unlocked = MutableStateFlow(false)
    /** readings made before this time are hidden from the list (they stay in the sheet) */
    val cleared = MutableStateFlow(prefs.getLong("cleared", 0L))
    val online = MutableStateFlow(true)

    private var touched = 0L
    private val flushLock = Mutex()

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { online.value = true; viewModelScope.launch { flush() } }
        override fun onLost(network: Network) { online.value = false }
    }

    init {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        try { cm.registerDefaultNetworkCallback(net) } catch (_: Exception) { }
        online.value = cm.activeNetwork != null
        refresh()
        // the Head QC unlock closes itself after 5 quiet minutes, like the web page
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (unlocked.value && System.currentTimeMillis() - touched > AUTO_LOCK_MS) unlocked.value = false
            }
        }
    }

    override fun onCleared() {
        try { getApplication<Application>().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) } catch (_: Exception) { }
    }

    fun refresh() {
        val op = operator.value
        boot.load("eclog.boot.$op") { repo.boot(op) }.invokeOnCompletion { viewModelScope.launch { flush() } }
    }

    fun start(name: String) {
        val n = name.trim()
        prefs.edit().putString("op", n).apply()
        operator.value = n
        refresh()
    }

    fun switchOperator() { lock(); operator.value = ""; prefs.edit().remove("op").apply() }

    // ------------------------------------------------------------------ saving

    /** Saves one reading. Without signal it waits on the phone. Returns a message for the person. */
    fun save(e: Entry, done: (ok: Boolean, msg: String) -> Unit) {
        saving.value = true
        viewModelScope.launch {
            try {
                val tab = repo.add(e)
                setTab(tab)
                done(true, "Saved to the sheet.")
            } catch (x: OfflineException) {
                queue.value = queue.value + e; saveQueue()
                done(true, "Held on this phone — no connection. It will go up when signal is back.")
            } catch (x: IllegalArgumentException) {
                done(false, x.message ?: "Check the reading.")
            } catch (x: Exception) {
                queue.value = queue.value + e; saveQueue()
                done(true, "Held on this phone — the sheet didn't answer (${x.message}). It will try again.")
            } finally { saving.value = false }
        }
    }

    fun update(row: Int, oldSno: String, e: Entry, done: (Boolean, String) -> Unit) {
        val date = boot.state.value.data?.date ?: return
        touch(); saving.value = true
        viewModelScope.launch {
            try { setTab(repo.update(date, operator.value, row, oldSno, e)); done(true, "Reading corrected.") }
            catch (x: Exception) { done(false, x.message ?: "Couldn't save the change.") }
            finally { saving.value = false }
        }
    }

    fun remove(row: Int, sno: String, done: (String) -> Unit) {
        val date = boot.state.value.data?.date ?: return
        touch(); saving.value = true
        viewModelScope.launch {
            try { setTab(repo.remove(date, operator.value, row, sno)); done("Removed.") }
            catch (x: Exception) { done(x.message ?: "Couldn't remove it.") }
            finally { saving.value = false }
        }
    }

    /** Sends the readings waiting on the phone, oldest first. Returns how many are still waiting. */
    suspend fun flush(): Int = flushLock.withLock {
        while (queue.value.isNotEmpty()) {
            val next = queue.value.first()
            try { setTab(repo.add(next)) } catch (x: IllegalArgumentException) {
                // a reading the sheet will never take: drop it rather than block the rest
            } catch (x: Exception) { return queue.value.size }
            queue.value = queue.value.drop(1); saveQueue()
        }
        0
    }

    fun sendWaiting(done: (String) -> Unit) {
        viewModelScope.launch {
            val left = flush()
            done(if (left == 0) "All readings sent." else "Still no connection. They are safe on this phone.")
        }
    }

    private fun setTab(tab: EcTab) {
        val cur = boot.state.value.data ?: return
        if (tab.who != com.fibrocoir.app.ec.Ec.cleanName(operator.value)) return
        boot.set("eclog.boot.${operator.value}", cur.copy(tab = tab, date = tab.date,
            spots = (listOfNotNull(tab.rows.lastOrNull()?.takeIf { it.point == "Yard" }?.spot) + cur.spots).distinct()))
    }

    // ------------------------------------------------------------------ Head QC lock

    fun unlock(pin: String): Boolean {
        if (pin.trim() != ADMIN_PIN) return false
        unlocked.value = true; touch(); return true
    }
    fun lock() { unlocked.value = false }
    fun touch() { touched = System.currentTimeMillis() }

    fun clearList() { val t = System.currentTimeMillis(); cleared.value = t; prefs.edit().putLong("cleared", t).apply() }
    fun showAll() { cleared.value = 0; prefs.edit().putLong("cleared", 0).apply() }

    // ------------------------------------------------------------------ queue on the phone

    private fun loadQueue(): List<Entry> = try {
        json.decodeFromString(ListSerializer(Entry.serializer()), prefs.getString("queue", "[]") ?: "[]")
    } catch (_: Exception) { emptyList() }

    private fun saveQueue() { prefs.edit().putString("queue", json.encodeToString(ListSerializer(Entry.serializer()), queue.value)).apply() }

    companion object {
        /** The Head QC password (same as the web page). */
        const val ADMIN_PIN = "FCP12600"
        const val AUTO_LOCK_MS = 5 * 60_000L
        val YARD_SPOTS = listOf(
            "Balan Kulivaial Yard 1", "Balan Kulivaial Yard 2", "Balan Kannimula Valial Yard",
            "Balan Mango Trees Yard", "Balan L Bed",
        )
        val CONDITIONS = listOf("Dry", "Half dry — from the kumi", "Half dry", "Wet")
    }
}
