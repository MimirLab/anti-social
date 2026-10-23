/* AntiSocial, GPL-3.0-or-later. Optional model setup; standard reader needs no AI. */
package app.antisocial.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.*

@Composable internal fun AiSettingsScreen(repo: Repository) {
    val manager=repo.ai
    val state by manager.state.collectAsState()
    val scope=rememberCoroutineScope()
    var kindName by rememberSaveable {mutableStateOf("TEXT")}
    val kind=AiKind.valueOf(kindName)
    var revision by remember {mutableIntStateOf(0)}
    var editor by remember {mutableStateOf<AiProviderSettings?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val recommendation=remember(revision,state.enabled){manager.deviceRecommendation()}
    val profile=remember(revision,state.enabled){manager.preferences.textProfile}
    val providers=remember(kind,revision){manager.preferences.providers(kind.name.lowercase())}
    val selected=remember(kind,revision){manager.preferences.selectedId(kind.name.lowercase())}
    val routing=remember(kind,revision){manager.preferences.routing(kind.name.lowercase())}
    fun work(action: suspend ()->Unit) {scope.launch {try {error=null;action();revision++}catch(e: CancellationException){throw e}catch(e: Throwable){error=e.message?.take(240) ?: "Could not complete model setup."}}}
    LaunchedEffect(Unit){manager.refresh()}
    LaunchedEffect(editor){error=null}
    LazyColumn(Modifier.fillMaxSize().testTag("AI settings list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {AiCard {
            Row(Modifier.fillMaxWidth().toggleable(state.enabled,role=Role.Switch,onValueChange={manager.enabled(it);revision++}).testTag("AI master toggle").padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                    Text("AI enhancements",fontWeight=FontWeight.SemiBold)
                    Text(if(state.enabled)"Your models, your choice"else "Off by default · reading needs no AI",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.enabled,onCheckedChange=null)
            }
        }}
        item {Text(if(state.enabled)"Local models come first by default. Choose your own server or cloud models whenever you prefer. Text and embeddings have separate choices; your feed stays chronological."
            else "Read, search, follow and save without a model, API key or AI account. Enable AI here when you want to set up optional enhancements.",fontSize=14.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(state.enabled) {
            item {AiCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PhoneAndroid,null,tint=MaterialTheme.colorScheme.primary)
                    Text("ON THIS DEVICE",Modifier.padding(start=10.dp),fontSize=11.sp,letterSpacing=1.sp,fontWeight=FontWeight.SemiBold)
                }
                Text("Text + embeddings",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                Text("Bonsai 2 with a small CPU embedding companion. The text engine installs only when you choose local text, and can be removed separately. No keys, login or separate app.",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val complete=state.textInstalled!=null&&state.embeddingInstalled!=null
                if(state.working) {
                    state.progress?.let {progress->
                        LinearProgressIndicator(progress={if(progress.totalBytes>0)(progress.downloadedBytes.toFloat()/progress.totalBytes).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth().testTag("AI download progress"))
                        Text(if(progress.verifying)"Verifying download…"else "${aiBytes(progress.downloadedBytes)} of ${aiBytes(progress.totalBytes)}",fontSize=12.sp)
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick=manager::cancelDownload,modifier=Modifier.testTag("Pause AI download")){Text("Pause download")}
                }else {
                    val bytes=runCatching {manager.selectedText().downloadBytes+manager.catalog.default(AiKind.EMBEDDING).downloadBytes+
                        (if(state.engineInstalled)0 else LocalTextRuntime.pack(repo.context)?.donorArchiveBytes ?: 0)}.getOrDefault(0)
                    if(!complete)Button(onClick={try {manager.download()}catch(e: Exception){error=e.message}},enabled=manager.localSupported,modifier=Modifier.fillMaxWidth().testTag("Download local AI pack")) {
                        Icon(Icons.Rounded.Download,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Download & activate · ${aiBytes(bytes)}")
                    }else OutlinedButton(onClick={work {manager.checkSetup()}},modifier=Modifier.testTag("Check local AI setup")){Text("Check setup")}
                }
                Text(state.message,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("AI setup status"))
                if(!manager.localSupported)Text("This device's architecture is not supported by the local runtime. You can connect your own provider below.",fontSize=12.sp)
            }}}
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("AI model tabs")) {
                    AiKind.entries.forEachIndexed {index,item->SegmentedButton(selected=kind==item,onClick={kindName=item.name},shape=SegmentedButtonDefaults.itemShape(index,AiKind.entries.size),modifier=Modifier.testTag("AI model ${item.name.lowercase()}")){Text(if(item==AiKind.TEXT)"Text"else "Embeddings")}}
                }
            }
            item {AiCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(kind==AiKind.TEXT)"Text model"else "Embedding model",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                Text(if(kind==AiKind.TEXT)state.textInstalled ?: "No local text model installed"else state.embeddingInstalled ?: "No local embedding model installed",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(kind==AiKind.TEXT) {
                    for((id,label,description) in listOf(Triple("compact","Bonsai 2 · 2B","Smaller download · 517 MB"),Triple("standard","Bonsai 2 · 4B","Standard profile · 1.15 GB"),Triple("quality","Bonsai 2 · 9B","Larger model · 2.42 GB"))) {
                        Row(Modifier.fillMaxWidth().selectable(profile==id,role=Role.RadioButton,onClick={manager.preferences.textProfile=id;revision++}).testTag("AI text profile $id").padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(profile==id,onClick=null)
                            Column(Modifier.weight(1f).padding(start=10.dp)){Text(label,fontSize=14.sp,fontWeight=FontWeight.Medium);Text(description,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            if(recommendation.profile==id)Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(10.dp)){Text("Recommended",Modifier.padding(horizontal=8.dp,vertical=6.dp),fontSize=10.sp)}
                        }
                    }
                    Text(recommendation.reason+" "+recommendation.qualification+".",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }else Text("SSE · English · 512 dimensions\n8.65 MB · CPU · independent of the text model",fontSize=13.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val installed=if(kind==AiKind.TEXT)state.textInstalled!=null else state.embeddingInstalled!=null
                Text(if(kind==AiKind.TEXT)state.textRoute else state.embeddingRoute,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=5)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={try {manager.download(kind)}catch(e: Exception){error=e.message}},enabled=!state.working&&manager.localSupported,modifier=Modifier.rejectObscuredTouches().testTag("Download selected AI model")){Text(if(installed)"Install selected"else "Download")}
                    if(installed)TextButton(onClick={work {manager.remove(kind)}},enabled=!state.working,modifier=Modifier.testTag("Remove selected AI model")){Text("Remove")}
                }
                if(installed)TextButton(onClick={work {manager.checkSetup(kind)}},enabled=!state.working,modifier=Modifier.testTag("Check selected AI model")){Text("Check this model")}
                if(kind==AiKind.TEXT&&manager.localSupported) {
                    HorizontalDivider()
                    Text(if(state.engineInstalled)"Text engine · ${aiBytes(state.engineBytes)}"else "Text engine · not installed",fontSize=13.sp,modifier=Modifier.testTag("Local text engine status"))
                    Text("A compact installer stays in the app. Setup restores our exact custom engine; temporary upstream files are then deleted.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.engineInstalled)TextButton(onClick={work {manager.removeEngine()}},enabled=!state.working,modifier=Modifier.testTag("Remove local text engine")){Text("Remove engine · keep models")}
                    else if(installed)TextButton(onClick={work {manager.installEngine()}},enabled=!state.working,modifier=Modifier.rejectObscuredTouches().testTag("Install local text engine")){Text("Install engine")}
                }
            }}}
            item {AiCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.Dns,null,tint=MaterialTheme.colorScheme.primary);Text("YOUR PROVIDERS",Modifier.padding(start=10.dp),fontSize=11.sp,letterSpacing=1.sp)}
                Text(if(kind==AiKind.TEXT)"Text models"else "Embedding models",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                AiRouting.entries.forEach {option->
                    Row(Modifier.fillMaxWidth().selectable(routing==option,role=Role.RadioButton,onClick={manager.routing(kind,option);revision++}).testTag("AI routing ${option.name}").padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(routing==option,onClick=null)
                        Text(option.label,Modifier.padding(start=10.dp),fontSize=14.sp)
                    }
                }
                Text(when(routing) {
                    AiRouting.LOCAL_FIRST->"Use the downloaded model first, then your selected provider if no local model was chosen. A failed local request stays local."
                    AiRouting.DEVICE_ONLY->"Only downloaded models. No text or embeddings go to a server."
                    AiRouting.PROVIDER_ONLY->"Use your selected provider, even with a model downloaded. Keep local models ready for whenever you want them."
                },fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                val slots=AiProviderPreset.entries.filter {it!=AiProviderPreset.CUSTOM&&it.supports(kind)}.map {preset->
                    providers.firstOrNull {it.id==preset.name.lowercase()} ?: AiProviderSettings(id=preset.name.lowercase(),preset=preset,baseUrl=preset.baseUrl,model=preset.model(kind),protocol=preset.protocol)
                }+providers.filter {it.preset==AiProviderPreset.CUSTOM}+
                    if(providers.none {it.id=="server"})listOf(AiProviderSettings(id="server",label="Your local server",location=AiLocation.SERVER))else emptyList()
                slots.forEach {slot->
                    Row(Modifier.fillMaxWidth().testTag("AI provider slot ${slot.id}"),verticalAlignment=Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).selectable(selected==slot.id&&slot.enabled,role=Role.RadioButton,onClick={if(slot.enabled){manager.selectProvider(kind,slot.id);revision++}else editor=slot}).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected==slot.id&&slot.enabled,onClick=null)
                            Column(Modifier.padding(start=10.dp)) {
                                Text(slot.displayName,fontSize=14.sp,fontWeight=FontWeight.Medium)
                                Text(if(slot.enabled)slot.model else "Connect your model",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
                            }
                        }
                        IconButton(onClick={editor=slot},modifier=Modifier.testTag("Edit AI provider ${slot.id}")){Icon(Icons.Rounded.Tune,"Configure ${slot.displayName}",Modifier.size(20.dp))}
                    }
                }
                OutlinedButton(onClick={editor=AiProviderSettings(id=if(providers.none {it.id=="custom"})"custom"else "custom-"+java.util.UUID.randomUUID().toString())},modifier=Modifier.testTag("Configure AI provider")){Icon(Icons.Rounded.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Other provider")}
                if(providers.any {it.id==selected&&it.enabled})TextButton(onClick={work {error=manager.testProvider(kind)}},modifier=Modifier.testTag("Test AI provider")){Text("Test selected provider")}
                Text("Connect with your own API key. Consumer chat subscriptions may have separate API access and billing. Nothing is sent by opening or saving settings; a connection test can incur a small provider charge.",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(kind==AiKind.EMBEDDING)Text("Text providers without a documented embedding endpoint are shown in the Text tab. You can pair cloud text with local embeddings.",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}}
            item {ContextSettingsContent(repo)}
            if(state.localBytes>0)item {
                TextButton(onClick={work {manager.remove()}},enabled=!state.working,modifier=Modifier.testTag("Remove all local AI models")){Icon(Icons.Rounded.DeleteOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Remove local AI · ${aiBytes(state.localBytes)}")}
                Text("Switching AI off keeps its downloads. Removing models frees their storage; your saved posts and subscriptions stay safe.",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }else {
            item {Text(state.message,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(state.localBytes>0)item {
                TextButton(onClick={work {manager.remove()}},enabled=!state.working,modifier=Modifier.testTag("Remove all local AI models")){Icon(Icons.Rounded.DeleteOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Remove local AI · ${aiBytes(state.localBytes)}")}
                Text("Downloaded models stay here while AI is off. You can delete them without enabling AI.",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let {message->item {Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(16.dp)){Text(message,Modifier.fillMaxWidth().padding(16.dp).testTag("AI settings message"),fontSize=13.sp,lineHeight=20.sp)}}}
    }
    editor?.let {chosen->AiProviderDialog(chosen,kind,providers.any {it.id==chosen.id},error,onDismiss={editor=null},onSave={settings,key,remove->work {manager.saveProvider(kind,settings,key,remove);editor=null}},onForget={work {manager.forgetProvider(kind,chosen.id);editor=null}})}
}

@Composable private fun AiProviderDialog(current: AiProviderSettings,kind: AiKind,saved: Boolean,message: String?,onDismiss: ()->Unit,onSave: (AiProviderSettings,String?,Boolean)->Unit,onForget: ()->Unit) {
    var url by remember {mutableStateOf(current.baseUrl)}
    var model by remember {mutableStateOf(current.model)}
    var label by remember {mutableStateOf(current.label)}
    var protocol by remember {mutableStateOf(current.protocol)}
    var workspace by remember {mutableStateOf(current.workspaceId)}
    var key by remember {mutableStateOf("")}
    var removeKey by remember {mutableStateOf(false)}
    var server by remember {mutableStateOf(current.location==AiLocation.SERVER)}
    var enabled by remember {mutableStateOf(current.enabled)}
    val custom=current.preset==AiProviderPreset.CUSTOM
    AlertDialog(onDismissRequest=onDismiss,title={Text(current.displayName)},text={
        SensitiveInteraction()
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Your ${if(kind==AiKind.TEXT)"text"else "embedding"} model",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(custom) {
                OutlinedTextField(label,{label=it},label={Text("Provider name")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("AI provider label"))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(server,onClick={server=true},label={Text("Your server")},modifier=Modifier.testTag("AI provider server"))
                    FilterChip(!server,onClick={server=false},label={Text("Cloud")},modifier=Modifier.testTag("AI provider cloud"))
                }
                Text("API format",fontSize=12.sp)
                AiProtocol.entries.filter {kind==AiKind.TEXT||it!=AiProtocol.ANTHROPIC}.forEach {format->
                    Row(Modifier.fillMaxWidth().selectable(protocol==format,role=Role.RadioButton,onClick={protocol=format}).testTag("AI protocol ${format.name}"),verticalAlignment=Alignment.CenterVertically){RadioButton(protocol==format,onClick=null);Text(format.label,Modifier.padding(start=8.dp),fontSize=13.sp)}
                }
            }
            OutlinedTextField(url,{url=it},readOnly=!custom,label={Text("API base URL")},placeholder={Text(if(server)"http://192.168.1.10:1234/v1"else "https://provider.example/v1")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("AI provider URL"))
            OutlinedTextField(model,{model=it},label={Text("Model ID")},supportingText={Text("Choose a model available to your account.")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("AI provider model name"))
            OutlinedTextField(key,{key=it;removeKey=false},label={Text(if(current.hasKey)"Replace saved API key (optional)"else "API key (if required)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth().rejectObscuredTouches().testTag("AI provider API key"))
            if(current.hasKey)TextButton(onClick={removeKey=true;key=""}){Text(if(removeKey)"Saved key will be removed"else "Remove saved key")}
            if(protocol==AiProtocol.ANTHROPIC)OutlinedTextField(workspace,{workspace=it},label={Text("Workspace ID (optional)")},supportingText={Text("For Anthropic keys that require a workspace.")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("AI provider workspace"))
            Row(Modifier.fillMaxWidth().toggleable(enabled,role=Role.Switch,onValueChange={enabled=it}).testTag("AI provider enabled"),verticalAlignment=Alignment.CenterVertically) {
                Text("Allow requests to this provider",Modifier.weight(1f),fontSize=13.sp);Switch(enabled,onCheckedChange=null)
            }
            Text(if(server)"Private LAN endpoints follow your connection settings. With Tor selected, local destinations are paused."else "Your provider receives submitted text. API access and charges follow your provider account. Requests follow your Tor and privacy settings.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(current.preset==AiProviderPreset.ZAI)Text("Uses Z.ai’s standard API. Coding Plan access has a separate endpoint and plan rules; configure a permitted endpoint under Other provider.",fontSize=12.sp,lineHeight=18.sp)
            if(saved)TextButton(onClick=onForget,modifier=Modifier.testTag("Forget AI provider")){Text("Forget provider & key")}
            message?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp,modifier=Modifier.testTag("AI provider error"))}
        }
    },confirmButton={TextButton(onClick={onSave(current.copy(enabled=enabled,location=if(server)AiLocation.SERVER else AiLocation.CLOUD,baseUrl=url,model=model,label=label,protocol=protocol,workspaceId=if(protocol==AiProtocol.ANTHROPIC)workspace else ""),key.takeIf {it.isNotEmpty()},removeKey)},enabled=url.isNotBlank()&&model.isNotBlank(),modifier=Modifier.rejectObscuredTouches().testTag("Save AI provider")){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable private fun AiCard(content: @Composable ColumnScope.()->Unit) {Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainer){Column(content=content)}}
private fun aiBytes(bytes: Long): String=if(bytes>=1_000_000_000)"%.2f GB".format(bytes/1e9)else "%.1f MB".format(bytes/1e6)
