package com.example.autoswipeassistant

object CandidateHistory {
    /** Oldest first; reopening an app moves it to the most-recent end. */
    fun touch(packages: List<String>, packageName: String): List<String> =
        if (packages.lastOrNull() == packageName) packages
        else packages.filterNot { it == packageName } + packageName
}
