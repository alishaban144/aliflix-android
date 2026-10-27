package com.aliflix.app.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.recommendation.RecommendationSort
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.launch

/** Result ordering belongs to the collection, rather than the filter draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DiscoverSortControl(value:RecommendationSort,onChange:(RecommendationSort)->Unit) {
 var open by remember{mutableStateOf(false)}
 OutlinedButton(onClick={open=true},shape=RoundedCornerShape(16.dp),
  border=BorderStroke(1.dp,AliflixContentTertiary.copy(alpha=.18f)),
  colors=ButtonDefaults.outlinedButtonColors(contentColor=AliflixContentSecondary),
  contentPadding=PaddingValues(horizontal=14.dp),modifier=Modifier.heightIn(min=48.dp)) {
  Icon(Icons.Rounded.Sort,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
  Text("Sort",fontSize=13.sp,fontWeight=FontWeight.Medium)
 }
 if(open){
  val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
  val scope=rememberCoroutineScope()
  fun close(){scope.launch{sheet.hide();open=false}}
  ModalBottomSheet(onDismissRequest={open=false},sheetState=sheet,sheetGesturesEnabled=false,dragHandle=null,containerColor=AliflixBackgroundBase) {
   Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp)) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
     Text("Sort",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=AliflixContentPrimary)
     IconButton(onClick=::close){Icon(Icons.Rounded.Close,"Close sorting",tint=AliflixContentSecondary)}
    }
    RecommendationSort.entries.forEach { sort ->
     Surface(color=if(value==sort)AliflixAccentSecondary.copy(alpha=.10f) else androidx.compose.ui.graphics.Color.Transparent,shape=RoundedCornerShape(14.dp),modifier=Modifier.padding(vertical=2.dp)) {
      Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clickable(role=Role.RadioButton){onChange(sort);close()}.semantics{selected=value==sort}.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
       Text(sort.label,Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Medium,color=if(value==sort)AliflixAccentSecondary else AliflixContentPrimary)
       if(value==sort)Icon(Icons.Rounded.Check,null,Modifier.size(18.dp),tint=AliflixAccentSecondary)
      }
     }
    }
   }
  }
 }
}
