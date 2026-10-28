package com.example.autoswipeassistant

import com.example.autoswipeassistant.AdSwipeLoop.Decision.*
import org.junit.Assert.*
import org.junit.Test

class AdSwipeLoopTest {
    private fun AdSwipeLoop.read(at: Long, ad: Boolean = true, complete: Boolean = true) =
        inspect(at, ad, complete)

    @Test fun normalVideoNeverStartsLoop() {
        val loop = AdSwipeLoop()
        assertEquals(IDLE, loop.read(0, ad = false))
        assertEquals(IDLE, loop.read(1000, ad = false))
        assertEquals(0, loop.attempts)
        assertFalse(loop.active)
    }

    @Test fun visibleAdStartsImmediatelyWithoutWaitingForReadyHint() {
        val loop = AdSwipeLoop()
        assertEquals(SWIPE, loop.read(0))
        assertTrue(loop.inFlight)
        assertEquals(1, loop.attempts)
    }

    @Test fun waitStartsAtGestureCompletionAndNeverQueuesAnotherGesture() {
        val loop = AdSwipeLoop()
        assertEquals(SWIPE, loop.read(0))
        assertEquals(WAIT, loop.read(10_000))
        assertEquals(1, loop.attempts)
        loop.gestureFinished(10_420)
        assertEquals(WAIT, loop.read(11_419))
        assertEquals(SWIPE, loop.read(11_420))
        assertEquals(2, loop.attempts)
    }

    @Test fun firstAbsentReadStopsGesturesWhileThreeSpacedChecksEndCycle() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        assertEquals(ABSENT, loop.read(1420, ad = false))
        assertEquals(WAIT, loop.read(1500, ad = false))
        assertEquals(1, loop.absentChecks)
        assertEquals(ABSENT, loop.read(2420, ad = false))
        assertEquals(CLEARED, loop.read(3420, ad = false))
        assertFalse(loop.active)
        assertEquals(IDLE, loop.read(4420, ad = false))
    }

    @Test fun transientAbsenceDoesNotEndAdCycle() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        assertEquals(ABSENT, loop.read(1420, ad = false))
        assertEquals(SWIPE, loop.read(2420))
        assertEquals(0, loop.absentChecks)
        assertEquals(2, loop.attempts)
    }

    @Test fun incompleteReadsCannotProveAbsence() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        loop.read(1420, ad = false)
        assertEquals(UNREADABLE, loop.read(2420, ad = false, complete = false))
        assertEquals(0, loop.absentChecks)
        assertEquals(ABSENT, loop.read(3420, ad = false))
    }

    @Test fun incompleteAdTextCannotStartAnAttempt() {
        val loop = AdSwipeLoop()
        assertEquals(UNREADABLE, loop.read(0, complete = false))
        assertEquals(0, loop.attempts)
        assertFalse(loop.inFlight)
    }

    @Test fun interruptionClearsAbsenceEvidenceButKeepsAttempts() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420); loop.read(1420, ad = false)
        loop.interrupt(1500)
        assertEquals(0, loop.absentChecks)
        assertEquals(1, loop.attempts)
        assertEquals(WAIT, loop.read(2499))
        assertEquals(SWIPE, loop.read(2500))
        assertEquals(2, loop.attempts)
    }

    @Test fun timeSpentAwayDoesNotConsumeTheActiveBudget() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        assertEquals(SWIPE, loop.read(1420)); loop.gestureFinished(1840)
        loop.interrupt(2000)
        assertEquals(SWIPE, loop.read(120_000))
        assertEquals(2000L, loop.activeElapsedMs)
        assertFalse(loop.blocked)
        assertEquals(3, loop.attempts)
    }

    @Test fun processFreezeCannotChargeMinutesOfUnobservedTime() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        assertEquals(SWIPE, loop.read(120_000))
        assertEquals(0L, loop.activeElapsedMs)
        loop.gestureFinished(120_420)
        assertEquals(SWIPE, loop.read(121_420))
        assertEquals(1420L, loop.activeElapsedMs)
    }

    @Test fun ninetySecondsOfObservedAdTimeStopsWithTimeReason() {
        val loop = AdSwipeLoop()
        for (i in 0 until 18) {
            assertEquals(SWIPE, loop.read(i * 5000L))
            loop.gestureFinished(i * 5000L)
        }
        assertEquals(LIMIT, loop.read(90_000))
        assertEquals(AdSwipeLoop.LimitReason.ACTIVE_TIME, loop.limitReason)
        assertEquals(90_000L, loop.activeElapsedMs)
        assertEquals(LIMIT, loop.read(200_000))
        assertEquals(18, loop.attempts)
    }

    @Test fun completeEmptyVideoTreeCanEndAnAdRound() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        val normal = VisibleTextRead("", emptyList(), true, nodeCount = 79)
        fun scan(at: Long) = loop.inspect(at, AdBadgeDetector.contains(normal.segments), normal.complete)
        assertEquals(ABSENT, scan(1420))
        assertEquals(ABSENT, scan(2420))
        assertEquals(CLEARED, scan(3420))
        assertFalse(loop.active)
        assertEquals(SWIPE, loop.read(4420))
        assertEquals(1, loop.attempts)
    }

    @Test fun attemptBudgetStopsUntilThreeAbsentReads() {
        val loop = AdSwipeLoop()
        for (i in 0 until AdSwipeLoop.MAX_ATTEMPTS) {
            assertEquals(SWIPE, loop.read(i * 1000L))
            loop.gestureFinished(i * 1000L)
        }
        assertEquals(LIMIT, loop.read(60_000))
        assertEquals(AdSwipeLoop.LimitReason.ATTEMPTS, loop.limitReason)
        assertEquals(LIMIT, loop.read(61_000))
        assertEquals(ABSENT, loop.read(62_000, ad = false))
        assertEquals(ABSENT, loop.read(63_000, ad = false))
        assertEquals(CLEARED, loop.read(64_000, ad = false))
        assertEquals(SWIPE, loop.read(65_000))
        assertEquals(1, loop.attempts)
    }

    @Test fun manualResetRetainsPhysicalFlightAndCompletionInterval() {
        val loop = AdSwipeLoop()
        loop.read(0)
        loop.reset()
        assertEquals(WAIT, loop.read(5000))
        loop.gestureFinished(5420)
        assertEquals(WAIT, loop.read(6419))
        assertEquals(SWIPE, loop.read(6420))
        assertEquals(1, loop.attempts)
    }

    @Test fun interruptionsWhileInFlightDoNotReleaseReservation() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.interrupt(100)
        assertEquals(WAIT, loop.read(5000))
        assertTrue(loop.inFlight)
        loop.gestureFinished(5420)
        assertEquals(WAIT, loop.read(6000))
        assertEquals(SWIPE, loop.read(6420))
    }

    @Test fun aNewAdCanStartAfterConfirmedAbsence() {
        val loop = AdSwipeLoop()
        loop.read(0); loop.gestureFinished(420)
        loop.read(1420, ad = false); loop.read(2420, ad = false)
        assertEquals(CLEARED, loop.read(3420, ad = false))
        assertEquals(SWIPE, loop.read(4420))
        assertEquals(1, loop.attempts)
    }
}

class AdBadgeDetectorTest {
    @Test fun exactVisibleBadgeAndDropdownAreAccepted() {
        for (text in listOf("广告", " 广 告\n", "广告⌄", "广告∨", "广告▼", "广告▾"))
            assertTrue(text, AdBadgeDetector.contains(listOf(text)))
    }
    @Test fun descriptionsAndSubstringsAreNotAdBadges() {
        for (text in listOf("广告商", "无广告", "关闭广告", "这是一条广告", "广告服务", "广告立即下载", "上滑继续观看剧集", "广告："))
            assertFalse(text, AdBadgeDetector.contains(listOf(text)))
        assertFalse(AdBadgeDetector.contains(listOf("广", "告")))
        assertFalse(AdBadgeDetector.contains(emptyList()))
    }
}
