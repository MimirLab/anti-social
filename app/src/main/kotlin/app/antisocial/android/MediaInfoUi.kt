/* anti social, GPL-3.0-or-later. Deterministic findings, streamed into a native drawer. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*

@Composable internal fun MediaInfoAction(repo:Repository,posts:List<Post>,localOnly:Boolean=false) {
    val entries=rememberAiProvenance(repo,posts,localOnly)
    var open by remember(posts.map {repo.key(it)}){mutableStateOf(false)}
    if(entries.isNotEmpty()||posts.any {it.media.isNotEmpty()})IconButton(onClick={open=true},modifier=Modifier.size(34.dp).testTag("AI provenance button")) {
        val flagged=entries.any {it.aiDeclared||it.metadataRemoved||it.metadataGap}
        Icon(if(flagged)Icons.Rounded.WarningAmber else Icons.Rounded.Info,
            if(entries.any {it.aiDeclared})"Post information · AI use recorded"else if(flagged)"Post information · metadata finding"else "Post information",
            Modifier.size(19.dp),tint=if(flagged)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(open)MediaInfoSheet(repo,posts,localOnly){open=false}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MediaInfoSheet(repo:Repository,posts:List<Post>,localOnly:Boolean=false,onDismiss:()->Unit) {
    val assets=remember(posts){posts.take(CrossPosts.MAX_EDITIONS).flatMap {post->post.media.take(16).flatMapIndexed {i,m->
        listOf(MediaInfoAsset(m.url,post.contentSourceUrl,"${SourcePresentation.label(post)} · ${if(m.type=="image")"Image"else "Video"} ${i+1}",m.type=="image"))+
            listOfNotNull(m.preview?.takeIf {it!=m.url}?.let {MediaInfoAsset(it,post.contentSourceUrl,"${SourcePresentation.label(post)} · Video ${i+1} preview")})
    }}.distinctBy {it.url}}
    var progress by remember(assets,localOnly){mutableStateOf(MediaInfoProgress(total=assets.size,working=true))}
    LaunchedEffect(repo,assets,localOnly){repo.mediaInfo.inspect(assets,localOnly){progress=it}}
    val entries=rememberAiProvenance(repo,posts,localOnly,progress.results)
    val recipeResults=remember(posts,progress.results) {buildMap<String,MediaInfoResult> {
        posts.take(CrossPosts.MAX_EDITIONS).flatMap {it.media.take(16)}.forEach {m->
            AiMetadataValues.captured(m.aiMetadata)?.takeIf {it.recipes.isNotEmpty()}?.let {put(m.url,MediaInfoResult(it,it.inspectedAt,detail="Saved generation record; signature authority requires available local bytes."))}
        }
        putAll(progress.results)
    }}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp).padding(bottom=30.dp)
            .testTag("Post information drawer"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Behind this post",fontSize=23.sp,fontWeight=FontWeight.SemiBold)
            Text(if(localOnly)"Inspecting your local copy."else "Findings from the media and its available credentials.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(progress.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("Info checking"))
                Text("Checking media · ${progress.checked} of ${progress.total}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val useful=entries.filter {(it.aiDeclared||it.metadataRemoved||it.metadataGap||it.inspectionStatus==null)&&
                (recipeResults.isEmpty()||it.evidence !in setOf("Embedded generation recipe","NovelAI signed image and generation record"))}
            useful.forEach {entry->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${entry.source} · ${entry.asset}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.headline ?: "Creation record available",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                if(entry.tools.isNotEmpty())Text(entry.tools.joinToString(" · "),fontSize=13.sp)
                entry.integrity?.let {Text(it,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }}}
            recipeResults.entries.groupBy {it.value.metadata.sha256 ?: it.key}.values.forEach {copies->
                val (url,result)=copies.first()
                val recipes=result.metadata.recipes
                if(recipes.isNotEmpty()) {
                    Text(copies.mapNotNull {(u,_)->assets.firstOrNull {it.url==u}?.label}.distinct().joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    recipes.forEach {recipe->RecipeDetails(recipe)}
                }
                result.detail?.let {Text(it,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            if(!progress.working&&useful.isEmpty()&&progress.results.values.all {it.metadata.recipes.isEmpty()}) {
                Text(if(progress.results.isEmpty())"No media could be inspected from the available copy."else "No supported AI declaration or signed creation record was found in the inspected copies.",fontSize=15.sp,lineHeight=22.sp)
            }
            progress.note?.let {Text(it,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            var technical by remember{mutableStateOf(false)}
            if(entries.isNotEmpty()) {
                TextButton(onClick={technical=!technical},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("Info inspection details")) {
                    Text(if(technical)"Hide inspection details"else "Inspection details");Icon(Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))
                }
                if(technical)AiProvenanceDetails(entries)
            }
        }
    }
}

@Composable private fun RecipeDetails(recipe:GenerationRecipe) {
    var expanded by remember(recipe){mutableStateOf(false)}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(if(recipe.signature==RecipeSignature.VALID)"NovelAI signature verified"else "${recipe.generator} recipe embedded",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
        Text(when(recipe.signature) {
            RecipeSignature.VALID->"The image samples and signed generation record match NovelAI's public verification key."
            RecipeSignature.INVALID->"A NovelAI signature was supplied, but it did not verify for this copy."
            RecipeSignature.UNCHECKED->"The file supplies a generation record; its signature has not been verified."
            RecipeSignature.UNAVAILABLE->"Signature verification is unavailable for this copy."
            RecipeSignature.UNSIGNED->"The file includes a generative workflow. This record is unsigned."
        },fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        recipe.model?.let {Text("Recorded model: $it",fontSize=12.sp)}
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("Generation recipe details")) {Text(if(expanded)"Hide recipe"else "View recipe",fontSize=12.sp)}
        if(expanded) {
            recipe.prompt?.let {Text("Prompt",fontSize=11.sp,fontWeight=FontWeight.SemiBold);Text(it,fontSize=13.sp,lineHeight=19.sp)}
            recipe.negativePrompt?.let {Text("Negative prompt",fontSize=11.sp,fontWeight=FontWeight.SemiBold);Text(it,fontSize=13.sp,lineHeight=19.sp)}
            recipe.parameters.forEach {(name,value)->Text("$name: $value",fontSize=12.sp)}
            Text(recipe.carrier,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }}
}
