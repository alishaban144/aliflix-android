package com.aliflix.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailOverviewPresentationTest {
    @Test
    fun collapsedOverviewOffersExpansionOnlyWhenTextIsVisuallyClipped() {
        assertTrue(
            shouldShowOverviewExpansion(
                isExpanded = false,
                lineCount = 5,
                hasVisualOverflow = true,
            ),
        )
        assertFalse(
            shouldShowOverviewExpansion(
                isExpanded = false,
                lineCount = 5,
                hasVisualOverflow = false,
            ),
        )
    }

    @Test
    fun expandedOverviewKeepsCollapseActionWhenFullTextExceedsFiveLines() {
        assertTrue(
            shouldShowOverviewExpansion(
                isExpanded = true,
                lineCount = 6,
                hasVisualOverflow = false,
            ),
        )
        assertFalse(
            shouldShowOverviewExpansion(
                isExpanded = true,
                lineCount = 5,
                hasVisualOverflow = false,
            ),
        )
    }
}
