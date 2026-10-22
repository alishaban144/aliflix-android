@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.aliflix.app.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.*
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native adaptation of Agent Elements' expanding input/attachment chips and
 * Motion Primitives' shared-layout tabs/transition panels. No web runtime. */
@Composable
internal fun UnifiedAskAliflix(
    state:AskAliflixUiState,editor:AskAliflixEditorState,onEditor:(AskAliflixEditorState)->Unit,
    onSubmit:(AskAliflixRequest)->Unit,onReset:()->Unit,onBack:()->Unit,onOpen:(Media)->Unit,
    suggestions:List<Media>,suggestionsLoading:Boolean,onRetry:()->Unit,
    onSearchCompanies:suspend(String)->List<ProductionCompanyFilter>,listState:LazyListState,
    myListKeys:Set<String>,onToggleMyList:(Media)->Unit,modifier:Modifier=Modifier,
    dailyProvider:(suspend(MediaType)->List<Media>)?=null,
    onRefine:(String)->Unit={},
) {
    val context=LocalContext.current
    val app=context.applicationContext as AliflixApplication
    val research=remember{AskResearchClient(context)}
    val taste=remember{AskTaste(context)}
    val recent by app.libraryStore.recent.collectAsState()
    val favorites by app.libraryStore.likes.collectAsState()
    val saved by app.libraryStore.myList.collectAsState()
    var rejected by remember{mutableStateOf(taste.rejected())}
    var sheet by rememberSaveable{mutableStateOf(false)}
    var similar by rememberSaveable{mutableStateOf(editor.mode==1)}
    var surprisePending by rememberSaveable{mutableStateOf(false)}
    var surpriseChoices by remember { mutableStateOf<List<RecommendationCandidate>>(emptyList()) }
    var surprisePrompt by rememberSaveable { mutableStateOf("") }
    var sentDraft by rememberSaveable{mutableStateOf("")}
    var dailyRetry by remember{mutableIntStateOf(0)}
    var dailyLoading by remember{mutableStateOf(false)}
    var dailyError by remember{mutableStateOf(false)}
    var dailyState by remember{mutableStateOf("loading")}
    var dailyRetryAt by remember{mutableLongStateOf(0L)}
    val type=editor.mediaType
    var rememberedSeriesStatus by rememberSaveable{mutableStateOf(editor.spec.requiredStatus)}
    LaunchedEffect(type,editor.spec.requiredStatus){if(type==MediaType.TV)rememberedSeriesStatus=editor.spec.requiredStatus}
    var daily by remember(type){mutableStateOf<List<Media>>(emptyList())}
    var dailyResearchBacked by remember(type){mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val motion=rememberMobileMotionEnabled()
    val anchors=editor.selectedAnchors.ifEmpty{listOfNotNull(editor.selectedAnchor)}
    val resultState=state as? AskAliflixUiState.Results
    val busy=state is AskAliflixUiState.Searching||state is AskAliflixUiState.Interpreting||resultState?.refining==true
    val results=(state as? AskAliflixUiState.Results)?.items.orEmpty().filterNot{it.media.key in rejected}
    val tracker=remember{DiscoverTouchTracker()}
    fun submit(applyFilters:Boolean=false) {
        keyboard?.hide()
        val active=resultState?.activeRequest
        val sameAnchors=active !is AskAliflixRequest.Similar||active.anchors.map{it.key}==anchors.map{it.key}
        sentDraft=editor.describeText
        if(!applyFilters&&active!=null&&active !is AskAliflixRequest.Filters&&editor.describeText.isNotBlank()&&sameAnchors){onRefine(editor.describeText);return}
        val preservingIntent=applyFilters||active!=null&&editor.describeText.isBlank()
        val request=when {
            preservingIntent&&active is AskAliflixRequest.Describe -> active.copy(filters=editor.spec)
            preservingIntent&&active is AskAliflixRequest.Similar -> active.copy(filters=editor.spec,anchors=anchors,anchor=anchors.firstOrNull())
            similar&&anchors.isNotEmpty()->AskAliflixRequest.Similar(type,anchors,requiredStatus=editor.spec.requiredStatus,refinementText=editor.describeText.takeIf{it.isNotBlank()},filters=editor.spec)
            editor.describeText.isNotBlank()->AskAliflixRequest.Describe(type,editor.describeText,filters=editor.spec)
            else->AskAliflixRequest.Filters(editor.spec)
        };onSubmit(request)
    }
    LaunchedEffect(resultState?.activeRequest,resultState?.refining){if(resultState!=null&&!resultState.refining&&editor.describeText==sentDraft)onEditor(editor.copy(describeText=""))}
    LaunchedEffect(type,dailyRetry) {
        dailyLoading=true;dailyError=false
        if(dailyProvider!=null){daily=dailyProvider(type);dailyLoading=false;return@LaunchedEffect}
        val file=java.io.File(context.cacheDir,"ask-daily-${type.routeName}.json")
        fun parse(json:JSONObject)=parseAskDailySuggestions(json,type)
        daily=withContext(Dispatchers.IO){retainedAskDailySuggestions(type,
            {android.util.AtomicFile(file).openRead().bufferedReader().use{it.readText()}},
            {context.assets.open("ask-daily-fallback.json").bufferedReader().use{it.readText()}})}
        dailyResearchBacked=withContext(Dispatchers.IO){runCatching{android.util.AtomicFile(file).openRead().bufferedReader().use{researchBackedAskDailySuggestions(JSONObject(it.readText()),type)}}.getOrDefault(false)}
        try {
            for(attempt in 0 until 18) {
                val json=research.request("daily?type=${type.routeName}");val items=parse(json)
                dailyState=json.optString("state",if(items.isNotEmpty())"ready" else "unavailable")
                dailyRetryAt=json.optLong("retryAt",0L)
                if(items.isNotEmpty()) {
                    daily=items
                    dailyResearchBacked=researchBackedAskDailySuggestions(json,type)
                    withContext(Dispatchers.IO){runCatching{
                        val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
                        try{output.write(json.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}
                        catch(error:Throwable){atomic.failWrite(output);throw error}
                    }}
                    break
                }
                if(dailyState!="warming"||daily.isNotEmpty()||attempt==17)break
                delay(5000)
            }
            dailyError=daily.isEmpty()
        }
        catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){dailyError=daily.isEmpty();dailyState="unavailable"}
        finally{dailyLoading=false}
    }
    LaunchedEffect(dailyState,dailyRetryAt,type) {
        if(dailyState=="limited"&&dailyRetryAt>System.currentTimeMillis()) {
            delay((dailyRetryAt-System.currentTimeMillis()).coerceAtMost(86_400_000))
            dailyRetry++
        }
    }
    LaunchedEffect(state,surprisePending) {
        if(surprisePending&&!busy&&(resultState?.activeRequest as? AskAliflixRequest.Describe)?.text==surprisePrompt&&results.size>=3&&!resultState.researching) {
            surpriseChoices=results.take(3);surprisePending=false
        } else if(surprisePending&&state is AskAliflixUiState.Error)surprisePending=false
    }
    BackHandler(sheet){sheet=false}
    CompositionLocalProvider(LocalAskTouchTracker provides tracker) {
    Box(modifier.fillMaxSize().aliflixScreenBackground().observeDiscoverTouches(tracker).testTag("ask-unified")) {
        Column(Modifier.fillMaxSize().then(if(sheet)Modifier.clearAndSetSemantics{}else Modifier)) {
            MobileTopSafeArea(extraPadding=8.dp)
            AskAliflixHeader(onReset={similar=false;onReset()},onBack=onBack,showNewSearch=state !is AskAliflixUiState.Editing)
            AskMediaSelector(type,{newType->onEditor(editor.copy(mediaType=newType,spec=editor.spec.copy(mediaKind=if(newType==MediaType.TV)RecommendationMediaKind.SERIES else RecommendationMediaKind.MOVIE,requiredStatus=if(newType==MediaType.TV)rememberedSeriesStatus else null)));if(state !is AskAliflixUiState.Editing)onReset()},motion)
            AnimatedVisibility(type==MediaType.TV,enter=if(motion)expandVertically(spring(stiffness=550f))+fadeIn() else EnterTransition.None,exit=if(motion)shrinkVertically()+fadeOut() else ExitTransition.None){
                AskSeriesStatus(editor.spec.requiredStatus,{value->rememberedSeriesStatus=value;onEditor(editor.copy(spec=editor.spec.copy(requiredStatus=value)))},motion)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).clip(RoundedCornerShape(24.dp))
                .background(AliflixSurfaceDefaults.color()).animateContentSize(spring(stiffness=if(motion)500f else 10000f))) {
                AnimatedVisibility(similar&&anchors.isNotEmpty()) {
                    LazyRow(contentPadding=PaddingValues(start=12.dp,end=12.dp,top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(anchors,key={it.key}){m->
                            Row(Modifier.clip(RoundedCornerShape(15.dp)).background(AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Selected)).clickable{onOpen(m)},verticalAlignment=Alignment.CenterVertically) {
                                AskAliflixPoster(m,Modifier.width(34.dp).height(48.dp),cornerRadius=9.dp)
                                Text(m.title,Modifier.widthIn(max=120.dp).padding(start=8.dp),color=AliflixContentPrimary,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                AliflixIconButton(onClick={val updated=anchors.filterNot{it.key==m.key};onEditor(editor.copy(selectedAnchors=updated,selectedAnchor=updated.firstOrNull()))},modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.Close,"Remove ${m.title}",Modifier.size(16.dp))}
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start=17.dp,end=6.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    BasicTextField(value=editor.describeText,onValueChange={onEditor(editor.copy(describeText=it))},
                        textStyle=TextStyle(color=AliflixContentPrimary,fontSize=17.sp,lineHeight=24.sp),
                        cursorBrush=SolidColor(AliflixAccentSecondary),maxLines=5,modifier=Modifier.weight(1f).heightIn(min=48.dp).padding(vertical=12.dp).testTag("ask-composer"),
                        decorationBox={inner->Box{if(editor.describeText.isEmpty())Text(if(resultState!=null)"Refine" else "What are you in the mood for?",color=AliflixContentSecondary,fontSize=16.sp);inner()}})
                    AliflixIconButton(onClick={if(!busy)submit()},modifier=Modifier.size(48.dp).testTag("ask-send")){
                        if(busy)AskResearchProgress(Modifier.size(24.dp),motion)else Icon(Icons.AutoMirrored.Rounded.ArrowForward,"Find titles",tint=AliflixAccentSecondary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AskAction("Similar",Icons.Rounded.Layers,similar,Modifier.weight(1f),AskActionGlyph.Similar){similar=!similar;onEditor(editor.copy(mode=if(similar)1 else 0))}
                AskAction("Filters",Icons.Rounded.Tune,selectedFilterCount(editor.spec)>0,Modifier.weight(1f),AskActionGlyph.Filters){sheet=true}
                AskAction("Surprise Me",Icons.Rounded.Shuffle,false,Modifier.weight(1.25f),AskActionGlyph.Surprise){
                    if(busy)return@AskAction
                    val prefs=context.getSharedPreferences("aliflix-ask-surprises-"+(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?:"guest"),0);val prior=prefs.getStringSet("seen",emptySet()).orEmpty()
                    val excluded=(recent+favorites).map{it.key}.toSet()+rejected+prior
                    val genres=favorites.flatMap{it.genres}.groupingBy{it}.eachCount()
                    val picks=daily.takeIf{dailyResearchBacked&&selectedFilterCount(editor.spec)==0&&editor.describeText.isBlank()&&!similar}.orEmpty().filterNot{it.key in excluded}.shuffled().sortedByDescending{m->m.genres.sumOf{genres[it]?:0}}.take(3)
                    if(picks.size==3){prefs.edit().putStringSet("seen",(prior+picks.map{it.key}).toList().takeLast(96).toSet()).apply();surpriseChoices=picks.map{RecommendationCandidate(it)}}
                    else {surprisePrompt="Choose three distinctive, excellent unwatched ${if(type==MediaType.TV)"series" else "films"}: one inspired by my Favorites, one unexpected gem, and one bold wildcard. Make the three choices genuinely different in atmosphere. ${editor.describeText}";surprisePending=true;onSubmit(AskAliflixRequest.Describe(type,surprisePrompt,filters=editor.spec,resultCount=3))}
                }
            }
            AnimatedVisibility(similar&&anchors.size<4,enter=expandVertically()+fadeIn(),exit=shrinkVertically()+fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) {
                    BasicTextField(editor.similarQuery,{onEditor(editor.copy(similarQuery=it,mode=1))},Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AliflixSurfaceDefaults.color()).padding(15.dp).testTag("ask-anchor-search"),textStyle=TextStyle(color=AliflixContentPrimary,fontSize=15.sp),singleLine=true,cursorBrush=SolidColor(AliflixAccentSecondary),decorationBox={inner->Box{if(editor.similarQuery.isEmpty())Text("Add a title",color=AliflixContentSecondary,fontSize=15.sp);inner()}})
                    if(suggestionsLoading)AskResearchProgress(Modifier.fillMaxWidth().height(32.dp),motion)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=10.dp)){
                        items(suggestions.filterNot{m->anchors.any{it.key==m.key}}.take(8),key={it.key}){m->
                            Column(Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable{val updated=anchors+m;onEditor(editor.copy(selectedAnchors=updated,selectedAnchor=updated.firstOrNull(),similarQuery="",mode=1))}){
                                AskAliflixPoster(m,Modifier.width(82.dp).height(120.dp),cornerRadius=12.dp)
                                Text(m.title,Modifier.padding(top=5.dp),color=AliflixContentPrimary,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth().testTag("ask-content"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(state is AskAliflixUiState.Editing||results.isEmpty()) {
                    item("daily"){AnimatedContent(type to daily,transitionSpec={fadeIn(tween(if(motion)220 else 0)) togetherWith fadeOut(tween(if(motion)120 else 0))},label="ask-daily-type"){ (_, collection)->
                        if(collection.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth().testTag("ask-daily")){
                            items(collection,key={it.key}){m->Column(Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).clickable{onOpen(m)}){AskAliflixPoster(m,Modifier.width(112.dp).height(168.dp),cornerRadius=16.dp);Text(m.title,Modifier.padding(top=8.dp),color=AliflixContentPrimary,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(m.year,Modifier.padding(top=3.dp),color=AliflixContentSecondary,fontSize=11.sp)}}
                        }else if(dailyLoading)AskResearchProgress(Modifier.fillMaxWidth().height(180.dp),motion)
                        else if(dailyError&&dailyState=="limited")Text("Daily limit",color=AliflixContentSecondary,fontSize=13.sp,modifier=Modifier.padding(vertical=20.dp))
                        else if(dailyError)AskAction("Retry",Icons.Rounded.Refresh,false,Modifier.fillMaxWidth()){dailyRetry++}
                    }}
                }
                if(busy)item("progress"){AskResearchProgress(Modifier.fillMaxWidth().height(48.dp),motion)}
                if(resultState?.refineError!=null)item("refine-error"){AskAction("Retry refinement",Icons.Rounded.Refresh,false,Modifier.fillMaxWidth()){onRefine(sentDraft)}}
                items(results,key={it.media.key}){item->
                    MobileAskResultCard(item,{onOpen(item.media)},item.media.key in myListKeys,{onToggleMyList(item.media)},editorial=false,
                        modifier=Modifier.animateItem(),onDislike={rejected=rejected+item.media.key;taste.reject(item.media)})
                }
                if(resultState?.researching==true)item("research-continuing"){AskResearchProgress(Modifier.fillMaxWidth().height(32.dp).testTag("ask-research-continuing"),motion)}
                if(!busy&&state !is AskAliflixUiState.Editing&&results.isEmpty())item("empty"){
                    val limited=state is AskAliflixUiState.Error&&state.message=="Daily limit"
                    Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(if(limited)Icons.Rounded.Schedule else Icons.Rounded.SearchOff,null,tint=AliflixContentSecondary,modifier=Modifier.size(32.dp));Text(if(limited)"Daily limit" else if(state is AskAliflixUiState.Empty)"No matches" else "Unavailable",Modifier.padding(12.dp),color=AliflixContentPrimary,fontSize=17.sp);if(!limited)AskAction("Retry",Icons.Rounded.Refresh,false,Modifier){onRetry()}}
                }
            }
        }
        AnimatedVisibility(sheet,enter=fadeIn(tween(if(motion)150 else 0)),exit=fadeOut(tween(if(motion)120 else 0))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.55f)).clickable(indication=null,interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()}){sheet=false})
        }
        AnimatedVisibility(sheet,Modifier.align(Alignment.BottomCenter),enter=slideInVertically(spring(stiffness=500f)){it},exit=slideOutVertically(tween(if(motion)180 else 0)){it}) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).clip(RoundedCornerShape(topStart=28.dp,topEnd=28.dp)).background(AliflixBackgroundBase).navigationBarsPadding().imePadding().testTag("ask-filter-sheet")) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.End){AliflixIconButton(onClick={sheet=false}){Icon(Icons.Rounded.Close,"Close filters")}}
                AskAliflixFilters(editor.spec,{onEditor(editor.copy(spec=it))},{sheet=false;submit(true)},busy,onSearchCompanies,Modifier.weight(1f))
            }
        }
        if(surpriseChoices.isNotEmpty()) androidx.compose.ui.window.Dialog(
            onDismissRequest={surpriseChoices=emptyList()},
            properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false),
        ) {
            AliflixSurface(Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("ask-surprise-three"),
                shape=RoundedCornerShape(28.dp),level=AliflixSurfaceLevel.Content) {
                LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    item { Row(Modifier.fillMaxWidth().padding(start=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("A little unexpected",color=AliflixAccentSecondary,fontSize=11.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.sp)
                            Text("Your next three",color=AliflixContentPrimary,fontSize=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=4.dp))
                        }
                        AliflixIconButton(onClick={surpriseChoices=emptyList()}){Icon(Icons.Rounded.Close,"Close discoveries",tint=AliflixContentSecondary)}
                    } }
                    items(surpriseChoices.filterNot{it.media.key in rejected},key={it.media.key}) { pick ->
                        MobileAskResultCard(pick,{surpriseChoices=emptyList();onOpen(pick.media)},pick.media.key in myListKeys,
                            {onToggleMyList(pick.media)},editorial=false,onDislike={rejected=rejected+pick.media.key;taste.reject(pick.media)})
                    }
                }
            }
        }
    }}
}

@Composable private fun AskMediaSelector(type:MediaType,onSelect:(MediaType)->Unit,motion:Boolean){
    SharedTransitionLayout {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).clip(RoundedCornerShape(18.dp)).background(AliflixSurfaceDefaults.color()).padding(4.dp)) {
            MediaType.entries.forEach{v->val selected=v==type
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).selectable(selected,onClick={onSelect(v)},role=Role.Tab),contentAlignment=Alignment.Center){
                    androidx.compose.animation.AnimatedVisibility(selected,enter=fadeIn(tween(if(motion)160 else 0)),exit=fadeOut(tween(if(motion)100 else 0))){Box(Modifier.fillMaxSize().sharedBounds(rememberSharedContentState("ask-selector"),this).background(AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Selected)))}
                    Text(if(v==MediaType.MOVIE)"Movies" else "Series",color=if(selected)AliflixContentPrimary else AliflixContentSecondary,fontSize=14.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
/** Native Radix/coss-style segmented selection: one compact surface and a
 * shared moving selection, with optional All instead of ambiguous empty state. */
@Composable private fun AskSeriesStatus(status:String?,onSelect:(String?)->Unit,motion:Boolean){
    val choices=listOf("All" to null,"Running" to "Returning Series","Ended" to "Ended")
    val selectedIndex=choices.indexOfFirst{it.second==status}.coerceAtLeast(0)
    val position by animateFloatAsState(selectedIndex.toFloat(),if(motion)spring(stiffness=500f,dampingRatio=.86f)else snap(),label="series-status-selection")
    Box(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=9.dp).clip(RoundedCornerShape(15.dp))
        .background(Color.White.copy(alpha=.025f)).border(.5.dp,Color.White.copy(alpha=.09f),RoundedCornerShape(15.dp)).padding(3.dp).testTag("ask-series-status")){
        Canvas(Modifier.matchParentSize()){val cell=size.width/3;drawRoundRect(Color.White.copy(alpha=.11f),topLeft=Offset(position*cell,0f),size=androidx.compose.ui.geometry.Size(cell,size.height),cornerRadius=androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))}
        Row(Modifier.fillMaxWidth().selectableGroup()){
            choices.forEach{(label,value)->val selected=status==value
                Box(Modifier.weight(1f).heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).selectable(selected,onClick={onSelect(value)},role=Role.RadioButton).testTag("ask-status-${label.lowercase()}"),contentAlignment=Alignment.Center){
                    Text(label,Modifier.padding(horizontal=8.dp,vertical=12.dp),color=if(selected)Color(0xFFF2F0F6)else AliflixContentSecondary,fontSize=13.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,textAlign=TextAlign.Center,maxLines=2)
                }
            }
        }
    }
}
private enum class AskActionGlyph(val svg:String){
    Similar("M13 13.74a2 2 0 0 1-2 0L2.5 8.87a1 1 0 0 1 0-1.74L11 2.26a2 2 0 0 1 2 0l8.5 4.87a1 1 0 0 1 0 1.74z M20 14.285l1.5 .845a1 1 0 0 1 0 1.74L13 21.74a2 2 0 0 1-2 0l-8.5-4.87a1 1 0 0 1 0-1.74l1.5-.845"),
    Filters("M10 5H3 M12 19H3 M14 3v4 M16 17v4 M21 12h-9 M21 19h-5 M21 5h-7 M8 10v4 M8 12H3"),
    Surprise("M18 14l4 4-4 4 M18 2l4 4-4 4 M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22 M2 6h1.972a4 4 0 0 1 3.6 2.2 M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45")
}
@Composable private fun AskAction(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,selected:Boolean,modifier:Modifier,glyph:AskActionGlyph?=null,onClick:()->Unit){
    val interaction=remember{androidx.compose.foundation.interaction.MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState();val focused by interaction.collectIsFocusedAsState();val motion=rememberMobileMotionEnabled()
    val scale by animateFloatAsState(if(pressed&&motion).98f else 1f,spring(stiffness=650f),label="ask-action-press")
    val fill by animateColorAsState(Color.White.copy(alpha=if(selected).11f else if(pressed).07f else .028f),tween(if(motion)160 else 0),label="ask-action-surface")
    val edge by animateColorAsState(Color.White.copy(alpha=if(focused).42f else if(selected).24f else .11f),tween(if(motion)160 else 0),label="ask-action-edge")
    val shape=RoundedCornerShape(13.dp);val shade=Color(0xFFEEEAF2)
    Row(modifier.heightIn(min=48.dp).scale(scale).clip(shape).background(fill).border(.5.dp,edge,shape)
        .clickable(interactionSource=interaction,indication=null,role=Role.Button,onClick=onClick).semantics{this.selected=selected}.padding(horizontal=6.dp,vertical=9.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
        if(glyph==null)Icon(icon,null,Modifier.size(17.dp),tint=shade)else{
            val path=remember(glyph){PathParser().parsePathString(glyph.svg).toPath()}
            Canvas(Modifier.size(17.dp)){scale(size.minDimension/24f,pivot=Offset.Zero){drawPath(path,shade,style=Stroke(1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round))}}
        }
        Spacer(Modifier.width(5.dp));Text(label,color=shade,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=2,textAlign=TextAlign.Center)
    }
}
@Composable private fun AskResearchProgress(modifier:Modifier,motion:Boolean){val clock=rememberMobileAnimationClock(motion);Canvas(modifier.semantics{contentDescription="Researching";liveRegion=LiveRegionMode.Polite}){for(i in 0..3){val phase=(clock.value*1.4f-i*.22f);val alpha=if(motion)(.3f+.7f*((kotlin.math.sin(phase*6.283f)+1f)/2))else .7f;drawCircle(AliflixAccentSecondary.copy(alpha=alpha),2.3.dp.toPx(),androidx.compose.ui.geometry.Offset(center.x+(i-1.5f)*11.dp.toPx(),center.y))}}}
