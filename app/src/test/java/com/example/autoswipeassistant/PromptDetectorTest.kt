package com.example.autoswipeassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptDetectorTest {
    @Test fun longTimersCannotFallThroughIntoReady() {
        assertEquals(PromptState.Countdown(100), PromptDetector.detect("100秒后上滑继续观看剧集"))
        assertEquals(PromptState.Countdown(61), PromptDetector.detect("61s后上滑继续观看剧集"))
        assertEquals(PromptState.Countdown(Int.MAX_VALUE), PromptDetector.detect("999999999999秒后上滑继续观看剧集"))
        assertEquals(PromptState.Countdown(100), PromptDetector.detect("100秒后滑动恢复播放", listOf("滑动恢复播放")))
    }
    @Test
    fun detectsScreenshotCountdown() {
        assertEquals(PromptState.Countdown(5), PromptDetector.detect("5s后可继续上滑观看短剧"))
    }

    @Test
    fun detectsChineseSecondsCountdown() {
        assertEquals(PromptState.Countdown(3), PromptDetector.detect("3 秒后可以向上滑动"))
    }

    @Test
    fun detectsReadyPrompt() {
        assertEquals(PromptState.Ready, PromptDetector.detect("上滑继续观看短剧"))
    }

    @Test
    fun countdownWinsOverReadyWords() {
        assertEquals(PromptState.Countdown(5), PromptDetector.detect("5s后可继续上滑观看短剧"))
    }

    @Test
    fun separateReadyNodeWinsOverLingeringCountdown() {
        val result = PromptDetector.detectSegments(listOf("5s后可继续上滑观看短剧", "上滑继续观看短剧"))
        assertEquals(PromptState.Ready, result.state)
        assertEquals("上滑继续观看短剧", result.sourceText)
    }

    @Test
    fun countdownStillWinsWithinTheSameNode() {
        assertEquals(PromptState.Countdown(5),
            PromptDetector.detectSegments(listOf("5s后可继续上滑观看短剧")).state)
    }

    @Test
    fun ignoresOrdinarySwipePrompt() {
        assertEquals(PromptState.None, PromptDetector.detect("上滑查看更多商品"))
    }
}

