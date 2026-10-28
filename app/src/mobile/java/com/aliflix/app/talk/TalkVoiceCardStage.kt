package com.aliflix.app.talk

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class TalkCardPlacement(val x:Float,val y:Float,val width:Float,val height:Float)

/** Rows stay centred, including an incomplete final row. A selected title
 * occupies the same keyed stage, so its poster survives a focus transition. */
internal fun talkVoiceCardPlacements(width:Float,count:Int,fontScale:Float=1f):List<TalkCardPlacement> {
    if(count==0)return emptyList()
    val columns=when { count==1->1; fontScale>1.3f||width<300->2; count>=7&&width>=350->4; else->3 }.coerceAtMost(count)
    val card=minOf(if(count==1)112f else if(columns==4)76f else if(count>3)84f else 88f,(width-(columns-1)*12)/columns)
    val height=card*1.5f+64*fontScale
    return (0 until count.coerceAtMost(8)).map { index->
        val row=index/columns;val rowCount=minOf(columns,count-row*columns)
        TalkCardPlacement((width-rowCount*card-(rowCount-1)*12)/2+(index%columns)*(card+12),row*(height+12),card,height)
    }
}

@Composable internal fun TalkVoiceCardStage(
    titles:List<TalkTitleCard>,onOpen:(TalkTitleCard)->Unit,modifier:Modifier,motion:Boolean,
    focusedKey:String?,onBounds:(String,Rect)->Unit,
) {
    val current=titles.distinctBy{it.key}.take(8)
    val retained=remember { mutableStateMapOf<String,TalkTitleCard>() }
    val keys=current.map{it.key}
    LaunchedEffect(current,motion) {
        current.forEach{retained[it.key]=it}
        if(motion)delay(650)
        retained.keys.toList().filter{it !in keys}.forEach{retained.remove(it)}
    }
    val density=LocalDensity.current
    val viewportLimit=LocalConfiguration.current.screenHeightDp*.45f
    val scroll=rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stageWidth=maxWidth.value
        val placements=talkVoiceCardPlacements(maxWidth.value,current.size,density.fontScale)
        val contentHeight=placements.maxOfOrNull{it.y+it.height}?:0f
        val viewport by animateDpAsState(minOf(contentHeight,viewportLimit).dp,if(motion)spring(dampingRatio=1f,stiffness=190f)else snap(),label="card-stage-height")
        LaunchedEffect(focusedKey,keys,scroll.maxValue) {
            val index=keys.indexOf(focusedKey)
            if(index>=0&&contentHeight>viewportLimit){
                val target=with(density){(placements[index].y-(viewportLimit-placements[index].height)/2).dp.roundToPx()}.coerceIn(0,scroll.maxValue)
                if(motion)scroll.animateScrollTo(target)else scroll.scrollTo(target)
            }
        }
        Box(Modifier.fillMaxWidth().height(viewport).verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(maxOf(contentHeight,viewport.value).dp)) {
                retained.values.toList().forEach { title->key(title.key) {
                    val index=keys.indexOf(title.key);val active=index>=0
                    var last by remember{mutableStateOf(placements.getOrNull(index)?:TalkCardPlacement(0f,0f,84f,180f))}
                    val place=placements.getOrNull(index)?:last
                    LaunchedEffect(place,active){if(active)last=place}
                    val x=remember{Animatable(place.x)};val y=remember{Animatable(place.y+18)}
                    val width=remember{Animatable(place.width*.94f)};val alpha=remember{Animatable(0f)};val tilt=remember{Animatable(0f)}
                    LaunchedEffect(active,place,motion) {
                        val outLeft=last.x+last.width/2<stageWidth/2
                        val targetX=if(active)place.x else if(outLeft)-last.width-24 else stageWidth+24
                        if(!motion){x.snapTo(targetX);y.snapTo(place.y);width.snapTo(place.width);alpha.snapTo(if(active)1f else 0f);tilt.snapTo(0f)}
                        else coroutineScope {
                            launch{x.animateTo(targetX,if(active)spring(.92f,190f)else tween(460,easing=FastOutSlowInEasing))}
                            launch{y.animateTo(if(active)place.y else last.y+18,spring(1f,190f))}
                            launch{width.animateTo(place.width,spring(.94f,170f))}
                            launch{alpha.animateTo(if(active)1f else 0f,tween(if(active)340 else 300,delayMillis=if(active&&alpha.value==0f)index.coerceAtLeast(0)*35 else 0))}
                            launch{tilt.animateTo(if(active)0f else if(outLeft)-5f else 5f,tween(420))}
                        }
                    }
                    val semantics=if(active)Modifier else Modifier.clearAndSetSemantics{}
                    TalkRailCard(title,onOpen,Modifier.offset{IntOffset(with(density){x.value.dp.roundToPx()},with(density){y.value.dp.roundToPx()})}
                        .width(width.value.dp).graphicsLayer{this.alpha=alpha.value;rotationZ=tilt.value}.then(semantics),
                        motion,focusedKey==title.key||current.size==1,{key,bounds->if(active)onBounds(key,bounds)},enabled=active)
                }}
            }
        }
    }
}
