/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class FeedRuleExplanation(val channel: String,val reasons: List<String>)
internal data class ChannelFeedSelection(val posts: List<Post>,val counterparts: Map<String,List<Post>>,val explanations: Map<String,FeedRuleExplanation>)

/** Evaluate the full known fusion before exposing any edition. A forbidden extra
 * caption must not return through either fusion or the PostCard discussion sidecar. */
internal fun selectChannelFeed(channel: PersonalChannel,posts: List<Post>,follows: List<Follow>,memberships: Map<String,Set<String>>,counterparts: Map<String,List<Post>>,present: (Post)->Post={it}): ChannelFeedSelection {
    val match=PersonalChannels.matcher(channel,follows,memberships)
    val examined=mutableSetOf<String>()
    val admitted=mutableMapOf<String,List<Post>>()
    val reasons=mutableMapOf<String,FeedRuleExplanation>()
    for(post in posts) {
        val key=PersonalChannels.postKey(post)
        if(key in examined)continue
        val group=(listOf(post)+counterparts[key].orEmpty()).distinctBy(PersonalChannels::postKey)
        examined+=group.map(PersonalChannels::postKey)
        val result=match(group.map(present)) ?: continue
        val explanation=FeedRuleExplanation(channel.name,result.reasons)
        group.forEach {edition->val identity=PersonalChannels.postKey(edition);admitted[identity]=group;reasons[identity]=explanation}
    }
    return ChannelFeedSelection(posts.filter {PersonalChannels.postKey(it) in admitted},admitted,reasons)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChannelSelector(channels: List<PersonalChannel>,selected: String?,onSelect: (String?)->Unit,onManage: ()->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Feed chooser"),containerColor=MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp)) {
            item {Text("Your feeds",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(bottom=12.dp))}
            item {Row(Modifier.fillMaxWidth().selectable(selected==null,onClick={onSelect(null)},role=Role.RadioButton).testTag("Channel all subscriptions").padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected==null,onClick=null);Text("All subscriptions",Modifier.padding(start=12.dp))
            }}
            items(channels,key={it.id}) {channel->Row(Modifier.fillMaxWidth().selectable(selected==channel.id,onClick={onSelect(channel.id)},role=Role.RadioButton).testTag("Channel tab ${channel.id}").padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected==channel.id,onClick=null);Text(channel.name,Modifier.padding(start=12.dp))
            }}
            item {HorizontalDivider(Modifier.padding(vertical=10.dp));TextButton(onClick=onManage,modifier=Modifier.testTag("Manage channels")) {
                Icon(if(channels.isEmpty())Icons.Rounded.Add else Icons.Rounded.Tune,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text(if(channels.isEmpty())"Create channel"else "Manage channels")
            }}
        }
    }
}

private data class ChannelFeedInput(val posts: List<Post>,val follows: List<Follow>,val memberships: Map<String,Set<String>>,val counterparts: Map<String,List<Post>>)
private data class EvaluatedChannelFeed(val input: ChannelFeedInput,val selection: ChannelFeedSelection)

@Composable internal fun PersonalChannelFeed(vm: AntiSocialViewModel,channel: PersonalChannel,posts: List<Post>,follows: List<Follow>,savedKeys: Set<String>,memberships: Map<String,Set<String>>,counterparts: Map<String,List<Post>>,ready: Boolean,onAdd: ()->Unit,onEdit: ()->Unit) {
    val fingerprint=remember(channel){PersonalChannels.fingerprint(channel)}
    key(channel.id,fingerprint) {
        val state by vm.screen.collectAsState()
        val scope=if(state.combined)"all"else state.platform.id
        val input=remember(posts,follows,memberships,counterparts){ChannelFeedInput(posts,follows,memberships,counterparts)}
        val result by produceState<EvaluatedChannelFeed?>(null,channel,input,ready) {
            if(ready)value=withContext(Dispatchers.Default){EvaluatedChannelFeed(input,selectChannelFeed(channel,input.posts,input.follows,input.memberships,input.counterparts) {post->
                post.copy(title=vm.repo.publicText(post.title,post.platform),body=vm.repo.publicText(post.body,post.platform),links=post.links?.map {vm.repo.publicUrl(it,post.platform)})
            })}
        }
        val selection=result?.selection ?: ChannelFeedSelection(emptyList(),emptyMap(),emptyMap())
        val accounts=remember(channel,follows,scope){PersonalChannels.accounts(channel,follows).filter {state.combined||it.target.platform==state.platform}}
        val complete=ready&&result?.input===input
        val message=if(accounts.isEmpty())"No enabled subscriptions fit these source rules. Edit the channel or add a subscription."
            else "Only fetched posts are checked. Refresh the selected subscriptions or edit the rules."+
                if(channel.minVideoSeconds!=null)" Videos without a supplied duration do not match the minimum length."else ""
        CompositionLocalProvider(LocalPostCounterparts provides selection.counterparts) {
            ChronologicalFeed(vm,selection.posts,accounts,savedKeys,"channel:${channel.id}:$fingerprint:$scope",channel.name,"${if(state.combined)"All platforms"else state.platform.label} · newest first · your rules",grouped=true,onAdd=onAdd,onRefresh={vm.refresh()},counterparts=selection.counterparts,counterpartsReady=complete,explanations=selection.explanations,emptyTitle="No matching posts yet",emptyMessage=message,onEditRules=onEdit)
        }
    }
}

@Composable internal fun UnavailableChannel(message: String,onManage: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("Channel unavailable"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.FilterAlt,null)
        Text("This channel needs attention",style=MaterialTheme.typography.titleLarge)
        Text(message,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick=onManage){Text("Choose a channel")}
    }
}
