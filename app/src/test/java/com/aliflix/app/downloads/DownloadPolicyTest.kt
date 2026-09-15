package com.aliflix.app.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun quotaIncludesOtherQueuedDownloadsAndInFlightWrites() {
        assertTrue(downloadFits(40 * DOWNLOAD_GB, 8 * DOWNLOAD_GB, 2 * DOWNLOAD_GB, 50 * DOWNLOAD_GB, 10 * DOWNLOAD_GB))
        assertFalse(downloadFits(40 * DOWNLOAD_GB, 8 * DOWNLOAD_GB, 2 * DOWNLOAD_GB + 1, 50 * DOWNLOAD_GB, 10 * DOWNLOAD_GB))
        assertFalse(downloadFits(51 * DOWNLOAD_GB, 0, 1, 50 * DOWNLOAD_GB, 10 * DOWNLOAD_GB))
    }
    @Test fun leavesDeviceSpaceAndRejectsInvalidOrOverflowingSizes() {
        assertFalse(downloadFits(0, 0, 100, DOWNLOAD_GB, DOWNLOAD_DISK_RESERVE + 99))
        assertFalse(downloadFits(Long.MAX_VALUE, 1, 1, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(downloadFits(0, -1, 1, DOWNLOAD_GB, DOWNLOAD_GB))
    }
    @Test fun preferenceSelectsNearestAvailableWithoutExceedingIt() {
        assertEquals(480, preferredDownloadHeight(listOf(360, 480, 1080), 720))
        assertEquals(1080, preferredDownloadHeight(listOf(1080, 2160), 720))
        assertNull(preferredDownloadHeight(listOf(0), 720))
    }
}
