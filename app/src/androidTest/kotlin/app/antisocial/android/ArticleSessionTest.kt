/* AntiSocial, GPL-3.0-or-later. Loopback-only native Gateway/sandbox qualification. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ArticleSessionTest {
    private val article="""<html><head><title>A practical workshop report</title></head><body><article><h1>A practical workshop report</h1><p>The public workshop opens at nine on Saturday. Bring the exact equipment model and the original connector so volunteers can check compatibility before starting a repair.</p><p>The published instructions explain the replacement sequence, the required tools, and the measurements taken after reassembly. Registration closes on Friday and the venue supplies protective equipment.</p></article></body></html>"""
    private data class Reply(val code:Int=200,val body:String="",val headers:Map<String,String> = emptyMap())
    private class Server(private val reply:(String,Map<String,String>)->Reply):Closeable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val executor=Executors.newSingleThreadExecutor()
        val requests=CopyOnWriteArrayList<Pair<String,Map<String,String>>>()
        val bodyBytes=java.util.concurrent.atomic.AtomicLong()
        val url get()="http://127.0.0.1:${socket.localPort}"
        private val worker=executor.submit {
            while(!socket.isClosed) {
                val connection=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e}
                connection.use {client->
                    client.soTimeout=5_000
                    val input=client.getInputStream().bufferedReader()
                    val request=requireNotNull(input.readLine());val headers=linkedMapOf<String,String>()
                    repeat(64) {
                        val line=input.readLine() ?: error("Incomplete fixture request")
                        if(line.isEmpty()) {
                            val path=request.substringAfter(' ').substringBefore(' ')
                            requests+=path to headers.toMap()
                            val result=reply(path,headers);val bytes=result.body.toByteArray()
                            client.getOutputStream().apply {
                                write(buildString {
                                    val type=result.headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value ?: "text/html"
                                    append("HTTP/1.1 ${result.code} Fixture\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                                    result.headers.filterKeys {!it.equals("Content-Type",true)}.forEach {(k,v)->append("$k: $v\r\n")};append("\r\n")
                                }.toByteArray());write(bytes);flush();bodyBytes.addAndGet(bytes.size.toLong())
                            }
                            return@use
                        }
                        require(line.length<=8192);headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()
                    }
                    error("Fixture request exceeded its header bound")
                }
            }
        }
        override fun close(){socket.close();try {worker.get(5,TimeUnit.SECONDS)}finally {executor.shutdownNow()}}
    }
    private fun gateway()=NetworkGateway(UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject),object:RequestFilter {
        override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
    },true)
    @Suppress("INVISIBLE_MEMBER","INVISIBLE_REFERENCE")
    private fun localTransport(gateway:NetworkGateway,server:Server):ArticleTransport=object:ArticleTransport {
        override val session get()=gateway.currentSession()
        override val originalAllowed get()=gateway.originalFallback
        override suspend fun document(url:String,source:String,headers:Map<String,String>,head:Boolean,strict:Boolean,purpose:FetchPurpose,expectedSession:NetworkSession):GatewayDocument {
            val logical=java.net.URI(url);require(logical.scheme=="https"&&logical.host=="publisher.example"&&logical.rawQuery==null)
            val response=gateway.extractorDocument(server.url+logical.rawPath,method=if(head)"HEAD"else "GET",headers=headers,sourceUrl=source,sameOrigin=strict,purpose=purpose,expectedSession=expectedSession)
            return response.copy(finalUrl="https://publisher.example"+java.net.URI(response.finalUrl).rawPath)
        }
        override suspend fun index(url:String,source:String,origins:Set<String>,expectedSession:NetworkSession)=
            Payload(if(url.startsWith("https://web.archive.org"))"[[\"timestamp\",\"original\"]]"else "<title>No captures</title>",url,"text/html",200)
    }

    @Test fun nativeRevalidationRetainsItsSpeedWithoutReplayingEarlierSessionIdentifiers():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val gateway=gateway()
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject,cleaner)
        fun createReader()=ContextEvidenceReader(gateway,routes,extract=repo.documentSandbox::article)
        val tag="\"native-session-fixture\""
        try {Server {_,headers->if(headers["if-none-match"]==tag)Reply(304)else Reply(body=article,headers=mapOf("ETag" to tag))}.use {server->
            val url=server.url+"/report";val reader=createReader();val first=reader.article(url,url)
            assertTrue(first.text.contains("Registration closes on Friday"));assertEquals(tag,first.etag)
            assertEquals(first,reader.article(url,url));assertEquals(1,server.requests.size)
            assertTrue(reader.articleConditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.NotModified)
            assertEquals(tag,server.requests[1].second["if-none-match"])
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            try {reader.articleConditional(url,url,ContextArticleValidators(first.etag),first.finalUrl);fail("Unavailable Tor must stop before direct I/O")}
            catch(_:SourceFailure){}
            assertEquals(2,server.requests.size)
            gateway.configure(NetworkPolicy())
            assertTrue(reader.articleConditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.Ready)
            reader.clearArticleMemory()
            assertTrue(reader.articleConditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.Ready)
            assertTrue(createReader().articleConditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.Ready)
            assertEquals(5,server.requests.size)
            server.requests.drop(2).forEach {assertNull(it.second["if-none-match"]);assertNull(it.second["if-modified-since"])}
        }}finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
    }
    @Test fun nativeRedirectsNeverCarryThePreviousResourcesValidator():Unit=runBlocking {
        val gateway=gateway()
        try {Server {path,_->if(path=="/original")Reply(302,headers=mapOf("Location" to "/final"))else Reply(body=article)}.use {server->
            val response=gateway.extractorDocument(server.url+"/original",headers=mapOf("If-None-Match" to "\"source-only\""))
            assertEquals(2,server.requests.size);assertEquals("\"source-only\"",server.requests.first().second["if-none-match"])
            assertNull(server.requests.last().second["if-none-match"]);assertFalse(response.conditional)
            assertTrue(response.text.contains("Registration closes on Friday"))
        }}finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
    }
    /** Test-only access to the core transport seam. Logical HTTPS article URIs
     * map to a local fixture; all actual I/O uses the production Gateway. This
     * qualifies resolver/Gateway/worker composition, not public TLS identity. */
    @Suppress("INVISIBLE_MEMBER","INVISIBLE_REFERENCE")
    @Test fun nativeCanonicalReadAvoidsASecondRedirectAndBodyWithoutBypassingOriginalPolicy():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val gateway=gateway();val original="https://publisher.example/old-report";val canonical="https://publisher.example/reports/battery"
        val tag="\"canonical-native-fixture\"";var extractions=0
        try {Server {path,headers->when {
            path=="/old-report"->Reply(302,headers=mapOf("Location" to "/reports/battery"))
            headers["if-none-match"]==tag->Reply(304)
            else->Reply(body=article,headers=mapOf("ETag" to tag))
        }}.use {server->
            val resolver=PublicArticleResolver(transport=localTransport(gateway,server),extract={payload,url,at->extractions++;repo.documentSandbox.article(payload,url,at)})
            val first=(resolver.conditional(original,original) as ContextArticleFetch.Ready).document
            assertEquals(canonical,first.finalUrl);assertEquals(original,first.originalUrl);assertEquals("Original publisher",first.access!!.route)
            assertEquals(2,server.requests.size);assertEquals(1,extractions)
            assertTrue(resolver.conditional(original,original,ContextArticleValidators(first.etag),canonical) is ContextArticleFetch.NotModified)
            assertEquals(3,server.requests.size);assertEquals("/reports/battery",server.requests.last().first)
            assertEquals(tag,server.requests.last().second["if-none-match"]);assertEquals(1,extractions)
            assertEquals(article.toByteArray().size.toLong(),server.bodyBytes.get())
            gateway.originalFallback=false
            val denied=runCatching {resolver.conditional(original,original)}.exceptionOrNull()
            assertTrue(denied is SourceFailure);assertEquals(3,server.requests.size)
            val out=java.io.File(requireNotNull(context.getExternalFilesDir("validation")),"article-session").apply {mkdirs()}
            java.io.File(out,"canonical-routing.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("initialRequests" to 2,"requestsAfterRevalidation" to server.requests.size,"servedBodyBytes" to server.bodyBytes.get(),"extractions" to extractions,"originalDisabledMadeNoDocumentRequest" to true,"scope" to "Logical HTTPS fixture mapped to loopback HTTP; actual Gateway and permissionless worker, not real-publisher TLS or phone latency.")))
        }}finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
    }
    @Suppress("INVISIBLE_MEMBER","INVISIBLE_REFERENCE")
    @Test fun observedPdfMimeDoesNotFreezeTheNextNativeReadAsAPdf():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val gateway=gateway();var pdf=true;val original="https://publisher.example/changing-edition"
        try {Server {_,_->if(pdf)Reply(headers=mapOf("Content-Type" to "application/pdf"))else Reply(body=article)}.use {server->
            val resolver=PublicArticleResolver(transport=localTransport(gateway,server),extract=repo.documentSandbox::article)
            assertTrue(resolver.conditional(original,original) is ContextArticleFetch.PublicPdf)
            pdf=false
            val next=resolver.conditional(original,original)
            assertTrue(next is ContextArticleFetch.Ready)
            assertTrue((next as ContextArticleFetch.Ready).document.text.contains("Registration closes on Friday"))
            assertEquals(2,server.requests.size)
        }}finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
    }
    @Test fun retainedPmcHtmlReadsInTheWorkerWhileTheLiveSpaShellIsRejected():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialCapturedPmcHtml")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val dir=java.io.File(context.noBackupFilesDir,"pmc-html-fixture")
        val payload=repo.gson.fromJson(java.io.File(dir,"ncbi.json").readText(),Payload::class.java)
        val original="https://europepmc.org/article/PMC/11168924"
        val document=repo.documentSandbox.article(payload,original,1234)
        assertEquals("Accurate structure prediction of biomolecular interactions with AlphaFold 3",document.title)
        assertEquals("10.1038/s41586-024-07487-w",document.publicationDoi);assertTrue(document.text.length>70_000);assertFalse(document.truncated)
        val shell=repo.gson.fromJson(java.io.File(dir,"europe.json").readText(),Payload::class.java)
        assertTrue(runCatching {repo.documentSandbox.article(shell,original,1234)}.exceptionOrNull() is SourceFailure)
        val out=java.io.File(requireNotNull(context.getExternalFilesDir("validation")),"article-session").apply {mkdirs()}
        java.io.File(out,"ncbi-document.json").writeText(repo.gson.toJson(document))
    }
}
