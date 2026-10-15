package com.fibrocoir.app.hub

import android.content.Context
import com.fibrocoir.app.core.FcpSheets
import com.fibrocoir.app.core.google.Cells.cell
import com.fibrocoir.app.core.google.Cells.text
import com.fibrocoir.app.core.google.Sheets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Who can sign in and which tiles they see, kept in the hub sheet:
 *   Users: id | login | name | role (admin/user) | salt | hash | apps (blank = all, else ids)
 *   Apps:  id | name  | icon | url | screen (built-in screen key, blank = open the link)
 * Passwords are salted SHA-256, the same as the old hub script, so they keep working.
 */
@Serializable
data class HubUser(
    val id: String, val login: String, val name: String, val role: String,
    val salt: String, val hash: String, val apps: List<String>? = null, val row: Int = 0,
) {
    val admin get() = role == "admin"
    fun sees(app: HubApp) = admin || apps == null || app.id in apps
}

@Serializable
data class HubApp(
    val id: String, val name: String, val icon: String = "", val url: String = "",
    val screen: String = "", val row: Int = 0,
)

@Serializable
data class HubData(val users: List<HubUser> = emptyList(), val apps: List<HubApp> = emptyList())

/** The built-in screens a tile can open. */
enum class Screen(val key: String, val title: String) {
    EC_LOG("ec-log", "EC Log"),
    EC_VIEWER("ec-viewer", "EC Viewer"),
    HUSK("husk-check", "Raw material check"),
    INVENTORY("inventory", "Stores inventory"),
    FLEET("fleet", "Fleet manager"),
    WATER("water-load", "Water record");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key?.trim() }
    }
}

class HubRepo(private val sheets: Sheets) {
    private val id get() = FcpSheets.HUB

    suspend fun load(): HubData {
        val (u, a) = sheets.read(id, listOf("Users!A2:G", "Apps!A2:E"))
        val users = u.mapIndexedNotNull { i, r ->
            val login = r.cell(1).text().trim().lowercase()
            if (login.isEmpty()) return@mapIndexedNotNull null
            val apps = r.cell(6).text().trim()
            HubUser(
                id = r.cell(0).text().ifBlank { "u$i" }, login = login, name = r.cell(2).text().ifBlank { login },
                role = if (r.cell(3).text().trim().lowercase() == "admin") "admin" else "user",
                salt = r.cell(4).text(), hash = r.cell(5).text().lowercase(),
                apps = if (apps.isEmpty() || apps.lowercase() == "all") null
                       else apps.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "-" },
                row = i + 2,
            )
        }
        val apps = a.mapIndexedNotNull { i, r ->
            val name = r.cell(1).text().trim()
            if (name.isEmpty()) return@mapIndexedNotNull null
            HubApp(
                id = r.cell(0).text().ifBlank { "a$i" }, name = name, icon = r.cell(2).text(),
                url = r.cell(3).text().trim(), screen = r.cell(4).text().trim(), row = i + 2,
            )
        }
        return HubData(users, apps)
    }

    // ------------------------------------------------------------------ apps (tiles)

    suspend fun saveApp(app: HubApp, data: HubData) {
        if (app.row > 0) {
            sheets.write(id, "Apps!A${app.row}:E${app.row}", listOf(listOf(app.id, app.name, app.icon, app.url, app.screen)))
        } else {
            sheets.append(id, "Apps!A1:E1", listOf(listOf(newId(), app.name, app.icon, app.url, app.screen)))
        }
    }

    suspend fun deleteApp(app: HubApp, data: HubData) {
        deleteRow("Apps", app.row)
        // nobody keeps access to a tile that is gone
        data.users.filter { it.apps?.contains(app.id) == true }.forEach { u ->
            writeAccess(u, u.apps!!.filter { it != app.id })
        }
    }

    /** Rewrites the tile list in this order. */
    suspend fun reorder(apps: List<HubApp>) {
        sheets.write(id, "Apps!A2:E${apps.size + 1}", apps.map { listOf(it.id, it.name, it.icon, it.url, it.screen) })
    }

    // ------------------------------------------------------------------ people

    suspend fun addUser(login: String, name: String, role: String, password: String, data: HubData) {
        val l = login.trim().lowercase()
        require(Regex("^[a-z0-9._@-]{3,}$").matches(l)) { "User name: 3 or more letters, numbers, dot, dash or @ — no spaces." }
        require(data.users.none { it.login == l }) { "That user name is taken." }
        checkPassword(password)
        val salt = UUID.randomUUID().toString()
        sheets.append(id, "Users!A1:G1", listOf(listOf(newId(), l, name.trim().ifEmpty { l }, role, salt, hash(password, salt), "")))
    }

    suspend fun deleteUser(user: HubUser, data: HubData) {
        require(data.users.any { it.admin && it.id != user.id }) { "Keep at least one admin." }
        deleteRow("Users", user.row)
    }

    suspend fun setPassword(user: HubUser, password: String) {
        checkPassword(password)
        val salt = UUID.randomUUID().toString()
        sheets.write(id, "Users!E${user.row}:F${user.row}", listOf(listOf(salt, hash(password, salt))))
    }

    /** apps = null for every tile, or the tile ids this person may open. */
    suspend fun writeAccess(user: HubUser, apps: List<String>?) {
        sheets.write(id, "Users!G${user.row}", listOf(listOf(apps?.joinToString(",")?.ifEmpty { "-" } ?: "")))
    }

    private suspend fun deleteRow(tab: String, row: Int) {
        val t = sheets.info(id).tab(tab) ?: error("No $tab tab in the hub sheet.")
        sheets.batchUpdate(id, listOf(buildJsonObject {
            putJsonObject("deleteDimension") {
                putJsonObject("range") {
                    put("sheetId", t.id); put("dimension", "ROWS")
                    put("startIndex", row - 1); put("endIndex", row)
                }
            }
        }))
    }

    companion object {
        fun hash(password: String, salt: String): String =
            MessageDigest.getInstance("SHA-256").digest("$salt|$password".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun checkPassword(pw: String) = require(pw.length >= 6) { "Password needs at least 6 characters." }

        fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/** Who is signed in on this phone, and the failed-attempt cool-down. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("fcp_session", Context.MODE_PRIVATE)

    val userId: String? get() = prefs.getString("id", null)
    val name: String get() = prefs.getString("name", "") ?: ""
    val role: String get() = prefs.getString("role", "user") ?: "user"

    fun signIn(u: HubUser) {
        prefs.edit().putString("id", u.id).putString("name", u.name).putString("role", u.role)
            .remove("fails").remove("failAt").apply()
    }

    fun signOut() { prefs.edit().remove("id").remove("name").remove("role").apply() }

    /** Same rule as the hub script: 8 wrong passwords, then wait 15 minutes. */
    fun lockedFor(): Long {
        val fails = prefs.getInt("fails", 0)
        val at = prefs.getLong("failAt", 0)
        val left = at + 15 * 60_000 - System.currentTimeMillis()
        return if (fails >= 8 && left > 0) left else 0
    }

    fun failed() {
        val at = prefs.getLong("failAt", 0)
        val fresh = System.currentTimeMillis() - at > 15 * 60_000
        prefs.edit().putInt("fails", if (fresh) 1 else prefs.getInt("fails", 0) + 1)
            .putLong("failAt", System.currentTimeMillis()).apply()
    }
}
