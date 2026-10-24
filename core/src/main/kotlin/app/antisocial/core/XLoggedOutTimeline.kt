/* anti social, GPL-3.0-or-later. Darwin remains the routing donor.
 * Protocol/decoder reference: Nikolai Shcheglov's MIT XProfiles for FreshRSS.
 * See licenses/social-data-sources.txt and x-profile-notices/XPROFILES-LICENSE.txt. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.math.BigDecimal
import java.util.Collections
import java.util.IdentityHashMap

/** Read data already delivered with an allowed original-profile request. No
 * browser, JavaScript engine, cookies, second HTTP request or guessed cursor. */
object XLoggedOutTimeline {
    private const val MAX_BYTES=1024*1024
    private const val MAX_NODES=80_000
    private val handle=Regex("[A-Za-z0-9_]{1,15}")
    private val id=Regex("[1-9][0-9]{0,39}")
    private val prefix=Regex("""^\(\x24R=>\x24R\[[0-9]{1,6}]\.next\(""")
    private val originalHosts=setOf("x.com","www.x.com","mobile.x.com","twitter.com","www.twitter.com","mobile.twitter.com")
    private val literals=mapOf("!0" to JsonPrimitive(true),"!1" to JsonPrimitive(false),"true" to JsonPrimitive(true),"false" to JsonPrimitive(false),"null" to JsonNull.INSTANCE,"undefined" to JsonNull.INSTANCE,"void 0" to JsonNull.INSTANCE)
    private fun JsonElement?.obj()=this?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject?.obj(key:String)=this?.get(key).obj()
    private fun JsonObject?.string(key:String)=this?.get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString.orEmpty()
    private fun JsonObject?.array(key:String)=this?.get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
    private fun JsonObject?.long(key:String)=this?.get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.let {runCatching {it.asBigDecimal.longValueExact()}.getOrNull()}
    private fun JsonObject?.yes(key:String)=this?.get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean==true
    private fun https(raw:String)=raw.takeIf {it.length<=8192}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()

    fun parse(document:Document,target:Target,stamp:Provenance):Page? {
        if(target.platform!=Platform.X||target.kind!=TargetKind.PROFILE||stamp.source.role!=SourceRole.ORIGINAL||stamp.source.platform!=Platform.X)return null
        val wanted=target.identity.removePrefix("@").takeIf(handle::matches) ?: return null
        fun selected(raw:String):Boolean {
            val u=raw.toHttpUrlOrNull() ?: return false
            return u.isHttps&&u.port==443&&u.username.isEmpty()&&u.password.isEmpty()&&u.host in originalHosts&&
                u.pathSegments.filter(String::isNotBlank).let {it.size==1&&it[0].equals(wanted,true)}
        }
        if(!selected(target.canonicalUrl)||!selected(stamp.finalUrl))return null
        var bytes=0;var candidates=0
        for(script in document.select("script")) {
            val data=script.data().trimStart()
            if(!data.contains("GraphQLRequestStream.Completed"))continue
            val start=prefix.find(data) ?: continue
            if(++candidates>8)break
            if(data.length>MAX_BYTES)continue
            if(bytes+data.length>MAX_BYTES)break
            bytes+=data.length
            val message=runCatching {LiteralData(data,start.range.last+1).read().asJsonObject}.getOrNull() ?: continue
            if(message.string("kind")!="GraphQLRequestStream.Completed")continue
            val instructions=runCatching {timeline(message)}.getOrNull() ?: continue
            val posts=linkedMapOf<String,Post>();var profile:Profile?=null
            for(instruction in instructions.take(32)) {
                val node=instruction.obj() ?: continue
                val kind=node.string("__typename").ifBlank {node.string("type")}
                val entries=when(kind) {
                    "TimelineAddEntries"->node.array("entries")
                    "TimelinePinEntry","TimelineReplaceEntry"->listOfNotNull(node["entry"])
                    else->emptyList()
                }
                for(entry in entries.take(100)) {
                    val content=entry.obj().obj("content")
                    val item=content.obj("content") ?: content.obj("itemContent") ?: continue
                    val tweet=unwrap(item.obj("tweet_results").obj("result")) ?: continue
                    val user=tweet.obj("core").obj("user_results").obj("result") ?: continue
                    if(!user.obj("core").string("screen_name").equals(wanted,true)||user.obj("privacy").yes("protected"))continue
                    val post=post(tweet,stamp,0) ?: continue
                    if(posts.size<100)posts.putIfAbsent(post.id,post)
                    if(profile==null)profile=Profile(target,user.obj("core").string("name").ifBlank {wanted},
                        user.obj("profile_bio").string("description"),https(user.obj("avatar").string("image_url")),
                        user.obj("relationship_counts").long("followers")?.takeIf {it>=0},
                        links=urls(user.obj("profile_bio").array("url_entities")).values.distinct(),
                        displayNameSupplied=user.obj("core").string("name").isNotBlank(),persistentId=user.string("rest_id").takeIf(id::matches))
                }
            }
            if(posts.isNotEmpty())return Page(posts.values.sortedWith(compareByDescending<Post>{it.publishedAt}.thenBy {it.id}),profile=profile,
                provenance=stamp,limitedTimeline=true)
        }
        return null
    }
    private fun unwrap(node:JsonObject?):JsonObject? {
        val value=if(node.string("__typename")=="TweetWithVisibilityResults")node.obj("tweet")else node
        return value?.takeIf {it.string("__typename")=="Tweet"}
    }
    private fun urls(values:List<JsonElement>):Map<String,String> = values.take(64).mapNotNull {value->
        val entry=value.obj();val short=https(entry.string("url")) ?: return@mapNotNull null
        https(entry.string("expanded_url"))?.let {short to it}
    }.toMap()
    private val web=Regex("https?://[^\\s<>]+")
    private fun expanded(text:String,urls:Map<String,String>)=web.replace(Parser.unescapeEntities(text,false)) {match->
        val token=match.value;val trimmed=token.trimEnd('.',',','!',')',']',';')
        urls[token] ?: urls[trimmed]?.let {it+token.removePrefix(trimmed)} ?: token
    }
    private fun post(tweet:JsonObject,stamp:Provenance,depth:Int):Post? {
        val number=tweet.string("rest_id").takeIf(id::matches) ?: return null
        val user=tweet.obj("core").obj("user_results").obj("result")?.takeIf {it.string("__typename")=="User"} ?: return null
        val name=user.obj("core").string("screen_name").takeIf(handle::matches) ?: return null
        if(user.obj("privacy").yes("protected"))return null
        val detail=tweet.obj("details") ?: return null
        val time=detail.long("created_at_ms")?.takeIf {it>=1_142_899_200_000L&&it<=stamp.fetchedAt+86_400_000L} ?: return null
        val note=tweet.obj("note_tweet").obj("note_tweet_results").obj("result")
        val longText=note.string("text").takeIf {it.isNotBlank()}
        val raw=longText ?: detail.string("full_text")
        if(raw.length>65_536)return null
        val truncated=longText==null&&(detail.yes("truncated")||tweet.obj("legacy").yes("truncated"))
        val mappings=urls(if(longText!=null)note.obj("entity_set").array("urls")else tweet.array("url_entities"))
        var text=expanded(raw,mappings)
        val canonical="https://x.com/$name/status/$number"
        var incompleteMedia=tweet.array("media_entities2").size>16
        val media=tweet.array("media_entities2").take(16).mapNotNull {entry->
            val item=entry.obj() ?: return@mapNotNull null
            val image=https(item.string("media_url_https"));val alt=item.string("ext_alt_text").take(8192)
            when(item.string("type")) {
                "photo"->image?.let {Media(it,description=alt)}.also {if(it==null)incompleteMedia=true}
                "video","animated_gif"->item.obj("video_info")?.let {VideoExtraction.xVideo(it,canonical,image,alt)}
                    ?: run {incompleteMedia=true;image?.let {Media(it,description=alt)}}
                else->{incompleteMedia=true;null}
            }
        }.toMutableList()
        val links=mappings.values.toMutableList()
        val card=tweet.obj("card").obj("legacy").array("binding_values").take(64).mapNotNull {binding->
            val b=binding.obj();val key=b.string("key");val value=b.obj("value")
            key.takeIf {it.isNotBlank()}?.let {it to value.string("string_value").ifBlank {value.obj("image_value").string("url")}}
        }.toMap()
        https(mappings[card["card_url"]] ?: card["card_url"].orEmpty())?.let {link->
            links+=link
            val title=card["title"].orEmpty();val description=card["description"].orEmpty()
            if(title.isNotBlank()&&title.length+description.length<=8192) {
                val additional=listOf(title,description).filter {it.isNotBlank()&&!text.contains(it)}
                if(additional.isNotEmpty())text+=additional.joinToString("\n",prefix="\n\n")
            }
            listOf("photo_image_full_size_large","summary_photo_image_large","thumbnail_image_large","thumbnail_image").firstNotNullOfOrNull {https(card[it].orEmpty())}?.let {media+=Media(it)}
        }
        val quoted=tweet.obj("quoted_tweet_results").obj("result")
        val quote=if(depth<1)unwrap(quoted)?.let {post(it,stamp,depth+1)}else null
        val quoteExpected=quoted!=null||tweet.obj("legacy").yes("is_quote_status")
        val quotePartial=quote?.preview==true||quote?.extractionCoverage?.quotedContext in setOf(ExtractionStatus.PARTIAL,ExtractionStatus.UNAVAILABLE)
        if(quote!=null) {
            val quoteUser=unwrap(quoted).obj("core").obj("user_results").obj("result")
            val label=quoteUser.obj("core").string("name").ifBlank {quote.author}
            text+="\n\nQuoted post by $label:\n${quote.body}\n${quote.canonicalUrl}"
            media+=quote.media;links+=quote.canonicalUrl;links+=quote.links.orEmpty()
        }
        if(text.isBlank()&&media.isEmpty())return null
        val parent=listOf(detail.string("in_reply_to_status_id_str"),tweet.obj("legacy").string("in_reply_to_status_id_str")).firstOrNull(id::matches)
        return Post("post:$number",Platform.X,canonical,name,text,publishedAt=time,avatar=https(user.obj("avatar").string("image_url")),
            media=MediaDuration.distinctMedia(media),replyCount=tweet.obj("counts").long("reply_count")?.takeIf {it>=0},
            parentId=parent?.let {"post:$it"},sourceParentId=parent,relationId=number,provenance=stamp,preview=truncated,
            links=links.filter {it!=canonical}.distinct().take(PostLinks.MAX_LINKS),
            extractionCoverage=ExtractionCoverage(text=if(truncated)ExtractionStatus.PARTIAL else if(raw.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,
                quotedContext=if(quotePartial)ExtractionStatus.PARTIAL else if(quote!=null)ExtractionStatus.PRESENT else if(quoteExpected)ExtractionStatus.UNAVAILABLE else ExtractionStatus.UNKNOWN,
                attachments=if(incompleteMedia||quote?.extractionCoverage?.attachments==ExtractionStatus.PARTIAL)ExtractionStatus.PARTIAL else if(media.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,
                note=if(quotePartial||depth>=1&&quoteExpected)"Further nested quote context was not read."else null))
    }

    /** Only Result.Ok.value is decoded as JSON; a caption containing JSON is text. */
    private fun timeline(message:JsonObject):List<JsonElement>? {
        val queue=ArrayDeque<Pair<JsonElement,Int>>();queue.add(message to 0)
        val seen=Collections.newSetFromMap(IdentityHashMap<JsonElement,Boolean>());var nodes=0
        while(queue.isNotEmpty()) {
            val (value,depth)=queue.removeFirst()
            if(++nodes>MAX_NODES)error("Timeline budget exceeded")
            if(depth>16||!seen.add(value))continue
            if(value.isJsonObject) {
                val o=value.asJsonObject
                o.obj("timeline")?.get("instructions")?.takeIf {it.isJsonArray}?.let {return it.asJsonArray.toList()}
                if(o.string("kind")=="Result.Ok"&&o["value"]?.let {it.isJsonPrimitive&&it.asJsonPrimitive.isString}==true)
                    queue.add(strictJson(o.string("value")) to depth+1)
                o.entrySet().forEach {(_,child)->if(child.isJsonObject||child.isJsonArray)queue.add(child to depth+1)}
            }else if(value.isJsonArray)value.asJsonArray.forEach {queue.add(it to depth+1)}
        }
        return null
    }
    private fun strictJson(text:String):JsonElement {
        require(text.length<=MAX_BYTES);var nodes=0
        JsonReader(text.reader()).use {r->
            r.strictness=Strictness.STRICT;r.nestingLimit=64
            fun value(depth:Int):JsonElement {
                require(depth<=64&&++nodes<=MAX_NODES)
                return when(r.peek()) {
                    JsonToken.BEGIN_OBJECT->{val out=JsonObject();r.beginObject();while(r.hasNext()){val key=r.nextName();require(key.length<=8192&&!out.has(key));out.add(key,value(depth+1))};r.endObject();out}
                    JsonToken.BEGIN_ARRAY->{val out=JsonArray();r.beginArray();while(r.hasNext())out.add(value(depth+1));r.endArray();out}
                    JsonToken.STRING->JsonPrimitive(r.nextString())
                    JsonToken.NUMBER->JsonPrimitive(BigDecimal(r.nextString()))
                    JsonToken.BOOLEAN->JsonPrimitive(r.nextBoolean())
                    JsonToken.NULL->{r.nextNull();JsonNull.INSTANCE}
                    else->error("Non-JSON timeline")
                }
            }
            val out=value(0);require(r.peek()==JsonToken.END_DOCUMENT);return out
        }
    }

    /** Strict data literals, not JavaScript. Completed references share immutable
     * values; unknown/self/forward references stay unavailable. No calls, getters,
     * constructors, property access or expressions can execute. */
    internal class LiteralData(private val input:String,start:Int=0) {
        private var at=start;private var nodes=0
        private val refs=mutableMapOf<Int,JsonElement>();private val assigned=mutableSetOf<Int>()
        init {require(input.length<=MAX_BYTES)}
        val position get()=at
        fun read():JsonElement=value(0)
        private fun peek()=input.getOrNull(at)
        private fun space(){while(peek()?.let {it==' '||it=='\t'||it=='\r'||it=='\n'}==true)at++}
        private fun expect(c:Char){space();require(peek()==c);at++}
        private fun reference():Int {
            expect('$');expect('R');expect('[');val start=at
            while(peek()?.let {it in '0'..'9'}==true)at++
            require(at-start in 1..6);val id=input.substring(start,at).toInt();expect(']');return id
        }
        private fun value(depth:Int):JsonElement {
            require(depth<=64&&++nodes<=MAX_NODES);space();val labels=mutableListOf<Int>()
            while(peek()=='$') {
                val start=at;val id=reference();space()
                if(peek()!='='){at=start;break}
                require(assigned.size<4096&&assigned.add(id));labels+=id;at++;space()
            }
            val out:JsonElement=when(peek()) {
                '{'->{at++;space();val o=JsonObject()
                    if(peek()=='}')at++ else while(true) {
                        space();val key=if(peek()=='"')string()else {
                            val start=at
                            val numeric=peek()?.let {it in '0'..'9'}==true
                            require(peek()?.let {it.isLetter()&&it.code<128||it in "_$"||numeric}==true)
                            while(peek()?.let {if(numeric)it in '0'..'9'else it.code<128&&(it.isLetterOrDigit()||it in "_$")}==true)at++
                            input.substring(start,at)
                        }
                        require(key.length<=8192&&!o.has(key));expect(':');o.add(key,value(depth+1));space()
                        if(peek()=='}'){at++;break};expect(',');space();if(peek()=='}'){at++;break}
                    };o}
                '['->{at++;space();val a=JsonArray()
                    while(peek()!=']') {
                        if(peek()==','){require(++nodes<=MAX_NODES);a.add(JsonNull.INSTANCE)}else a.add(value(depth+1))
                        space();if(peek()==']')break;expect(',');space()
                    };expect(']');a}
                '"'->JsonPrimitive(string())
                '$'->refs[reference()] ?: JsonNull.INSTANCE
                else->{
                    val word=literals.keys.firstOrNull {input.startsWith(it,at)}
                    if(word!=null){at+=word.length;literals.getValue(word)}else {
                        val start=at;if(peek()=='-')at++
                        while(peek()?.let {it in '0'..'9'}==true)at++
                        if(peek()=='.'){at++;while(peek()?.let {it in '0'..'9'}==true)at++}
                        if(peek() in listOf('e','E')){at++;if(peek() in listOf('+','-'))at++;val exponent=at;while(peek()?.let {it in '0'..'9'}==true)at++;require(at>exponent)}
                        require(at>start);JsonPrimitive(BigDecimal(input.substring(start,at)))
                    }
                }
            }
            labels.forEach {refs[it]=out};return out
        }
        private fun string():String {
            expect('"');val out=StringBuilder()
            fun hex(length:Int):Char {require(at+length<=input.length);val token=input.substring(at,at+length);require(token.all {it in "0123456789abcdefABCDEF"});at+=length;return token.toInt(16).toChar()}
            while(at<input.length) {
                val c=input[at++];if(c=='"')return out.toString()
                require(c>=' ')
                if(c!='\\'){out.append(c);continue}
                require(at<input.length)
                out.append(when(val escape=input[at++]) {
                    '"','\\','/','\''->escape
                    'n'->'\n';'r'->'\r';'t'->'\t';'b'->'\b';'f'->'\u000c';'v'->'\u000b';'0'->{require(peek()?.isDigit()!=true);'\u0000'}
                    'x'->hex(2);'u'->hex(4)
                    else->error("Non-data string escape")
                })
            }
            error("Unterminated data string")
        }
    }
}
