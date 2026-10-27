package app.antisocial.android

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Dedicated databases and invented places; never edits reader records. */
@RunWith(AndroidJUnit4::class)
class LocalDiscoveryStoreTest {
    private lateinit var context:Context
    private lateinit var store:LocalDiscoveryStore
    private lateinit var name:String
    private val gson=Gson()
    private var now=java.time.Instant.parse("2031-06-10T12:00:00Z").toEpochMilli()
    private val area=LocalArea("fixture-area","Example City","Example City, Test Region",LocalPoint(10.0,20.0),25,"UTC",countryCode="ZZ")
    private val business=LocalBusiness("fixture-business","Example workshop","workshop",address="1 Example Street",locality=area.name,point=area.point,sourceIds=listOf("fixture-original-business"))
    private val event=LocalEvent("fixture-event","Example workshop session",LocalEventTime("2031-06-11",allDay=true),venue=business.name,locality=area.name,point=area.point,sourceIds=listOf("fixture-original-event"))
    @Before fun open() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        context=InstrumentationRegistry.getInstrumentation().targetContext
        name="local-store-contract-${System.nanoTime()}.db";store=LocalDiscoveryStore(context,{now},name)
    }
    @After fun close(){if(::store.isInitialized){store.close();context.deleteDatabase(name)}}
    private fun raw(id:String,kind:String,value:Any) {
        store.writableDatabase.insertOrThrow("records",null,ContentValues().apply {put("id",id);put("kind",kind);put("payload",gson.toJson(value));put("lat",area.point.latitude);put("lon",area.point.longitude);put("checked",now);put("expires",now+86400000)})
    }
    private fun payloads(table:String,kind:String?=null):Map<String,String> {
        require(table in setOf("records","copies"))
        return store.readableDatabase.rawQuery("SELECT id,payload FROM $table"+if(kind==null)""else " WHERE kind=?",kind?.let {arrayOf(it)}).use {c->buildMap {while(c.moveToNext())put(c.getString(0),c.getString(1))}}
    }
    @Test fun linkOnlyAndEmptyReadsLeaveUnrelatedRecordsAndSavedBytesUntouched() {
        raw(business.id,"business",business);raw("fixture-business-copy","business",business.copy(id="fixture-business-copy",sourceIds=listOf("fixture-other-business")))
        raw(event.id,"event",event);raw("fixture-event-copy","event",event.copy(id="fixture-event-copy",sourceIds=listOf("fixture-other-event")))
        store.save(business);store.save(event)
        val before=payloads("records");val saved=payloads("copies");val revision=store.revision.value
        val source=LocalPublisherSource("https://publisher.fixture.invalid/calendar",LocalSourceKind.CALENDAR)
        assertEquals(0 to 0,store.ingest(LocalExtraction(sources=listOf(source)),area))
        assertEquals(0 to 0,store.ingest(LocalExtraction(validEmpty=true),area))
        assertEquals(before,payloads("records"));assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        assertEquals(source,store.sources(area).single())
        assertTrue(store.reconcile(area));assertTrue(store.reconcileEvents())
        assertEquals(1,store.businesses(area).size);assertEquals(1,store.events().size)
        assertEquals(saved,payloads("copies"))
    }
    @Test fun libraryCoffeeSearchRetainsCafesAndRetailersWithoutRewritingTheirSnapshots() {
        val cafe=business.copy(id="fixture-cafe",name="Copper Cup",category="cafe")
        val retailer=business.copy(id="fixture-beans",name="Bean Counter",category="coffee store")
        val unrelated=business.copy(id="fixture-hardware",name="Copper Hardware",category="hardware store")
        listOf(cafe,retailer,unrelated).forEach(store::save);val saved=payloads("copies")
        assertEquals(setOf(cafe.id,retailer.id),store.savedDiscoveries("coffee").map {it.id}.toSet())
        assertEquals(setOf(retailer.id),store.savedDiscoveries("coffee -cafe").map {it.id}.toSet())
        assertTrue(store.savedDiscoveries("\"coffee\"").none {it.id==cafe.id})
        assertEquals(saved,payloads("copies"))
    }
    @Test fun businessFactsDoNotScanOrRewriteUnrelatedEventOccurrences() {
        raw(event.id,"event",event);raw("fixture-event-copy","event",event.copy(id="fixture-event-copy",sourceIds=listOf("fixture-other-event")))
        val before=payloads("records","event")
        assertEquals(1 to 0,store.ingest(LocalExtraction(businesses=listOf(business)),area))
        assertEquals(before,payloads("records","event"));assertEquals(1,store.businesses(area).size)
        assertTrue(store.reconcileEvents());assertEquals(1,store.events().size)
    }
    @Test fun eventFactsDoNotRewriteUnrelatedBusinessLocations() {
        raw(business.id,"business",business);raw("fixture-business-copy","business",business.copy(id="fixture-business-copy",sourceIds=listOf("fixture-other-business")))
        val before=payloads("records","business")
        assertEquals(0 to 1,store.ingest(LocalExtraction(events=listOf(event)),area))
        assertEquals(before,payloads("records","business"));assertEquals(1,store.events().size)
        assertTrue(store.reconcile(area));assertEquals(1,store.businesses(area).size)
    }
    @Test fun expiredPublisherRoutesCanBeRediscoveredAndTheQueueStaysBounded() {
        val source=LocalPublisherSource("https://publisher.fixture.invalid/calendar",LocalSourceKind.CALENDAR)
        store.offerSources(area,listOf(source));now+=31L*86400000
        assertTrue(store.sources(area).isEmpty())
        store.offerSources(area,listOf(source));assertEquals(source,store.sources(area).single())
        store.offerSources(area,(1..780).map {LocalPublisherSource("https://publisher.fixture.invalid/$it")})
        assertEquals(768,store.sources(area).size)
        assertTrue(store.sources(area).any {it.url.endsWith("/780")})
    }
    @Test fun accountMetadataKeepsItsOwnLimitsAndOrdinaryCleanupProtectsSaves() {
        store.ingest(LocalExtraction(businesses=listOf(business)),area);store.save(business)
        val records=payloads("records");val saved=payloads("copies");val revision=store.revision.value
        repeat(300){store.cache("account-$it","usable",60000)}
        val large="x".repeat(256*1024);repeat(40){store.cache("large-$it",large,60000)}
        val limits=store.readableDatabase.rawQuery("SELECT COUNT(*),SUM(length(CAST(payload AS BLOB))) FROM cache",null).use {it.moveToFirst();it.getInt(0)to it.getLong(1)}
        assertTrue(limits.first<=256);assertTrue(limits.second<=8L*1024*1024)
        assertEquals(records,payloads("records"));assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        now+=100L*86400000
        assertTrue(store.businesses(area).isEmpty());assertNull(store.business(business.id));store.cleanup()
        assertTrue(payloads("records").isEmpty());assertEquals(saved,payloads("copies"));assertTrue(store.revision.value>revision)
    }
    @Test fun anExpiredAliasCannotReviveOldContactFactsOnRefresh() {
        val old=business.copy(phones=listOf("+1 202 555 0101"))
        store.ingest(LocalExtraction(businesses=listOf(old)),area);store.save(old)
        val saved=payloads("copies")
        now+=31L*86400000
        // No cleanup sweep is needed to enforce expiry at the read boundary.
        assertNull(store.business(old.id))
        val fresh=business.copy(id="fixture-fresh-business",checkedAt=now)
        assertEquals(1 to 0,store.ingest(LocalExtraction(businesses=listOf(fresh)),area))
        val current=store.businesses(area).single()
        assertEquals(fresh.id,current.id);assertTrue(current.phones.isEmpty());assertEquals(saved,payloads("copies"))
    }
    @Test fun unchangedReconciliationStillNoticesSameLengthEditsFromAnotherConnection() {
        val secondEvent=event.copy(id="fixture-event-copy",title="Another workshop session",sourceIds=listOf("fixture-other-event"))
        val secondBusiness=business.copy(id="fixture-business-copy",name="Another workshop",address="9 Other Street",sourceIds=listOf("fixture-other-business"))
        raw(event.id,"event",event);raw(secondEvent.id,"event",secondEvent)
        raw(business.id,"business",business);raw(secondBusiness.id,"business",secondBusiness)
        store.save(event);store.save(business);val saved=payloads("copies");val revision=store.revision.value
        assertFalse(store.reconcileEvents());assertFalse(store.reconcile(area))
        assertFalse(store.reconcileEvents());assertFalse(store.reconcile(area))
        // No revision, row count or checked-time update in this store. The
        // replacement has exactly the same serialized length as its predecessor.
        LocalDiscoveryStore(context,{now},name).use {writer->
            val changedEvent=event.copy(id=secondEvent.id,sourceIds=secondEvent.sourceIds)
            val changedBusiness=business.copy(id=secondBusiness.id,sourceIds=secondBusiness.sourceIds)
            fun edit(id:String,value:Any) {
                val json=gson.toJson(value);val old=payloads("records").getValue(id)
                // Padding a JSON document is valid and equalizes byte length.
                val size=maxOf(json.length,old.length)
                writer.writableDatabase.execSQL("UPDATE records SET payload=? WHERE id=?",arrayOf(old.padEnd(size),id))
                assertFalse(if(value is LocalEvent)store.reconcileEvents()else store.reconcile(area))
                writer.writableDatabase.execSQL("UPDATE records SET payload=? WHERE id=?",arrayOf(json.padEnd(size),id))
                assertEquals(size,payloads("records").getValue(id).length)
            }
            edit(secondEvent.id,changedEvent);edit(secondBusiness.id,changedBusiness)
        }
        assertTrue(store.reconcileEvents());assertTrue(store.reconcile(area))
        assertEquals(event.id,store.events().single().id);assertEquals(business.id,store.businesses(area).single().id)
        assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        assertFalse(store.reconcileEvents());assertFalse(store.reconcile(area))
    }
    @Test fun reconciliationNoticesNewlyActiveRowsAndDoesNotRenewEventExpiry() {
        raw(event.id,"event",event)
        val other=event.copy(id="fixture-event-copy",sourceIds=listOf("fixture-other-event"))
        raw(other.id,"event",other)
        val db=store.writableDatabase
        db.execSQL("UPDATE records SET expires=? WHERE id=?",arrayOf(now,other.id))
        assertFalse(store.reconcileEvents())
        db.execSQL("UPDATE records SET expires=? WHERE id=?",arrayOf(now+86400000,other.id))
        assertTrue(store.reconcileEvents());assertFalse(store.reconcileEvents())
        now+=86400001;assertTrue(store.events().isEmpty());assertFalse(store.reconcileEvents())
    }
    @Test fun businessReconciliationRetainsTheCompleteGeographicScope() {
        val regional=business.copy(point=null,locality="Other Example City",address="1 Other Example City Street")
        raw(regional.id,"business",regional)
        raw("fixture-other-business","business",regional.copy(id="fixture-other-business",sourceIds=listOf("fixture-other-business")))
        assertTrue(store.businesses(area).isEmpty());assertFalse(store.reconcile(area))
        val otherArea=area.copy(name="Other Example City",label="Other Example City, Test Region")
        // Same bounding-box rows, radius and center; only the locality evidence
        // changes which coordinate-less records can actually be admitted.
        assertEquals(2,store.businesses(otherArea).size)
        assertTrue(store.reconcile(otherArea));assertEquals(1,store.businesses(otherArea).size)
        assertFalse(store.reconcile(otherArea))
    }
    @Test fun aFailedOrOuterRolledBackRepairCannotCertifyUncommittedRecords() {
        raw(event.id,"event",event);store.save(event);val saved=payloads("copies")
        assertFalse(store.reconcileEvents())
        val other=event.copy(id="fixture-event-copy",sourceIds=listOf("fixture-other-event"))
        raw(other.id,"event",other)
        val db=store.writableDatabase
        db.execSQL("CREATE TRIGGER fixture_abort_repair BEFORE DELETE ON records BEGIN SELECT RAISE(ABORT,'fixture rollback');END")
        try {store.reconcileEvents();fail("The repair should roll back")}
        catch(_:android.database.sqlite.SQLiteException){}
        db.execSQL("DROP TRIGGER fixture_abort_repair")
        assertEquals(2,store.events().size)
        db.beginTransaction()
        try {assertTrue(store.reconcileEvents());assertEquals(1,store.events().size)}finally {db.endTransaction()}
        assertEquals(2,store.events().size)
        assertTrue(store.reconcileEvents());assertFalse(store.reconcileEvents())
        assertEquals(event.id,store.events().single().id);assertEquals(saved,payloads("copies"))
    }
    @Test fun aRevokedBatchRollsBackFactsAndPublisherHintsBeforeCommit() {
        store.save(business);val saved=payloads("copies");val revision=store.revision.value
        val source=LocalPublisherSource("https://publisher.fixture.invalid/calendar")
        var checks=0
        assertEquals(0 to 0,store.ingest(LocalExtraction(businesses=listOf(business),events=listOf(event),sources=listOf(source)),area){++checks<3})
        assertTrue(store.businesses(area).isEmpty());assertTrue(store.events().isEmpty());assertTrue(store.sources(area).isEmpty())
        assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        checks=0;store.offerSources(area,listOf(source)){++checks<2}
        assertTrue(store.sources(area).isEmpty());assertEquals(saved,payloads("copies"))
        val outcome=LocalRouteYield(checkedAt=now,nextAt=now+60000)
        checks=0;store.sourceResult(area,source,outcome){++checks<2}
        assertTrue(store.sources(area).isEmpty());assertNull(store.cache(LocalBusinessEnrichment.key(source)))
        assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        store.sourceResult(area,source,outcome){true}
        assertEquals(source,store.sources(area).single());assertNotNull(store.cache(LocalBusinessEnrichment.key(source)))
        assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        val account=source.copy(url="https://publisher.fixture.invalid/profile")
        checks=0;store.sourceResult(area,account,projectionKey="fixture-profile-projection"){++checks<2}
        assertNull(store.cache("fixture-profile-projection"));assertEquals(listOf(source),store.sources(area))
        store.sourceResult(area,account,projectionKey="fixture-profile-projection"){true}
        assertEquals("read",store.cache("fixture-profile-projection"));assertTrue(account in store.sources(area))
        assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
        checks=0;store.cache("fixture-query-completed","ok",60000){++checks<2}
        assertNull(store.cache("fixture-query-completed"))
        store.cache("fixture-query-completed","ok",60000){true}
        assertEquals("ok",store.cache("fixture-query-completed"))
        assertEquals(saved,payloads("copies"));assertEquals(revision,store.revision.value)
    }
}
