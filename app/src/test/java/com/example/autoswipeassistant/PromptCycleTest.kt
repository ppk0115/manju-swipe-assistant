package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test

class PromptCycleTest {
    @Test fun unchangedReadyNeverStartsAnotherCycleAfterCooldown() {
        val cycle = PromptCycle()
        assertTrue(cycle.rearmReady(0))
        assertTrue(cycle.beginAction(0, false))
        // Time passing, scroll events and changing subtitles are not rearming evidence.
        for (now in listOf(500L, 15_000L, 60_000L)) {
            assertFalse(cycle.rearmReady(now))
            assertFalse(cycle.beginAction(now, false))
        }
    }

    @Test fun interruptedAbsenceMustStartItsStabilityPeriodAgain() {
        val cycle = PromptCycle()
        cycle.beginAction(0, false)
        assertFalse(cycle.observeAbsent(1_000))
        cycle.interruptEvidence() // Missing node, unreadable window or app switch.
        assertFalse(cycle.observeAbsent(4_000))
        assertFalse(cycle.observeAbsent(5_999))
        assertTrue(cycle.observeAbsent(6_000))
        assertFalse(cycle.rearmReady(14_999))
        assertTrue(cycle.rearmReady(15_000))
        assertTrue(cycle.beginAction(15_000, false))
    }

    @Test fun readyReappearingEarlyInterruptsAbsenceEvidence() {
        val cycle = PromptCycle()
        cycle.beginAction(0, false)
        cycle.observeAbsent(1_000)
        assertFalse(cycle.rearmReady(2_000))
        assertFalse(cycle.observeAbsent(3_000))
        assertFalse(cycle.observeAbsent(4_999))
        assertTrue(cycle.observeAbsent(5_000))
    }

    @Test fun resettingSessionOrConfigurationPreservesMinimumInterval() {
        val cycle = PromptCycle()
        cycle.beginAction(1_000, false)
        cycle.resetSession()
        assertFalse(cycle.rearmReady(5_000))
        assertFalse(cycle.beginAction(5_000, false))
        assertFalse(cycle.armCountdown(15_999))
        assertTrue(cycle.rearmReady(16_000))
        assertTrue(cycle.beginAction(16_000, false))
    }

    @Test fun samePromptAllowsOnlyOneRetry() {
        val cycle = PromptCycle()
        assertTrue(cycle.beginAction(0, false))
        assertFalse(cycle.beginAction(14_999, true))
        assertTrue(cycle.beginAction(15_000, true))
        assertEquals(2, cycle.attempt)
        assertFalse(cycle.beginAction(30_000, true))
        assertFalse(cycle.rearmReady(30_000))
    }

    @Test fun newCountdownCancelsOldRetryEligibility() {
        val cycle = PromptCycle()
        cycle.beginAction(0, false)
        assertFalse(cycle.armCountdown(10_000))
        assertTrue(cycle.armCountdown(15_000))
        assertFalse(cycle.beginAction(15_000, true))
        assertTrue(cycle.beginAction(16_000, false))
        assertEquals(1, cycle.attempt)
    }

    @Test fun clearedPromptCannotBeRetriedWhileWaitingForNextAd() {
        val cycle = PromptCycle()
        cycle.beginAction(0, false)
        cycle.observeAbsent(1_000)
        cycle.observeAbsent(3_000)
        assertFalse(cycle.beginAction(15_000, true))
    }

    @Test fun incompleteReadyOrEmptyReadCannotSupplyDetectionEvidence() {
        assertNull(VisibleTextRead("上滑继续观看短剧", listOf("上滑继续观看短剧"), false).detection(null))
        assertNull(VisibleTextRead("", emptyList(), false).detection(null))
        assertEquals(PromptState.Ready,
            VisibleTextRead("上滑继续观看短剧", listOf("上滑继续观看短剧"), true).detection(null)?.state)
    }
}
