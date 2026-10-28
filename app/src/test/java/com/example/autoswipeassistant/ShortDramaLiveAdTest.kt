package com.example.autoswipeassistant

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class ShortDramaLiveAdTest {
    private val area = WindowArea(0, 0, 1280, 2772)
    private val pkg = ShortDramaLiveAdCollector.PACKAGE
    private val window = WindowSnapshot(12, pkg, true, true, true, 0, area)

    // Replay sanitized layout fixtures; retain actual ancestor relationships without private page labels.
    private fun replay(file: String, change: (Element) -> Unit = {}): VisibleTextRead {
        val stream = javaClass.getResourceAsStream("/live-ad/$file")!!
        val root = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }
        val collector = ShortDramaLiveAdCollector(area)
        val labels = mutableListOf<String>()
        var count = 0
        fun visit(node: Element, ancestors: Set<String>) {
            change(node)
            var next = ancestors
            if (node.tagName == "node") {
                count++
                val coordinates = Regex("-?\\d+").findAll(node.getAttribute("bounds")).map { it.value.toInt() }.toList()
                val bounds = WindowArea(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
                val visible = node.getAttribute("test-hidden") != "true"
                next = collector.visit(node.getAttribute("package"), node.getAttribute("resource-id"),
                    node.getAttribute("class"), visible, bounds, ancestors)
                if (visible) listOf("text", "content-desc").map(node::getAttribute)
                    .filter(String::isNotBlank).forEach(labels::add)
            }
            for (i in 0 until node.childNodes.length) {
                (node.childNodes.item(i) as? Element)?.let { visit(it, next) }
            }
        }
        visit(root, emptySet())
        return VisibleTextRead(labels.joinToString("\n"), labels, true, window = area, nodeCount = count,
            liveAdEvidence = collector.evidence())
    }

    private fun candidate(read: VisibleTextRead) = ShortDramaLiveAdDetector.candidate(pkg, read)

    @Test fun actualLivePromotionWithRewardMatches() {
        val read = replay("promotion.xml")
        assertEquals(listOf("立即领取"), read.segments)
        assertEquals(1, read.liveAdEvidence.matchingPlayers)
        assertTrue(candidate(read))
    }

    @Test fun actualLivePromotionWithoutRewardMatches() {
        val read = replay("promotion-no-reward.xml")
        assertTrue(read.segments.isEmpty())
        assertTrue(candidate(read))
    }

    @Test fun shorterClaimLabelIsAcceptedOnlyWithStructure() {
        val read = replay("promotion.xml") { if (it.getAttribute("text") == "立即领取") it.setAttribute("text", "领取") }
        assertTrue(candidate(read))
        assertFalse(candidate(read.copy(liveAdEvidence = LiveAdEvidence())))
    }

    @Test fun actualPausedEpisodeWithRewardDoesNotMatch() {
        val read = replay("episode.xml")
        assertTrue("立即领取" in read.segments)
        assertTrue("第1集" in read.segments)
        assertTrue(read.liveAdEvidence.episodeControlsVisible)
        assertEquals(0, read.liveAdEvidence.matchingPlayers)
        assertFalse(candidate(read))
    }

    @Test fun emptyNormalVideoAndClaimLabelsAloneNeverTrigger() {
        for (labels in listOf(emptyList(), listOf("领取"), listOf("立即领取"))) {
            assertFalse(candidate(VisibleTextRead(labels.joinToString(""), labels, true, nodeCount = 80)))
        }
    }

    @Test fun incompleteReadAndOtherAppsNeverMatch() {
        val read = replay("promotion.xml")
        assertFalse(candidate(read.copy(complete = false)))
        assertFalse(candidate(read.copy(nodeCount = 0)))
        assertFalse(ShortDramaLiveAdDetector.candidate("com.kylin.read", read))
        assertFalse(ShortDramaLiveAdDetector.candidate("com.other.app", read))
    }

    @Test fun hiddenPreloadedPlayerDoesNotMatch() {
        assertFalse(candidate(replay("promotion.xml") {
            if (it.getAttribute("resource-id") == ShortDramaLiveAdCollector.PLAYER) it.setAttribute("test-hidden", "true")
        }))
    }

    @Test fun invisibleParentCannotBeResurrectedByVisibleChildren() {
        assertFalse(candidate(replay("promotion.xml") {
            if (it.getAttribute("resource-id").endsWith(":id/root_view")) it.setAttribute("test-hidden", "true")
        }))
    }

    @Test fun offscreenAndTinyLivePlayersDoNotMatch() {
        for (bounds in listOf("[1280,0][2560,2284]", "[0,0][120,200]", "[1000,0][2280,2284]")) {
            assertFalse(candidate(replay("promotion.xml") {
                if (it.getAttribute("resource-id") == ShortDramaLiveAdCollector.PLAYER) it.setAttribute("bounds", bounds)
            }))
        }
    }

    @Test fun missingFeedOrLiveCardAncestorRejectsStandaloneLiveRoom() {
        for (id in listOf("root_view", "h8l", "l0", "i05", "axz", "fr0")) {
            assertFalse("missing $id", candidate(replay("promotion.xml") {
                if (it.getAttribute("resource-id") == "$pkg:id/$id") it.setAttribute("resource-id", "")
            }))
        }
    }

    @Test fun realRoomLabelsAndEndedLiveMessageAreNotRewardOnly() {
        val read = replay("promotion.xml")
        for (text in listOf("欢迎来到直播间", "直播已结束", "第1集", "领取优惠券")) {
            assertFalse(candidate(read.copy(segments = listOf(text))))
        }
    }

    @Test fun visibleEpisodeOrAmbiguousMultiplePlayersPreventMatch() {
        val read = replay("promotion.xml")
        assertFalse(candidate(read.copy(liveAdEvidence = LiveAdEvidence(1, true))))
        assertFalse(candidate(read.copy(liveAdEvidence = LiveAdEvidence(2, false))))
    }

    @Test fun playerIdFromAnotherPackageOrWrongClassDoesNotMatch() {
        for (attribute in listOf("package", "class")) {
            assertFalse(candidate(replay("promotion.xml") {
                if (it.getAttribute("resource-id") == ShortDramaLiveAdCollector.PLAYER)
                    it.setAttribute(attribute, if (attribute == "package") "com.other" else "android.widget.ImageView")
            }))
        }
    }

    @Test fun confirmationNeedsOneSecondAndSameWindow() {
        val confirmation = LiveAdConfirmation()
        assertFalse(confirmation.inspect(0, window, true))
        assertFalse(confirmation.inspect(999, window, true))
        assertTrue(confirmation.inspect(1000, window, true))
        assertFalse(confirmation.inspect(2000, window.copy(id = 13), true))
        assertFalse(confirmation.inspect(3000, window.copy(area = WindowArea(0, 0, 2772, 1280)), true))
    }

    @Test fun absenceInterruptionAndLongGapsRequireNewConfirmation() {
        val confirmation = LiveAdConfirmation()
        confirmation.inspect(0, window, true)
        assertFalse(confirmation.inspect(1000, window, false))
        assertFalse(confirmation.inspect(2000, window, true))
        assertTrue(confirmation.inspect(3000, window, true))
        confirmation.reset()
        assertFalse(confirmation.inspect(4000, window, true))
        assertFalse(confirmation.inspect(10_000, window, true))
        assertTrue(confirmation.inspect(11_000, window, true))
    }

    @Test fun confirmedLivePromotionUsesExistingSerialLoopAndStopsOnEpisode() {
        val confirmation = LiveAdConfirmation()
        val loop = AdSwipeLoop()
        val promotion = replay("promotion-no-reward.xml")
        val episode = replay("episode.xml")
        assertFalse(confirmation.inspect(0, window, candidate(promotion)))
        assertEquals(AdSwipeLoop.Decision.SWIPE, loop.inspect(1000,
            confirmation.inspect(1000, window, candidate(promotion)), true))
        loop.gestureFinished(1350)
        assertEquals(AdSwipeLoop.Decision.SWIPE, loop.inspect(2350,
            confirmation.inspect(2350, window, candidate(promotion)), true))
        loop.gestureFinished(2700)
        for (at in listOf(3700L, 4700L, 5700L)) {
            val hasAd = confirmation.inspect(at, window, candidate(episode))
            assertFalse(hasAd)
            loop.inspect(at, hasAd, true)
        }
        assertFalse(loop.active)
        assertEquals(0, loop.attempts)
    }
}
