package com.example.autoswipeassistant

data class WindowArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun intersects(other: WindowArea) = left < other.right && right > other.left &&
        top < other.bottom && bottom > other.top
}

data class WindowSnapshot(
    val id: Int, val packageName: String, val application: Boolean,
    val active: Boolean, val focused: Boolean, val layer: Int, val area: WindowArea
)

data class SwipePlan(val x: Float, val startY: Float, val endY: Float, val durationMs: Long, val landscape: Boolean)

object WindowSafety {
    fun failureReason(windows: List<WindowSnapshot>, rootPackage: String, rootId: Int): String? {
        if (rootPackage.isBlank()) return "前台应用未知，请重新打开目标应用"
        val owners = windows.filter { it.active || it.focused }
        if (owners.isEmpty()) return "没有活动窗口，请点一下目标应用后重试"
        if (owners.any { !it.application }) return "系统窗口正在前台，请关闭通知栏、键盘或系统弹窗"
        if (owners.any { it.packageName != rootPackage } || owners.distinctBy { it.id }.size != 1)
            return "窗口焦点冲突，请关闭小窗并全屏打开目标应用"
        val selected = select(windows, rootPackage) ?: return "目标窗口过小，请全屏打开目标应用"
        if (selected.id != rootId) return "窗口正在切换，等待重新读取"
        if (!unobstructed(selected, windows)) return "上滑路径被遮挡，请关闭悬浮窗后重试"
        return null
    }

    fun swipePlan(area: WindowArea): SwipePlan {
        val landscape = area.width >= area.height
        val x = area.left + area.width * 0.5f
        val startY = area.top + area.height * if (landscape) 0.86f else 0.82f
        val endY = area.top + area.height * if (landscape) 0.14f else 0.30f
        return SwipePlan(x, startY, endY, if (landscape) 420L else 350L, landscape)
    }

    fun select(windows: List<WindowSnapshot>, activeRootPackage: String): WindowSnapshot? {
        if (activeRootPackage.isBlank()) return null
        val owners = windows.filter { it.active || it.focused }
        // Conflicting owners, unknown roots and system dialogs are not safe to operate.
        if (owners.isEmpty() || owners.any { !it.application || it.packageName != activeRootPackage }) return null
        val selected = owners.distinctBy { it.id }.singleOrNull() ?: return null
        if (selected.area.width < 80 || selected.area.height < 160) return null
        return selected
    }

    fun swipeArea(window: WindowSnapshot): WindowArea {
        val plan = swipePlan(window.area)
        return WindowArea(plan.x.toInt() - 2, plan.endY.toInt(),
            plan.x.toInt() + 2, plan.startY.toInt() + 1)
    }

    fun unobstructed(window: WindowSnapshot, windows: List<WindowSnapshot>): Boolean {
        val path = swipeArea(window)
        return windows.none { it.id != window.id && it.layer >= window.layer && it.area.intersects(path) }
    }
}
