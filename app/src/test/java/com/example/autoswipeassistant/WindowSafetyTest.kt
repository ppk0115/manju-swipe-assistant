package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test

class WindowSafetyTest {
    private val target = WindowSnapshot(1, "target", true, true, true, 1, WindowArea(0, 0, 1080, 1920))
    private val other = target.copy(id = 2, packageName = "other", layer = 2)

    @Test fun rejectedWindowsHaveAnActionableReasonWithoutDiagnosticMode() {
        assertNull(WindowSafety.failureReason(listOf(target), "target", 1))
        assertTrue(WindowSafety.failureReason(listOf(other.copy(application = false)), "other", 2)!!.contains("系统窗口"))
        assertTrue(WindowSafety.failureReason(listOf(target, other), "target", 1)!!.contains("焦点冲突"))
        val overlay = other.copy(active = false, focused = false)
        assertTrue(WindowSafety.failureReason(listOf(target, overlay), "target", 1)!!.contains("遮挡"))
        assertTrue(WindowSafety.failureReason(emptyList(), "target", 1)!!.contains("没有活动窗口"))
    }

    @Test fun selectsConfirmedForeground() {
        assertEquals(target, WindowSafety.select(listOf(target), "target"))
    }
    @Test fun backgroundTargetDoesNotOverrideOtherApp() {
        val background = target.copy(active = false, focused = false)
        assertEquals(other, WindowSafety.select(listOf(background, other), "other"))
    }
    @Test fun refusesConflictingOwnersAndUnknownRoot() {
        assertNull(WindowSafety.select(listOf(target, other), "target"))
        assertNull(WindowSafety.select(listOf(target), ""))
        assertNull(WindowSafety.select(emptyList(), "target"))
    }
    @Test fun systemDialogBlocksOperation() {
        assertNull(WindowSafety.select(listOf(other.copy(application = false)), "other"))
    }
    @Test fun focusedSplitWindowUsesItsOwnBounds() {
        val split = target.copy(area = WindowArea(300, 400, 900, 1400))
        val path = WindowSafety.swipeArea(split)
        assertEquals(WindowArea(598, 700, 602, 1221), path)
        assertTrue(path.left > split.area.left && path.right < split.area.right)
        assertTrue(path.top > split.area.top && path.bottom < split.area.bottom)
    }
    @Test fun overlappingHigherWindowBlocksSwipeButAdjacentWindowDoesNot() {
        assertFalse(WindowSafety.unobstructed(target, listOf(target, other)))
        val adjacent = other.copy(area = WindowArea(0, 0, 400, 1920))
        assertTrue(WindowSafety.unobstructed(target, listOf(target, adjacent)))
    }
    @Test fun pendingWindowIdentityChangesOnResizeOrAppSwitch() {
        assertNotEquals(target, target.copy(area = WindowArea(0, 0, 500, 800)))
        assertNotEquals(target, other)
    }

    @Test fun landscapeGestureStaysWithinActiveWindowAndUsesLongerStroke() {
        val area = WindowArea(100, 200, 2020, 1080)
        val plan = WindowSafety.swipePlan(area)
        assertTrue(plan.landscape)
        assertEquals(1060f, plan.x)
        assertTrue(plan.startY < area.bottom && plan.endY > area.top)
        assertTrue(plan.startY - plan.endY > area.height * 0.7f)
        val path = WindowSafety.swipeArea(target.copy(area = area))
        assertTrue(path.top >= area.top && path.bottom <= area.bottom)
    }
}
