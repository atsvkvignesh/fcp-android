package com.fibrocoir.app

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Notification groups (FCM topics). Every phone is always in "all".
 * The optional groups below can be switched on/off from the app menu
 * (press Back on the home page), or by the web page via window.FCPApp.
 */
object Topics {
    const val ALL = "all"

    /** topic -> label shown in the app menu */
    val OPTIONAL: LinkedHashMap<String, String> = linkedMapOf(
        "admin" to "Admin (owner alerts)",
        "tanker" to "Tanker in / out",
        "stock" to "Stock",
        "payments" to "Payments",
    )

    private const val PREFS = "fcp_prefs"
    private const val KEY = "topics"
    private val VALID = Regex("^[a-zA-Z0-9\\-_.~%]{1,900}$")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Topics this phone is subscribed to, always including "all". */
    fun subscribed(ctx: Context): Set<String> =
        (prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()) + ALL

    fun isValid(topic: String) = VALID.matches(topic)

    fun set(ctx: Context, topic: String, on: Boolean): Boolean {
        val t = topic.trim()
        if (!isValid(t) || t == ALL) return false
        val current = (prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (on) current.add(t) else current.remove(t)
        prefs(ctx).edit().putStringSet(KEY, current).apply()
        val fm = FirebaseMessaging.getInstance()
        if (on) fm.subscribeToTopic(t) else fm.unsubscribeFromTopic(t)
        return true
    }

    /** Called at launch and on token refresh, so subscriptions are never lost. */
    fun resubscribeAll(ctx: Context) {
        val fm = FirebaseMessaging.getInstance()
        subscribed(ctx).forEach { fm.subscribeToTopic(it) }
    }
}
