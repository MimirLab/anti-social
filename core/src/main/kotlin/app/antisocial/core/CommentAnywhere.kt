/* AntiSocial, GPL-3.0-or-later. Independent native interoperability with
 * CommentAnywhere.app 0.14.0's published protocol; no extension code is bundled. */
package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

const val COMMENT_ANYWHERE="commentanywhere"
enum class CommentOrder(val wire: String) { NEWEST("New"), SLOP("Top") }
data class AnywhereComment(val id: String,val text: String,val username: String="Anonymous",val timestamp: Long?=null,
    val up: Long=0,val down: Long=0,val authorIds: List<String> = emptyList(),val deleted: Boolean=false,
    val children: List<AnywhereComment> = emptyList(),val hasMore: Boolean=false)
data class AnywherePage(val pageUrl: String,val items: List<AnywhereComment>,val order: CommentOrder,
    val fetchedAt: Long,val limit: Int=30,val limited: Boolean=false) {
    fun flattened(): List<Pair<AnywhereComment,Int>> = buildList {
        fun walk(nodes: List<AnywhereComment>,depth: Int) {if(depth>8)return;nodes.forEach {add(it to depth);walk(it.children,depth+1)}}
        walk(items,0)
    }
}
/** A public anonymous Firebase client identity, never a social-media account.
 * Tokens belong in a device-local encrypted vault, outside exports and cache. */
data class AnywhereIdentity(val uid: String,val token: String,val refreshToken: String,val expiresAt: Long) {
    fun owns(comment: AnywhereComment)=comment.authorIds.any {it==uid||it=="firebase:$uid"||it=="anon:$uid"}
    override fun toString()="AnywhereIdentity(uid=$uid, credentials=redacted, expiresAt=$expiresAt)"
}
interface AnywhereIdentityStore {
    suspend fun load(): AnywhereIdentity?
    suspend fun save(identity: AnywhereIdentity)
}
enum class AnywhereAction(val endpoint: String) { POST("postComment"), REPLY("replyComment"), VOTE("vote"), EDIT("editComment"), DELETE("deleteComment") }

/** Service keys match the extension's path normalization, but we refuse query-
 * identified pages rather than put unrelated Facebook posts in the same room.
 * Darwin resolves known frontend aliases; archives always unwrap to originals.
 * The URL is not fetched or resolved over the network just to open comments. */
object AnywhereUrls {
    fun root(post: Post,routes: DarwinRoutes): String {
        val original=ArchiveUrls.original(post.canonicalUrl) ?: post.canonicalUrl
        val supplied=original.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid original page.")
        // The service normalizes away queries. Its YouTube conversation must
        // use an authentic per-video path, never the shared /watch document.
        if(post.platform==Platform.YOUTUBE) {
            val target=routes.recognize(original,Platform.YOUTUBE)
            require(target?.platform==Platform.YOUTUBE){"This entry has no original YouTube page."}
            val id=YouTubeUrls.videoId(target.canonicalUrl) ?: throw IllegalArgumentException("A YouTube video is required.")
            return "https://youtu.be/$id"
        }
        require(supplied.query==null||ContentIdentity.key(post.platform,supplied.toString())==ContentIdentity.key(post.platform,supplied.newBuilder().query(null).fragment(null).build().toString())){
            "CommentAnywhere cannot safely distinguish this query-based address. Use the platform's own thread."
        }
        val target=routes.recognize(original,post.platform) ?: throw IllegalArgumentException("This entry has no original page for CommentAnywhere.")
        require(target.platform==post.platform){"The original page does not match this post."}
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid original page")
        require(url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()&&url.port==443){"A public original page is required."}
        require(url.host !in setOf("t.co","redd.it","pin.it","vm.tiktok.com","vt.tiktok.com","fb.watch","lnkd.in")){"Open the full original post before joining its CommentAnywhere discussion."}
        val path=url.encodedPath.replace(Regex("/{2,}"),"/").trimEnd('/').ifEmpty {"/"}
        require(!(url.query!=null&&(path.endsWith(".php",true)||path=="/watch"||target.kind==TargetKind.SEARCH||target.kind==TargetKind.COMMUNITY))){
            "CommentAnywhere cannot safely distinguish posts at this query-based address. The platform's own thread is still available."
        }
        require(url.query==null||ContentIdentity.key(post.platform,url.toString())==ContentIdentity.key(post.platform,url.newBuilder().query(null).fragment(null).build().toString())){
            "CommentAnywhere cannot safely distinguish this query-based comment address. Use the platform's own thread."
        }
        return url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
    }
}

class CommentAnywhereClient(private val gateway: NetworkGateway,private val identities: AnywhereIdentityStore,
    private val base: String="https://us-central1-page-comments.cloudfunctions.net/api/",
    private val signup: String="https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$PUBLIC_CLIENT_KEY",
    private val refresh: String="https://securetoken.googleapis.com/v1/token?key=$PUBLIC_CLIENT_KEY",
    private val now: ()->Long=System::currentTimeMillis) {
    private val auth=Mutex()
    suspend fun identity()=identities.load()
    private fun endpoint(path: String)=base.trimEnd('/')+"/"+path
    suspend fun read(pageUrl: String,order: CommentOrder,limit: Int=30,parentId: String?=null): AnywherePage {
        validatePageUrl(pageUrl);require(limit in 1..100);parentId?.let(::validateId)
        val url=endpoint(if(parentId==null)"list"else "subtree").toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("pageUrl",pageUrl).addQueryParameter("sort",order.wire)
            .addQueryParameter("limit",limit.toString()).addQueryParameter("depth",if(parentId==null)"2"else "5")
            .apply {parentId?.let {addQueryParameter("parentId",it)}}.build()
        return parse(gateway.service(url.toString()).text,pageUrl,order,limit,now())
    }
    /** Called only by explicit Publish/Reply/Vote/Edit/Delete actions. A read
     * never registers an identity, and a failed refresh never creates another. */
    suspend fun mutate(pageUrl: String,order: CommentOrder,action: AnywhereAction,text: String="",id: String?=null,value: Int?=null) {
        validatePageUrl(pageUrl)
        if(action in setOf(AnywhereAction.POST,AnywhereAction.REPLY,AnywhereAction.EDIT))require(text.isNotBlank()&&text.toByteArray().size<=8000){"Comments must contain 1–8,000 bytes of text."}
        if(action!=AnywhereAction.POST)validateId(requireNotNull(id))
        if(action==AnywhereAction.VOTE)require(value in setOf(-1,1))
        val identity=authenticated()
        val body=JsonObject().apply {
            addProperty("pageUrl",pageUrl);addProperty("sort",order.wire)
            if(action in setOf(AnywhereAction.POST,AnywhereAction.REPLY,AnywhereAction.EDIT))addProperty("text",text.trim())
            if(action==AnywhereAction.REPLY)addProperty("parentId",id)else if(id!=null)addProperty("id",id)
            if(value!=null)addProperty("value",value)
        }
        checkedObject(gateway.service(endpoint(action.endpoint),json=body,token=identity.token,provider="firebase").text)
    }
    private suspend fun authenticated(): AnywhereIdentity=auth.withLock {
        val previous=identities.load()
        if(previous!=null&&previous.expiresAt>now()+60_000)return@withLock previous
        val data=if(previous==null)checkedObject(gateway.service(signup,json=JsonObject().apply {addProperty("returnSecureToken",true)}).text)
        else checkedObject(gateway.service(refresh,form=mapOf("grant_type" to "refresh_token","refresh_token" to previous.refreshToken)).text)
        val token=data.string(if(previous==null)"idToken"else "id_token").orEmpty()
        val renew=data.string(if(previous==null)"refreshToken"else "refresh_token") ?: previous?.refreshToken.orEmpty()
        val uid=previous?.uid ?: data.string("localId").orEmpty()
        require(uid.length in 1..256&&token.length in 1..8192&&renew.length in 1..8192){"CommentAnywhere could not establish an anonymous identity."}
        val seconds=data.string(if(previous==null)"expiresIn"else "expires_in")?.toLongOrNull()?.coerceIn(60,86_400) ?: 3600
        AnywhereIdentity(uid,token,renew,now()+seconds*1000).also {identities.save(it)}
    }
    companion object {
        // Published by CommentAnywhere's distributed client. This identifies its
        // Firebase project; it is not a server secret or a AntiSocial developer key.
        private const val PUBLIC_CLIENT_KEY="AIzaSyCPWX2Bg6vxQ-HdEXE_beiNNUSo7XNtu3k"
        private fun validateId(id: String){require(id.length in 1..256&&id.all {it.code in 33..126}&&!id.contains('/')){"Invalid comment identifier."}}
        fun validatePageUrl(value: String) {
            val url=value.toHttpUrlOrNull()
            require(value.length<=4096&&url!=null&&url.isHttps&&url.port==443&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null&&url.query==null){"A normalized original page is required."}
        }
        private fun checkedObject(text: String): JsonObject {
            val data=runCatching {JsonParser.parseString(text).takeIf {it.isJsonObject}?.asJsonObject}.getOrNull()
                ?: throw IllegalStateException("CommentAnywhere returned an unreadable response.")
            if(data.has("error")&&!data["error"].isJsonNull||data["ok"]?.toString()=="false"||data["success"]?.toString()=="false")
                throw IllegalStateException("CommentAnywhere could not complete this request.")
            return data
        }
        fun parse(text: String,pageUrl: String,order: CommentOrder,limit: Int,at: Long): AnywherePage {
            validatePageUrl(pageUrl)
            require(text.toByteArray().size<=512*1024){"CommentAnywhere's response is too large."}
            val data=checkedObject(text)
            val raw=data["items"]?.takeIf {it.isJsonArray}?.asJsonArray ?: throw IllegalStateException("CommentAnywhere did not supply a comment list.")
            var count=0;var characters=0;var limited=false
            val seen=hashSetOf<String>()
            fun nodes(array: JsonArray,depth: Int): List<AnywhereComment> = buildList {
                for(item in array) {
                    if(count>=200||characters>=200_000||depth>8){limited=true;break}
                    val o=item.takeIf {it.isJsonObject}?.asJsonObject ?: throw IllegalStateException("CommentAnywhere returned an invalid comment.")
                    val id=o.string("id") ?: throw IllegalStateException("CommentAnywhere returned a comment without an identifier.")
                    validateId(id);if(!seen.add(id))continue
                    val body=o.string("text").orEmpty();require(body.length<=32_000){"Comment text exceeds its size limit."}
                    if(characters+body.length+id.length+512>200_000){limited=true;break}
                    characters+=body.length+id.length+512;count++
                    val children=o["children"]?.takeIf {it.isJsonArray}?.asJsonArray
                    val parsed=children?.let {nodes(it,depth+1)}.orEmpty()
                    val timestamp=o.string("timestamp")?.let {v->v.toLongOrNull()?.takeIf {it>0} ?: runCatching {Instant.parse(v).toEpochMilli()}.getOrNull()}
                    add(AnywhereComment(id,body,o.string("username")?.take(100)?.ifBlank {"Anonymous"} ?: "Anonymous",timestamp,
                        o.string("up")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,o.string("down")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                        (listOfNotNull(o.string("userId"),o.string("uid"),o.string("authorId"))+o["authorIds"]?.takeIf {it.isJsonArray}?.asJsonArray?.take(3)?.mapNotNull {it.takeIf {it.isJsonPrimitive}?.asString}.orEmpty()).filter {it.length<=256}.distinct().take(3),
                        o["deleted"]?.toString()=="true"||body.equals("[deleted by user]",true),parsed,
                        o["hasMore"]?.toString()=="true"||children!=null&&parsed.size<children.size()))
                }
            }
            val items=nodes(raw,0)
            return AnywherePage(pageUrl,items,order,at,limit,limited||raw.size()>=limit)
        }
    }
}
private fun JsonObject.string(key: String)=get(key)?.takeIf {it.isJsonPrimitive}?.asString
