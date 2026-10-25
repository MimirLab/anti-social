/* anti social, GPL-3.0-or-later. Explicit bounded public-protocol research.
 * Operation shape researched from andresayac/instapick (MIT); no upstream code runs. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import okhttp3.FormBody
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.math.BigInteger
import java.util.UUID

/** Research output is not route qualification. Requires the explicit disposable
 * installation and live-read flags; no login state or app reader data is used. */
class InstagramChildCommentResearchTest {
    @Test fun inspectOnlyDeclaredStaticQueryMetadata():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveChildMetadata")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.getExternalFilesDir("validation"),"public-child-assets.json")
        require(input.isFile&&input.length() in 2..64*1024L)
        val urls=JsonParser.parseString(input.readText()).asJsonArray.map {it.asString}
        require(urls.size in 1..4&&urls.distinct().size==urls.size)
        urls.forEach {val u=java.net.URI(it);require(it.length<=8192&&u.scheme=="https"&&u.host=="static.cdninstagram.com"&&u.userInfo==null&&u.port==-1&&u.path.startsWith("/rsrc.php/")&&u.path.endsWith(".js")&&u.query==null&&u.fragment==null)}
        val output=File(context.getExternalFilesDir("validation"),"public-child-metadata").apply {mkdirs()}
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        val results=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client()
            try {withTimeout(60_000){gateway.boundedMediaRead(maxBytes=16L*1024*1024,maxRequests=4) {
                for((i,url)in urls.withIndex()) {
                    val doc=withTimeout(15_000){gateway.extractorDocument(url,sourceUrl="https://www.instagram.com/p/DeTUk4sDSp-/",maxBytes=4L*1024*1024)}
                    File(output,"asset-$i.txt").writeText(doc.text)
                    val matches=Regex("PolarisPost(?:ChildComments|CommentsPagination)Query").findAll(doc.text).take(12).map {match->
                        doc.text.substring((match.range.first-240).coerceAtLeast(0),(match.range.last+720).coerceAtMost(doc.text.length))
                    }.toList()
                    results+=mapOf("asset" to i,"url" to url,"status" to doc.status,"bytes" to doc.text.length,"literalMetadata" to matches)
                    if(doc.status !in 200..299)break
                }
            }}}
            catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;results+=mapOf("error" to "Owned metadata deadline expired")}
            catch(e:Exception){results+=mapOf("error" to (e.javaClass.simpleName+": "+e.message).take(1000))}
            finally {
                File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results))
                client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            }
        }
    }
    @Test fun inspectChildConnectionsForActuallyObservedParents():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveChildComments")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val code=args.getString("antisocialResearchShortcode") ?: "DeTUk4sDSp-"
        require(code.matches(Regex("[A-Za-z0-9_-]{5,16}")))
        val post="https://www.instagram.com/p/$code/"
        val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val media=code.fold(BigInteger.ZERO){n,c->n.shiftLeft(6)+BigInteger.valueOf(alphabet.indexOf(c).toLong())}.toString()
        val output=File(context.getExternalFilesDir("validation"),"public-child-comments").apply {mkdirs()}
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        val records=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client()
            val headers=mapOf("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                "Accept" to "*/*","Accept-Language" to "en-US,en;q=0.9","Content-Type" to "application/x-www-form-urlencoded",
                "X-IG-App-ID" to "936619743392459","Sec-Fetch-Site" to "same-origin","Sec-Fetch-Mode" to "cors","Sec-Fetch-Dest" to "empty")
            suspend fun query(label:String,name:String,id:String,variables:JsonObject):JsonObject {
                val lsd=UUID.randomUUID().toString().replace("-","").take(12)
                val fields=mapOf("lsd" to lsd,"fb_api_caller_class" to "RelayModern","fb_api_req_friendly_name" to name,
                    "server_timestamps" to "true","doc_id" to id,"variables" to variables.toString())
                val form=FormBody.Builder().apply {fields.forEach {(k,v)->add(k,v)}}.build()
                val start=System.nanoTime()
                val document=withTimeout(20_000){gateway.extractorDocument("https://www.instagram.com/api/graphql",method="POST",
                    data=Buffer().also {form.writeTo(it)}.readByteArray(),headers=headers+mapOf("X-FB-LSD" to lsd,"X-FB-Friendly-Name" to name),
                    sourceUrl=post,sameOrigin=true,maxBytes=4L*1024*1024)}
                File(output,"$label.json").writeText(document.text)
                records+=mapOf("step" to label,"operation" to name,"status" to document.status,"bytes" to document.text.length,"ms" to (System.nanoTime()-start)/1_000_000)
                check(document.status in 200..299){"HTTP ${document.status}; no further calls"}
                val root=JsonReader(StringReader(document.text.removePrefix("for (;;);"))).use {reader->reader.strictness=Strictness.STRICT;reader.nestingLimit=64;JsonParser.parseReader(reader).asJsonObject}
                check(root.getAsJsonArray("errors").isNullOrEmpty()&&root.get("require_login")?.toString()!="true"){"Query refused: "+root.get("errors").toString().take(600)}
                return root.getAsJsonObject("data") ?: error("No selected data")
            }
            try {withTimeout(60_000){gateway.boundedMediaRead(maxBytes=12L*1024*1024,maxRequests=4) {
                fun variables()=JsonObject().apply {addProperty("media_id",media);addProperty("first",12);add("before",JsonNull.INSTANCE);add("last",JsonNull.INSTANCE);addProperty("__relay_internal__pv__PolarisIsLoggedInrelayprovider",false)}
                val head=query("parents","PolarisPostCommentsPaginationQuery","25516980651312394",variables().apply {addProperty("sort_order","popular")})
                val edges=head.getAsJsonObject("xdt_api__v1__media__media_id__comments__connection")?.getAsJsonArray("edges") ?: error("No selected parents")
                val parents=edges.mapNotNull {it.asJsonObject.getAsJsonObject("node")}.filter {
                    it.get("parent_comment_id")?.isJsonNull==true&&it.get("pk")?.asString?.matches(Regex("[0-9]{1,40}"))==true
                }.sortedByDescending {it.get("child_comment_count")?.takeUnless {v->v.isJsonNull}?.asLong ?: 0}.take(2)
                records+=mapOf("step" to "observed-parents","rows" to edges.size(),"parents" to parents.map {mapOf("id" to it.get("pk").asString,"childCount" to it.get("child_comment_count"))})
                for((index,parent)in parents.withIndex()) {
                    val id=parent.get("pk").asString
                    if(args.getString("antisocialChildRestRead")=="true") {
                        val url="https://www.instagram.com/api/v1/media/$media/comments/$id/child_comments/?is_chronological=true&paging_direction=view_more"
                        val doc=withTimeout(20_000){gateway.extractorDocument(url,sourceUrl=post,sameOrigin=true,maxBytes=4L*1024*1024,
                            headers=headers+mapOf("X-IG-WWW-Claim" to "0"))}
                        File(output,"rest-children-$index.json").writeText(doc.text)
                        records+=mapOf("step" to "rest-children-$index","status" to doc.status,"bytes" to doc.text.length,"parent" to id)
                        check(doc.status in 200..299){"REST HTTP ${doc.status}; no further calls"}
                        val rest=JsonReader(StringReader(doc.text)).use {reader->reader.strictness=Strictness.STRICT;reader.nestingLimit=64;JsonParser.parseReader(reader).asJsonObject}
                        check(rest.get("status")?.asString=="ok"){"REST refused: "+rest.get("message")}
                        records+=mapOf("step" to "rest-child-observation-$index","fields" to rest.keySet(),"children" to rest.getAsJsonArray("child_comments")?.map {it.asJsonObject.let {n->mapOf("id" to n.get("pk"),"parent" to n.get("parent_comment_id"),"fields" to n.keySet())}})
                        continue
                    }
                    val data=query("children-$index","PolarisPostChildCommentsQuery","26914912424764761",variables().apply {addProperty("parent_comment_id",id);addProperty("is_chronological",true)})
                    val connection=data.getAsJsonObject("xdt_api__v1__media__media_id__comments__parent_comment_id__child_comments__connection")
                    val children=connection?.getAsJsonArray("edges")
                    records+=mapOf("step" to "child-observation-$index","parent" to id,"dataKeys" to data.keySet(),"rows" to children?.size(),
                        "rowBindings" to children?.map {it.asJsonObject.getAsJsonObject("node")?.let {n->mapOf("id" to n.get("pk"),"parent" to n.get("parent_comment_id"),"media" to n.get("media_id"),"fields" to n.keySet())}},"pageInfo" to connection?.get("page_info"))
                }
            }}}
            catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("error" to "Owned research deadline expired")}
            catch(e:Exception){records+=mapOf("error" to (e.javaClass.simpleName+": "+e.message).take(1000))}
            finally {
                File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records))
                client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            }
        }
    }
    private fun JsonArray?.isNullOrEmpty()=this==null||size()==0
}
