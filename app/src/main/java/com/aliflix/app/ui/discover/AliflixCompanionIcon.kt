package com.aliflix.app.ui.discover

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aliflix.app.ui.theme.AliflixAccentSecondary

/** Native optical adaptation of Lucide's ISC-licensed bot-message-square:
 * speech silhouette and two eyes, with the mechanical antenna removed. */
@Composable
internal fun AliflixCompanionIcon(modifier:Modifier=Modifier,tint:Color=AliflixAccentSecondary) {
    Canvas(modifier.semantics{contentDescription="Talk to Aliflix"}) {
        val u=size.minDimension/24f
        val path=Path().apply {
            moveTo(7*u,4*u);lineTo(17*u,4*u);cubicTo(20*u,4*u,21*u,5*u,21*u,8*u)
            lineTo(21*u,15*u);cubicTo(21*u,18*u,20*u,19*u,17*u,19*u)
            lineTo(9*u,19*u);lineTo(4*u,22*u);lineTo(4*u,17*u)
            cubicTo(3*u,16*u,3*u,15*u,3*u,13*u);lineTo(3*u,8*u)
            cubicTo(3*u,5*u,4*u,4*u,7*u,4*u);close()
        }
        drawPath(path,tint,style=Stroke(1.65f*u,cap=StrokeCap.Round))
        for(x in listOf(8.5f,15.5f))drawRoundRect(tint,Offset((x-.75f)*u,9*u),Size(1.5f*u,4*u),CornerRadius(u,u))
    }
}
