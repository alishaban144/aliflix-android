package com.aliflix.app.ui.discover

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.CubicBezierEasing
import com.aliflix.app.ui.common.rememberMobileAnimationClock
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary
import kotlin.math.min

/** Native adaptation of Jatin Yadav's Morph Loading (21st.dev).
 * Retains the four authored paths, 2 s timing, .2 s stagger, CSS ease-in-out,
 * scale, rotation and corner morph. Colours follow Aliflix. */
@Composable internal fun AskDiscoveryLoader(modifier:Modifier=Modifier,motion:Boolean=true) {
 val clock=rememberMobileAnimationClock(motion)
 val ease=CubicBezierEasing(.42f,0f,.58f,1f)
 val paths=listOf(
  listOf(Offset.Zero,Offset(20f,-20f),Offset(40f,0f),Offset(20f,20f),Offset.Zero),
  listOf(Offset.Zero,Offset(-20f,-20f),Offset(-40f,0f),Offset(-20f,20f),Offset.Zero),
  listOf(Offset.Zero,Offset(-20f,20f),Offset(0f,40f),Offset(20f,20f),Offset.Zero),
  listOf(Offset.Zero,Offset(20f,20f),Offset(0f,-40f),Offset(-20f,-20f),Offset.Zero))
 val scales=listOf(listOf(1f,1.2f,.8f,1.1f,1f),listOf(1f,1.3f,.7f,1.2f,1f),listOf(1f,.9f,1.4f,.8f,1f),listOf(1f,1.1f,1.3f,.9f,1f))
 val corners=listOf(listOf(0f,.5f,.25f,.75f,0f),listOf(0f,.5f,.25f,.75f,0f),listOf(0f,1f,0f,.5f,0f),listOf(0f,.25f,1f,.75f,0f))
 Canvas(modifier.semantics{contentDescription="Loading matches"}) {
  val unit=min(size.width,size.height)/128f
  for(i in 0..3){
   val time=if(motion)(clock.value-i*.2f).coerceAtLeast(0f)%2f else .62f-i*.12f
   val position=(time*2f).coerceIn(0f,3.9999f);val index=position.toInt();val t=ease.transform(position-index)
   fun value(values:List<Float>)=values[index]+(values[index+1]-values[index])*t
   val at=center+(paths[i][index]+(paths[i][index+1]-paths[i][index])*t)*unit
   val side=16f*unit*value(scales[i]);val radius=(side*value(corners[i])).coerceAtMost(side/2f)
   val angle=when(i){1->value(listOf(0f,90f,180f,270f,0f));3->value(listOf(0f,-90f,-180f,-270f,0f));else->0f}
   rotate(angle,at){drawRoundRect(if(i%2==0)AliflixAccentSecondary else AliflixContentPrimary,at-Offset(side/2f,side/2f),Size(side,side),CornerRadius(radius,radius))}
  }
 }
}
