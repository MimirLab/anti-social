/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.CrossPosts
import app.antisocial.core.Post
import app.antisocial.core.PostPresentation
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Inspect every captured original without fetching captions, counts or artwork. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OriginalPostsSheet(vm: AntiSocialViewModel,posts: List<Post>,copyTarget: Post,onDismiss: ()->Unit) {
    val repo=vm.repo
    val outgoing=rememberOutgoingActions(repo)
    val originals=remember(posts){posts.distinctBy(repo::key).take(CrossPosts.MAX_EDITIONS)}
    val defaultKey=repo.key(copyTarget)
    val defaultCount=copyTarget.replyCount?.takeIf {it>=0}
    val highest=originals.filter {it.platform==copyTarget.platform}.mapNotNull {it.replyCount?.takeIf {count->count>=0}}.maxOrNull()
    val formatter=remember {DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault())}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Collected originals")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text("Collected originals",style=MaterialTheme.typography.headlineSmall);Text("${originals.size} original ${if(originals.size==1)"post"else "posts"} · every source stays intact",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close collected originals")}
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("Collected original list"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {Text("Read each original caption, copy its own link, or open that post's comments. Reply counts come from public sources and may be incomplete.",fontSize=13.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(originals,key=repo::key) {post->
                val key=repo.key(post);val url=repo.publicUrl(post.canonicalUrl,post.platform)
                val presentation=remember(post){PostPresentation.text(post)}
                val caption=remember(post,presentation){listOf(presentation.title,presentation.body).filter(String::isNotBlank).joinToString("\n\n").let {repo.publicText(it,post.platform)}}
                var expanded by remember(key){mutableStateOf(false)}
                var overflowing by remember(key,caption){mutableStateOf(false)}
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().testTag("Original edition $key")) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            PlatformMark(post.platform,24.dp)
                            Column(Modifier.weight(1f).padding(start=10.dp)) {MentionText(post.author.ifBlank {"Author not supplied"},repo,post.platform,post.canonicalUrl,accountLabel=true,explicitProfileLinks=post.links.orEmpty(),fontWeight=FontWeight.SemiBold,fontSize=15.sp);Text(post.platform.label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        }
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(post.publishedAt?.takeIf {it>0}?.let {runCatching {formatter.format(Instant.ofEpochMilli(it))}.getOrNull()} ?: "Date not supplied",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(post.replyCount?.takeIf {it>=0}?.let {"${NumberFormat.getIntegerInstance().format(it)} reported ${if(it==1L)"reply"else "replies"}"} ?: "Reply count not supplied",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(key==defaultKey)Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.testTag("Default original link")) {
                            Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text("Default link",fontWeight=FontWeight.Medium,fontSize=11.sp)
                                Text(if(defaultCount!=null&&defaultCount==highest)"Highest supplied reply count"else "Original date fallback",fontSize=10.sp,color=MaterialTheme.colorScheme.onSecondaryContainer)
                                if(originals.map {it.platform}.distinct().size>1)Text("Within ${copyTarget.platform.label} originals",fontSize=10.sp,color=MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                        presentation.attribution?.let {MentionText(it,repo,post.platform,post.canonicalUrl,explicitProfileLinks=post.links.orEmpty(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        if(caption.isNotBlank())SelectionContainer {
                            MentionText(caption,repo,post.platform,post.canonicalUrl,explicitProfileLinks=post.links.orEmpty(),fontSize=14.sp,lineHeight=21.sp,maxLines=if(expanded)Int.MAX_VALUE else 4,overflow=TextOverflow.Ellipsis,onTextLayout={overflowing=it.hasVisualOverflow},modifier=Modifier.testTag("Original caption $key"))
                        }else Text("No caption was supplied.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Original caption $key"))
                        if(overflowing||expanded)TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("Expand original caption $key")){Text(if(expanded)"Collapse caption"else "Read full caption",fontSize=12.sp)}
                        SelectionContainer {Text(url,fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Original URL $key"))}
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={outgoing.copy(post,originals)},modifier=Modifier.testTag("Copy original $key")){Icon(Icons.Rounded.Link,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Copy link",fontSize=12.sp)}
                            TextButton(onClick={onDismiss();vm.discussions.open(listOf(post))},modifier=Modifier.testTag("Original comments $key")){Icon(Icons.Rounded.Forum,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Comments",fontSize=12.sp)}
                        }
                    }
                }
            }
        }
        }
    }
}
