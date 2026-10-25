/* anti social, GPL-3.0-or-later. Darwin remains the routing donor.
 * Public field reference: toolzerhub/threads-scraper (MIT). No upstream runtime. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Only selected Relay data roots may supply posts. Recommendation/quote trees
 * are not a discussion, and a matching name is not a parent relationship. */
internal object ThreadsPublicPages {
    private const val MAX_JSON=4*1024*1024
    private const val MAX_POSTS=128
    // Repeated streamed patches count too; reject before constructing their media.
    private class Budget {
        private var remaining=MAX_POSTS*2
        fun take(count:Int) {if(count>remaining)limited();remaining-=count}
    }
    private val handle=Regex("[A-Za-z0-9_.]{1,30}")
    private val code=Regex("[A-Za-z0-9_-]{5,32}")
    private val decimal=Regex("[0-9]{1,40}")
    private data class Selection(val handle:String?,val code:String?) {val profile get()=code==null}
    private data class Node(val post:Post,val raw:JsonObject,val owner:String,val ownerId:String?,val pk:String?,val sourceId:String?,val reply:Boolean?,val level:Int?)
    private fun JsonObject.o(key:String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.a(key:String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray
    private fun JsonObject.s(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.let {p->p.isString||p.isNumber}}?.asString.orEmpty()
    private fun JsonObject.b(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean
    private fun JsonObject.n(key:String)=s(key).takeIf {it.matches(decimal)}?.toLongOrNull()?.takeIf {it>=0}
    private fun fail(message:String):Nothing=throw SourceFailure(HealthStatus.EMPTY,message)
    private fun limited():Nothing=throw SourceFailure(HealthStatus.UNSUPPORTED,"This public page exceeds its extraction budget")
    private fun selection(value:String):Selection? {
        val uri=runCatching {URI(value)}.getOrNull() ?: return null
        if(!uri.scheme.equals("https",true)||uri.host?.lowercase(Locale.ROOT) !in setOf("threads.com","www.threads.com","threads.net","www.threads.net")||uri.userInfo!=null||uri.port !in setOf(-1,443))return null
        val parts=uri.rawPath.orEmpty().trim('/').split('/')
        if(parts.size==1&&parts[0].startsWith('@'))return parts[0].removePrefix("@").takeIf {handle.matches(it)}?.let {Selection(it,null)}
        if(parts.size==2&&parts[0]=="t"&&code.matches(parts[1]))return Selection(null,parts[1])
        if(parts.size!=3||!parts[0].startsWith('@')||parts[1]!="post"||!code.matches(parts[2]))return null
        return parts[0].removePrefix("@").takeIf {handle.matches(it)}?.let {Selection(it,parts[2])}
    }
    private fun sourceScope(target:Target,provenance:Provenance):Selection {
        val selected=selection(target.canonicalUrl) ?: fail("The selected Threads address is unsupported")
        if(target.platform!=Platform.THREADS||target.kind !in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD)||selected.profile!=(target.kind==TargetKind.PROFILE))fail("The public page changed its target type")
        val root=runCatching {URI(provenance.source.root)}.getOrNull() ?: fail("The public source has no origin")
        val actual=runCatching {URI(provenance.finalUrl)}.getOrNull() ?: fail("The public page has no origin")
        fun port(uri:URI)=if(uri.port>=0)uri.port else if(uri.scheme=="https")443 else 80
        if(provenance.source.role!=SourceRole.ORIGINAL||provenance.source.platform!=Platform.THREADS||actual.scheme!=root.scheme||actual.host!=root.host||port(actual)!=port(root)||actual.userInfo!=null)fail("The public page left its selected source")
        val physical=selection(Platform.THREADS.origin+actual.rawPath.orEmpty()) ?: fail("The public page redirected away from its selected target")
        if(physical.profile!=selected.profile||physical.code!=selected.code||selected.handle!=null&&physical.handle!=null&&!selected.handle.equals(physical.handle,true))fail("The public page redirected to another target")
        return selected
    }
    fun html(document:Document,target:Target,provenance:Provenance):Page {
        sourceScope(target,provenance)
        val scripts=document.select("script[type=application/json]")
        if(scripts.size>128)limited()
        var bytes=0L
        val values=scripts.mapNotNull {script->
            val raw=script.data();bytes+=raw.length.toLong()*2
            if(bytes>MAX_JSON*2L)limited()
            runCatching {JsonReader(raw.reader()).use {reader->
                reader.strictness=Strictness.STRICT;reader.nestingLimit=64
                JsonParser.parseReader(reader).also {require(reader.peek()==JsonToken.END_DOCUMENT)}
            }}.getOrNull()
        }
        return parse(values,target,provenance)
    }
    fun json(value:JsonElement,target:Target,provenance:Provenance):Page {
        val selected=sourceScope(target,provenance)
        // Earlier original readers also supply a single literal selected post.
        // Its URL/code/author can establish readable content without numeric IDs;
        // absent IDs must never authorize streamed reply patches.
        val direct=value.takeIf {it.isJsonObject}?.asJsonObject?.takeIf {
            !selected.profile&&it.s("code")==selected.code&&it.o("text_post_app_info")!=null&&
                (selected.handle==null||it.o("user")?.s("username").equals(selected.handle,true))
        }
        val values=if(direct==null)listOf(value)else listOf(JsonObject().apply {add("data",JsonObject().apply {add("media",direct)})})
        return parse(values,target,provenance,sparseRoot=direct!=null)
    }
    private fun roots(values:List<JsonElement>):List<JsonObject> {
        val result=mutableListOf<JsonObject>();var visited=0
        fun walk(value:JsonElement,depth:Int) {
            if(depth>64||++visited>50_000)limited()
            if(value.isJsonArray){value.asJsonArray.forEach {walk(it,depth+1)};return}
            if(!value.isJsonObject)return
            val obj=value.asJsonObject
            val data=obj.o("__bbox")?.o("result")?.o("data") ?: obj.o("result")?.o("data")
            if(data!=null){if(result.size>=128)limited();result+=data;return}
            obj.entrySet().forEach {(_,v)->if(v.isJsonObject||v.isJsonArray)walk(v,depth+1)}
        }
        values.forEach {value->
            val direct=value.takeIf {it.isJsonObject}?.asJsonObject?.o("data")
            if(direct!=null){if(result.size>=128)limited();result+=direct}else walk(value,0)
        }
        return result
    }
    private fun https(value:String):String?=value.takeIf {it.length<=8192}?.let {runCatching {URI(it)}.getOrNull()}
        ?.takeIf {it.scheme=="https"&&it.host!=null&&it.userInfo==null&&it.port in setOf(-1,443)}?.toString()
    private fun userId(user:JsonObject):String? {
        val a=user.s("pk");val b=user.s("id")
        if(a.isNotEmpty()&&b.isNotEmpty()&&a!=b)return null
        return a.ifBlank {b}.takeIf {decimal.matches(it)&&it.any {c->c!='0'}}
    }
    private fun profile(user:JsonObject):Profile? {
        val name=user.s("username").takeIf {handle.matches(it)} ?: return null
        val supplied=user.s("full_name").take(300)
        val target=Target(Platform.THREADS,TargetKind.PROFILE,"@$name",Platform.THREADS.origin+"/@$name")
        val links=user.a("bio_links").orEmpty().take(24).mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.s("url")?.let(::https)}.distinct()
        return Profile(target,supplied.ifBlank {name},user.s("biography").take(16_384),https(user.s("profile_pic_url")),user.n("follower_count"),links,
            displayNameSupplied=supplied.isNotBlank(),persistentId=userId(user))
    }
    private fun node(raw:JsonObject,provenance:Provenance,requireIds:Boolean=true):Node? {
        val token=raw.s("code").takeIf {code.matches(it)} ?: return null
        val pk=raw.s("pk").takeIf {decimal.matches(it)&&it.any {c->c!='0'}}
        if(pk==null&&(requireIds||raw.s("pk").isNotEmpty()))return null
        val user=raw.o("user") ?: return null;val account=profile(user) ?: return null
        val owner=user.s("username");val uid=userId(user)
        if(uid==null&&(requireIds||user.s("pk").isNotEmpty()||user.s("id").isNotEmpty()))return null
        val sourceId=raw.s("id").takeIf {it.isNotEmpty()}
        if(sourceId!=null&&(pk==null||sourceId!=pk&&(uid==null||sourceId!="${pk}_$uid")))return null
        val info=raw.o("text_post_app_info") ?: return null
        val fragments=info.o("text_fragments")?.a("fragments").orEmpty().take(128).mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject}
        val caption=raw.o("caption")?.s("text").orEmpty().ifBlank {fragments.joinToString(""){it.s("plaintext")}}
        val links=(fragments.mapNotNull {it.o("link_fragment")?.s("uri")?.let(::https)}+listOfNotNull(info.o("link_preview_attachment")?.s("url")?.let(::https))).distinct().take(PostLinks.MAX_LINKS)
        val media=if(raw.n("media_type")==19L)emptyList()else {
            val carousel=raw.a("carousel_media")
            if(carousel!=null&&carousel.size()>32)limited()
            (carousel?.mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject} ?: listOf(raw)).mapNotNull {item->
                val image=item.o("image_versions2")?.a("candidates").orEmpty().take(32).firstNotNullOfOrNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.s("url")?.let(::https)}
                val video=item.a("video_versions").orEmpty().take(32).firstNotNullOfOrNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.s("url")?.let(::https)}
                val description=item.s("accessibility_caption").take(2000)
                if(video!=null)Media(video,"video",image,description,durationMs=MediaDuration.seconds(item["video_duration"]))else image?.let {Media(it,description=description)}
            }.distinctBy {it.type to it.url}
        }
        if(caption.isBlank()&&media.isEmpty()&&links.isEmpty())return null
        val address=Platform.THREADS.origin+"/@$owner/post/$token";val key=ContentIdentity.key(Platform.THREADS,address)
        val reply=info.b("is_reply");val level=info.n("reply_level")?.takeIf {it<=32}?.toInt() ?: if(reply==false)0 else null
        val post=Post(key,Platform.THREADS,address,account.name,caption.take(65_536),publishedAt=raw.n("taken_at")?.takeIf {it>0&&it<=Long.MAX_VALUE/1000}?.times(1000),avatar=account.avatar,
            media=media,replyCount=info.n("direct_reply_count"),score=raw.n("like_count"),provenance=provenance,links=links,relationId=pk,
            extractionCoverage=ExtractionCoverage(text=if(caption.length>65_536)ExtractionStatus.PARTIAL else if(caption.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,
                attachments=if(media.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,replyParent=if(reply==true)ExtractionStatus.UNKNOWN else null))
        return Node(post,raw,owner,uid,pk,sourceId,reply,level)
    }
    private fun parentMatches(child:Node,parent:Node):Boolean {
        val declared=child.raw.o("text_post_app_info")?.o("reply_to_author") ?: return false
        if(declared.has("id")||declared.has("pk")) {
            val id=userId(declared) ?: return false
            if(id!=parent.ownerId)return false
        }
        return declared.s("username").equals(parent.owner,true)
    }
    private fun chain(values:List<Node>,root:Node?,conversation:String?):List<Node> {
        val parents=mutableMapOf<Int,Node>();root?.level?.let {parents[it]=root}
        return values.map {value->
            val level=value.level
            val parent=if(value.reply==true&&level!=null&&level>0)parents[level-1]?.takeIf {parentMatches(value,it)}else null
            if(level!=null){parents.keys.filter {it>=level}.forEach(parents::remove);parents[level]=value}
            val post=value.post.copy(parentId=parent?.post?.id,sourceParentId=parent?.pk,conversationId=conversation ?: parent?.post?.conversationId,
                extractionCoverage=value.post.extractionCoverage?.copy(replyParent=if(parent!=null)ExtractionStatus.PRESENT else if(value.reply==true)ExtractionStatus.UNKNOWN else null))
            value.copy(post=post)
        }
    }
    private fun replyGroups(info:JsonObject,key:String,budget:Budget):List<List<JsonObject>> {
        val edges=info.o(key)?.a("edges") ?: return emptyList()
        if(edges.size()>MAX_POSTS)limited()
        return edges.mapNotNull {edge->edge.takeIf {it.isJsonObject}?.asJsonObject?.o("node")?.o("posts")?.a("edges")?.let {posts->
            if(posts.size()>MAX_POSTS)limited()
            budget.take(posts.size())
            posts.mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.o("node")}
        }}
    }
    private fun parse(values:List<JsonElement>,target:Target,provenance:Provenance,sparseRoot:Boolean=false):Page {
        val selected=sourceScope(target,provenance);val data=roots(values);val output=mutableListOf<Node>();val budget=Budget();var owner:Profile?=null
        fun append(nodes:List<Node>) {if(output.size+nodes.size>MAX_POSTS*2)limited();output+=nodes}
        if(selected.profile) {
            val users=data.mapNotNull {it.o("user")}.filter {it.s("username").equals(selected.handle,true)&&userId(it)!=null}
            if(users.map(::userId).distinct().size!=1)fail("The public profile did not identify the selected account")
            owner=profile(users.first())!!
            data.mapNotNull {it.o("mediaData")}.forEach {connection->
                val edges=connection.a("edges") ?: return@forEach
                if(edges.size()>MAX_POSTS)limited()
                edges.forEach {edge->
                    val items=edge.takeIf {it.isJsonObject}?.asJsonObject?.o("node")?.a("thread_items") ?: return@forEach
                    if(items.size()>MAX_POSTS)limited()
                    budget.take(items.size())
                    val nodes=items.mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.o("post")?.let {p->node(p,provenance)}}
                        .filter {it.owner.equals(selected.handle,true)&&it.ownerId==owner.persistentId}
                    append(chain(nodes,null,nodes.firstOrNull()?.post?.id))
                }
            }
        }else {
            val raw=data.mapNotNull {it.o("media")}
            budget.take(raw.size)
            val matches=raw.filter {it.s("code")==selected.code}.mapNotNull {node(it,provenance,requireIds=!sparseRoot)}.filter {selected.handle==null||it.owner.equals(selected.handle,true)}
            val root=matches.distinctBy {it.pk to it.ownerId}.singleOrNull() ?: fail("The public page did not supply the selected post")
            output+=root.copy(post=root.post.copy(conversationId=root.post.id))
            raw.filter {patch->root.sourceId!=null&&patch.s("id")==root.sourceId&&
                (patch.s("pk").isEmpty()||patch.s("pk")==root.pk)&&
                (patch.s("code").isEmpty()||patch.s("code")==selected.code)&&
                (patch.o("user")?.let {userId(it)==root.ownerId&&it.s("username").equals(root.owner,true)}!=false)
            }.forEach {patch->
                val info=patch.o("text_post_app_info") ?: return@forEach
                val groups=replyGroups(info,"direct_replies",budget)+replyGroups(info,"pinned_replies",budget)+
                    listOfNotNull(info.o("self_thread")?.o("posts")?.a("edges")?.let {edges->
                        if(edges.size()>MAX_POSTS)limited();budget.take(edges.size())
                        edges.mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.o("node")}
                    })
                groups.forEach {group->append(chain(group.mapNotNull {node(it,provenance)},root,root.post.id))}
            }
        }
        val posts=output.map {it.post}.distinctBy {it.identityKey}
        if(posts.size>MAX_POSTS)limited()
        val accounts=(listOfNotNull(owner)+output.mapNotNull {it.raw.o("user")?.let(::profile)}).distinctBy {it.target.canonicalUrl.lowercase(Locale.ROOT)}
        return Page(posts,profile=owner?.copy(target=target),provenance=provenance,accounts=accounts,limitedTimeline=selected.profile,
            notice=if(selected.profile)"Public page snapshot; earlier posts may be unavailable."else "Public replies supplied by the page; additional replies may be unavailable.")
    }
    private fun JsonArray?.orEmpty():List<JsonElement> = this?.toList().orEmpty()
}
