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
                                    append("HTTP/1.1 ${result.code} Fixture\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                                    result.headers.forEach {(k,v)->append("$k: $v\r\n")};append("\r\n")
                                }.toByteArray());write(bytes);flush()
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
}
