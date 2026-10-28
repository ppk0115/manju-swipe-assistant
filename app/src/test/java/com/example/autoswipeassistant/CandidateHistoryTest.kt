package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test

class CandidateHistoryTest {
    @Test fun revisitingAnOldAppBringsItIntoThePreviewWithoutDuplicates() {
        val recent = CandidateHistory.touch(listOf("a", "b", "c", "d"), "a")
        assertEquals(listOf("a", "d", "c"), recent.asReversed().take(3))
        assertEquals(4, recent.size)
        assertEquals(recent, CandidateHistory.touch(recent, "a"))
    }
}
