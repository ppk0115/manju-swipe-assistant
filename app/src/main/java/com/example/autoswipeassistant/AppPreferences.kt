package com.example.autoswipeassistant

import android.content.Context

object AppPreferences {
    private const val FILE_NAME = "auto_swipe_settings"
    const val KEY_AUTOMATION_ENABLED = "automation_enabled"
    const val KEY_OBSERVE_ONLY = "observe_only"
    const val KEY_TARGET_PACKAGES = "target_packages"
    const val KEY_TARGETS_LOCKED = "targets_locked"
    const val KEY_CANDIDATE_SCAN_ENABLED = "candidate_scan_enabled"
    const val KEY_DETECTED_PACKAGES = "detected_packages"
    const val KEY_LAST_EXTERNAL_PACKAGE = "last_external_package"
    const val KEY_LAST_STATUS = "last_status"
    const val KEY_LAST_STATUS_PACKAGE = "last_status_package"
    const val KEY_LAST_TEXT = "last_text"
    const val KEY_LAST_ACTION_AT = "last_action_at"
    const val KEY_LAST_ACTION_PACKAGE = "last_action_package"
    const val KEY_REDESIGN_DEFAULTS_APPLIED = "redesign_defaults_applied"
    const val KEY_CUSTOM_RULES_ENABLED = "custom_rules_enabled"
    const val KEY_CUSTOM_PROMPTS = "custom_prompts"
    const val KEY_AD_BADGE_LOOP_ENABLED = "ad_badge_loop_enabled"

    /** Migration for versions that persisted page text even with diagnostics disabled. */
    fun clearLegacyText(context: Context) {
        val settings = prefs(context)
        if (settings.contains(KEY_LAST_TEXT)) settings.edit().remove(KEY_LAST_TEXT).apply()
        if (settings.contains("landscape_ocr_enabled")) settings.edit().remove("landscape_ocr_enabled").apply()
    }

    fun customPrompts(context: Context): List<String>? {
        val settings = prefs(context)
        if (!settings.getBoolean(KEY_CUSTOM_RULES_ENABLED, false)) return null
        return PromptDetector.parseCustomPrompts(settings.getString(KEY_CUSTOM_PROMPTS, "").orEmpty())
    }

    fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun targetPackages(context: Context): Set<String> =
        parsePackages(prefs(context).getString(KEY_TARGET_PACKAGES, "").orEmpty())

    fun saveTargetPackages(context: Context, packages: Set<String>) {
        prefs(context).edit()
            .putString(KEY_TARGET_PACKAGES, formatPackages(packages))
            .apply()
    }

    fun detectedPackages(context: Context): List<String> =
        prefs(context).getString(KEY_DETECTED_PACKAGES, "").orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    fun saveDetectedPackages(context: Context, packages: List<String>) {
        prefs(context).edit()
            .putString(KEY_DETECTED_PACKAGES, packages.distinct().joinToString("\n"))
            .apply()
    }

    fun removeDetectedPackage(context: Context, packageName: String) {
        saveDetectedPackages(context, detectedPackages(context).filterNot { it == packageName })
    }

    fun parsePackages(raw: String): Set<String> = raw
        .split(Regex("[,，;；\\s]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    fun formatPackages(packages: Set<String>): String = packages.sorted().joinToString("\n")
}
