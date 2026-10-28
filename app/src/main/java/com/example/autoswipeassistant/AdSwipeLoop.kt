package com.example.autoswipeassistant

/** Serial attempts; only complete live-window reads prove absence, including empty video trees. */
class AdSwipeLoop {
    enum class Decision { IDLE, WAIT, SWIPE, ABSENT, CLEARED, UNREADABLE, LIMIT }
    enum class LimitReason { ACTIVE_TIME, ATTEMPTS }
    var attempts = 0
        private set
    var absentChecks = 0
        private set
    var active = false
        private set
    var blocked = false
        private set
    var inFlight = false
        private set
    var nextCheckAt = 0L
        private set
    var activeElapsedMs = 0L
        private set
    var limitReason: LimitReason? = null
        private set
    private var accountingAt: Long? = null

    fun inspect(now: Long, hasAd: Boolean, complete: Boolean): Decision {
        if (inFlight || now < nextCheckAt) return Decision.WAIT
        if (!complete) {
            pauseBudget(now)
            absentChecks = 0
            nextCheckAt = now + INTERVAL_MS
            return Decision.UNREADABLE
        }
        nextCheckAt = now + INTERVAL_MS
        if (!hasAd) {
            pauseBudget(now)
            if (!active && !blocked) return Decision.IDLE
            absentChecks++
            if (absentChecks < ABSENT_CHECKS) return Decision.ABSENT
            active = false
            blocked = false
            attempts = 0
            absentChecks = 0
            activeElapsedMs = 0
            limitReason = null
            return Decision.CLEARED
        }
        absentChecks = 0
        if (blocked) return Decision.LIMIT
        if (!active) {
            active = true
            activeElapsedMs = 0
            attempts = 0
        }
        accrue(now)
        accountingAt = now
        if (attempts >= MAX_ATTEMPTS || activeElapsedMs >= MAX_DURATION_MS) {
            blocked = true
            limitReason = if (attempts >= MAX_ATTEMPTS) LimitReason.ATTEMPTS else LimitReason.ACTIVE_TIME
            accountingAt = null
            return Decision.LIMIT
        }
        attempts++
        inFlight = true
        return Decision.SWIPE
    }

    /** Called even when a gesture was cancelled/rejected; no overlapping or queued gestures. */
    fun gestureFinished(now: Long) {
        inFlight = false
        nextCheckAt = now + INTERVAL_MS
    }

    fun interrupt(now: Long) {
        pauseBudget(now)
        absentChecks = 0
        nextCheckAt = maxOf(nextCheckAt, now + INTERVAL_MS)
        // Keep spent budget and flight reservation, but do not charge time spent away.
    }

    fun reset() {
        active = false
        blocked = false
        attempts = 0
        absentChecks = 0
        activeElapsedMs = 0
        limitReason = null
        accountingAt = null
        // Keep the physical gesture reservation and minimum completion interval.
    }

    private fun accrue(now: Long) {
        val last = accountingAt ?: return
        val delta = now - last
        // Frozen/suspended processes cannot prove that a target stayed observable in the gap.
        if (delta in 0..MAX_OBSERVED_GAP_MS) activeElapsedMs += delta
    }

    private fun pauseBudget(now: Long) {
        accrue(now)
        accountingAt = null
    }

    companion object {
        const val INTERVAL_MS = 1_000L
        const val ABSENT_CHECKS = 3
        const val MAX_ATTEMPTS = 60
        const val MAX_DURATION_MS = 90_000L
        const val MAX_OBSERVED_GAP_MS = 5_000L
    }
}

object AdBadgeDetector {
    private val whitespace = Regex("\\s+")
    private val badge = Regex("^广告[⌄∨﹀▼▾˅⌵]?$")
    fun contains(segments: List<String>): Boolean = segments.any {
        badge.matches(it.replace(whitespace, ""))
    }
}
