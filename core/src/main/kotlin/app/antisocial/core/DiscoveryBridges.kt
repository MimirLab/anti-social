/* anti social, GPL-3.0-or-later. Metadata-only discovery bridges; never execute publisher scripts.
 * Protocol references: YouTube.js (MIT), Mixcloud public API, TwitchMap public graph.
 * These providers are routes, not additions to the reader's subscription platforms. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private fun JsonElement?.bo()=this?.takeIf {it.isJsonObject}?.asJsonObject
private fun JsonObject.bs(key:String)=get(key)?.takeIf {it.isJsonPrimitive}?.let {runCatching {it.asString}.getOrNull()}.orEmpty()
private fun JsonObject.ba(key:String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()

data class BridgeNeighbor(val account:PublicAccount,val kind:AdjacentKind,val detail:String,val sourceUrl:String)
data class BridgePage(val account:PublicAccount,val description:String="",val links:List<String> = emptyList(),
    val neighbors:List<BridgeNeighbor> = emptyList(),val checkedAt:Long=0,val detail:String="",val complete:Boolean=true)
data class SoundCloudIdentity(val page:BridgePage,val clientId:String,val userId:String,val urn:String)

object DiscoveryBridges {
    const val MAX_DOCUMENT=3*1024*1024
    val providers=setOf("youtube","mixcloud","twitch","soundcloud")
    fun supported(account:PublicAccount)=account.provider in providers
    /** Extract one literal JSON value. Braces inside strings and escaped quotes
     * do not end the payload. No eval, DOM, subresources or site code. */
    fun embedded(text:String,marker:String):JsonElement? {
        require(text.length<=MAX_DOCUMENT)
        val start=text.indexOf(marker).takeIf {it>=0}?.plus(marker.length) ?: return null
        var begin=start;while(begin<text.length&&text[begin].isWhitespace())begin++
        if(begin>=text.length||text[begin] !in "[{")return null
        var depth=0;var quote=false;var escape=false
        for(i in begin until text.length) {
            val c=text[i]
            if(quote){if(escape)escape=false else if(c=='\\')escape=true else if(c=='"')quote=false}
            else when(c){'"'->quote=true;'[','{'->{depth++;require(depth<=80)};']','}'->{depth--;if(depth==0)return JsonParser.parseString(text.substring(begin,i+1))}}
        }
        return null
    }
    private fun nodes(root:JsonElement?,key:String):List<JsonObject> {
        if(root==null)return emptyList()
        val queue=ArrayDeque<Pair<JsonElement,Int>>();queue.add(root to 0);val found=ArrayList<JsonObject>();var visited=0
        while(queue.isNotEmpty()) {
            val (node,depth)=queue.removeFirst();require(++visited<=80_000&&depth<=80)
            if(node.isJsonObject)node.asJsonObject.entrySet().forEach {(k,v)->if(k==key){v.bo()?.let {found+=it}}else if(v.isJsonObject||v.isJsonArray)queue.add(v to depth+1)}
            else if(node.isJsonArray)node.asJsonArray.forEach {if(it.isJsonObject||it.isJsonArray)queue.add(it to depth+1)}
        }
        return found.take(100)
    }
    private fun urls(root:JsonElement?)=nodes(root,"urlEndpoint").map {it.bs("url")}
    private fun clean(raw:String,base:String):String? {
        if(raw.isBlank())return null
        var u=raw.toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(raw) ?: return null
        if(u.host.removePrefix("www.")=="youtube.com"&&u.encodedPath=="/redirect")u=u.queryParameter("q")?.toHttpUrlOrNull() ?: return null
        if(!u.isHttps)u=u.newBuilder().scheme("https").build()
        return PublicRelations.url(u.toString(),base)
    }
    private fun text(o:JsonObject?):String=o?.bs("simpleText")?.ifBlank {o.ba("runs").joinToString(""){it.bo()?.bs("text").orEmpty()}}.orEmpty()
    fun youtube(requested:PublicAccount,payload:Payload,routes:DarwinRoutes,at:Long):BridgePage {
        require(payload.status in 200..299&&payload.text.length<=MAX_DOCUMENT)
        if(payload.contentType.contains("json")||payload.text.trimStart().startsWith('{'))return invidious(requested,payload,routes,at)
        val root=(embedded(payload.text,"var ytInitialData = ") ?: embedded(payload.text,"window[\"ytInitialData\"] = "))?.bo()
            ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"No public channel metadata was returned")
        val metadata=root["metadata"].bo()?.get("channelMetadataRenderer").bo() ?: throw SourceFailure(HealthStatus.EMPTY,"This is not a public channel page")
        val id=metadata.bs("externalId");require(id.matches(Regex("UC[A-Za-z0-9_-]{22}")))
        val target=PublicAccounts.target(requested,routes) ?: error("Not a channel")
        val knownId=requested.id ?: target.identity.removePrefix("channel/").takeIf {it.startsWith("UC")}
        val aliases=metadata.ba("ownerUrls").map {runCatching {it.asString}.getOrDefault("")}+listOf(metadata.bs("vanityChannelUrl"),metadata.bs("channelUrl"))
        require(if(knownId!=null)knownId==id else aliases.any {YouTubeUrls.recognize(it)?.canonicalUrl?.equals(target.canonicalUrl,true)==true}){"A different channel was returned"}
        val account=requested.copy(url="https://www.youtube.com/channel/$id",id=id,name=metadata.bs("title").take(160))
        val about=nodes(root,"aboutChannelViewModel").filter {it.bs("channelId")==id}+nodes(root,"channelAboutFullMetadataRenderer").filter {it.bs("channelId").ifBlank {id}==id}
        val links=(listOfNotNull(metadata.bs("facebookProfileId").takeIf {it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))}?.let {"https://www.facebook.com/$it"})+
            about.flatMap {urls(it["links"] ?: it["primaryLinks"])}).mapNotNull {clean(it,account.url)}.distinct().take(24)
        // Only channel tiles in the creator's published channel page. Never
        // convert suggested videos, autoplay choices or ads into endorsements.
        val channels=(nodes(root["contents"],"gridChannelRenderer")+nodes(root["contents"],"channelRenderer")).mapNotNull {o->
            val channel=o.bs("channelId");if(!channel.matches(Regex("UC[A-Za-z0-9_-]{22}"))||channel==id)return@mapNotNull null
            val neighbor=PublicAccount("youtube","https://www.youtube.com/channel/$channel",text(o["title"].bo()).take(160),channel,sourceUrl=account.url)
            BridgeNeighbor(neighbor,AdjacentKind.FEATURED,"Channel selected on ${account.name.ifBlank {"this creator"}}'s channel page",account.url+"/featured")
        }.distinctBy {it.account.id}.take(30)
        return BridgePage(account,metadata.bs("description").take(4000),links,channels,at,"Creator-selected channels; this is not YouTube's suggested-video ranking")
    }
    private fun invidious(requested:PublicAccount,payload:Payload,routes:DarwinRoutes,at:Long):BridgePage {
        val root=PublicRelations.json(payload.text).bo() ?: error("No channel record")
        val id=root.bs("authorId");require(id.matches(Regex("UC[A-Za-z0-9_-]{22}"))&&id==(requested.id ?: PublicAccounts.target(requested,routes)?.identity?.removePrefix("channel/")))
        val account=requested.copy(url="https://www.youtube.com/channel/$id",id=id,name=root.bs("author").take(160))
        val links=Subscriptions.urls(root.bs("description").take(4000)).mapNotNull {clean(it,account.url)}.take(24)
        val neighbors=root.ba("relatedChannels").take(30).mapNotNull {it.bo()?.let {o->
            val other=o.bs("authorId");if(!other.matches(Regex("UC[A-Za-z0-9_-]{22}")))null else BridgeNeighbor(PublicAccount("youtube","https://www.youtube.com/channel/$other",o.bs("author").take(160),other),AdjacentKind.FEATURED,"Channel listed on the public channel page",account.url)
        }}
        return BridgePage(account,root.bs("description").take(4000),links,neighbors,at,"Public channel metadata through an alternate route")
    }
    fun mixcloud(requested:PublicAccount,payload:Payload,routes:DarwinRoutes,at:Long,following:Boolean=false):BridgePage {
        require(payload.status in 200..299)
        val root=PublicRelations.json(payload.text).bo() ?: error("No public profile")
        val base=requested.url.trimEnd('/')
        if(following) {
            require(root.has("data")&&root["data"].isJsonArray)
            val neighbors=root.ba("data").take(30).mapNotNull {it.bo()?.let {o->PublicAccounts.parse(o.bs("url"),routes,o.bs("name"),source=base)}}.filter {it.provider=="mixcloud"}.map {BridgeNeighbor(it,AdjacentKind.FOLLOWS,"Followed by this Mixcloud account",base+"/following/")}
            return BridgePage(requested,neighbors=neighbors,checkedAt=at,detail="Public following sample")
        }
        val actual=PublicAccounts.parse(root.bs("url"),routes) ?: error("No account identity")
        require(actual.url.trimEnd('/').equals(base,true))
        val links=(listOf(root.bs("website"),root.bs("twitter"),root.bs("facebook"))+Subscriptions.urls(root.bs("biog").take(4000))).mapNotNull {clean(it,base)}.distinct().take(24)
        return BridgePage(requested.copy(name=root.bs("name").take(160)),root.bs("biog").take(4000),links,checkedAt=at)
    }
    fun soundcloudIdentity(requested:PublicAccount,payload:Payload,routes:DarwinRoutes,at:Long):SoundCloudIdentity {
        require(payload.status in 200..299)
        val data=embedded(payload.text,"window.__sc_hydration = ")?.takeIf {it.isJsonArray}?.asJsonArray?.take(64).orEmpty()
        val user=data.mapNotNull {it.bo()}.firstOrNull {it.bs("hydratable")=="user"}?.get("data").bo() ?: error("No public SoundCloud profile")
        val client=data.mapNotNull {it.bo()}.firstOrNull {it.bs("hydratable")=="apiClient"}?.get("data").bo()?.bs("id").orEmpty()
        require(client.matches(Regex("[A-Za-z0-9]{32}")))
        val actual=PublicAccounts.parse(user.bs("permalink_url"),routes) ?: error("No profile identity")
        require(actual.url.equals(requested.url,true));val id=user.bs("id");require(id.matches(Regex("[0-9]{1,24}")))
        val urn=user.bs("urn").ifBlank {"soundcloud:users:$id"};require(urn=="soundcloud:users:$id")
        val account=requested.copy(name=user.bs("username").take(160),id=id)
        val links=Subscriptions.urls(user.bs("description").take(4000)).mapNotNull {clean(it,account.url)}.take(24)
        return SoundCloudIdentity(BridgePage(account,user.bs("description").take(4000),links,checkedAt=at),client,id,urn)
    }
    fun soundcloudNeighbors(owner:PublicAccount,payload:Payload,routes:DarwinRoutes,featured:Boolean):List<BridgeNeighbor> {
        require(payload.status in 200..299)
        val root=PublicRelations.json(payload.text).bo() ?: error("No public collection");require(root.has("collection"))
        return root.ba("collection").take(30).mapNotNull {it.bo()?.let {o->PublicAccounts.parse(o.bs("permalink_url"),routes,o.bs("username"))}}
            .filter {it.provider=="soundcloud"&&it.url!=owner.url}.map {BridgeNeighbor(it,if(featured)AdjacentKind.FEATURED else AdjacentKind.FOLLOWS,if(featured)"Featured by this SoundCloud account"else "Followed by this SoundCloud account",owner.url)}
    }
    fun soundcloudLinks(payload:Payload,base:String):List<String> {
        require(payload.status in 200..299)
        val root=PublicRelations.json(payload.text);require(root.isJsonArray)
        return root.asJsonArray.take(24).mapNotNull {clean(it.bo()?.bs("url").orEmpty(),base)}.distinct()
    }
    fun twitch(requested:PublicAccount,payload:Payload,at:Long):BridgePage {
        require(payload.status in 200..299&&payload.text.length<=MAX_DOCUMENT)
        val root=JsonParser.parseString(payload.text).asJsonObject
        val nodes=root.ba("nodes");require(nodes.size in 1..2500&&root.ba("links").size<=30_000)
        val name=requested.url.toHttpUrlOrNull()!!.pathSegments.first {it.isNotBlank()}
        val index=nodes.indexOfFirst {it.bo()?.bs("n")?.equals(name,true)==true}
        if(index<0)return BridgePage(requested,checkedAt=at,detail="This channel is outside TwitchMap's observed panel")
        val neighbors=root.ba("links").mapNotNull {edge->
            if(!edge.isJsonArray||edge.asJsonArray.size()!=3)return@mapNotNull null
            val a=edge.asJsonArray;val first=runCatching {a[0].asInt}.getOrNull() ?: return@mapNotNull null;val second=runCatching {a[1].asInt}.getOrNull() ?: return@mapNotNull null
            val other=when(index){first->second;second->first;else->return@mapNotNull null};if(other !in 0 until nodes.size)return@mapNotNull null
            val handle=nodes[other].bo()?.bs("n").orEmpty();if(!handle.matches(Regex("[A-Za-z0-9_]{1,25}")))return@mapNotNull null
            val count=runCatching {a[2].asInt}.getOrDefault(0);if(count<=0)return@mapNotNull null
            count to BridgeNeighbor(PublicAccount("twitch","https://www.twitch.tv/$handle",handle),AdjacentKind.SHARED_PARTICIPANTS,"$count shared chat participants in TwitchMap's sampled panel · ${root.bs("generated").take(40)}","https://twitchmap.com/")
        }.sortedByDescending {it.first}.take(24).map {it.second}
        return BridgePage(requested,neighbors=neighbors,checkedAt=at,detail="Observed shared chat participation, not total audience")
    }
}
