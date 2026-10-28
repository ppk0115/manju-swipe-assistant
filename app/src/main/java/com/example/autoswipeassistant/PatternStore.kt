package com.example.autoswipeassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PatternStore {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "默认 · 红果短剧 / 红果漫剧"
    const val KEY_ACTIVE = "active_pattern"
    const val KEY_REQUEST = "pattern_record_request"
    private const val KEY_PATTERNS = "learned_patterns_v1"
    const val LIMIT = 20
    private var cachedRaw: String? = null
    private var cachedPatterns: List<LearnedPattern> = emptyList()

    fun list(context: Context): List<LearnedPattern> {
        val raw = AppPreferences.prefs(context).getString(KEY_PATTERNS, "[]").orEmpty()
        if (raw == cachedRaw) return cachedPatterns
        val result = runCatching {
        val array = JSONArray(raw)
        (0 until array.length().coerceAtMost(LIMIT)).mapNotNull { i -> runCatching {
            val obj = array.getJSONObject(i)
            LearnedPattern(obj.getString("id"), obj.getString("name"), obj.getString("package"),
                sample(obj.getJSONObject("normal")), sample(obj.getJSONObject("ad")), sample(obj.getJSONObject("ready")),
                obj.optJSONObject("alternate")?.let { PatternSamples(sample(it.getJSONObject("normal")),
                    sample(it.getJSONObject("ad")), sample(it.getJSONObject("ready"))) })
        }.getOrNull() }.filter { it.id != DEFAULT_ID && it.id.isNotBlank() &&
            it.packageName == it.ready.packageName && PatternMatcher.validation(it.normal, it.advertisement, it.ready) == null &&
            (it.alternate == null || (it.alternate.ready.packageName == it.packageName &&
                PatternMatcher.validation(it.alternate.normal, it.alternate.advertisement, it.alternate.ready) == null &&
                !PatternMatcher.sameDirection(it.ready.aspect, it.alternate.ready.aspect))) }
        }.getOrDefault(emptyList())
        cachedRaw = raw
        cachedPatterns = result
        return result
    }

    fun active(context: Context): LearnedPattern? {
        val id = AppPreferences.prefs(context).getString(KEY_ACTIVE, DEFAULT_ID)
        return list(context).firstOrNull { it.id == id }
    }

    fun effectiveTargets(context: Context): Set<String> {
        val allowed = AppPreferences.targetPackages(context)
        val selected = active(context) ?: return allowed
        return allowed.intersect(setOf(selected.packageName))
    }

    fun select(context: Context, id: String) {
        require(id == DEFAULT_ID || list(context).any { it.id == id })
        val edit = AppPreferences.prefs(context).edit().putString(KEY_ACTIVE, id)
        if (id != DEFAULT_ID) edit.remove(AppPreferences.KEY_LAST_TEXT)
        edit.apply()
    }

    fun save(context: Context, pattern: LearnedPattern) {
        require(pattern.id.isNotBlank() && pattern.id != DEFAULT_ID && pattern.packageName == pattern.ready.packageName)
        require(pattern.name.isNotBlank() && pattern.name.length <= 30)
        require(PatternMatcher.validation(pattern.normal, pattern.advertisement, pattern.ready) == null)
        require(pattern.alternate == null || (PatternMatcher.validation(pattern.alternate.normal,
            pattern.alternate.advertisement, pattern.alternate.ready) == null &&
            !PatternMatcher.sameDirection(pattern.ready.aspect, pattern.alternate.ready.aspect)))
        val all = list(context).filterNot { it.id == pattern.id } + pattern
        require(all.size <= LIMIT)
        write(context, all)
    }

    fun delete(context: Context, id: String) {
        write(context, list(context).filterNot { it.id == id })
        if (AppPreferences.prefs(context).getString(KEY_ACTIVE, DEFAULT_ID) == id) select(context, DEFAULT_ID)
    }

    private fun write(context: Context, patterns: List<LearnedPattern>) {
        val array = JSONArray()
        patterns.forEach { p ->
            val obj = JSONObject().put("id", p.id).put("name", p.name).put("package", p.packageName)
                .put("normal", json(p.normal)).put("ad", json(p.advertisement)).put("ready", json(p.ready))
            p.alternate?.let { obj.put("alternate", JSONObject().put("normal", json(it.normal))
                .put("ad", json(it.advertisement)).put("ready", json(it.ready))) }
            array.put(obj)
        }
        AppPreferences.prefs(context).edit().putString(KEY_PATTERNS, array.toString()).apply()
    }

    private fun json(s: ControlSample) = JSONObject().put("package", s.packageName).put("aspect", s.aspect)
        .put("complete", s.complete).put("features", JSONArray(s.features.sorted()))
    private fun sample(obj: JSONObject): ControlSample {
        val values = obj.getJSONArray("features")
        require(values.length() <= 1200)
        return ControlSample(obj.getString("package"), obj.getDouble("aspect"),
            (0 until values.length()).map { values.getString(it) }.toSet(), obj.getBoolean("complete"))
    }
}
