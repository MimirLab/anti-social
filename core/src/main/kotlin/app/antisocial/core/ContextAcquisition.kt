/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor.
 * Article isolation uses Readability4J (dankito / Mozilla Readability, Apache-2.0).
 * No page JavaScript, subresource loader or browser session is involved. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.dankito.readability4j.Readability4J
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

enum class ContextEvidenceFailure { ABSENT, UNAVAILABLE, BLOCKED, LOGIN_REQUIRED, CHALLENGE, UNSUPPORTED }
enum class ContextArticleBlockKind { HEADING, PARAGRAPH, LIST, QUOTE, CODE, CAPTION }
data class ContextArticleBlock(val text: String,val kind: ContextArticleBlockKind=ContextArticleBlockKind.PARAGRAPH,val headingLevel: Int=0,val links: List<String> = emptyList(),val listMarker:String?=null,val listDepth:Int=0)
data class ContextArticleEvidence(val originalUrl: String,val finalUrl: String,val title: String,val author: String?,val siteName: String?,val publishedAt: Long?,val blocks: List<ContextArticleBlock>,val fetchedAt: Long,val truncated: Boolean=false,val etag: String?=null,val lastModified: String?=null,val access:ArticleAccess?=null,val references:List<SignalReference>?=null,val publicationDoi:String?=null) {
    val text: String get()=blocks.joinToString("\n\n"){it.text}
    /** Small Markdown subset, from inert extracted blocks. Destinations remain
     * typed source links; no image/widget or HTML is rendered by this view. */
    val readingText:String get()=blocks.filterIndexed {index,block->!(index==0&&block.kind==ContextArticleBlockKind.HEADING&&block.text.equals(title,true))}.joinToString("\n\n") {block->when(block.kind) {
        ContextArticleBlockKind.HEADING->"#".repeat(block.headingLevel.coerceIn(1,6))+" "+block.text
        ContextArticleBlockKind.LIST->"  ".repeat(block.listDepth.coerceIn(0,8))+(block.listMarker ?: "-")+" "+block.text
        ContextArticleBlockKind.QUOTE->"> "+block.text.replace("\n","\n> ")
        ContextArticleBlockKind.CODE->block.text.lineSequence().joinToString("\n"){"    "+it}
        else->block.text
    }}
}
data class ContextArticleValidators(val etag: String?=null,val lastModified: String?=null) {
    init {require(etag==null||etag.length<=2048&&etag.all {it.code in 32..126});require(lastModified==null||lastModified.length<=256&&lastModified.all {it.code in 32..126})}
}
sealed interface ContextArticleFetch {
    data class Ready(val document: ContextArticleEvidence): ContextArticleFetch
    data class NotModified(val finalUrl: String,val validators: ContextArticleValidators,val checkedAt: Long): ContextArticleFetch
    /** Reachable public PDF candidate; the caller must download and validate
     * actual pages before claiming that its content was read. */
    data class PublicPdf(val originalUrl:String,val url:String,val access:ArticleAccess):ContextArticleFetch
}
data class ContextCaptionCue(val startMs: Long,val endMs: Long?,val text: String)
data class ContextAudioDescriptor(val videoId: String,val url: String,val sourceUrl: String,val mimeType: String?,val durationMs: Long?,val source: Source,val title: String="")
sealed interface ContextCaptionResult {
    data class Available(val cues: List<ContextCaptionCue>,val language: String,val automatic: Boolean,val sourceUrl: String,val source: Source,val videoUrl: String,val title: String="",val durationMs: Long?=null,val truncated: Boolean=false): ContextCaptionResult {
        val text: String get()=cues.joinToString("\n"){it.text}
    }
    data class Unavailable(val reason: ContextEvidenceFailure,val message: String,val audio: ContextAudioDescriptor?=null): ContextCaptionResult
}
internal data class ContextCaptionTrack(val url: String,val language: String,val automatic: Boolean,val label: String="",val mimeType: String?=null)
internal data class ContextVideoMetadata(val videoId: String,val title: String,val tracks: List<ContextCaptionTrack>,val captionsDeclared: Boolean,val audio: ContextAudioDescriptor?,val sourceUrl: String,val source: Source,val durationMs: Long?=null)

/** Requested evidence only. It shares the existing source candidates/backoff,
 * never starts a heartbeat, follows article links, or claims media it has not read. */
class ContextEvidenceReader(private val gateway: NetworkGateway,private val routes: DarwinRoutes,private val pool: SourcePool=SourcePool(gateway,routes),private val clock: ()->Long=System::currentTimeMillis,extract:suspend (Payload,String,Long)->ContextArticleEvidence={payload,url,at->ContextArticles.extract(payload,url,at,readerChecks=true)}) {
    val articles=PublicArticleResolver(gateway,clock,extract)
    private data class Cached(val at: Long,val value: ContextVideoMetadata)
    private val metadata=ConcurrentHashMap<String,Cached>()
    private data class ArticleMemo(val at:Long,val value:ContextArticleEvidence)
    private data class ArticleMemoKey(val session:NetworkSession,val originals:Boolean,val purpose:FetchPurpose,val url:String)
    private val articleMemo=linkedMapOf<ArticleMemoKey,ArticleMemo>()
    private var articleGeneration=0L
    @Synchronized fun clearArticleMemory(){articleGeneration++;articleMemo.clear();articles.clearValidationMemory()}
    @Synchronized private fun articleGeneration()=articleGeneration
    @Synchronized private fun recentArticle(key:ArticleMemoKey):ContextArticleEvidence? {
        articleMemo.entries.removeAll {clock()-it.value.at !in 0..15*60_000L}
        return articleMemo[key]?.value
    }
    @Synchronized private fun rememberArticle(key:ArticleMemoKey,value:ContextArticleEvidence,generation:Long) {
        if(articleGeneration!=generation||gateway.currentSession()!==key.session)return
        articleMemo.remove(key);articleMemo[key]=ArticleMemo(clock(),value)
        while(articleMemo.size>8||articleMemo.values.sumOf {it.value.text.length.toLong()}>1024*1024)articleMemo.remove(articleMemo.keys.first())
    }
    suspend fun article(url: String,sourceUrl: String): ContextArticleEvidence=when(val result=articleConditional(url,sourceUrl,ContextArticleValidators())) {
        is ContextArticleFetch.Ready->result.document
        is ContextArticleFetch.NotModified->throw SourceFailure(HealthStatus.EMPTY,"The article source returned no new document")
        is ContextArticleFetch.PublicPdf->throw SourceFailure(HealthStatus.UNSUPPORTED,"The available public copy is a PDF")
    }
    suspend fun articleConditional(url:String,sourceUrl:String,validators:ContextArticleValidators,knownRetrieval:String?=null,purpose:FetchPurpose=FetchPurpose.SUPPLEMENTAL):ContextArticleFetch=
        withContext(Dispatchers.IO){
            val session=gateway.currentSession();val generation=articleGeneration();val key=ArticleMemoKey(session,gateway.originalFallback,purpose,url)
            if(validators.etag==null&&validators.lastModified==null&&knownRetrieval==null)recentArticle(key)?.let {return@withContext ContextArticleFetch.Ready(it)}
            articles.conditional(url,sourceUrl,validators,knownRetrieval,purpose).also {result->
                if(result is ContextArticleFetch.Ready)rememberArticle(key,result.document,generation)
            }
        }
    suspend fun captions(videoUrl: String,originalFallback: Boolean,preferredLanguages: List<String> = listOf("en"),allowAudio: Boolean=false): ContextCaptionResult {
        val videoId=YouTubeUrls.videoId(videoUrl) ?: return ContextCaptionResult.Unavailable(ContextEvidenceFailure.UNSUPPORTED,"This video has no supported public caption route")
        val allowed=originalFallback&&gateway.originalFallback
        val mode=gateway.currentPolicy()
        var sawAbsent=false;var sawTrack=false;var last: Exception?=null;var cachedAudio: ContextAudioDescriptor?=null
        for(source in candidates(videoId,allowed)) {
            currentCoroutineContext().ensureActive()
            try {
                val data=videoMetadata(source,videoId)
                if(data.audio!=null&&endpointAllowed(data.audio.url,source,allowed))cachedAudio=cachedAudio ?: data.audio
                if(data.captionsDeclared&&data.tracks.isEmpty())sawAbsent=true
                val tracks=data.tracks.sortedWith(compareBy<ContextCaptionTrack> {languageRank(it.language,preferredLanguages)}.thenBy {it.automatic}.thenBy {it.label}).take(3)
                for(track in tracks) {
                    sawTrack=true
                    if(!endpointAllowed(track.url,source,allowed)){last=SourceFailure(HealthStatus.UNAVAILABLE,"The caption route needs original-site access");continue}
                    try {
                        val payload=withTimeout(if(mode.tor)30_000L else 12_000L) {
                            gateway.contextText(track.url,YouTubeUrls.canonical(videoId),2L*1024*1024,sameOrigin=source.role!=SourceRole.ORIGINAL&&!allowed)
                        }
                        val parsed=ContextCaptionParser.parse(payload.text,track.mimeType ?: payload.contentType)
                        checkMode(mode)
                        return ContextCaptionResult.Available(parsed.cues,track.language,track.automatic,payload.finalUrl,source,YouTubeUrls.canonical(videoId),data.title,data.durationMs,parsed.truncated)
                    }catch(e: CancellationException){currentCoroutineContext().ensureActive();last=e}
                    catch(e: Exception){last=e}
                }
            }catch(e: CancellationException){currentCoroutineContext().ensureActive();last=e}
            catch(e: Exception){last=e}
            checkMode(mode)
        }
        // An unreachable/blocked timed-text endpoint is not evidence that captions
        // are absent, and must not silently trigger audio transcription.
        if(sawAbsent&&!sawTrack)return ContextCaptionResult.Unavailable(ContextEvidenceFailure.ABSENT,"The available public video records provide no captions",if(allowAudio)cachedAudio else null)
        return ContextCaptionResult.Unavailable(failure(last),last?.message?.take(200) ?: "No public source supplied captions for this video")
    }
    /** Returns a descriptor only; no media request, playback proof or audio download. */
    suspend fun audio(videoUrl: String,originalFallback: Boolean): ContextAudioDescriptor? {
        val id=YouTubeUrls.videoId(videoUrl) ?: return null
        val allowed=originalFallback&&gateway.originalFallback;val mode=gateway.currentPolicy();var obtained=false;var last: Exception?=null
        for(source in candidates(id,allowed)) {
            currentCoroutineContext().ensureActive()
            try {
                val data=videoMetadata(source,id);obtained=true;checkMode(mode)
                data.audio?.takeIf {endpointAllowed(it.url,source,allowed)}?.let {return it}
            }catch(e: CancellationException){currentCoroutineContext().ensureActive();last=e}
            catch(e: Exception){last=e}
            checkMode(mode)
        }
        if(!obtained&&last!=null)throw last
        return null
    }
    private fun candidates(id: String,original: Boolean): List<Source> {
        val target=YouTubeUrls.recognize(YouTubeUrls.canonical(id))!!;val tor=gateway.currentPolicy().tor
        val paths=pool.sourcesFor(target,Capability.POST,original).filter {it.frontend in setOf("invidious","piped","original")&&(original||!ContextVideoParsing.originalEndpoint(it.root))&&(it.frontend!="original"||it.role==SourceRole.ORIGINAL&&original)&&pool.learning.eligible(it,Capability.POST,tor,target)}
            .sortedWith(compareBy<Source> {it.role==SourceRole.ORIGINAL}.thenBy {pool.learning.score(it,Capability.POST,tor)})
        return paths.filter {it.role!=SourceRole.ORIGINAL}.take(5)+paths.filter {it.role==SourceRole.ORIGINAL}.take(1)
    }
    private suspend fun videoMetadata(source: Source,id: String): ContextVideoMetadata {
        val mode=gateway.currentPolicy();val key="${source.key}|$id|$mode";val now=clock()
        metadata[key]?.takeIf {now-it.at in 0..60_000}?.let {return it.value}
        val data=withTimeout(if(mode.tor)35_000L else 15_000L){YouTubeAdapter(source,gateway,routes).contextMetadata(id)}
        checkMode(mode);require(data.videoId==id){"Video identity changed"}
        metadata[key]=Cached(now,data)
        while(metadata.size>8)metadata.entries.minByOrNull {it.value.at}?.let {metadata.remove(it.key)}
        return data
    }
    private fun endpointAllowed(url: String,source: Source,original: Boolean): Boolean {
        val endpoint=url.toHttpUrlOrNull() ?: return false
        if(endpoint.username.isNotEmpty()||endpoint.password.isNotEmpty())return false
        if(original||source.role==SourceRole.ORIGINAL)return original
        if(ContextVideoParsing.originalEndpoint(url))return false
        val root=source.root.toHttpUrlOrNull() ?: return false
        return endpoint.scheme==root.scheme&&endpoint.host==root.host&&endpoint.port==root.port
    }
    private fun checkMode(mode: NetworkPolicy) {if(gateway.currentPolicy()!=mode)throw CancellationException("Network mode changed")}
    private fun failure(error: Exception?): ContextEvidenceFailure=when((error as? SourceFailure)?.status) {
        HealthStatus.LOGIN_REQUIRED->ContextEvidenceFailure.LOGIN_REQUIRED
        HealthStatus.CHALLENGE->ContextEvidenceFailure.CHALLENGE
        HealthStatus.UNSUPPORTED->ContextEvidenceFailure.UNSUPPORTED
        else->if(error?.message.orEmpty().contains("Blocked by privacy filters",true))ContextEvidenceFailure.BLOCKED else ContextEvidenceFailure.UNAVAILABLE
    }
    private fun languageRank(language: String,preferred: List<String>): Int {
        val normal=language.lowercase(Locale.ROOT);return preferred.indexOfFirst {normal==it.lowercase(Locale.ROOT)||normal.substringBefore('-')==it.lowercase(Locale.ROOT).substringBefore('-')}.takeIf {it>=0} ?: preferred.size+1
    }
}

/** Local HTML extraction only; metadata and text remain source assertions. */
object ContextArticles {
    private val identities by lazy {DarwinArticleRoutes()}
    const val MAX_TEXT_CHARS=240_000
    const val READING_FORMAT="markdown-blocks-v1"
    fun extract(payload: Payload,originalUrl: String,fetchedAt: Long=System.currentTimeMillis(),readerChecks:Boolean=false): ContextArticleEvidence {
        val type=payload.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if(type.isNotEmpty()&&type !in setOf("text/html","application/xhtml+xml"))throw SourceFailure(HealthStatus.UNSUPPORTED,"This source is not a public HTML article")
        if(!payload.text.trimStart('\uFEFF',' ','\n','\r','\t').startsWith('<'))throw SourceFailure(HealthStatus.UNSUPPORTED,"The source did not return an HTML article")
        if(payload.text.length>4*1024*1024)throw SourceFailure(HealthStatus.UNSUPPORTED,"This article exceeds the extraction budget")
        HtmlAdmission.check(payload.text)
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        if(document.allElements.size>30_000)throw SourceFailure(HealthStatus.UNSUPPORTED,"This article exceeds the document budget")
        if(readerChecks) {
            val lead=(document.title()+" "+document.selectFirst("h1")?.text().orEmpty()).lowercase()
            if(Regex("article not found|could not (?:find|retrieve)|error fetching|unsupported url|something went wrong").containsMatchIn(lead))throw SourceFailure(HealthStatus.EMPTY,"The public reader did not supply this article")
            if(ArchiveUrls.original(payload.finalUrl)!=null||payload.finalUrl.toHttpUrlOrNull()?.host in setOf("archive.ph","archive.is","archive.today"))
                document.select("#wm-ipp-base,#wm-ipp-print,#donato,#HEADER,#FOOTER,#DIVSHARE,.archive-toolbar").remove()
        }
        val headline=document.title()+" "+document.select("h1").text()
        val lead=(headline+" "+document.body().text().take(800)).lowercase(Locale.ROOT)
        if(document.select("#challenge-container,#turnstile-widget-container,#anubis-main,#captcha-area,.g-recaptcha").isNotEmpty()||Regex("checking your browser|verify you are human|making sure you.re not a bot|just a moment").containsMatchIn(headline.lowercase(Locale.ROOT)))throw SourceFailure(HealthStatus.CHALLENGE,"This article requires a browser challenge")
        val wall=document.select("#paywall,.paywall,[data-testid=paywall],#login-wall,.login-wall,[data-testid=login-wall]").any {it.text().isNotBlank()}
        val login=document.select("input[type=password]").isNotEmpty()&&Regex("sign in|log in|login").containsMatchIn(lead)
        val shortGate=Regex("log in to continue|login required|sign in to (?:view|continue)|account required|subscribe to (?:read|continue)|subscription required").containsMatchIn(lead)&&document.select("article,main,[role=main]").text().length<800
        if(wall||login||shortGate)throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"This source did not provide an accountless article")
        fun meta(vararg names: String)=names.firstNotNullOfOrNull {name->document.selectFirst("meta[property=$name],meta[name=$name]")?.attr("content")?.trim()?.takeIf {it.isNotBlank()}}
        val author=meta("author","article:author","parsely-author")?.take(300)
        val site=meta("og:site_name","application-name")?.take(200) ?: originalUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
        val published=meta("article:published_time","datePublished","date","pubdate") ?: document.selectFirst("time[datetime]")?.attr("datetime")
        val publishedAt=published?.let {value->runCatching {Instant.parse(value).toEpochMilli()}.getOrNull() ?: runCatching {LocalDate.parse(value.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()}.getOrNull()}
        val paper=identities.metadataDoi(document)?.doi ?: PublicSourceRecords.paper(originalUrl)?.identity
        val declaredReferences=PublicRelations.citations(document,originalUrl)
        document.select("script,style,noscript,iframe,frame,object,embed,canvas,svg,form,nav,aside,footer,button,input,textarea,select,[hidden],[aria-hidden=true],[inert],[role=navigation],[role=banner],.cookie-banner,.cookie-consent,.newsletter-signup,.share-buttons,.social-share,.related-posts").remove()
        val hiddenStyle=Regex("(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important)?\\s*(?:;|$)",RegexOption.IGNORE_CASE)
        document.select("[style]").filter {hiddenStyle.containsMatchIn(it.attr("style"))}.forEach {it.remove()}
        // Resolve before Readability4J normalizes relative links: its URI
        // helper can discard an explicit source port. Attribution must retain
        // the exact public destination, including non-default ports.
        document.select("a[href]").forEach {link->link.absUrl("href").takeIf {it.isNotBlank()}?.let {link.attr("href",it)}}
        // Readability4J is a local parser. The URL is a base for links, never a fetch.
        val readable=Readability4J(payload.finalUrl,document).parse()
        val content=readable.articleContent ?: throw SourceFailure(HealthStatus.EMPTY,"No readable article body was supplied")
        content.select("script,style,iframe,object,embed,nav,aside,footer,form").remove()
        val blocks=mutableListOf<ContextArticleBlock>();var total=0;var truncated=false
        val markers=java.util.IdentityHashMap<Element,String>()
        content.select("ol").forEach {list->
            val items=list.children().filter {it.normalName()=="li"}
            val reverse=list.hasAttr("reversed")
            var number=(list.attr("start").toIntOrNull() ?: if(reverse)items.size else 1).toLong()
            items.forEach {item->
                number=item.attr("value").toIntOrNull()?.toLong() ?: number
                markers[item]="$number.";number+=if(reverse)-1 else 1
            }
        }
        val tableHeaders=java.util.IdentityHashMap<Element,List<Element>>()
        content.select("table").forEach {table->
            val headers=table.selectFirst("tr")?.children()?.filter {it.normalName() in setOf("td","th")}.orEmpty()
            if(headers.isNotEmpty()&&headers.all {it.normalName()=="th"}&&table.select("[colspan],[rowspan]").all {
                    it.attr("colspan").let {v->v.isEmpty()||v=="1"}&&it.attr("rowspan").let {v->v.isEmpty()||v=="1"}})tableHeaders[table]=headers
        }
        val candidates=content.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,figcaption,tr")
        for(element in candidates) {
            val tag=element.normalName()
            if(element.parents().any {it!=content&&(it.normalName() in setOf("p","pre","figcaption","td","th","tr")||tag!="li"&&it.normalName()=="li")})continue
            if(tag=="blockquote"&&element.select("p,li").isNotEmpty())continue
            fun plain(value:Element)=value.wholeText().replace(Regex("[\\t\\x0B\\f\\r ]+")," ").replace(Regex("\\n[ \\n]+"),"\n").trim()
            val text=when(tag) {
                "pre"->element.wholeText().trim('\n','\r')
                "li"->plain(element.clone().also {it.select("ul,ol").remove()})
                "tr"->{
                    val cells=element.children().filter {it.normalName() in setOf("td","th")}
                    val table=element.parents().firstOrNull {it.normalName()=="table"}
                    val headers=tableHeaders[table].orEmpty()
                    val labelled=headers.firstOrNull()?.parent()!=element&&headers.size==cells.size&&headers.isNotEmpty()
                    cells.mapIndexed {index,cell->if(labelled)plain(headers[index])+": "+plain(cell)else plain(cell)}.joinToString(if(labelled)"; "else " | ")
                }
                else->plain(element)
            }
            if(text.isEmpty())continue
            val remaining=MAX_TEXT_CHARS-total
            if(remaining<=0){truncated=true;break}
            val limited=text.take(remaining);if(limited.length<text.length)truncated=true
            val level=tag.takeIf {it.matches(Regex("h[1-6]"))}?.last()?.digitToInt() ?: 0
            val kind=when {level>0->ContextArticleBlockKind.HEADING;tag=="li"->ContextArticleBlockKind.LIST;tag=="pre"->ContextArticleBlockKind.CODE;tag=="figcaption"->ContextArticleBlockKind.CAPTION;tag=="blockquote"||element.parents().any {it.normalName()=="blockquote"}->ContextArticleBlockKind.QUOTE;else->ContextArticleBlockKind.PARAGRAPH}
            val links=element.select("a[href]").mapNotNull {a->a.absUrl("href").toHttpUrlOrNull()?.takeIf {it.scheme in setOf("http","https")&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()}.distinct().take(32)
            blocks+=ContextArticleBlock(limited,kind,level,links,markers[element],if(tag=="li")(element.parents().count {it.normalName() in setOf("ul","ol")}-1).coerceAtLeast(0)else 0);total+=limited.length
        }
        if(blocks.isEmpty())content.text().trim().takeIf {it.isNotEmpty()}?.let {blocks+=ContextArticleBlock(it.take(MAX_TEXT_CHARS));truncated=it.length>MAX_TEXT_CHARS}
        if(blocks.sumOf {it.text.length}<160||blocks.none {it.kind!=ContextArticleBlockKind.HEADING&&it.text.length>=60})throw SourceFailure(HealthStatus.EMPTY,"The source did not supply a substantial readable article")
        val title=readable.title?.trim().takeUnless {it.isNullOrBlank()} ?: meta("og:title") ?: document.title()
        return ContextArticleEvidence(originalUrl,payload.finalUrl,title.take(1000),author ?: readable.byline?.trim()?.takeIf {it.isNotBlank()}?.take(300),site,publishedAt,blocks,fetchedAt,truncated,references=(SignalDiscovery.references(content,originalUrl)+declaredReferences).distinctBy {it.url}.sortedByDescending {it.priority}.take(12),publicationDoi=paper)
    }
}

data class ContextParsedCaptions(val cues: List<ContextCaptionCue>,val truncated: Boolean=false)
/** VTT/SRT, timedtext XML/SRV3/TTML and JSON3; XML parsing has no external loader. */
object ContextCaptionParser {
    private const val MAX_CUES=12_000
    private const val MAX_TEXT=240_000
    fun parse(body: String,mimeType: String?=null): ContextParsedCaptions {
        require(body.length<=2*1024*1024){"Caption data exceeds its size limit"}
        val trimmed=body.trimStart('\uFEFF',' ','\n','\r','\t')
        val raw=when {
            trimmed.startsWith('{')->json(trimmed)
            trimmed.startsWith('<')->xml(trimmed)
            trimmed.startsWith("WEBVTT")||trimmed.contains(" --> ")->vtt(trimmed)
            else->throw SourceFailure(HealthStatus.UNSUPPORTED,"The source supplied an unsupported caption format")
        }
        val output=mutableListOf<ContextCaptionCue>();var length=0;var truncated=false
        for(cue in raw.sortedBy {it.startMs}) {
            if(cue.startMs<0||cue.endMs?.let {it<cue.startMs}==true||cue.text.isBlank())continue
            if(output.size>=MAX_CUES||length+cue.text.length>MAX_TEXT){truncated=true;break}
            if(output.lastOrNull()==cue)continue
            output+=cue;length+=cue.text.length
        }
        if(output.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The caption source returned no readable timed text")
        return ContextParsedCaptions(output,truncated)
    }
    private fun plain(value: String)=Jsoup.parseBodyFragment(value.replace(Regex("<\\d{2}:\\d{2}(?::\\d{2})?[.,]\\d+>"),"").replace("<br>","\n").replace("<br/>","\n")).text().trim()
    private fun number(value: String): Long?=value.toDoubleOrNull()?.takeIf {it.isFinite()&&it>=0&&it<31_536_000_000.0}?.roundToLong()
    internal fun timestamp(value: String): Long? {
        val text=value.trim().replace(',','.')
        if(text.endsWith("ms"))return number(text.dropLast(2))
        if(text.endsWith('s'))return text.dropLast(1).toDoubleOrNull()?.times(1000)?.takeIf {it.isFinite()&&it>=0}?.roundToLong()
        val parts=text.split(':')
        if(parts.size !in 2..3)return null
        val seconds=parts.last().toDoubleOrNull()?.takeIf {it.isFinite()&&it in 0.0..<60.0} ?: return null
        val minutes=parts[parts.size-2].toLongOrNull()?.takeIf {it in 0..59} ?: return null
        val hours=if(parts.size==3)parts[0].toLongOrNull()?.takeIf {it in 0..8760} ?: return null else 0
        return ((hours*3600+minutes*60)*1000+seconds*1000).roundToLong()
    }
    private fun vtt(body: String): List<ContextCaptionCue> {
        val cues=mutableListOf<ContextCaptionCue>()
        for(block in body.replace("\r\n","\n").split(Regex("\n[ \\t]*\n"))) {
            val lines=block.lines();val index=lines.indexOfFirst {it.contains("-->")};if(index<0)continue
            val times=lines[index].split("-->",limit=2);val start=timestamp(times[0]) ?: continue;val end=timestamp(times[1].trim().substringBefore(' ')) ?: continue
            val text=plain(lines.drop(index+1).joinToString("\n"));if(text.isNotBlank())cues+=ContextCaptionCue(start,end,text)
        }
        return cues
    }
    private fun xml(body: String): List<ContextCaptionCue> {
        val document=Jsoup.parse(body,"",Parser.xmlParser());val cues=mutableListOf<ContextCaptionCue>()
        for(node in document.select("text[start],p[t],p[begin]")) {
            val start=when {node.hasAttr("start")->node.attr("start").toDoubleOrNull()?.times(1000)?.let {number(it.toString())};node.hasAttr("t")->number(node.attr("t"));else->timestamp(node.attr("begin"))} ?: continue
            val duration=when {node.hasAttr("dur")->node.attr("dur").toDoubleOrNull()?.times(1000)?.let {number(it.toString())};node.hasAttr("d")->number(node.attr("d"));else->null}
            val end=if(node.hasAttr("end"))timestamp(node.attr("end"))else duration?.let {start+it}
            val text=plain(node.html());if(text.isNotBlank())cues+=ContextCaptionCue(start,end,text)
        }
        return cues
    }
    private fun json(body: String): List<ContextCaptionCue> {
        val root=runCatching {JsonParser.parseString(body).asJsonObject}.getOrElse {throw SourceFailure(HealthStatus.EMPTY,"Invalid caption JSON")}
        return root.getAsJsonArray("events")?.mapNotNull {event->
            val obj=event.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val start=obj["tStartMs"]?.let {number(it.asString)} ?: return@mapNotNull null
            val duration=obj["dDurationMs"]?.let {number(it.asString)}
            val text=plain(obj.getAsJsonArray("segs")?.joinToString("") {runCatching {it.asJsonObject["utf8"]?.asString}.getOrNull().orEmpty()}.orEmpty())
            text.takeIf {it.isNotBlank()}?.let {ContextCaptionCue(start,duration?.let {d->start+d},it)}
        }.orEmpty()
    }
}

internal object ContextVideoParsing {
    fun fromJson(o: JsonObject,source: Source,sourceUrl: String,id: String,piped: Boolean): ContextVideoMetadata {
        fun text(value: JsonObject,key: String)=YouTubeAdapter.text(value,key)
        fun number(value: JsonObject,key: String)=YouTubeAdapter.number(value,key)
        val duration=number(o,if(piped)"duration"else "lengthSeconds")?.takeIf {it in 1L..31_536_000L}?.times(1000)
        val key=if(piped)"subtitles"else "captions"
        val tracks=YouTubeAdapter.array(o,key).take(32).mapNotNull {entry->
            val track=entry.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val url=safe(text(track,"url"),sourceUrl) ?: return@mapNotNull null
            val language=text(track,if(piped)"code"else "languageCode").ifBlank {"und"}.take(40)
            val label=text(track,if(piped)"name"else "label").take(200)
            val automatic=runCatching {track["autoGenerated"]?.asBoolean}.getOrNull()==true||text(track,"kind")=="asr"||label.contains("auto-generated",true)||label.contains("auto generated",true)
            ContextCaptionTrack(url,language,automatic,label,text(track,"mimeType").takeIf {it.isNotBlank()})
        }.distinctBy {it.url}
        val audioValues=YouTubeAdapter.array(o,if(piped)"audioStreams"else "adaptiveFormats")
        val audio=audioValues.mapNotNull {entry->entry.takeIf {it.isJsonObject}?.asJsonObject}.filter {text(it,if(piped)"mimeType"else "type").startsWith("audio/")}
            .sortedWith(compareBy<JsonObject> {!text(it,if(piped)"mimeType"else "type").startsWith("audio/mp4")}.thenBy {number(it,"bitrate") ?: Long.MAX_VALUE})
            .firstNotNullOfOrNull {entry->safe(text(entry,"url"),sourceUrl)?.let {url->ContextAudioDescriptor(id,url,sourceUrl,text(entry,if(piped)"mimeType"else "type").substringBefore(';'),duration,source,text(o,"title"))}}
        val declared=o.has(key)&&o[key].isJsonArray&&(o[key].asJsonArray.size()==0||tracks.isNotEmpty())
        return ContextVideoMetadata(id,text(o,"title"),tracks,declared,audio,sourceUrl,source,duration)
    }
    fun safe(value: String,base: String): String?=base.toHttpUrlOrNull()?.resolve(value)?.takeIf {value.isNotBlank()&&it.scheme in setOf("http","https")&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null}?.toString()
    fun originalEndpoint(value: String): Boolean=value.toHttpUrlOrNull()?.host?.let {host->listOf("youtube.com","youtu.be","youtube-nocookie.com","googlevideo.com","ytimg.com","youtubei.googleapis.com").any {host==it||host.endsWith(".$it")}} ?: false
}
