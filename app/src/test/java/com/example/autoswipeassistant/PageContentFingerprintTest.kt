package com.example.autoswipeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageContentFingerprintTest {
    @Test fun ignoresEngagementCountersAndNodeOrdering() {
        assertEquals(PageContentFingerprint.from("广告甲\n点赞123\n作者乙\n3.5万"),
            PageContentFingerprint.from("作者乙\n广告甲\n点赞124\n3.6万"))
    }
    @Test
    fun ignoresDirectReadyPrompt() {
        assertEquals("短剧标题", PageContentFingerprint.from("短剧标题\n上滑继续观看短剧"))
    }

    @Test
    fun ignoresCountdownAndStandaloneTimer() {
        val first = PageContentFingerprint.from("广告内容\n5s后可继续上滑观看短剧\n05")
        val second = PageContentFingerprint.from("广告内容\n3s后可继续上滑观看短剧\n03")

        assertEquals(first, second)
    }

    @Test
    fun detectsChangedPageContentWithSamePrompt() {
        val first = PageContentFingerprint.from("广告甲\n上滑继续观看短剧")
        val second = PageContentFingerprint.from("广告乙\n上滑继续观看短剧")

        assertNotEquals(first, second)
    }
}
