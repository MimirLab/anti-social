/* anti social, GPL-3.0-or-later. Explicit public-protocol qualification only. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

/** No production capability is registered merely because a probe ran. */
class InstagramCommentResearchTest {
    @Test fun inspectPublicCommentQueries()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicComments")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-comments").apply {mkdirs()}
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject})
        val code="DeTUk4sDSp-";val post="https://www.instagram.com/p/$code/"
        val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val media=code.fold(BigInteger.ZERO){n,c->n.shiftLeft(6)+BigInteger.valueOf(alphabet.indexOf(c).toLong())}.toString()
        val name="PolarisPostCommentsPaginationQuery";val rows=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client()
            try {
                for((index,candidate)in listOf("/api/graphql" to "25516980651312394","/graphql/query" to "26248690958161038").withIndex()) {
                    val row=linkedMapOf<String,Any?>("path" to candidate.first,"doc" to candidate.second);val started=System.nanoTime()
                    try {
                        val lsd=UUID.randomUUID().toString().replace("-","").take(12)
                        val variables=JsonObject().apply {
                            addProperty("media_id",media);addProperty("first",12);add("after",JsonNull.INSTANCE);add("before",JsonNull.INSTANCE);add("last",JsonNull.INSTANCE)
                            addProperty("sort_order","recent");addProperty("__relay_internal__pv__PolarisIsLoggedInrelayprovider",false)
                        }
                        val fields=mapOf("lsd" to lsd,"av" to "0","__user" to "0","fb_api_caller_class" to "RelayModern","fb_api_req_friendly_name" to name,"variables" to variables.toString(),"doc_id" to candidate.second)
                        val body=FormBody.Builder().apply {fields.forEach {(k,v)->add(k,v)}}.build()
                        val headers=mapOf("Content-Type" to "application/x-www-form-urlencoded","Accept" to "*/*","Accept-Language" to "en-US,en;q=0.9",
                            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                            "X-IG-App-ID" to "936619743392459","X-FB-LSD" to lsd,"X-FB-Friendly-Name" to name,"Sec-Fetch-Site" to "same-origin","Sec-Fetch-Mode" to "cors","Sec-Fetch-Dest" to "empty")
                        val document=withTimeout(20_000){gateway.extractorDocument("https://www.instagram.com"+candidate.first,method="POST",data=Buffer().also {body.writeTo(it)}.readByteArray(),headers=headers,sourceUrl=post,sameOrigin=true,maxBytes=4L*1024*1024)}
                        File(folder,"query-$index.txt").writeText(document.text);row["status"]=document.status;row["bytes"]=document.text.length
                        val root=runCatching {JsonReader(StringReader(document.text)).use {reader->reader.nestingLimit=64;JsonParser.parseReader(reader).asJsonObject}}.getOrNull()
                        val data=root?.get("data")?.takeIf {it.isJsonObject}?.asJsonObject
                        val connection=data?.get("xdt_api__v1__media__media_id__comments__connection")?.takeIf {it.isJsonObject}?.asJsonObject
                        val edges=connection?.get("edges")?.takeIf {it.isJsonArray}?.asJsonArray
                        row["comments"]=edges?.size();row["keys"]=data?.keySet()?.take(12);row["errors"]=root?.get("errors")?.toString()?.take(800)
                        row["pageInfo"]=connection?.get("page_info")?.toString();row["ms"]=(System.nanoTime()-started)/1_000_000;rows+=row
                        val pageInfo=connection?.get("page_info")?.takeIf {it.isJsonObject}?.asJsonObject
                        val after=pageInfo?.get("end_cursor")?.takeIf {it.isJsonPrimitive}?.asString
                        if(edges!=null&&edges.size()>0&&pageInfo?.get("has_next_page")?.asBoolean==true&&after!=null&&after.length<=8192) {
                            variables.addProperty("after",after)
                            val form=FormBody.Builder().apply {fields.forEach {(k,v)->add(k,if(k=="variables")variables.toString()else v)}}.build()
                            val next=withTimeout(20_000){gateway.extractorDocument("https://www.instagram.com"+candidate.first,method="POST",data=Buffer().also {form.writeTo(it)}.readByteArray(),headers=headers,sourceUrl=post,sameOrigin=true,maxBytes=4L*1024*1024)}
                            File(folder,"next-$index.txt").writeText(next.text)
                            val second=JsonReader(StringReader(next.text)).use {reader->reader.nestingLimit=64;JsonParser.parseReader(reader).asJsonObject}
                                .getAsJsonObject("data")?.getAsJsonObject("xdt_api__v1__media__media_id__comments__connection")?.getAsJsonArray("edges")
                            val seen=edges.map {it.asJsonObject.getAsJsonObject("node")["pk"].asString}.toSet()
                            rows+=mapOf("step" to "issued-continuation","status" to next.status,"comments" to second?.size(),"new" to second?.count {it.asJsonObject.getAsJsonObject("node")["pk"].asString !in seen})
                        }
                        val refusal=listOf("login_required","log in","rate limit","please wait","challenge","checkpoint").any {it in root?.get("errors").toString().lowercase()}
                        if(document.status in setOf(401,403,429)||root?.get("require_login")?.toString()=="true"||refusal||edges!=null&&edges.size()>0)break
                    }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;row["error"]="Timed out";rows+=row;break}
                    catch(e:Exception){row["error"]=e.javaClass.simpleName+": "+e.message;rows+=row;break}
                }
            }finally {
                File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
                client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            }
        }
    }
}
