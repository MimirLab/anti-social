/* AntiSocial, GPL-3.0-or-later. Native, accountless article and document reading. */
package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ArticleReaderSheet(reader:ArticleReaderController,onOpenLink:(String)->Unit,onBrowser:(String)->Unit) {
    val state by reader.state.collectAsState()
    val shown=state ?: return
    val clipboard=LocalClipboardManager.current
    var links by rememberSaveable(shown.url){mutableStateOf(false)}
    var sources by rememberSaveable(shown.url){mutableStateOf(false)}
    var records by rememberSaveable(shown.url){mutableStateOf(false)}
    if(records)SourceDetailsSheet(reader.repo,listOf(shown.access?.originalUrl ?: shown.url),onOpen=onOpenLink,onDismiss={records=false})
    val document=shown.document
    ModalBottomSheet(onDismissRequest=reader::close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Article reader")) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.94f).testTag("Article reading list"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Reader",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                        IconButton(onClick={clipboard.setText(AnnotatedString(shown.access?.originalUrl ?: shown.url))},modifier=Modifier.testTag("Copy article link")){Icon(Icons.Rounded.Link,"Copy original link")}
                        IconButton(onClick=reader::close){Icon(Icons.Rounded.Close,"Close article")}
                    }
                    Text(document?.title ?: if(shown.pdf!=null)"Document"else "Opening article…",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("Article title"))
                    if(document!=null)Text(listOfNotNull(document.author,document.siteName).filter {it.isNotBlank()}.distinct().joinToString(" · "),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    shown.access?.let {access->AssistChip(onClick={sources=!sources},label={Text(articleAccessLabel(access))},leadingIcon={Icon(if(access.archivedAt!=null)Icons.Rounded.History else Icons.Rounded.Description,null,Modifier.size(16.dp))},modifier=Modifier.testTag("Article source"))}
                    if(sources)shown.access?.let {access->
                        SelectionContainer {Text(listOfNotNull("Original: ${access.originalUrl}","Read from: ${access.retrievedUrl}",
                            access.version?.let {"Edition: "+articleVersion(it)},access.license?.let {"License: $it"},
                            "The source marks this paper as retracted.".takeIf {access.retracted}).joinToString("\n\n"),fontSize=12.sp,lineHeight=19.sp)}
                        TextButton(onClick={records=true}){Text("Source details")}
                    }
                    if(!shown.loading&&shown.error==null)PaperUpdateNotice(reader.repo,listOf(shown.access?.originalUrl ?: shown.url)){records=true}
                    if(shown.loading)Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("Finding a readable public copy…",fontSize=13.sp)}
                    shown.error?.let {Text(it,style=MaterialTheme.typography.bodyMedium);TextButton(onClick={reader.open(shown.url)}){Text("Try again")}}
                    if(shown.access?.retracted==true)Text("This paper is marked retracted by its public record.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                    if(document?.truncated==true)Text("Only part of this article is available in this copy.",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val blocks=document?.blocks.orEmpty().filterIndexed {index,block->!(index==0&&block.kind==ContextArticleBlockKind.HEADING&&block.text==document?.title)}
            itemsIndexed(blocks){_,block->SelectionContainer {
                Text((if(block.kind==ContextArticleBlockKind.LIST)(block.listMarker ?: "•")+" "else "")+block.text,
                    modifier=Modifier.padding(start=if(block.kind==ContextArticleBlockKind.LIST)(block.listDepth.coerceIn(0,8)*12).dp else 0.dp),
                    fontSize=if(block.kind==ContextArticleBlockKind.HEADING)21.sp else 17.sp,lineHeight=27.sp,
                    fontWeight=if(block.kind==ContextArticleBlockKind.HEADING)FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily=if(block.kind==ContextArticleBlockKind.CODE)FontFamily.Monospace else FontFamily.Default)
            }}
            if(shown.pdf!=null)items(shown.pages){index->Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${index+1} / ${shown.pages}",style=MaterialTheme.typography.labelSmall)
                ArticlePdfPage(shown.pdf,index)
            }}
            val destinations=document?.blocks.orEmpty().flatMap {it.links}.distinct()
            val references=document?.references.orEmpty().take(4)
            if(references.isNotEmpty())item {
                Text("Underlying sources",style=MaterialTheme.typography.titleMedium)
                references.forEach {reference->
                    val generic=reference.label.trim().lowercase() in setOf("doi","here","article","pubmed","crossref","source")||reference.label.all(Char::isDigit)
                    val label=if(generic&&reference.passage.isNotBlank())reference.passage.take(220)else reference.label
                    TextButton(onClick={reader.close();onOpenLink(reference.url)},modifier=Modifier.testTag("Underlying source ${reference.url}")){Column(Modifier.fillMaxWidth()){Text(label,maxLines=2);Text(reference.reason,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                }
            }
            if(destinations.isNotEmpty())item {
                TextButton(onClick={links=!links}){Icon(Icons.Rounded.Link,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Links · ${destinations.size}")}
                if(links)destinations.forEach {url->TextButton(onClick={reader.close();onOpenLink(ArchiveUrls.original(url) ?: url)}){Text(ArchiveUrls.original(url) ?: url,maxLines=2)}}
            }
            item {TextButton(onClick={onBrowser(shown.access?.originalUrl ?: shown.url)}){Icon(Icons.Rounded.OpenInNew,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Open in browser")}}
        }
    }
}
internal fun articleVersion(version:String)=when(version){"publishedVersion"->"Published version";"acceptedVersion"->"Accepted manuscript";"submittedVersion"->"Author manuscript";else->version}
internal fun articleAccessLabel(access:ArticleAccess):String=when {
    access.route=="Existing archive capture"->access.archivedAt?.let {"Archived "+DateTimeFormatter.ofPattern("MM/yy").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(it))} ?: "Archived copy"
    access.version!=null->articleVersion(requireNotNull(access.version))
    access.route=="Original publisher"->"Publisher"
    access.route=="Publisher text edition"->"Text edition"
    else->"Public copy"
}
@Composable private fun ArticlePdfPage(file:File,index:Int) {
    val page by produceState<Pair<ImageBitmap?,Boolean>>(null to false,file.path,index){value=withContext(Dispatchers.IO){runCatching {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {renderer->renderer.openPage(index).use {page->
            val scale=minOf(2.0,1000.0/page.width,kotlin.math.sqrt(2_000_000.0/(page.width.toDouble()*page.height)))
            val bitmap=Bitmap.createBitmap(maxOf(1,(page.width*scale).toInt()),maxOf(1,(page.height*scale).toInt()),Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap.asImageBitmap()
        }}}
    }.fold({it to false},{null to true})}}
    val image=page.first
    if(page.second)Text("This page could not be displayed.",style=MaterialTheme.typography.bodyMedium)
    else if(image==null)LinearProgressIndicator(Modifier.fillMaxWidth())else Image(image,"Document page ${index+1}",Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height))
}
