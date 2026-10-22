package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises Android's real cleartext policy as well as the scoped Gateway. */
@RunWith(AndroidJUnit4::class)
class AiLocalServerTest {
    @Test fun delayedInferenceBodyUsesTheModelBudgetOnAndroid():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cleaner=UrlCleaner(JsonParser.parseString(context.assets.open("clean-links.json").bufferedReader().use {it.readText()}).asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})
        val executor=Executors.newSingleThreadExecutor()
        try {ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use {server->
            server.soTimeout=10_000
            val served=executor.submit<Boolean> {
                server.accept().use {socket->
                    socket.soTimeout=10_000
                    val input=socket.getInputStream().bufferedReader();var length=0
                    while(true) {val line=input.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                    val body=CharArray(length);var read=0
                    while(read<length){val n=input.read(body,read,length-read);if(n<0)break;read+=n}
                    val response="""{"choices":[{"finish_reason":"stop","message":{"content":"The delayed source is complete."}}]}""".toByteArray()
                    val output=socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray());output.flush()
                    // The real body gap exceeded the old ordinary read timeout.
                    Thread.sleep(16_000)
                    output.write(response);output.flush();true
                }
            }
            val provider=AiRemoteProvider(gateway,AiRemoteConfig("http://127.0.0.1:${server.localPort}/v1","delayed-fixture","",true))
            assertEquals("The delayed source is complete.",withTimeout(30_000){provider.text("Read one source.","An explicit bounded test request.",8192)})
            assertTrue(served.get(5,TimeUnit.SECONDS));assertEquals(15_000,gateway.client().readTimeoutMillis)
        }}finally {executor.shutdownNow();gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
    }

    @Test fun explicitlySelectedLoopbackModelWorksThroughGatewayAndTorStopsItBeforeIo(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cleaner=UrlCleaner(JsonParser.parseString(context.assets.open("clean-links.json").bufferedReader().use {it.readText()}).asJsonObject)
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
        val executor=Executors.newSingleThreadExecutor()
        ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use {server->
            server.soTimeout=10_000
            val received=executor.submit<String> {
                server.accept().use {socket->
                    socket.soTimeout=10_000
                    val input=socket.getInputStream().bufferedReader();val request=StringBuilder();var length=0
                    while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;request.append(line).append('\n');if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                    val body=CharArray(length);var count=0;while(count<length){val n=input.read(body,count,length-count);if(n<0)break;count+=n};request.append(body)
                    val response="{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".toByteArray()
                    socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray());write(response);flush()}
                    request.toString()
                }
            }
            val provider=AiRemoteProvider(gateway,AiRemoteConfig("http://127.0.0.1:${server.localPort}/v1","fixture-model","fixture-secret",true))
            assertEquals("OK",provider.text("Be brief.","Say OK.",8))
            val request=received.get(15,TimeUnit.SECONDS)
            assertTrue(request.startsWith("POST /v1/chat/completions"));assertTrue(request.contains("Authorization: Bearer fixture-secret",true))
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            try {provider.text("Be brief.","Say OK.",8);fail("A selected Tor connection must never bypass to LAN")}catch(_: java.io.IOException){}
        }
        executor.shutdownNow()
    }
}
