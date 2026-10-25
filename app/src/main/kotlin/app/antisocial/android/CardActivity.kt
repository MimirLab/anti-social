/* anti social, GPL-3.0-or-later. A small, truthful status in existing card chrome. */
package app.antisocial.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antisocial.core.*

internal fun postWorkKey(post:Post)="post:"+post.identityKey
internal fun businessWorkKey(id:String)="business:$id"
internal fun eventWorkKey(id:String)="event:$id"
internal fun mediaWorkKey(url:String)="media:$url"
internal fun localSourceWorkKeys(url:String)=listOfNotNull(LocalDiscoveryIdentity.url(url)?.let {"local-source:$it"})
internal fun businessWorkKeys(value:LocalBusiness):Set<String> = buildSet {
    add(businessWorkKey(value.id));value.sourceIds.forEach {add(businessWorkKey(it))}
    (value.websites+value.socials+value.evidence.map {it.sourceUrl}).forEach {addAll(localSourceWorkKeys(it))}
}
internal fun eventWorkKeys(value:LocalEvent):Set<String> = buildSet {
    add(eventWorkKey(value.id));(value.urls+value.evidence.map {it.sourceUrl}).forEach {addAll(localSourceWorkKeys(it))}
}

@Composable internal fun CardActivity(work:CardWork,keys:Set<String>,tag:String,compact:Boolean=false,discoveryPending:Boolean=false) {
    val flow=remember(work,keys){work.activity(keys)}
    val tasks by flow.collectAsStateWithLifecycle(emptyList())
    var details by remember(tag){mutableStateOf(false)}
    val busy=tasks.isNotEmpty()
    // A fixed footprint avoids changing card geometry when status changes.
    Row(Modifier.width(88.dp).heightIn(min=if(compact)22.dp else 44.dp).testTag("Card activity $tag")
        .semantics {stateDescription=if(busy)"Updating"else if(discoveryPending)"Searching"else "Ready"}.clickable {details=true},
        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        if(busy||discoveryPending)CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp)
        else Icon(Icons.Rounded.Check,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(if(busy)"Updating…"else if(discoveryPending)"Searching…"else "Ready",fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text(if(busy)"Adding details"else if(discoveryPending)"Searching public sources"else "Ready to read")},
        text={Text(if(busy)"Working on: "+tasks.joinToString(", ")+". You can keep reading or move on; updates arrive automatically."
            else if(discoveryPending)"Other source searches are still running. No update is running for this card right now; useful details can arrive as those searches finish. You can keep reading or move on."
            else "No background updates are running for this card. You can read it and move on. A later refresh may find new information.")},
        confirmButton={TextButton(onClick={details=false}){Text("Done")}})
}

/** Only derive ownership keys; this does not schedule more work. */
@Composable internal fun PostActivity(repo:Repository,posts:List<Post>,links:List<String>) {
    val keys=remember(repo,posts,links){buildSet {
        links.mapNotNull(PublicSourceRecords::paper).forEach {add("source:"+it.key)}
        posts.forEach {post->
            add(postWorkKey(post))
            post.media.forEach {add(mediaWorkKey(it.url));it.preview?.let {u->add(mediaWorkKey(u))}}
            post.avatar?.let {add(mediaWorkKey(it))}
            val hasMentions='@' in post.body||'@' in post.title||"u/" in post.body||"u/" in post.title
            val text=if(hasMentions)PostPresentation.content(post)else ""
            val mentions=(if('@' in text||"u/" in text)MentionNames.find(text,post.platform,post.canonicalUrl,repo.routes,post.links.orEmpty())else emptyList())+
                MentionNames.label(post.author,post.platform,post.canonicalUrl,repo.routes,post.links.orEmpty())
            mentions.forEach {add("mention:"+it.key.id)}
        }
    }}
    CardActivity(repo.cardWork,keys,posts.first().identityKey,compact=true)
}
