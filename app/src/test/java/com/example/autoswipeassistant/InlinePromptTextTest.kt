package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test

class InlinePromptTextTest {
    private val window = WindowArea(0, 0, 2400, 1080)
    private val parent = WindowArea(1640, 50, 2240, 150)
    private val raw = listOf("上滑", "继续观看剧集")
    private val parts = listOf(
        InlineTextFragment(0, 7, raw[0], WindowArea(1680, 70, 1760, 120), parent),
        InlineTextFragment(1, 7, raw[1], WindowArea(1770, 70, 2110, 120), parent)
    )
    private fun read(parts: List<InlineTextFragment> = this.parts, complete: Boolean = true) =
        VisibleTextRead(raw.joinToString("\n"), raw, complete, parts, window)

    @Test fun exactReportedLandscapeTextAlreadyMatches() {
        assertEquals(PromptState.Ready, PromptDetector.detect("⌃ 上滑继续观看剧集"))
    }

    @Test fun adjacentTopRightLabelsFormOnePrompt() {
        assertEquals(PromptState.Ready, read().detection(null)?.state)
        assertEquals(listOf("上滑继续观看剧集"), read().matchingSegments(null))
    }

    @Test fun geometricOrderWinsOverTreeOrder() {
        val reversed = listOf(parts[1].copy(segment = 0), parts[0].copy(segment = 1))
        val value = VisibleTextRead("", raw.reversed(), true, reversed, window)
        assertEquals(PromptState.Ready, value.detection(null)?.state)
    }

    @Test fun fiveCharacterFragmentsCanFormSameControl() {
        val words = listOf("上", "滑", "继续", "观看", "剧集")
        val fragments = words.mapIndexed { i, word ->
            InlineTextFragment(i, 7, word, WindowArea(1680 + i * 70, 70, 1740 + i * 70, 120), parent)
        }
        assertEquals(PromptState.Ready, VisibleTextRead("", words, true, fragments, window).detection(null)?.state)
    }

    @Test fun separateControlsNeverJoin() {
        assertEquals(PromptState.None, read(listOf(parts[0], parts[1].copy(parent = 8))).detection(null)?.state)
    }

    @Test fun largeGapNeverJoins() {
        val distant = parts[1].copy(bounds = WindowArea(1880, 70, 2220, 120))
        assertEquals(PromptState.None, read(listOf(parts[0], distant)).detection(null)?.state)
    }

    @Test fun separateLinesNeverJoin() {
        assertEquals(PromptState.None, read(listOf(parts[0], parts[1].copy(
            bounds = WindowArea(1770, 130, 2110, 180), parentBounds = parent.copy(bottom = 190))))
            .detection(null)?.state)
    }

    @Test fun clickableSiblingsAreIndependentControls() {
        assertEquals(PromptState.None, read(parts.map { it.copy(clickable = true) }).detection(null)?.state)
    }

    @Test fun scrollingAndWholePageContainersNeverJoin() {
        assertEquals(PromptState.None, read(parts.map { it.copy(parentScrollable = true) }).detection(null)?.state)
        assertEquals(PromptState.None, read(parts.map { it.copy(parentBounds = window) }).detection(null)?.state)
    }

    @Test fun labelsOutsideWindowNeverJoin() {
        val value = VisibleTextRead("", raw, true, parts, WindowArea(0, 0, 1080, 1080))
        assertEquals(PromptState.None, value.detection(null)?.state)
    }

    @Test fun splitCountdownWithinSameControlProtectsReadySuffix() {
        val words = listOf("5秒后", "上滑继续观看剧集")
        val fragments = parts.mapIndexed { i, f -> f.copy(text = words[i]) }
        val value = VisibleTextRead("", words, true, fragments, window)
        assertEquals(PromptState.Countdown(5), value.detection(null)?.state)
    }

    @Test fun splitPromptIsExcludedFromContentFingerprint() {
        assertEquals("", read().fingerprint(null))
    }

    @Test fun incompleteLayoutCannotSupplyDetectionOrFingerprint() {
        assertNull(read(complete = false).detection(null))
        assertEquals("", read(complete = false).fingerprint(null))
    }

    @Test fun unrelatedLabelsDoNotBecomePrompts() {
        val words = listOf("上滑", "查看更多商品")
        val fragments = parts.mapIndexed { i, f -> f.copy(text = words[i]) }
        assertEquals(PromptState.None, VisibleTextRead("", words, true, fragments, window).detection(null)?.state)
    }
}
