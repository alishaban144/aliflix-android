package com.aliflix.app.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadStorageLimitTest {
    @Test fun customLimitsAcceptOnlyOneToTwoHundredGigabytes() {
        listOf("1", "25", "50", "200").forEach { assertEquals(it.toInt(), storageLimitInput(it)) }
        listOf("", "0", "201", "-1", "1.5", "abc").forEach { assertNull(storageLimitInput(it)) }
    }
    @Test fun loweringLimitWarnsWithoutChangingDownloadData() {
        val used = 51 * DOWNLOAD_GB
        assertTrue(storageLimitBelowUsage(50, used))
        assertFalse(storageLimitBelowUsage(51, used))
        assertFalse(storageLimitBelowUsage(200, used))
    }
}
