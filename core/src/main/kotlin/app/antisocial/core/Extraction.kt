package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import com.google.gson.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.time.*
import java.time.format.DateTimeFormatter

object Extraction {
    private val gson=Gson()
    fun timestamp(value: String?): Long? {
        if(value.isNullOrBlank())return null
        value.toLongOrNull()?.let { return if(it<10_000_000_000L)it*1000 else it }
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching {LocalDateTime.parse(value,DateTimeFormatter.ofPattern("uuuu年M月d日 H点m分")).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()}.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value,DateTimeFormatter.ofPattern("MMM dd, uuuu HH:mm:ss VV",java.util.Locale.ENGLISH)).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value,DateTimeFormatter.ofPattern("MMM dd uuuu, HH:mm:ss z",java.util.Locale.ENGLISH)).toInstant().toEpochMilli() }.getOrNull()
            ?: listOf("MMM d, uuuu · h:mm a z", "EEE MMM dd HH:mm:ss Z uuuu", "MMM dd, uuuu, hh:mm:ss a").firstNotNullOfOrNull { pattern -> runCatching { ZonedDateTime.parse(value.replace(" UTC", " GMT"),DateTimeFormatter.ofPattern(pattern,java.util.Locale.ENGLISH).withZone(ZoneOffset.UTC)).toInstant().toEpochMilli() }.getOrNull() }
    }
    fun plain(html: String): String=Jsoup.parse(html).apply { select("script,style,iframe,form").remove() }.text()
    private fun safe(url: String, base: String): String? = if(url.isBlank())null else runCatching { URI(base).resolve(url.trim()).toString().takeIf { URI(it).scheme=="https" } }.getOrNull()
    private fun text(o: JsonObject, vararg names: String): String = names.firstNotNullOfOrNull { name -> o.get(name)?.takeIf { it.isJsonPrimitive }?.asString } ?: ""
    /** A displayed Bluesky link may be abbreviated. Its facet/embed still
     * identifies the actual destination without changing the original caption.
     * These are data links only; collecting them never makes another request.
     */
    private fun blueskyLinks(record: JsonObject,embed: JsonObject?): List<String> {
        fun https(value: String)=runCatching {URI(value.trim()).takeIf {it.scheme=="https"&&!it.host.isNullOrBlank()&&it.userInfo==null}?.toString()}.getOrNull()
        fun objectField(value: JsonObject?,name: String)=value?.get(name)?.takeIf {it.isJsonObject}?.asJsonObject
        val links=linkedSetOf<String>()
        fun add(value: String){if(links.size<12)https(value)?.let {links+=it}}
        record["facets"]?.takeIf {it.isJsonArray}?.asJsonArray?.take(64)?.forEach {facet->
            facet.takeIf {it.isJsonObject}?.asJsonObject?.get("features")?.takeIf {it.isJsonArray}?.asJsonArray?.take(8)?.forEach {feature->
                feature.takeIf {it.isJsonObject}?.asJsonObject?.takeIf {text(it,"\$type")=="app.bsky.richtext.facet#link"}?.let {add(text(it,"uri"))}
            }
        }
        val rawEmbed=objectField(record,"embed")
        listOf(rawEmbed,embed,objectField(rawEmbed,"media"),objectField(embed,"media")).forEach {value->
            objectField(value,"external")?.let {add(text(it,"uri"))}
        }
        return links.toList()
    }
    fun parse(payload: Payload, target: Target, source: Source, routes: DarwinRoutes, now: Long = System.currentTimeMillis(),publicSearchQuery: String?=null,publicSearchEntitiesOnly: Boolean=false): Page {
        if(target.platform==Platform.RSS)return RssSources.parse(payload,target,now)
        val host=runCatching {URI(payload.finalUrl).host}.getOrNull().orEmpty()
        val original=source.platform.origin.let {URI(it).host.removePrefix("www.")}
        val actual=if(source.role==SourceRole.ALTERNATE && (host==original||host.endsWith(".$original")))source.copy(frontend="original",root=source.platform.origin,role=SourceRole.ORIGINAL)else source
        val provenance=Provenance(actual,now,payload.finalUrl)
        val body=payload.text.trim()
        val page=when {
            actual.frontend=="bing-rss" -> PublicSearchAdapter.rss(body,target,routes,publicSearchEntitiesOnly)
            actual.frontend=="duckduckgo" -> PublicSearchAdapter.duckDuckGo(Jsoup.parse(body,payload.finalUrl),target,routes,publicSearchQuery ?: PublicSearchAdapter.query(target),publicSearchEntitiesOnly)
            body.startsWith("{") || body.startsWith("[") -> json(JsonParser.parseString(body),target,provenance,routes)
            body.startsWith("<?xml") || payload.contentType.contains("xml",true)&&!payload.contentType.contains("xhtml",true) || Regex("^<(?:[a-z0-9_]+:)?(rss|feed)(?:\\s|>)",RegexOption.IGNORE_CASE).containsMatchIn(body) -> rss(body,target,provenance,routes)
            else -> html(body,target,provenance,routes,publicSearchQuery,publicSearchEntitiesOnly)
        }
        val indexContinuation=target.kind==TargetKind.SEARCH&&actual.frontend in PublicSearchAdapter.frontends&&page.next!=null
        if(!indexContinuation&&page.posts.isEmpty() && page.collections.none {!it.navigation} && page.accounts.isEmpty() && (page.profile==null||target.kind !in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)))throw SourceFailure(HealthStatus.EMPTY,"The source returned no recognizable content")
        val labeled=PlatformAiLabels.attach(page.posts,payload,target,provenance)
        return page.copy(provenance=provenance,profile=page.profile?.let {it.copy(links=(it.links.orEmpty()+Subscriptions.urls(it.description)).filter(Subscriptions::publicHttps).distinct().take(24))},posts=labeled.map {post->
            val inText=if(post.platform==Platform.X&&!post.links.isNullOrEmpty())emptyList()else Regex("https://[^\\s<>]+",RegexOption.IGNORE_CASE).findAll(post.body).map {it.value.trimEnd('.',',',')',']','}','>',';','\"')}.toList()
            val links=(post.links.orEmpty()+inText).asSequence().mapNotNull {safe(it,provenance.finalUrl)}.filter {it!=post.canonicalUrl}.distinct().take(PostLinks.MAX_LINKS).toList()
            post.copy(id=ContentIdentity.key(post.platform,post.canonicalUrl),parentId=post.parentId?.let {if(it.startsWith("https://"))ContentIdentity.key(post.platform,it)else it},links=links)
        }.distinctBy {it.id})
    }
    fun rss(body: String, target: Target, provenance: Provenance, routes: DarwinRoutes): Page {
        // This parser does not resolve external resources. Reject declarations as
        // well, independent of Android's unsupported JAXP security-feature flags.
        if(body.contains('\u0000')||Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b",RegexOption.IGNORE_CASE).containsMatchIn(body))throw SourceFailure(HealthStatus.UNSUPPORTED,"Feed declarations and external entities are not supported")
        val document=Jsoup.parse(body,provenance.finalUrl,Parser.xmlParser())
        fun local(element: Element)=element.normalName().substringAfter(':')
        val items=document.getAllElements().filter {local(it)=="item"}
        val nodes=if(items.isNotEmpty())items else document.getAllElements().filter {local(it)=="entry"}
        val posts=nodes.take(200).mapNotNull { e ->
            val elements=e.getAllElements()
            fun tag(vararg names: String)=names.firstNotNullOfOrNull { name -> val n=name.lowercase();elements.filter {if(n.contains(':'))it.normalName()==n else local(it)==n}.firstNotNullOfOrNull {it.wholeText().takeIf {text->text.isNotBlank()}} } ?: ""
            val links=elements.filter {local(it)=="link"}
            val webLink=links.firstOrNull {it.attr("rel")=="alternate"} ?: links.firstOrNull {it.attr("rel").isBlank()}
            val raw=webLink?.wholeText()?.takeIf {it.isNotBlank()} ?: webLink?.attr("href").orEmpty()
            val link=safe(raw,provenance.finalUrl) ?: return@mapNotNull null
            val canonical=routes.recognize(link)?.canonicalUrl ?: link
            val content=tag("content:encoded","content","description","summary")
            val markup=Jsoup.parse(content,provenance.finalUrl)
            val enclosed=elements.filter {local(it) in setOf("enclosure","content","thumbnail")&&it.hasAttr("url")}.mapNotNull {m->safe(m.attr("url"),provenance.finalUrl)?.let {
                if(m.attr("type").startsWith("audio/"))null else {
                    val isVideo=VideoExtraction.mime(m.attr("type"))!=null||m.attr("type").startsWith("video/")
                    Media(it,if(isVideo)"video"else "image",mimeType=VideoExtraction.mime(m.attr("type")),durationMs=if(isVideo&&m.normalName()=="media:content")MediaDuration.seconds(m.attr("duration"))else null)
                }
            }}
            val media=MediaDuration.distinctMedia(markup.select("img[src]").mapNotNull { m -> safe(m.attr("src"),provenance.finalUrl)?.let { Media(it,description=m.attr("alt")) } }+VideoExtraction.html(markup,provenance.finalUrl)+enclosed)
            Post(canonical,target.platform,canonical,tag("dc:creator","name","author").ifBlank { if(target.kind==TargetKind.PROFILE)target.identity else "" },plain(content),tag("title"),timestamp(tag("pubDate","published","updated")),media=media,provenance=provenance,links=markup.select("a[href]").mapNotNull {safe(it.attr("href"),provenance.finalUrl)}.filter {it!=canonical})
        }
        val next=document.getAllElements().firstOrNull {local(it)=="link"&&it.attr("rel")=="next"&&it.parent()?.let {parent->local(parent) in setOf("feed","channel")}==true}?.attr("href")?.let {safe(it,provenance.finalUrl)}
        return Page(posts,next=next)
    }
    private fun redditProfile(node: JsonObject,routes: DarwinRoutes): Profile? {
        val data=node["data"]?.takeIf {it.isJsonObject}?.asJsonObject ?: return null
        if(text(data,"subreddit_type")=="private"||text(data,"is_suspended")=="true")return null
        return when(text(node,"kind")) {
            "t5"->{
                val name=text(data,"display_name")
                val selected=safe(text(data,"url"),Platform.REDDIT.origin)?.let {routes.recognize(it)} ?: return null
                if(!PublicSearchAdapter.communityAllowed(selected)||!selected.identity.removePrefix("r/").equals(name,true))return null
                val icon=listOf("community_icon","icon_img").firstNotNullOfOrNull {safe(Parser.unescapeEntities(text(data,it),false),Platform.REDDIT.origin)}
                Profile(selected,text(data,"title").ifBlank {name},plain(text(data,"public_description")),icon,
                    data["subscribers"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asString?.toLongOrNull()?.takeIf {it>=0})
            }
            "t2"->{
                val name=text(data,"name")
                if(!name.matches(Regex("[A-Za-z0-9_-]{1,100}"))||text(data,"id").isBlank())return null
                val selected=routes.recognize(Platform.REDDIT.origin+"/user/$name") ?: return null
                val subreddit=data["subreddit"]?.takeIf {it.isJsonObject}?.asJsonObject
                Profile(selected,subreddit?.let {text(it,"title")}.orEmpty().ifBlank {name},subreddit?.let {plain(text(it,"public_description"))}.orEmpty(),safe(Parser.unescapeEntities(text(data,"icon_img"),false),Platform.REDDIT.origin),displayNameSupplied=subreddit?.let {text(it,"title")}.orEmpty().isNotBlank())
            }
            else->null
        }
    }
    /** A genuine selected Lemmy entity view, with actor ownership checked even
     * when a selected server represents a remote community/person locally. */
    fun lemmyProfile(value: JsonObject,target: Target): Profile? {
        if(target.platform!=Platform.LEMMY||target.kind !in setOf(TargetKind.COMMUNITY,TargetKind.PROFILE))return null
        val requested=target.canonicalUrl.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        val parts=requested.pathSegments.filter(String::isNotBlank)
        val community=target.kind==TargetKind.COMMUNITY
        if(parts.size!=2||parts[0]!=(if(community)"c"else "u"))return null
        val view=value[if(community)"community_view"else "person_view"]?.takeIf {it.isJsonObject}?.asJsonObject ?: return null
        val entity=view[if(community)"community"else "person"]?.takeIf {it.isJsonObject}?.asJsonObject ?: return null
        if(text(entity,"deleted")=="true"||text(entity,"removed")=="true"||text(entity,"visibility").equals("private",true))return null
        val name=text(entity,"name")
        val selectedName=parts[1].substringBefore('@')
        if(name.isBlank()||!name.equals(selectedName,true))return null
        val actor=(text(entity,"actor_id","ap_id")).toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        val expectedHost=parts[1].substringAfter('@',requested.host)
        if(!actor.host.equals(expectedHost,true)||actor.pathSegments.filter(String::isNotBlank).let {it.size!=2||it[0]!=parts[0]||!it[1].equals(name,true)})return null
        if('@' !in parts[1]&&actor.port!=requested.port)return null
        val counts=view["counts"]?.takeIf {it.isJsonObject}?.asJsonObject
        return Profile(target,text(entity,if(community)"title"else "display_name").ifBlank {name},text(entity,if(community)"description"else "bio"),safe(text(entity,if(community)"icon"else "avatar"),target.canonicalUrl),
            if(community)counts?.get("subscribers")?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asString?.toLongOrNull()?.takeIf {it>=0}else null,displayNameSupplied=text(entity,if(community)"title"else "display_name").isNotBlank())
    }
    fun json(value: JsonElement, target: Target, provenance: Provenance, routes: DarwinRoutes): Page {
        val posts=mutableListOf<Post>()
        val accounts=(if(target.platform==Platform.BLUESKY&&target.kind==TargetKind.SEARCH&&value.isJsonObject)value.asJsonObject["actors"]?.takeIf {it.isJsonArray}?.asJsonArray?.asSequence()?.mapNotNull {entry->
            val actor=entry.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val handle=text(actor,"handle").takeIf {it.isNotBlank()} ?: return@mapNotNull null
            routes.recognize("https://bsky.app/profile/$handle")?.let {Profile(it,text(actor,"displayName").ifBlank {handle},text(actor,"description"),safe(text(actor,"avatar"),provenance.finalUrl),displayNameSupplied=text(actor,"displayName").isNotBlank())}
        }?.take(30)?.toList().orEmpty()else emptyList()).toMutableList()
        val identities=mutableMapOf<String,String>()
        var profile: Profile?=if(target.platform==Platform.LEMMY&&value.isJsonObject)lemmyProfile(value.asJsonObject,target)else null
        if(target.platform==Platform.REDDIT&&value.isJsonObject) {
            val root=value.asJsonObject
            val entities=if(text(root,"kind")=="Listing")root["data"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("children")?.takeIf {it.isJsonArray}?.asJsonArray?.take(50).orEmpty()else listOf(root)
            val actual=entities.mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject?.let {node->redditProfile(node,routes)}}
            if(target.kind==TargetKind.SEARCH)accounts+=actual else profile=actual.firstOrNull {PublicSearchAdapter.entityKey(it.target)==PublicSearchAdapter.entityKey(target)}?.copy(target=target)
        }
        var next: String?=null
        var visited=0
        fun walk(v: JsonElement, depth: Int=0) {
            if(depth>32 || posts.size>=200||visited++>=20_000)return
            if(v.isJsonArray) {v.asJsonArray.forEach { walk(it,depth+1) };return}
            if(!v.isJsonObject)return
            val o=v.asJsonObject
            if(target.platform==Platform.TIKTOK&&target.kind==TargetKind.POST&&text(o,"provider_name")=="TikTok"&&o.has("author_url")) {
                val thumbnail=safe(text(o,"thumbnail_url"),provenance.finalUrl)
                val title=text(o,"title")
                if(title.isNotBlank()||thumbnail!=null)posts+=Post(target.canonicalUrl,target.platform,target.canonicalUrl,text(o,"author_name"),title,media=listOfNotNull(thumbnail?.let {Media(it,description="Video preview; this source did not supply a playable video")}),provenance=provenance,preview=true)
            }
            // Public JSON-LD is data, never executable page script. Website,
            // organization and profile branding are deliberately excluded.
            val schemaType=text(o,"@type")
            if(schemaType in setOf("SocialMediaPosting","Article","NewsArticle","VideoObject","ImageObject")) {
                val declaredLink=text(o,"url","mainEntityOfPage").ifBlank {o["mainEntityOfPage"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"@id","url")}.orEmpty()}.ifBlank {text(o,"@id")}
                val rawLink=declaredLink.ifBlank {if(target.kind==TargetKind.POST)target.canonicalUrl else ""}
                val item=safe(rawLink,provenance.finalUrl)?.let {routes.recognize(it)}
                if(item?.kind==TargetKind.POST&&item.platform==target.platform) {
                    val author=o["author"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"name")}.orEmpty()
                    val image=o["image"]?.let {if(it.isJsonPrimitive)it.asString else if(it.isJsonObject)text(it.asJsonObject,"url","contentUrl")else ""}.orEmpty().ifBlank {text(o,"thumbnailUrl")}
                    val content=safe(text(o,"contentUrl"),provenance.finalUrl)
                    val media=listOfNotNull(if(schemaType=="VideoObject")content?.let {Media(it,"video",safe(image,provenance.finalUrl))}else safe(image.ifBlank {content.orEmpty()},provenance.finalUrl)?.let {Media(it)})
                    val body=plain(text(o,"articleBody","description","caption"))
                    // Some ImageObject metadata names the social webpage as
                    // contentUrl. It is not an image and must not displace the
                    // native content record carrying the actual media URL.
                    val webpageMedia=schemaType in setOf("ImageObject","VideoObject")&&media.any {routes.recognize(it.url)?.kind==TargetKind.POST}
                    // A generic image or recommended record with no identity may
                    // still be a preview, but cannot disclose AI use for this post.
                    val declarations=if(declaredLink.isNotBlank())StructuredAiMetadata.declarations(o).takeIf {it.isNotEmpty()}else null
                    if(!webpageMedia&&(body.isNotBlank()||media.isNotEmpty()))posts+=Post(item.canonicalUrl,target.platform,item.canonicalUrl,author,body,text(o,"headline","name"),timestamp(text(o,"datePublished","uploadDate")),media=media,provenance=provenance,aiDisclosures=declarations)
                }
            }
            if(target.platform==Platform.THREADS&&o.has("text_post_app_info")&&o.has("code")&&o["user"]?.isJsonObject==true) {
                val user=o["user"].asJsonObject;val handle=text(user,"username")
                val caption=o["caption"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"text")}.orEmpty()
                val candidate=o["image_versions2"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("candidates")?.takeIf {it.isJsonArray}?.asJsonArray?.firstOrNull()?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"url"),provenance.finalUrl)}
                val video=o["video_versions"]?.takeIf {it.isJsonArray}?.asJsonArray?.firstOrNull()?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"url"),provenance.finalUrl)}
                val link="https://www.threads.net/@$handle/post/${text(o,"code") }"
                if(handle.isNotBlank()&&(caption.isNotBlank()||candidate!=null||video!=null))posts+=Post(link,target.platform,link,handle,caption,publishedAt=timestamp(text(o,"taken_at")),avatar=safe(text(user,"profile_pic_url"),provenance.finalUrl),media=listOfNotNull(video?.let {Media(it,"video",candidate,durationMs=MediaDuration.seconds(o["video_duration"]))} ?: candidate?.let {Media(it)}),provenance=provenance)
            }
            if(target.platform==Platform.PINTEREST&&o.has("id")&&o["images"]?.isJsonObject==true&&o["pinner"]?.isJsonObject==true) {
                val pinner=o["pinner"].asJsonObject;val images=o["images"].asJsonObject
                val image=(images["orig"] ?: images["originals"] ?: images["736x"])?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"url"),provenance.finalUrl)}
                val link="https://www.pinterest.com/pin/${text(o,"id") }"
                if(image!=null)posts+=Post(link,target.platform,link,text(pinner,"username","full_name"),text(o,"description"),text(o,"title","grid_title"),timestamp(text(o,"created_at")),media=listOf(Media(image)),provenance=provenance)
            }
            if(target.platform==Platform.IFUNNY&&o.has("created")&&o.has("link")&&o["creator"]?.isJsonObject==true) {
                val link=safe(text(o,"link","canonical"),provenance.finalUrl)
                val creator=o["creator"].asJsonObject
                val media=safe(text(o,"url"),provenance.finalUrl)
                val avatar=creator["avatar"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"url","m"),provenance.finalUrl)}
                val preview=o["thumb"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"xl","l","m"),provenance.finalUrl)}
                if(link!=null&&media!=null&&routes.recognize(link)?.kind==TargetKind.POST)posts+=Post(link,target.platform,link,text(creator,"nick"),listOf(text(o,"captionText"),text(o,"description"),text(o,"captionBottomText")).filter {it.isNotBlank()}.joinToString("\n"),text(o,"title"),timestamp(text(o,"published","created")),avatar,listOf(Media(media,if(text(o,"type")=="video")"video"else "image",preview)),o["comments"]?.takeIf {it.isJsonPrimitive}?.asLong,o["smiles"]?.takeIf {it.isJsonPrimitive}?.asLong,provenance=provenance)
            }
            if(o.has("cursor") && o["cursor"].isJsonPrimitive)next=o["cursor"].asString
            if(target.platform==Platform.REDDIT&&o["children"]?.isJsonArray==true&&o["after"]?.isJsonPrimitive==true)next=o["after"].asString
            // Reddit listing and comments share the same public listing structure.
            if(target.platform==Platform.REDDIT && o.has("permalink") && (o.has("title") || o.has("body"))) {
                val rawLink=safe(text(o,"permalink"),target.platform.origin) ?: target.canonicalUrl
                val link=routes.recognize(rawLink)?.canonicalUrl ?: rawLink
                identities[text(o,"name","id")]=link
                val media=mutableListOf<Media>()
                text(o,"url_overridden_by_dest","url").takeIf { it.matches(Regex("https://.*\\.(?:png|jpg|jpeg|gif|webp)(?:\\?.*)?",RegexOption.IGNORE_CASE)) }?.let { media+=Media(it) }
                VideoExtraction.reddit(o,provenance.finalUrl)?.let {media+=it}
                posts+=Post(link,target.platform,link,text(o,"author"),text(o,"selftext","body"),text(o,"title"),o["created_utc"]?.takeIf {it.isJsonPrimitive}?.asDouble?.times(1000)?.toLong(),media=media,replyCount=o["num_comments"]?.takeIf {it.isJsonPrimitive}?.asLong,score=o["score"]?.takeIf {it.isJsonPrimitive}?.asLong,parentId=text(o,"parent_id").ifBlank { null },provenance=provenance,relationId=text(o,"name","id").ifBlank {null},sourceParentId=text(o,"parent_id").ifBlank {null},conversationId=text(o,"link_id").ifBlank {null},links=listOfNotNull(safe(text(o,"url_overridden_by_dest","url"),provenance.finalUrl)?.takeIf {u->media.none {it.url==u}&&u!=link}))
            }
            // Bluesky's public API embeds the post record and creator.
            val record=o.get("record")?.takeIf { it.isJsonObject }?.asJsonObject
            if(target.platform==Platform.BLUESKY && record?.has("text")==true && o.has("uri")) {
                val author=o.get("author")?.takeIf { it.isJsonObject }?.asJsonObject
                val handle=author?.let { text(it,"handle","did") }.orEmpty()
                val link="https://bsky.app/profile/$handle/post/${text(o,"uri").substringAfterLast('/')}"
                val embed=o.get("embed")?.takeIf {it.isJsonObject}?.asJsonObject
                val mediaEmbed=embed?.get("media")?.takeIf {it.isJsonObject}?.asJsonObject ?: embed
                val images=mediaEmbed?.get("images")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { im ->val x=im.asJsonObject;safe(text(x,"fullsize","thumb"),provenance.finalUrl)?.let { Media(it,preview=text(x,"thumb").takeIf {it.isNotBlank()},description=text(x,"alt")) } } ?: emptyList()
                val videos=mediaEmbed?.let {e->safe(text(e,"playlist"),provenance.finalUrl)?.let {listOf(Media(it,"video",safe(text(e,"thumbnail"),provenance.finalUrl),text(e,"alt"),"application/x-mpegURL"))}}.orEmpty()
                posts+=Post(link,target.platform,link,handle,text(record,"text"),publishedAt=timestamp(text(record,"createdAt")),avatar=author?.let { text(it,"avatar") }?.takeIf { it.startsWith("https://") },media=images+videos,replyCount=o["replyCount"]?.takeIf {it.isJsonPrimitive}?.asLong,parentId=record["reply"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("parent")?.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"uri")},provenance=provenance,links=blueskyLinks(record,embed),relationId=text(o,"uri").ifBlank {null},
                    sourceParentId=record["reply"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("parent")?.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"uri")},
                    conversationId=record["reply"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("root")?.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"uri")})
                identities[text(o,"uri")]=link
            }
            // TikTok's embedded public data contains itemStruct objects.
            if(target.platform==Platform.TIKTOK && o["video"]?.isJsonObject==true && o.has("desc") && o.has("id")) {
                val author=o.get("author")?.takeIf { it.isJsonObject }?.asJsonObject
                val handle=author?.let { text(it,"uniqueId","nickname") }.orEmpty()
                val video=o.getAsJsonObject("video");val media=text(video,"playAddr","downloadAddr").takeIf { it.startsWith("https://") }?.let { listOf(Media(it,"video",text(video,"cover"))) } ?: emptyList()
                val link="https://www.tiktok.com/@$handle/video/${text(o,"id")}"
                posts+=Post(link,target.platform,link,handle,text(o,"desc"),publishedAt=timestamp(text(o,"createTime")),media=media,provenance=provenance)
            }
            if(target.platform==Platform.INSTAGRAM && (o.has("shortcode") || (o.has("caption") && o.has("timestamp")))) {
                val shortcode=text(o,"shortcode","code","id")
                val caption=o["caption"]?.let { if(it.isJsonPrimitive)it.asString else if(it.isJsonObject)text(it.asJsonObject,"text") else "" }.orEmpty()
                val owner=o["owner"]?.takeIf { it.isJsonObject }?.asJsonObject
                val mediaUrl=text(o,"video_url","display_url","image_url","thumbnail_src")
                val link="https://www.instagram.com/p/$shortcode"
                val isVideo=text(o,"video_url").startsWith("https://")
                posts+=Post(link,target.platform,link,owner?.let { text(it,"username") }.orEmpty(),caption,publishedAt=timestamp(text(o,"timestamp","taken_at_timestamp")),media=mediaUrl.takeIf { it.startsWith("https://") }?.let { listOf(Media(it,if(isVideo)"video" else "image",durationMs=if(isVideo)MediaDuration.seconds(o["video_duration"])else null)) } ?: emptyList(),provenance=provenance)
            }
            if(target.platform==Platform.X && (o.has("full_text")||o.has("text")) && o.has("id_str")&&o["user"]?.isJsonObject==true) {
                val user=o["user"]?.takeIf { it.isJsonObject }?.asJsonObject
                val handle=user?.let { text(it,"screen_name") }.orEmpty()
                val link="https://x.com/$handle/status/${text(o,"id_str")}"
                val images=o["photos"]?.takeIf {it.isJsonArray}?.asJsonArray?.mapNotNull {photo->photo.takeIf {it.isJsonObject}?.asJsonObject?.let {p->safe(text(p,"url"),provenance.finalUrl)?.let {Media(it)}}}.orEmpty()
                val links=o["entities"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("urls")?.takeIf {it.isJsonArray}?.asJsonArray?.mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject?.let {u->safe(text(u,"expanded_url","url"),provenance.finalUrl)}}.orEmpty()
                val videos=o["video"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {video->VideoExtraction.xVideo(video,provenance.finalUrl,safe(text(video,"poster"),provenance.finalUrl))}?.let(::listOf).orEmpty()
                val attachments=(o["extended_entities"] ?: o["entities"])?.takeIf {it.isJsonObject}?.asJsonObject?.get("media")?.takeIf {it.isJsonArray}?.asJsonArray?.take(16)?.mapNotNull {entry->
                    val item=entry.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
                    val preview=safe(text(item,"media_url_https","media_url"),provenance.finalUrl)
                    if(text(item,"type") in setOf("video","animated_gif"))item["video_info"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {VideoExtraction.xVideo(it,provenance.finalUrl,preview,text(item,"ext_alt_text"))}
                    else preview?.let {Media(it,description=text(item,"ext_alt_text"))}
                }.orEmpty()
                posts+=Post(link,target.platform,link,handle,text(o,"full_text","text"),publishedAt=timestamp(text(o,"created_at")),avatar=user?.let { text(it,"profile_image_url_https") },media=MediaDuration.distinctMedia(images+videos+attachments),replyCount=o["conversation_count"]?.takeIf {it.isJsonPrimitive}?.asLong,provenance=provenance,links=links,relationId=text(o,"id_str"),
                    sourceParentId=text(o,"in_reply_to_status_id_str","in_reply_to_status_id").ifBlank {null},parentId=text(o,"in_reply_to_status_id_str","in_reply_to_status_id").ifBlank {null}?.let {"post:$it"},
                    conversationId=text(o,"conversation_id_str","conversation_id").ifBlank {null}?.let {"post:$it"},
                    extractionCoverage=if(text(o,"in_reply_to_status_id_str","in_reply_to_status_id").isBlank()&&text(o,"conversation_id_str","conversation_id").isNotBlank()&&text(o,"conversation_id_str","conversation_id")!=text(o,"id_str"))ExtractionCoverage(text=if(text(o,"full_text","text").isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,attachments=if((images+videos+attachments).isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,replyParent=ExtractionStatus.UNAVAILABLE)else null)
            }
            val wanted=target.identity.removePrefix("profile/").removePrefix("@").trim('/')
            fun profileLinks(): List<String> {
                val links=listOf("external_url","externalUrl","website","url","bio_url").mapNotNull {Subscriptions.httpsLink(text(o,it))}.toMutableList()
                o["bioLink"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"link"),provenance.finalUrl)?.let(links::add)}
                o["bio_links"]?.takeIf {it.isJsonArray}?.asJsonArray?.take(24)?.forEach {link->if(link.isJsonObject)safe(text(link.asJsonObject,"url"),provenance.finalUrl)?.let(links::add)}
                return links.distinct().take(24)
            }
            if(target.kind==TargetKind.PROFILE&&target.platform==Platform.TIKTOK&&text(o,"uniqueId").equals(wanted,true)&&o.has("nickname"))profile=Profile(target,text(o,"nickname","uniqueId"),text(o,"signature"),safe(text(o,"avatarMedium","avatarLarger"),provenance.finalUrl),links=profileLinks(),displayNameSupplied=text(o,"nickname").isNotBlank())
            if(target.kind==TargetKind.PROFILE&&target.platform==Platform.IFUNNY&&text(o,"nick").equals(wanted.removePrefix("user/"),true)&&o.has("about"))profile=Profile(target,text(o,"nick"),text(o,"about"),o["avatar"]?.takeIf {it.isJsonObject}?.asJsonObject?.let {safe(text(it,"m","l"),provenance.finalUrl)})
            val sameCreator=listOf("handle","username","did").any {text(o,it).equals(wanted,true)}
            if(target.kind==TargetKind.PROFILE && sameCreator && (o.has("handle")&&(o.has("displayName")||o.has("description")) || o.has("biography")&&o.has("username")))profile=Profile(target,text(o,"displayName","full_name","username","handle"),text(o,"description","biography"),text(o,"avatar","profile_pic_url").takeIf { it.startsWith("https://") },links=profileLinks(),displayNameSupplied=text(o,"displayName","full_name").isNotBlank(),persistentId=if(target.platform==Platform.BLUESKY)PublicSourceRecords.account(text(o,"did"))?.identity else null)
            o.entrySet().forEach { (_,child)->if(child.isJsonArray || child.isJsonObject)walk(child,depth+1) }
        }
        walk(value)
        return Page(StructuredAiMetadata.mergeExactPosts(posts).map {post->post.copy(parentId=identities[post.parentId] ?: post.parentId)},next,profile,accounts=accounts)
    }
    fun html(body: String, target: Target, provenance: Provenance, routes: DarwinRoutes,publicSearchQuery: String?=null,publicSearchEntitiesOnly: Boolean=false): Page {
        val d=Jsoup.parse(body,provenance.finalUrl)
        d.selectFirst(".disabled-feature")?.let {node->throw SourceFailure(HealthStatus.UNSUPPORTED,node.selectFirst("h1,h2")?.text().orEmpty().ifBlank {"This feature is disabled on this source"})}
        val head=d.title()+" "+d.body().text().take(800)
        if(d.select("#challenge-container,#turnstile-widget-container,#anubis-main,#captcha-area").isNotEmpty() || Regex("making sure you.re not a bot|checking your browser|just a moment|verify you are human",RegexOption.IGNORE_CASE).containsMatchIn(head))throw SourceFailure(HealthStatus.CHALLENGE,"This source requires a browser challenge")
        if(Regex("log in to continue|login required|sign in to view|account required|suspended domain|domain is for sale|upstream error|internal server error",RegexOption.IGNORE_CASE).containsMatchIn(head))throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"The source did not deliver public content")
        if(sourceFrontend(provenance)=="librey")return PublicSearchAdapter.extract(d,target,routes,publicSearchQuery ?: PublicSearchAdapter.query(target),publicSearchEntitiesOnly)
        val embedded=mutableListOf<Post>()
        var embeddedProfile: Profile?=null
        fun retainEmbedded(page: Page){embedded.addAll(page.posts);if(embeddedProfile==null)embeddedProfile=page.profile}
        d.select("script[type=application/json],script[type=application/ld+json],script#__UNIVERSAL_DATA_FOR_REHYDRATION__,script#SIGI_STATE").forEach { script ->runCatching { json(JsonParser.parseString(script.data()),target,provenance,routes) }.getOrNull()?.let(::retainEmbedded) }
        if(target.platform==Platform.IFUNNY)d.select("script").forEach {script->
            val prefix=Regex("^\\s*window\\.__INITIAL_STATE__\\s*=\\s*").find(script.data()) ?: return@forEach
            // Parse a single JSON value after the literal assignment. Trailing
            // script is ignored, and no part of the script is executed.
            runCatching {json(JsonParser.parseReader(com.google.gson.stream.JsonReader(script.data().substring(prefix.range.last+1).reader())),target,provenance,routes)}.getOrNull()?.let(::retainEmbedded)
        }
        val selectors=when(sourceFrontend(provenance)) {
            "nitter" -> ".timeline-item,.main-tweet"
            "redlib","libreddit","teddit","eddrit","troddit" -> "div.post,.thing,.comment"
            "ratAintTieba" -> ".l_post,.thread,.post"
            "kittygram" -> ".post,.tile-item,.grid-item"
            "pinless","binternet","painterest" -> ".pin-container,.pins-grid > div,.grid-container > div,.pin"
            "proxiTok" -> if(target.kind==TargetKind.POST)".columns.is-centered,article.media"else "article.media,[data-video_share_url]"
            "shoelace" -> ".post"
            else -> "article,.timeline-item,.post,.subpost,.post-card,.pin,.video-card"
        }
        val selected=d.select(selectors)
        val selectedNodes=selected.toHashSet()
        val posts=selected.mapNotNull { node ->
            // The whole document was previously queried once for every ancestor
            // of every card. Reuse the selected nodes for nested-card detection.
            if(!node.hasClass("comment")&&node.parents().any {it in selectedNodes})return@mapNotNull null
            val copy=node.clone().apply {select(".comment .comment,.comments,.subpost,script,style,iframe,form").remove()}
            val a=copy.selectFirst("a.tweet-link,.tweet-date a,a.comment_permalink,a.post_title,a.title,.post-timestamp a,a[href*=status],a[href*=comments],a[href*=/p/],a[href*=/post/],a[href*=/video/],a[href*=/pin/],a[href*=/t/]")
            val link=safe(copy.attr("data-video_share_url"),provenance.finalUrl) ?: a?.absUrl("href")?.takeIf { it.startsWith("https://") } ?: if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))target.canonicalUrl else null
            val textNode=copy.selectFirst(".caption,.post-caption-text") ?: copy.selectFirst(".tweet-content,.post_body,.post-content,.comment_body,.post-body,.d_post_content,.description,.text-content,.pin-description,.media-content > .mb-2,.box .media-content > .content > p:first-child")
            val rat=sourceFrontend(provenance)=="ratAintTieba"
            val content=copy.attr("data-desc").ifBlank {textNode?.text().orEmpty()}.ifBlank {if(rat)copy.selectFirst(".content,.summary > div:not(.title)")?.text().orEmpty()else ""}
            val title=copy.selectFirst(".post_title,.title,.pin-title,h1,h2,h3")?.clone()?.apply {select(".post_flair").remove()}?.text().orEmpty().ifBlank {if(rat&&node.id()=="1")d.selectFirst(".thread-nav .title")?.text().orEmpty()else ""}
            val media=(copy.select(".attachments img[src],.post-content img[src],img.post_media[src],img.media[src],.post_image[src],.image img[src],.pin img[src],.post-image img[src],.tile-item-media img[src],.grid-item img[src],.pin-container > img[src],.pins-grid img[src],.grid-container img[src],.post-media img[src],.slides img[src]").mapNotNull { m ->safe(m.attr("src"),provenance.finalUrl)?.let { Media(it,description=m.attr("alt")) } }+VideoExtraction.html(copy,provenance.finalUrl)).toMutableList()
            safe(copy.attr("data-video_url"),provenance.finalUrl)?.let {url->media.add(Media(url,"video",copy.selectFirst("img[src]")?.attr("src")?.let {safe(it,provenance.finalUrl)}))}
            if(rat)copy.select(".content img[src]").forEach {image->safe(image.attr("src"),provenance.finalUrl)?.let {media.add(Media(it,description=image.attr("alt")))}}
            // Tile nodes are selected individually; only their own image is content.
            if(media.isEmpty()&&(node.hasClass("grid-item")||node.parent()?.hasClass("pins-grid")==true||node.parent()?.hasClass("grid-container")==true))copy.select("img[src]").forEach {m->safe(m.attr("src"),provenance.finalUrl)?.let {media.add(Media(it,description=m.attr("alt")))}}
            if(link==null || content.isBlank()&&title.isBlank()&&media.isEmpty())return@mapNotNull null
            val floor=node.id().takeIf {rat&&it.matches(Regex("[0-9]+"))}
            val thread=target.platform.origin+URI(target.canonicalUrl).rawPath
            val canonical=if(floor!=null)thread+if(floor=="1")""else "?pid=$floor"else routes.recognize(link)?.canonicalUrl ?: link
            val date=copy.selectFirst("time[datetime],.tweet-date a,.post_created,.created,.date,.timestamp,small[title],.post-clickable-time")
            val published=timestamp(copy.attr("data-createtime")) ?: timestamp(date?.attr("datetime")) ?: timestamp(date?.attr("title")) ?: timestamp(date?.text())
            val author=copy.selectFirst(".username,.post_author,.comment_author,.author,.author-name,.d_name,.p_author_name,.user-name,.post-username,.media-content small a,.pin-pinner a,.userinfo a,.participants a")?.text()?.trim()?.removePrefix("@").orEmpty()
            val avatar=copy.selectFirst(".tweet-avatar img,.avatar img,img.avatar,.user-info img,.post-pfp,.media-left img")?.attr("src")?.let { safe(it,provenance.finalUrl) }
            val nitterReply=sourceFrontend(provenance)=="nitter"&&target.kind==TargetKind.POST&&node.parents().any {it.hasClass("replies")||it.hasClass("after-tweet")}
            // Nitter explicitly separates replies/continuation from related tweets and ancestors.
            // It does not expose every immediate parent ID; retain the known conversation root.
            val parent=if(nitterReply)target.canonicalUrl else if(floor!=null&&floor!="1")thread else node.parents().firstOrNull {it.hasClass("comment")}?.selectFirst("a.comment_permalink,a[href*=comments]")?.absUrl("href")?.let {routes.recognize(it)?.canonicalUrl ?: it} ?: target.canonicalUrl.takeIf {node.hasClass("comment")&&target.kind==TargetKind.POST}
            Post(canonical,target.platform,canonical,author.ifBlank { if(target.kind==TargetKind.PROFILE)target.identity.removePrefix("@") else "" },content,title,published,avatar,media.distinctBy {it.url},parentId=parent,provenance=provenance,links=textNode?.select("a[href]")?.mapNotNull {safe(it.attr("href"),provenance.finalUrl)},
                conversationId=target.canonicalUrl.takeIf {nitterReply},extractionCoverage=if(nitterReply)ExtractionCoverage(text=if(content.isNotBlank()||title.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,attachments=if(media.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,replyParent=ExtractionStatus.UNAVAILABLE)else null)
        }.toMutableList()
        posts+=embedded
        if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&posts.none {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}) {
            val text=d.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            val title=d.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            val image=d.selectFirst("meta[property=og:image]")?.attr("content")?.let { safe(it,provenance.finalUrl) }
            val declaredVideoType=d.selectFirst("meta[property=og:video:type]")?.attr("content").orEmpty()
            val videoType=VideoExtraction.mime(declaredVideoType)
            val video=if(declaredVideoType.isBlank()||videoType!=null)listOf("og:video:secure_url","og:video","og:video:url").firstNotNullOfOrNull {property->d.selectFirst("meta[property=$property]")?.attr("content")?.let {VideoExtraction.safe(it,provenance.finalUrl)}}else null
            val declared=d.selectFirst("meta[property=og:url]")?.attr("content") ?: d.selectFirst("link[rel=canonical]")?.attr("href")
            fun matchesRequested(url: String)=routes.recognize(url)?.let {it.platform==target.platform&&it.kind==TargetKind.POST&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}==true
            val samePost=declared?.let {safe(it,provenance.finalUrl)}?.let(::matchesRequested)==true
            val type=d.selectFirst("meta[property=og:type]")?.attr("content").orEmpty()
            val typedPost=type.startsWith("article")||type.startsWith("video")||type.startsWith("instapp:")
            if((samePost||declared==null&&typedPost&&matchesRequested(provenance.finalUrl))&&(image!=null || video!=null)&&!Regex("login|sign up|log in",RegexOption.IGNORE_CASE).containsMatchIn(text+title))posts+=Post(target.canonicalUrl,target.platform,target.canonicalUrl,if(target.platform in setOf(Platform.TIKTOK,Platform.THREADS))target.identity.substringBefore('/').removePrefix("@")else "",text,title,publishedAt=timestamp(d.selectFirst("meta[property=article:published_time]")?.attr("content")),media=listOfNotNull(video?.let { Media(it,"video",image,mimeType=videoType) } ?: image?.let { Media(it) }),provenance=provenance,preview=true)
        }
        val profileNode=if(target.kind==TargetKind.PROFILE)d.selectFirst(".profile-card,.user-profile,.profile-header,.user-info-card")else null
        // Redlib's actual subreddit panel identifies its owner independently of
        // posts. Template reference: redlib-org/redlib/templates/subreddit.html.
        val communityProfile=if(target.platform==Platform.REDDIT&&PublicSearchAdapter.communityAllowed(target)&&sourceFrontend(provenance) in setOf("redlib","libreddit","teddit"))d.selectFirst("#subreddit #sub_meta")?.takeIf {node->node.selectFirst("#sub_name")?.text()?.removePrefix("r/")?.equals(target.identity.removePrefix("r/"),true)==true}?.let {node->
            Profile(target,node.selectFirst("#sub_title")?.text().orEmpty().ifBlank {node.selectFirst("#sub_name")!!.text()},node.selectFirst("#sub_description")?.text().orEmpty(),node.selectFirst("img#sub_icon[src]")?.attr("src")?.let {safe(it,provenance.finalUrl)})
        }else null
        val profile=embeddedProfile ?: communityProfile ?: profileNode?.let { node ->
            val expected=MentionNames.account(target,routes)?.key
            fun exactProfile(url: String)=expected!=null&&routes.recognize(url,target.platform)?.let {MentionNames.account(it,routes)?.key}==expected
            fun inPost(element: Element)=element.parents().any {parent->parent.hasClass("timeline-item")||parent.hasClass("tweet")||parent.hasClass("tweet-content")||parent.hasClass("post-card")}
            val nameNode=node.select(".profile-card-fullname,.profile-name,.usernames h3").firstOrNull {!inPost(it)}
                ?: node.select("a.fullname[href]").firstOrNull {!inPost(it)&&safe(it.attr("href"),provenance.finalUrl)?.let(::exactProfile)==true}
            val publicName=nameNode?.text().orEmpty()
            Profile(target,publicName.ifBlank {node.selectFirst(".fullname,.user-name,h1")?.text().orEmpty().ifBlank {target.identity}},node.selectFirst(".profile-bio,.bio,.description,.user-bio-text,.user-bio,.profile-about")?.text().orEmpty(),node.selectFirst("img")?.attr("src")?.let {url->safe(url,provenance.finalUrl)},displayNameSupplied=publicName.isNotBlank()&&exactProfile(provenance.finalUrl))
        } ?: run {
            val declared=d.selectFirst("meta[property=og:url]")?.attr("content") ?: d.selectFirst("link[rel=canonical]")?.attr("href")
            val same=declared?.let {safe(it,provenance.finalUrl)}?.let {routes.recognize(it)}?.canonicalUrl?.trimEnd('/')?.equals(target.canonicalUrl.trimEnd('/'),ignoreCase=true)==true
            if(target.kind==TargetKind.PROFILE&&same&&d.selectFirst("meta[property=og:type]")?.attr("content")=="profile")Profile(target,d.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().ifBlank {target.identity},d.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),d.selectFirst("meta[property=og:image]")?.attr("content")?.let {safe(it,provenance.finalUrl)})else null
        }
        val numericNext=if(sourceFrontend(provenance)=="ratAintTieba") {
            val current=DarwinRoutes.queryValue(URI(provenance.finalUrl),"pn")?.toIntOrNull() ?: 1
            d.select(".paginator a[href]").mapNotNull {node->val url=node.absUrl("href");val number=runCatching {DarwinRoutes.queryValue(URI(url),"pn")?.toIntOrNull()}.getOrNull();if(number==current+1)url else null}.firstOrNull()
        }else null
        val nextSelector=if(sourceFrontend(provenance)=="nitter"&&target.kind==TargetKind.POST)".replies .show-more a"else ".show-more a,a[rel=next],a.next,a.next-page,a[accesskey=N],.pagination a.next,a.next-button,.pagination-container a,a.button.is-success[href*=cursor]:not([disabled])"
        val next=(d.selectFirst(nextSelector)?.absUrl("href") ?: numericNext)?.takeIf { it.startsWith("https://") }
        val collections=if(target.platform==Platform.PINTEREST)d.select(".boards-grid > a[href]").mapNotNull {node->
            val link=safe(node.attr("href"),provenance.finalUrl) ?: return@mapNotNull null
            val board=routes.recognize(link) ?: return@mapNotNull null
            val name=node.selectFirst(".board-card-name")?.text().orEmpty().ifBlank {URI(board.canonicalUrl).path.trim('/').substringAfterLast('/').replace('-',' ')}
            if(name.isBlank())return@mapNotNull null
            Collection(board,name,node.selectFirst("img[src]")?.attr("src")?.let {safe(it,provenance.finalUrl)},node.selectFirst(".board-card-count")?.text().orEmpty())
        }else if(sourceFrontend(provenance)=="kittygram"&&target.kind==TargetKind.SEARCH)d.select(".search-result a[href]").mapNotNull {node->
            val link=safe(node.attr("href"),provenance.finalUrl) ?: return@mapNotNull null
            val found=routes.recognize(link) ?: return@mapNotNull null
            Collection(found,node.text(),node.parent()?.selectFirst("img[src]")?.attr("src")?.let {safe(it,provenance.finalUrl)})
        }else emptyList()
        val views=if(sourceFrontend(provenance)=="pinless"&&target.kind==TargetKind.PROFILE)d.select(".tabs a[href]").mapNotNull {node->
            safe(node.attr("href"),provenance.finalUrl)?.let {routes.recognize(it)}?.let {Collection(it,node.text(),navigation=true)}
        }else emptyList()
        val nativeAccounts=if(sourceFrontend(provenance)=="kittygram"&&target.kind==TargetKind.SEARCH)collections.filter {it.target.kind==TargetKind.PROFILE}.map {Profile(it.target,it.name.trim().removePrefix("@"),avatar=it.image)}else if(target.platform==Platform.REDDIT&&target.kind==TargetKind.SEARCH&&sourceFrontend(provenance) in setOf("redlib","libreddit")) {
            // Genuine directory cards, not post-author links or arbitrary site
            // navigation. Primary template: redlib-org/redlib/templates/search.html.
            d.select("#search_subreddits a.search_subreddit[href]:not(#more_subreddits)").mapNotNull {node->
                val link=runCatching {URI(provenance.finalUrl).resolve(node.attr("href")).takeIf {it.scheme in setOf("https","http")&&it.userInfo==null}}.getOrNull() ?: return@mapNotNull null
                val selected=routes.recognize(link.toString()) ?: runCatching {if(!DarwinRoutes.matches(link,provenance.source.root))null else routes.recognize(Platform.REDDIT.origin+link.rawPath.removePrefix(URI(provenance.source.root).rawPath.trimEnd('/')))}.getOrNull() ?: return@mapNotNull null
                if(!PublicSearchAdapter.communityAllowed(selected)||!node.selectFirst(".search_subreddit_name")?.text().orEmpty().removePrefix("r/").equals(selected.identity.removePrefix("r/"),true))return@mapNotNull null
                Profile(selected,node.selectFirst(".search_subreddit_name")!!.text(),node.selectFirst(".search_subreddit_description")?.text().orEmpty(),node.selectFirst(".search_subreddit_left img[src]")?.attr("src")?.let {safe(it,provenance.finalUrl)})
            }.distinctBy {PublicSearchAdapter.entityKey(it.target)}.take(30)
        }else emptyList()
        return Page(StructuredAiMetadata.mergeExactPosts(posts),next,profile?.let {it.copy(links=(it.links.orEmpty()+Subscriptions.profileLinks(body,provenance.finalUrl)).distinct().take(24))},collections=views+if(nativeAccounts.isNotEmpty())emptyList()else collections,accounts=nativeAccounts)
    }
    private fun sourceFrontend(p: Provenance)=p.source.frontend
}
