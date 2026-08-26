package com.aliflix.app.ui

import com.aliflix.app.ui.common.AliflixLogoGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class AliflixLogoGeometryTest {

    @Test
    fun logoViewBoxAndPointsMatchReference() {
        assertEquals(100f, AliflixLogoGeometry.VIEWBOX_SIZE, 0.001f)
        assertEquals(25f, AliflixLogoGeometry.CIRCLE_CX, 0.001f)
        assertEquals(66f, AliflixLogoGeometry.CIRCLE_CY, 0.001f)
        assertEquals(11.5f, AliflixLogoGeometry.CIRCLE_RADIUS, 0.001f)

        // Shadow blade: (59, 19) -> (80, 84) -> (68, 84) -> (56, 47)
        assertEquals(4, AliflixLogoGeometry.SHADOW_BLADE_POINTS.size)
        assertEquals(59f to 19f, AliflixLogoGeometry.SHADOW_BLADE_POINTS[0])
        assertEquals(80f to 84f, AliflixLogoGeometry.SHADOW_BLADE_POINTS[1])
        assertEquals(68f to 84f, AliflixLogoGeometry.SHADOW_BLADE_POINTS[2])
        assertEquals(56f to 47f, AliflixLogoGeometry.SHADOW_BLADE_POINTS[3])

        // Light blade: (43, 19) -> (59, 19) -> (68, 84) -> (55, 84)
        assertEquals(4, AliflixLogoGeometry.LIGHT_BLADE_POINTS.size)
        assertEquals(43f to 19f, AliflixLogoGeometry.LIGHT_BLADE_POINTS[0])
        assertEquals(59f to 19f, AliflixLogoGeometry.LIGHT_BLADE_POINTS[1])
        assertEquals(68f to 84f, AliflixLogoGeometry.LIGHT_BLADE_POINTS[2])
        assertEquals(55f to 84f, AliflixLogoGeometry.LIGHT_BLADE_POINTS[3])
    }
}
