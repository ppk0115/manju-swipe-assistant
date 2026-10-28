package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test

class PatternMatcherTest {
    private val common = setOf("root", "player", "navigation", "anchor=player")
    private fun sample(vararg extra: String) = ControlSample("app.a", 0.5, common + extra, true)
    private val normal = sample("feed", "like")
    private val ad = sample("advertiser", "disabled")
    private val ready = sample("advertiser", "enabled", "swipe_control")
    private val pattern = LearnedPattern("a", "A软件", "app.a", normal, ad, ready)

    @Test fun matchesOnlyLearnedReadyState() {
        assertNull(PatternMatcher.validation(normal, ad, ready))
        assertTrue(PatternMatcher.match(pattern, ready))
        assertFalse(PatternMatcher.match(pattern, normal))
        assertFalse(PatternMatcher.match(pattern, ad))
    }
    @Test fun sameLayoutInDifferentAppNeverMatches() {
        assertFalse(PatternMatcher.match(pattern, ready.copy(packageName = "app.b")))
        assertNotNull(PatternMatcher.validation(normal, ad.copy(packageName = "app.b"), ready))
    }
    @Test fun noAccessibleTransitionCannotBeLearned() {
        assertNotNull(PatternMatcher.validation(normal, ad, ad))
        assertNotNull(PatternMatcher.validation(normal, ad, normal))
    }
    @Test fun truncatedOrMissingNodesCannotMatchOrBeLearned() {
        assertFalse(PatternMatcher.match(pattern, ready.copy(complete = false)))
        assertNotNull(PatternMatcher.validation(normal.copy(complete = false), ad, ready))
    }
    @Test fun rotationAndLargeResizeAreRejected() {
        assertFalse(PatternMatcher.match(pattern, ready.copy(aspect = 2.0)))
        assertNotNull(PatternMatcher.validation(normal, ad.copy(aspect = 2.0), ready))
    }
    @Test fun genericPageWithMissingStageSpecificEvidenceDoesNotMatch() {
        assertFalse(PatternMatcher.match(pattern, ready.copy(features = ready.features - "swipe_control")))
        assertFalse(PatternMatcher.match(pattern, ready.copy(features = ready.features - "advertiser")))
    }
    @Test fun invalidSavedPatternFailsClosed() {
        assertFalse(PatternMatcher.match(pattern.copy(advertisement = ready), ready))
        assertNotNull(PatternMatcher.validation(sample(), sample(), sample()))
    }
    @Test fun additionalContentDoesNotBreakReadyMatch() {
        assertTrue(PatternMatcher.match(pattern, ready.copy(features = ready.features + setOf("new_subtitle_node", "decoration"))))
    }

    @Test fun onePatternAutomaticallyUsesPortraitOrLandscapeSamples() {
        val wideNormal = normal.copy(aspect = 2.0)
        val wideAd = ad.copy(aspect = 2.0)
        val wideReady = ready.copy(aspect = 2.0)
        val both = pattern.withSamples(PatternSamples(wideNormal, wideAd, wideReady))
        assertNotNull(both.alternate)
        assertTrue(PatternMatcher.match(both, ready))
        assertTrue(PatternMatcher.match(both, wideReady))
        assertFalse(PatternMatcher.match(pattern, wideReady))
        val relearnedWide = both.withSamples(PatternSamples(wideNormal, wideAd, wideReady.copy(features = wideReady.features + "new_control")))
        assertTrue(PatternMatcher.match(relearnedWide, ready))
        assertTrue(PatternMatcher.match(relearnedWide, wideReady.copy(features = wideReady.features + "new_control")))
    }
}
