package com.example.autoswipeassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureOutcomeTest {
    @Test fun twoDifferentChangedPagesAreNotStableEvidence() {
        assertEquals(GestureObservation.UNKNOWN, GestureOutcome.confirm(
            GestureEvidence(GestureObservation.CHANGED, "字幕一"),
            GestureEvidence(GestureObservation.CHANGED, "字幕二")))
        assertEquals(GestureObservation.CHANGED, GestureOutcome.confirm(
            GestureEvidence(GestureObservation.CHANGED, "下一条内容"),
            GestureEvidence(GestureObservation.CHANGED, "下一条内容")))
    }
    @Test fun completedGestureWithUnchangedReadablePageIsNotSuccess() {
        assertEquals(GestureObservation.SAME,
            GestureOutcome.text(PromptState.Ready, "广告标题A", "广告标题A", false, false))
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.text(PromptState.Ready, "", "", false, false))
    }

    @Test fun stablePageChangeConfirmsTransitionWhileCountdownAloneDoesNot() {
        assertEquals(GestureObservation.CHANGED,
            GestureOutcome.text(PromptState.Ready, "广告标题A", "短剧标题B", false, false))
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.text(PromptState.Countdown(5), "广告标题A", "广告标题A", false, false))
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.text(PromptState.None, "广告标题A", "广告标题A", false, false))
        assertEquals(GestureObservation.CHANGED,
            GestureOutcome.text(PromptState.None, "广告标题A", "", true, false))
    }

    @Test fun weakOrConflictingEvidenceCannotConfirmOrTriggerRetry() {
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.text(PromptState.Ready, "广告标题A", "广告标题A", false, true))
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.controls(false, setOf("old"), setOf("new"), false, false))
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.confirm(GestureObservation.SAME, GestureObservation.CHANGED))
    }

    @Test fun learnedControlsRequireSameFeaturesAndNoTransitionEvent() {
        assertEquals(GestureObservation.SAME,
            GestureOutcome.controls(true, setOf("a", "b"), setOf("a", "b"), false, false))
        assertEquals(GestureObservation.UNKNOWN,
            GestureOutcome.controls(true, setOf("a"), setOf("b"), false, false))
        assertEquals(GestureObservation.CHANGED,
            GestureOutcome.controls(false, setOf("a"), setOf("b"), true, false))
    }
}
