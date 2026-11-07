package com.aliflix.app.talk

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.AliflixAccentSecondary

/** Native adaptation of animbits LoaderMorphing (MIT), 21st.dev preview.
 * Authored 2s cycle: corner 20/50/20/50/20%, rotation 0..360, scale 1/1.2/1/1.2/1. */
@Composable internal fun TalkThinking(motion:Boolean,modifier:Modifier=Modifier) {
    val phase=if(motion) {
        val clock=rememberInfiniteTransition(label="morphing-loader")
        val progress by clock.animateFloat(0f,1f,infiniteRepeatable(tween(2000,easing=LinearEasing)),label="morph-cycle")
        progress
    }else 0f
    val ease=remember { CubicBezierEasing(.42f,0f,.58f,1f) }
    Canvas(modifier.size(32.dp).testTag("talk-thinking-animation")) {
        val part=(phase*4f).coerceIn(0f,3.9999f)
        val index=part.toInt();val fraction=ease.transform(part-index)
        val middle=if(index%2==0)fraction else 1f-fraction
        val scale=1f+.2f*middle
        val side=size.minDimension*.64f*scale
        val radius=side*(.2f+.3f*middle)
        rotate((index+fraction)*90f) {
            drawRoundRect(AliflixAccentSecondary,center-Offset(side/2,side/2),Size(side,side),CornerRadius(radius,radius))
        }
    }
}
