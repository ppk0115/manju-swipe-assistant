package com.example.autoswipeassistant

sealed interface PromptState {
    data object None : PromptState
    data class Countdown(val seconds: Int) : PromptState
    data object Ready : PromptState
}

data class PromptDetection(val state: PromptState, val sourceText: String = "")

object PromptDetector {
    private val countdownPattern = Regex(
        pattern = "([0-9]+)\\s*(?:s|秒)\\s*后.{0,20}(?:上滑|向上滑动|滑动)",
        option = RegexOption.IGNORE_CASE
    )

    private val readyPatterns = listOf(
        Regex("(?:上滑|向上滑动).{0,10}(?:继续观看|继续播放|观看短剧)"),
        Regex("(?:请)?(?:上滑|向上滑动).{0,6}(?:继续|观看)"),
        Regex("(?:继续观看|继续播放).{0,8}(?:请)?(?:上滑|向上滑动)")
    )

    private val customCountdownPattern = Regex("([0-9]+)(?:s|秒)后", RegexOption.IGNORE_CASE)

    fun parseCustomPrompts(rawText: String): List<String> = rawText.lineSequence()
        .map(::normalize).filter(String::isNotBlank).distinct().toList()

    /** A ready prompt in its own visible node wins over a lingering countdown node. */
    fun detectSegments(segments: List<String>, customPrompts: List<String>? = null): PromptDetection {
        var countdown: PromptDetection? = null
        for (segment in segments) {
            when (val state = detect(segment, customPrompts)) {
                PromptState.Ready -> return PromptDetection(state, segment)
                is PromptState.Countdown -> if (countdown == null || state.seconds < (countdown.state as PromptState.Countdown).seconds)
                    countdown = PromptDetection(state, segment)
                PromptState.None -> Unit
            }
        }
        return countdown ?: PromptDetection(PromptState.None)
    }

    fun detect(rawText: String, customPrompts: List<String>? = null): PromptState {
        val text = normalize(rawText)

        countdownPattern.find(text)?.let { match ->
            // An out-of-range timer must not fall through into a ready match.
            return PromptState.Countdown(match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE)
        }

        if (customPrompts != null) {
            val matched = customPrompts.any { normalize(it).let { word ->
                word.isNotBlank() && text.contains(word, ignoreCase = true)
            } }
            if (!matched) return PromptState.None
            customCountdownPattern.find(text)?.groupValues?.get(1)?.let {
                return PromptState.Countdown(it.toIntOrNull() ?: Int.MAX_VALUE)
            }
            return PromptState.Ready
        }

        if (readyPatterns.any { it.containsMatchIn(text) }) {
            return PromptState.Ready
        }

        return PromptState.None
    }

    private fun normalize(text: String): String = text
        .replace('Ｓ', 'S')
        .replace('ｓ', 's')
        .replace(Regex("\\s+"), "")
}

