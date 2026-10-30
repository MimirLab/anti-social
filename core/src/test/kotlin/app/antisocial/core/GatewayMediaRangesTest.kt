package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
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
    @Test fun anUnusedPreparedPrefixIsClaimedOnceAndItsRetryStillRevalidates()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));s.enqueue(range(block,3*block));s.enqueue(range(0,3*block))
            val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val context=kotlinx.coroutines.currentCoroutineContext();val initial=g.prepareMediaRanges(url)
                val view=g.claimPreparedMediaRanges(initial,url)
                assertEquals(1,view.fetchedBlocks);assertEquals(1,s.requestCount)
                assertThrows(IllegalStateException::class.java){runBlocking(context){g.claimPreparedMediaRanges(initial,url)}}
                assertEquals(1,view.read(block.toLong(),1).size)
                val retry=g.mediaRanges(url,previous=view)
                assertNotSame(view,retry);assertEquals(2,retry.cachedBlocks);assertEquals(3,s.requestCount)
            }
        }
    }
    @Test fun preparationRequiresABoundedParentAndClosingWithdrawsItsClaim()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            assertThrows(IllegalStateException::class.java){runBlocking {g.prepareMediaRanges(url)}}
            assertEquals(0,s.requestCount)
            g.boundedVideoComparison {
                val context=kotlinx.coroutines.currentCoroutineContext();val initial=g.prepareMediaRanges(url);initial.close()
                assertThrows(IllegalStateException::class.java){runBlocking(context){g.claimPreparedMediaRanges(initial,url)}}
            };assertEquals(1,s.requestCount)
        }
    }
    @Test fun preparationCannotTransferToAnotherUrlSourceObservationModeOrParent()=runBlocking {
        MockWebServer().use {s->s.start();repeat(4){s.enqueue(range(0,3*block))};val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val context=kotlinx.coroutines.currentCoroutineContext()
                for(mode in 0..3) {
                    val initial=g.prepareMediaRanges(url)
                    assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking(context){when(mode) {
                        0->g.claimPreparedMediaRanges(initial,url+"?other=1")
                        1->g.claimPreparedMediaRanges(initial,url,"https://other.fixture.invalid/post")
                        2->g.claimPreparedMediaRanges(initial,url,observationOnly=true)
                        else->g.boundedVideoComparison {g.claimPreparedMediaRanges(initial,url)}
                    }}}
                    assertThrows(IllegalStateException::class.java){runBlocking(context){g.claimPreparedMediaRanges(initial,url)}}
                }
            };assertEquals(4,s.requestCount)
        }
    }
    @Test fun preparedReadsKeepTheirOriginalRequestAndByteAllowance()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedMediaRead(maxBytes=block.toLong(),maxRequests=1) {
                val initial=g.prepareMediaRanges(url);val view=g.claimPreparedMediaRanges(initial,url)
                val context=kotlinx.coroutines.currentCoroutineContext()
                assertThrows(IOException::class.java){runBlocking(context){view.read(block.toLong(),1)}}
            };assertEquals(1,s.requestCount)
        }
    }
    @Test fun aConnectionRoundTripWithdrawsAnUnusedPreparedPrefix()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val context=kotlinx.coroutines.currentCoroutineContext();val initial=g.prepareMediaRanges(url)
                g.configure(NetworkPolicy(tor=true,ready=false));g.configure(NetworkPolicy())
                assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking(context){g.claimPreparedMediaRanges(initial,url)}}
            };assertEquals(1,s.requestCount)
        }
    }
    @Test fun aCancelledConsumerCannotClaimThePreparedPrefix()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val initial=g.prepareMediaRanges(url);var entered=false;var accepted=false
                try {kotlinx.coroutines.coroutineScope {
                    entered=true;kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                    g.claimPreparedMediaRanges(initial,url);accepted=true
                }}catch(_:java.util.concurrent.CancellationException){}
                finally {initial.close()}
                assertTrue(entered);assertFalse(accepted)
            };assertEquals(1,s.requestCount)
        }
    }
    @Test fun preparedValidatorFreeReadsStillVerifyTheirPrefixBeforeAcceptance()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block).removeHeader("ETag"));s.enqueue(range(0,3*block).removeHeader("ETag").setBody("y".repeat(block)))
            val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val context=kotlinx.coroutines.currentCoroutineContext();val initial=g.prepareMediaRanges(url,observationOnly=true)
                val view=g.claimPreparedMediaRanges(initial,url,observationOnly=true)
                assertThrows(IOException::class.java){runBlocking(context){view.verify()}}
            };assertEquals(2,s.requestCount)
        }
    }
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
            assertFalse(view.reusable)
            assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {view.read(0,1)}}
            assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {view.verify()}}
            assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {view.read(block.toLong(),1)}};assertEquals(1,s.requestCount)
        }
    }
    @Test fun aCompletedBlockRemainsAvailableAfterAnInterruptedReadWithoutRenewingTheParentAllowance()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,block+7))
            val g=NetworkGateway(cleaner,permit,true)
            val view=g.boundedMediaRead(maxRequests=1){g.mediaRanges(s.url("/clip").toString())}
            kotlinx.coroutines.coroutineScope {
                val read=launch {assertEquals(1,view.read(0,1).size);kotlinx.coroutines.awaitCancellation()}
                kotlinx.coroutines.yield();read.cancel();read.join()
            }
            assertTrue(view.reusable);assertEquals(1,view.read(0,1).size)
            assertThrows(IOException::class.java){runBlocking {view.read(block.toLong(),1)}}
            assertEquals(1,s.requestCount);assertEquals(1,view.fetchedBlocks);assertEquals(block.toLong(),view.fetchedBytes)
        }
    }
    @Test fun withdrawalBlocksCachedAndEmptyReadsAndVerificationWithoutFetching()=runBlocking {
        var active=true;var calls=0
        val view=GatewayMediaRanges(8,4,byteArrayOf(1,2,3,4),{_,_->calls++;byteArrayOf(5,6,7,8)},active={active})
        assertTrue(view.reusable);assertArrayEquals(byteArrayOf(1,2),view.read(0,2))
        active=false
        assertFalse(view.reusable)
        for(read in listOf<suspend ()->Unit>({view.read(0,1);Unit},{view.read(4,1);Unit},{view.read(0,0);Unit},{view.read(8,1);Unit},{view.verify()})) {
            assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {read()}}
        }
        assertEquals(0,calls)
    }
    @Test fun unrelatedTorReadinessDoesNotRetireAnOrdinaryDirectRangeView()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,10));val g=NetworkGateway(cleaner,permit,true)
            val view=g.mediaRanges(s.url("/clip").toString());g.configureTor(NetworkPolicy(tor=true,ready=false))
            assertTrue(view.reusable);assertEquals(1,view.read(0,1).size);view.verify();assertEquals(1,s.requestCount)
        }
    }
    @Test fun retryRevalidatesThePrefixAndReusesOnlyCompletedBlocksWithinTheSameParent()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));s.enqueue(range(block,3*block));s.enqueue(range(0,3*block));s.enqueue(range(2*block,3*block))
            val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedMediaRead(maxRequests=4) {
                val budgetContext=kotlinx.coroutines.currentCoroutineContext()
                val first=g.mediaRanges(url);first.read(block.toLong(),1)
                val next=g.mediaRanges(url,previous=first)
                assertNotSame(first,next);assertEquals(2,next.cachedBlocks);assertEquals(1,next.fetchedBlocks)
                assertEquals(block.toLong(),next.fetchedBytes);next.read(block.toLong(),1)
                assertEquals(3,s.requestCount);next.read(2L*block,1);assertEquals(4,s.requestCount)
                assertThrows(IOException::class.java){runBlocking(budgetContext){g.mediaRanges(url,previous=next)}}
                assertEquals(4,s.requestCount)
            }
        }
    }
    @Test fun aChangedPrefixOrRepresentationStartsFreshRatherThanBorrowingOldBlocks()=runBlocking {
        for(change in listOf("prefix","validator","length","destination"))MockWebServer().use {s->s.start()
            s.enqueue(range(0,3*block));s.enqueue(range(block,3*block))
            val total=if(change=="length")4*block else 3*block
            if(change=="destination")s.enqueue(MockResponse().setResponseCode(302).setHeader("Location",s.url("/other")))
            s.enqueue(range(0,total,if(change=="validator")"\"two\"" else "\"one\"").apply {if(change=="prefix")setBody("y".repeat(block))})
            val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val old=g.mediaRanges(url);old.read(block.toLong(),1)
                val fresh=g.mediaRanges(url,previous=old)
                assertEquals(change,1,fresh.cachedBlocks);assertEquals(total.toLong(),fresh.length)
                assertEquals(if(change=="destination")4 else 3,s.requestCount)
            }
        }
    }
    @Test fun retryCannotTransferBlocksToADifferentSourceModeUrlOrParent()=runBlocking {
        MockWebServer().use {s->s.start();s.enqueue(range(0,3*block));val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            val old=g.boundedVideoComparison {
                val budgetContext=kotlinx.coroutines.currentCoroutineContext()
                val view=g.mediaRanges(url)
                for(retry in listOf<suspend ()->Unit>(
                    {g.mediaRanges(url+"?other=1",previous=view);Unit},
                    {g.mediaRanges(url,"https://other.fixture.invalid/",previous=view);Unit},
                    {g.mediaRanges(url,observationOnly=true,previous=view);Unit},
                    {g.boundedVideoComparison {g.mediaRanges(url,previous=view)};Unit})) {
                    assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking(budgetContext){retry()}}
                }
                view
            }
            assertThrows(java.util.concurrent.CancellationException::class.java){runBlocking {g.mediaRanges(url,previous=old)}}
            assertEquals(1,s.requestCount)
        }
    }
    @Test fun validatorFreeRetryRetainsItsFinalVerificationReserveAndRejectsChangedPrefix()=runBlocking {
        MockWebServer().use {s->s.start();repeat(15){s.enqueue(range(it*block,20*block).removeHeader("ETag"))}
            s.enqueue(range(0,20*block).removeHeader("ETag"));s.enqueue(range(0,20*block).removeHeader("ETag").setBody("y".repeat(block)))
            val g=NetworkGateway(cleaner,permit,true);val url=s.url("/clip").toString()
            g.boundedVideoComparison {
                val old=g.mediaRanges(url,observationOnly=true);repeat(15){old.read(it*block.toLong(),1)}
                val retry=g.mediaRanges(url,observationOnly=true,previous=old)
                assertEquals(15,retry.cachedBlocks);assertEquals(1,retry.fetchedBlocks)
                assertThrows(IOException::class.java){runBlocking {retry.read(15L*block,1)}}
                assertEquals(16,s.requestCount)
                assertThrows(IOException::class.java){runBlocking {retry.verify()}}
                assertEquals(17,s.requestCount)
            }
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
