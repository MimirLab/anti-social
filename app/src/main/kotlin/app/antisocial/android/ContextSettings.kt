/* AntiSocial, GPL-3.0-or-later. Local reader choices; Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.ContextMediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Fits as one item in the existing AI settings list. Optional detail stays
 * folded until chosen; preparing a brief never gives a model tools to operate. */
@Composable internal fun ContextSettingsContent(repo: Repository) {
    val ai by repo.ai.state.collectAsState()
    if (!ai.enabled) return
    val reader = repo.contextReader
    val options by reader.preferences.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var mediaExpanded by rememberSaveable { mutableStateOf(false) }
    var languagesExpanded by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun update(value: ContextOptions) { reader.preferences.update(value); reader.changed() }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("Context settings")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("Context settings expand"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) { Text("Context", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(if (options.ahead || options.saved) "Reading & quiet updates" else "On demand · your sources, made readable", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse Context settings" else "Expand Context settings")
            }
            if (expanded) {
                Text("Bring together a post, useful public replies and readable linked material. Claims keep their sources; routing and reading tools follow the app’s rules.", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!repo.ai.contextAvailable()) Text("Choose a ready text model to prepare Context. These reading preferences can be set beforehand.", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context text capability"))
                ContextSettingSwitch("Read ahead", "Prepare Context for nearby posts as you browse.", options.ahead, "Context read ahead") { update(options.copy(ahead = it)) }
                if (options.ahead) {
                    Text("How far ahead", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (count in listOf(1, 3, 10)) FilterChip(options.lookahead == count, onClick = { update(options.copy(lookahead = count)) }, label = { Text("$count ${if (count == 1) "post" else "posts"}") }, modifier = Modifier.testTag("Context lookahead $count"))
                    }
                }
                ContextSettingSwitch("Keep saved Context current", "Prepare Context for saved posts and check for useful additions, even if you have not opened it yet. Captured snapshots stay as saved.", options.saved, "Context keep saved") { update(options.copy(saved = it)) }
                if (options.ahead || options.saved) {
                    ContextSettingMenu("Check for updates", options.refreshMinutes, listOf(15 to "Every 15 minutes", 60 to "Hourly", 360 to "Every 6 hours", 1440 to "Daily"), "Context refresh menu") { update(options.copy(refreshMinutes = it)) }
                    Text("Quiet sources are checked less often. Android may defer background work to respect battery and foreground reading.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable {languagesExpanded=!languagesExpanded}.testTag("Context languages expand"),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Translate,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(end=12.dp))
                    Column(Modifier.weight(1f)) {Text("Languages",fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text("English Context · original sources preserved",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))}
                    Icon(if(languagesExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(languagesExpanded)"Collapse languages"else "Expand languages")
                }
                if(languagesExpanded)ContextLanguageSettings(repo)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { mediaExpanded = !mediaExpanded }.testTag("Context media expand"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Images & speech", fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text("Optional tools that run on your device", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    Icon(if (mediaExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (mediaExpanded) "Collapse image and speech tools" else "Expand image and speech tools")
                }
                if (mediaExpanded) ContextMediaSettings(repo)
                ContextSettingMenu("Automatic media reading", options.mediaPolicy, listOf("manual" to "Only when I request it", "charging" to "While charging", "browsing" to "While browsing"), "Context media policy menu") { update(options.copy(mediaPolicy = it)) }
                Text(when (options.mediaPolicy) { "manual" -> "Background Context leaves image and speech reading for your explicit requests."; "charging" -> "Automatic image and speech reading waits until the device is charging."; else -> "Allow installed media tools while you browse, subject to battery and connection limits." }, fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ContextSettingSwitch("Avoid metered media reads", "Automatic media waits on connections marked as metered.", options.unmetered, "Context unmetered media") { update(options.copy(unmetered = it)) }
                Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.testTag("Context advanced settings"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Advanced reading settings", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (advanced) "Hide advanced settings" else "Show advanced settings", Modifier.size(20.dp))
                }
                if (advanced) {
                    ContextSettingMenu("Pause automatic work below", options.minimumBattery, (1..8).map { it * 10 to "${it * 10}% battery" }, "Context battery menu") { update(options.copy(minimumBattery = it)) }
                    ContextSettingMenu("Automatic work session", options.sliceSeconds, listOf(30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes", 600 to "10 minutes"), "Context slice menu") { update(options.copy(sliceSeconds = it)) }
                    Text("Work saves its place between short sessions. Battery saver, heat and the reader’s current task take priority.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ContextSettingMenu("Linked pages from replies", options.commentLinks, listOf(0 to "Off", 8 to "Up to 8 pages", 24 to "Up to 24 pages", 64 to "Up to 64 pages", 128 to "Up to 128 pages"), "Context comment links menu") { update(options.copy(commentLinks = it)) }
                    Text("Read a bounded set of links directly shared in replies. Links on those pages do not start another round of browsing.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    ContextWindowSettings(repo, options, ::update)
                }
            }
        }
    }
}

@Composable private fun ContextLanguageSettings(repo:Repository) {
    val tools=repo.languages;val options by tools.options.collectAsState();val state by tools.state.collectAsState()
    val scope=rememberCoroutineScope();var language by rememberSaveable {mutableStateOf("es")};var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){tools.refresh()}
    val available=remember {tools.available};val installed=language in state.installed;val downloading=state.downloading!=null
    Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.testTag("Context language settings")) {
        Text("Translate source text on your device before Context or its embedding lookups read it. Posts, replies, linked pages and available captions keep their original quotations.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ContextSettingSwitch("Translate sources","Language detection and translation stay on this device. If a translation is unavailable, that passage waits.",options.enabled,"Context translation enabled"){tools.update(options.copy(enabled=it))}
        if(options.enabled) {
            ContextSettingSwitch("Download languages as needed","Automatic downloads use unmetered connections, usually Wi-Fi. No source text is uploaded.",options.automatic,"Context automatic languages"){tools.update(options.copy(automatic=it))}
            if(options.automatic)ContextSettingMenu("Automatic language storage",options.automaticMb,listOf(64 to "64 MB",160 to "160 MB",320 to "320 MB",512 to "512 MB"),"Context language storage menu"){tools.update(options.copy(automaticMb=it))}
            Text("Unused automatic language packs expire. Languages you explicitly download stay until you remove them.",fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ContextSettingMenu("Offline language",language,available,"Context language menu",enabled=!downloading){language=it}
        val bytes=remember(language){tools.bytes(language)}
        if(installed) {
            Text("${tools.label(language)} downloaded · ${contextToolBytes(bytes)}${if(language in state.kept)" · kept offline"else " · automatic"}",fontSize=12.sp,modifier=Modifier.testTag("Context language installed"))
            if(language !in state.kept)TextButton(onClick={tools.download(language)},enabled=!downloading,modifier=Modifier.testTag("Context keep language")){Text("Keep offline")}
            TextButton(onClick={scope.launch {try {error=null;tools.remove(language)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){error="The language could not be removed. Try again shortly."}}},enabled=!downloading,modifier=Modifier.testTag("Context remove language")){Text("Remove language")}
        } else OutlinedButton(onClick={try {error=null;tools.download(language)}catch(_:Exception){error="The language download could not start."}},enabled=!downloading,modifier=Modifier.fillMaxWidth().testTag("Context download language")) {
            Icon(Icons.Rounded.Download,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("Download · ${contextToolBytes(bytes)}")
        }
        if(!installed&&language in state.downloads)TextButton(onClick={scope.launch {tools.remove(language)}},enabled=!downloading,modifier=Modifier.testTag("Context remove partial language")){Text("Remove previous or partial download")}
        if(downloading) {
            LinearProgressIndicator(progress={if(state.total>0)(state.downloaded.toFloat()/state.total).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth().testTag("Context language progress"))
            Text("${tools.label(state.downloading!!)} · ${contextToolBytes(state.downloaded)} of ${contextToolBytes(state.total)}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=tools::cancelDownload,modifier=Modifier.testTag("Context pause language")){Text("Pause download")}
        }
        Text("Language files: ${contextToolBytes(state.localBytes)}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        (error ?: state.message)?.let {Text(it,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.testTag("Context language status"))}
        Text("Machine translation can miss nuances. Original evidence remains available; uncertain languages are not silently marked as reviewed.",fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ContextWindowSettings(repo: Repository, options: ContextOptions, update: (ContextOptions) -> Unit) {
    val capability by repo.contextReader.capability.collectAsState()
    val plan by repo.contextReader.processingPlan.collectAsState()
    var window by rememberSaveable(options.remoteContextTokens) { mutableStateOf(if (options.remoteContextTokens == 0) "" else options.remoteContextTokens.toString()) }
    val parsed = window.toIntOrNull()
    val valid = parsed == 0 || parsed != null && parsed in 1024..1_048_576
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("Context window settings")) {
        Text("Adapt to your model", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("Automatic adjusts the work to your model’s reported size and available reading space. Phone models get focused tasks; larger models can compare sources together. The same evidence stays available in every mode.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ContextSettingMenu("Remote processing", options.processingStyle, listOf("auto" to "Automatic", "focused" to "Focused tasks", "balanced" to "Balanced reading", "extended" to "Broader comparisons"), "Context processing menu") { update(options.copy(processingStyle = it)) }
        Text("Use a remote style when your server does not report model size. This never switches providers. On-device work remains focused.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan?.let { Text("Last reading · ${it.reason}", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context processing reason")) }
        capability?.parameterCount?.let { Text("Reported model size: %.1fB parameters".format(it / 1_000_000_000.0), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider()
        Text("Remote reading window", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("Automatic uses the selected server’s reported active window, with a conservative fallback. Set a limit only when you know the server’s configured capacity. This does not change the on-device model.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = window, onValueChange = { if (it.length <= 7 && it.all(Char::isDigit)) window = it }, label = { Text("Token limit") }, placeholder = { Text("Automatic") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = window.isNotEmpty() && !valid, supportingText = { Text("1,024–1,048,576 tokens, or use Automatic") }, modifier = Modifier.fillMaxWidth().testTag("Context remote window input"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { parsed?.let { update(options.copy(remoteContextTokens = it)) } }, enabled = valid && parsed != options.remoteContextTokens, modifier = Modifier.testTag("Context apply remote window")) { Text("Apply limit") }
            TextButton(onClick = { window = ""; update(options.copy(remoteContextTokens = 0)) }, enabled = options.remoteContextTokens != 0 || window.isNotEmpty(), modifier = Modifier.testTag("Context automatic window")) { Text("Use automatic") }
        }
        val reported = capability
        Text(reported?.activeContextTokens?.let { "Observed active window: %,d tokens".format(it) } ?: "Active window not yet reported", fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.testTag("Context observed window"))
        reported?.let {
            Text("Working limit: %,d tokens".format(it.contextTokens), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context working window"))
            if (it.activeContextTokens == null && it.advertisedContextTokens != null) Text("Advertised model limit: %,d tokens · active window unknown".format(it.advertisedContextTokens), fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(it.source, fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context window source"))
        }
        Text("Opening these settings makes no capability request. Server information updates when Context prepares a reading.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ContextMediaSettings(repo: Repository) {
    val tools = repo.mediaTools
    val state by tools.state.collectAsState()
    val capabilities = remember(state) { tools.capabilities() }
    val scope = rememberCoroutineScope()
    var language by rememberSaveable { mutableStateOf("eng") }
    var message by remember { mutableStateOf<String?>(null) }
    fun work(action: suspend () -> Unit) { scope.launch { try { message = null; action() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { message = "This optional tool could not be updated. Your text Context remains available." } } }
    fun download(kind: ContextMediaKind) { try { message = null; tools.download(kind, if (kind == ContextMediaKind.OCR) language else "eng") } catch (_: Exception) { message = "The download could not start. Try again when the connection is available." } }
    LaunchedEffect(Unit) { tools.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("Context media settings")) {
        Text("Read text in images", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Include readable words from images and PDF pages. Download only the languages you use.", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ContextSettingMenu("Image text language", language, tools.availableOcrLanguages, "Context OCR language menu", enabled = !state.working) { language = it }
        val ocrInstalled = language in state.languages
        val ocrBytes = remember(language, state) { runCatching { tools.downloadBytes(ContextMediaKind.OCR, language) }.getOrNull() }
        val speechBytes = remember(state) { runCatching { tools.downloadBytes(ContextMediaKind.SPEECH) }.getOrNull() }
        val ocrDownload = remember(language, state) { runCatching { tools.hasDownload(ContextMediaKind.OCR, language) }.getOrDefault(false) }
        val speechDownload = remember(state) { runCatching { tools.hasDownload(ContextMediaKind.SPEECH) }.getOrDefault(false) }
        if (ocrInstalled) {
            ContextSettingSwitch("Use image text", "The selected language is downloaded.", state.ocrEnabled, "Context OCR enabled", enabled = capabilities.ocrAllowed && !state.working) { work { tools.activate(ContextMediaKind.OCR, it, language) } }
        } else if (capabilities.ocrAllowed && ocrBytes != null) OutlinedButton(onClick = { download(ContextMediaKind.OCR) }, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("Context download OCR")) { Icon(Icons.Rounded.Download, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Download & enable · ${contextToolBytes(ocrBytes)}") }
        else Text(capabilities.reason ?: "Image text setup is unavailable. Text Context remains available.", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context OCR unavailable"))
        if (ocrDownload) TextButton(onClick = { work { tools.remove(ContextMediaKind.OCR, language) } }, enabled = !state.working, modifier = Modifier.testTag("Context remove OCR")) { Text(if (ocrInstalled) "Remove this language" else "Remove partial language download") }
        HorizontalDivider()
        Text("Understand speech", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Include available speech from audio and videos. Transcription runs locally and keeps time references for review.", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.speechInstalled) {
            ContextSettingSwitch("Use speech reading", "Multilingual speech model downloaded.", state.speechEnabled, "Context SPEECH enabled", enabled = capabilities.speechAllowed && !state.working) { work { tools.activate(ContextMediaKind.SPEECH, it) } }
        } else if (capabilities.speechAllowed && speechBytes != null) OutlinedButton(onClick = { download(ContextMediaKind.SPEECH) }, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("Context download SPEECH")) { Icon(Icons.Rounded.Download, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Download & enable · ${contextToolBytes(speechBytes)}") }
        else Text(capabilities.reason ?: "Speech setup is unavailable. Text Context remains available.", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context SPEECH unavailable"))
        if (speechDownload) TextButton(onClick = { work { tools.remove(ContextMediaKind.SPEECH) } }, enabled = !state.working, modifier = Modifier.testTag("Context remove SPEECH")) { Text(if (state.speechInstalled) "Remove speech model" else "Remove partial speech download") }
        if (state.working) {
            state.progress?.let { progress ->
                LinearProgressIndicator(progress = { if (progress.totalBytes > 0) (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().testTag("Context media progress"))
                Text(if (progress.verifying) "Checking the downloaded tool…" else "${contextToolBytes(progress.downloadedBytes)} of ${contextToolBytes(progress.totalBytes)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = tools::cancelDownload, modifier = Modifier.testTag("Context pause media download")) { Text("Pause download") }
        }
        val status = when {
            state.working || state.message == "Optional media tools · off" -> null
            state.message.contains("paused", true) -> "Download paused. Tap Download to continue."
            state.message.contains("ready", true) -> "The downloaded tool is ready on this device."
            state.message.contains("storage", true) || state.message.contains("space", true) -> "Free some storage, then retry the download."
            state.message.contains("integrity", true) || state.message.contains("verif", true) -> "The download could not be verified. Remove it or try again."
            state.message.contains("Could not", true) || state.message.contains("failed", true) -> "Optional media setup did not finish. Your text Context remains available."
            else -> null
        }
        status?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context media status")) }
        Text("These tools are optional. Turning one off keeps its download; Remove frees its storage. Existing saved text and Context stay intact.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context media message")) }
    }
}

@Composable private fun ContextSettingSwitch(title: String, description: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(description, fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable private fun <T> ContextSettingMenu(label: String, current: T, choices: List<Pair<T, String>>, tag: String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { opened = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text(choices.firstOrNull { it.first == current }?.second ?: current.toString(), Modifier.weight(1f), fontSize = 13.sp)
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(17.dp))
            }
            DropdownMenu(expanded = opened, onDismissRequest = { opened = false }) {
                choices.forEach { (value, title) -> DropdownMenuItem(text = { Text(title, fontSize = 13.sp) }, onClick = { opened = false; onSelect(value) }, leadingIcon = if (value == current) ({ Icon(Icons.Rounded.Check, null, Modifier.size(17.dp)) }) else null) }
            }
        }
    }
}

private fun contextToolBytes(bytes: Long): String = if (bytes >= 1_000_000_000) "%.2f GB".format(bytes / 1e9) else "%.1f MB".format(bytes / 1e6)
