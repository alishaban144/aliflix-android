package com.aliflix.app.ui

import androidx.compose.ui.geometry.Offset
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.discover.*
import org.junit.Assert.*
import org.junit.Test

class MobileMotionRegressionTest {
    @Test fun orbTouchTargetsAreBoundedAndDampingNeverJumpsOrOvershoots() {
        val target = boundedOrbLook(Offset(10000f,-10000f),Offset.Zero,38f)
        assertEquals(1f,target.getDistance(),.0001f)
        assertEquals(Offset.Zero,boundedOrbLook(Offset(100f,100f),Offset.Zero,0f))
        var current = Offset.Zero
        repeat(100) {
            val next = dampOrbLook(current,target,1f/60f,false)
            assertTrue((next-current).getDistance() < .17f)
            assertTrue((target-next).getDistance() <= (target-current).getDistance())
            assertTrue(next.getDistance() <= 1.0001f)
            current = next
        }
        val recentered = dampOrbLook(current,Offset.Zero,1f/60f,true)
        assertTrue(recentered.getDistance() > current.getDistance()*.9f)
        assertEquals(current,dampOrbLook(current,Offset.Zero,0f,true))
    }
    @Test fun slimPlaybackMorphHasBoundedContinuousGeometry() {
        for (i in 0..7) {
            val start = com.aliflix.app.player.playbackMorphPoint(i,0f)
            val end = com.aliflix.app.player.playbackMorphPoint(i,1f)
            assertEquals((start+end)/2f,com.aliflix.app.player.playbackMorphPoint(i,.5f))
            assertEquals(start,com.aliflix.app.player.playbackMorphPoint(i,-1f))
            assertEquals(end,com.aliflix.app.player.playbackMorphPoint(i,2f))
        }
        assertEquals(2f,com.aliflix.app.player.playbackMorphPoint(1,0f).x-com.aliflix.app.player.playbackMorphPoint(0,0f).x,0f)
        assertEquals(com.aliflix.app.player.playbackMorphPoint(5,1f),com.aliflix.app.player.playbackMorphPoint(6,1f))
    }
    @Test fun lumaFollowsEightSourceInsetsAndHalfCycleDelayWithoutASeam() {
        val expected = listOf(
            floatArrayOf(0f,0f,30f,30f),floatArrayOf(0f,0f,30f,65f),
            floatArrayOf(0f,35f,30f,65f),floatArrayOf(0f,35f,65f,65f),
            floatArrayOf(35f,35f,65f,65f),floatArrayOf(35f,0f,65f,65f),
            floatArrayOf(35f,0f,65f,30f),floatArrayOf(0f,0f,65f,30f))
        expected.forEachIndexed { i, e ->
            val r = lumaSpinRect(i*2.5f/8f)
            assertArrayEquals(e,floatArrayOf(r.left,r.top,r.right,r.bottom),.001f)
            assertEquals(lumaSpinRect(i*2.5f/8f+1.25f),lumaSpinRect(i*2.5f/8f,true))
        }
        assertEquals(lumaSpinRect(0f),lumaSpinRect(2.5f))
        assertTrue((lumaSpinRect(2.49999f).left-lumaSpinRect(0f).left).let { kotlin.math.abs(it) } < .01f)
    }
    @Test fun homeBarPinsContinuouslyAndNeverCrossesTheSearchHeader() {
        val header = 96f
        assertEquals(200f,homeFilterY(200f,header),0f)
        assertEquals(header,homeFilterY(20f,header),0f)
        var last = homeFilterY(300f,header)
        for (natural in 299 downTo -100) {
            val current = homeFilterY(natural.toFloat(),header)
            assertTrue(last-current in 0f..1.001f)
            assertTrue(current >= header)
            last = current
        }
        assertEquals(0f,homeFilterOpacity(200f,header,68f),0f)
        assertEquals(1f,homeFilterOpacity(header,header,68f),0f)
        assertEquals(1f,homeFilterOpacity(Float.NEGATIVE_INFINITY,header,68f),0f)
    }
}
