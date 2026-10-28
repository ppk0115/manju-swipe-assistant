package com.example.autoswipeassistant

data class LiveAdEvidence(val matchingPlayers: Int = 0, val episodeControlsVisible: Boolean = false)

/** Collects only the visible, same-branch structure of the observed short-series live promotion. */
class ShortDramaLiveAdCollector(private val window: WindowArea) {
    private var players = 0
    private var episodeControls = false

    fun visit(packageName: String, id: String, className: String, visible: Boolean,
              bounds: WindowArea, ancestors: Set<String>): Set<String> {
        if (HIDDEN_BRANCH in ancestors || packageName != PACKAGE || !visible || !window.intersects(bounds) ||
            bounds.width <= 0 || bounds.height <= 0) return setOf(HIDDEN_BRANCH)
        if (id in EPISODE_IDS) episodeControls = true
        if (id == PLAYER && className == "android.view.TextureView" &&
            ancestors.containsAll(CONTEXT_IDS) && largePlayer(bounds)) players++
        return if (id in CONTEXT_IDS && largeContainer(bounds)) ancestors + id else ancestors
    }

    fun evidence() = LiveAdEvidence(players, episodeControls)

    private fun largeContainer(bounds: WindowArea) =
        bounds.width >= window.width * 0.8 && bounds.height >= window.height * 0.6 &&
            visibleFraction(bounds) >= 0.9

    private fun largePlayer(bounds: WindowArea) =
        bounds.width >= window.width * 0.8 && bounds.height >= window.height * 0.5 &&
            visibleFraction(bounds) >= 0.9

    private fun visibleFraction(bounds: WindowArea): Double {
        val width = (minOf(bounds.right, window.right) - maxOf(bounds.left, window.left)).coerceAtLeast(0)
        val height = (minOf(bounds.bottom, window.bottom) - maxOf(bounds.top, window.top)).coerceAtLeast(0)
        return width.toDouble() * height / (bounds.width.toDouble() * bounds.height)
    }

    companion object {
        const val PACKAGE = "com.phoenix.read"
        const val PLAYER = "$PACKAGE:id/ttlive_player_render_view"
        private const val HIDDEN_BRANCH = "hidden-branch"
        private val CONTEXT_IDS = setOf("root_view", "h8l", "l0", "i05", "axz", "fr0")
            .map { "$PACKAGE:id/$it" }.toSet()
        private val EPISODE_IDS = setOf("k9d", "d4", "kdk").map { "$PACKAGE:id/$it" }.toSet()
    }
}

object ShortDramaLiveAdDetector {
    private val rewardLabels = setOf("立即领取", "领取")
    fun candidate(packageName: String, read: VisibleTextRead): Boolean =
        packageName == ShortDramaLiveAdCollector.PACKAGE && read.complete && read.nodeCount > 0 &&
            read.liveAdEvidence.matchingPlayers == 1 && !read.liveAdEvidence.episodeControlsVisible &&
            // Empty text / the reward label is supporting evidence, never a trigger by itself.
            read.segments.all { it.isBlank() || it.trim() in rewardLabels }
}

/** Two independent full reads, a second apart, in the same uninterrupted window. */
class LiveAdConfirmation {
    private var window: WindowSnapshot? = null
    private var firstAt: Long? = null
    private var lastAt: Long? = null

    fun inspect(now: Long, expectedWindow: WindowSnapshot, candidate: Boolean): Boolean {
        if (!candidate) { reset(); return false }
        val previous = lastAt
        if (window != expectedWindow || previous == null || now < previous || now - previous > 5_000L) {
            window = expectedWindow
            firstAt = now
            lastAt = now
            return false
        }
        lastAt = now
        return now - (firstAt ?: now) >= AdSwipeLoop.INTERVAL_MS
    }

    fun reset() { window = null; firstAt = null; lastAt = null }
}
