package com.fibrocoir.app.core

/**
 * The Google Sheets each part of the app reads and writes. Every one of them must be shared
 * (as Editor) with the app's service account; the hub's Settings screen shows its address.
 */
object FcpSheets {
    /** Users, their app access and the hub tiles (made once by apps-script/hub/ExportHub.gs). */
    const val HUB = "HUB_SHEET_ID"

    /** EC Log and EC Viewer: one tab per day per person, plus "Limits". */
    const val EC = "1W0EoXjd-rn7-X68jqzMJ2aZPQmud_Nm0N1UcgTvu5ew"

    /** Water Load: "Log" and "Tankers", written by the ESP32 card readers. */
    const val WATER = "18fF22Kbsp1l9tMJafUdlfYPm0b4mDc5zVrraOI18-t0"

    /** Raw material check: our husk loads form, the vendor form (+ "RM Matches"). */
    const val HUSK_COMPANY = "1jKjsqbMxlpPFfQ5_z2N74RqzbOOA5cnSYEPo36o3zvk"
    const val HUSK_VENDOR = "1kNlaqWPhxasycg_QPkkBkshdYJ_Cvcnc0zqiUH0jHnA"

    /** Stores inventory (Items, Movements, People, ...). */
    const val INVENTORY = "INVENTORY_SHEET_ID"

    /** Fleet manager (Vehicles, logs, service, documents, settings). */
    const val FLEET = "1M0ai13frJbB2VRI2MSIQvwRsc18431VCHfWxxw-EEF8"

    const val TZ = "Asia/Kolkata"

    fun url(id: String, gid: Int? = null) =
        "https://docs.google.com/spreadsheets/d/$id/edit" + (gid?.let { "#gid=$it" } ?: "")
}
