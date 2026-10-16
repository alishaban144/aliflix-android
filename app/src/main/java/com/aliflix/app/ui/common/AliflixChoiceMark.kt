package com.aliflix.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.*

/** The containing selectable/toggleable row owns the accessible touch target. */
@Composable fun AliflixChoiceMark(selected:Boolean,modifier:Modifier=Modifier) {
    val motion=rememberMobileMotionEnabled()
    val amount by animateFloatAsState(if(selected) 1f else 0f,if(motion) AliflixMotion.press() else androidx.compose.animation.core.snap(),label="choice")
    val fill by animateColorAsState(if(selected) AliflixAccentPrimary else AliflixSurfacePrimary,if(motion) AliflixMotion.selection() else androidx.compose.animation.core.snap(),label="choice-fill")
    val shape=RoundedCornerShape(7.dp)
    Box(modifier.size(24.dp).background(fill,shape).border(1.dp,if(selected) AliflixAccentSecondary.copy(alpha=.35f) else AliflixBorderSubtle,shape),contentAlignment=Alignment.Center) {
        if(selected || motion && amount>0f) Icon(Icons.Rounded.Check,null,Modifier.size(17.dp).graphicsLayer {
            alpha=if(motion) amount else if(selected) 1f else 0f
            scaleX=.75f+amount*.25f;scaleY=scaleX
        },tint=AliflixContentPrimary)
    }
}
