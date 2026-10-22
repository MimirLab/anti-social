/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor.
 * Uses NewPipeExtractor (Team NewPipe, GPL-3.0-or-later) through AntiSocial's gateway. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page as ExtractorPage
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.*
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.ContinuationInterceptor

/** Synchronous extractor work runs on an interruptible worker. Its singleton
 * downloader has no captured repository/client: every request requires the
 * calling coroutine's thread-local gateway binding. Unbound requests fail. */
private object YouTubeExtractorTransport {
    private data class Binding(val gateway: NetworkGateway,val sourceUrl: String,val context: CoroutineContext)
    private val binding=ThreadLocal<Binding>()
    private val downloader=object: Downloader() {
        override fun execute(request: Request): Response {
            val current=binding.get() ?: throw IOException("YouTube extraction has no network context")
            current.context.ensureActive()
            val result=runBlocking(current.context.minusKey(ContinuationInterceptor)) {
                current.gateway.extractorDocument(request.url(),request.httpMethod(),request.dataToSend(),
                    request.headers().filterKeys {it.lowercase() !in setOf("cookie","authorization")}.mapValues {it.value.joinToString(",")},current.sourceUrl)
            }
            return Response(result.status,result.message,result.headers,result.text,result.finalUrl)
        }
    }
    @Volatile private var initialized=false
    private fun initialize() {
        if(initialized)return
        synchronized(this){if(!initialized){NewPipe.init(downloader,Localization("en","US"),ContentCountry("US"));initialized=true}}
    }
    suspend fun <T> run(gateway: NetworkGateway,sourceUrl: String,action: ()->T): T {
        initialize()
        val current=Binding(gateway,sourceUrl,currentCoroutineContext())
        return withContext(binding.asContextElement(current)){runInterruptible(Dispatchers.IO){YouTubeScriptGuard.run(current.context,action=action)}}
    }
}

/** Accountless original fallback only. Alternate YouTube adapters are selected
 * ahead of this adapter by Darwin's source pool. No WebView or login session. */
class YouTubeNewPipe(private val gateway: NetworkGateway,private val source: Source,private val routes: DarwinRoutes) {
    private val service get()=ServiceList.YouTube
    private val gson=Gson()
    internal data class Lane(val channelId: String,val tab: String,val page: ExtractorPage?)
    internal data class Cursor(val version: Int=1,val sourceKey: String,val targetUrl: String,val mode: String,val lanes: List<Lane> = emptyList(),val page: ExtractorPage?=null)
    private fun provenance(url: String)=Provenance(source,System.currentTimeMillis(),url)
    private fun validTarget(target: Target) {
        require(target.platform==Platform.YOUTUBE&&YouTubeUrls.recognize(target.canonicalUrl)?.let {
            (it.kind==target.kind||target.kind==TargetKind.FEED&&it.kind==TargetKind.PROFILE||target.kind==TargetKind.THREAD&&it.kind==TargetKind.POST)&&it.identity==target.identity
        }==true){"Invalid YouTube target"}
    }
    suspend fun fetch(target: Target,cursor: String?=null): Page {
        validTarget(target)
        return YouTubeExtractorTransport.run(gateway,target.canonicalUrl) {
            when(target.kind) {
                TargetKind.POST,TargetKind.THREAD->{require(cursor==null){"A video has no continuation"};val info=StreamInfo.getInfo(service,target.canonicalUrl);require(YouTubeUrls.videoId(info.url)==YouTubeUrls.videoId(target.canonicalUrl)){"YouTube returned a different video"};Page(listOf(post(info)),provenance=provenance(target.canonicalUrl))}
                TargetKind.PROFILE,TargetKind.FEED->channel(target,cursor)
                TargetKind.SEARCH->search(target,cursor,false)
                else->throw SourceFailure(HealthStatus.UNSUPPORTED,"This YouTube target is not supported")
            }
        }
    }
    suspend fun comments(target: Target,cursor: String?=null): Page {
        validTarget(target)
        require(YouTubeUrls.videoId(target.canonicalUrl)!=null){"Comments need a YouTube video"}
        return YouTubeExtractorTransport.run(gateway,target.canonicalUrl) {
            val items: List<CommentsInfoItem>;val next: ExtractorPage?;var notice: String?="YouTube supplies ranked public comments. New sorts the available comments by their published dates."
            if(cursor==null) {
                val info=CommentsInfo.getInfo(service,target.canonicalUrl)
                items=info.relatedItems;next=info.nextPage
                if(info.isCommentsDisabled)notice="Comments are disabled for this video."
            }else {
                val decoded=decode(cursor,target,"comments")
                val page=CommentsInfo.getMoreItems(service,target.canonicalUrl,requireNotNull(decoded.page))
                items=page.items;next=page.nextPage
            }
            Page(items.take(200).mapNotNull {comment(it,target)},encode(Cursor(sourceKey=source.key,targetUrl=target.canonicalUrl,mode="comments",page=next)),provenance=provenance(target.canonicalUrl),notice=notice)
        }
    }
    suspend fun directory(target: Target,cursor: String?=null): Page {
        validTarget(target);require(target.kind==TargetKind.SEARCH)
        return YouTubeExtractorTransport.run(gateway,target.canonicalUrl){search(target,cursor,true)}
    }
    private fun channel(target: Target,cursor: String?): Page {
        val posts=mutableListOf<Post>();val lanes=mutableListOf<Lane>();var profile: Profile?=null
        if(cursor==null) {
            val info=ChannelInfo.getInfo(service,target.canonicalUrl)
            profile=channelProfile(info,target)
            val selected=info.tabs.filter {handler->handler.contentFilters.any {it in setOf(ChannelTabs.VIDEOS,ChannelTabs.SHORTS,ChannelTabs.LIVESTREAMS)}}.take(3)
            if(selected.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"This public channel does not expose an upload tab")
            selected.forEach {handler->
                val infoPage=ChannelTabInfo.getInfo(service,handler)
                posts+=infoPage.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::post)
                // NewPipe's ready Videos handler contains a relative URL and
                // a renderer-backed builder. Persist only the resolved stable
                // channel ID and tab; no lambda, renderer or handler graph.
                infoPage.nextPage?.takeIf(ExtractorPage::isValid)?.let {lanes+=Lane(info.id,handler.contentFilters.single(),it)}
            }
        }else {
            val decoded=decode(cursor,target,"channel")
            decoded.lanes.forEach {lane->
                val handler=service.channelTabLHFactory.fromQuery(lane.channelId,listOf(lane.tab),"")
                val more=ChannelTabInfo.getMoreItems(service,handler,requireNotNull(lane.page))
                posts+=more.items.filterIsInstance<StreamInfoItem>().mapNotNull(::post)
                more.nextPage?.takeIf(ExtractorPage::isValid)?.let {lanes+=Lane(lane.channelId,lane.tab,it)}
            }
        }
        return channelPage(target,posts,profile,lanes)
    }
    internal fun channelPage(target: Target,posts: List<Post>,profile: Profile?,lanes: List<Lane>): Page {
        var issue: String?=null
        val next=try {encode(Cursor(sourceKey=source.key,targetUrl=target.canonicalUrl,mode="channel",lanes=lanes))}
        catch(_: YouTubeCursorWire.SizeLimit){issue="More uploads remain, but YouTube's continuation exceeds this reading window. This subscription is still pending.";null}
        return Page(posts.distinctBy {it.id}.sortedByDescending {it.publishedAt ?: 0},next,profile,provenance(target.canonicalUrl),notice=issue,continuationIssue=issue)
    }
    internal fun channelProfile(info: ChannelInfo,target: Target): Profile {
        val id=info.id
        require(id.matches(Regex("UC[A-Za-z0-9_-]{22}"))){"YouTube returned an invalid channel"}
        val actual=YouTubeUrls.recognize(info.url)?.takeIf {it.kind==TargetKind.PROFILE&&it.identity=="channel/$id"}
            ?: throw IllegalArgumentException("YouTube returned a different channel")
        val requested=YouTubeUrls.recognize(target.canonicalUrl) ?: throw IllegalArgumentException("Invalid YouTube channel")
        if(requested.identity.startsWith("channel/"))require(requested.identity==actual.identity){"YouTube returned a different channel"}
        return Profile(actual,info.name,info.description.orEmpty(),image(info.avatars),info.subscriberCount.takeIf {it>=0},displayNameSupplied=info.name.isNotBlank())
    }
    private fun search(target: Target,cursor: String?,directory: Boolean): Page {
        val mode=if(directory)"directory"else "search"
        val handler=service.searchQHFactory.fromQuery(target.identity,listOf(if(directory)"channels"else "videos"),"")
        val items: List<InfoItem>;val next: ExtractorPage?
        if(cursor==null){val info=SearchInfo.getInfo(service,handler);items=info.relatedItems;next=info.nextPage}
        else {val decoded=decode(cursor,target,mode);val more=SearchInfo.getMoreItems(service,handler,requireNotNull(decoded.page));items=more.items;next=more.nextPage}
        return Page(if(directory)emptyList()else items.filterIsInstance<StreamInfoItem>().take(100).mapNotNull(::post),
            encode(Cursor(sourceKey=source.key,targetUrl=target.canonicalUrl,mode=mode,page=next)),provenance=provenance(target.canonicalUrl),
            accounts=items.filterIsInstance<ChannelInfoItem>().take(100).mapNotNull {item->YouTubeUrls.recognize(item.url)?.let {Profile(it,item.name,item.description.orEmpty(),image(item.thumbnails),item.subscriberCount.takeIf {it>=0},displayNameSupplied=item.name.isNotBlank())}})
    }
    private fun post(info: StreamInfo): Post {
        val id=YouTubeUrls.videoId(info.url) ?: throw SourceFailure(HealthStatus.EMPTY,"YouTube returned a different video")
        val canonical=YouTubeUrls.canonical(id)
        return Post(ContentIdentity.key(Platform.YOUTUBE,canonical),Platform.YOUTUBE,canonical,info.uploaderName.orEmpty(),description(info.description),info.name.orEmpty(),info.uploadDate?.instant?.toEpochMilli(),image(info.uploaderAvatars),
            listOf(Media(canonical,"video",image(info.thumbnails),info.name.orEmpty(),durationMs=info.duration.takeIf {it>0}?.times(1000))),score=info.likeCount.takeIf {it>=0},provenance=provenance(canonical),links=listOfNotNull(info.uploaderUrl.takeIf {it.isNotBlank()}))
    }
    internal fun post(item: StreamInfoItem): Post? {
        val id=YouTubeUrls.videoId(item.url) ?: return null;val canonical=YouTubeUrls.canonical(id)
        return Post(ContentIdentity.key(Platform.YOUTUBE,canonical),Platform.YOUTUBE,canonical,item.uploaderName.orEmpty(),item.shortDescription.orEmpty(),item.name.orEmpty(),item.uploadDate?.instant?.toEpochMilli(),image(item.uploaderAvatars),
            listOf(Media(canonical,"video",image(item.thumbnails),item.name.orEmpty(),durationMs=item.duration.takeIf {it>0}?.times(1000))),provenance=provenance(canonical),links=listOfNotNull(item.uploaderUrl?.takeIf {it.isNotBlank()}))
    }
    private fun comment(item: CommentsInfoItem,target: Target): Post? {
        val id=item.commentId?.takeIf {it.matches(Regex("[A-Za-z0-9_.-]{1,256}"))} ?: return null
        val root=YouTubeUrls.canonical(requireNotNull(YouTubeUrls.videoId(target.canonicalUrl)))
        return Post(ContentIdentity.key(Platform.YOUTUBE,"$root&lc=$id"),Platform.YOUTUBE,"$root&lc=$id",item.uploaderName.orEmpty(),description(item.commentText),publishedAt=item.uploadDate?.instant?.toEpochMilli(),avatar=image(item.uploaderAvatars),replyCount=item.replyCount.toLong().takeIf {it>=0},score=item.likeCount.toLong().takeIf {it>=0},parentId=ContentIdentity.key(Platform.YOUTUBE,root),provenance=provenance(root))
    }
    private fun description(value: Description?): String=if(value==null)""else if(value.type==Description.HTML)Jsoup.parseBodyFragment(value.content).apply {select("br").append("\n");select("p").prepend("\n")}.wholeText().trim()else value.content.orEmpty()
    private fun image(images: List<Image>?): String?=images.orEmpty().filter {safeUrl(it.url)}.minByOrNull {if(it.width>0)kotlin.math.abs(it.width-480)else Int.MAX_VALUE}?.url
    private fun safeUrl(value: String?)=value?.toHttpUrlOrNull()?.let {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}==true

    /** Accountless original fallback; callers must exhaust alternate sources and
     * respect original access policy before entering this method. StreamInfo is
     * shared for both caption and optional audio descriptors, without media IO. */
    internal suspend fun contextMetadata(videoId: String): ContextVideoMetadata {
        require(YouTubeUrls.validId(videoId));val canonical=YouTubeUrls.canonical(videoId)
        return YouTubeExtractorTransport.run(gateway,canonical) {
            val info=StreamInfo.getInfo(service,canonical)
            require(YouTubeUrls.videoId(info.url)==videoId&&!info.name.isNullOrBlank()) {"YouTube returned a different or empty video"}
            val duration=info.duration.takeIf {it in 1L..31_536_000L}?.times(1000)
            val tracks=info.subtitles.orEmpty().take(32).mapNotNull {stream->
                stream.content.takeIf(::safeUrl)?.let {ContextCaptionTrack(it,stream.languageTag.orEmpty().ifBlank {"und"},stream.isAutoGenerated,stream.displayLanguageName.orEmpty(),stream.format?.mimeType)}
            }.distinctBy {it.url}
            val audio=info.audioStreams.orEmpty().filter {validStream(it,emptySet())}.sortedWith(compareByDescending<AudioStream> {it.format?.mimeType in setOf("audio/mp4","audio/m4a")}.thenBy {it.averageBitrate}).firstOrNull()
                ?.let {ContextAudioDescriptor(videoId,it.content,canonical,it.format?.mimeType,duration,source,info.name.orEmpty())}
            ContextVideoMetadata(videoId,info.name.orEmpty(),tracks,true,audio,canonical,source,duration)
        }
    }

    suspend fun playback(videoId: String,rejectedUrls: Set<String> = emptySet(),muxedOnly: Boolean=false): YouTubePlayback {
        require(YouTubeUrls.validId(videoId));val canonical=YouTubeUrls.canonical(videoId)
        return YouTubeExtractorTransport.run(gateway,canonical) {
            val info=StreamInfo.getInfo(service,canonical)
            require(YouTubeUrls.videoId(info.url)==videoId){"YouTube returned a different video"}
            val muxed=info.videoStreams.orEmpty().filter {validStream(it,rejectedUrls)&&!it.isVideoOnly&&height(it)<=1080}
            val selected=muxed.filter {height(it)<=720}.maxWithOrNull(compareBy<VideoStream>({height(it)},{it.format?.mimeType=="video/mp4"}))
                ?: muxed.minByOrNull {height(it)}
            val preview=image(info.thumbnails);val duration=info.duration.takeIf {it>0}?.times(1000)
            val media=if(selected!=null)Media(selected.content,"video",preview,info.name.orEmpty(),selected.format?.mimeType,durationMs=duration)
            else if(!muxedOnly) {
                val audio=info.audioStreams.orEmpty().filter {validStream(it,rejectedUrls)}.sortedWith(compareByDescending<AudioStream> {it.format?.mimeType in setOf("audio/mp4","audio/m4a")}.thenBy {kotlin.math.abs(it.averageBitrate-128)}).firstOrNull()
                val video=info.videoOnlyStreams.orEmpty().filter {validStream(it,rejectedUrls)&&height(it)<=1080}.maxWithOrNull(compareBy<VideoStream>({height(it)},{it.format?.mimeType=="video/mp4"}))
                if(video!=null&&audio!=null)Media(video.content,"video",preview,info.name.orEmpty(),video.format?.mimeType,durationMs=duration,audioUrl=audio.content,audioMimeType=audio.format?.mimeType)
                else if(safeUrl(info.hlsUrl)&&info.hlsUrl !in rejectedUrls)Media(info.hlsUrl,"video",preview,info.name.orEmpty(),VideoFormats.HLS,durationMs=duration)
                else if(safeUrl(info.dashMpdUrl)&&info.dashMpdUrl !in rejectedUrls)Media(info.dashMpdUrl,"video",preview,info.name.orEmpty(),VideoFormats.DASH,durationMs=duration)
                else throw SourceFailure(HealthStatus.UNAVAILABLE,"YouTube has no playable accountless stream")
            }else throw SourceFailure(HealthStatus.UNAVAILABLE,"YouTube has no downloadable combined video and audio stream")
            YouTubePlayback(videoId,media,canonical,source)
        }
    }
    private fun validStream(stream: Stream,rejected: Set<String>)=stream.isUrl&&safeUrl(stream.content)&&stream.content !in rejected&&stream.deliveryMethod==DeliveryMethod.PROGRESSIVE_HTTP
    private fun height(stream: VideoStream)=stream.height.takeIf {it>0} ?: stream.resolution.orEmpty().takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE

    internal fun encode(value: Cursor): String? {
        if(value.mode=="channel"&&value.lanes.isEmpty()||value.mode!="channel"&&!ExtractorPage.isValid(value.page))return null
        validateCursor(value)
        return YouTubeCursorWire.encode(gson.toJson(value).toByteArray(Charsets.UTF_8))
    }
    private fun decode(value: String,target: Target,mode: String): Cursor {
        val parsed=gson.fromJson(String(YouTubeCursorWire.decode(value),Charsets.UTF_8),Cursor::class.java)
        require(parsed.version==1&&parsed.sourceKey==source.key&&parsed.targetUrl==target.canonicalUrl&&parsed.mode==mode){"YouTube continuation belongs to another source or target"}
        validateCursor(parsed);return parsed
    }
    private fun validateCursor(value: Cursor) {
        require(value.mode in setOf("channel","search","comments","directory"))
        require(value.lanes.size<=3)
        if(value.mode=="channel")require(value.lanes.isNotEmpty()&&value.page==null)else require(value.lanes.isEmpty()&&value.page!=null)
        fun checkUrl(value: String?) {
            if(value.isNullOrEmpty())return
            val u=value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid YouTube continuation URL")
            require(u.isHttps&&u.port==443&&u.host in setOf("www.youtube.com","youtube.com","m.youtube.com")&&u.username.isEmpty()&&u.password.isEmpty()){"YouTube continuation left its source"}
        }
        fun checkPage(page: ExtractorPage?) {
            require(page!=null&&ExtractorPage.isValid(page)){"Invalid YouTube continuation page"}
            require(page.cookies.orEmpty().isEmpty()){"YouTube continuation may not store cookies"}
            checkUrl(page.url)
            require(page.id.orEmpty().length<=16384&&page.ids.orEmpty().size<=128&&page.ids.orEmpty().all {it.length<=8192})
            val body=page.body
            if(body!=null){require(body.size<=16384);require(JsonParser.parseString(String(body,Charsets.UTF_8)).isJsonObject)}
        }
        if(value.mode!="channel")checkPage(value.page)
        if(value.mode=="channel") {
            val target=YouTubeUrls.recognize(value.targetUrl)?.takeIf {it.kind==TargetKind.PROFILE}
                ?: throw IllegalArgumentException("Invalid YouTube channel continuation target")
            require(value.lanes.map {it.channelId}.distinct().size==1){"YouTube continuation mixes different channels"}
            if(target.identity.startsWith("channel/"))require(value.lanes.all {"channel/${it.channelId}"==target.identity}){"YouTube continuation belongs to another channel"}
        }
        value.lanes.forEach {lane->
            require(lane.channelId.matches(Regex("UC[A-Za-z0-9_-]{22}"))&&lane.tab in setOf(ChannelTabs.VIDEOS,ChannelTabs.SHORTS,ChannelTabs.LIVESTREAMS)){"Invalid YouTube channel continuation"}
            checkPage(lane.page)
            val page=requireNotNull(lane.page)
            val url=page.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid YouTube continuation URL")
            require(url.encodedPath=="/youtubei/v1/browse"&&page.body!=null){"Invalid YouTube channel continuation endpoint"}
            val context=page.ids.orEmpty()
            require(context.size==3&&YouTubeUrls.recognize(context[1])?.identity=="channel/${lane.channelId}"&&context[2] in setOf("VERIFIED","UNVERIFIED","UNKNOWN")){"YouTube continuation attribution belongs to another channel"}
        }
    }
}
