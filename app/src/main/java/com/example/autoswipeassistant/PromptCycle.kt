package com.example.autoswipeassistant

/** Monotonic-clock state machine. A session reset never resets the action rate limit. */
class PromptCycle {
    enum class Phase { WAITING, HANDLED, CLEARED }
    var phase = Phase.WAITING
        private set
    val handled get() = phase != Phase.WAITING
    val promptCleared get() = phase == Phase.CLEARED
    var cooldownUntil = 0L
        private set
    var attempt = 0
        private set
    private var absentSince: Long? = null

    fun resetSession() {
        phase = Phase.WAITING
        attempt = 0
        interruptEvidence()
    }

    fun interruptEvidence() { absentSince = null }

    fun observeAbsent(now: Long): Boolean {
        if (phase != Phase.HANDLED) return false
        val since = absentSince
        if (since == null) absentSince = now
        else if (now - since >= ABSENCE_MS) {
            phase = Phase.CLEARED
            return true
        }
        return false
    }

    fun armCountdown(now: Long): Boolean {
        interruptEvidence()
        if (now < cooldownUntil) return false
        resetSession()
        return true
    }

    fun rearmReady(now: Long): Boolean {
        interruptEvidence()
        if (now < cooldownUntil || phase == Phase.HANDLED) return false
        if (phase == Phase.CLEARED) resetSession()
        return true
    }

    fun beginAction(now: Long, retry: Boolean): Boolean {
        if (now < cooldownUntil) return false
        if (retry) {
            if (phase != Phase.HANDLED || attempt != 1) return false
        } else if (phase != Phase.WAITING) return false
        attempt = if (retry) 2 else 1
        phase = Phase.HANDLED
        cooldownUntil = now + COOLDOWN_MS
        interruptEvidence()
        return true
    }

    companion object {
        const val COOLDOWN_MS = 15_000L
        const val ABSENCE_MS = 2_000L
    }
}
