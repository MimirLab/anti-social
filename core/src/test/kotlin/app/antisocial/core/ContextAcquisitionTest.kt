/* AntiSocial, GPL-3.0-or-later. Controlled public-data fixtures; no live endpoint claims. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ContextAcquisitionTest {
    private val id="BaW_jenozKc"
    private val canonical=YouTubeUrls.canonical(id)
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val allow=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private fun routes(vararg sources: Pair<String,String>): DarwinRoutes {
        val data=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        data.add("sources",JsonArray().apply {sources.forEach {(frontend,root)->add(JsonObject().apply {addProperty("platform","youtube");addProperty("frontend",frontend);addProperty("root",root);addProperty("fallbackOnly",false)})}})
        data.add("searchSources",JsonArray())
        return DarwinRoutes(data,cleaner)
    }
    private fun setup(vararg sources: Pair<String,String>,filter: RequestFilter=allow): Pair<ContextEvidenceReader,NetworkGateway> {
        val routes=routes(*sources);val gateway=NetworkGateway(cleaner,filter,true)
        return ContextEvidenceReader(gateway,routes,SourcePool(gateway,routes)) to gateway
    }
    private fun article(extra: String="")="""<!doctype html><html><head><title>Repair a laptop battery | Workshop</title><meta name="author" content="Asha Example"><meta property="og:site_name" content="Public Workshop"><meta property="article:published_time" content="2026-10-18T12:30:00Z"></head><body><nav>NOISE NAVIGATION <a href="/buy">Shopping promotions</a></nav><article><h1>Repair a laptop battery</h1><p>Power the laptop off and unplug the charger before opening its case. Find the battery connector and disconnect it carefully. Photograph the original cable position so the connector can be restored without guessing.</p><h2>Check the replacement</h2><p>The replacement battery must match the exact laptop model and its electrical requirements. Check the part number against the maker's documentation. A similar looking battery is not enough to establish compatibility, and damaged or swollen cells need specialist disposal.</p><p>Reconnect the replacement and inspect the cable before closing the case. Keep screws organized and avoid pinching the wiring. The <a href="/manual">service manual</a> provides the approved sequence and warns about model-specific catches.</p></article><footer>NOISE FOOTER</footer><script>fetch('/must-not-run');</script>$extra</body></html>"""
    private fun video(captions: String="[]",audio: String="[]",includeCaptions: Boolean=true)="""{"videoId":"$id","title":"A public battery guide","lengthSeconds":90,${if(includeCaptions)"\"captions\":$captions,"else ""}"adaptiveFormats":$audio}"""
    private fun vtt(text: String="Disconnect the battery, not the display.")="WEBVTT\n\n00:00:01.000 --> 00:00:03.500\n$text\n\n00:00:10.000 --> 00:00:12.000\nThe replacement costs 23 dollars.\n"

    @Test fun articleKeepsReadableBodyHeadingsAttributionAndLinksWithoutExecutingResources(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(article("<img src='/must-not-load.jpg'><iframe src='/must-not-load-frame'></iframe>")))
            val (reader,_)=setup();val url=server.url("/article").toString();val result=reader.article(url,"https://x.com/source/status/123")
            assertEquals(url,result.originalUrl);assertEquals(url,result.finalUrl);assertEquals("Asha Example",result.author);assertEquals("Public Workshop",result.siteName)
            assertEquals(1792326600000L,result.publishedAt)
            assertTrue(result.title.contains("Repair a laptop battery"));assertTrue(result.text.contains("not enough to establish compatibility"));assertTrue(result.blocks.any {it.kind==ContextArticleBlockKind.HEADING&&it.text=="Check the replacement"})
            assertTrue(result.blocks.flatMap {it.links}.contains(server.url("/manual").toString()))
            assertFalse(result.text.contains("NOISE"));assertFalse(result.text.contains("must-not-run"));assertFalse(result.truncated)
            assertEquals("/article",server.takeRequest().path);assertEquals(1,server.requestCount)
        }
    }
    @Test fun sharedArticleBytesAreReusedForNewReadersButExplicitClearRequiresAnotherRead():Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody(article()))
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody(article().replace("Asha Example","Another author")))
            val (reader,_)=setup();val url=server.url("/article").toString()
            val first=reader.article(url,url);val second=reader.article(url,"https://x.com/another/status/123")
            assertEquals(first,second);assertEquals(1,server.requestCount)
            reader.clearArticleMemory()
            assertEquals("Another author",reader.article(url,url).author);assertEquals(2,server.requestCount)
        }
    }
    @Test fun conditionalArticleReturnsTypedUnchangedAndKeepsValidators(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setHeader("Content-Type","text/html").setHeader("ETag","\"edition-2\"").setHeader("Last-Modified","Sun, 18 Oct 2026 12:30:00 GMT").setBody(article()))
            server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag","\"edition-2\""))
            val (reader,_)=setup();val url=server.url("/article").toString();val initial=reader.article(url,url)
            assertEquals("\"edition-2\"",initial.etag);assertNotNull(initial.lastModified);server.takeRequest()
            val response=reader.articleConditional(url,url,ContextArticleValidators(initial.etag,initial.lastModified))
            assertTrue(response is ContextArticleFetch.NotModified);response as ContextArticleFetch.NotModified
            assertEquals(initial.etag,response.validators.etag);assertEquals(url,response.finalUrl)
            val request=server.takeRequest();assertEquals(initial.etag,request.getHeader("If-None-Match"));assertEquals(initial.lastModified,request.getHeader("If-Modified-Since"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun clearingArticleMemoryRevokesRetainedValidators():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            repeat(2){server.enqueue(MockResponse().setHeader("Content-Type","text/html").setHeader("ETag","\"v1\"").setBody(article()))}
            val (reader,_)=setup();val url=server.url("/article").toString();val initial=reader.article(url,url)
            reader.clearArticleMemory()
            assertTrue(reader.articleConditional(url,url,ContextArticleValidators(initial.etag),initial.finalUrl) is ContextArticleFetch.Ready)
            server.takeRequest();assertNull(server.takeRequest().getHeader("If-None-Match"))
        }
    }
    @Test fun cacheClearDuringExtractionCannotRestoreTheValidator():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            repeat(2){server.enqueue(MockResponse().setHeader("Content-Type","text/html").setHeader("ETag","\"v1\"").setBody(article()))}
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var reads=0
            val gateway=NetworkGateway(cleaner,allow,true)
            val reader=ContextEvidenceReader(gateway,routes(),extract={payload,url,at->
                if(++reads==1){entered.complete(Unit);release.await()}
                ContextArticles.extract(payload,url,at,readerChecks=true)
            })
            val url=server.url("/article").toString()
            val pending=async {reader.article(url,url)}
            try {
                withTimeout(5_000){entered.await()};reader.clearArticleMemory();release.complete(Unit)
                val old=pending.await()
                assertTrue(reader.articleConditional(url,url,ContextArticleValidators(old.etag),old.finalUrl) is ContextArticleFetch.Ready)
                server.takeRequest();assertNull("A cleared in-flight extraction cannot restore its validator",server.takeRequest().getHeader("If-None-Match"))
                assertEquals(2,server.requestCount)
            }finally {release.complete(Unit);pending.cancelAndJoin()}
        }
    }
    @Test fun cacheClearDuringExtractionCannotRestoreTheArticleMemo():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            repeat(2){server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody(article()))}
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var reads=0
            val gateway=NetworkGateway(cleaner,allow,true)
            val reader=ContextEvidenceReader(gateway,routes(),extract={payload,url,at->
                if(++reads==1){entered.complete(Unit);release.await()}
                ContextArticles.extract(payload,url,at,readerChecks=true)
            })
            val url=server.url("/article").toString();val pending=async {reader.article(url,url)}
            try {
                withTimeout(5_000){entered.await()};reader.clearArticleMemory();release.complete(Unit);pending.await()
                reader.article(url,url)
                assertEquals("The pre-clear result must not become a new memory hit",2,server.requestCount)
            }finally {release.complete(Unit);pending.cancelAndJoin()}
        }
    }
    @Test fun articleLoginChallengeEmptyAndWrongMediaDoNotBecomeWorkingEvidence() {
        for((body,type,status) in listOf(
            Triple("<title>Sign in</title><form><input type=password>Log in to continue</form>","text/html",HealthStatus.LOGIN_REQUIRED),
            Triple("<title>Just a moment</title><div id=challenge-container>Verify you are human</div>","text/html",HealthStatus.CHALLENGE),
            Triple("<title>Nothing here</title><nav>Home Links More</nav>","text/html",HealthStatus.EMPTY),
            Triple(article(),"application/pdf",HealthStatus.UNSUPPORTED),
            Triple(article("<div id=paywall>Subscribe to read</div>"),"text/html",HealthStatus.LOGIN_REQUIRED)
        )) {
            val error=assertThrows(SourceFailure::class.java){ContextArticles.extract(Payload(body,"https://publisher.example/article",type,200),"https://publisher.example/article")}
            assertEquals(status,error.status)
        }
    }
    @Test fun unsafeConditionalHeadersAreRejectedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException::class.java){ContextArticleValidators("etag\r\nAuthorization: bad")}
        assertThrows(IllegalArgumentException::class.java){ContextArticleValidators(lastModified="x".repeat(257))}
    }
    @Test fun articleAndCaptionsFailClosedWhenTorIsUnavailable(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val (reader,gateway)=setup("invidious" to server.url("/").toString());gateway.configure(NetworkPolicy(tor=true,ready=false))
            try {reader.article(server.url("/article").toString(),canonical);fail("Unavailable Tor must pause article IO")}catch(_: Exception){}
            val result=reader.captions(canonical,originalFallback=false)
            assertTrue(result is ContextCaptionResult.Unavailable);assertNotEquals(ContextEvidenceFailure.ABSENT,(result as ContextCaptionResult.Unavailable).reason)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun privacyFiltersApplySeparatelyToArticleMetadataAndCaptionRequests(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video("""[{"url":"/captions","languageCode":"en","label":"English"}]""")))
            val filter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(url.contains("/captions")||url.contains("/article"),"Fixture rule")}
            val (reader,gateway)=setup("invidious" to server.url("/").toString(),filter=filter)
            try {reader.article(server.url("/article").toString(),canonical);fail("Filter must block article") }catch(_: Exception){}
            val result=reader.captions(canonical,false) as ContextCaptionResult.Unavailable
            assertEquals(ContextEvidenceFailure.BLOCKED,result.reason);assertEquals(1,server.requestCount);assertTrue(gateway.protectionStats().blocked>=2)
        }
    }
    @Test fun webVttPreservesRealTimestampGapsAndMarkupFreeNumbers() {
        val parsed=ContextCaptionParser.parse(vtt("<c.white>It is <b>not</b> 120 dollars.</c>"))
        assertEquals(2,parsed.cues.size);assertEquals(1000L,parsed.cues[0].startMs);assertEquals(3500L,parsed.cues[0].endMs);assertEquals(10000L,parsed.cues[1].startMs)
        assertEquals("It is not 120 dollars.",parsed.cues[0].text);assertFalse(parsed.truncated)
    }
    @Test fun timedTextSrv3AndTtmlDecodeActualTextAndOffsets() {
        val inputs=listOf(
            "<transcript><text start='1.25' dur='2.5'>Asha &amp; Bob said &quot;no&quot;.</text></transcript>",
            "<timedtext><body><p t='1250' d='2500'><s>Asha &amp; Bob said &quot;no&quot;.</s></p></body></timedtext>",
            "<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='00:00:01.250' end='00:00:03.750'>Asha &amp; Bob said &quot;no&quot;.</p></div></body></tt>"
        )
        for(input in inputs){val cue=ContextCaptionParser.parse(input).cues.single();assertEquals(1250L,cue.startMs);assertEquals(3750L,cue.endMs);assertEquals("Asha & Bob said \"no\".",cue.text)}
    }
    @Test fun json3PreservesSegmentsNegationAndChangedNumbers() {
        val body="""{"events":[{"tStartMs":200,"dDurationMs":1000,"segs":[{"utf8":"It is not "},{"utf8":"120 dollars. "},{"utf8":"The corrected price is 23."}]},{"tStartMs":5000,"dDurationMs":500,"segs":[{"utf8":"A later correction."}]}]}"""
        val cues=ContextCaptionParser.parse(body).cues
        assertEquals("It is not 120 dollars. The corrected price is 23.",cues.first().text);assertEquals(200L,cues.first().startMs);assertEquals(1200L,cues.first().endMs);assertEquals(5000L,cues.last().startMs)
    }
    @Test fun emptyInvalidAndUntimedCaptionsNeverBecomeSuccessfulTranscripts() {
        for(body in listOf("WEBVTT\n\n","<html><form>Sign in to view</form></html>","{\"events\":[]}","Unstructured caption-like text","WEBVTT\n\n00:00:05 --> 00:00:01\nBackwards."))assertThrows(Exception::class.java){ContextCaptionParser.parse(body)}
    }
    @Test fun captionBudgetsMarkPartialCoverageInsteadOfSilentlyCallingItComplete() {
        val body=buildString {append("WEBVTT\n\n");repeat(12001){i->append("00:${(i/60)%60}:00.000 --> 00:${(i/60)%60}:01.000\nx$i\n\n")}}
        val result=ContextCaptionParser.parse(body)
        assertEquals(12000,result.cues.size);assertTrue(result.truncated)
        assertThrows(IllegalArgumentException::class.java){ContextCaptionParser.parse("x".repeat(2*1024*1024+1))}
    }
    @Test fun invidiousReturnsNativeCaptionEvidenceAndPrefersManualMatchingLanguage(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video("""[{"url":"/fr","languageCode":"fr","label":"French"},{"url":"/auto","languageCode":"en","label":"English (auto-generated)"},{"url":"/manual","languageCode":"en","label":"English"}]""")))
            server.enqueue(MockResponse().setHeader("Content-Type","text/vtt").setBody(vtt()))
            val (reader,_)=setup("invidious" to server.url("/").toString());val result=reader.captions(canonical,false) as ContextCaptionResult.Available
            assertEquals("en",result.language);assertFalse(result.automatic);assertEquals(canonical,result.videoUrl);assertEquals(2,result.cues.size);assertEquals(90000L,result.durationMs)
            assertEquals("/api/v1/videos/$id",server.takeRequest().path);assertEquals("/manual",server.takeRequest().path);assertEquals(2,server.requestCount)
        }
    }
    @Test fun pipedAutoGeneratedCaptionsRetainTheirLabelAndProvenance(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""{"title":"Public guide","duration":90,"subtitles":[{"url":"/subtitles","code":"en-US","name":"English (auto-generated)","autoGenerated":true,"mimeType":"application/ttml+xml"}],"audioStreams":[]}"""))
            server.enqueue(MockResponse().setBody("<transcript><text start='0' dur='3'>A public statement.</text></transcript>"))
            val (reader,_)=setup("piped" to server.url("/").toString());val result=reader.captions("https://youtu.be/$id",false) as ContextCaptionResult.Available
            assertTrue(result.automatic);assertEquals("en-US",result.language);assertEquals("piped",result.source.frontend);assertEquals(server.url("/subtitles").toString(),result.sourceUrl)
            assertEquals("/streams/$id",server.takeRequest().path);assertEquals("/subtitles",server.takeRequest().path)
        }
    }
    @Test fun explicitlyAbsentCaptionsAllowSeparatelyRequestedAudioWithoutPlaybackOrAnotherFetch(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video(audio="""[{"url":"/audio.m4a","type":"audio/mp4; codecs=mp4a","bitrate":64000}]""")))
            val (reader,_)=setup("invidious" to server.url("/").toString())
            val captions=reader.captions(canonical,false,allowAudio=false) as ContextCaptionResult.Unavailable
            assertEquals(ContextEvidenceFailure.ABSENT,captions.reason);assertNull(captions.audio)
            val descriptor=reader.audio(canonical,false)!!;assertEquals(server.url("/audio.m4a").toString(),descriptor.url);assertEquals("audio/mp4",descriptor.mimeType)
            assertEquals(1,server.requestCount);assertEquals("/api/v1/videos/$id",server.takeRequest().path)
        }
    }
    @Test fun requestedAudioDescriptorCanAccompanyAbsenceButIsNeverDownloaded(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video(audio="""[{"url":"/audio.m4a","type":"audio/mp4","bitrate":64000}]""")))
            val (reader,_)=setup("invidious" to server.url("/").toString());val result=reader.captions(canonical,false,allowAudio=true) as ContextCaptionResult.Unavailable
            assertEquals(ContextEvidenceFailure.ABSENT,result.reason);assertNotNull(result.audio);assertEquals(1,server.requestCount)
        }
    }
    @Test fun missingCaptionCapabilityAndBrokenCaptionTransportDoNotAuthorizeAsr(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video(includeCaptions=false)))
            val (first,_)=setup("invidious" to server.url("/").toString());val unknown=first.captions(canonical,false) as ContextCaptionResult.Unavailable
            assertNotEquals(ContextEvidenceFailure.ABSENT,unknown.reason)
            server.enqueue(MockResponse().setBody(video("""[{"languageCode":"en","label":"Missing URL"}]""")))
            val (malformed,_)=setup("invidious" to server.url("/").toString());assertNotEquals(ContextEvidenceFailure.ABSENT,(malformed.captions(canonical,false) as ContextCaptionResult.Unavailable).reason)
            server.enqueue(MockResponse().setBody(video("""[{"url":"/broken","languageCode":"en"}]""")));server.enqueue(MockResponse().setResponseCode(403))
            val (second,_)=setup("invidious" to server.url("/").toString());val broken=second.captions(canonical,false,allowAudio=true) as ContextCaptionResult.Unavailable
            assertNotEquals(ContextEvidenceFailure.ABSENT,broken.reason);assertNull(broken.audio);assertEquals(4,server.requestCount)
        }
    }
    @Test fun alternateSourcesAreTriedBeforeOriginalAndDoNotTriggerNewHeartbeats(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {second->
            first.start();second.start();first.enqueue(MockResponse().setBody("<html>Unavailable reader</html>"));second.enqueue(MockResponse().setBody(video("""[{"url":"/captions","languageCode":"en"}]""")));second.enqueue(MockResponse().setBody(vtt()))
            val events=mutableListOf<String>();val filter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {events+=url;return FilterDecision(false)}}
            val routes=routes("invidious" to first.url("/").toString(),"invidious" to second.url("/").toString());val gateway=NetworkGateway(cleaner,filter,true);val pool=SourcePool(gateway,routes);val reader=ContextEvidenceReader(gateway,routes,pool)
            assertTrue(reader.captions(canonical,true) is ContextCaptionResult.Available)
            assertEquals(1,first.requestCount);assertEquals(2,second.requestCount);assertTrue(events.none {it.startsWith("https://www.youtube.com/")});assertTrue(pool.results(Platform.YOUTUBE).isEmpty())
        }}
    }
    @Test fun perRequestOriginalOptOutPinsCaptionAndMetadataRedirectsBeforeASocketLeavesTheSource(): Unit=runBlocking {
        MockWebServer().use {front->MockWebServer().use {foreign->
            front.start();foreign.start();foreign.enqueue(MockResponse().setBody(vtt()))
            front.enqueue(MockResponse().setBody(video("""[{"url":"/caption","languageCode":"en"}]""")));front.enqueue(MockResponse().setResponseCode(302).setHeader("Location",foreign.url("/foreign")))
            val (reader,gateway)=setup("invidious" to front.url("/").toString());assertTrue(gateway.originalFallback)
            val result=reader.captions(canonical,false) as ContextCaptionResult.Unavailable
            assertNotEquals(ContextEvidenceFailure.ABSENT,result.reason);assertEquals(0,foreign.requestCount)
            front.enqueue(MockResponse().setResponseCode(302).setHeader("Location",foreign.url("/metadata")))
            val (other,_)=setup("invidious" to front.url("/").toString());assertTrue(other.captions(canonical,false) is ContextCaptionResult.Unavailable)
            assertEquals(0,foreign.requestCount)
        }}
    }
    @Test fun originalCaptionAndAudioUrlsAreNotFollowedWhenTheCallOptsOut(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video("""[{"url":"https://www.youtube.com/api/timedtext?v=$id","languageCode":"en"}]""",audio="""[{"url":"https://r1.googlevideo.com/audio","type":"audio/mp4","bitrate":64000}]""")))
            val (reader,_)=setup("invidious" to server.url("/").toString());val result=reader.captions(canonical,false) as ContextCaptionResult.Unavailable
            assertNotEquals(ContextEvidenceFailure.ABSENT,result.reason);assertNull(reader.audio(canonical,false));assertEquals(1,server.requestCount)
        }
    }
    @Test fun noOriginalPathIsInventedWhenFallbackIsOffAndUnsupportedLinksDoNoWork(): Unit=runBlocking {
        val events=mutableListOf<String>();val filter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {events+=url;return FilterDecision(true,"Fixture never uses live networks")}}
        val (reader,_)=setup(filter=filter)
        assertTrue(reader.captions(canonical,false) is ContextCaptionResult.Unavailable);assertTrue(events.isEmpty())
        assertEquals(ContextEvidenceFailure.UNSUPPORTED,(reader.captions("https://unrelated.example/watch?v=$id",true) as ContextCaptionResult.Unavailable).reason);assertTrue(events.isEmpty())
        assertTrue(reader.captions(canonical,true) is ContextCaptionResult.Unavailable);assertTrue(events.isNotEmpty())
    }
}
