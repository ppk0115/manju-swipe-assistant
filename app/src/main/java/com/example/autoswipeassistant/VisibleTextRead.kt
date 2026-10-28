package com.example.autoswipeassistant

/** Incomplete reads must never count as evidence that a prompt disappeared. */
data class VisibleTextRead(
    val text: String,
    val segments: List<String>,
    val complete: Boolean,
    val fragments: List<InlineTextFragment> = emptyList(),
    val window: WindowArea? = null,
    val nodeCount: Int = 0,
    val problem: String = "",
    val liveAdEvidence: LiveAdEvidence = LiveAdEvidence()
) {
    fun matchingSegments(customPrompts: List<String>?): List<String> =
        window?.let { InlinePromptText.segments(segments, fragments, it, customPrompts) } ?: segments

    fun detection(customPrompts: List<String>?): PromptDetection? =
        if (complete) PromptDetector.detectSegments(matchingSegments(customPrompts), customPrompts) else null

    fun fingerprint(customPrompts: List<String>?): String =
        if (complete) PageContentFingerprint.from(matchingSegments(customPrompts).joinToString("\n"), customPrompts) else ""
}
