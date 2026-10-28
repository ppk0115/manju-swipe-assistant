package com.example.autoswipeassistant

/** A leaf label and its direct parent, obtained from the same complete window read. */
data class InlineTextFragment(
    val segment: Int, val parent: Int, val text: String,
    val bounds: WindowArea, val parentBounds: WindowArea,
    val parentScrollable: Boolean = false, val clickable: Boolean = false
)

/** Joins only adjacent labels inside one small control, never the whole page. */
object InlinePromptText {
    fun segments(raw: List<String>, fragments: List<InlineTextFragment>, window: WindowArea,
                 customPrompts: List<String>? = null): List<String> {
        if (window.width <= 0 || window.height <= 0) return raw
        val replacements = mutableMapOf<Int, String>()
        val consumed = mutableSetOf<Int>()
        fragments.filter { f ->
            f.segment in raw.indices && raw[f.segment] == f.text && f.parent >= 0 &&
                !f.parentScrollable && !f.clickable && f.text.isNotBlank() && f.text.length <= 80 &&
                f.bounds.width > 0 && f.bounds.height > 0 && contains(window, f.bounds) &&
                contains(f.parentBounds, f.bounds) && f.parentBounds.width <= window.width * 0.65 &&
                f.parentBounds.height <= window.height * 0.18
        }.groupBy { it.parent }.values.forEach { siblings ->
            val ordered = siblings.distinctBy { it.segment }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val remaining = ordered.toMutableList()
            while (remaining.isNotEmpty()) {
                val first = remaining.removeAt(0)
                val row = mutableListOf(first)
                // Geometry, not accessibility-tree order, defines a horizontal label.
                val peers = remaining.filter { sameLine(first.bounds, it.bounds) }.sortedBy { it.bounds.left }
                val candidates = (row + peers).sortedBy { it.bounds.left }
                val run = mutableListOf<InlineTextFragment>()
                fun flush() {
                    if (run.size in 2..6) {
                        val combined = run.joinToString("") { it.text }
                        if (combined.length <= 80 && PromptDetector.detect(combined, customPrompts) != PromptState.None) {
                            val anchor = run.minOf { it.segment }
                            replacements[anchor] = combined
                            consumed.addAll(run.map { it.segment })
                        }
                    }
                    run.clear()
                }
                candidates.forEach { fragment ->
                    if (run.isNotEmpty() && !adjacent(run.last().bounds, fragment.bounds)) flush()
                    run.add(fragment)
                }
                flush()
                remaining.removeAll(peers.toSet())
            }
        }
        return raw.indices.mapNotNull { index -> replacements[index] ?: raw[index].takeUnless { index in consumed } }
    }

    private fun contains(outer: WindowArea, inner: WindowArea) = outer.left <= inner.left &&
        outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

    private fun sameLine(a: WindowArea, b: WindowArea): Boolean {
        val height = minOf(a.height, b.height)
        return height > 0 && maxOf(a.height, b.height) <= height * 1.7 &&
            kotlin.math.abs((a.top + a.bottom) - (b.top + b.bottom)) <= height * 0.7
    }

    private fun adjacent(a: WindowArea, b: WindowArea): Boolean = sameLine(a, b) &&
        b.left >= a.right && b.left - a.right <= maxOf(16.0, minOf(a.height, b.height) * 0.75)
}
