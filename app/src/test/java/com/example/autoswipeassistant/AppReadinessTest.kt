package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test

class AppReadinessTest {
    @Test fun restrictedBackgroundIsNotShownAsReadyEvenWithAConnectedService() {
        val state = AppReadiness.evaluate(true, true, true, true, true, false, true)
        assertFalse(state.ready)
        assertEquals(AppReadiness.Action.BATTERY, state.action)
    }

    @Test fun batteryRestrictionHasAPracticalRecoveryActionAfterAStaleHeartbeat() {
        assertEquals(AppReadiness.Action.BATTERY,
            AppReadiness.evaluate(true, true, false, true, true, false, true).action)
        assertEquals(AppReadiness.Action.ENABLE,
            AppReadiness.evaluate(true, true, true, false, true, false, true).action)
    }

    @Test fun permissionAloneDoesNotMeanReady() {
        val state = AppReadiness.evaluate(true, false, false, true, true, false)
        assertFalse(state.ready)
        assertEquals(AppReadiness.Action.RECONNECT, state.action)
    }

    @Test fun staleHeartbeatRequiresRecoveryEvenIfConnected() {
        assertEquals(AppReadiness.Action.RECONNECT,
            AppReadiness.evaluate(true, true, false, true, true, false).action)
    }

    @Test fun setupActionsLeadToTheMissingRequirement() {
        assertEquals(AppReadiness.Action.AUTHORIZE,
            AppReadiness.evaluate(false, false, false, true, false, false).action)
        assertEquals(AppReadiness.Action.ENABLE,
            AppReadiness.evaluate(true, true, true, false, true, false).action)
        assertEquals(AppReadiness.Action.TARGETS,
            AppReadiness.evaluate(true, true, true, true, false, false).action)
    }

    @Test fun observationModeIsNotPresentedAsAutomaticOperation() {
        assertFalse(AppReadiness.evaluate(true, true, true, true, true, true).ready)
        assertTrue(AppReadiness.evaluate(true, true, true, true, true, false).ready)
    }
}
