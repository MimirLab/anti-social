/* anti social, GPL-3.0-or-later. Descriptor-view ownership and retry controls. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class VideoSamplingAttemptTest {
    private class Ranges:AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val requests=AtomicInteger()
        val url get()="http://127.0.0.1:${socket.localPort}/clip"
        private val serving=Thread({
            while(!socket.isClosed)try {socket.accept().use {client->
                client.soTimeout=5000;val reader=client.getInputStream().bufferedReader();reader.readLine() ?: return@use
                var range=""
                while(true) {val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Range:",true))range=line.substringAfter(':').trim()}
                val start=range.substringAfter("bytes=").substringBefore('-').toInt();val end=minOf(range.substringAfter('-').toInt(),3*256*1024-1)
                val bytes=ByteArray(end-start+1){((start+it)%251).toByte()};requests.incrementAndGet()
                client.getOutputStream().apply {write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${3*256*1024}\r\nETag: \"fixture-one\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray());write(bytes);flush()}
            }}catch(_:Exception){if(socket.isClosed)break}
        },"video range fixture").apply {isDaemon=true;start()}
        override fun close(){socket.close();serving.join(5000)}
    }
    private fun gateway()=NetworkGateway(UrlCleaner(JsonObject().apply {add("providers",JsonObject())}),object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
    @Test fun interruptedDecoderRevalidatesPrefixAndReusesOtherCompletedBlocks():Unit=runBlocking {
        Ranges().use {server->
            val g=gateway();val attempt=VideoSamplingAttempt();val media=Media(server.url,"video");val source="https://studio.fixture.invalid/post/1"
            try {g.boundedVideoComparison {
                val first=attempt.ranges(media,source){g.mediaRanges(server.url,source)}
                assertEquals(1,first.read(256L*1024,1).size);assertEquals(2,server.requests.get())
                val decoder=launch {assertEquals(1,first.read(0,1).size);awaitCancellation()}
                yield();decoder.cancelAndJoin()
                val resumed=attempt.ranges(media,source){g.mediaRanges(server.url,source,previous=it)}
                assertNotSame(first,resumed);assertEquals(2,attempt.retainedBlocks)
                assertEquals(1,resumed.read(0,1).size);assertEquals(1,resumed.read(256L*1024,1).size)
                assertEquals(3,server.requests.get())
            }}finally {attempt.close()}
            assertEquals(0,attempt.retainedBlocks)
        }
    }
    @Test fun sourceOrAcquisitionChangesDiscardTheOldViewAndClosureCannotReopenIt():Unit=runBlocking {
        Ranges().use {server->
            val g=gateway();val attempt=VideoSamplingAttempt();var opens=0
            suspend fun read(m:Media,s:String,reuse:Boolean)=attempt.ranges(m,s){assertEquals(reuse,it!=null);opens++;g.mediaRanges(m.url,s,previous=it)}
            val a=Media(server.url,"video");val source="https://studio.fixture.invalid/post/1"
            g.boundedVideoComparison {
                val first=read(a,source,false);assertNotSame(first,read(a,source,true))
                assertNotSame(first,read(a.copy(durationMs=120_000),source,false))
                read(a,source+"?edition=2",false);read(a.copy(url=server.url+"-other"),source,false)
            }
            assertEquals(5,opens);assertEquals(5,server.requests.get());attempt.close()
            assertThrows(IllegalStateException::class.java){runBlocking {read(a,source,false)}};assertEquals(5,opens)
        }
    }
    @Test fun connectionRoundTripNeedsANewViewAndCannotBorrowTheOldFirstBlock():Unit=runBlocking {
        Ranges().use {server->
            val g=gateway();val attempt=VideoSamplingAttempt();val media=Media(server.url,"video")
            try {g.boundedVideoComparison {
                val old=attempt.ranges(media,server.url){g.mediaRanges(server.url)}
                g.configure(NetworkPolicy(tor=true,ready=false));assertFalse(old.reusable)
                assertThrows(java.io.IOException::class.java){runBlocking {attempt.ranges(media,server.url){g.mediaRanges(server.url)}}}
                assertEquals(1,server.requests.get());g.configure(NetworkPolicy())
                val fresh=attempt.ranges(media,server.url){g.mediaRanges(server.url)}
                assertNotSame(old,fresh);assertEquals(2,server.requests.get())
                assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {old.read(0,1)}}
            }}finally {attempt.close()}
        }
    }
}
