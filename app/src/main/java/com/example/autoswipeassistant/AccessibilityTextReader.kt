package com.example.autoswipeassistant

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object AccessibilityTextReader {
    private data class Entry(val node: AccessibilityNodeInfo, val parent: Int,
                             val parentBounds: WindowArea, val parentScrollable: Boolean,
                             val liveAncestors: Set<String> = emptySet())

    fun read(context: android.content.Context, packageName: String, root: AccessibilityNodeInfo,
             deepRefresh: Boolean, area: WindowArea): VisibleTextRead {
        val output = StringBuilder()
        val segments = ArrayList<String>()
        val diagnostic = DiagnosticStore.enabled(context)
        val nodeDetails = StringBuilder()
        var detailTruncated = false
        var refreshFailures = 0
        var missingChildren = 0
        var textTruncated = false
        var childrenTruncated = false
        val fragments = ArrayList<InlineTextFragment>()
        val liveCollector = if (packageName == ShortDramaLiveAdCollector.PACKAGE) ShortDramaLiveAdCollector(area) else null
        val queue = ArrayDeque<Entry>()
        queue.add(Entry(root, -1, area, false))
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES && output.length < MAX_COLLECTED_TEXT) {
            val entry = queue.removeFirst()
            val node = entry.node
            visited++

            // Android may keep child accessibility nodes cached even after the
            // target app has changed videos. Refresh a bounded number of nodes
            // only while recovering from a stalled handled prompt.
            if (deepRefresh && visited <= MAX_DEEP_REFRESH_NODES) {
                if (!node.refresh()) refreshFailures++
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = WindowArea(rect.left, rect.top, rect.right, rect.bottom)
            val liveAncestors = liveCollector?.visit(node.packageName?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                node.isVisibleToUser, bounds, entry.liveAncestors) ?: emptySet()

            if (diagnostic) {
                val detail = "#$visited visible=${node.isVisibleToUser} bounds=$rect id=${node.viewIdResourceName} class=${node.className} text=${node.text} contentDescription=${node.contentDescription}\n"
                val remaining = 28_000 - nodeDetails.length
                if (detail.length > remaining) detailTruncated = true
                if (remaining > 0) nodeDetails.append(detail.take(remaining))
            }

            // Accessibility trees can retain detached/off-screen nodes. Only their
            // visible text is eligible to trigger an action.
            if (node.isVisibleToUser) {
                val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .filter(String::isNotBlank).distinct()
                labels.forEachIndexed { index, label ->
                    val remaining = (MAX_COLLECTED_TEXT - output.length - 1).coerceAtLeast(0)
                    if (label.length > remaining) textTruncated = true
                    val bounded = label.take(remaining)
                    if (bounded.isNotEmpty()) {
                        if (index == 0 && node.childCount == 0) {
                            fragments.add(InlineTextFragment(segments.size, entry.parent, bounded, bounds,
                                entry.parentBounds, entry.parentScrollable, node.isClickable))
                        }
                        output.append(bounded).append('\n')
                        segments.add(bounded)
                    }
                }
            }

            val available = (MAX_NODES - visited - queue.size).coerceAtLeast(0)
            if (node.childCount > available) childrenTruncated = true
            for (index in 0 until node.childCount.coerceAtMost(available)) {
                val child = node.getChild(index)
                if (child == null) missingChildren++
                else queue.addLast(Entry(child, visited, bounds, node.isScrollable, liveAncestors))
            }
        }
        val problem = when {
            childrenTruncated || queue.isNotEmpty() -> "节点数量达到上限"
            textTruncated || output.length >= MAX_COLLECTED_TEXT -> "文字长度达到上限"
            missingChildren > 0 -> "部分子节点不可读"
            refreshFailures > 0 -> "部分节点刷新失败"
            else -> ""
        }
        if (diagnostic) {
            val live = liveCollector?.evidence() ?: LiveAdEvidence()
            val metadata = "已访问节点=$visited；可见文字字符=${output.length}；扫描完整=${problem.isEmpty()}；读取问题=$problem；扫描达到上限=${childrenTruncated || queue.isNotEmpty()}；节点明细截断=$detailTruncated；文字快照截断=$textTruncated；空子节点=$missingChildren；深度刷新失败=$refreshFailures；直播推广播放器=${live.matchingPlayers}；剧集控件=${live.episodeControlsVisible}；deepRefresh=$deepRefresh"
            DiagnosticStore.event(context, "读取", "应用=$packageName $metadata")
            DiagnosticStore.snapshot(context, packageName, metadata, output.toString(), nodeDetails.toString())
        }
        return VisibleTextRead(output.toString(), segments, problem.isEmpty(), fragments, area, visited, problem,
            liveCollector?.evidence() ?: LiveAdEvidence())
    }

    private const val MAX_NODES = 600
    private const val MAX_DEEP_REFRESH_NODES = 120
    private const val MAX_COLLECTED_TEXT = 12_000
}
