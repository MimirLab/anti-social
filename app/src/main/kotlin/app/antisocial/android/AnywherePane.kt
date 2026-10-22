/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.antisocial.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AnywherePane(reader: DiscussionReader,modifier: Modifier=Modifier,onSettings: ()->Unit={},repo: Repository?=null) {
    val state by reader.community.state.collectAsState()
    val current=state ?: return
    val composing=WindowInsets.isImeVisible
    val readerFocus=remember {FocusRequester()}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(current.url){readerFocus.requestFocus();keyboard?.hide()}
    Column(modifier.focusRequester(readerFocus).focusable().testTag("CommentAnywhere reader")) {
        if(!composing)Row(Modifier.padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${current.post.platform.label} · original post",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                current.url?.let {Text((repo?.publicUrl(it,current.post.platform) ?: it).removePrefix("https://"),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("CommentAnywhere original URL"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            IconButton(onClick=onSettings){Icon(Icons.Rounded.Settings,"Comment settings",Modifier.size(20.dp))}
            IconButton(onClick={reader.community.refresh()},enabled=!current.loading&&!current.draft.working,modifier=Modifier.testTag("Refresh CommentAnywhere")){Icon(Icons.Rounded.Refresh,"Refresh shared discussion",Modifier.size(20.dp))}
        }
        if(current.loading)LinearProgressIndicator(Modifier.fillMaxWidth())else HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
        key(current.url,current.order) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("CommentAnywhere comments"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                current.error?.let {message->item {Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CloudOff,null,Modifier.size(24.dp));Text("Shared discussion unavailable",fontWeight=FontWeight.SemiBold)
                    Text(repo?.publicText(message) ?: message,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.testTag("CommentAnywhere error"))
                    Row {if(current.url!=null)TextButton(onClick={reader.community.refresh()},enabled=!current.loading){Text("Try again")};TextButton(onClick=onSettings){Text("Comment settings")}}
                }}}}
                current.page?.let {page->
                    item {Text((if(current.error!=null)"Previously loaded · "else "")+if(current.order==CommentOrder.SLOP)"Community votes"else "Newest conversations first",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    if(page.items.isEmpty()&&current.error==null)item {Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Forum,null,Modifier.size(30.dp),tint=MaterialTheme.colorScheme.primary)
                        Text("Start the conversation",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                        Text("Join this shared discussion without a social account. Original platform comments are available in their own tabs.",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }}
                    items(page.flattened(),key={it.first.id}) {(comment,depth)->
                        val own=current.owner?.let {uid->comment.authorIds.any {it==uid||it=="firebase:$uid"||it=="anon:$uid"}}==true
                        AnywhereCommentActions(reader,comment,depth,own,repo)
                    }
                    if(page.limited&&page.limit<100)item {OutlinedButton(onClick={reader.community.refresh(more=true)},enabled=!current.loading&&!current.draft.working,modifier=Modifier.fillMaxWidth()){Text("Load more conversations")}}
                    if(page.limited)item {Text("A selection of this discussion · replies may be incomplete.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                if(current.loading&&current.page==null)item {Text("Opening this page’s shared discussion…",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        AnywhereComposer(reader,onSettings,repo)
    }
}

@Composable private fun AnywhereCommentActions(reader: DiscussionReader,comment: AnywhereComment,depth: Int,own: Boolean,repo: Repository?) {
    val current by reader.community.state.collectAsState()
    var deleting by remember {mutableStateOf(false)}
    AnywhereCommentCard(comment,depth,own,repo=repo,enabled=current?.let {!it.draft.working&&!it.draft.checkRequired&&it.error==null}==true,
        onReply={reader.community.target(comment)},onVote={reader.community.mutate(AnywhereAction.VOTE,comment.id,it)},
        onEdit={reader.community.target(comment,editing=true)},onDelete={deleting=true},onExpand=if(comment.hasMore){{reader.community.refresh(parent=comment.id)}}else null)
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("Delete your comment?")},text={Text("Your public comment text will be removed from the shared discussion. Replies may remain.")},confirmButton={TextButton(onClick={deleting=false;reader.community.mutate(AnywhereAction.DELETE,comment.id)}){Text("Delete")}},dismissButton={TextButton(onClick={deleting=false}){Text("Cancel")}})
}

/** One writer for the shared page conversation, visible only in All/community.
 * It never posts to the selected original social-media platform. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AnywhereComposer(reader: DiscussionReader,onSettings: ()->Unit={},repo: Repository?=null,compact: Boolean=false) {
    val state by reader.community.state.collectAsState()
    val discussion by reader.state.collectAsState()
    val current=state ?: return
    val all=discussion ?: return
    if(all.selected !in setOf("all",COMMENT_ANYWHERE)||!reader.communityEnabled||current.url==null)return
    val composing=WindowInsets.isImeVisible
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val readerFocus=remember {FocusRequester()}
    val draft=current.draft
    // Reading starts with a small participation control, including when an old
    // draft or failed action is restored. Only a deliberate reply opens it.
    var expanded by remember(all.posts,compact){mutableStateOf(!compact)}
    LaunchedEffect(all.composeRequest) {if(all.composeRequest>0)expanded=true}
    if(compact&&!expanded) {
        Surface(onClick={expanded=true},tonalElevation=3.dp,modifier=Modifier.fillMaxWidth().testTag("Shared discussion composer")) {
            Row(Modifier.heightIn(min=48.dp).padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Forum,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {draft.working->"Sending shared comment…";draft.checkRequired->"Check your shared comment";draft.target!=null->if(draft.editing)"Continue editing your comment"else "Continue your shared reply";draft.text.isNotBlank()->"Continue your shared comment";else->"Join shared discussion"},
                    Modifier.weight(1f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.Edit,"Write a shared comment",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
            }
        }
        return
    }
    LaunchedEffect(current.url){readerFocus.requestFocus();keyboard?.hide()}
    Surface(tonalElevation=3.dp) {
        Column(Modifier.fillMaxWidth().focusRequester(readerFocus).focusable().padding(horizontal=16.dp,vertical=8.dp).testTag("Shared discussion composer"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Rounded.Forum,null,Modifier.size(16.dp));Text("Shared discussion · ${all.originalLabel(current.post)} page",Modifier.weight(1f).padding(start=7.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                IconButton(onClick=onSettings,modifier=Modifier.size(32.dp)){Icon(Icons.Rounded.Settings,"Shared comment settings",Modifier.size(17.dp))}
                if(compact)IconButton(onClick={focus.clearFocus();keyboard?.hide();expanded=false},modifier=Modifier.size(32.dp)){Icon(Icons.Rounded.ExpandMore,"Minimize shared composer",Modifier.size(20.dp))}
            }
            if(all.selected=="all"||composing)Text((repo?.publicUrl(current.url,current.post.platform) ?: current.url).removePrefix("https://"),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Shared composer original URL"))
            if(all.posts.size>1)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                all.posts.forEach {post->val key=all.threadKey(post);FilterChip(selected=all.edition==key,onClick={reader.edition(key)},label={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){PlatformMark(post.platform,20.dp);if(all.posts.count {it.platform==post.platform}>1)Text("${all.originalNumber(post)}",fontSize=11.sp)}},modifier=Modifier.testTag("CommentAnywhere edition $key"))}
            }
            draft.target?.let {target->Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(draft.editing)"Editing your shared comment"else "Replying to ${target.username}",Modifier.weight(1f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={reader.community.target(null)},enabled=!draft.working,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text("Cancel",fontSize=12.sp)}
            }}
            draft.message?.let {Text(it,fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("CommentAnywhere action status"))}
            if(compact&&current.error!=null)Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Posting is paused while the shared discussion is unavailable.",Modifier.weight(1f),fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={reader.community.refresh()},enabled=!current.loading&&!draft.working){Text("Retry",fontSize=12.sp)}
            }
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.text,{reader.community.draft(it)},placeholder={Text("Join the discussion…")},enabled=!draft.working,maxLines=4,
                    modifier=Modifier.weight(1f).heightIn(min=56.dp).testTag("CommentAnywhere draft"),shape=RoundedCornerShape(16.dp),
                    keyboardOptions=KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))
                FilledIconButton(onClick={focus.clearFocus();keyboard?.hide();reader.community.publish()},enabled=draft.text.isNotBlank()&&draft.text.toByteArray().size<=8000&&!draft.working&&!draft.checkRequired&&!current.loading&&current.error==null&&current.page!=null,modifier=Modifier.size(48.dp).testTag("Publish CommentAnywhere")) {
                    if(draft.working)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Rounded.ArrowUpward,if(draft.editing)"Save shared edit"else "Publish to shared discussion")
                }
            }
            if(!composing)Text("Public shared comments · no social account needed",fontSize=10.sp,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun AnywhereCommentCard(comment: AnywhereComment,depth: Int=0,own: Boolean=false,enabled: Boolean=true,
    onReply: (()->Unit)?=null,onVote: ((Int)->Unit)?=null,onEdit: (()->Unit)?=null,onDelete: (()->Unit)?=null,onExpand: (()->Unit)?=null,repo: Repository?=null) {
    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant,
        modifier=Modifier.fillMaxWidth().padding(start=(depth.coerceAtMost(4)*10).dp).testTag("CommentAnywhere comment ${comment.id}")) {
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(comment.deleted)"Deleted comment"else comment.username+(if(own)" · you"else ""),Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                comment.timestamp?.let {Text(DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Text(if(comment.deleted)"[deleted by user]"else repo?.publicText(comment.text) ?: comment.text,fontSize=14.sp,lineHeight=21.sp)
            if(!comment.deleted&&onReply!=null)Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onReply,enabled=enabled,modifier=Modifier.size(40.dp)){Icon(Icons.AutoMirrored.Rounded.Reply,"Reply to ${comment.username}",Modifier.size(18.dp))}
                if(onVote!=null){IconButton(onClick={onVote(1)},enabled=enabled,modifier=Modifier.size(40.dp)){Icon(Icons.Rounded.ThumbUpOffAlt,"Upvote comment",Modifier.size(17.dp))}}else Icon(Icons.Rounded.ThumbUpOffAlt,null,Modifier.size(16.dp))
                Text("${comment.up}",Modifier.padding(horizontal=5.dp),fontSize=11.sp)
                if(onVote!=null){IconButton(onClick={onVote(-1)},enabled=enabled,modifier=Modifier.size(40.dp)){Icon(Icons.Rounded.ThumbDownOffAlt,"Downvote comment",Modifier.size(17.dp))}}else Icon(Icons.Rounded.ThumbDownOffAlt,null,Modifier.size(16.dp))
                Text("${comment.down}",Modifier.padding(horizontal=5.dp),fontSize=11.sp)
                Spacer(Modifier.weight(1f))
                if(own){IconButton(onClick={onEdit?.invoke()},enabled=enabled,modifier=Modifier.size(40.dp)){Icon(Icons.Rounded.Edit,"Edit your comment",Modifier.size(17.dp))};IconButton(onClick={onDelete?.invoke()},enabled=enabled,modifier=Modifier.size(40.dp)){Icon(Icons.Rounded.DeleteOutline,"Delete your comment",Modifier.size(17.dp))}}
            }
            onExpand?.let {TextButton(onClick=it,enabled=enabled){Text("Load replies",fontSize=12.sp)}}
        }
    }
}
