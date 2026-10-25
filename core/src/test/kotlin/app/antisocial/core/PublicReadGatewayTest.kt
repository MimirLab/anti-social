/* Owned SOCKS transport + scripted byte engine test Gateway policy, not TLS cryptography.
 * Native TLS/H2 and platform PKI are qualified separately in Android fixtures. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager

class PublicReadGatewayTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val permit=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)}
    private val url="https://reader.invalid/NASA"
    private val target=Target(Platform.X,TargetKind.PROFILE,"NASA",url)
    private val source=Source(Platform.X,PublicPreviewAdapter.DESKTOP,"https://reader.invalid",role=SourceRole.ORIGINAL)
    private class Proxy:Closeable {
        val socket=ServerSocket(0,8,InetAddress.getLoopbackAddress())
        val hosts=CopyOnWriteArrayList<String>();val peers=CopyOnWriteArrayList<Socket>()
        val port get()=socket.localPort
        val ready=CountDownLatch(1)
        private val thread=Thread {
            while(!socket.isClosed)try {
                val peer=socket.accept();peers+=peer;peer.soTimeout=3000
                val input=peer.getInputStream();val output=peer.getOutputStream()
                fun read(n:Int)=ByteArray(n).also {bytes->var at=0;while(at<n){val got=input.read(bytes,at,n-at);check(got>0);at+=got}}
                check(read(3).contentEquals(byteArrayOf(5,1,0)));output.write(byteArrayOf(5,0));output.flush()
                check(read(4).contentEquals(byteArrayOf(5,1,0,3)));val length=read(1)[0].toInt()and 255
                hosts+=String(read(length),Charsets.US_ASCII);read(2)
                output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));output.flush();ready.countDown()
                // A stalled script can wait for Gateway's cancellation/close.
                while(input.read()!=-1){}
            }catch(e:Exception){if(!socket.isClosed&&e !is java.net.SocketTimeoutException)failure=e}
        }.apply {isDaemon=true;name="owned-public-read-proxy";start()}
        @Volatile var failure:Exception?=null
        override fun close(){socket.close();peers.forEach {runCatching {it.close()}};thread.join(4000);assertFalse(thread.isAlive);failure?.let {throw it}}
    }
    private inner class Factory(val body:ByteArray="usable public document".toByteArray()):PublicReadEngineFactory {
        val requests=CopyOnWriteArrayList<List<Pair<String,String>>>();val closed=AtomicInteger();val opened=AtomicInteger()
        val pumped=CountDownLatch(1)
        var stall=false;var verify=true;var decodeBeforeVerify=false
        var response:(Int)->List<Pair<String,String>> = {listOf("content-type" to "text/plain")}
        var status:(Int)->Int={200}
        val certificate=javaClass.getResourceAsStream("/public-read-fixture.der")!!.use {it.readBytes()}
        override fun supports(cohort:Int)=true
        override fun verifyChain(manager:X509TrustManager,session:PublicReadTlsSession) {
            assertArrayEquals(certificate,session.peerCertificates.first().encoded)
            assertEquals("reader.invalid",session.peerHost)
        }
        override fun open(cohort:Int,host:String,port:Int,path:String,headers:List<Pair<String,String>>,limit:Int,verify:(PublicReadPeer)->Boolean,decoded:(Int)->Unit):PublicReadByteEngine {
            val index=opened.getAndIncrement();requests+=headers
            return object:PublicReadByteEngine {
                var progressed=false
                override fun step():Int {
                    if(stall){pumped.countDown();return 0}
                    if(!progressed){
                        progressed=true
                        if(decodeBeforeVerify)decoded(body.size)
                        if(this@Factory.verify)check(verify(PublicReadPeer(listOf(certificate),"TLSv1.3","TLS_AES_128_GCM_SHA256",byteArrayOf(),byteArrayOf())))
                        if(!decodeBeforeVerify)decoded(body.size)
                    }
                    return 2
                }
                override fun feed(bytes:ByteArray,count:Int)=Unit
                override fun output(bytes:ByteArray)=0
                override fun result()=PublicReadResult(status(index),response(index),body)
                override fun close(){closed.incrementAndGet()}
            }
        }
    }
    private fun gateway(proxy:Proxy,factory:Factory,filter:RequestFilter=permit)=NetworkGateway(cleaner,filter,true).apply {
        installPublicReadFactory(factory);configure(NetworkPolicy(tor=true,socksPort=proxy.port,ready=true))
    }
    @Test fun torUsesDomainAddressingAndBudgetsDecodedBytesExactlyOnce():Unit=runBlocking {
        Proxy().use {proxy->val factory=Factory(ByteArray(900){'a'.code.toByte()});val gateway=gateway(proxy,factory)
            gateway.boundedHealthRead(1800,2){repeat(2){assertEquals(900,gateway.publicPreview(url,target,source).text.length)}}
            assertEquals(listOf("reader.invalid","reader.invalid"),proxy.hosts);assertEquals(2,factory.closed.get())
            assertTrue(factory.requests.all {headers->headers.none {it.first in setOf("authorization","x-api-key")}})
        }
    }
    @Test fun anonymousCookiesStayScopedAndDoNotSurviveModeRoundTrips():Unit=runBlocking {
        Proxy().use {proxy->val factory=Factory();factory.response={listOf("set-cookie" to "guest=public; Secure; Path=/","content-type" to "text/plain")}
            val gateway=gateway(proxy,factory)
            gateway.publicPreview(url,target,source);gateway.publicPreview(url,target,source)
            val other=target.copy(identity="ESA",canonicalUrl="https://reader.invalid/ESA")
            gateway.publicPreview(other.canonicalUrl,other,source)
            gateway.configure(NetworkPolicy());gateway.configure(NetworkPolicy(tor=true,socksPort=proxy.port,ready=true))
            gateway.publicPreview(url,target,source)
            assertNull(factory.requests[0].toMap()["cookie"]);assertEquals("guest=public",factory.requests[1].toMap()["cookie"])
            assertNull(factory.requests[2].toMap()["cookie"]);assertNull(factory.requests[3].toMap()["cookie"])
        }
    }
    @Test fun redirectsAreFilteredAndCannotLeaveTheIssuingOrigin():Unit=runBlocking {
        for(destination in listOf("https://other.invalid/path","/blocked"))Proxy().use {proxy->
            val factory=Factory();factory.status={302};factory.response={listOf("location" to destination)}
            val checked=CopyOnWriteArrayList<String>()
            val gateway=gateway(proxy,factory,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String):FilterDecision {checked+=url;return FilterDecision(url.endsWith("/blocked"))}})
            assertThrows(IOException::class.java){runBlocking {gateway.publicPreview(url,target,source)}}
            assertEquals(1,factory.opened.get());assertEquals(1,proxy.hosts.size)
            if(destination=="/blocked")assertTrue(checked.any {it.endsWith("/blocked")})
        }
    }
    @Test fun sameOriginRedirectRetainsOnlyTheScopedAnonymousState():Unit=runBlocking {
        Proxy().use {proxy->val factory=Factory();factory.status={if(it==0)302 else 200}
            factory.response={if(it==0)listOf("location" to "/NASA/","set-cookie" to "guest=public; Secure; Path=/")else listOf("content-type" to "text/plain")}
            val gateway=gateway(proxy,factory);assertTrue(gateway.publicPreview(url,target,source).finalUrl.endsWith("/NASA/"))
            assertEquals("guest=public",factory.requests[1].toMap()["cookie"])
        }
    }
    @Test fun unverifiedResultsAndPreverificationBodyAreRejected():Unit=runBlocking {
        for(early in listOf(false,true))Proxy().use {proxy->val factory=Factory().apply {verify=false;decodeBeforeVerify=early}
            assertThrows(IOException::class.java){runBlocking {gateway(proxy,factory).publicPreview(url,target,source)}}
            assertEquals(1,factory.closed.get())
        }
    }
    @Test fun decodedByteAndAttemptLimitsApplyBeforeSuccessfulDelivery():Unit=runBlocking {
        Proxy().use {proxy->val factory=Factory(ByteArray(900));val gateway=gateway(proxy,factory)
            assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(1024,2){gateway.publicPreview(url,target,source);gateway.publicPreview(url,target,source)}}}
            assertEquals(2,factory.closed.get())
            val before=factory.opened.get()
            assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(1800,1){gateway.publicPreview(url,target,source);gateway.publicPreview(url,target,source)}}}
            assertEquals(before+1,factory.opened.get())
        }
    }
    @Test fun modeChangeClosesAStalledSocketAndUnrelatedTorReadinessDoesNotCancelDirectSessions():Unit=runBlocking {
        Proxy().use {proxy->val factory=Factory().apply {stall=true};val gateway=gateway(proxy,factory)
            val task=async {runCatching {gateway.publicPreview(url,target,source)}}
            withContext(Dispatchers.IO){assertTrue(factory.pumped.await(3,TimeUnit.SECONDS))}
            gateway.configure(NetworkPolicy())
            assertTrue(withTimeout(3000){task.await()}.isFailure);assertEquals(1,factory.closed.get())
            val direct=gateway.currentSession();gateway.configureTor(NetworkPolicy(tor=true,socksPort=proxy.port,ready=true))
            assertSame(direct,gateway.currentSession())
        }
    }
    @Test fun unavailableTransportOriginalOptOutAndUnreadyTorDoNotOpenAConnection():Unit=runBlocking {
        Proxy().use {proxy->val factory=Factory();val gateway=gateway(proxy,factory)
            gateway.originalFallback=false
            assertThrows(IOException::class.java){runBlocking {gateway.publicPreview(url,target,source)}}
            gateway.originalFallback=true;gateway.configure(NetworkPolicy(tor=true,socksPort=proxy.port,ready=false))
            assertThrows(IOException::class.java){runBlocking {gateway.publicPreview(url,target,source)}}
            val missing=NetworkGateway(cleaner,permit,true)
            assertThrows(SourceFailure::class.java){runBlocking {missing.publicPreview(url,target,source)}}
            assertEquals(0,factory.opened.get());assertTrue(proxy.hosts.isEmpty())
        }
    }
}
