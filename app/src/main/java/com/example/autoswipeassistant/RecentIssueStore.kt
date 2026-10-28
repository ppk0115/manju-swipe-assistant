package com.example.autoswipeassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Short, text-free explanations available even when verbose diagnostics are off. */
object RecentIssueStore {
    private const val KEY_ITEMS = "recent_issues_v1"
    private const val LIMIT = 8
    private const val REPEAT_INTERVAL_MS = 20_000L

    data class Issue(val at: Long, val packageName: String, val landscape: Boolean, val reason: String)

    fun list(context: Context): List<Issue> = runCatching {
        val array = JSONArray(AppPreferences.prefs(context).getString(KEY_ITEMS, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Issue(item.getLong("at"), item.getString("package"), item.getBoolean("landscape"), item.getString("reason"))
        }
    }.getOrDefault(emptyList())

    fun record(context: Context, packageName: String, landscape: Boolean, reason: String) {
        if (packageName.isBlank()) return
        val now = System.currentTimeMillis()
        val current = list(context)
        if (current.any { it.packageName == packageName && it.landscape == landscape && it.reason == reason &&
                now - it.at in 0 until REPEAT_INTERVAL_MS }) return
        val updated = (listOf(Issue(now, packageName, landscape, reason)) + current.filterNot {
            it.packageName == packageName && it.landscape == landscape && it.reason == reason
        }).take(LIMIT)
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("at", it.at).put("package", it.packageName)
            .put("landscape", it.landscape).put("reason", it.reason)) }
        AppPreferences.prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    fun clear(context: Context) {
        AppPreferences.prefs(context).edit().remove(KEY_ITEMS).apply()
    }
}
