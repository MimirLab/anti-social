/* Explicit opt-in acquisition experiment. Public data only, no accounts/JS. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PublicPreviewPagingTest {
    @Test fun qualifiedReaderPagesAndOpensAvailableFullMedia()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicPaging")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        fun asset(name:String)=context.assets.open(name).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        val target=requireNotNull(routes.recognize("https://www.instagram.com/nasa/"))
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-preview/qualified-reader").apply {mkdirs()}
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter)
            val rows=mutableListOf<Map<String,Any?>>()
            try {gateway.boundedMediaRead(16L*1024*1024,8) {
                val pool=SourcePool(gateway,routes,PublicPathways(asset("public-pathways.json")))
                val source=pool.sourcesFor(target,Capability.TIMELINE,true).single {it.frontend=="instagram-public-graphql"}
                val reader=pool.sourceReader(source);val seen=linkedSetOf<String>();var previous:Page?=null
                var first:Page?=null
                repeat(3) {index->
                    val start=System.nanoTime()
                    val page=withTimeout(20_000){if(previous==null)reader.fetch(target)else pool.continuePage(target,requireNotNull(previous))}
                    assertTrue(page.posts.isNotEmpty());assertTrue(page.posts.all {it.preview&&it.publishedAt==null})
                    val added=page.posts.count {seen.add(it.identityKey)};assertTrue(added>0)
                    assertNotNull(FeedRefresh.pendingReason(page,Platform.INSTAGRAM))
                    rows+=mapOf("page" to index,"count" to page.posts.size,"new" to added,"next" to (page.next!=null),"ms" to (System.nanoTime()-start)/1_000_000)
                    if(first==null)first=page;previous=page
                }
                val selected=first!!.posts.first();val post=withTimeout(20_000){reader.fetch(requireNotNull(routes.recognize(selected.canonicalUrl)))}.posts.single()
                assertFalse(post.preview);assertNotNull(post.publishedAt);assertTrue(post.media.isNotEmpty())
                rows+=mapOf("operation" to "post","url" to post.canonicalUrl,"dated" to (post.publishedAt!=null),"mediaTypes" to post.media.map {it.type},"coverage" to post.extractionCoverage)
                File(folder,"post.json").writeText(Gson().toJson(post))
                val galleryTarget=routes.recognize("https://www.instagram.com/p/DeAYvsnn4la/")!!
                val gallery=withTimeout(20_000){reader.fetch(galleryTarget)}.posts.single()
                rows+=mapOf("operation" to "gallery","count" to gallery.media.size,"coverage" to gallery.extractionCoverage)
                File(folder,"gallery.json").writeText(Gson().toJson(gallery))
                assertTrue(seen.size>12)
            }}finally {
                File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
                gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
            }
        }
    }
    @Test fun inspectAnonymousProfileAndGallery()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicPaging")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject})
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-preview/profile-gallery").apply {mkdirs()}
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter)
            try {
                val headers=mapOf("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9","Accept" to "*/*","X-IG-App-ID" to "936619743392459",
                    "Sec-Fetch-Site" to "same-origin","Sec-Fetch-Mode" to "cors","Sec-Fetch-Dest" to "empty")
                val profile=withTimeout(20_000){gateway.extractorDocument("https://www.instagram.com/api/v1/users/web_profile_info/?username=nasa",headers=headers,sourceUrl="https://www.instagram.com/nasa/",sameOrigin=true,maxBytes=4L*1024*1024)}
                File(folder,"profile.txt").writeText(profile.text)
                val lsd=java.util.UUID.randomUUID().toString().replace("-","").take(12)
                val code="DeAYvsnn4la" // Observed selected-grid carousel, not a guessed account/post.
                val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                val id=code.fold(java.math.BigInteger.ZERO){n,c->n.shiftLeft(6)+java.math.BigInteger.valueOf(alphabet.indexOf(c).toLong())}.toString()
                val friendly="PolarisLoggedOutDesktopWWWPostRootContentQuery"
                val fields=mapOf("lsd" to lsd,"fb_api_caller_class" to "RelayModern","fb_api_req_friendly_name" to friendly,"server_timestamps" to "true","variables" to "{\"media_id\":\"$id\"}","doc_id" to "27130156389949648")
                val body=okhttp3.FormBody.Builder().apply {fields.forEach {(k,v)->add(k,v)}}.build()
                val bytes=okio.Buffer().also {body.writeTo(it)}.readByteArray()
                val gallery=withTimeout(20_000){gateway.extractorDocument("https://www.instagram.com/api/graphql",method="POST",data=bytes,headers=headers+mapOf("Content-Type" to "application/x-www-form-urlencoded","X-FB-LSD" to lsd,"X-FB-Friendly-Name" to friendly),sourceUrl="https://www.instagram.com/p/$code/",sameOrigin=true,maxBytes=4L*1024*1024)}
                File(folder,"gallery.txt").writeText(gallery.text)
                File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("profileStatus" to profile.status,"galleryStatus" to gallery.status,"code" to code)))
            }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
        }
    }
    private data class Window(val codes:Set<String>,val cursor:String?,val more:Boolean?)
    /** Inspect only the named timeline connection. Highlight/reply cursors
     * are not pagination of the selected profile's posts. No script executes. */
    private fun window(payload:Payload):Window {
        HtmlAdmission.check(payload.text)
        val candidates=mutableListOf<JsonObject>();var left=80_000
        fun walk(value:JsonElement,depth:Int) {
            if(depth>70||--left<0)return
            if(value.isJsonObject) {
                val o=value.asJsonObject
                val user=o["xig_user_by_username"]?.takeIf {it.isJsonObject}?.asJsonObject
                user?.get("polaris_ordered_timeline_connection")?.takeIf {it.isJsonObject}?.asJsonObject?.let(candidates::add)
                o.entrySet().forEach {walk(it.value,depth+1)}
            }else if(value.isJsonArray)value.asJsonArray.forEach {walk(it,depth+1)}
        }
        val body=payload.text.trim().removePrefix("for (;;);").trimStart()
        if(body.startsWith("{")||body.startsWith("["))runCatching {JsonParser.parseString(body)}.getOrNull()?.let {walk(it,0)}
        else Jsoup.parse(payload.text).select("script[type=application/json]").take(128).forEach {script->
            runCatching {JsonParser.parseString(script.data())}.getOrNull()?.let {walk(it,0)}
        }
        val connection=candidates.singleOrNull() ?: return Window(emptySet(),null,null)
        val codes=connection.getAsJsonArray("edges")?.take(48)?.mapNotNull {edge->
            edge.takeIf {it.isJsonObject}?.asJsonObject?.getAsJsonObject("node")?.get("code")?.takeIf {it.isJsonPrimitive}?.asString
                ?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{5,64}"))}
        }?.toSet().orEmpty()
        val info=connection["page_info"]?.takeIf {it.isJsonObject}?.asJsonObject
        val more=info?.get("has_next_page")?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean
        val cursor=info?.get("end_cursor")?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
            ?.takeIf {it.length in 1..8192&&!it.any(Char::isISOControl)}
        return Window(codes,cursor,more)
    }
    @Test fun followIssuedPublicProfileCursor()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicPaging")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        fun asset(name:String)=context.assets.open(name).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        val url="https://www.instagram.com/nasa/";val target=requireNotNull(routes.recognize(url))
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-preview/paging").apply {mkdirs()}
        val rows=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            for(label in listOf("standard","headers")+if(BuildConfig.EXPERIMENTAL_PUBLIC_READ)listOf("native")else emptyList()) {
                val gateway=NetworkGateway(cleaner,filter)
                val source=Source(Platform.INSTAGRAM,PublicPreviewAdapter.DESKTOP,"https://www.instagram.com",role=SourceRole.ORIGINAL)
                if(label=="native")gateway.installPublicReadFactory(NativePublicReadFactory())
                val seen=linkedSetOf<String>();val issued=linkedSetOf<String>();var next=url
                try {
                    for(index in 0..2) {
                        val row=linkedMapOf<String,Any?>("transport" to label,"window" to index);val start=System.nanoTime()
                        try {
                            val payload=withTimeout(20_000){when(label) {
                                "native"->gateway.publicPreview(next,target,source)
                                "headers"->{val p=gateway.extractorDocument(next,maxBytes=4L*1024*1024,sameOrigin=true,headers=mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8","Accept-Language" to "en-US,en;q=0.9",
                                    "sec-ch-ua" to "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"",
                                    "sec-ch-ua-mobile" to "?0","sec-ch-ua-platform" to "\"macOS\"","sec-fetch-site" to "none","sec-fetch-mode" to "navigate","sec-fetch-user" to "?1","sec-fetch-dest" to "document"))
                                    Payload(p.text,p.finalUrl,p.headers.entries.firstOrNull {it.key.equals("content-type",true)}?.value?.firstOrNull().orEmpty(),p.status)}
                                else->gateway.text(next,maxBytes=4L*1024*1024)
                            }}
                            File(folder,"$label-$index.html").writeText(payload.text)
                            val state=window(payload);row["status"]=payload.status;row["finalUrl"]=payload.finalUrl
                            row["codes"]=state.codes.toList();row["newCodes"]=state.codes.filter {it !in seen};row["more"]=state.more
                            row["hasCursor"]=state.cursor!=null;seen+=state.codes
                            val advance=state.more==true&&state.cursor!=null&&issued.add(state.cursor)&&state.codes.isNotEmpty()&&
                                (index==0||(row["newCodes"] as List<*>).isNotEmpty())
                            row["advance"]=advance
                            row["elapsedMs"]=(System.nanoTime()-start)/1_000_000;rows+=row
                            File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
                            if(!advance)break
                            next=url.toHttpUrl().newBuilder().addQueryParameter("cursor",state.cursor).build().toString()
                        }catch(e:Exception) {
                            row["error"]=e.javaClass.simpleName+": "+e.message;row["elapsedMs"]=(System.nanoTime()-start)/1_000_000;rows+=row
                            File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows));break
                        }
                    }
                }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
            }
        }
        assertTrue(rows.isNotEmpty()) // A documented negative result is valid evidence.
    }
    @Test fun followAnonymousGraphQlCursor()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicPaging")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        fun asset(name:String)=context.assets.open(name).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cleaner=UrlCleaner(asset("clean-links.json"));val url="https://www.instagram.com/nasa/"
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-preview/graphql-paging").apply {mkdirs()}
        val rows=mutableListOf<Map<String,Any?>>()
        val friendly="PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery"
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter)
            try {gateway.boundedMediaRead(16L*1024*1024,8) {
                val header=mapOf("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9","Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                val navigation=header+mapOf("sec-ch-ua" to "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"",
                    "sec-ch-ua-mobile" to "?0","sec-ch-ua-platform" to "\"macOS\"","Sec-Fetch-Site" to "none","Sec-Fetch-Mode" to "navigate","Sec-Fetch-Dest" to "document","Sec-Fetch-User" to "?1")
                val head=withTimeout(20_000){gateway.extractorDocument(url,headers=navigation,maxBytes=4L*1024*1024)}
                File(folder,"head.html").writeText(head.text)
                val preloaders=mutableListOf<JsonObject>();var left=80_000
                fun walk(value:JsonElement,depth:Int) {
                    if(depth>70||--left<0)return
                    if(value.isJsonObject) {
                        val o=value.asJsonObject
                        if(o["queryName"]?.takeIf {it.isJsonPrimitive}?.asString==friendly&&o["actorID"]?.asString=="0")preloaders+=o
                        o.entrySet().forEach {walk(it.value,depth+1)}
                    }else if(value.isJsonArray)value.asJsonArray.forEach {walk(it,depth+1)}
                }
                Jsoup.parse(head.text).select("script[type=application/json]").take(128).forEach {script->runCatching {JsonParser.parseString(script.data())}.getOrNull()?.let {walk(it,0)}}
                val selected=preloaders.single();val doc=selected["queryID"].asString;require(doc.matches(Regex("[0-9]{10,32}")))
                val initial=selected.getAsJsonObject("variables");require(initial["username"].asString=="nasa")
                for(method in listOf("POST","GET")) {
                    var cursor:String?=null;val seen=linkedSetOf<String>();val cursors=linkedSetOf<String>()
                    for(index in 0..2) {
                        val row=linkedMapOf<String,Any?>("method" to method,"window" to index,"docId" to doc)
                        val start=System.nanoTime()
                        try {
                            val vars=initial.deepCopy().apply {cursor?.let {addProperty("after",it)}}
                            val lsd=java.util.UUID.randomUUID().toString().replace("-","").take(12)
                            val fields=linkedMapOf("lsd" to lsd,"fb_api_caller_class" to "RelayModern","fb_api_req_friendly_name" to friendly,"server_timestamps" to "true","variables" to vars.toString(),"doc_id" to doc)
                            val form=okhttp3.FormBody.Builder().apply {fields.forEach {(k,v)->add(k,v)}}.build()
                            val bytes=okio.Buffer().also {form.writeTo(it)}.readByteArray()
                            val endpoint=if(method=="GET")"https://www.instagram.com/api/graphql".toHttpUrl().newBuilder().apply {fields.forEach {(k,v)->addQueryParameter(k,v)}}.build().toString()else "https://www.instagram.com/api/graphql"
                            val document=withTimeout(20_000){gateway.extractorDocument(endpoint,method=method,data=bytes.takeIf {method=="POST"},sourceUrl=url,sameOrigin=true,maxBytes=4L*1024*1024,
                                headers=header+mapOf("Content-Type" to "application/x-www-form-urlencoded","Accept" to "*/*","X-IG-App-ID" to "936619743392459","X-FB-LSD" to lsd,"X-FB-Friendly-Name" to friendly,"Sec-Fetch-Site" to "same-origin","Sec-Fetch-Mode" to "cors","Sec-Fetch-Dest" to "empty"))}
                            File(folder,"$method-$index.txt").writeText(document.text)
                            val state=window(Payload(document.text,url,"application/json",document.status))
                            row["status"]=document.status;row["codes"]=state.codes.toList();row["newCodes"]=state.codes.filter {it !in seen};seen+=state.codes
                            row["more"]=state.more;row["hasCursor"]=state.cursor!=null
                            row["responsePrefix"]=document.text.take(300).takeIf {state.codes.isEmpty()}
                            val advance=state.codes.isNotEmpty()&&state.more==true&&state.cursor!=null&&cursors.add(state.cursor)&&(index==0||(row["newCodes"] as List<*>).isNotEmpty())
                            row["advance"]=advance;row["elapsedMs"]=(System.nanoTime()-start)/1_000_000;rows+=row
                            File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
                            if(document.status==429)return@boundedMediaRead
                            if(!advance)break
                            cursor=state.cursor
                        }catch(e:Exception) {row["error"]=e.javaClass.simpleName+": "+e.message;rows+=row;File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows));break}
                    }
                }
                val code=(rows.firstOrNull {it["method"]=="POST"&&it["window"]==0}?.get("codes") as? List<*>)?.firstOrNull() as? String
                if(code!=null) {
                    val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                    val mediaId=code.fold(java.math.BigInteger.ZERO){n,c->n.shiftLeft(6)+java.math.BigInteger.valueOf(alphabet.indexOf(c).toLong())}.toString()
                    val name="PolarisLoggedOutDesktopWWWPostRootContentQuery";val lsd=java.util.UUID.randomUUID().toString().replace("-","").take(12)
                    val fields=mapOf("lsd" to lsd,"fb_api_caller_class" to "RelayModern","fb_api_req_friendly_name" to name,"server_timestamps" to "true","variables" to JsonObject().apply {addProperty("media_id",mediaId)}.toString(),"doc_id" to "27130156389949648")
                    val body=okhttp3.FormBody.Builder().apply {fields.forEach {(k,v)->add(k,v)}}.build()
                    val bytes=okio.Buffer().also {body.writeTo(it)}.readByteArray()
                    val response=withTimeout(20_000){gateway.extractorDocument("https://www.instagram.com/api/graphql",method="POST",data=bytes,sourceUrl=url,sameOrigin=true,maxBytes=4L*1024*1024,
                        headers=header+mapOf("Content-Type" to "application/x-www-form-urlencoded","Accept" to "*/*","X-IG-App-ID" to "936619743392459","X-FB-LSD" to lsd,"X-FB-Friendly-Name" to name,"Sec-Fetch-Site" to "same-origin","Sec-Fetch-Mode" to "cors","Sec-Fetch-Dest" to "empty"))}
                    File(folder,"post.txt").writeText(response.text)
                    rows+=mapOf("method" to "POST","operation" to "post-detail","code" to code,"status" to response.status,"responsePrefix" to response.text.take(500))
                    File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
                }
            }}finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
        }
        assertTrue(rows.isNotEmpty())
    }
}
