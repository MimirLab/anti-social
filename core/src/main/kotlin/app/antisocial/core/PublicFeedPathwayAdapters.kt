/* AntiSocial, GPL-3.0-or-later. Independent bounded feed-path adapter.
 * Request contracts reviewed in RSS-Bridge/rss-bridge bridges/ (Unlicense) and
 * DIYgod/RSSHub lib/routes/ (AGPL-3.0), 2026-10-17. No server runtime copied.
 * Shared renderers are pathways, never social-link aliases or complete mirrors.
 */
package app.antisocial.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

fun publicFeedPathwayAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DataAdapter?=
    if(source.frontend in setOf("rsshub","rss-bridge"))PublicFeedPathwayAdapter(source,gateway,routes)else null

/** Pure, source-confirmed contracts. An unsupported shape makes no request. */
internal object PublicFeedPathways {
    private val user=Regex("[A-Za-z0-9_.-]{1,100}")
    private val domain=Regex("(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}")
    private val federated=setOf(Platform.MASTODON,Platform.LEMMY,Platform.MISSKEY)
    private fun url(raw: String)=raw.toHttpUrlOrNull()?.takeIf {it.username.isEmpty()&&it.password.isEmpty()}
    private fun localUsername(target: Target): String? {
        val parsed=url(target.canonicalUrl) ?: return null;val parts=parsed.pathSegments.filter(String::isNotBlank)
        val value=when(target.platform) {
            Platform.BLUESKY -> if(parts.size==2&&parts[0]=="profile")parts[1]else null
            Platform.GETTR -> if(parts.size==2&&parts[0]=="user")parts[1]else null
            Platform.TELEGRAM -> parts.filter {it!="s"}.singleOrNull()
            Platform.LINKEDIN -> if(parts.size==2&&parts[0]=="company")parts[1]else null
            Platform.TUMBLR -> if(parsed.host.endsWith(".tumblr.com")&&parsed.host!="www.tumblr.com")parsed.host.removeSuffix(".tumblr.com")else when {parts.size==1->parts[0];parts.size==2&&parts[0] in setOf("blog","blog/view")->parts[1];else->null}
            Platform.MASTODON,Platform.MISSKEY -> if(parts.size==1)parts[0].removePrefix("@")else if(parts.size==2&&parts[0]=="users")parts[1]else null
            Platform.REDDIT -> if(parts.size>=2&&parts[0] in setOf("r","u","user"))parts[1]else null
            Platform.LEMMY -> if(parts.size==2&&parts[0]=="c")parts[1]else null
            Platform.TIEBA -> parsed.queryParameter("kw")
            else -> parts.singleOrNull()?.removePrefix("@")
        }
        return value?.takeIf {if(target.platform==Platform.TIEBA)it.isNotBlank()&&it.length<=100&&!it.any {c->c.isISOControl()}else if(target.platform==Platform.BLUESKY)it.matches(Regex("[A-Za-z0-9_.:-]{1,256}"))else it.matches(user)}
    }
    private fun server(target: Target): String?=url(target.canonicalUrl)?.takeIf {it.isHttps&&it.port==443&&domain.matches(it.host)&&!it.host.endsWith(".local")&&!it.host.endsWith(".localhost")&&!it.host.endsWith(".internal")}?.host
    fun request(source: Source,target: Target): String? {
        if(target.platform!=source.platform||target.kind !in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY))return null
        val parsed=url(target.canonicalUrl) ?: return null
        if(profileKey(target.platform,target.canonicalUrl)==null)return null
        if(target.platform in federated&&server(target)==null)return null
        val name=localUsername(target)
        if(source.frontend=="rsshub") {
            val path=when(target.platform) {
                Platform.X -> name?.let {"/twitter/user/${DarwinRoutes.encode(it)}"}
                Platform.BLUESKY -> name?.let {"/bsky/profile/${DarwinRoutes.encode(it)}"}
                Platform.TUMBLR -> name?.let {"/tumblr/posts/${DarwinRoutes.encode(it)}"}
                Platform.INSTAGRAM -> name?.let {"/instagram/user/${DarwinRoutes.encode(it)}"}
                Platform.PINTEREST -> name?.let {"/pinterest/user/${DarwinRoutes.encode(it)}/_created"}
                Platform.THREADS -> name?.let {"/threads/${DarwinRoutes.encode(it)}"}
                Platform.LINKEDIN -> name?.let {"/linkedin/company/${DarwinRoutes.encode(it)}/posts"}
                Platform.TIKTOK -> name?.let {"/tiktok/user/${DarwinRoutes.encode("@$it")}/false"}
                Platform.GETTR -> name?.let {"/gettr/user/${DarwinRoutes.encode(it)}"}
                Platform.GAB -> name?.let {"/gab/user/${DarwinRoutes.encode(it)}"}
                Platform.TELEGRAM -> name?.let {"/telegram/channel/${DarwinRoutes.encode(it)}"}
                Platform.MASTODON -> name?.let {"/mastodon/acct/${DarwinRoutes.encode("$it@${parsed.host}")}/statuses"}
                Platform.LEMMY -> name?.takeIf {target.kind==TargetKind.COMMUNITY}?.let {"/lemmy/${DarwinRoutes.encode("$it@${parsed.host}")}/New"}
                Platform.MISSKEY -> name?.let {"/misskey/users/notes/${DarwinRoutes.encode("$it@${parsed.host}")}"}
                Platform.TIEBA -> name?.takeIf {target.kind==TargetKind.COMMUNITY}?.let {"/baidu/tieba/forum/${DarwinRoutes.encode(it)}/created"}
                else -> null
            } ?: return null
            return source.root.trimEnd('/')+path
        }
        if(source.frontend!="rss-bridge")return null
        val fields=linkedMapOf("action" to "display","format" to "Atom")
        fun bridge(value: String,vararg values: Pair<String,String>){fields["bridge"]=value;fields.putAll(values)}
        when(target.platform) {
            Platform.X -> bridge("Twitter","context" to "By username","u" to (name ?: return null),"norep" to "on","noretweet" to "on","nopinned" to "on")
            Platform.BLUESKY -> bridge("Bluesky","context" to "Posts from a user","user_id" to (name ?: return null),"feed_filter" to "posts_no_replies")
            Platform.REDDIT -> if(target.kind==TargetKind.COMMUNITY&&parsed.pathSegments.firstOrNull()=="r")bridge("Reddit","context" to "single","r" to (name ?: return null),"d" to "new","score" to "-1")else if(target.kind==TargetKind.PROFILE&&parsed.pathSegments.firstOrNull() in setOf("u","user"))bridge("Reddit","context" to "user","u" to (name ?: return null),"d" to "new","score" to "-1")else return null
            Platform.INSTAGRAM -> bridge("Instagram","context" to "Username","u" to (name ?: return null),"media_type" to "all")
            Platform.FACEBOOK -> if(parsed.pathSegments.firstOrNull()=="profile.php")bridge("Facebook","context" to "User","u" to (parsed.queryParameter("id")?.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return null),"media_type" to "all","limit" to "20")else bridge("Facebook","context" to "User","u" to (name ?: return null),"media_type" to "all","limit" to "20")
            Platform.PINTEREST -> {val parts=parsed.pathSegments.filter(String::isNotBlank);if(parts.size!=2||parts.any {!it.matches(user)}||parts[1].startsWith('_'))return null;bridge("Pinterest","context" to "By username and board","u" to parts[0],"b" to parts[1])}
            Platform.TELEGRAM -> bridge("Telegram","username" to "@${name ?: return null}")
            Platform.TIKTOK -> bridge("TikTok","context" to "By user","username" to "@${name ?: return null}")
            Platform.GETTR -> bridge("Gettr","user" to (name ?: return null),"limit" to "20")
            Platform.GAB -> bridge("Gab","username" to (name ?: return null))
            Platform.MASTODON -> bridge("Mastodon","canusername" to "@${name ?: return null}@${parsed.host}","norep" to "on","noboost" to "on","signaturetype" to "nosig")
            else -> return null
        }
        return source.root.trimEnd('/')+"/?"+fields.entries.joinToString("&"){DarwinRoutes.encode(it.key)+"="+DarwinRoutes.encode(it.value)}
    }
    fun profileKey(platform: Platform,raw: String): String? {
        val parsed=url(raw) ?: return null;val host=parsed.host.removePrefix("www.");val parts=parsed.pathSegments.filter(String::isNotBlank)
        if(!parsed.isHttps||parsed.port!=443)return null
        val central=when(platform) {
            Platform.X -> host in setOf("x.com","twitter.com","mobile.twitter.com")
            Platform.BLUESKY -> host=="bsky.app"
            Platform.TUMBLR -> host=="tumblr.com"||host.endsWith(".tumblr.com")
            Platform.REDDIT -> host in setOf("reddit.com","old.reddit.com","m.reddit.com")
            Platform.THREADS -> host in setOf("threads.net","threads.com")
            Platform.TELEGRAM -> host in setOf("t.me","telegram.me")
            Platform.FACEBOOK -> host in setOf("facebook.com","m.facebook.com","mbasic.facebook.com","web.facebook.com")
            in federated -> server(Target(platform,TargetKind.PROFILE,"",raw))!=null
            else -> host==platform.searchDomain
        }
        if(!central)return null
        if(platform==Platform.FACEBOOK&&parts.firstOrNull()=="profile.php")return parsed.queryParameter("id")?.let {"facebook:$it"}
        if(platform==Platform.PINTEREST&&parts.size==2&&parts[0]!="pin")return "pinterest:"+parts.joinToString("/").lowercase()
        val kind=if(platform in setOf(Platform.LEMMY,Platform.TIEBA)||platform==Platform.REDDIT&&parts.firstOrNull()=="r")TargetKind.COMMUNITY else TargetKind.PROFILE
        val name=localUsername(Target(platform,kind,"",raw)) ?: return null
        return when(platform) {
            in federated -> "${platform.id}:$host:${name.lowercase()}"
            Platform.REDDIT -> "reddit:${if(parts.firstOrNull()=="r")"r"else "user"}:${name.lowercase()}"
            else -> "${platform.id}:${name.lowercase()}"
        }
    }
    fun entryMatches(target: Target,post: Post,feedVerified: Boolean): Boolean {
        val parsed=url(post.canonicalUrl) ?: return false;val selected=url(target.canonicalUrl) ?: return false
        val parts=parsed.pathSegments.filter(String::isNotBlank);val wanted=localUsername(target) ?: selected.queryParameter("id") ?: selected.pathSegments.firstOrNull()?.takeIf {target.platform==Platform.PINTEREST} ?: return false
        return when(target.platform) {
            Platform.X -> parts.size>=3&&parts[0].equals(wanted,true)&&parts[1] in setOf("status","statuses")
            Platform.BLUESKY -> parts.size==4&&parts[0]=="profile"&&parts[1].equals(wanted,true)&&parts[2]=="post"
            Platform.REDDIT -> if(target.kind==TargetKind.COMMUNITY)parts.size>=4&&parts[0]=="r"&&parts[1].equals(wanted,true)&&parts[2]=="comments"else post.author.removePrefix("u/").equals(wanted,true)
            Platform.TUMBLR -> parsed.host=="$wanted.tumblr.com"||parsed.host in setOf("www.tumblr.com","tumblr.com")&&parts.firstOrNull().equals(wanted,true)
            Platform.TIKTOK -> parts.firstOrNull()?.removePrefix("@").equals(wanted,true)
            Platform.THREADS -> parts.firstOrNull()?.removePrefix("@").equals(wanted,true)||feedVerified&&parts.size==2&&parts[0]=="t"
            Platform.GAB -> parts.firstOrNull().equals(wanted,true)
            Platform.TELEGRAM -> parts.filter {it!="s"}.firstOrNull().equals(wanted,true)
            Platform.MASTODON -> parsed.host==selected.host&&((parts.firstOrNull()?.removePrefix("@").equals(wanted,true))||parts.getOrNull(1).equals(wanted,true)&&parts.firstOrNull()=="users")
            Platform.MISSKEY -> feedVerified&&parsed.host==selected.host&&parts.firstOrNull()=="notes"
            Platform.LEMMY -> feedVerified&&parts.firstOrNull()=="post"
            Platform.GETTR -> feedVerified||post.author.equals(wanted,true)
            Platform.INSTAGRAM,Platform.PINTEREST,Platform.LINKEDIN,Platform.FACEBOOK,Platform.TIEBA -> feedVerified
            else -> false
        }
    }
}

private class PublicFeedPathwayAdapter(override val source: Source,private val gateway: NetworkGateway,private val routes: DarwinRoutes): DataAdapter {
    private val undatedSnapshot=source.frontend=="rss-bridge"&&source.platform==Platform.TIKTOK
    override val capabilities=if(undatedSnapshot)setOf(Capability.TIMELINE,Capability.MEDIA)else setOf(Capability.TIMELINE,Capability.FEED)
    override fun accepts(target: Target)=PublicFeedPathways.request(source,target)!=null
    override fun sentinel(capability: Capability): Target {
        if(source.platform==Platform.PINTEREST&&source.frontend=="rss-bridge")throw SourceFailure(HealthStatus.UNSUPPORTED,"Choose a public Pinterest board to establish this feed pathway")
        return FrontendAdapter(source,gateway,routes).sentinel(Capability.TIMELINE)
    }
    override suspend fun feed(target: Target): Page {
        if(undatedSnapshot)throw SourceFailure(HealthStatus.UNSUPPORTED,"This snapshot has no verified publication dates and cannot refresh a chronological feed")
        return fetch(target)
    }
    override suspend fun fetch(target: Target,cursor: String?): Page {
        val endpoint=PublicFeedPathways.request(source,target) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"This feed pathway does not support the selected profile or community")
        require(cursor==null){"Refresh this feed; this pathway has no verified continuation"}
        val payload=gateway.text(endpoint,target.canonicalUrl,maxBytes=2L*1024*1024,headers=mapOf("Accept" to "application/atom+xml,application/rss+xml,application/xml;q=0.9"))
        if(!DarwinRoutes.matches(URI(payload.finalUrl),source.root))throw SourceFailure(HealthStatus.UNAVAILABLE,"The feed pathway redirected outside its selected source")
        val message=Jsoup.parse(payload.text.take(100_000)).text().lowercase()
        if(message.contains("not whitelisted")||message.contains("not enabled")||message.contains("bridge is disabled")||message.contains("rss is disabled")||message.contains("route is disabled")||message.contains("confignotfounderror")||message.contains("require config"))throw SourceFailure(HealthStatus.UNSUPPORTED,"Feed pathway disabled or not configured on this instance")
        if(payload.text.contains('\u0000')||Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b",RegexOption.IGNORE_CASE).containsMatchIn(payload.text))throw SourceFailure(HealthStatus.UNSUPPORTED,"Feed declarations and external entities are not supported")
        val document=Jsoup.parse(payload.text,"",Parser.xmlParser());fun local(element: Element)=element.normalName().substringAfter(':')
        val container=document.children().firstOrNull {local(it) in setOf("rss","feed")}?.let {if(local(it)=="rss")it.children().firstOrNull {child->local(child)=="channel"}else it} ?: throw SourceFailure(HealthStatus.EMPTY,"The feed pathway returned a page shell instead of an XML feed")
        val selectedKey=PublicFeedPathways.profileKey(target.platform,target.canonicalUrl) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"This profile has no verified feed identity")
        val channelLinks=container.children().filter {local(it)=="link"&&it.attr("rel") in setOf("","alternate")}.map {it.attr("href").ifBlank {it.text()}}
        val keys=channelLinks.mapNotNull {PublicFeedPathways.profileKey(target.platform,it)}
        if(keys.any {it!=selectedKey})throw SourceFailure(HealthStatus.EMPTY,"The feed pathway returned a different account or community")
        val feedVerified=selectedKey in keys
        if(!feedVerified&&target.platform !in setOf(Platform.X,Platform.REDDIT,Platform.GETTR,Platform.GAB,Platform.TELEGRAM,Platform.TIKTOK,Platform.THREADS,Platform.TUMBLR,Platform.BLUESKY,Platform.MASTODON))throw SourceFailure(HealthStatus.EMPTY,"The feed pathway did not identify its selected account or community")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val posts=Extraction.rss(payload.text,target,provenance,routes).posts.filter {post->
            val identity=routes.recognize(post.canonicalUrl,target.platform)
            identity?.platform==target.platform&&identity.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&(post.publishedAt!=null||undatedSnapshot)&&(post.body.isNotBlank()||post.title.isNotBlank()||post.media.isNotEmpty())&&PublicFeedPathways.entryMatches(target,post,feedVerified)
        }.map {it.copy(id=ContentIdentity.key(it.platform,it.canonicalUrl),publishedAt=if(undatedSnapshot)null else it.publishedAt)}.distinctBy {it.id}.sortedByDescending {it.publishedAt}.take(100)
        if(posts.isEmpty())throw SourceFailure(HealthStatus.EMPTY,if(undatedSnapshot)"The pathway returned no readable posts for this selected account"else "The feed pathway returned no dated readable posts for this selected account or community")
        return Page(posts,provenance=provenance,notice=if(undatedSnapshot)"Publication dates are unavailable. These posts appear only in this profile snapshot, outside the chronological feed."else "Public feed snapshot; earlier pages and comments may need another source.")
    }
}
