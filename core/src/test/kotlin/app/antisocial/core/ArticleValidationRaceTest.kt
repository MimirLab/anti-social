package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** An obsolete frame must not evict a newer frame's approved validator. */
class ArticleValidationRaceTest {
    @Test fun obsoleteAdmissionCannotDestroyNewSessionRevalidation():Unit=runBlocking {
        val old=NetworkSession(NetworkPolicy());val active=AtomicReference(old)
        val entering=CountDownLatch(1);val release=CountDownLatch(1);val tracked=ThreadLocal<Int>()
        val uri="https://publisher.example/repair"
        val html="<html><head><title>Repair measurements</title></head><body><article><p>The workshop measured the replacement connector under the documented load and retained the complete calibration records. The measured retention force remained stable throughout the repeated insertion sequence, and the service manual lists the compatible parts and the equipment required for this repair.</p></article></body></html>"
        val transport=object:ArticleTransport {
            override val originalAllowed=true
            override val mode get()=active.get().policy
            override val session:NetworkSession get() {
                val snapshot=active.get()
                tracked.get()?.let {reads->
                    tracked.set(reads+1)
                    if(reads+1==2){entering.countDown();check(release.await(5,TimeUnit.SECONDS))}
                }
                return snapshot
            }
            override suspend fun document(url:String,source:String,headers:Map<String,String>,head:Boolean,strict:Boolean,purpose:FetchPurpose,expectedSession:NetworkSession):GatewayDocument {
                if(expectedSession!==active.get())throw CancellationException("Obsolete fixture session")
                val tag=if(expectedSession===old)"\"old-frame\""else "\"new-frame\""
                return if(headers["If-None-Match"]==tag)GatewayDocument(304,"Unchanged",emptyMap(),"",url,conditional=true)
                    else GatewayDocument(200,"OK",mapOf("Content-Type" to listOf("text/html"),"ETag" to listOf(tag)),html,url)
            }
            override suspend fun index(url:String,source:String,origins:Set<String>,expectedSession:NetworkSession):Payload=error("This successful publisher fixture needs no index")
        }
        val resolver=PublicArticleResolver(transport)
        val original=(resolver.conditional(uri,uri) as ContextArticleFetch.Ready).document
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val obsolete=async(dispatcher) {
            tracked.set(0)
            try {runCatching {resolver.conditional(uri,uri,ContextArticleValidators(original.etag),uri)}.exceptionOrNull()}
            finally {tracked.remove()}
        }
        try {
            assertTrue("The old read must have captured its session before the round trip",withContext(Dispatchers.IO){entering.await(5,TimeUnit.SECONDS)})
            active.set(NetworkSession(NetworkPolicy()))
            val current=(resolver.conditional(uri,uri) as ContextArticleFetch.Ready).document
            assertEquals("\"new-frame\"",current.etag)
            release.countDown();assertTrue(obsolete.await() is CancellationException)
            assertTrue("The obsolete request must leave the new session's conditional fast path intact",resolver.conditional(uri,uri,ContextArticleValidators(current.etag),uri) is ContextArticleFetch.NotModified)
        }finally {release.countDown();obsolete.cancelAndJoin();dispatcher.close()}
    }
}
