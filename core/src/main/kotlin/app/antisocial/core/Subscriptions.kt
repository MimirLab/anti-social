package app.antisocial.core

import org.jsoup.Jsoup
import java.net.URI
import java.text.Normalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class Subscription(val id: String,val name: String,val accounts: List<Follow>)
data class AccountCandidate(val target: Target,val evidence: String,val kind:AccountLinkKind=AccountLinkKind.PROFILE,val sourceUrl:String?=null,val checkedAt:Long?=null)
/** A public identity label is a fallback, separate from the account's own name. */
data class AccountNameHint(val target:Target,val name:String,val kind:AccountLinkKind,val sourceUrl:String,val checkedAt:Long,val identityGroup:String?=null)
data class AccountDiscovery(val name: String,val candidates: List<AccountCandidate>,val notice: String?=null,val complete:Boolean=true,val nameHints:List<AccountNameHint> = emptyList(),val publicAccounts:List<PublicAccount>?=null)
data class PostGroup(val posts: List<Post>) { val primary get()=posts.first() }

/** Local, explicitly linked identities. A matching handle is never identity evidence. */
object Subscriptions {
    fun id(follow: Follow)=follow.personId?.takeIf {it.isNotBlank()} ?: follow.id
    fun bundles(follows: List<Follow>)=follows.groupBy(::id).map {(id,accounts)->
        Subscription(id,accounts.firstNotNullOfOrNull {it.personName?.takeIf(String::isNotBlank)} ?: accounts.first().name,accounts)
    }.sortedBy {it.name.lowercase()}
    fun urls(text: String)=Regex("https://[^\\s<>\"']+",RegexOption.IGNORE_CASE).findAll(text).map {it.value.trimEnd('.',',',')',']','}',';')}.take(24).toList()
    fun profileLinks(html: String,base: String): List<String> {
        val page=Jsoup.parse(html,base)
        val area=page.selectFirst(".profile-card,.profile-header,.profile-info,.user-info,.user-profile,.profile-about,header.profile")
        return area?.select("a[href]")?.mapNotNull {httpsLink(it.absUrl("href"))}?.distinct()?.take(24).orEmpty()
    }
    fun publicHttps(url: String)=runCatching {val u=URI(url);u.scheme=="https"&&!u.host.isNullOrBlank()&&u.userInfo==null}.getOrDefault(false)
    /** Old profile fields often still contain http URLs. Try only their HTTPS form; never make an insecure request. */
    fun httpsLink(url: String): String?=url.replaceFirst(Regex("^http://",RegexOption.IGNORE_CASE),"https://").takeIf(::publicHttps)
    fun candidates(links: List<String>,routes: DarwinRoutes,evidence: String,discoveryRouting:Boolean=false)=links.asSequence().mapNotNull(::httpsLink).mapNotNull {routes.recognize(it)}
        .filter {(ReaderScope.isFeedSource(it.platform)||discoveryRouting&&it.platform==Platform.YOUTUBE)&&it.kind==TargetKind.PROFILE&&PublicSearchAdapter.profileAllowed(it)}.distinctBy {if(it.platform==Platform.YOUTUBE)it.canonicalUrl.trimEnd('/')else it.canonicalUrl.lowercase().trimEnd('/')}.take(20).map {AccountCandidate(it,evidence)}.toList()
    /** One explicitly linked website, no recursive crawl, embedded assets or scripts. */
    fun websiteLinks(html: String,base: String): List<String> {
        val page=Jsoup.parse(html,base)
        val links=page.select("a[href]").take(2000).map {it.absUrl("href")}.toMutableList()
        page.select("script[type=application/ld+json]").take(8).forEach {node->
            // Only sameAs declarations are identity links; article/post URLs are not.
            runCatching {com.google.gson.JsonParser.parseString(node.data())}.getOrNull()?.let {json->
                fun read(value: com.google.gson.JsonElement,depth: Int=0) {
                    if(depth>6)return
                    if(value.isJsonArray)value.asJsonArray.take(30).forEach {read(it,depth+1)}
                    if(value.isJsonObject){val o=value.asJsonObject;o["sameAs"]?.let {same->if(same.isJsonArray)same.asJsonArray.take(24).filter {it.isJsonPrimitive}.forEach {links+=it.asString}else if(same.isJsonPrimitive)links+=same.asString};o["@graph"]?.let {read(it,depth+1)}}
                };read(json)
            }
        }
        return links.mapNotNull(::httpsLink).distinct().take(2000)
    }
}

/** Content matching never establishes account ownership. Explicit local links only
 * lower the text threshold; public copies otherwise need stronger corroboration. */
object CrossPosts {
    const val MAX_EDITIONS=64
    const val MAX_REPEAT_WINDOW=48L*60*60*1000
    const val MAX_COPY_WINDOW=30L*24*60*60*1000
    private const val WINDOW=48L*60*60*1000
    private const val UNLINKED_WINDOW=6L*60*60*1000
    private const val MAX_TEXT=16_384
    private const val MAX_CANDIDATES=128
    // Punctuation around a number can reverse its claim. Keep operators, signs,
    // currency, decimal/group separators and percentages as part of the token.
    private val words=Regex("(?:[<>!]=?|[≤≥≠≈=])?\\s*(?:[+\\-−\\p{Sc}]\\s*){0,3}\\p{N}+(?:[.,]\\p{N}+)*(?:\\s*[%‰\\p{Sc}])?|[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)?")
    private val tokenWhitespace=Regex("[\\p{Z}\\s]+")
    private val meaningfulSymbols=Regex("[\\p{So}\\p{Sm}\\p{Sc}\\p{Sk}]")
    private val urlPattern=Regex("https://[^\\s<>\"']+",RegexOption.IGNORE_CASE)
    private val invisible=Regex("[\u00ad\u200b\u200e\u200f\u202a-\u202e\u2060\u2066-\u2069\ufeff]")
    private val trailingTags=Regex("(?:\\s+#[\\p{L}\\p{N}_]+){1,8}\\s*$")
    private val negations=setOf("no","not","never","without","cannot","can't","don't","doesn't","didn't","won't","wouldn't","shouldn't","isn't","aren't","wasn't","weren't","hasn't","haven't","hadn't","mustn't","couldn't")
    private val opaqueLinkHosts=setOf("bit.ly","bitly.com","tinyurl.com","t.co","lnkd.in","go.nasa.gov")
    private val opaqueLinkPath=Regex("/[A-Za-z0-9_-]{3,80}/?")
    internal fun opaqueLink(value:String)=value.toHttpUrlOrNull()?.let {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.host in opaqueLinkHosts&&it.query==null&&opaqueLinkPath.matches(it.encodedPath)}==true
    internal fun onlyOpaqueOrOriginalLinks(post:Post):Boolean {
        return editorialLinks(post).all(::opaqueLink)
    }
    internal fun editorialLinks(post:Post):Set<String> {
        val literal=if(post.links.isNullOrEmpty())emptySet()else PostPresentation.destinationLabels(PostPresentation.content(post)).mapNotNull(::normalizedUrl).toSet()
        val enclosures=enclosureLinks(post,literal)
        return PostLinks.urls(post).filterNot {linksToOriginal(it,post)||normalizedUrl(it) in enclosures||escapedEntityTag(post,it)}.toSet()
    }
    /** The bridge's hashtag renderer can interpret the # inside an escaped
     * apostrophe as a tag. Only a tag matching that exact numeric entity is a
     * renderer artifact; ordinary author hashtags and other links stay intact. */
    private fun escapedEntityTag(post:Post,value:String):Boolean {
        if(post.platform!=Platform.INSTAGRAM||post.provenance?.source?.frontend!="rss-bridge")return false
        val url=value.toHttpUrlOrNull() ?: return false
        if(!url.isHttps||url.host !in setOf("instagram.com","www.instagram.com")||url.query!=null||url.fragment!=null)return false
        val tag=Regex("/explore/tags/([0-9]{1,7})/?").matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return false
        return post.body.contains("&#$tag;")&&!Regex("(?<!&)#${Regex.escape(tag)}\\b").containsMatchIn(post.body)
    }
    private data class Features(val post: Post,val owner: String?,val text: String,val base: String,val tokens: List<String>,
        val numbers: List<String>,val negatives: List<String>,val symbols: String,val urls: Set<String>,val destinations: Set<String>,val media: Set<String>,val truncated: Boolean,
        val validOriginal:Boolean,val canonical:String?,val account:String?,val curator:String?,val clips:Set<String>,val suppliedBy:Set<String>,val decoration:DecoratedPostCopies.Prepared?) {
    }
    private fun uri(value: String)=runCatching {URI(value)}.getOrNull()?.takeIf {it.scheme=="https"&&it.host!=null&&it.userInfo==null}
    private fun origin(value: URI)=value.host.lowercase()+":"+(if(value.port==-1)443 else value.port)
    fun originalKey(post: Post)=post.identityKey
    /** An author-bearing original URL or qualified source declaration identifies
     * which public account posted it.
     * Display names, feed membership, opaque post IDs and matching handles on
     * other servers never supply this proof. */
    fun originalAccount(post: Post): String? {
        val url=uri(post.canonicalUrl) ?: return null
        if(post.platform !in ExpandedRoutes.federated&&SocialLinkHosts.platform(post.canonicalUrl)!=post.platform)return null
        PostAuthorAccounts.target(post)?.let {return post.platform.id+":"+it.identity.lowercase(java.util.Locale.ROOT)}
        declaredCommentAccount(post)?.let {return it}
        val parts=url.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        val username=Regex("[A-Za-z0-9_.-]{1,100}")
        val token=Regex("[A-Za-z0-9_-]{1,200}")
        fun name(raw: String?)=raw?.removePrefix("@")?.takeIf {it.matches(username)}
        val account=when(post.platform) {
            Platform.X->if(parts.size>=3&&parts[1] in setOf("status","statuses")&&parts[2].matches(Regex("[0-9]{1,40}"))&&parts[0] !in setOf("i","intent","search"))name(parts[0])else null
            Platform.BLUESKY->if(parts.size==4&&parts[0]=="profile"&&parts[2]=="post"&&parts[3].matches(token))parts[1].takeIf {it.isNotBlank()&&it.length<=256&&it.none(Char::isISOControl)}else null
            Platform.THREADS->if(parts.size>=3&&parts[0].startsWith('@')&&parts[1] in setOf("post","t")&&parts[2].matches(token))name(parts[0])else null
            Platform.TIKTOK->if(parts.size>=3&&parts[0].startsWith('@')&&parts[1] in setOf("video","photo")&&parts[2].matches(token))name(parts[0])else null
            Platform.MASTODON->when {
                parts.size==2&&parts[0].startsWith('@')&&parts[1].matches(token)->name(parts[0])
                parts.size==4&&parts[0]=="users"&&parts[2]=="statuses"&&parts[3].matches(token)->name(parts[1])
                else->null
            }
            Platform.PIXELFED->if(parts.size==3&&parts[0]=="p"&&parts[2].matches(token))name(parts[1])else null
            Platform.TRUTH_SOCIAL->if(parts.size==2&&parts[0].startsWith('@')&&parts[1].matches(token))name(parts[0])else null
            Platform.GAB->if(parts.size==3&&parts[1] in setOf("posts","statuses")&&parts[2].matches(token))name(parts[0])else null
            Platform.FACEBOOK->if(parts.size==3&&parts[1]=="posts"&&parts[2].matches(token))name(parts[0])else null
            Platform.TELEGRAM->parts.filterNot {it=="s"}.let {if(it.size==2&&it[1].all(Char::isDigit)&&it[0] !in setOf("c","share","joinchat","proxy","socks","login"))name(it[0])else null}
            Platform.TUMBLR->{
                val blog=url.host.lowercase().takeIf {it.endsWith(".tumblr.com")&&it !in setOf("www.tumblr.com","assets.tumblr.com")}?.removeSuffix(".tumblr.com")
                when {
                    blog!=null&&parts.size>=2&&parts[0]=="post"&&parts[1].all(Char::isDigit)->name(blog)
                    parts.size>=2&&parts[1].all(Char::isDigit)->name(parts[0])
                    else->null
                }
            }
            else->null
        } ?: return null
        val server=if(post.platform in ExpandedRoutes.federated)origin(url)+":"else ""
        val identity=if(post.platform==Platform.BLUESKY&&account.startsWith("did:"))account else account.lowercase(java.util.Locale.ROOT)
        return post.platform.id+":"+server+identity
    }
    /** A Reddit profile feed explicitly names its author. Keep this narrower
     * than a display-name guess: both the profile acquisition path and the /u/
     * declaration must agree, and the original must identify an actual comment. */
    private fun declaredCommentAccount(post:Post):String? {
        if(!ContentIdentity.key(post.platform,post.canonicalUrl).startsWith("comment:"))return null
        return declaredRedditProfileAuthor(post)
    }
    internal fun declaredRedditProfileAuthor(post:Post):String? {
        if(post.platform!=Platform.REDDIT||post.parentId!=null)return null
        if(SocialLinkHosts.platform(post.canonicalUrl)!=Platform.REDDIT)return null
        val author=Regex("^/?u/([A-Za-z0-9_-]{1,100})$").matchEntire(post.author)?.groupValues?.get(1) ?: return null
        val p=post.provenance ?: return null
        if(p.source.platform!=Platform.REDDIT||p.source.frontend !in setOf("original","redlib","libreddit","teddit","rss-bridge","rsshub"))return null
        val url=uri(p.finalUrl) ?: return null
        if(p.source.role==SourceRole.ORIGINAL) {if(SocialLinkHosts.platform(p.finalUrl)!=Platform.REDDIT)return null}
        else if(!DarwinRoutes.matches(url,p.source.root))return null
        val supplied=Regex("^/(?:user|u)/([A-Za-z0-9_-]{1,100})(?:/(?:[.]rss|comments[.]json|overview[.]json))?/?$")
            .matchEntire(url.path.orEmpty())?.groupValues?.get(1) ?: return null
        return author.takeIf {it.equals(supplied,true)}?.let {Platform.REDDIT.id+":"+it.lowercase(java.util.Locale.ROOT)}
    }
    /** Choose a real discussion root using supplied public reply counts only.
     * An unknown count never outranks a known count; ties favor the original. */
    fun representative(posts: List<Post>,platform: Platform?=null): Post?=posts.asSequence().filter {platform==null||it.platform==platform}
        .sortedWith(compareByDescending<Post> {it.replyCount?.takeIf {count->count>=0} ?: -1L}
            .thenBy {it.publishedAt?.takeIf {time->time>0} ?: Long.MAX_VALUE}.thenBy(::originalKey)).firstOrNull()
    private fun curator(post: Post): String? {
        if(post.platform!=Platform.X)return null
        val provenance=post.provenance ?: return null;val source=provenance.source
        if(source.platform!=Platform.X||source.frontend!="nitter"||source.role!=SourceRole.ALTERNATE)return null
        val root=source.root.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.query==null&&it.fragment==null} ?: return null
        val final=provenance.finalUrl.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.query==null&&it.fragment==null} ?: return null
        if(root.host!=final.host||root.port!=final.port)return null
        val base=root.encodedPath.trimEnd('/');val path=final.encodedPath
        if(!path.startsWith("$base/"))return null
        val parts=path.removePrefix("$base/").trimEnd('/').split('/')
        if(parts.size!=2||parts[1]!="rss"||!parts[0].matches(Regex("[A-Za-z0-9_]{1,15}")))return null
        val handle=parts[0]
        val key="twitter:"+handle.lowercase(java.util.Locale.ROOT)
        val own=originalAccount(post)==key
        val repost=PostPresentation.text(post).attribution?.equals("Reposted by @$handle",true)==true
        return key.takeIf {own||repost}
    }
    private fun repeatIdentity(a: Post,b: Post): Boolean {
        if(a.platform!=b.platform)return false
        val owner=originalAccount(a)
        return owner!=null&&owner==originalAccount(b)||(curator(a) ?: owner)?.let {it==(curator(b) ?: originalAccount(b))}==true
    }
    private val quotedSpeaker=Regex("^\\s*\\.?@([A-Za-z0-9_]{1,15})\\s*:\\s*[\"“]")
    private val subjectNoise=setOf("about","after","again","before","being","could","every","first","going","having","other","people","really","should","their","there","these","thing","things","those","through","today","together","under","very","where","which","while","would","public","update","updates","video")
    private fun speaker(post: Post)=quotedSpeaker.find(PostPresentation.content(post))?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)
    /** Related coverage is a collection, not an assertion that two claims are
     * interchangeable. The same publisher/verified curator, quoted subject,
     * close scene, meaningful subject wording and short interval must all agree.
     * Every different sentence remains separately attributed in the composition. */
    private fun relatedVisualPair(a: Post,b: Post): Boolean {
        if(a.platform!=Platform.X||b.platform!=Platform.X||a.preview||b.preview||a.parentId!=null||b.parentId!=null)return false
        val subject=speaker(a) ?: return false
        if(subject!=speaker(b))return false
        if(!repeatIdentity(a,b)||!realOriginal(a)||!realOriginal(b))return false
        val left=a.publishedAt?.takeIf {it>0} ?: return false;val right=b.publishedAt?.takeIf {it>0} ?: return false
        val distance=if(left>=right)left.toULong()-right.toULong()else right.toULong()-left.toULong()
        if(distance>6uL*60uL*60uL*1000uL)return false
        fun frame(post: Post)=post.media.firstOrNull {it.type=="image"&&it.url!=post.avatar}?.visualFingerprint
        if(!VisualFrames.same(frame(a),frame(b),nearbyFrame=true))return false
        fun subjects(post: Post): Set<String> {
            val text=Regex("@[A-Za-z0-9_]+|https://[^\\s]+").replace(PostPresentation.content(post)," ")
            return tokens(decoded(text)).filter {it.length>=6&&it.none(Char::isDigit)&&it !in subjectNoise&&it !in negations}.toSet()
        }
        return subjects(a).intersect(subjects(b)).isNotEmpty()&&CrossPostFusion.relatedCompatible(a,b)
    }
    fun relatedVisualUpdates(posts: List<Post>): Boolean=posts.size in 2..MAX_EDITIONS&&posts.map(::originalKey).distinct().size==posts.size&&
        posts.indices.all {i->(i+1 until posts.size).all {j->relatedVisualPair(posts[i],posts[j])}}
    private fun clipIds(post: Post)=post.media.mapNotNull {media->
        val url=media.url.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.host in setOf("pbs.twimg.com","video.twimg.com","video-s.twimg.com")} ?: return@mapNotNull null
        val match=Regex("^/(amplify_video|ext_tw_video)(?:_thumb)?/([0-9]{1,40})/").find(url.encodedPath) ?: return@mapNotNull null
        match.groupValues[1]+":"+match.groupValues[2]
    }.toSet()
    /** A short literal quote can be one excerpt of the same supplied video.
     * Generic images, avatars, shared words and merely similar claims cannot
     * enter this path. Keep all complete original captions for presentation. */
    private fun videoExcerpt(a: Post,b: Post): Boolean {
        if(clipIds(a).intersect(clipIds(b)).isEmpty())return false
        val quotes=Regex("[\"“]([^\"“”]+)[\"”]",RegexOption.DOT_MATCHES_ALL)
        fun normalize(value: String)=decoded(value).lowercase(java.util.Locale.ROOT).replace(Regex("[\\p{Z}\\s]+")," ").trim()
        val left=PostPresentation.content(a);val right=PostPresentation.content(b)
        val short=if(left.length<=right.length)left else right;val long=if(left.length<=right.length)right else left
        val normalizedLong=normalize(long)
        return quotes.findAll(short).take(16).any {match->
            val literal=normalize(match.groupValues[1]);val quoteTokens=tokens(literal)
            val outside=short.removeRange(match.range).replace(Regex("@[A-Za-z0-9_]{1,15}")," ")
            literal.length>=50&&quoteTokens.size>=10&&quoteTokens.toSet().size>=8&&tokens(outside).isEmpty()&&normalizedLong.indexOf(literal).let {start->
                start>=0&&normalizedLong.indexOf(literal,start+1)<0
            }
        }
    }
    /** Select an actual full caption for a proven same-publisher clip collection.
     * This does not manufacture a shared passage or discard the short original. */
    fun clipQuotationCaption(posts:List<Post>):Post? {
        if(!sameSourceRepeats(posts))return null
        return posts.sortedByDescending {PostPresentation.content(it).length}.firstOrNull {full->
            posts.all {it==full||videoExcerpt(full,it)}
        }
    }
    /** Shared saved-copy validation: no local subscription database is needed to
     * verify repeated originals, and no original or discussion URL is rewritten. */
    fun sameSourceRepeats(posts: List<Post>,sameMedia: (Post,Post)->Boolean={_,_->false}): Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        if(AnnouncementEditions.sameBroadcast(posts))return true
        val prepared=posts.map {features(it,emptyMap(),emptyMap())}
        return prepared.indices.all {i->(i+1 until prepared.size).all {j->repeatMatch(prepared[i],prepared[j],sameMedia)}}
    }
    /** Content evidence, not account ownership. Every supplied original and its
     * discussion remains distinct; the UI must retain each publisher attribution. */
    fun sameContentCopies(posts: List<Post>,sameMedia: (Post,Post)->Boolean={_,_->false}): Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        if(RedditSupplements.collection(posts)||galleryCollection(posts,sameMedia)||imageEchoCollection(posts,sameMedia))return true
        val prepared=posts.map {features(it,emptyMap(),emptyMap())}
        return prepared.indices.all {i->(i+1 until prepared.size).all {j->copyMatch(prepared[i],prepared[j],sameMedia)}}
    }
    /** Corroborated literal editions, not inferred ownership or equal media.
     * Saved manifests can replay the same bounded proof without a network read. */
    fun corroboratedPassageCollection(posts:List<Post>,sameMedia:(Post,Post)->Boolean={_,_->false}):Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        val rows=posts.map {features(it,emptyMap(),emptyMap())}
        return rows.indices.all {i->(i+1 until rows.size).all {j->corroboratedPassage(rows[i],rows[j],sameMedia)}}&&AnnouncementEditions.composePassages(posts)!=null
    }
    /** Strict internal wording edits only; shared starts and endings exclude
     * an unlabeled excerpt, and the existing factual guards remain mandatory. */
    fun literalWordingCollection(posts:List<Post>):Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        val rows=posts.map {features(it,emptyMap(),emptyMap())}
        return rows.indices.all {i->(i+1 until rows.size).all {j->wordingEdit(rows[i],rows[j])}}&&CaptionWeave.composeWordingEdits(posts)!=null
    }
    fun resourceAnnouncementCollection(posts:List<Post>):Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        val rows=posts.map {features(it,emptyMap(),emptyMap())}
        return rows.indices.all {i->(i+1 until rows.size).all {j->resourceAnnouncement(rows[i],rows[j])}}&&ResourceAnnouncements.compose(posts)!=null
    }
    private fun resourceAnnouncement(a:Features,b:Features):Boolean {
        if(!a.validOriginal||!b.validOriginal||a.truncated||b.truncated||!referenceIntegrity(a.post)||!referenceIntegrity(b.post))return false
        val x=a.post.publishedAt?.takeIf {it>0} ?: return false;val y=b.post.publishedAt?.takeIf {it>0} ?: return false
        if(kotlin.math.abs(x-y)>600_000||a.numbers!=b.numbers||a.negatives!=b.negatives||a.symbols!=b.symbols||a.destinations!=b.destinations)return false
        fun questions(post:Post)=PostPresentation.proseWithoutLinks(PostPresentation.content(post)).count {it=='?'}
        return questions(a.post)==questions(b.post)&&CrossPostFusion.neutralCopyFraming(a.post)&&CrossPostFusion.neutralCopyFraming(b.post)&&
            ResourceAnnouncements.key(a.post)?.let {it==ResourceAnnouncements.key(b.post)}==true&&ResourceAnnouncements.compose(listOf(a.post,b.post))!=null
    }
    private fun referenceIntegrity(post:Post):Boolean = PostPresentation.destinationLabels(PostPresentation.content(post)).none {
        it.startsWith("http://",true)||it.contains("...")||it.contains('…')
    }
    private fun wordingEdit(a:Features,b:Features):Boolean {
        if(!a.validOriginal||!b.validOriginal||a.post.preview||b.post.preview||a.post.parentId!=null||b.post.parentId!=null||a.truncated||b.truncated)return false
        val x=a.post.publishedAt?.takeIf {it>0} ?: return false;val y=b.post.publishedAt?.takeIf {it>0} ?: return false
        return kotlin.math.abs(x-y)<=600_000&&a.destinations==b.destinations&&a.numbers==b.numbers&&a.negatives==b.negatives&&a.symbols==b.symbols&&
            a.tokens.take(3)==b.tokens.take(3)&&a.tokens.takeLast(3)==b.tokens.takeLast(3)&&referenceIntegrity(a.post)&&referenceIntegrity(b.post)&&
            CrossPostFusion.neutralCopyFraming(a.post)&&CrossPostFusion.neutralCopyFraming(b.post)&&CaptionWeave.composeWordingEdits(listOf(a.post,b.post))!=null
    }
    private fun corroboratedPassage(a:Features,b:Features,sameMedia:(Post,Post)->Boolean):Boolean {
        if(!a.validOriginal||!b.validOriginal||a.post.preview||b.post.preview||a.post.parentId!=null||b.post.parentId!=null||a.truncated||b.truncated||!referenceIntegrity(a.post)||!referenceIntegrity(b.post))return false
        val x=a.post.publishedAt?.takeIf {it>0} ?: return false;val y=b.post.publishedAt?.takeIf {it>0} ?: return false
        if(kotlin.math.abs(x-y)>600_000||a.numbers!=b.numbers||a.negatives!=b.negatives||a.symbols!=b.symbols)return false
        fun concrete(row:Features)=row.destinations.filterNot {opaqueLink(it)||nitterMentionLink(row.post,it)}.toSet()
        val left=concrete(a);val right=concrete(b)
        if(left.isNotEmpty()&&right.isNotEmpty()&&left!=right)return false
        return CrossPostFusion.neutralCopyFraming(a.post)&&CrossPostFusion.neutralCopyFraming(b.post)&&
            sameAnnouncementArtwork(a.post,b.post,sameMedia,nearby=true)&&AnnouncementEditions.composePassages(listOf(a.post,b.post))!=null
    }
    /** A complete gallery can prove that separately published, captionless
     * pictures are already present. Compare actual images, never their common
     * template, account name or publication time alone. No transitive chains. */
    fun galleryCollection(posts: List<Post>,sameMedia: (Post,Post)->Boolean={_,_->false}): Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        val anchors=posts.filter(::galleryAnchor)
        if(anchors.any {anchor->posts.all {galleryMember(anchor,it,posts,sameMedia)}})return true
        // Independent validation for saves/composition has no local follow
        // database. Union only pairwise proven, same-caption gallery editions
        // with an actually shared picture. This does not trust a shared slogan,
        // complementary disjoint galleries or a transitive stock-photo chain.
        fun overlap(a:Post,b:Post)=GalleryCaptionCopies.contains(a,b)&&GalleryCaptionCopies.contains(b,a)&&
            b.media.any {image->galleryContains(a,b.copy(media=listOf(image)),sameMedia)}
        for(anchor in anchors) {
            val peers=anchors.filter {it===anchor||overlap(anchor,it)}
            if(peers.size<2||peers.indices.any {i->(i+1 until peers.size).any {j->!overlap(peers[i],peers[j])}})continue
            val images=peers.flatMap {it.media}.distinctBy {it.url}
            if(images.size>48)continue
            if(peers.any {edition->val proof=edition.copy(media=images);posts.all {galleryMember(proof,it,posts,sameMedia)}})return true
        }
        return false
    }
    private fun imageEcho(post:Post):Boolean {
        if(post.body.length.toLong()+post.title.length>MAX_TEXT||post.media.size!=1||post.media.single().type!="image")return false
        val text=decoded(PostPresentation.content(post))
        return meaningfulSymbols.replace(text,"").replace(Regex("[\\p{Z}\\s\\uFE0E\\uFE0F\\u200D]"),"").isEmpty()&&
            PostLinks.urls(post).all {url->normalizedUrl(url)?.let {it==normalizedUrl(post.canonicalUrl)||!destination(it)}==true}
    }
    /** A picture-only reshare adds no new prose. Keep its publisher, original
     * and discussion, and attach only to one unambiguous complete caption.
     * Repeated identical flags are allowed, different symbols are not. */
    fun imageEchoCollection(posts:List<Post>,sameMedia:(Post,Post)->Boolean={_,_->false}):Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.map(::originalKey).distinct().size!=posts.size)return false
        if(posts.any {it.body.length.toLong()+it.title.length>MAX_TEXT||it.preview||it.parentId!=null||!realOriginal(it)||it.media.size!=1||it.media.single().type!="image"||it.media.single().url==it.avatar||it.publishedAt==null||it.publishedAt<=0})return false
        val times=posts.map {it.publishedAt!!};if(times.max()-times.min()>UNLINKED_WINDOW)return false
        val echoes=posts.filter(::imageEcho);val originals=posts.filterNot(::imageEcho)
        if(echoes.isEmpty()||originals.isEmpty()||originals.any {tokens(decoded(PostPresentation.content(it))).size<3})return false
        val caption=PostPresentation.comparableContent(originals.first())
        if(originals.any {PostPresentation.comparableContent(it)!=caption})return false
        fun symbols(p:Post)=meaningfulSymbols.findAll(decoded(PostPresentation.content(p))).joinToString(""){it.value}
        return echoes.all {echo->originals.any {original->
            val a=symbols(echo);val b=symbols(original)
            val compatible=a.isEmpty()||a==b||b.isNotEmpty()&&a.length%b.length==0&&a==b.repeat(a.length/b.length)
            compatible&&sameImages(echo,original,sameMedia)
        }}
    }
    private fun collectImageEchoes(groups:List<PostGroup>,sameMedia:(Post,Post)->Boolean):List<PostGroup> {
        val echoes=groups.indices.filter {groups[it].posts.all(::imageEcho)}
        if(echoes.size>MAX_CANDIDATES)return groups
        val targets=groups.indices.filter {it !in echoes}
        val assignments=echoes.mapNotNull {echo->targets.filter {target->imageEchoCollection(groups[target].posts+groups[echo].posts,sameMedia)}.singleOrNull()?.let {echo to it}}.toMap()
        val order=compareBy<Post> {it.publishedAt ?: Long.MAX_VALUE}.thenBy(::originalKey)
        return groups.indices.filter {it !in assignments}.map {index->
            val additions=assignments.filterValues {it==index}.keys.flatMap {groups[it].posts}
            val all=groups[index].posts+additions
            if(additions.isNotEmpty()&&imageEchoCollection(all,sameMedia))PostGroup(all.sortedWith(order))else groups[index]
        }+assignments.keys.filter {echo->
            val target=assignments.getValue(echo);!imageEchoCollection(groups[target].posts+assignments.filterValues {it==target}.keys.flatMap {groups[it].posts},sameMedia)
        }.map {groups[it]}
    }
    private fun galleryMember(anchor:Post,post:Post,scope:List<Post>,sameMedia:(Post,Post)->Boolean):Boolean {
        if(post.parentId==null)return galleryContains(anchor,post,sameMedia)
        // A publisher may deliver the remaining gallery pictures as a reply
        // chain. A later conversation fetch must not split that collected card.
        // The actual parent must be another proven same-account gallery
        // original. A supplied caption still needs the ordinary literal gallery
        // relation below; an unrelated reply cannot borrow its parent's prose.
        val account=originalAccount(post) ?: return false
        val path=mutableListOf<Post>();val seen=hashSetOf<String>();var current=post
        while(current.parentId!=null) {
            if(!seen.add(originalKey(current))||path.size>=MAX_EDITIONS)return false
            path+=current
            current=scope.singleOrNull {it.platform==post.platform&&it.id==current.parentId&&originalAccount(it)==account} ?: return false
        }
        // Every generation must fit the same frozen gallery. A path of shared
        // pictures is never permission to bridge different announcements.
        return galleryContains(anchor,current,sameMedia)&&path.all {galleryContains(anchor,it.copy(parentId=null),sameMedia,parentQualified=true)}
    }
    private fun galleryAnchor(post: Post)=post.media.size in 2..12&&post.media.all {it.type=="image"&&it.url!=post.avatar}&&
        tokens(decoded(PostPresentation.content(post))).size>=8
    internal fun galleryContains(anchor: Post,post: Post,sameMedia: (Post,Post)->Boolean,parentQualified:Boolean=false): Boolean {
        if(anchor.preview||post.preview||anchor.parentId!=null||post.parentId!=null)return false
        val a=anchor.publishedAt?.takeIf {it>0} ?: return false;val b=post.publishedAt?.takeIf {it>0} ?: return false
        val distance=if(a>=b)a.toULong()-b.toULong()else b.toULong()-a.toULong()
        if(distance>UNLINKED_WINDOW.toULong()||post.media.isEmpty()||post.media.size>anchor.media.size||post.media.any {it.type!="image"||it.url==post.avatar})return false
        // Most feed records are outside this gallery's time/media window. Keep
        // the same mandatory identity proof after those cheap exclusions.
        if(!realOriginal(anchor)||!realOriginal(post))return false
        // Only layout pointers may differ in an otherwise identical caption.
        // Numbers, negation, flags, meaningful symbols and links still matter.
        fun caption(p:Post)=decoded(PostPresentation.content(p)).replace(Regex("[⬇↓↘👇\\uFE0F]+\\s*$"),"").trim()
        val text=caption(post)
        if(text.isNotBlank()) {
            val full=caption(anchor)
            val exact=tokens(text)==tokens(full)&&meaningfulSymbols.findAll(text).map {it.value}.toList()==meaningfulSymbols.findAll(full).map {it.value}.toList()&&
                urlPattern.findAll(text).map {it.value}.toList()==urlPattern.findAll(full).map {it.value}.toList()
            if(!exact&&!GalleryCaptionCopies.contains(anchor,post))return false
        }
        // Low-information pictures cannot establish a post match by themselves.
        // A qualified caption or explicit, already-proven publisher parent can
        // corroborate detailed attachment observations. A captionless picture
        // alone and weak scene hashes never gain this permission.
        val weak=anchor.media.any {it.attachmentFingerprint!=null}||post.media.any {it.attachmentFingerprint!=null}
        val detailQualified=weak&&(text.isNotBlank()&&GalleryCaptionCopies.contains(anchor,post)||parentQualified&&text.isBlank())
        return post.media.all {image->anchor.media.any {other->
            // A complete gallery claims that every picture is already present.
            // Detailed observations outrank a similar template/scene or a
            // looser callback when a face, number or crop has changed.
            val a=if(detailQualified)other.attachmentFingerprint ?: other.visualFingerprint else other.visualFingerprint
            val b=if(detailQualified)image.attachmentFingerprint ?: image.visualFingerprint else image.visualFingerprint
            val attachment=detailQualified&&(other.attachmentFingerprint!=null||image.attachmentFingerprint!=null)
            if(attachment&&(a?.startsWith("f2|")!=true||b?.startsWith("f2|")!=true))false
            else if(a!=null&&b!=null)VisualFrames.sameImage(a,b)
            else sameImages(anchor.copy(media=listOf(other)),post.copy(media=listOf(image)),sameMedia)
        }}
    }
    private fun collectGalleries(groups:List<PostGroup>,sameMedia:(Post,Post)->Boolean):List<PostGroup> {
        val scope=groups.flatMap {it.posts}
        val anchors=scope.filter(::galleryAnchor).sortedWith(compareByDescending<Post> {it.media.size}.thenBy(::originalKey))
        if(anchors.size>MAX_CANDIDATES)return groups
        // Already admitted editions belong to one announcement even when each
        // supplies a different gallery or one rendition has not decoded yet.
        // They must not become competing roots for their own proven children.
        val owners=groups.flatMapIndexed {index,group->group.posts.map {originalKey(it) to index}}.toMap()
        data class Root(val owner:Int,val proofs:List<Post>)
        val roots=mutableListOf<Root>()
        for(anchor in anchors) {
            val owner=owners.getValue(originalKey(anchor))
            if(roots.any {it.owner==owner||it.proofs.any {proof->galleryContains(proof,anchor,sameMedia)}})continue
            val editions=anchors.filter {owners[originalKey(it)]==owner}
            val observed=editions.flatMap {it.media}.distinctBy {it.url}
            // A bounded, private proof projection only. Source records and the
            // presentation inventory retain every original attachment/URL.
            val proofs=if(observed.size<=48)editions.map {it.copy(media=observed)}else editions
            roots+=Root(owner,proofs)
        }
        if(roots.isEmpty())return groups
        val assignments=groups.mapIndexed {owner,group->
            roots.indices.firstOrNull {roots[it].owner==owner} ?: roots.indices.filter {index->
                group.posts.all {post->roots[index].proofs.any {galleryMember(it,post,scope,sameMedia)}}
            }.singleOrNull()
        }
        val joined=linkedMapOf<Int,MutableList<Post>>()
        val unchanged=mutableListOf<PostGroup>()
        groups.forEachIndexed {index,group->
            val root=assignments[index]
            if(root==null)unchanged+=group else joined.getOrPut(root){mutableListOf()}.addAll(group.posts)
        }
        val order=compareBy<Post> {it.publishedAt ?: Long.MAX_VALUE}.thenBy(::originalKey)
        return unchanged+joined.flatMap {(root,posts)->
            if(posts.size<=MAX_EDITIONS)listOf(PostGroup(posts.sortedWith(order)))
            else groups.filterIndexed {index,_->assignments[index]==root}
        }
    }
    private fun own(post: Post,account: Follow): Boolean {
        if(post.platform!=account.target.platform||account.target.kind!=TargetKind.PROFILE||post.parentId!=null||post.preview)return false
        val postUri=uri(post.canonicalUrl) ?: return false
        val accountUri=uri(account.target.canonicalUrl) ?: return false
        if(post.platform in ExpandedRoutes.federated) {
            if(origin(postUri)!=origin(accountUri))return false
        }else if(SocialLinkHosts.platform(post.canonicalUrl)!=post.platform||SocialLinkHosts.platform(account.target.canonicalUrl)!=post.platform)return false
        val parts=postUri.path.orEmpty().trim('/').split('/')
        val profile=accountUri.path.orEmpty().trim('/').split('/')
        val named=profile.firstOrNull().orEmpty().removePrefix("@")
        fun same(a: String?,b: String)=b.isNotBlank()&&a?.removePrefix("@").equals(b,true)
        // A display name is never proof for an opaque post ID.
        return when(post.platform) {
            Platform.INSTAGRAM -> PostAuthorAccounts.target(post)?.identity?.equals(named,true)==true
            Platform.X -> parts.size>=3&&parts[1] in setOf("status","statuses")&&same(parts[0],named)
            Platform.BLUESKY -> parts.size==4&&parts[0]=="profile"&&parts[2]=="post"&&profile.size==2&&same(parts[1],profile[1])
            Platform.THREADS -> parts.size>=3&&parts[1] in setOf("post","t")&&same(parts[0],named)
            Platform.TIKTOK -> parts.size>=3&&parts[1]=="video"&&same(parts[0],named)
            Platform.MASTODON -> parts.size==2&&parts[0].startsWith('@')&&same(parts[0],named)||parts.size==4&&parts[0]=="users"&&parts[2]=="statuses"&&same(parts[1],named)
            Platform.PIXELFED -> parts.size==3&&parts[0]=="p"&&same(parts[1],named)
            Platform.TRUTH_SOCIAL -> parts.size==2&&parts[0].startsWith('@')&&same(parts[0],named)
            Platform.TELEGRAM -> parts.filterNot {it=="s"}.let {it.size==2&&same(it[0],profile.filterNot {p->p=="s"}.firstOrNull().orEmpty())}
            Platform.GAB -> parts.size==3&&parts[1]=="posts"&&same(parts[0],named)
            Platform.FACEBOOK -> parts.size==3&&parts[1]=="posts"&&same(parts[0],runCatching {DarwinRoutes.queryValue(accountUri,"id")}.getOrNull() ?: profile.lastOrNull().orEmpty())
            Platform.TUMBLR -> {
                fun blog(value: URI): String?=value.host.lowercase().takeIf {it.endsWith(".tumblr.com")&&it !in setOf("www.tumblr.com","assets.tumblr.com") }?.removeSuffix(".tumblr.com")
                val expected=blog(accountUri) ?: named.takeIf {it !in setOf("", "blog", "dashboard", "explore")}
                val author=blog(postUri) ?: parts.firstOrNull()?.takeIf {parts.size>=3&&parts[1].all(Char::isDigit)}
                expected!=null&&same(author,expected)
            }
            else -> false
        }
    }
    private fun decoded(value: String): String {
        var result=value
        repeat(2){result=org.jsoup.parser.Parser.unescapeEntities(result,false)}
        return invisible.replace(Normalizer.normalize(result,Normalizer.Form.NFKC),"").replace('’','\'').replace('‘','\'')
    }
    private fun tokens(value: String)=words.findAll(value.lowercase()).map {tokenWhitespace.replace(it.value,"").replace('−','-')}.toList()
    private fun normalizedUrl(value: String)=CrossPostFusion.normalizedUrl(value){normalizeUrl(value)}
    /** Additional link-inventory entries for actual media are transport data.
     * A literal publisher link or a social work URL remains editorial evidence. */
    private fun enclosureLinks(post:Post,literal:Set<String>):Set<String> {
        val supplied=post.links.orEmpty().take(16).toSet()
        if(supplied.isEmpty())return emptySet()
        val media=post.media.take(12).flatMap {listOfNotNull(it.url,it.preview)}
        return supplied.filter {link->media.any {it==link||blueskyEnclosure(it)?.let {key->key==blueskyEnclosure(link)}==true}}.mapNotNull(::normalizedUrl)
            .filter {it !in literal&&(SocialLinkHosts.platform(it)==null||ImageAssetIdentity.key(it)!=null)}.toSet()
    }
    // These are two renditions of the same declared enclosure, not competing
    // article destinations. This classification never suppresses an attachment
    // or transfers pixel/signature validation between the files.
    private fun blueskyEnclosure(value:String):String? {
        val url=value.toHttpUrlOrNull() ?: return null
        if(!url.isHttps||url.host!="cdn.bsky.app"||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||url.fragment!=null)return null
        return Regex("/img/(?:feed_thumbnail|feed_fullsize)/plain/(did:plc:[a-z2-7]{24})/(b[a-z2-7]{30,120})(?:@(?:jpeg|png|webp))?")
            .matchEntire(url.encodedPath)?.let {it.groupValues[1]+"/"+it.groupValues[2]}
    }
    private fun normalizeUrl(value: String): String? {
        val clean=decoded(value).trim()
        val alias=SocialLinkHosts.canonical(clean) ?: clean
        val parsed=alias.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        val builder=parsed.newBuilder().fragment(null)
        parsed.queryParameterNames.filter {it.startsWith("utm_",true)||it.lowercase() in setOf("fbclid","gclid","dclid","igshid","mc_cid","mc_eid")}.forEach(builder::removeAllQueryParameters)
        return builder.build().toString()
    }
    private fun destination(value: String): Boolean {
        if(SocialLinkHosts.needsExpansion(value))return false
        val parsed=value.toHttpUrlOrNull() ?: return false
        val platform=SocialLinkHosts.platform(value)
        if(platform!=null&&!ContentIdentity.key(platform,value).startsWith("post:"))return false
        return parsed.encodedPath!="/"||parsed.querySize>0
    }
    /** Feed enclosures often link back to their own post using another canonical
     * spelling. A trailing slash, social hostname alias or share parameter does
     * not turn that attribution into a competing article destination. Compare
     * platform identities, keeping other posts and federated origins distinct. */
    private fun linksToOriginal(value: String,post: Post): Boolean {
        val source=uri(value) ?: return false;val actual=uri(post.canonicalUrl) ?: return false
        val frontend=post.provenance?.source
        val acquiredAlias=post.platform !in ExpandedRoutes.federated&&frontend?.platform==post.platform&&DarwinRoutes.matches(source,frontend.root)
        val same=if(post.platform in ExpandedRoutes.federated)origin(source)==origin(actual)else SocialLinkHosts.platform(value)==post.platform||acquiredAlias
        return same&&ContentIdentity.key(post.platform,value)==ContentIdentity.key(post.platform,post.canonicalUrl)
    }
    internal fun originalLink(value:String,post:Post)=linksToOriginal(value,post)
    private fun publisherOwner(post: Post,accounts: Map<Platform,List<Pair<String,Follow>>>,memberships: Map<String,Set<String>>):String? {
        val eligible=accounts[post.platform].orEmpty()
        val pathOwners=eligible.filter {own(post,it.second)}.map {it.first}.distinct()
        val sources=memberships[originalKey(post)].orEmpty()
        // An opaque post ID cannot name its author. Exact local subscription
        // memberships can still prove which explicitly linked feed supplied it;
        // conflicting source bundles do not lower the content threshold.
        val sourceOwners=eligible.filter {(_,follow)->
            val validOrigin=if(post.platform in ExpandedRoutes.federated) {
                val actual=uri(post.canonicalUrl);val source=uri(follow.target.canonicalUrl)
                actual!=null&&source!=null&&origin(actual)==origin(source)
            }else SocialLinkHosts.platform(post.canonicalUrl)==post.platform
            follow.target.kind==TargetKind.PROFILE&&follow.id in sources&&validOrigin
        }.map {it.first}.distinct()
        // Legacy/case-alias subscriptions may name the same public account in
        // different local bundles. A list's first entry is not identity proof.
        // Its actual feed membership can disambiguate those matching accounts;
        // a curator's feed must never override an independently named author.
        return when(pathOwners.size) {
            0->sourceOwners.singleOrNull()
            1->pathOwners.single()
            else->pathOwners.intersect(sourceOwners.toSet()).singleOrNull()
        }
    }
    private fun features(post: Post,accounts: Map<Platform,List<Pair<String,Follow>>>,memberships: Map<String,Set<String>>): Features {
        val eligible=accounts[post.platform].orEmpty()
        val sources=memberships[originalKey(post)].orEmpty()
        val owner=publisherOwner(post,accounts,memberships)
        val bounded=post.body.length.toLong()+post.title.length<=MAX_TEXT
        val content=if(bounded) {
            val body=decoded(post.body);val title=decoded(post.title)
            // Decode cached source escapes before presentation, while preserving
            // distinct URL path/query case, signs and symbols. Token equality
            // alone cannot prove that a headline is an echo of its body.
            decoded(PostPresentation.content(post.copy(title=title,body=body)))
        }else ""
        val plain=PostPresentation.proseWithoutLinks(content).trim()
        val wordTokens=tokens(plain)
        val base=tokens(trailingTags.replace(plain,"")).joinToString(" ")
        val literalLinks=PostPresentation.destinationLabels(content).mapNotNull(::normalizedUrl).toSet()
        val urls=(post.links.orEmpty().take(16).filterNot {escapedEntityTag(post,it)}.mapNotNull(::normalizedUrl)+literalLinks).toSet()
        val enclosures=enclosureLinks(post,literalLinks)
        val media=post.media.take(12).mapNotNull {if(it.type=="image")ImageAssetIdentity.key(it.url) ?: normalizedUrl(it.url)else normalizedUrl(it.url)}.toSet()
        return Features(post,owner,wordTokens.joinToString(" "),base,wordTokens,wordTokens.filter {it.any(Char::isDigit)},wordTokens.filter {it in negations},meaningfulSymbols.findAll(plain).joinToString(""){it.value},urls,
            // A literal non-social site address can itself be the announced
            // resource. Unmentioned generic homepages from attachment lists
            // remain weak evidence, as do profile/navigation destinations.
            urls.filter {it !in enclosures&&(destination(it)||it in literalLinks&&SocialLinkHosts.platform(it)==null)&&it!=normalizedUrl(post.canonicalUrl)&&!linksToOriginal(it,post)}.toSet(),media,Regex("(?:…|\\.\\.\\.)\\s*$").containsMatchIn(plain),
            realOriginal(post),normalizedUrl(post.canonicalUrl),originalAccount(post),curator(post),clipIds(post),eligible.filter {it.second.id in sources}.mapTo(linkedSetOf()){it.second.id},null)
    }
    private fun explicit(a: Features,b: Features): Boolean {
        return a.urls.any {linksToOriginal(it,b.post)}||b.urls.any {linksToOriginal(it,a.post)}
    }
    private fun matches(a: Features,b: Features,sameMedia: (Post,Post)->Boolean,repeated: Boolean=false): Boolean {
        if(a.post.platform==b.post.platform&&!repeated||a.post.parentId!=null||b.post.parentId!=null||a.post.preview||b.post.preview||!a.validOriginal||!b.validOriginal)return false
        val linked=repeated||a.owner!=null&&a.owner==b.owner
        val reference=linked&&explicit(a,b)
        val timeA=a.post.publishedAt;val timeB=b.post.publishedAt
        if((timeA==null||timeB==null)&&!reference)return false
        val distance=if(timeA==null||timeB==null)0uL else if(timeA>=timeB)timeA.toULong()-timeB.toULong()else timeB.toULong()-timeA.toULong()
        if(distance>(if(linked)WINDOW else UNLINKED_WINDOW).toULong())return false
        if(a.text.isBlank()||b.text.isBlank())return false
        if(a.numbers!=b.numbers||a.negatives!=b.negatives)return false
        if(a.symbols!=b.symbols&&!AttachmentCaptions.equivalent(a.post,b.post))return false
        if(a.text==b.text&&!completeCopyText(a,b))return false
        if(a.destinations.isNotEmpty()&&b.destinations.isNotEmpty()&&a.destinations!=b.destinations) {
            // Different opaque campaign codes do not establish different claims.
            // Only a complete, distinctive caption from explicitly linked accounts,
            // with media and a close timestamp, can supply the missing corroboration.
            // Every original URL stays in the collected editions and link inventory.
            val changed=(a.destinations-b.destinations)+(b.destinations-a.destinations)
            val campaignCopies=linked&&!a.truncated&&!b.truncated&&a.text==b.text&&a.text.length>=160&&a.tokens.size>=24&&a.tokens.toSet().size>=12&&
                distance<=3_600_000uL&&a.post.media.isNotEmpty()&&b.post.media.isNotEmpty()&&changed.all(::opaqueLink)
            if(!campaignCopies)return false
        }
        val corroborated=a.destinations.intersect(b.destinations).isNotEmpty()||a.media.intersect(b.media).isNotEmpty()
        if(a.text==b.text) {
            val length=a.text.length;val count=a.tokens.size;val distinct=a.tokens.toSet().size
            if(linked&&length>=80&&count>=10||length>=160&&count>=24&&distinct>=12||corroborated&&length>=80&&count>=12&&distinct>=8)return true
            // A linked publisher can distribute one short announcement with
            // different pictures or video opening frames. Collect those close
            // editions without claiming their attachments are identical. The
            // whole caption, symbols and destinations must still agree; retain
            // every distinct attachment and original discussion. Pairwise and
            // ambiguity checks in group() prevent a chain of recurring slogans.
            if(linked&&!a.truncated&&!b.truncated&&timeA!=null&&timeA>0&&timeB!=null&&timeB>0&&
                a.post.media.isNotEmpty()&&b.post.media.isNotEmpty()&&
                (length>=12&&count>=3&&distinct>=3&&distance<=600_000uL||
                    length>=24&&count>=5&&distinct>=5&&distance<=1_800_000uL))return true
            // Short complete captions can still be specific cross-posts. Require
            // independently linked accounts plus image identity; an avatar,
            // common article or short slogan is never enough. This callback is
            // local evidence from already-decoded media, not another request.
            if(linked&&!a.truncated&&!b.truncated&&timeA!=null&&timeA>0&&timeB!=null&&timeB>0&&distance<=UNLINKED_WINDOW.toULong()&&length>=12&&count>=3&&distinct>=3&&sameImages(a.post,b.post,sameMedia))return true
            // Some public readers supply a caption without its media. A complete
            // distinctive seven-word caption, from the same linked person and
            // within ten minutes, is stronger evidence than its arbitrary byte
            // length. Existing mutual/per-platform ambiguity checks still apply.
            return linked&&!a.truncated&&!b.truncated&&timeA!=null&&timeB!=null&&distance<=600_000uL&&
                (length>=48&&count>=7&&distinct>=7||length>=35&&count>=8&&distinct>=8)&&
                (a.post.media.isEmpty()||b.post.media.isEmpty())
        }
        // Hashtag-only presentation adaptations are permitted for linked accounts.
        // Arbitrary word substitutions (including opposite claims) never qualify.
        if(linked&&a.base==b.base&&a.base.length>=80&&tokens(a.base).size>=10)return true
        if(linked&&distance<=600_000uL&&CaptionAliases.equivalent(a.post,b.post))return true
        if(!corroborated)return false
        val short=if(a.tokens.size<=b.tokens.size)a else b;val long=if(short===a)b else a
        // A public reader can visibly truncate a post. Require an explicit marker,
        // substantial exact prefix, and an independent matching URL/media record.
        return short.truncated&&short.text.length>=120&&short.tokens.size>=20&&short.tokens.size>=long.tokens.size*.65&&
            long.tokens.take(short.tokens.size)==short.tokens
    }
    private fun sameImages(a: Post,b: Post,sameMedia: (Post,Post)->Boolean): Boolean {
        val left=a.media.filter {it.type=="image"}.take(12);val right=b.media.filter {it.type=="image"}.take(12)
        if(left.isEmpty()||right.isEmpty())return false
        val urls=left.mapNotNull {normalizedUrl(it.url)}.toSet()
        return right.mapNotNull {normalizedUrl(it.url)}.any {it in urls}||left.any {x->right.any {y->ImageAssetIdentity.same(x,y)||VisualFrames.same(x.visualFingerprint,y.visualFingerprint)||VisualFrames.sameImage(x.visualFingerprint,y.visualFingerprint)}}||sameMedia(a.copy(media=left),b.copy(media=right))
    }
    private fun realOriginal(post: Post): Boolean {
        val url=uri(post.canonicalUrl) ?: return false
        if(post.platform==Platform.RSS)return true
        if(post.platform in ExpandedRoutes.federated)return ContentIdentity.key(post.platform,post.canonicalUrl).startsWith("post:")
        return SocialLinkHosts.platform(url.toString())==post.platform&&ContentIdentity.key(post.platform,post.canonicalUrl).startsWith("post:")
    }
    private fun copyImages(a: Post,b: Post,sameMedia: (Post,Post)->Boolean): Boolean {
        if(a.media.none {it.type=="image"}||b.media.none {it.type=="image"})return false
        return sameImages(a.copy(media=a.media.filter {normalizedUrl(it.url)!=a.avatar?.let(::normalizedUrl)}),
            b.copy(media=b.media.filter {normalizedUrl(it.url)!=b.avatar?.let(::normalizedUrl)}),sameMedia)
    }
    private fun completeCopyText(a: Features,b: Features): Boolean {
        if(a.text.isBlank()||a.text!=b.text||a.numbers!=b.numbers||a.negatives!=b.negatives||a.symbols!=b.symbols&&!AttachmentCaptions.equivalent(a.post,b.post))return false
        // A question is not silently changed into an assertion. Ignore URL query
        // punctuation here; destinations are compared separately below.
        fun questions(post: Post)=Regex("[?]").findAll(PostPresentation.proseWithoutLinks(PostPresentation.content(post))).joinToString(""){it.value}
        return questions(a.post)==questions(b.post)
    }
    /** Presentation-only fallback for already admitted literal versions. It
     * retains every punctuation variant as an attributed block; it does not
     * lower any candidate, ownership, media or time threshold in group(). */
    internal fun literalCaptionVersions(posts:List<Post>):Boolean {
        if(posts.size !in 2..MAX_EDITIONS||posts.any {it.preview||it.parentId!=null||!onlyOpaqueOrOriginalLinks(it)})return false
        val first=features(posts.first(),emptyMap(),emptyMap())
        if(first.tokens.size>=20&&first.text.length>=120)return false
        return first.text.length>=12&&first.tokens.size>=3&&first.tokens.distinct().size>=3&&
            posts.all {post->val value=if(post===posts.first())first else features(post,emptyMap(),emptyMap());value.validOriginal&&!value.truncated&&completeCopyText(first,value)}
    }
    /** Presentation only after collection. Word boundaries, claims, questions,
     * symbols and real destinations still use the existing literal checks.
     * Compact identity removes spacing only, never punctuation or authored words. */
    fun formattingCaptionKey(post:Post):String? {
        if(post.body.length.toLong()+post.title.length>MAX_TEXT||!onlyOpaqueOrOriginalLinks(post))return null
        return PostPresentation.referenceFreeCaption(PostPresentation.content(post)).filterNot {it.isWhitespace()||Character.isSpaceChar(it)}
    }
    fun formattingCaptionCopies(posts:List<Post>):Boolean=CrossPostFusion.withPrepared {
        literalCaptionVersions(posts)&&posts.map(::formattingCaptionKey).let {it.none {v->v==null}&&it.distinct().size==1}
    }
    /** Portable content evidence for a complete compact caption whose second
     * extraction omitted every attachment. Never account or media equality. */
    private fun compactFacebookAlias(a:Features,b:Features):Boolean {
        if(a.post.platform!=Platform.FACEBOOK||b.post.platform!=Platform.FACEBOOK||!a.validOriginal||!b.validOriginal||
            a.post.preview||b.post.preview||a.post.parentId!=null||b.post.parentId!=null||a.truncated||b.truncated||
            a.post.publishedAt?.let {it>0&&it==b.post.publishedAt}!=true||a.post.media.isEmpty()==b.post.media.isEmpty()||
            a.text.length<35||a.tokens.size<8||a.tokens.distinct().size<8||a.destinations!=b.destinations||!completeCopyText(a,b))return false
        fun supplied(p:Post)=p.recoveredArchive==null&&p.provenance?.source?.let {it.platform==Platform.FACEBOOK&&it.role!=SourceRole.CACHE}==true
        fun label(p:Post)=p.author.takeIf {it.length<=300}?.let {TextWhitespace.collapse(decoded(it)).trim().lowercase(java.util.Locale.ROOT)}
        val author=label(a.post)
        return !author.isNullOrBlank()&&author==label(b.post)&&supplied(a.post)&&supplied(b.post)
    }
    private fun copyMatch(a: Features,b: Features,sameMedia: (Post,Post)->Boolean): Boolean {
        if(commentCopies(a,b))return true
        if(a.post.parentId!=null||b.post.parentId!=null||a.post.preview||b.post.preview||a.truncated||b.truncated||!a.validOriginal||!b.validOriginal)return false
        val reference=explicit(a,b)
        val ownUrls=listOfNotNull(a.canonical,b.canonical).toSet()
        fun external(urls: Set<String>)=urls.filterNot {it in ownUrls||linksToOriginal(it,a.post)||linksToOriginal(it,b.post)}.toSet()
        val left=external(a.destinations);val right=external(b.destinations)
        if(left.isNotEmpty()&&right.isNotEmpty()&&left!=right)return false
        val images=copyImages(a.post,b.post,sameMedia)
        val clips=a.clips.intersect(b.clips).isNotEmpty()
        // Video-only identity may corroborate a substantial caption, but cannot
        // stand in for two different explicitly attached still-image galleries.
        fun stills(post: Post)=post.media.any {it.type=="image"&&clipIds(post.copy(media=listOf(it))).isEmpty()}
        val sharedMedia=images||clips&&!(stills(a.post)&&stills(b.post))
        val timeA=a.post.publishedAt?.takeIf {it>0};val timeB=b.post.publishedAt?.takeIf {it>0}
        val distance=if(timeA==null||timeB==null)null else if(timeA>=timeB)timeA.toULong()-timeB.toULong()else timeB.toULong()-timeA.toULong()
        // A separately reencoded copy can be published days later. A strong
        // picture observation plus long, identical surrounding prose supports
        // bounded credit-name/handle formatting, not an account linkage claim.
        if(images&&distance!=null&&distance<=MAX_COPY_WINDOW.toULong()&&CaptionAliases.equivalent(a.post,b.post,mediaConfirmed=true))return true
        // A local wording edit can be one announcement if the same actual
        // picture corroborates it. Unlike equivalence, weaving preserves every
        // differing fragment with its exact source. Numerical/negative/symbol
        // and destination changes still use the existing conservative paths.
        if(images&&distance!=null&&distance<=600_000uL&&a.numbers==b.numbers&&a.negatives==b.negatives&&a.symbols==b.symbols&&
            CaptionWeave.matches(a.post,b.post))return true
        if(a.text==b.text&&!completeCopyText(a,b))return false
        if(completeCopyText(a,b)) {
            val length=a.text.length;val count=a.tokens.size;val distinct=a.tokens.toSet().size
            // Portable content evidence for independently captured Facebook
            // aliases: a distinctive complete caption at the exact same
            // publication instant. This does not assert common account control;
            // keep both source identities and every attachment in saves too.
            if(a.post.platform==Platform.FACEBOOK&&b.post.platform==Platform.FACEBOOK&&timeA!=null&&timeA==timeB) {
                if(length>=80&&count>=12&&distinct>=8)return true
                if(compactFacebookAlias(a,b))return true
            }
            val substantial=length>=160&&count>=24&&distinct>=12
            val specific=length>=80&&count>=12&&distinct>=8
            if(substantial&&(reference||sharedMedia||distance!=null&&distance<=MAX_COPY_WINDOW.toULong()))return true
            if(specific&&(reference||sharedMedia))return true
            return length>=35&&count>=6&&distinct>=6&&(reference||images&&distance!=null&&distance<=MAX_COPY_WINDOW.toULong())
        }
        if(distance==null||distance>MAX_REPEAT_WINDOW.toULong()||!(images||clips||reference))return false
        return CrossPostFusion.copiedCore(a.post,b.post)||images&&CrossPostFusion.mediaMatched(a.post,b.post)
    }
    private fun commentCopies(a:Features,b:Features):Boolean {
        val account=declaredCommentAccount(a.post) ?: return false
        if(account!=declaredCommentAccount(b.post)||a.post.preview||b.post.preview||!completeCopyText(a,b)||a.destinations!=b.destinations)return false
        val body=tokens(decoded(a.post.body))
        if(body.size<24||body.toSet().size<12||a.post.body.length<160)return false
        val first=a.post.publishedAt?.takeIf {it>0} ?: return false
        val second=b.post.publishedAt?.takeIf {it>0} ?: return false
        return kotlin.math.abs(first-second)<=600_000
    }
    private fun fusionMatch(a: Features,b: Features,sameMedia: (Post,Post)->Boolean,repeated: Boolean=false): Boolean {
        if(a.post.platform==b.post.platform&&!repeated||a.post.parentId!=null||b.post.parentId!=null||a.post.preview||b.post.preview||!a.validOriginal||!b.validOriginal)return false
        if(a.text==b.text&&!completeCopyText(a,b))return false
        if(!repeated&&(a.owner==null||a.owner!=b.owner))return false
        val timeA=a.post.publishedAt ?: return false;val timeB=b.post.publishedAt ?: return false
        val distance=if(timeA>=timeB)timeA.toULong()-timeB.toULong()else timeB.toULong()-timeA.toULong()
        if(distance>WINDOW.toULong())return false
        if(!repeated&&distance<=600_000uL&&fieldCaptionMatch(a,b,sameMedia))return true
        if(!repeated&&distance<=600_000uL&&headingMatch(a,b,sameMedia))return true
        // Linked publishers often make a small literal wording edit while
        // distributing one announcement. The strict aligner preserves every
        // variant and rejects changed claims/names. Do not make this route
        // wait for an image download when the complete long passage itself
        // supplies corroboration; destinations and the tight clock must agree.
        if(!repeated&&wordingEdit(a,b))return true
        if(!repeated&&resourceAnnouncement(a,b))return true
        if(!repeated&&corroboratedPassage(a,b,sameMedia))return true
        // A specific shared article destination can corroborate a substantial
        // caption too. Neither a common URL nor image alone establishes a match.
        if(!repeated&&a.destinations.intersect(b.destinations).isEmpty()&&!sameImages(a.post,b.post,sameMedia))return false
        return CrossPostFusion.matchesWithContext(a.post,b.post)
    }
    private fun fieldCaptionMatch(a:Features,b:Features,sameMedia:(Post,Post)->Boolean):Boolean {
        if(a.post.media.isEmpty()||b.post.media.isEmpty()||a.numbers!=b.numbers||a.negatives!=b.negatives||a.symbols!=b.symbols||a.destinations!=b.destinations)return false
        val (left,right)=CaptionFieldBlocks.bodyEditions(a.post,b.post) ?: return false
        val first=features(left,emptyMap(),emptyMap()).copy(owner=a.owner)
        val second=features(right,emptyMap(),emptyMap()).copy(owner=b.owner)
        // Only a complete literal body may reuse the established linked short
        // caption route. The extra title never authorizes general containment,
        // changed claims, missing media or identity inferred from a label.
        return first.text.length<120&&first.tokens.size<20&&completeCopyText(first,second)&&
            CrossPostFusion.compatibleIntroductions(PostPresentation.text(a.post).title,PostPresentation.text(b.post).title)&&matches(first,second,sameMedia)
    }
    internal fun fieldCaptionComposition(first:Post,second:Post):Boolean {
        val a=features(first,emptyMap(),emptyMap()).copy(owner="saved-field-editions")
        val b=features(second,emptyMap(),emptyMap()).copy(owner="saved-field-editions")
        val x=first.publishedAt?.takeIf {it>0} ?: return false
        val y=second.publishedAt?.takeIf {it>0} ?: return false
        return kotlin.math.abs(x-y)<=600_000&&fieldCaptionMatch(a,b){_,_->false}
    }
    private fun headingMatch(a:Features,b:Features,sameMedia:(Post,Post)->Boolean):Boolean =
        AnnouncementHeadings.matches(a.post,b.post) {
            fun concrete(row:Features)=(editorialLinks(row.post)+PostPresentation.destinationLabels(PostPresentation.content(row.post)).toSet()).filterNot {opaqueLink(it)||linksToOriginal(it,row.post)||nitterMentionLink(row.post,it)}.map {value->
                value.toHttpUrlOrNull()?.let {it.newBuilder().host(it.host.removePrefix("www.")).build().toString()} ?: value
            }.toSet()
            val left=concrete(a);val right=concrete(b)
            a.numbers==b.numbers&&a.negatives==b.negatives&&a.symbols==b.symbols&&
                (left.isEmpty()||right.isEmpty()||left==right)&&sameAnnouncementArtwork(a.post,b.post,sameMedia)
        }
    internal fun nitterMentionLink(post:Post,value:String):Boolean {
        val source=post.provenance?.source ?: return false
        if(post.platform!=Platform.X||source.frontend!="nitter"||PostPresentation.destinationLabels(PostPresentation.content(post)).any {it==value})return false
        val url=uri(value) ?: return false
        if(!DarwinRoutes.matches(url,source.root)||url.rawQuery!=null||url.fragment!=null)return false
        val handle=url.path.trim('/').takeIf {it.matches(Regex("[A-Za-z0-9_]{1,15}"))} ?: return false
        return Regex("(?<![\\p{L}\\p{N}_])@${Regex.escape(handle)}(?![A-Za-z0-9_])",RegexOption.IGNORE_CASE).containsMatchIn(post.body)
    }
    /** Matching cover art can corroborate a specific announcement headline.
     * It does not establish equal recordings: every playable edition is kept. */
    private fun sameAnnouncementArtwork(a:Post,b:Post,sameMedia:(Post,Post)->Boolean,nearby:Boolean=false):Boolean {
        fun frames(post:Post)=post.media.take(12).mapNotNull {item->when(item.type) {
            "image"->item
            "video"->item.preview?.let {item.copy(url=it,type="image")}
            else->null
        }}
        val left=frames(a);val right=frames(b)
        if(left.any {x->right.any {y->
            val first=x.attachmentFingerprint ?: x.visualFingerprint;val second=y.attachmentFingerprint ?: y.visualFingerprint
            // Scene resemblance is sufficient only for this corroborated
            // announcement route. Attachment suppression continues to require
            // its separate detailed equality proof.
            if(first!=null&&second!=null)VisualFrames.sameImage(first,second)||VisualFrames.same(first,second,nearbyFrame=nearby)
            else x.url==y.url||ImageAssetIdentity.same(x,y)
        }})return true
        return sameImages(a,b,sameMedia)
    }
    internal fun headingComposition(posts:List<Post>):Boolean {
        val rows=posts.map {features(it,emptyMap(),emptyMap())}
        return rows.indices.all {i->(i+1 until rows.size).all {j->
            val a=rows[i];val b=rows[j];val x=a.post.publishedAt?.takeIf {it>0};val y=b.post.publishedAt?.takeIf {it>0}
            x!=null&&y!=null&&kotlin.math.abs(x-y)<=600_000&&headingMatch(a,b){_,_->false}
        }}
    }
    private fun repeatMatch(a: Features,b: Features,sameMedia: (Post,Post)->Boolean): Boolean {
        if(a.post.platform!=b.post.platform||a.post.parentId!=null||b.post.parentId!=null||a.post.preview||b.post.preview||a.truncated||b.truncated)return false
        // Fold proven extraction aliases before they become two competing
        // candidates for another platform's one counterpart slot.
        if(compactFacebookAlias(a,b))return true
        val sameAccount=a.account!=null&&a.account==b.account||(a.curator ?: a.account)?.let {it==(b.curator ?: b.account)}==true
        // Facebook commonly exposes both numeric and pfbid editions. The
        // exact same subscribed feed, timestamp and distinctive complete text
        // prove a repeat without pretending either URL is the other's ID.
        val feedAlias=a.post.platform==Platform.FACEBOOK&&a.suppliedBy.intersect(b.suppliedBy).isNotEmpty()&&
            a.post.publishedAt!=null&&a.post.publishedAt==b.post.publishedAt&&completeCopyText(a,b)&&
            (a.text.length>=80&&a.tokens.size>=12&&a.tokens.toSet().size>=8||
                // An extraction alias can carry its own media rendition too.
                // Retain both originals and every distinct picture; a second
                // rendition must not create competing Facebook candidates.
                a.text.length>=48&&a.tokens.size>=7&&a.tokens.toSet().size>=7)
        if(!sameAccount&&!feedAlias)return false
        val timeA=a.post.publishedAt?.takeIf {it>0} ?: return false;val timeB=b.post.publishedAt?.takeIf {it>0} ?: return false
        val distance=if(timeA>=timeB)timeA.toULong()-timeB.toULong()else timeB.toULong()-timeA.toULong()
        if(distance>MAX_REPEAT_WINDOW.toULong())return false
        return matches(a,b,sameMedia,repeated=true)||fusionMatch(a,b,sameMedia,repeated=true)||videoExcerpt(a.post,b.post)||relatedVisualPair(a.post,b.post)
    }
    private fun decoratedMatch(a:Features,b:Features,sameMedia:(Post,Post)->Boolean):Boolean {
        if(a.owner==null||a.owner!=b.owner||!a.validOriginal||!b.validOriginal||a.post.preview||b.post.preview||a.post.parentId!=null||b.post.parentId!=null)return false
        val first=a.decoration ?: return false;val second=b.decoration ?: return false
        if(!first.changed&&!second.changed)return false
        val left=a.post.publishedAt?.takeIf {it>0} ?: return false;val right=b.post.publishedAt?.takeIf {it>0} ?: return false
        val distance=if(left>=right)left-right else right-left
        if(distance>30*60*1000L)return false
        val match=DecoratedPostCopies.compare(first,second) ?: return false
        val corroborated=match.sharedResource||a.clips.intersect(b.clips).isNotEmpty()||sameImages(a.post,b.post,sameMedia)
        if(minOf(first.tokens.size,second.tokens.size)<12)return match.exact&&distance<=30*60*1000L&&corroborated
        return corroborated||match.exact&&minOf(first.tokens.size,second.tokens.size)>=24&&minOf(first.text.length,second.text.length)>=160
    }
    private fun candidateKeys(row: Features): Set<String> = buildSet {
        if(row.text.isNotBlank()){add("text:"+row.text);add("base:"+row.base)}
        normalizedUrl(row.post.canonicalUrl)?.let {add("url:"+it)}
        row.destinations.forEach {add("url:"+it)};row.media.forEach {add("media:"+it)}
        row.clips.forEach {add("clip:"+it)}
        val repeatAccount=row.account
        if(row.owner!=null&&row.post.title.isNotBlank()&&PostPresentation.text(row.post).title.isNotBlank())CaptionFieldBlocks.bodyEdition(row.post)?.let {body->
            val terms=tokens(PostPresentation.proseWithoutLinks(decoded(PostPresentation.content(body))))
            val key=terms.joinToString(" ")
            if(terms.size in 3..19&&key.length<120)add("text:"+key)
        }
        if(row.owner!=null)AnnouncementHeadings.key(row.post)?.let {add("heading:${row.owner}:$it")}
        if(row.owner!=null)ResourceAnnouncements.key(row.post)?.let {add("resource-tail:${row.owner}:"+it.length+":"+it.hashCode())}
        speaker(row.post)?.let {subject->(row.curator ?: repeatAccount)?.let {add("speaker:$it:$subject")}}
        CrossPostFusion.candidateAnchors(row.post).forEach {
            if(row.owner!=null)add("fusion:"+row.owner+":"+it)
            if(repeatAccount!=null)add("repeat:"+repeatAccount+":"+it)
            add("copy:"+it)
        }
    }
    fun group(posts: List<Post>,follows: List<Follow>,memberships: Map<String,Set<String>> = emptyMap(),sameMedia: (Post,Post)->Boolean = {_,_->false}): List<PostGroup> {
        return CrossPostFusion.withPrepared {groupPrepared(posts,follows,memberships,sameMedia)}
    }
    /** A newer source/image snapshot cancels obsolete CPU work, not just its
     * publication. Never return a partly checked collection after cancellation. */
    suspend fun groupCancellable(posts:List<Post>,follows:List<Follow>,memberships:Map<String,Set<String>> = emptyMap(),sameMedia:(Post,Post)->Boolean={_,_->false}):List<PostGroup> {
        val context=currentCoroutineContext()
        return CrossPostFusion.withPrepared {groupPrepared(posts,follows,memberships,sameMedia){context.ensureActive()}}
    }
    /** Footage proofs are completed worker results, not cover-image matches.
     * Keep the established public entry points and their trailing lambdas intact. */
    suspend fun groupWithVideoFootage(posts:List<Post>,follows:List<Follow>,memberships:Map<String,Set<String>> = emptyMap(),sameMedia:(Post,Post)->Boolean={_,_->false},sharedFootage:(Post,Post)->SharedVideoFootage?):List<PostGroup> {
        val context=currentCoroutineContext()
        return CrossPostFusion.withPrepared {groupPrepared(posts,follows,memberships,sameMedia,sharedFootage){context.ensureActive()}}
    }
    /** Apply completed footage observations to an unchanged caption/image
     * projection. Source or image changes require a new base projection. This
     * is the same final admission step as groupWithVideoFootage; no cover or
     * caption similarity substitutes for a footage proof. */
    fun withVideoFootage(groups:List<PostGroup>,follows:List<Follow>,memberships:Map<String,Set<String>> = emptyMap(),
        sharedFootage:(Post,Post)->SharedVideoFootage?,checkpoint:()->Unit={}):List<PostGroup> = CrossPostFusion.withPrepared {
        val accounts=follows.filter {it.enabled}.map {Subscriptions.id(it) to it}.groupBy {it.second.target.platform}
        val owners=groups.flatMap {it.posts}.associate {post->
            checkpoint();originalKey(post) to publisherOwner(post,accounts,memberships).takeIf {realOriginal(post)}
        }
        SharedVideoCollections.collect(groups,owners,sharedFootage,checkpoint)
            .sortedWith(compareByDescending<PostGroup> {it.primary.publishedAt ?: Long.MIN_VALUE}.thenBy {originalKey(it.primary)})
    }
    fun videoFootageRequests(groups:List<PostGroup>,follows:List<Follow>,memberships:Map<String,Set<String>> = emptyMap()):List<VideoFootageRequest> = CrossPostFusion.withPrepared {
        if(groups.size<2)return@withPrepared emptyList()
        val accounts=follows.filter {it.enabled}.map {Subscriptions.id(it) to it}.groupBy {it.second.target.platform}
        val owners=groups.flatMap {it.posts}.associate {post->
            originalKey(post) to publisherOwner(post,accounts,memberships).takeIf {realOriginal(post)}
        }
        SharedVideoCollections.requests(groups,owners)
    }
    fun galleryContinuationRequests(groups:List<PostGroup>,follows:List<Follow>,memberships:Map<String,Set<String>> = emptyMap()):List<GalleryContinuationRequest> = CrossPostFusion.withPrepared {
        val accounts=follows.filter {it.enabled}.map {Subscriptions.id(it) to it}.groupBy {it.second.target.platform}
        groups.mapNotNull {group->
            if(!GalleryContinuations.potential(group.posts))return@mapNotNull null
            val owners=group.posts.associate {post->originalKey(post) to publisherOwner(post,accounts,memberships).takeIf {realOriginal(post)}}
            GalleryContinuations.request(group.posts,owners) {root->
                follows.filter {it.enabled&&it.id in memberships[originalKey(root)].orEmpty()&&own(root,it)}.mapTo(linkedSetOf()){it.id}
            }?.let {request->request.copy(bindings=follows.filter {it.enabled&&Subscriptions.id(it)==owners[originalKey(request.root)]&&it.id in memberships[originalKey(request.root)].orEmpty()+memberships[originalKey(request.peer)].orEmpty()}.map {it.copy(name="",personName=null)})}
        }
    }
    /** Demand-scoped fallback after the visible-card dwell. Reuse current
     * observations without repeating caption matching or issuing any read. */
    fun focusedVideoFootageRequests(groups:List<PostGroup>,follows:List<Follow>,memberships:Map<String,Set<String>>,focus:Set<String>,
        sharedFootage:(Post,Post)->SharedVideoFootage?={_,_->null},checkpoint:()->Unit={}):List<VideoFootageRequest> = CrossPostFusion.withPrepared {
        require(focus.isNotEmpty()&&focus.size<=MAX_EDITIONS)
        val accounts=follows.filter {it.enabled}.map {Subscriptions.id(it)to it}.groupBy {it.second.target.platform}
        val owners=groups.flatMap {it.posts}.associate {post->originalKey(post)to publisherOwner(post,accounts,memberships).takeIf {realOriginal(post)}}
        val observed=SharedVideoCollections.collect(groups,owners,sharedFootage,checkpoint)
        SharedVideoCollections.requests(observed,owners,focus=focus,checkpoint=checkpoint)
    }
    /** Reconcile overlapping extractions inside one input batch. Never union
     * attachments across historical revisions or rewrite a source record. */
    fun originalSnapshots(posts:List<Post>):List<Post> = CrossPostFusion.withPrepared {originalSnapshots(posts){}}
    private fun originalSnapshots(posts:List<Post>,checkpoint:()->Unit):List<Post> {
        val unique=LinkedHashMap<String,Post>()
        fun quality(post: Post)=listOf(if(post.preview)0L else 1L,if(post.recoveredArchive==null)1L else 0L,
            // A generated headline which presentation removes is not extra
            // source information. Do not let it displace a complete gallery.
            if(post.body.length.toLong()+post.title.length<=MAX_TEXT)PostPresentation.content(post).length.toLong()else MAX_TEXT.toLong(),
            post.media.size.toLong(),post.provenance?.fetchedAt ?: 0)
        fun better(a: Post,b: Post): Boolean {val aa=quality(a);val bb=quality(b);for(i in aa.indices)if(aa[i]!=bb[i])return aa[i]>bb[i];return false}
        posts.forEach {post->checkpoint();val key=originalKey(post);val old=unique[key];if(old==null||better(post,old))unique[key]=post}
        return unique.values.toList()
    }
    private fun groupPrepared(posts:List<Post>,follows:List<Follow>,memberships:Map<String,Set<String>>,sameMedia:(Post,Post)->Boolean,sharedFootage:((Post,Post)->SharedVideoFootage?)?=null,checkpoint:()->Unit={}):List<PostGroup> {
        val accounts=follows.filter {it.enabled}.map {Subscriptions.id(it) to it}.groupBy {it.second.target.platform}
        val postOrder=compareByDescending<Post> {it.publishedAt ?: Long.MIN_VALUE}.thenBy(::originalKey)
        val prepared=originalSnapshots(posts,checkpoint).sortedWith(postOrder).map {checkpoint();features(it,accounts,memberships)}
        val decorativeOwners=prepared.filter {checkpoint();it.owner!=null&&DecoratedPostCopies.mayChange(it.post)}.mapTo(hashSetOf()){it.owner}
        val ordered=if(decorativeOwners.isEmpty())prepared else prepared.map {row->
            checkpoint();if(row.owner in decorativeOwners)row.copy(decoration=DecoratedPostCopies.prepare(row.post))else row
        }
        fun nominate(keys:List<Set<String>>,exactKeys:List<String>):List<Set<Int>?> {
        val index=mutableMapOf<String,MutableList<Int>>()
        keys.forEachIndexed {i,names->names.forEach {index.getOrPut(it){mutableListOf()}.add(i)}}
        return keys.mapIndexed {i,names->
            checkpoint()
            val textKey=exactKeys[i]
            val exactBucket=index[textKey].orEmpty()
            if(exactBucket.size>MAX_CANDIDATES+1)null else {
                val exact=exactBucket.filter {it!=i}.toCollection(LinkedHashSet())
                val extra=LinkedHashSet<Int>()
                // Keep precise, complete buckets before broad shared links.
                // A crowded route must not erase comparisons admitted by a
                // more selective route. The total budget remains unchanged;
                // skipped buckets mean unexamined, never a negative match.
                for(name in names.filter {it!=textKey}.sortedWith(compareBy<String>{index[it].orEmpty().size}.thenBy {it})) {
                    val bucket=index[name].orEmpty()
                    // Repeated article/home/media links do not defeat a precise
                    // text bucket. Ignore whole broad buckets, never their first N.
                    if(bucket.size>MAX_CANDIDATES+1)continue
                    val additions=bucket.filter {it!=i&&it !in exact&&it !in extra}
                    if(exact.size+extra.size+additions.size<=MAX_CANDIDATES)extra+=additions
                }
                LinkedHashSet(exact).apply {addAll(extra)}
            }
        }
        }
        val candidates=nominate(ordered.map {checkpoint();candidateKeys(it)},ordered.map {"text:"+it.text})
        // This route has its own nomination budget. Adding cosmetic-copy hints
        // must never displace candidates from any established detector.
        val decorationExact=if(decorativeOwners.isEmpty())emptyList()else ordered.map {row->row.decoration?.let {"decoration:${row.owner}:${it.text.length}:${it.text.hashCode()}"}.orEmpty()}
        val decorationKeys=if(decorativeOwners.isEmpty())emptyList()else ordered.mapIndexed {i,row->buildSet {
            if(row.owner!=null&&row.decoration!=null) {
                add(decorationExact[i])
                DecoratedPostCopies.anchors(row.decoration).forEach {add("decoration-chunk:${row.owner}:$it")}
            }
        }}
        val decorationCandidates=nominate(decorationKeys,decorationExact)
        val edges=Array(ordered.size){mutableSetOf<Int>()}
        val repeatEdges=Array(ordered.size){mutableSetOf<Int>()}
        val fusionEdges=Array(ordered.size){mutableSetOf<Int>()}
        ordered.indices.forEach {i->candidates[i]?.filter {it>i&&i in candidates[it].orEmpty()}?.forEach {j->
            checkpoint()
            if(repeatMatch(ordered[i],ordered[j],sameMedia)||copyMatch(ordered[i],ordered[j],sameMedia)){repeatEdges[i]+=j;repeatEdges[j]+=i}
            val exact=matches(ordered[i],ordered[j],sameMedia)
            if(exact||fusionMatch(ordered[i],ordered[j],sameMedia)) {
                edges[i]+=j;edges[j]+=i
                if(!exact){fusionEdges[i]+=j;fusionEdges[j]+=i}
            }
        }}
        decorationCandidates.indices.forEach {i->decorationCandidates[i]?.filter {it>i&&i in decorationCandidates[it].orEmpty()}?.forEach {j->
            checkpoint()
            if(decoratedMatch(ordered[i],ordered[j],sameMedia)){repeatEdges[i]+=j;repeatEdges[j]+=i}
        }}
        // First collapse verified repetitions/copies into complete-link cohorts.
        // The earliest original anchors a cohort; a chain of near dates or
        // partial matches cannot transitively swallow a different post.
        val cohorts=mutableListOf<MutableList<Int>>()
        val cohortOf=IntArray(ordered.size){-1}
        val oldest=ordered.indices.sortedWith(compareBy<Int> {ordered[it].post.publishedAt ?: Long.MAX_VALUE}.thenBy {originalKey(ordered[it].post)})
        oldest.forEach {i->
            checkpoint()
            val matching=repeatEdges[i].mapNotNull {cohortOf[it].takeIf {id->id>=0}}.distinct().filter {id->
                val members=cohorts[id]+i
                if(members.size>MAX_EDITIONS||cohorts[id].any {it !in repeatEdges[i]})false else {
                    val originals=members.map {ordered[it].post}
                    val originalUrls=originals.mapNotNull {normalizedUrl(it.canonicalUrl)}.toSet()
                    CrossPostFusion.compose(originals)!=null||CrossPostFusion.composeMedia(originals)!=null||CrossPostFusion.composeRelated(originals)!=null||originals.indices.all {a->(a+1 until originals.size).all {b->
                        PostPresentation.comparableContent(originals[a])==PostPresentation.comparableContent(originals[b])||videoExcerpt(originals[a],originals[b])||
                            completeCopyText(ordered[members[a]],ordered[members[b]])&&ordered[members[a]].urls-originalUrls==ordered[members[b]].urls-originalUrls
                    }}
                }
            }
            val existing=matching.singleOrNull()
            if(existing==null){cohortOf[i]=cohorts.size;cohorts+=mutableListOf(i)}else {cohortOf[i]=existing;cohorts[existing]+=i}
        }
        // Cross-platform ambiguity counts cohorts, not every verified duplicate
        // of one account's post. All editions still need the original mutual
        // complete-link match; unrelated same-platform candidates remain
        // ambiguous and therefore cannot be selected arbitrarily.
        val links=HashMap<Pair<Int,Int>,Int>()
        edges.forEachIndexed {i,neighbors->neighbors.filter {it>i}.forEach {j->
            val a=cohortOf[i];val b=cohortOf[j]
            if(a!=b){val key=minOf(a,b) to maxOf(a,b);links[key]=(links[key] ?: 0)+1}
        }}
        val cohortEdges=Array(cohorts.size){mutableSetOf<Int>()}
        links.forEach {(pair,count)->val (a,b)=pair;if(count==cohorts[a].size*cohorts[b].size){cohortEdges[a]+=b;cohortEdges[b]+=a}}
        val counts=cohortEdges.map {neighbors->neighbors.groupingBy {ordered[cohorts[it].first()].post.platform}.eachCount()}
        fun unambiguous(a: Int,b: Int)=b in cohortEdges[a]&&counts[a][ordered[cohorts[b].first()].post.platform]==1&&counts[b][ordered[cohorts[a].first()].post.platform]==1
        val result=mutableListOf<MutableList<Int>>()
        val groupOf=IntArray(cohorts.size){-1}
        val recent=cohorts.indices.sortedWith(compareByDescending<Int> {ordered[cohorts[it].first()].post.publishedAt ?: Long.MIN_VALUE}.thenBy {originalKey(ordered[cohorts[it].first()].post)})
        recent.forEach {i->
            checkpoint()
            val matching=cohortEdges[i].mapNotNull {neighbor->groupOf[neighbor].takeIf {it>=0}}.distinct().filter {group->
                val members=(result[group]+i).flatMap {cohorts[it]}
                members.size<=MAX_EDITIONS&&result[group].all {unambiguous(it,i)}&&
                    (members.none {member->fusionEdges[member].any {it in members}}||CrossPostFusion.compose(members.map {ordered[it].post})!=null||CaptionFieldBlocks.compose(members.map {ordered[it].post})!=null)
            }
            val existing=matching.singleOrNull()
            if(existing==null){groupOf[i]=result.size;result+=mutableListOf(i)}else {groupOf[i]=existing;result[existing]+=i}
        }
        val grouped=result.map {group->
            checkpoint()
            val originals=group.flatMap {cohorts[it]}.map {ordered[it].post}
            PostGroup(originals.sortedWith(compareBy<Post> {it.publishedAt?.takeIf {time->time>0} ?: Long.MAX_VALUE}.thenBy(::originalKey)))
        }
        checkpoint()
        val established=RedditSupplements.collect(collectImageEchoes(collectGalleries(grouped,sameMedia),sameMedia))
        val owners=ordered.associate {row->originalKey(row.post) to row.owner.takeIf {row.validOriginal}}
        val announcements=AnnouncementCollections.collect(established,owners,{a,b->sameAnnouncementArtwork(a,b,sameMedia)},checkpoint)
        return (if(sharedFootage==null)announcements else SharedVideoCollections.collect(announcements,owners,sharedFootage,checkpoint))
            .sortedWith(compareByDescending<PostGroup> {it.primary.publishedAt ?: Long.MIN_VALUE}.thenBy {originalKey(it.primary)})
    }
}

/** Keep related/recommended posts out of a discussion. Only supplied parent relationships qualify. */
object Discussions {
    fun target(root: Post,routes: DarwinRoutes,popular: Boolean): Target? {
        val target=routes.recognize(root.canonicalUrl) ?: return null
        if(root.platform !in setOf(Platform.REDDIT,Platform.X,Platform.FACEBOOK,Platform.LEMMY,Platform.YOUTUBE))return target
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: return target
        val sort=if(root.platform==Platform.X){if(popular)"Relevance"else "Recency"}else if(popular)"top"else "new"
        return target.copy(canonicalUrl=url.newBuilder().setQueryParameter("sort",sort).build().toString())
    }
    fun comments(root: Post,page: Page,knownParent: (String)->Boolean={false}): List<Post> {
        val rootKey=ContentIdentity.key(root.platform,root.canonicalUrl)
        val roots=listOfNotNull(root.id,rootKey,root.canonicalUrl,root.relationId).map {ContentIdentity.reference(root.platform,it)}.toSet()
        val observedRoot=page.posts.singleOrNull {it.platform==root.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==rootKey}
        val conversation=(root.conversationId ?: observedRoot?.conversationId)?.let {ContentIdentity.reference(root.platform,it)}
        val branch=conversation?.let {it !in roots} ?: (root.parentId!=null||observedRoot?.parentId!=null)
        val allowedConversation=conversation?.let {setOf(it)} ?: roots
        val accepted=roots.toMutableSet()
        val cached=mutableMapOf<String,Boolean>()
        fun reference(value: String)=ContentIdentity.reference(root.platform,value)
        fun declared(post: Post)=post.conversationId?.takeIf {it.isNotBlank()}?.let(::reference)
        fun scoped(post: Post): Boolean {
            if(!branch&&declared(post) in roots)return true
            val key=ContentIdentity.key(post.platform,post.canonicalUrl)
            // These public comment permalinks contain their original post ID.
            return post.platform in setOf(Platform.REDDIT,Platform.TIKTOK,Platform.FACEBOOK,Platform.TIEBA)&&
                rootKey.startsWith("post:")&&key.startsWith("comment:${rootKey.removePrefix("post:")}:")
        }
        val result=mutableListOf<Post>()
        val remaining=page.posts.filter {it.platform==root.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)!=rootKey&&
            (declared(it)==null||declared(it) in allowedConversation||branch&&conversation==null)}.distinctBy {ContentIdentity.key(it.platform,it.canonicalUrl)}.toMutableList()
        repeat(32){
            val children=remaining.filter {post->scoped(post)||listOfNotNull(post.parentId,post.sourceParentId).any {parent->
                reference(parent) in accepted||cached.getOrPut(parent){knownParent(parent)}
            }}
            if(children.isEmpty())return page.posts.filter {it in result}
            children.forEach {post->result+=post;listOfNotNull(post.id,post.canonicalUrl,post.relationId).forEach {accepted+=reference(it)}}
            remaining.removeAll(children.toSet())
        }
        return page.posts.filter {it in result}
    }
}
