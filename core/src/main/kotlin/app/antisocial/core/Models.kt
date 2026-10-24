package app.antisocial.core

enum class Platform(val id: String, val label: String, val origin: String, val accent: Long, val mark: String) {
    X("twitter", "X", "https://x.com", 0xFFE7E9EA, "𝕏"),
    BLUESKY("bluesky", "Bluesky", "https://bsky.app", 0xFF208BFE, "☁"),
    REDDIT("reddit", "Reddit", "https://www.reddit.com", 0xFFFF6033, "r"),
    TUMBLR("tumblr", "Tumblr", "https://www.tumblr.com", 0xFF70C9F5, "t"),
    TIKTOK("tiktok", "TikTok", "https://www.tiktok.com", 0xFF25F4EE, "♪"),
    INSTAGRAM("instagram", "Instagram", "https://www.instagram.com", 0xFFEA679E, "◎"),
    PINTEREST("pinterest", "Pinterest", "https://www.pinterest.com", 0xFFEF4456, "P"),
    IFUNNY("ifunny", "iFunny", "https://ifunny.co", 0xFFFFCF48, "☺"),
    TIEBA("baiduTieba", "Tieba", "https://tieba.baidu.com", 0xFF679BFF, "贴"),
    THREADS("threads", "Threads", "https://www.threads.com", 0xFFE7E9EA, "@"),
    FACEBOOK("facebook", "Facebook", "https://www.facebook.com", 0xFF1877F2, "f"),
    TRUTH_SOCIAL("truthsocial", "Truth Social", "https://truthsocial.com", 0xFF8975F6, "T"),
    MASTODON("mastodon", "Mastodon", "https://mastodon.social", 0xFF6364FF, "m"),
    LEMMY("lemmy", "Lemmy", "https://lemmy.world", 0xFF80BD51, "L"),
    PIXELFED("pixelfed", "Pixelfed", "https://pixelfed.social", 0xFFEB718F, "P"),
    MISSKEY("misskey", "Misskey", "https://misskey.io", 0xFF86B300, "M"),
    GETTR("gettr", "GETTR", "https://gettr.com", 0xFFCE2047, "G"),
    GAB("gab", "Gab", "https://gab.com", 0xFF21CF7A, "g"),
    TELEGRAM("telegram", "Telegram", "https://t.me", 0xFF26A5E4, "➤"),
    LINKEDIN("linkedin", "LinkedIn", "https://www.linkedin.com", 0xFF0A66C2, "in"),
    SNAPCHAT("snapchat", "Snapchat", "https://www.snapchat.com", 0xFFFFD928, "S"),
    YOUTUBE("youtube", "YouTube", "https://www.youtube.com", 0xFFFF4545, "▶"),
    RSS("rss", "RSS / Atom", "https://feeds.example.invalid", 0xFFF39A46, "◔");
    val searchDomain get()=origin.removePrefix("https://").removePrefix("www.")
}
enum class TargetKind { PROFILE, COMMUNITY, TAG, POST, THREAD, SEARCH, FEED, MEDIA }
enum class Capability { PROFILE, TIMELINE, POST, COMMENTS, SEARCH, FEED, MEDIA }
enum class SourceRole { ALTERNATE, ORIGINAL, CACHE }
enum class HealthStatus { UNKNOWN, HEALTHY, EMPTY, CHALLENGE, LOGIN_REQUIRED, RATE_LIMITED, UNAVAILABLE, UNSUPPORTED }
data class Target(val platform: Platform, val kind: TargetKind, val identity: String, val canonicalUrl: String)
data class Source(val platform: Platform, val frontend: String, val root: String, val fallbackOnly: Boolean = false, val role: SourceRole = SourceRole.ALTERNATE) {
    val key get() = "${platform.id}|$frontend|$root"
}
data class Provenance(val source: Source, val fetchedAt: Long, val finalUrl: String, val cached: Boolean = false)
/** Runtime is optional source metadata; unknown runtime does not imply a short video. */
data class Media(val url: String, val type: String = "image", val preview: String? = null, val description: String = "", val mimeType: String? = null,val aiMetadata: AiMetadata?=null,val durationMs: Long?=null,val audioUrl: String?=null,val audioMimeType: String?=null,val visualFingerprint: String?=null)
data class Post(val id: String, val platform: Platform, val canonicalUrl: String, val author: String, val body: String, val title: String = "", val publishedAt: Long? = null, val avatar: String? = null, val media: List<Media> = emptyList(), val replyCount: Long? = null, val score: Long? = null, val parentId: String? = null, val provenance: Provenance? = null, val links: List<String>? = null,val preview: Boolean=false,val archivedVersion: ArchivedVersion?=null,val recoveredArchive: ArchiveRecovery?=null,val aiDisclosures: List<AiDisclosure>?=null,val extractionCoverage: ExtractionCoverage?=null,val conversationId: String?=null,val relationId: String?=null,val sourceParentId: String?=null,val contextNotes:List<PublicContextNote>?=null,val publicRelations:List<PublicRelation>?=null) {
    // Identity depends only on immutable constructor fields. Keep this derived
    // value with its existing record, not in another retained URL/history bank.
    // Transient also keeps Gson captures, exports and old reader files identical.
    @Transient @Volatile private var cachedIdentity:String?=null
    val identityKey:String get()=cachedIdentity ?: (platform.id+":"+ContentIdentity.key(platform,canonicalUrl)).also {cachedIdentity=it}
}
/** An archive-only recovery is not a live edition paired with an older version. */
data class ArchiveRecovery(val provider: String,val capturedAt: Long,val archiveUrl: String)
/** Archive artwork stays inside its replay origin while post identity remains shareable. */
val Post.contentSourceUrl: String get()=recoveredArchive?.archiveUrl ?: provenance?.takeIf {
    platform==Platform.YOUTUBE&&it.source.role==SourceRole.CACHE&&it.source.frontend=="youtube-archive"&&YouTubeShareEvidence.archive(this,it.finalUrl)
}?.finalUrl ?: canonicalUrl
/** An immutable data-only capture, paired with a current post rather than another feed entry. */
data class PostSnapshot(val title: String="",val body: String="",val author: String="",val publishedAt: Long?=null,
    val avatar: String?=null,val media: List<Media> = emptyList(),val links: List<String> = emptyList(),val aiDisclosures: List<AiDisclosure>?=null,val relationship: PostRelationship?=null,val contextNotes:List<PublicContextNote>?=null,val publicRelations:List<PublicRelation>?=null) {
    fun asPost(current: Post): Post=current.copy(title=title,body=body,author=author,publishedAt=publishedAt,avatar=avatar,media=media,links=links,replyCount=null,score=null,preview=false,archivedVersion=null,aiDisclosures=aiDisclosures,contextNotes=contextNotes,publicRelations=publicRelations,
        parentId=if(relationship!=null)relationship.parentId else current.parentId,
        conversationId=if(relationship!=null)relationship.conversationId else current.conversationId,
        relationId=if(relationship!=null)relationship.relationId else current.relationId,
        sourceParentId=if(relationship!=null)relationship.sourceParentId else current.sourceParentId,
        extractionCoverage=relationship?.parentCoverage?.let {ExtractionCoverage(text=if(body.isNotBlank()||title.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,attachments=if(media.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,replyParent=it)})
    companion object {fun from(post: Post)=PostSnapshot(post.title,post.body,post.author,post.publishedAt,post.avatar,post.media,post.links.orEmpty(),post.aiDisclosures,
        if(post.parentId!=null||post.conversationId!=null||post.relationId!=null||post.sourceParentId!=null||post.extractionCoverage?.replyParent==ExtractionStatus.UNAVAILABLE)
            PostRelationship(post.parentId,post.conversationId,post.relationId,post.sourceParentId,post.extractionCoverage?.replyParent)else null,post.contextNotes,post.publicRelations)}
}
/** Null snapshot relationship means a legacy capture did not record it. */
data class PostRelationship(val parentId: String?=null,val conversationId: String?=null,val relationId: String?=null,val sourceParentId: String?=null,val parentCoverage: ExtractionStatus?=null)
data class ArchivedVersion(val provider: String,val capturedAt: Long,val archiveUrl: String,val snapshot: PostSnapshot,val currentSignature: String="") {
    /** Replay context is kept on the archive for every asset request. Actions use the current post. */
    fun displayPost(current: Post): Post=snapshot.asPost(current).copy(canonicalUrl=archiveUrl,provenance=Provenance(Source(current.platform,"archive",runCatching {java.net.URI(archiveUrl).let {"${it.scheme}://${it.host}"}}.getOrDefault(archiveUrl)),capturedAt,archiveUrl))
}
data class Profile(val target: Target, val name: String, val description: String = "", val avatar: String? = null, val followers: Long? = null, val links: List<String>? = null,val displayNameSupplied: Boolean=false,val persistentId:String?=null,val verifiedWebsites:List<ProfileWebsite>?=null,val following:Long?=null)
data class Collection(val target: Target, val name: String, val image: String? = null, val detail: String = "",val navigation: Boolean=false)
data class Page(val posts: List<Post>, val next: String? = null, val profile: Profile? = null, val provenance: Provenance? = null, val collections: List<Collection> = emptyList(), val accounts: List<Profile> = emptyList(),val notice: String?=null,val continuationIssue: String?=null,val limitedTimeline:Boolean=false,val syndicationId:String?=null)
data class ProbeResult(val source: Source, val capability: Capability, val status: HealthStatus, val latencyMs: Long, val checkedAt: Long, val tor: Boolean, val detail: String = "",val retryAfterMs: Long?=null)
data class Follow(val id: String, val target: Target, val name: String, val group: String = "", val enabled: Boolean = true, val feedUrl: String? = null, val personId: String? = null, val personName: String? = null)
data class CacheEntry(val key: String, val bytes: Long, val createdAt: Long, val lastViewedAt: Long?, val saved: Boolean, val active: Boolean = false)
data class FilterDecision(val blocked: Boolean, val reason: String = "", val rewrittenUrl: String? = null)
data class NetworkPolicy(val tor: Boolean = false, val socksPort: Int = 0, val ready: Boolean = false)
enum class NetworkRoute { DEFAULT, TOR }
enum class SearchProvider(val id: String,val label: String,val frontend: String?,val root: String?) {
    BING("bing","Bing RSS","bing-rss","https://www.bing.com"),
    DUCKDUCKGO("duckduckgo","DuckDuckGo","duckduckgo","https://html.duckduckgo.com"),
    DARWIN("darwin","Darwin search routes",null,null);
    val torOnly get()=frontend!=null
}
class SourceFailure(val status: HealthStatus, message: String,val retryAfterMs: Long?=null,val httpStatus: Int?=null) : Exception(message)

interface DataAdapter {
    val source: Source
    val capabilities: Set<Capability>
    /** feed() can expose a cheaper finite representation than fetch(). */
    val separateFeedRepresentation:Boolean get()=false
    /** Some data paths serve only particular target forms or an explicitly scoped account. */
    fun accepts(target: Target): Boolean = target.platform==source.platform
    suspend fun fetch(target: Target, cursor: String? = null): Page
    /** Selected-source continuation only. Partials are checkpointed discovery
     * deltas, not completed pages or fabricated post bodies. The caller owns
     * all work and can apply backpressure or cancel the complete operation. */
    suspend fun searchUpdates(target: Target,cursor: String,onPartial: suspend (Page)->Unit): Page = fetch(target,cursor)
    suspend fun profile(target: Target): Page = fetch(target)
    suspend fun comments(target: Target,cursor: String?=null): Page = fetch(target,cursor)
    /** Independent directory discovery; it must not consume a post-search cursor. */
    suspend fun directory(target: Target, cursor: String? = null): Page =
        throw SourceFailure(HealthStatus.UNSUPPORTED,"This source does not expose a public directory")
    suspend fun feed(target: Target): Page
    fun sentinel(capability: Capability): Target
}
