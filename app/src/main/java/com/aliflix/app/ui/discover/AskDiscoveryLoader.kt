package com.aliflix.app.ui.discover

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.CubicBezierEasing
import com.aliflix.app.ui.common.rememberMobileAnimationClock
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import kotlin.math.min

/** Native path/dot adaptation of Aaron Iker's MIT loader, omvYNZ.
 * Copyright 2026 Aaron Iker. Full notice: docs/third-party-loader-license.txt. */
@Composable
internal fun AskDiscoveryLoader(modifier:Modifier=Modifier,motion:Boolean=true) {
 val clock=rememberMobileAnimationClock(motion)
 val easing=CubicBezierEasing(.785f,.135f,.15f,.86f)
 Canvas(modifier.semantics{contentDescription="Loading matches"}) {
  val side=min(size.width,size.height)*.38f
  val left=center.x-side/2;val top=center.y-side/2
  val phase=if(motion)(clock.value%3f)/3f else .12f
  val segment=phase*4;val step=segment.toInt();val t=easing.transform(segment-step)
  val distance=(step+t)*side
  val path=Path().apply{moveTo(left,top);lineTo(left+side,top);lineTo(left+side,top+side);lineTo(left,top+side);close()}
  drawPath(path,AliflixContentSecondary,style=Stroke(side*.09f,cap=StrokeCap.Round,join=StrokeJoin.Round,
   pathEffect=PathEffect.dashPathEffect(floatArrayOf(side*3,side),-distance)))
  val corners=listOf(Offset(left,top+side),Offset(left,top),Offset(left+side,top),Offset(left+side,top+side))
  val a=corners[step%4];val b=corners[(step+1)%4]
  drawCircle(AliflixAccentSecondary,side*.068f,a+(b-a)*t)
 }
}
