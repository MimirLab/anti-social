package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GatewayMediaRangesTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val permit=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)}
    private val block=256*1024
    private fun range(start:Int,total:Int,etag:String="\"one\"")=MockResponse().setResponseCode(206)
        .setHeader("Content-Range","bytes $start-${minOf(start+block,total)-1}/$total").setHeader("ETag",etag)
        .setBody("x".repeat(minOf(block,total-start)))
    @Test fun exactValidatedBlocksAreCachedAndReadsStopAtTheRecordedEnd()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,block+7));s.enqueue(range(block,block+7))
            val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString();val view=g.mediaRanges(url)
            assertEquals((block+7).toLong(),view.length)
            assertEquals(12,view.read(block-5L,30).size);assertEquals(12,view.read(block-5L,30).size)
            assertTrue(view.read(view.length,1).isEmpty());assertTrue(view.read(0,0).isEmpty())
            assertEquals(2,s.requestCount);assertEquals(2,view.fetchedBlocks)
            assertEquals((block+7).toLong(),view.fetchedBytes)
            assertEquals("bytes=0-${block-1}",s.takeRequest().getHeader("Range"))
            assertEquals("\"one\"",s.takeRequest().getHeader("If-Range"))
        }
    }
    @Test fun ignoredRangesWeakValidatorsEncodedAndWrongRangesNeverBecomeAByteView()=runBlocking {
        for(response in listOf(MockResponse().setBody("whole file"),range(0,10,"W/\"one\""),range(0,10).setHeader("Content-Encoding","br"),range(0,10).setHeader("Content-Range","bytes 1-10/11"),range(0,10).removeHeader("ETag"))) {
            MockWebServer().use {s->s.start();s.enqueue(response);val g=NetworkGateway(cleaner,permit,true)
                assertThrows(IOException::class.java){runBlocking {g.mediaRanges(s.url("/clip").toString())}}
                assertEquals(1,s.requestCount)
            }
        }
    }
    @Test fun aChangedRepresentationIsRejectedAndNeverCached()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,block+7));s.enqueue(range(block,block+7,"\"two\""));s.enqueue(range(block,block+7))
            val view=NetworkGateway(cleaner,permit,true).mediaRanges(s.url("/clip").toString())
            assertThrows(IOException::class.java){runBlocking {view.read(block.toLong(),1)}}
            assertEquals(1,view.fetchedBlocks);assertEquals(1,view.read(block.toLong(),1).size);assertEquals(2,view.fetchedBlocks)
        }
    }
    @Test fun byteAndExchangeAllowancesStayBoundToTheViewAcrossSeparateReads()=runBlocking {
        MockWebServer().use {s->s.start();repeat(16){s.enqueue(range(it*block,block*20))}
            val view=NetworkGateway(cleaner,permit,true).mediaRanges(s.url("/clip").toString())
            repeat(16){assertEquals(1,view.read(it*block.toLong(),1).size)}
            assertThrows(IOException::class.java){runBlocking {view.read(block*16L,1)}}
            assertEquals(16,s.requestCount);assertEquals(4L*1024*1024,view.fetchedBytes)
        }
    }
    @Test fun parentRequestAllowanceStillAppliesToLaterDecoderReads()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,block+7));s.enqueue(range(block,block+7))
            val g=NetworkGateway(cleaner,permit,true)
            val view=g.boundedMediaRead(maxRequests=1){g.mediaRanges(s.url("/clip").toString())}
            assertThrows(IOException::class.java){runBlocking {view.read(block.toLong(),1)}}
            assertEquals(1,s.requestCount)
        }
    }
    @Test fun anOldModificationDateCanPinRangesButARecentOrInvalidDateCannot()=runBlocking {
        MockWebServer().use {s->s.start()
            repeat(2){s.enqueue(range(it*block,block+7).removeHeader("ETag").setHeader("Last-Modified","Wed, 28 Oct 2026 00:00:00 GMT").setHeader("Date","Wed, 28 Oct 2026 12:00:00 GMT"))}
            val view=NetworkGateway(cleaner,permit,true).mediaRanges(s.url("/clip").toString())
            assertEquals(1,view.read(block.toLong(),1).size);s.takeRequest();assertEquals("Wed, 28 Oct 2026 00:00:00 GMT",s.takeRequest().getHeader("If-Range"))
        }
        for(date in listOf("Wed, 28 Oct 2026 00:00:00 GMT","invalid"))MockWebServer().use {s->s.start();s.enqueue(range(0,10).removeHeader("ETag").setHeader("Last-Modified",date).setHeader("Date",date))
            assertThrows(IOException::class.java){runBlocking {NetworkGateway(cleaner,permit,true).mediaRanges(s.url("/clip").toString())}}
        }
    }
    @Test fun explicitValidatorFreeObservationsRecheckThePrefixAndRejectChangedBytes()=runBlocking {
        for(changed in listOf(false,true))MockWebServer().use {s->s.start();s.enqueue(range(0,10).removeHeader("ETag"));s.enqueue(range(0,10).removeHeader("ETag").setBody(if(changed)"yyyyyyyyyy"else "xxxxxxxxxx"))
            val view=NetworkGateway(cleaner,permit,true).mediaRanges(s.url("/clip").toString(),observationOnly=true)
            if(changed)assertThrows(IOException::class.java){runBlocking {view.verify()}}else view.verify()
            assertEquals(2,s.requestCount);assertEquals(20,view.fetchedBytes)
        }
    }
    @Test fun aConnectionModeRoundTripCannotReviveTheRangeView()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,block+7));val g=NetworkGateway(cleaner,permit,true);val view=g.mediaRanges(s.url("/clip").toString())
            g.configure(NetworkPolicy(tor=true));g.configure(NetworkPolicy())
            assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {view.read(block.toLong(),1)}};assertEquals(1,s.requestCount)
        }
    }
    @Test fun observationReadsCannotSpendTheFinalPrefixVerificationAllowance()=runBlocking {
        MockWebServer().use {s->s.start();repeat(15){s.enqueue(range(it*block,block*20).removeHeader("ETag"))};s.enqueue(range(0,block*20).removeHeader("ETag"))
            val view=NetworkGateway(cleaner,permit,true).mediaRanges(s.url("/clip").toString(),observationOnly=true)
            repeat(15){view.read(it*block.toLong(),1)}
            assertThrows(IOException::class.java){runBlocking {view.read(15L*block,1)}}
            assertEquals(15,s.requestCount);view.verify();assertEquals(16,s.requestCount);assertEquals(4L*1024*1024,view.fetchedBytes)
        }
    }
}
