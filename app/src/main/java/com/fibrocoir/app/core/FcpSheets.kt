package com.fibrocoir.app.core

/**
 * The Google Sheets each part of the app reads and writes. Every one of them must be shared
 * (as Editor) with the app's service account; the hub's Settings screen shows its address.
 */
object FcpSheets {
    /**
     * Users, their app access and the hub tiles (made once by apps-script/hub/ExportHub.gs).
     * Set at build time (FCP_HUB_SHEET_ID) or pasted on the sign-in screen.
     */
    val HUB: String get() = saved("hub") ?: com.fibrocoir.app.BuildConfig.HUB_SHEET_ID

    /** EC Log and EC Viewer: one tab per day per person, plus "Limits". */
    const val EC = "1W0EoXjd-rn7-X68jqzMJ2aZPQmud_Nm0N1UcgTvu5ew"

    /** Water Load: "Log" and "Tankers", written by the ESP32 card readers. */
    const val WATER = "18fF22Kbsp1l9tMJafUdlfYPm0b4mDc5zVrraOI18-t0"

    /** Raw material check: our husk loads form, the vendor form (+ "RM Matches"). */
    const val HUSK_COMPANY = "1jKjsqbMxlpPFfQ5_z2N74RqzbOOA5cnSYEPo36o3zvk"
    const val HUSK_VENDOR = "1kNlaqWPhxasycg_QPkkBkshdYJ_Cvcnc0zqiUH0jHnA"

    /** Stores inventory (Items, Movements, People, ...). Build time (FCP_INVENTORY_SHEET_ID) or pasted in the app. */
    val INVENTORY: String get() = saved("inventory") ?: com.fibrocoir.app.BuildConfig.INVENTORY_SHEET_ID

    /** Fleet manager (Vehicles, logs, service, documents, settings). */
    const val FLEET = "1M0ai13frJbB2VRI2MSIQvwRsc18431VCHfWxxw-EEF8"

    const val TZ = "Asia/Kolkata"

    private fun prefs() = com.fibrocoir.app.FcpApplication.instance.getSharedPreferences("fcp_sheets", android.content.Context.MODE_PRIVATE)
    private fun saved(key: String): String? = try { prefs().getString(key, null)?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    /** The id inside a Google Sheets link (or the id itself). */
    fun idOf(link: String): String? {
        val t = link.trim()
        Regex("/spreadsheets/d/([a-zA-Z0-9_-]{20,})").find(t)?.let { return it.groupValues[1] }
        return t.takeIf { Regex("^[a-zA-Z0-9_-]{25,}$").matches(it) }
    }

    /** Keeps a pasted sheet link for "hub" or "inventory". Returns false if it isn't a sheet link. */
    fun remember(key: String, link: String): Boolean {
        val id = idOf(link) ?: return false
        prefs().edit().putString(key, id).apply()
        return true
    }

    fun isSet(id: String) = id.isNotBlank() && !id.endsWith("_SHEET_ID")

    fun url(id: String, gid: Int? = null) =
        "https://docs.google.com/spreadsheets/d/$id/edit" + (gid?.let { "#gid=$it" } ?: "")
}
