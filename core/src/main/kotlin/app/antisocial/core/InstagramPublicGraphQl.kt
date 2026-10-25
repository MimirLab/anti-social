/* anti social, GPL-3.0-or-later. First-party accountless public reader.
 * Public Relay mechanics researched from vvzvlad/research-mcp (MIT, 2026)
 * and FxEmbed/FxEmbed. See public-pathways-notices.txt. No site code executes.
 */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.jsoup.Jsoup
import java.io.StringReader
import java.math.BigInteger
import java.util.UUID

/** This route supplies a paged public grid and selected full posts. Grid rows
 * have no supplied dates: they must never certify chronological feed freshness.
 * A returned cursor is a local capability, not a caller-selected Relay query. */
internal class InstagramPublicGraphQlAdapter(override val source:Source,private val gateway:NetworkGateway,
    private val routes:DarwinRoutes,private val clock:()->Long=System::currentTimeMillis):DataAdapter {
    override val capabilities=setOf(Capability.TIMELINE,Capability.POST,Capability.MEDIA,Capability.COMMENTS)
    private val cursors=InstagramPublicCursors(clock)
    override fun accepts(target:Target)=InstagramPublicGraphQl.identity(target)!=null
    override fun sentinel(capability:Capability)=requireNotNull(routes.recognize(if(capability in setOf(Capability.POST,Capability.MEDIA,Capability.COMMENTS))
        "https://www.instagram.com/p/DeTUk4sDSp-/" else "https://www.instagram.com/nasa/"))
    override suspend fun feed(target:Target):Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"Public profile previews do not supply publication dates for a chronological feed")
    override suspend fun profile(target:Target):Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"This public route supplies posts rather than complete account profiles")
    override fun historyCursor(target:Target,known:List<Post>):String? {
        val selected=InstagramPublicGraphQl.identity(target)?.takeIf {it.profile} ?: return null
        if(known.isEmpty()||known.size>256||known.any {it.platform!=Platform.INSTAGRAM})return null
        return cursors.issue(source,target,gateway.sessionFor(source.root),InstagramPublicGraphQl.query(selected),"",null,
            emptyList(),emptyList(),known.map {it.identityKey})
    }
    override suspend fun fetch(target:Target,cursor:String?):Page {
        val selected=InstagramPublicGraphQl.identity(target) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Select a public Instagram profile or post")
        val session=gateway.sessionFor(source.root)
        fun check(){if(gateway.sessionFor(source.root)!==session)throw CancellationException("Connection changed; refresh this page")}
        currentCoroutineContext().ensureActive();check()
        if(!selected.profile&&cursor!=null)throw SourceFailure(HealthStatus.UNSUPPORTED,"Individual posts have no profile continuation")
        val original=cursor?.let {cursors.resolve(it,source,target,session)}
        var previous=original
        // Most snapshots overlap just one or two grid windows. Keep each click
        // bounded, and retain duplicate-only checkpoints if more work remains.
        repeat(3) {step->
            var query=previous?.query ?: InstagramPublicGraphQl.query(selected)
            var document:GatewayDocument
            var root:JsonObject
            try {
                document=request(target,query,previous?.after,session)
                root=InstagramPublicGraphQl.envelope(document,source,clock())
            }catch(e:InstagramQueryChanged) {
                if(previous?.after!=null)throw SourceFailure(HealthStatus.UNAVAILABLE,"The public query changed; refresh this page")
                check()
                val address=source.root.trimEnd('/')+requireNotNull(target.canonicalUrl.toHttpUrlOrNull()).encodedPath
                val head=gateway.extractorDocument(address,headers=InstagramPublicGraphQl.navigationHeaders,
                    sourceUrl=target.canonicalUrl,sameOrigin=true,maxBytes=4L*1024*1024,expectedSession=session)
                check();InstagramPublicGraphQl.requireStatus(head,clock())
                query=InstagramPublicGraphQl.bootstrap(head.text,selected)
                    ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"The public page did not declare a supported readonly query")
                document=request(target,query,null,session)
                root=try {InstagramPublicGraphQl.envelope(document,source,clock())}catch(_:InstagramQueryChanged){throw SourceFailure(HealthStatus.UNAVAILABLE,"The public query is temporarily unavailable")}
            }
            currentCoroutineContext().ensureActive();check()
            val provenance=Provenance(source,clock(),document.finalUrl)
            if(!selected.profile) {
                val post=InstagramPublicGraphQl.post(root,selected.value,provenance)
                // Exact transport and both media identities were checked. Use
                // the logical post address for labels; retain API provenance.
                val payload=Payload(document.text,target.canonicalUrl,"application/json",document.status)
                val labeled=PlatformAiLabels.attach(listOf(post),payload,target,provenance)
                currentCoroutineContext().ensureActive();check()
                return Page(labeled,accounts=listOfNotNull(InstagramPublicGraphQl.authorProfile(root,post)),provenance=provenance)
            }
            val window=InstagramPublicGraphQl.window(root,target,provenance)
            if(previous?.owner?.takeIf {it.isNotEmpty()}?.let {it!=window.owner}==true)throw SourceFailure(HealthStatus.UNAVAILABLE,"The public continuation returned a different account")
            val fresh=window.posts.filter {it.identityKey !in previous?.seen.orEmpty()}
            if(window.posts.isNotEmpty()&&fresh.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The public source repeated its previous page; refresh to continue")
            val visible=fresh.filter {it.identityKey !in previous?.omit.orEmpty()}
            val stalled=window.more&&(window.after==null||window.after==previous?.after||window.after in previous?.issued.orEmpty())
            check()
            val next=if(window.more&&!stalled&&fresh.isNotEmpty())cursors.issue(source,target,session,query,window.owner,
                requireNotNull(window.after),(previous?.seen.orEmpty()+fresh.map {it.identityKey}).takeLast(128),
                (previous?.issued.orEmpty()+window.after).takeLast(8),previous?.omit.orEmpty(),(previous?.position ?: 0)+1)else null
            if(visible.isNotEmpty()||next==null||step==2)return Page(visible,next=next,provenance=provenance,limitedTimeline=true,
                notice=if(visible.isEmpty()&&next!=null)"Known posts skipped; earlier pages are still available."else "Public profile previews; open a post for its available full media.",
                continuationIssue=if(stalled)"The public source did not supply an advancing continuation"else null,
                continuationProgress=visible.isEmpty()&&next!=null)
            previous=cursors.resolve(next,source,target,session)
            // Only duplicate-only windows advance this retry checkpoint. No
            // undelivered new post can be skipped after cancellation or failure.
            if(cursor!=null&&original!=null)cursors.checkpoint(cursor,original,requireNotNull(previous))
        }
        error("Bounded public paging did not finish")
    }
    override suspend fun comments(target:Target,cursor:String?):Page {
        val selected=InstagramPublicGraphQl.identity(target)?.takeUnless {it.profile}
            ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Select an Instagram post for its public comments")
        val session=gateway.sessionFor(source.root)
        val context=currentCoroutineContext()
        fun check(){context.ensureActive();if(gateway.sessionFor(source.root)!==session)throw CancellationException("Connection changed; retry this discussion")}
        check()
        val previous=cursor?.let {cursors.resolve(it,source,target,session)}
        var query=previous?.query ?: InstagramPublicGraphQl.commentsQuery(selected.value)
        require(query.name==InstagramPublicGraphQl.COMMENTS){"This cursor belongs to another operation"}
        var document:GatewayDocument;var data:JsonObject
        try {document=request(target,query,previous?.after,session);data=InstagramPublicGraphQl.envelope(document,source,clock())}
        catch(_:InstagramQueryChanged) {
            if(previous!=null)throw SourceFailure(HealthStatus.UNAVAILABLE,"The public comment query changed; reopen this discussion")
            check()
            val head=gateway.extractorDocument(source.root.trimEnd('/')+requireNotNull(target.canonicalUrl.toHttpUrlOrNull()).encodedPath,
                headers=InstagramPublicGraphQl.navigationHeaders,sourceUrl=target.canonicalUrl,sameOrigin=true,maxBytes=4L*1024*1024,expectedSession=session)
            check();InstagramPublicGraphQl.requireStatus(head,clock())
            query=InstagramPublicGraphQl.bootstrap(head.text,selected,query) ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"The public page did not declare a supported comment query")
            document=request(target,query,null,session)
            data=try {InstagramPublicGraphQl.envelope(document,source,clock())}catch(_:InstagramQueryChanged){throw SourceFailure(HealthStatus.UNAVAILABLE,"The public comment query is temporarily unavailable")}
        }
        currentCoroutineContext().ensureActive();check()
        val provenance=Provenance(source,clock(),document.finalUrl)
        val window=InstagramPublicComments.parse(data,target,provenance)
        val fresh=window.posts.filter {it.identityKey !in previous?.seen.orEmpty()}
        if(window.posts.isNotEmpty()&&fresh.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The public comment source repeated its previous page")
        val stalled=window.more&&(window.after==null||window.after==previous?.after||window.after in previous?.issued.orEmpty())
        check()
        val next=if(window.more&&!stalled&&fresh.isNotEmpty())cursors.issue(source,target,session,query,InstagramPublicGraphQl.mediaId(selected.value),
            requireNotNull(window.after),(previous?.seen.orEmpty()+fresh.map {it.identityKey}).takeLast(128),(previous?.issued.orEmpty()+window.after).takeLast(8))else null
        return Page(fresh,next=next,accounts=window.accounts,provenance=provenance,
            notice="Public comments; some replies may be unavailable.",continuationIssue=if(stalled)"The public source did not supply an advancing comment cursor"else null)
    }
    private suspend fun request(target:Target,query:InstagramPublicGraphQl.Query,after:String?,session:NetworkSession):GatewayDocument {
        val lsd=UUID.randomUUID().toString().replace("-","").take(12)
        val fields=linkedMapOf("lsd" to lsd,"fb_api_caller_class" to "RelayModern","fb_api_req_friendly_name" to query.name,
            "server_timestamps" to "true","doc_id" to query.id,"variables" to query.variables.deepCopy().apply {after?.let {addProperty("after",it)}}.toString())
        val body=FormBody.Builder().apply {fields.forEach {(k,v)->add(k,v)}}.build()
        val bytes=Buffer().also {body.writeTo(it)}.readByteArray()
        return gateway.extractorDocument(source.root.trimEnd('/')+"/api/graphql",method="POST",data=bytes,
            sourceUrl=target.canonicalUrl,sameOrigin=true,maxBytes=4L*1024*1024,expectedSession=session,
            headers=InstagramPublicGraphQl.apiHeaders+mapOf("Content-Type" to "application/x-www-form-urlencoded",
                "X-FB-LSD" to lsd,"X-FB-Friendly-Name" to query.name))
    }
    companion object {const val FRONTEND="instagram-public-graphql"}
}

/** Bound to the issuing source, target and actual connection object. Ordinary
 * cache only: bounded, expiring, memory-only and unusable after mode changes. */
internal class InstagramPublicCursors(private val clock:()->Long,private val maxEntries:Int=32,private val ttl:Long=10*60_000L) {
    data class Entry(val source:Source,val target:Target,val session:NetworkSession,val query:InstagramPublicGraphQl.Query,
        val owner:String,val after:String?,val seen:List<String>,val issued:List<String>,val created:Long,val omit:List<String> = emptyList(),val position:Long=0)
    private val entries=linkedMapOf<String,Entry>()
    init {require(maxEntries in 1..64&&ttl in 1..10*60_000L)}
    private fun trim(session:NetworkSession) {
        val now=clock();entries.entries.removeAll {now-it.value.created !in 0..ttl||it.value.session!==session}
        while(entries.size>maxEntries)entries.remove(entries.keys.first())
    }
    @Synchronized fun issue(source:Source,target:Target,session:NetworkSession,query:InstagramPublicGraphQl.Query,owner:String,
        after:String?,seen:List<String>,issued:List<String>,omit:List<String> = emptyList(),position:Long=0):String {
        trim(session)
        val key="ig-public-v1:"+UUID.randomUUID()
        entries[key]=Entry(source,target,session,query.copy(variables=query.variables.deepCopy()),owner,after,seen.takeLast(128),issued.takeLast(8),clock(),omit.take(256),position)
        trim(session);return key
    }
    @Synchronized fun resolve(key:String,source:Source,target:Target,session:NetworkSession):Entry {
        trim(session)
        return entries[key]?.takeIf {it.source==source&&it.target==target&&it.session===session}
            ?: throw SourceFailure(HealthStatus.EMPTY,"Refresh this profile to continue its public posts")
    }
    @Synchronized fun checkpoint(key:String,original:Entry,updated:Entry) {
        val current=entries[key] ?: return
        if(current.created==original.created&&current.source==updated.source&&current.target==updated.target&&current.session===updated.session&&
            current.position<=updated.position)entries[key]=updated.copy(created=original.created)
    }
    @Synchronized internal fun size()=entries.size
}

internal class InstagramQueryChanged:Exception()

internal object InstagramPublicGraphQl {
    data class Identity(val profile:Boolean,val value:String)
    data class Query(val name:String,val id:String,val variables:JsonObject)
    data class Window(val owner:String,val posts:List<Post>,val more:Boolean,val after:String?)
    private const val PROFILE="PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery"
    private const val POST="PolarisLoggedOutDesktopWWWPostRootContentQuery"
    const val COMMENTS="PolarisPostCommentsPaginationQuery"
    private const val ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val types=setOf("XIGPolarisImageMedia","XIGPolarisVideoMedia","XIGPolarisCarouselMedia","XIGPolarisSidecarMedia")
    private val userPattern=Regex("[A-Za-z0-9_.]{1,30}")
    private val codePattern=Regex("[A-Za-z0-9_-]{5,16}")
    private fun JsonObject.s(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString.orEmpty()
    private fun JsonObject.o(key:String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.a(key:String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
    private fun JsonObject.number(key:String)=get(key)?.takeIf {it.isJsonPrimitive}?.let {runCatching {it.asString.toLongOrNull()}.getOrNull()}
    private fun safe(value:String)=value.takeIf {it.length<=8192}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    private fun fail(message:String):Nothing=throw SourceFailure(HealthStatus.EMPTY,message)
    fun identity(target:Target):Identity? {
        if(target.platform!=Platform.INSTAGRAM)return null
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: return null
        if(!url.isHttps||url.host !in setOf("www.instagram.com","instagram.com")||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||url.fragment!=null)return null
        val path=url.pathSegments.filter(String::isNotEmpty)
        return when {
            target.kind==TargetKind.PROFILE&&path.size==1&&userPattern.matches(path[0])&&path[0].equals(target.identity.removePrefix("@"),true)->Identity(true,path[0])
            target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&path.size==2&&path[0] in setOf("p","reel","reels","tv")&&codePattern.matches(path[1])->Identity(false,path[1])
            else->null
        }
    }
    fun mediaId(code:String):String {
        require(codePattern.matches(code))
        return code.fold(BigInteger.ZERO){n,c->n.shiftLeft(6)+BigInteger.valueOf(ALPHABET.indexOf(c).toLong())}.toString()
    }
    fun query(selected:Identity)=Query(if(selected.profile)PROFILE else POST,if(selected.profile)"29483846714553007"else "27130156389949648",
        JsonObject().apply {if(selected.profile){addProperty("first",12);addProperty("username",selected.value);addProperty("__relay_internal__pv__PolarisSEOIsCrawlerrelayprovider",false)}else addProperty("media_id",mediaId(selected.value))})
    fun commentsQuery(code:String)=Query(COMMENTS,"25516980651312394",JsonObject().apply {
        addProperty("media_id",mediaId(code));addProperty("first",12);add("last",JsonNull.INSTANCE);add("before",JsonNull.INSTANCE)
        addProperty("sort_order","recent");addProperty("__relay_internal__pv__PolarisIsLoggedInrelayprovider",false)
    })
    private val browser="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    val apiHeaders=mapOf("User-Agent" to browser,"Accept-Language" to "en-US,en;q=0.9","Accept" to "*/*","X-IG-App-ID" to "936619743392459",
        "Sec-Fetch-Site" to "same-origin","Sec-Fetch-Mode" to "cors","Sec-Fetch-Dest" to "empty")
    val navigationHeaders=mapOf("User-Agent" to browser,"Accept-Language" to "en-US,en;q=0.9","Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "sec-ch-ua" to "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"","sec-ch-ua-mobile" to "?0","sec-ch-ua-platform" to "\"macOS\"",
        "Sec-Fetch-Site" to "none","Sec-Fetch-Mode" to "navigate","Sec-Fetch-Dest" to "document","Sec-Fetch-User" to "?1")
    private fun json(value:String):JsonObject {
        if(value.length>4*1024*1024)fail("The public response exceeds its extraction budget")
        return try {JsonReader(StringReader(value.trimStart().removePrefix("for (;;);").trimStart())).use {reader->
            reader.strictness=Strictness.STRICT;reader.nestingLimit=64
            val root=JsonParser.parseReader(reader).takeIf {it.isJsonObject}?.asJsonObject ?: fail("The public source returned no JSON record")
            if(reader.peek()!=JsonToken.END_DOCUMENT)fail("The public source returned extra data")
            root
        }}catch(e:SourceFailure){throw e}catch(_:Exception){fail("The public source returned an unreadable record")}
    }
    fun requireStatus(document:GatewayDocument,now:Long) {
        val retry=document.headers.entries.firstOrNull {it.key.equals("Retry-After",true)}?.value?.singleOrNull()?.let {value->
            value.toLongOrNull()?.takeIf {it>=0}?.coerceAtMost(86400)?.times(1000) ?: runCatching {java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now}.getOrNull()?.coerceIn(0,86_400_000)}
        if(document.status !in 200..299)throw SourceFailure(when(document.status){401->HealthStatus.LOGIN_REQUIRED;403->HealthStatus.CHALLENGE;429->HealthStatus.RATE_LIMITED;else->HealthStatus.UNAVAILABLE},"The public Instagram route returned ${document.status}",retry,document.status)
    }
    fun envelope(document:GatewayDocument,source:Source,now:Long):JsonObject {
        val final=document.finalUrl.toHttpUrlOrNull() ?: fail("The public route returned an invalid destination")
        val origin=source.root.toHttpUrlOrNull() ?: fail("The public route has no source")
        if(final.scheme!=origin.scheme||final.host!=origin.host||final.port!=origin.port||final.encodedPath!="/api/graphql"||final.query!=null||final.fragment!=null)fail("The public query redirected outside its selected endpoint")
        requireStatus(document,now)
        val root=json(document.text)
        val messages=(listOf(root.s("message"))+root.a("errors").take(16).mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject?.s("message")}).joinToString(" ").lowercase(java.util.Locale.ROOT)
        val status=when {
            root.get("require_login")?.let {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean&&it.asBoolean}==true||messages.contains("login_required")||messages.contains("log in")->HealthStatus.LOGIN_REQUIRED
            messages.contains("rate limit")||messages.contains("please wait a few minutes")->HealthStatus.RATE_LIMITED
            messages.contains("challenge")||messages.contains("checkpoint")->HealthStatus.CHALLENGE
            else->null
        }
        if(status!=null)throw SourceFailure(status,"The public Instagram route is currently ${status.name.lowercase().replace('_',' ')}")
        if(root.a("errors").isNotEmpty()) {
            if(listOf("unknown query","invalid doc_id","query not found","persistedquerynotfound","provided variable").any {it in messages})throw InstagramQueryChanged()
            throw SourceFailure(HealthStatus.UNAVAILABLE,"The public Instagram query returned an error")
        }
        return root.o("data") ?: fail("The public source returned no selected data")
    }
    fun bootstrap(html:String,selected:Identity,expected:Query=query(selected)):Query? {
        HtmlAdmission.check(html)
        val found=mutableListOf<Query>();var remaining=80_000
        fun walk(value:JsonElement,depth:Int) {
            if(depth>64||--remaining<0)return
            if(value.isJsonObject) {
                val node=value.asJsonObject
                if(node.s("actorID")=="0"&&node.s("queryName")==expected.name&&node.s("queryID").matches(Regex("[0-9]{10,32}"))) {
                    val vars=node.o("variables")
                    val matches=if(selected.profile)vars?.s("username")==selected.value else vars?.s("media_id")==mediaId(selected.value)
                    if(matches)found+=expected.copy(id=node.s("queryID"),variables=expected.variables.deepCopy().apply {
                        vars?.entrySet()?.filter {it.key.matches(Regex("__relay_internal__pv__[A-Za-z0-9_]{1,100}relayprovider"))&&it.value.isJsonPrimitive&&it.value.asJsonPrimitive.isBoolean}?.take(16)?.forEach {add(it.key,it.value)}
                        if(expected.name==COMMENTS)addProperty("__relay_internal__pv__PolarisIsLoggedInrelayprovider",false)
                    })
                }
                node.entrySet().forEach {walk(it.value,depth+1)}
            }else if(value.isJsonArray)value.asJsonArray.forEach {walk(it,depth+1)}
        }
        val scripts=Jsoup.parse(html).select("script[type=application/json]")
        if(scripts.size>128)return null
        scripts.forEach {script->runCatching {json(script.data())}.getOrNull()?.let {walk(it,0)}}
        return if(remaining<0)null else found.distinctBy {it.id+it.variables.toString()}.singleOrNull()
    }
    fun window(data:JsonObject,target:Target,provenance:Provenance):Window {
        val user=data.o("xig_user_by_username") ?: fail("The public profile returned no account")
        val owner=user.s("pk").takeIf {it.matches(Regex("[0-9]{1,30}"))} ?: fail("The public profile did not identify its account")
        val connection=user.o("polaris_ordered_timeline_connection") ?: fail("The public profile returned no post connection")
        val edges=connection.a("edges");if(edges.size>48)fail("The public profile exceeded its requested window")
        val posts=edges.mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject?.o("node")}.mapNotNull {node->
            // Membership comes from this exact selected-profile connection.
            // Keep the actual source author, including other grid contributors.
            val code=node.s("code").takeIf {codePattern.matches(it)} ?: return@mapNotNull null
            val postTarget=target.copy(kind=TargetKind.POST,canonicalUrl="https://www.instagram.com/p/$code")
            InstagramPublicMedia.parse(node,postTarget,provenance)
        }.distinctBy {it.id}
        val page=connection.o("page_info") ?: fail("The public source omitted its continuation state")
        val more=page.get("has_next_page")?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean ?: fail("The public source omitted its continuation state")
        val after=page.s("end_cursor").takeIf {it.length in 1..8192&&it.all {c->c.code in 33..126}}
        return Window(owner,posts,more,after)
    }
    fun post(data:JsonObject,code:String,provenance:Provenance):Post {
        val outer=data.o("xig_polaris_media") ?: fail("The public route returned no selected post")
        fun matches(node:JsonObject)=node.s("code")==code&&node.s("pk")==mediaId(code)&&node.s("__typename") in types
        if(!matches(outer))fail("The public source returned a different post")
        val node=outer.o("if_not_gated_logged_out") ?: throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"The selected post is not available to a logged-out reader")
        if(!matches(node)||node.s("__isXIGPolarisMedia")!=node.s("__typename"))fail("The public media identity did not match its post")
        val user=node.o("user") ?: fail("The public source did not identify the author")
        val handle=user.s("username").takeIf {userPattern.matches(it)} ?: fail("The public source did not identify the author")
        val body=node.o("caption")?.s("text").orEmpty().take(100_000)
        val type=node.s("__typename");var complete=true
        fun media(item:JsonObject):Media? {
            val display=safe(item.s("display_uri"))
            val image=item.o("image_versions2")?.a("candidates")?.take(16)?.firstNotNullOfOrNull {it.takeIf {it.isJsonObject}?.asJsonObject?.s("url")?.let(::safe)} ?: display
            val poster=display ?: image
            val video=item.s("__typename")=="XIGPolarisVideoMedia"||item.number("media_type")==2L
            if(video) {
                val variants=item.a("video_versions").take(16).mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject}
                val url=variants.firstNotNullOfOrNull {safe(it.s("url"))} ?: safe(item.s("video_url"))
                if(url!=null)return Media(url,"video",poster,item.s("accessibility_caption").take(4000))
                complete=false
            }
            return image?.let {Media(it,preview=display?.takeIf {preview->preview!=it},description=if(video)"Video preview; full media unavailable"else item.s("accessibility_caption").take(4000))}
        }
        val gallery=type in setOf("XIGPolarisCarouselMedia","XIGPolarisSidecarMedia")
        val rawChildren=node.a("carousel_media").ifEmpty {node.a("carousel_media_list")}
        val children=rawChildren.take(16).mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject}
        val attachments=if(gallery&&children.isNotEmpty())children.mapNotNull(::media)else listOfNotNull(media(node))
        if(gallery&&(children.isEmpty()||rawChildren.size!=attachments.size||node.number("carousel_media_count")?.let {it!=attachments.size.toLong()}==true))complete=false
        if(body.isBlank()&&attachments.isEmpty())fail("The public post contains no readable material")
        val url="https://www.instagram.com/p/$code"
        val relations=mutableListOf<PublicRelation>()
        fun relation(value:JsonObject,kind:PublicRelationKind) {
            val name=value.s("username").takeIf {userPattern.matches(it)} ?: return
            if(!name.equals(handle,true))relations+=PublicRelation(kind,"https://www.instagram.com/$name/",value.s("full_name").take(160).ifBlank {name})
        }
        node.a("coauthor_producers").take(16).forEach {it.takeIf {it.isJsonObject}?.asJsonObject?.let {value->relation(value,PublicRelationKind.COLLABORATOR)}}
        node.o("usertags")?.a("in")?.take(16)?.forEach {it.takeIf {it.isJsonObject}?.asJsonObject?.o("user")?.let {value->relation(value,PublicRelationKind.TAGGED)}}
        return Post(ContentIdentity.key(Platform.INSTAGRAM,url),Platform.INSTAGRAM,url,user.s("full_name").take(400).ifBlank {handle},body,
            publishedAt=node.number("taken_at")?.takeIf {it in 1..253402300799L}?.times(1000),avatar=safe(user.s("profile_pic_url")) ?: safe(user.s("profile_image_uri")),
            media=attachments,replyCount=node.number("comment_count")?.coerceAtLeast(0),score=node.number("like_count")?.coerceAtLeast(0),provenance=provenance,
            publicRelations=relations.distinctBy {it.kind to it.url}.take(16).takeIf {it.isNotEmpty()},
            authorAccount=PostAuthorAccounts.instagram(handle,user.get("pk")?.takeIf {it.isJsonPrimitive}?.asString),
            extractionCoverage=ExtractionCoverage(text=if(body.isBlank())ExtractionStatus.UNKNOWN else ExtractionStatus.PRESENT,
                attachments=if(complete&&attachments.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.PARTIAL,
                note=if(!complete)"The public source supplied only part of this post's media."else null))
    }
    fun authorProfile(data:JsonObject,post:Post):Profile? {
        val target=PostAuthorAccounts.target(post) ?: return null
        val user=data.o("xig_polaris_media")?.o("if_not_gated_logged_out")?.o("user") ?: return null
        if(user.s("username")!=target.identity)return null
        val name=user.s("full_name").take(400)
        return Profile(target,name.ifBlank {target.identity},avatar=post.avatar,displayNameSupplied=name.isNotBlank(),persistentId=post.authorAccount?.persistentId)
    }
}
