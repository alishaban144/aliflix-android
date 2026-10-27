package com.aliflix.app.talk

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.ui.theme.*

internal fun talkSourceUri(url:String):Uri?=runCatching { Uri.parse(url).takeIf { uri ->
    uri.scheme=="https"&&uri.userInfo==null&&uri.port==-1&&uri.host?.let { host ->
        host.contains('.')&&host.matches(Regex("[A-Za-z0-9.-]{1,253}"))&&!host.contains("..")&&!host.contains(':')&&!host.matches(Regex("[0-9.]+"))&&!host.endsWith(".local")&&!host.endsWith(".localhost")
    }==true
} }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TalkSources(sources:List<TalkReference>) {
    val context=LocalContext.current
    val valid=remember(sources){sources.distinctBy{it.url}.filter{talkSourceUri(it.url)!=null}}
    if(valid.isEmpty())return
    var open by remember { mutableStateOf(false) }
    val sites=valid.distinctBy{talkSourceUri(it.url)?.host}.take(3)
    TextButton(onClick={open=true},contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription="Sources, ${valid.size}" }) {
        Box(Modifier.width((sites.size*14+10).dp).height(24.dp)) {
            sites.forEachIndexed { index,source ->
                val host=talkSourceUri(source.url)!!.host!!
                Box(Modifier.offset(x=(index*14).dp).size(24.dp).clip(CircleShape).background(AliflixBackgroundBase),contentAlignment=Alignment.Center) {
                    Text(host.removePrefix("www.").take(1).uppercase(),fontSize=10.sp,color=AliflixAccentSecondary)
                    var fallback by remember(host){mutableStateOf(false)}
                    AsyncImage(if(fallback)"https://www.google.com/s2/favicons?domain=$host&sz=32" else "https://$host/favicon.ico",null,Modifier.size(17.dp).clip(CircleShape),onError={fallback=true})
                }
            }
        }
    }
    if(open)ModalBottomSheet(onDismissRequest={open=false},containerColor=AliflixBackgroundBase) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Sources",Modifier.weight(1f),color=AliflixContentPrimary,fontSize=22.sp)
                IconButton(onClick={open=false}) { Icon(Icons.Rounded.Close,"Close sources") }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=480.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                items(valid.size,key={valid[it].url}) { index -> val source=valid[index]
                    TextButton(onClick={talkSourceUri(source.url)?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,it)) } }},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(source.title,color=AliflixContentPrimary)
                            Text(talkSourceUri(source.url)?.host.orEmpty(),color=AliflixAccentSecondary,fontSize=12.sp)
                            if(source.license.isNotBlank())Text(source.license,color=AliflixContentTertiary,fontSize=10.sp)
                        }
                    }
                }
            }
        }
    }
}
