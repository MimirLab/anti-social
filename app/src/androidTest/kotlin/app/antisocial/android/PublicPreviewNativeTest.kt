/* anti social, GPL-3.0-or-later. Explicit opt-in, public-source qualification. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PublicPreviewNativeTest {
    @Test fun nativeEngineLoadsCheckedDependenciesInTheActualApk() {
        assumeTrue(BuildConfig.EXPERIMENTAL_PUBLIC_READ)
        val factory=NativePublicReadFactory();assertTrue(factory.supports(0));assertTrue(factory.supports(1))
        val versions=JsonParser.parseString(PublicReadNative.versions()).asJsonObject
        assertEquals("1.70.0",versions["nghttp2"].asString);assertEquals("1.3.2",versions["zlib"].asString)
        assertEquals(0x1002001,versions["brotli"].asInt)
        repeat(2){cohort->factory.open(cohort,"reader.invalid",443,"/",listOf("user-agent" to "fixture"),4096,
            verify={throw AssertionError("No peer received")},decoded={throw AssertionError("No response received")}).use {engine->
            assertEquals(1,engine.step());val bytes=ByteArray(32768);val n=engine.output(bytes)
            assertTrue(n>100);assertEquals(22,bytes[0].toInt());assertEquals(1,bytes[5].toInt());assertEquals(0,engine.step())
        }}
    }

    @Test fun compareRealPublicPreviewsThroughTheActualGateway()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.EXPERIMENTAL_PUBLIC_READ&&args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicRead")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        fun asset(name:String)=context.assets.open(name).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-preview/live").apply {mkdirs()}
        val rows=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);gateway.installPublicReadFactory(NativePublicReadFactory())
            try {
                val urls=listOf("https://www.instagram.com/nasa/","https://www.facebook.com/NASA/","https://www.tiktok.com/@nasa","https://x.com/NASA")
                for(url in urls) {
                    val target=requireNotNull(routes.recognize(url));val source=Source(target.platform,"original",java.net.URI(url).let {"https://${it.host}"},role=SourceRole.ORIGINAL)
                    for((label,cohort) in listOf("standard" to -1,"desktop" to 1,"mobile" to 0)) {
                        val row=linkedMapOf<String,Any?>("url" to url,"transport" to label);val start=System.nanoTime()
                        var paused=false
                        try {
                            val payload=withTimeout(18_000){if(cohort<0)gateway.text(url)else gateway.publicPreview(url,target,source,cohort)}
                            val bytes=payload.text.toByteArray();row["status"]=payload.status;row["bytes"]=bytes.size;row["finalUrl"]=payload.finalUrl
                            row["sha256"]=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                            File(folder,"${target.platform.id}-$label.html").writeBytes(bytes)
                            try {
                                val page=PublicPreviewPages.parse(payload,target,source,routes)
                                row["posts"]=page.posts.size;row["dated"]=page.posts.count {it.publishedAt!=null};row["profile"]=page.profile?.name
                                row["examples"]=page.posts.take(2).map {mapOf("url" to it.canonicalUrl,"body" to it.body.take(160))}
                            }catch(e:Exception){row["parseError"]=e.message}
                        }catch(e:Exception){row["error"]=generateSequence<Throwable>(e){it.cause}.take(4).map {it.javaClass.simpleName+": "+it.message}.toList();paused=(e as? SourceFailure)?.status==HealthStatus.RATE_LIMITED}
                        row["elapsedMs"]=(System.nanoTime()-start)/1_000_000;rows+=row
                        File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
                        if(paused)break
                    }
                }
            }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
        }
        assertTrue("The native route must return actual readable dated posts, not just a successful shell: $rows",
            rows.any {it["transport"]!="standard"&&(it["dated"] as? Int ?: 0)>0})
    }

    @Test fun measureRecoveryBeyondTheExistingSourceStack()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.EXPERIMENTAL_PUBLIC_READ&&args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicRead")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        fun asset(name:String)=context.assets.open(name).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"public-preview/recovery").apply {mkdirs()}
        val rows=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            for(url in listOf("https://www.facebook.com/NASA/","https://www.instagram.com/nasa/","https://x.com/NASA")) {
                val target=requireNotNull(routes.recognize(url));val gateway=NetworkGateway(cleaner,filter)
                val row=linkedMapOf<String,Any?>("url" to url);var baseline=emptyList<Post>()
                try {
                    val attempts=java.util.concurrent.CopyOnWriteArrayList<Map<String,Any?>>()
                    val pathways=PublicPathways(asset("public-pathways.json"))
                    val pool=SourcePool(gateway,routes,pathways,onProbe={attempts+=mapOf("source" to it.source.key,"status" to it.status.name,"detail" to it.detail,"ms" to it.latencyMs)})
                    row["bundledPathways"]=pathways.sources(target).size
                    val start=System.nanoTime()
                    try {
                        val page=withTimeout(100_000){pool.load(target,originalFallback=true,progress={})}
                        baseline=page.posts.filter {it.body.isNotBlank()||it.media.isNotEmpty()}
                        row["baselinePosts"]=baseline.size;row["baselineSource"]=page.provenance?.source?.key
                        row["baselineDated"]=page.posts.count {it.publishedAt!=null};row["baselineNotice"]=page.notice
                    }catch(e:Exception){row["baselineError"]=e.javaClass.simpleName+": "+e.message}
                    row["baselineMs"]=(System.nanoTime()-start)/1_000_000;row["attempts"]=attempts
                    check(attempts.isNotEmpty()) {"The existing source stack was not actually exercised: $row"}
                    var headerPosts=emptyList<Post>()
                    val headerStart=System.nanoTime()
                    try {
                        val document=withTimeout(20_000){gateway.extractorDocument(url,headers=mapOf(
                            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.9","sec-ch-ua" to "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"",
                            "sec-ch-ua-mobile" to "?0","sec-ch-ua-platform" to "\"macOS\"","upgrade-insecure-requests" to "1",
                            "sec-fetch-site" to "none","sec-fetch-mode" to "navigate","sec-fetch-user" to "?1","sec-fetch-dest" to "document"),maxBytes=4L*1024*1024)}
                        File(folder,"${target.platform.id}-headers.html").writeText(document.text)
                        headerPosts=PublicPreviewPages.parse(Payload(document.text,document.finalUrl,"text/html",document.status),target,
                            Source(target.platform,"header-comparison",java.net.URI(url).let {"https://${it.host}"},role=SourceRole.ORIGINAL),routes).posts
                        row["headerPosts"]=headerPosts.size;row["headerDated"]=headerPosts.count {it.publishedAt!=null}
                    }catch(e:Exception){row["headerError"]=e.javaClass.simpleName+": "+e.message}
                    row["headerMs"]=(System.nanoTime()-headerStart)/1_000_000
                    gateway.installPublicReadFactory(NativePublicReadFactory())
                    val source=Source(target.platform,PublicPreviewAdapter.DESKTOP,java.net.URI(url).let {"https://${it.host}"},role=SourceRole.ORIGINAL)
                    val nativeStart=System.nanoTime()
                    try {
                        val payload=withTimeout(20_000){gateway.publicPreview(url,target,source)}
                        File(folder,"${target.platform.id}-native.html").writeText(payload.text)
                        val page=PublicPreviewPages.parse(payload,target,source,routes)
                        row["nativePosts"]=page.posts.size;row["nativeDated"]=page.posts.count {it.publishedAt!=null}
                        fun normalized(post:Post)=post.body.trim().replace(Regex("\\s+")," ")
                        val additional=page.posts.filter {candidate->baseline.none {known->
                            candidate.identityKey==known.identityKey||normalized(candidate).let {it.length>=30&&it==normalized(known)}||CrossPostFusion.matches(candidate,known)
                        }}
                        row["nativeOnlyPostIds"]=additional.filter {candidate->headerPosts.none {it.identityKey==candidate.identityKey}}.map {it.identityKey}
                        row["additionalPosts"]=additional.size;row["additionalDated"]=additional.count {it.publishedAt!=null&&!it.preview}
                        row["additionalExamples"]=additional.take(3).map {mapOf("url" to it.canonicalUrl,"body" to it.body.take(200),"dated" to (it.publishedAt!=null),"preview" to it.preview)}
                    }catch(e:Exception){row["nativeError"]=e.javaClass.simpleName+": "+e.message}
                    row["nativeMs"]=(System.nanoTime()-nativeStart)/1_000_000
                }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
                rows+=row;File(folder,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
            }
        }
        // A successful experiment may disqualify the extra route. Persist the
        // measured outcome; never turn duplicate success into an access claim.
        assertEquals(3,rows.size)
    }
}
