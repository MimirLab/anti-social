/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChannelsSheet(store: PersonalChannelStore,follows: List<Follow>,onDismiss: ()->Unit,onShareSources: ((PersonalChannel)->Unit)?=null) {
    val channels by store.channels.collectAsState()
    val selected by store.selected.collectAsState()
    val error by store.error.collectAsState()
    var editing by remember {mutableStateOf<PersonalChannel?>(null)}
    var deleting by remember {mutableStateOf<PersonalChannel?>(null)}
    var resetting by remember {mutableStateOf(false)}
    var operationError by remember {mutableStateOf<String?>(null)}
    val subscriptions=remember(follows){Subscriptions.bundles(follows)}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Channels sheet")) {
        if(editing!=null) {
            ChannelRuleEditor(editing!!,subscriptions,onBack={editing=null},onSave={channel->
                try {val saved=store.save(channel);store.select(saved.id);onDismiss();null}
                catch(e: IllegalArgumentException){e.message ?: "Check these channel rules."}
                catch(e: IllegalStateException){e.message ?: "This channel could not be saved."}
            })
        }else LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.88f),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text("Your channels",style=MaterialTheme.typography.headlineSmall);Text("Your rules. Always chronological.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close channels")}
            }}
            item {ChannelChoice("All subscriptions","Every enabled subscription",selected==null,Modifier.testTag("Channel all"),onClick={store.select(null);onDismiss()})}
            error?.let {message->item {Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(message,fontSize=13.sp,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.testTag("Channel storage error"))
                    TextButton(onClick={resetting=true},modifier=Modifier.testTag("Reset channels")){Text("Reset channels")}
                }
            }}}
            if(channels.isEmpty()&&error==null)item {Text("Create Software releases, Local, or a channel of your own. A subscription can belong in as many channels as you like.",fontSize=14.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))}
            items(channels,key={it.id}) {channel->
                Surface(shape=MaterialTheme.shapes.large,color=if(channel.id==selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {store.select(channel.id);onDismiss()}.padding(vertical=16.dp).testTag("Channel select ${channel.id}")) {
                            Row(verticalAlignment=Alignment.CenterVertically) {Text(channel.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);if(channel.id==selected)Icon(Icons.Rounded.Check,null,Modifier.size(20.dp))}
                            Text(channelSummary(channel),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                        }
                        IconButton(onClick={editing=channel},modifier=Modifier.testTag("Channel edit ${channel.id}")){Icon(Icons.Rounded.Edit,"Edit ${channel.name}")}
                        if(onShareSources!=null)IconButton(onClick={onShareSources(channel)},modifier=Modifier.testTag("Channel share ${channel.id}")){Icon(Icons.Rounded.Share,"Share sources from ${channel.name}")}
                        IconButton(onClick={deleting=channel},modifier=Modifier.testTag("Channel delete ${channel.id}")){Icon(Icons.Rounded.DeleteOutline,"Delete ${channel.name}")}
                    }
                }
            }
            if(channels.size<PersonalChannels.MAX_CHANNELS&&error==null)item {FilledTonalButton(onClick={editing=PersonalChannel(id=UUID.randomUUID().toString(),name="")},modifier=Modifier.fillMaxWidth().testTag("New channel")) {Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text("Create a channel")}}
            operationError?.let {message->item {Text(message,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}}
        }
    }
    deleting?.let {channel->AlertDialog(onDismissRequest={deleting=null},title={Text("Delete ${channel.name}?")},text={Text("Its rules will be removed. Your subscriptions and saved posts stay here.")},confirmButton={TextButton(onClick={try {store.delete(channel.id);deleting=null}catch(e: Exception){operationError=e.message;deleting=null}},modifier=Modifier.testTag("Confirm delete channel")){Text("Delete")}},dismissButton={TextButton(onClick={deleting=null}){Text("Keep channel")}})}
    if(resetting)AlertDialog(onDismissRequest={resetting=false},title={Text("Reset saved channels?")},text={Text("This removes all local channel rules and returns to All subscriptions. Your subscriptions and saved posts stay here.")},confirmButton={TextButton(onClick={store.reset();resetting=false},modifier=Modifier.testTag("Confirm reset channels")){Text("Reset channels")}},dismissButton={TextButton(onClick={resetting=false}){Text("Cancel")}})
}

@Composable private fun ChannelChoice(title: String,subtitle: String,selected: Boolean,modifier: Modifier=Modifier,onClick: ()->Unit) {
    Surface(shape=MaterialTheme.shapes.large,color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=modifier.fillMaxWidth().clickable(onClick=onClick)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Rounded.DynamicFeed,null,Modifier.padding(end=12.dp).size(24.dp))
            Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(selected)Icon(Icons.Rounded.Check,null)
        }
    }
}

private fun channelSummary(channel: PersonalChannel): String=buildList {
    add(if(channel.subscriptionIds.isEmpty())"All subscriptions"else "${channel.subscriptionIds.size} selected ${if(channel.subscriptionIds.size==1)"subscription"else "subscriptions"}")
    val terms=channel.keywords.size+channel.hashtags.size
    if(terms>0)add("$terms ${if(terms==1)"term"else "terms"}")
    if(channel.contentTypes.isNotEmpty())add(channel.contentTypes.joinToString(", "){it.channelLabel()})
    if(channel.minVideoSeconds!=null)add("${videoMinutes(channel.minVideoSeconds!!)}+ min videos")
    if(channel.excludeContentTypes.isNotEmpty())add("Excluded: "+channel.excludeContentTypes.joinToString(", "){it.channelLabel()})
    if(channel.excludeKeywords.isNotEmpty()||channel.excludeHashtags.isNotEmpty()||channel.excludeSubscriptionIds.isNotEmpty())add("Exclusions")
}.joinToString(" · ")

private fun ChannelContentType.channelLabel()=when(this) {
    ChannelContentType.TEXT->"Text only";ChannelContentType.IMAGES->"Images";ChannelContentType.VIDEO->"Video";ChannelContentType.AUDIO->"Audio";ChannelContentType.LINKS->"Links"
}
private fun videoMinutes(seconds: Long)=BigDecimal(seconds).divide(BigDecimal(60),4,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
private fun channelTerms(value: String)=value.split(',', '\n').map(String::trim).filter(String::isNotEmpty)

@Composable private fun ChannelRuleEditor(channel: PersonalChannel,subscriptions: List<Subscription>,onBack: ()->Unit,onSave: (PersonalChannel)->String?) {
    var name by remember(channel.id){mutableStateOf(channel.name)}
    var sources by remember(channel.id){mutableStateOf(channel.subscriptionIds)}
    var keywords by remember(channel.id){mutableStateOf(channel.keywords.joinToString("\n"))}
    var hashtags by remember(channel.id){mutableStateOf(channel.hashtags.joinToString("\n"))}
    var types by remember(channel.id){mutableStateOf(channel.contentTypes)}
    var minimum by remember(channel.id){mutableStateOf(channel.minVideoSeconds?.let(::videoMinutes).orEmpty())}
    var excludeKeywords by remember(channel.id){mutableStateOf(channel.excludeKeywords.joinToString("\n"))}
    var excludeHashtags by remember(channel.id){mutableStateOf(channel.excludeHashtags.joinToString("\n"))}
    var excludeSources by remember(channel.id){mutableStateOf(channel.excludeSubscriptionIds)}
    var excludeTypes by remember(channel.id){mutableStateOf(channel.excludeContentTypes)}
    var exclusions by remember(channel.id){mutableStateOf(channel.excludeKeywords.isNotEmpty()||channel.excludeHashtags.isNotEmpty()||channel.excludeSubscriptionIds.isNotEmpty()||channel.excludeContentTypes.isNotEmpty())}
    var picker by remember {mutableStateOf<String?>(null)}
    var errors by remember {mutableStateOf<List<String>>(emptyList())}
    if(picker!=null) {
        ChannelSubscriptionPicker(subscriptions,if(picker=="include")sources else excludeSources,picker=="exclude",onChange={if(picker=="include")sources=it else excludeSources=it},onBack={picker=null})
        return
    }
    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.88f).testTag("Channel editor"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back to channels")};Text(if(channel.name.isEmpty())"Create a channel"else "Edit channel",style=MaterialTheme.typography.headlineSmall)}}
        item {OutlinedTextField(name,{name=it;errors=emptyList()},singleLine=true,label={Text("Channel name")},placeholder={Text("Software releases")},modifier=Modifier.fillMaxWidth().testTag("Channel name"))}
        item {Text("Each filled rule must match. Any entry within a rule can match. Exclusions always win.",fontSize=13.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {ChannelSection("Subscriptions","A creator can appear in several channels.") {
            OutlinedButton(onClick={picker="include"},modifier=Modifier.fillMaxWidth().testTag("Channel subscriptions")){Text(if(sources.isEmpty())"All enabled subscriptions"else "${sources.size} selected ${if(sources.size==1)"subscription"else "subscriptions"}")}
        }}
        item {ChannelSection("Words & hashtags","Keywords and hashtags are separate rules. Use commas or new lines between alternatives; letter case does not matter.") {
            ChannelTermField(keywords,{keywords=it;errors=emptyList()},"Keywords or phrases","release, version, changelog","Channel keywords")
            ChannelTermField(hashtags,{hashtags=it;errors=emptyList()},"Hashtags","opensource, local","Channel hashtags")
        }}
        item {ChannelSection("Content","Any selected type can match. Leave all unchecked for every content type.") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ChannelContentType.entries.forEach {type->FilterChip(selected=type in types,onClick={types=if(type in types)types-type else types+type},label={Text(type.channelLabel())},modifier=Modifier.testTag("Channel type ${type.name}"))}
            }
            OutlinedTextField(minimum,{minimum=it;errors=emptyList()},label={Text("Minimum video length (minutes)")},placeholder={Text("Optional, e.g. 10")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("Channel minimum video"))
            Text("A minimum length requires a video with a known duration. Sources that omit duration cannot match it.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        item {TextButton(onClick={exclusions=!exclusions},modifier=Modifier.testTag("Channel exclusions")){Icon(if(exclusions)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null);Text("Exclusions${if(excludeKeywords.isNotEmpty()||excludeHashtags.isNotEmpty()||excludeSources.isNotEmpty()||excludeTypes.isNotEmpty())" · active"else ""}")}}
        if(exclusions)item {ChannelSection("Keep out","An exclusion in any source edition keeps the whole merged post out.") {
            Text("Excluded content types",fontSize=14.sp,fontWeight=FontWeight.Medium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ChannelContentType.entries.forEach {type->FilterChip(selected=type in excludeTypes,onClick={excludeTypes=if(type in excludeTypes)excludeTypes-type else excludeTypes+type},label={Text(type.channelLabel())},modifier=Modifier.testTag("Channel excluded type ${type.name}"))}
            }
            Text("Any selected type keeps the post out, even when it also has other content.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ChannelTermField(excludeKeywords,{excludeKeywords=it;errors=emptyList()},"Excluded keywords or phrases","sponsored, giveaway","Channel excluded keywords")
            ChannelTermField(excludeHashtags,{excludeHashtags=it;errors=emptyList()},"Excluded hashtags","ad","Channel excluded hashtags")
            OutlinedButton(onClick={picker="exclude"},modifier=Modifier.fillMaxWidth().testTag("Channel excluded subscriptions")){Text(if(excludeSources.isEmpty())"No excluded subscriptions"else "${excludeSources.size} excluded ${if(excludeSources.size==1)"subscription"else "subscriptions"}")}
        }}
        if(errors.isNotEmpty())item {Text(errors.joinToString("\n"),color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.testTag("Channel validation"))}
        item {Button(onClick={
            // Decimal minutes are only a display aid. Preserve an imported
            // second-accurate threshold when the duration field was untouched.
            val seconds=if(minimum==channel.minVideoSeconds?.let(::videoMinutes).orEmpty())channel.minVideoSeconds else if(minimum.isBlank())null else runCatching {BigDecimal(minimum.trim()).multiply(BigDecimal(60)).setScale(0,RoundingMode.CEILING).longValueExact()}.getOrNull()
            val candidate=channel.copy(name=name,subscriptionIds=sources,keywords=channelTerms(keywords),hashtags=channelTerms(hashtags),contentTypes=types,excludeKeywords=channelTerms(excludeKeywords),excludeHashtags=channelTerms(excludeHashtags),excludeSubscriptionIds=excludeSources,minVideoSeconds=seconds,excludeContentTypes=excludeTypes)
            val invalid=PersonalChannels.validate(candidate).toMutableList()
            if(minimum.isNotBlank()&&seconds==null)invalid.add("Enter a valid minimum video length in minutes.")
            if(invalid.isEmpty())onSave(candidate)?.let {invalid.add(it)}
            errors=invalid
        },modifier=Modifier.fillMaxWidth().testTag("Save channel")){Text("Save channel")}}
    }
}

@Composable private fun ChannelSection(title: String,description: String,content: @Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontWeight=FontWeight.SemiBold)
        Text(description,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable private fun ChannelTermField(value: String,onValueChange: (String)->Unit,label: String,placeholder: String,tag: String) {
    OutlinedTextField(value,onValueChange,label={Text(label)},placeholder={Text(placeholder)},minLines=1,maxLines=4,modifier=Modifier.fillMaxWidth().testTag(tag))
}

@Composable private fun ChannelSubscriptionPicker(subscriptions: List<Subscription>,selected: Set<String>,excluding: Boolean,onChange: (Set<String>)->Unit,onBack: ()->Unit) {
    var query by remember {mutableStateOf("")}
    val missing=selected-subscriptions.map {it.id}.toSet()
    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.88f).testTag("Channel subscription picker"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically) {IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back to channel rules")};Text(if(excluding)"Exclude subscriptions"else "Choose subscriptions",style=MaterialTheme.typography.titleLarge)}}
        item {Text(if(excluding)"Posts from these subscriptions stay out of this channel."else "Choose creators or sources for this channel. Leave all unchecked to use every enabled subscription.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {OutlinedTextField(query,{query=it},label={Text("Find a subscription")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Channel find subscription"))}
        if(selected.isNotEmpty())item {TextButton(onClick={onChange(emptySet())},modifier=Modifier.testTag("Channel clear subscriptions")){Text("Clear selection (${selected.size})")}}
        if(missing.isNotEmpty())item {Text("${missing.size} selected ${if(missing.size==1)"subscription is"else "subscriptions are"} no longer available. ${if(missing.size==1)"Its rule is"else "Their rules are"} preserved until you clear the selection.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(subscriptions.isEmpty())item {Text("Add subscriptions first, or leave this rule open for future subscriptions.",fontSize=14.sp,modifier=Modifier.padding(vertical=12.dp))}
        items(subscriptions.filter {it.name.contains(query,true)||it.accounts.any {account->account.target.identity.contains(query,true)}},key={it.id}) {subscription->
            Row(Modifier.fillMaxWidth().toggleable(subscription.id in selected,role=Role.Checkbox,onValueChange={checked->onChange(if(checked)selected+subscription.id else selected-subscription.id)}).testTag("Channel subscription ${subscription.id}").padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(subscription.id in selected,null)
                Column(Modifier.weight(1f).padding(start=8.dp)){Text(subscription.name,fontWeight=FontWeight.Medium);Text(subscription.accounts.map {it.target.platform.label}.distinct().joinToString(" · ")+if(subscription.accounts.none {it.enabled})" · Paused"else "",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        item {FilledTonalButton(onClick=onBack,modifier=Modifier.fillMaxWidth().testTag("Channel subscriptions done")){Text("Done")}}
    }
}
