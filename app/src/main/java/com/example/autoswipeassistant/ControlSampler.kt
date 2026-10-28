package com.example.autoswipeassistant

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object ControlSampler {
    fun read(root: AccessibilityNodeInfo, area: WindowArea): ControlSample {
        val features = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        queue.add(root to "root")
        var visited = 0
        var complete = area.width > 0 && area.height > 0
        while (queue.isNotEmpty() && visited < 600 && features.size < 1200) {
            val (node, path) = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val id = node.viewIdResourceName.orEmpty()
                val kind = node.className?.toString().orEmpty()
                val identity = if (id.isNotBlank()) "id=$id" else "path=$path:$kind"
                val x = ((rect.centerX() - area.left) * 8 / area.width.coerceAtLeast(1)).coerceIn(0, 8)
                val y = ((rect.centerY() - area.top) * 12 / area.height.coerceAtLeast(1)).coerceIn(0, 12)
                val state = "enabled=${node.isEnabled},click=${node.isClickable},scroll=${node.isScrollable},selected=${node.isSelected},checked=${node.isChecked}"
                features.add("$identity@$x,$y|$state|actions=${node.actionList.map { it.id }.sorted()}")
                if (id.isNotBlank()) features.add("anchor=$id:$kind")
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child == null) complete = false
                else queue.add(child to "$path/${node.className?.toString()?.substringAfterLast('.')}[$index]")
            }
        }
        return ControlSample(root.packageName?.toString().orEmpty(), area.width.toDouble() / area.height.coerceAtLeast(1), features, complete && queue.isEmpty())
    }
}
