/* anti social, GPL-3.0-or-later. Small public-data adapters; no browser runtimes. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import kotlin.math.ln

private fun JsonElement?.ao()=this?.takeIf {it.isJsonObject}?.asJsonObject
private fun JsonObject.at(key:String)=get(key)?.takeIf {it.isJsonPrimitive}?.let {runCatching {it.asString}.getOrNull()}.orEmpty()
private fun JsonObject.al(key:String)=at(key).toLongOrNull()
private fun JsonObject.aa(key:String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.take(80).orEmpty()

object AdjacentSources {
    private val mastodon=setOf(Platform.MASTODON,Platform.PIXELFED,Platform.TRUTH_SOCIAL,Platform.GAB)
    private fun url(root:String,path:String,values:Map<String,String> = emptyMap())=root.toHttpUrlOrNull()!!.newBuilder().encodedPath(path).apply {values.forEach {(k,v)->addQueryParameter(k,v)}}.build().toString()
    private fun endpoint(p:Platform,family:String,root:String,path:String,values:Map<String,String> = emptyMap(),role:SourceRole=SourceRole.ALTERNATE)=AdjacentEndpoint(Source(p,"discovery-$family",root,role=role),url(root,path,values))
    private fun origin(value:String)=value.toHttpUrlOrNull()!!.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    private fun actorId(profile:Profile)=if(profile.target.platform==Platform.BLUESKY)profile.target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.dropWhile {it!="profile"}?.getOrNull(1).orEmpty()else profile.target.identity.removePrefix("@")
    private fun raw(owner:Profile,url:String,operation:AdjacentOperation,context:String="")=AdjacentRequest(owner,operation=operation,endpoints=listOf(AdjacentEndpoint(Source(owner.target.platform,"discovery-publisher",origin(url)),url)),context=context)
    fun plan(profile:Profile,routes:DarwinRoutes):List<AdjacentRequest> {
        val target=profile.target;if(!AdjacentDiscovery.valid(target))return emptyList()
        val tasks=ArrayList<AdjacentRequest>()
        when(target.platform) {
            Platform.X,Platform.BLUESKY->{tasks+=social(profile,profile,AdjacentOperation.FOLLOWING);tasks+=social(profile,profile,AdjacentOperation.FOLLOWERS)}
            Platform.REDDIT->{
                if(target.kind==TargetKind.COMMUNITY)tasks+=reddit(profile,profile,AdjacentOperation.AUTHORS)
                else {tasks+=reddit(profile,profile,AdjacentOperation.COMMUNITIES);tasks+=reddit(profile,profile,AdjacentOperation.INTERACTIONS)}
            }
            in mastodon->tasks+=lookup(profile,profile)
            Platform.RSS->tasks+=raw(profile,target.canonicalUrl,AdjacentOperation.WEB)
            else->Unit
        }
        // Reuse declared publisher websites, never ordinary article links or
        // the account's other social identities as a recommendation list.
        AccountLinkage.websites(profile.links.orEmpty(),routes).take(1).forEach {site->tasks+=raw(profile,site,AdjacentOperation.WEB)}
        return tasks.distinctBy {it.key}
    }
    private fun social(owner:Profile,actor:Profile,operation:AdjacentOperation,depth:Int=0,audience:Boolean=false,sample:Int=0,cursor:String?=null):AdjacentRequest {
        val p=actor.target.platform;val followers=operation==AdjacentOperation.FOLLOWERS
        val values=linkedMapOf("limit" to if(followers)"8"else "30");cursor?.takeIf {it.length<=2048}?.let {values["cursor"]=it}
        val endpoints=if(p==Platform.X) {
            val handle=actor.target.identity.removePrefix("@");require(handle.matches(Regex("[A-Za-z0-9_]{1,15}")))
            listOf(endpoint(p,if(followers)"x-followers"else "x-following","https://api.fxtwitter.com","/2/profile/$handle/${if(followers)"followers"else "following"}",mapOf("count" to if(followers)"8"else "30")+(cursor?.let {mapOf("cursor" to it)} ?: emptyMap())))
        }else {
            val id=actor.persistentId?.takeIf {it.startsWith("did:")} ?: actorId(actor)
            val list=ArrayList<AdjacentEndpoint>()
            if(followers&&id.startsWith("did:"))list+=endpoint(p,"bsky-backlinks","https://constellation.microcosm.blue","/xrpc/blue.microcosm.links.getBacklinks",mapOf("subject" to id,"source" to "app.bsky.graph.follow:subject","limit" to "8")+(cursor?.let {mapOf("cursor" to it)} ?: emptyMap()))
            list+=endpoint(p,if(followers)"bsky-followers"else "bsky-following","https://public.api.bsky.app","/xrpc/app.bsky.graph.${if(followers)"getFollowers"else "getFollows"}",values+mapOf("actor" to id),SourceRole.ORIGINAL)
            list
        }
        return AdjacentRequest(owner,actor,operation,endpoints,depth,audience,sample,cursor)
    }
    private fun lookup(owner:Profile,actor:Profile,depth:Int=0,audience:Boolean=false,sample:Int=0):AdjacentRequest {
        val parsed=actor.target.canonicalUrl.toHttpUrlOrNull()!!;val username=parsed.pathSegments.last {it.isNotBlank()}.removePrefix("@")
        return AdjacentRequest(owner,actor,AdjacentOperation.LOOKUP,listOf(endpoint(actor.target.platform,"federated-lookup",origin(actor.target.canonicalUrl),"/api/v1/accounts/lookup",mapOf("acct" to username),SourceRole.ORIGINAL)),depth,audience,sample)
    }
    private fun federated(owner:Profile,actor:Profile,id:String,operation:AdjacentOperation,root:String,depth:Int=0,audience:Boolean=false,sample:Int=0,cursor:String?=null):AdjacentRequest {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val segment=when(operation){AdjacentOperation.FEATURED->"endorsements";AdjacentOperation.FOLLOWERS->"followers";else->"following"}
        val params=mapOf("limit" to if(operation==AdjacentOperation.FOLLOWERS)"8"else "30")+(cursor?.let {mapOf("max_id" to it)} ?: emptyMap())
        return AdjacentRequest(owner,actor,operation,listOf(endpoint(actor.target.platform,"federated-$segment",root,"/api/v1/accounts/$id/$segment",params,SourceRole.ORIGINAL)),depth,audience,sample,cursor)
    }
    private fun reddit(owner:Profile,actor:Profile,operation:AdjacentOperation,depth:Int=0,sample:Int=0):AdjacentRequest {
        val name=actor.target.identity.substringAfter('/').removePrefix("u/").removePrefix("r/")
        val path=when(operation){AdjacentOperation.AUTHORS->"/api/comments/search/aggregate";AdjacentOperation.INTERACTIONS->"/api/users/interactions/users";else->"/api/users/interactions/subreddits"}
        val params=mapOf("after" to "30d","limit" to if(operation==AdjacentOperation.AUTHORS)"8"else "24")+
            if(operation==AdjacentOperation.AUTHORS)mapOf("subreddit" to name,"aggregate" to "author")else mapOf("author" to name,"min_count" to "3")
        return AdjacentRequest(owner,actor,operation,listOf(endpoint(Platform.REDDIT,"reddit-${operation.name.lowercase()}","https://arctic-shift.photon-reddit.com",path,params,SourceRole.CACHE)),depth=depth,audience=sample>0,sampleSize=sample)
    }
    private fun profile(o:JsonObject,p:Platform,root:String,routes:DarwinRoutes):Profile? {
        if(o.at("protected")=="true")return null
        val target=runCatching {when(p){
            Platform.X->routes.target(p,o.at("screen_name").ifBlank {return null})
            Platform.BLUESKY->{val handle=o.at("handle");if(handle.isBlank()||handle.endsWith(".invalid"))return null;routes.target(p,handle)}
            in mastodon->{
                val original=PublicRelations.url(o.at("url"),root) ?: return null
                routes.recognize(original)?.takeIf {it.kind==TargetKind.PROFILE} ?: routes.target(p,original)
            }
            else->return null
        }}.getOrNull()?.takeIf(AdjacentDiscovery::valid) ?: return null
        val name=when(p){Platform.X->o.at("name");Platform.BLUESKY->o.at("displayName");else->o.at("display_name")}.take(160)
        val description=when(p){in mastodon->Jsoup.parse(o.at("note").take(16_384)).text();else->o.at("description")}.take(2000)
        val links=(Subscriptions.urls(description)+listOfNotNull(o["website"].ao()?.at("url"))+o.aa("fields").mapNotNull {it.ao()?.at("value")?.let {v->Jsoup.parse(v).select("a[href]").firstOrNull()?.attr("href")}}).mapNotNull {PublicRelations.url(it,target.canonicalUrl)}.distinct().take(12)
        val avatar=o.at("avatar_url").ifBlank {o.at("avatar")}
        return Profile(target,name.ifBlank {target.identity},description,avatar.takeIf(String::isNotBlank)?.let {PublicRelations.url(it,target.canonicalUrl)},
            o.al("followers") ?: o.al("followersCount") ?: o.al("followers_count"),links,name.isNotBlank(),when(p){Platform.BLUESKY->o.at("did").takeIf {it.matches(Regex("did:(?:plc|web):[A-Za-z0-9.:%_-]{1,180}"))};in mastodon->o.at("uri").takeIf {it.startsWith("https://")}?.let {PublicRelations.url(it,target.canonicalUrl)};else->o.at("id").takeIf {it.matches(Regex("[0-9]{1,24}"))}},following=o.al("following") ?: o.al("followsCount") ?: o.al("following_count"))
    }
    private fun ensure(payload:Payload) {
        if(payload.status==429)throw SourceFailure(HealthStatus.RATE_LIMITED,"The public relationship source is rate limited")
        if(payload.status in setOf(401,403))throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"This public relationship source requires access it did not provide")
        if(payload.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"Public relationship source returned ${payload.status}")
        require(payload.text.length<=PublicRelations.MAX_DOCUMENT)
    }
    fun parse(q:AdjacentRequest,endpoint:AdjacentEndpoint,document:AdjacentDocument,routes:DarwinRoutes,at:Long):AdjacentBatch {
        val payload=document.payload;ensure(payload)
        if(q.operation in setOf(AdjacentOperation.WEB,AdjacentOperation.OPML,AdjacentOperation.RECOMMENDATIONS))return publisher(q,payload,document.headers,routes,at,document.cachedPublisher)
        val root=PublicRelations.json(payload.text)
        if(endpoint.source.frontend=="discovery-bsky-backlinks") {
            val o=root.ao() ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"No relationship records were returned")
            require(o.has("records")&&o["records"].isJsonArray)
            val people=o.aa("records").mapNotNull {it.ao()?.at("did")?.takeIf {did->did.matches(Regex("did:(?:plc|web):[A-Za-z0-9.:%_-]{1,180}"))}}.distinct().take(4)
            val next=people.map {did->
                // A DID is an exact protocol identity. Resolve its public handle
                // through getFollows; never fabricate a display-name match.
                val actor=Profile(Target(Platform.BLUESKY,TargetKind.PROFILE,did,"https://bsky.app/profile/$did"),did,displayNameSupplied=false,persistentId=did)
                social(q.owner,actor,AdjacentOperation.FOLLOWING,1,true,people.size)
            }
            return AdjacentBatch(next=next,sampled=people.size,more=o.at("cursor").isNotBlank(),detail="Observed public follow records in Constellation; historical backfill is incomplete")
        }
        if(q.operation==AdjacentOperation.LOOKUP) {
            val o=root.ao() ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"No public profile identity was returned")
            val actor=profile(o,q.actor.target.platform,endpoint.source.root,routes) ?: throw SourceFailure(HealthStatus.EMPTY,"No usable public account identity")
            require(AdjacentDiscovery.key(actor.target).equals(AdjacentDiscovery.key(q.actor.target),true))
            val id=o.at("id");require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            val next=if(q.depth==0)listOf(AdjacentOperation.FEATURED,AdjacentOperation.FOLLOWING,AdjacentOperation.FOLLOWERS).map {federated(q.owner,actor,id,it,endpoint.source.root)}
                else listOf(federated(q.owner,actor,id,AdjacentOperation.FOLLOWING,endpoint.source.root,q.depth,q.audience,q.sampleSize))
            return AdjacentBatch(next=next,sampled=1,detail="Resolved public account identity")
        }
        if(q.actor.target.platform==Platform.REDDIT)return redditBatch(q,payload,root,routes,at)
        val objectRoot=root.ao()
        if(q.actor.target.platform==Platform.X&&objectRoot?.al("code")!=200L)throw SourceFailure(HealthStatus.UNAVAILABLE,"The X frontend did not return relationship data")
        val array=when {
            root.isJsonArray->root.asJsonArray.take(80)
            q.actor.target.platform==Platform.X->objectRoot?.get("results")?.takeIf {it.isJsonArray}?.asJsonArray?.take(80)
            q.actor.target.platform==Platform.BLUESKY->objectRoot?.get(if(q.operation==AdjacentOperation.FOLLOWERS)"followers"else "follows")?.takeIf {it.isJsonArray}?.asJsonArray?.take(80)
            else->null
        } ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"The source did not return a public account list")
        if(q.actor.target.platform==Platform.BLUESKY) {
            val subject=objectRoot?.get("subject").ao() ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"The source omitted its account identity")
            require(subject.at("did")==q.actor.persistentId||subject.at("handle").equals(actorId(q.actor),true)||subject.at("did")==actorId(q.actor))
        }
        val people=array.mapNotNull {it.ao()?.let {o->profile(o,q.actor.target.platform,endpoint.source.root,routes)}}.distinctBy(AdjacentDiscovery::profileKey).take(if(q.operation==AdjacentOperation.FOLLOWERS)8 else 30)
        val observations=ArrayList<AdjacentObservation>();val next=ArrayList<AdjacentRequest>()
        if(q.operation==AdjacentOperation.FOLLOWERS) {
            people.take(4).forEach {actor->if(actor.target.platform in mastodon)next+=lookup(q.owner,actor,1,true,minOf(4,people.size))else next+=social(q.owner,actor,AdjacentOperation.FOLLOWING,1,true,minOf(4,people.size))}
        }else {
            val kind=when {q.operation==AdjacentOperation.FEATURED->AdjacentKind.FEATURED;q.depth==0->AdjacentKind.FOLLOWS;q.audience->AdjacentKind.SHARED_FOLLOWERS;else->AdjacentKind.SECOND_HOP}
            people.forEach {p->observations+=AdjacentObservation(p,AdjacentEvidence(kind,q.owner.target,payload.finalUrl,at,if(q.depth>0)q.actor.target else null,
                sampleSize=q.sampleSize,detail=if(q.depth>0)"A bounded public-following sample; no full-audience claim"else "Public account list; source ordering may be incomplete or selective",
                weight=if(q.depth>0)1.0/ln((maxOf(people.size.toLong(),q.actor.following?.coerceIn(0,100_000) ?: 0)+2).toDouble())else 1.0))}
            if(q.depth==0&&q.operation==AdjacentOperation.FOLLOWING)people.take(3).forEach {actor->if(actor.target.platform in mastodon)next+=lookup(q.owner,actor,1)else next+=social(q.owner,actor,AdjacentOperation.FOLLOWING,1)}
        }
        val mastodonNext=if(q.actor.target.platform in mastodon)document.headers.entries.filter {it.key.equals("Link",true)}.flatMap {it.value}.take(8).asSequence().flatMap {line->
            Regex("<([^>]{1,8192})>;?([^,]*)").findAll(line.take(32_768))
        }.filter {Regex("""rel=\s*"?next"?(?:;|$|\s)""",RegexOption.IGNORE_CASE).containsMatchIn(it.groupValues[2])}.mapNotNull {m->
            val base=endpoint.url.toHttpUrlOrNull()!!;val next=base.resolve(m.groupValues[1]) ?: return@mapNotNull null
            if(next.scheme!=base.scheme||next.host!=base.host||next.port!=base.port||next.encodedPath!=base.encodedPath)null else next.queryParameter("max_id")
        }.firstOrNull()else null
        val cursor=(objectRoot?.at("cursor")?.takeIf {it.isNotBlank()} ?: mastodonNext)?.takeIf {it.isNotBlank()&&it.length<=2048&&it!=q.cursor}
        // Only first-hop lists paginate, with a single extra page. Sampled
        // observers do not recursively enumerate their followers.
        if(q.depth==0&&q.cursor==null&&cursor!=null) {
            if(q.actor.target.platform in setOf(Platform.X,Platform.BLUESKY))next+=social(q.owner,q.actor,q.operation,0,cursor=cursor)
            else if(q.actor.target.platform in mastodon)next+=q.copy(endpoints=listOf(endpoint.copy(url=endpoint.url.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("max_id",cursor).build().toString())),cursor=cursor)
        }
        return AdjacentBatch(observations,next,people.size,cursor!=null,"Public ${if(q.operation==AdjacentOperation.FEATURED)"featured accounts"else if(q.operation==AdjacentOperation.FOLLOWERS)"follower sample"else "following sample"}; bounded pages, not a complete graph")
    }
    private fun redditBatch(q:AdjacentRequest,payload:Payload,root:JsonElement,routes:DarwinRoutes,at:Long):AdjacentBatch {
        val o=root.ao();val entries=o?.get("data")?.takeIf {it.isJsonArray}?.asJsonArray?.take(80) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"The archive omitted its relationship records")
        val profiles=entries.mapNotNull {entry->val record=entry.ao() ?: return@mapNotNull null
            val community=q.operation==AdjacentOperation.COMMUNITIES
            val name=record.at(if(community)"subreddit"else "author").ifBlank {record.at("key")}
            if(!name.matches(Regex("[A-Za-z0-9_-]{3,24}"))||name in setOf("AutoModerator","deleted","removed"))return@mapNotNull null
            val target=runCatching {routes.target(Platform.REDDIT,(if(community)"r/"else "u/")+name)}.getOrNull() ?: return@mapNotNull null
            Profile(target,name,displayNameSupplied=false) to (record.al("count") ?: 1).coerceIn(1,100_000).toInt()
        }.distinctBy {AdjacentDiscovery.key(it.first.target)}.take(24)
        if(q.operation==AdjacentOperation.AUTHORS)return AdjacentBatch(next=profiles.take(4).map {reddit(q.owner,it.first,AdjacentOperation.COMMUNITIES,1,profiles.size)},sampled=profiles.size,more=entries.size>=8,detail="Sampled recent public commenters in this community, from archive records")
        val values=profiles.map {(p,count)->AdjacentObservation(p,AdjacentEvidence(if(q.operation==AdjacentOperation.COMMUNITIES)AdjacentKind.COMMUNITY else AdjacentKind.INTERACTION,q.owner.target,payload.finalUrl,at,
            via=if(q.depth>0)q.actor.target else null,count=count,sampleSize=q.sampleSize,detail="Archived interactions from the requested past-30-day window; interaction does not imply agreement"))}
        return AdjacentBatch(values,sampled=profiles.size,more=entries.size>=24,detail="Arctic Shift historical interactions; archive coverage varies")
    }
    private fun publisher(q:AdjacentRequest,payload:Payload,headers:Map<String,List<String>>,routes:DarwinRoutes,at:Long,cached:PublisherLinks?=null):AdjacentBatch {
        if(q.operation==AdjacentOperation.OPML) {
            val entries=PublicRelations.opml(payload)
            return AdjacentBatch(entries.map {(url,name,category)->AdjacentObservation(Profile(RssSources.target(url),name.ifBlank {url.toHttpUrlOrNull()!!.host}),AdjacentEvidence(AdjacentKind.READING_LIST,q.owner.target,payload.finalUrl,at,detail=if(category.isBlank())"Included in a published reading list"else "Listed under $category"))},sampled=entries.size,detail="Published OPML reading list; category labels are retained")
        }
        if(q.operation==AdjacentOperation.RECOMMENDATIONS) {
            val root=PublicRelations.json(payload.text);require(root.isJsonArray)
            val urls=root.asJsonArray.take(24).mapNotNull {it.ao()?.at("url")?.let {v->PublicRelations.url(v,payload.finalUrl)}}.distinct()
            return AdjacentBatch(next=urls.take(6).map {raw(q.owner,it,AdjacentOperation.WEB,"Recommended by publisher")},sampled=urls.size,more=urls.size>6,detail="Creator-published Ghost recommendations")
        }
        PublicRelations.readingFeed(payload)?.let {feed->
            val values=feed.feeds.map {(url,name)->AdjacentObservation(Profile(RssSources.target(url),name.ifBlank {url.toHttpUrlOrNull()!!.host}),AdjacentEvidence(AdjacentKind.READING_LIST,q.owner.target,payload.finalUrl,at,detail="Recommended in this publisher's feed"))}
            return AdjacentBatch(values,feed.blogrolls.take(2).map {raw(q.owner,it,AdjacentOperation.OPML)},sampled=values.size+feed.blogrolls.size,detail="Feed-level recommendations; no audio or episode files downloaded")
        }
        val data=cached ?: PublicRelations.publisher(payload,headers)
        val values=if(q.context.isBlank())emptyList()else data.feeds.map {(url,name)->AdjacentObservation(Profile(RssSources.target(url),name.ifBlank {url.toHttpUrlOrNull()!!.host}),AdjacentEvidence(AdjacentKind.READING_LIST,q.owner.target,payload.finalUrl,at,detail=q.context))}
        val next=data.blogrolls.take(2).map {raw(q.owner,it,AdjacentOperation.OPML)}.toMutableList()
        data.recommendations.take(3).forEach {(url,reason)->next+=raw(q.owner,url,AdjacentOperation.WEB,reason.ifBlank {"Linked by this publisher"})}
        if(data.ghost&&q.context.isBlank()) {
            val u=payload.finalUrl.toHttpUrlOrNull()!!
            next+=raw(q.owner,u.newBuilder().encodedPath("/.well-known/recommendations.json").query(null).build().toString(),AdjacentOperation.RECOMMENDATIONS)
        }
        if(Regex("(?is)^(?:\\s|<\\?xml[^>]*>)*<opml(?:\\s|>)").containsMatchIn(payload.text))return publisher(q.copy(operation=AdjacentOperation.OPML),payload,headers,routes,at)
        return AdjacentBatch(values,next,data.feeds.size+data.blogrolls.size+data.recommendations.size,detail="Declared feed and reading-list links; website scripts were not executed")
    }
}
