package com.example.autoswipeassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomPromptTest {
    @Test fun customModeReplacesDefaultReadyRules() {
        assertEquals(PromptState.None, PromptDetector.detect("上滑继续观看短剧", listOf("滑动恢复播放")))
        assertEquals(PromptState.Ready, PromptDetector.detect("请滑动恢复播放", listOf("滑动恢复播放")))
        assertEquals(PromptState.Ready, PromptDetector.detect("上滑继续观看短剧"))
    }
    @Test fun parsesMultipleLiteralPromptsAndIgnoresWhitespace() {
        assertEquals(listOf("上滑继续看短剧", "继续[播放]"),
            PromptDetector.parseCustomPrompts("上滑 继续看短剧\n\n继续[播放]\n上滑继续看短剧"))
        assertEquals(PromptState.Ready, PromptDetector.detect("上滑\n继续 看短剧", listOf("上滑继续看短剧")))
        assertEquals(PromptState.Ready, PromptDetector.detect("继续[播放]", listOf("继续[播放]")))
        assertEquals(PromptState.None, PromptDetector.detect("继续播放", listOf("继续[播放]")))
    }
    @Test fun emptyCustomRulesCannotTrigger() {
        assertEquals(PromptState.None, PromptDetector.detect("上滑继续观看短剧", emptyList()))
        assertEquals(PromptState.None, PromptDetector.detect("任意内容", listOf("  ")))
    }
    @Test fun countdownWinsOverCustomReadyPrompt() {
        assertEquals(PromptState.Countdown(5), PromptDetector.detect("5秒后上滑继续看短剧", listOf("上滑继续看短剧")))
        assertEquals(PromptState.Countdown(3), PromptDetector.detect("3秒后滑动恢复播放", listOf("滑动恢复播放")))
    }
    @Test fun fingerprintExcludesCustomPrompt() {
        assertEquals("广告甲", PageContentFingerprint.from("广告甲\n滑动恢复播放", listOf("滑动恢复播放")))
    }
}
