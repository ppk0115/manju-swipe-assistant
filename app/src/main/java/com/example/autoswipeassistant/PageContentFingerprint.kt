package com.example.autoswipeassistant

/**
 * Produces a stable representation of the visible page while excluding the
 * countdown/ready prompt that is reused between ads by some applications.
 */
object PageContentFingerprint {
    private val standaloneTimer = Regex("^[0-9]{1,2}(?::[0-9]{2}|s|秒)?$", RegexOption.IGNORE_CASE)
    private val engagementCounter = Regex("^(?:(?:点赞|评论|收藏|分享|播放|观看|热度)[:：]?)?[0-9]+(?:[.,][0-9]+)*(?:万|亿|[kKwW])?(?:人观看|次播放|点赞|评论|收藏|分享)?$")
    private val whitespace = Regex("\\s+")

    fun from(rawText: String, customPrompts: List<String>? = null): String = rawText
        .lineSequence()
        .map { line -> line.trim().replace(whitespace, "") }
        .filter(String::isNotBlank)
        .filterNot { line -> PromptDetector.detect(line, customPrompts) != PromptState.None }
        .filterNot { line -> standaloneTimer.matches(line) || engagementCounter.matches(line) }
        .distinct()
        .sorted()
        .joinToString("\n")
        .take(MAX_FINGERPRINT_LENGTH)

    private const val MAX_FINGERPRINT_LENGTH = 4_000
}
