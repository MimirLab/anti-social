/* anti social, GPL-3.0-or-later. Opt-in synthetic local preparation evidence. */
package app.antisocial.android

import android.content.ContentValues
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Measures the same work and records on each candidate. These timings describe
 * the test device/run, never a physical-phone performance prediction. */
@RunWith(AndroidJUnit4::class)
class LocalDiscoveryPerformanceTest {
    @Test fun measureSearchMatchingAndGroupingComponents() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialDiscoveryBenchmark")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val label=args.getString("antisocialBenchmarkLabel")?.takeIf {it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))} ?: "measurement"
        val origin=LocalPoint(40.0,-100.0)
        val description="Published information includes practical arrangements, access instructions, contact details and registration. ".repeat(12)
        val names=listOf("Saffron Room","Burger King","Copper Cup","Circuit Garden","Spoke Works","Chapter House")
        val categories=listOf("Indian restaurant","burger restaurant","cafe","makerspace","bicycle repair","bookshop")
        val businesses=(0 until 1200).map {i->LocalBusiness("benchmark-match-business-$i",if(i%6==1)names[1]else names[i%6]+" $i",categories[i%6],description,
            "${i+1} Example Street","Example City",LocalPoint(40+(i/50)*.001,-100+(i%50)*.001),checkedAt=100,
            evidence=listOf(LocalDiscoveryIdentity.evidence("accessibility","Level entrance","https://publisher.fixture.invalid/location/$i",100,LocalEvidenceKind.PUBLISHER)))}
        val titles=listOf("Spaceflight demonstration","Workshop afternoon","Children’s nature walk","Chess club evening","Live music","Repair class")
        val events=(0 until 2500).map {i->LocalEvent("benchmark-match-event-$i",titles[i%6]+" $i",LocalEventTime("2031-06-11",allDay=true),
            description=description+if(i%6==0&&(i/6)%5==0)" Private session."else " Public session.",venue="Example hall $i",point=origin,
            price=if((i/6)%2==0)"Free"else "$15",age=when(i%6){2->"Ages 1–3";1,5->"Ages 5–12";else->""})}
        val samples=mutableListOf<Map<String,Any>>();val mismatches=mutableSetOf<String>()
        fun measure(kind:String,query:String,expected:Set<String>,match:()->List<String>) {
            repeat(3){sample->
                val began=SystemClock.elapsedRealtimeNanos();val ids=match();val ms=(SystemClock.elapsedRealtimeNanos()-began)/1e6
                val actual=ids.toSet();val missing=(expected-actual).size;val unexpected=(actual-expected).size
                if(missing>0||unexpected>0)mismatches+="$kind / $query: $missing missing, $unexpected unexpected"
                samples+=mapOf("kind" to kind,"query" to query,"sample" to sample,"elapsedMs" to ms,"matches" to ids.size,"missing" to missing,"unexpected" to unexpected)
            }
        }
        for((query,type)in listOf("indian food" to 0,"burger" to 1,"coffee" to 2,"makerspace" to 3,"bicycle repair" to 4)) {
            measure("businessMatching",query,businesses.filterIndexed {i,_->i%6==type}.map {it.id}.toSet()) {
                businesses.filter {LocalBusinessRouting.matches(query,it)}.map {it.id}
            }
        }
        measure("businessMatching","\"Saffron Room 0\"",setOf(businesses.first().id)) {
            businesses.filter {LocalBusinessRouting.matches("\"Saffron Room 0\"",it)}.map {it.id}
        }
        for(query in listOf("","rocket launch","free workshops","outdoors for toddlers","rocket launch -\"private session\"")) {
            val intent=LocalEventIntent.parse(query)
            val expected=events.filterIndexed {i,_->when(query) {
                ""->true
                "rocket launch"->i%6==0
                "free workshops"->i%6 in setOf(1,5)&&(i/6)%2==0
                "outdoors for toddlers"->i%6==2
                else->i%6==0&&(i/6)%5!=0
            }}.map {it.id}.toSet()
            measure("eventMatching",query,expected){events.filter {intent.match(it).matches}.map {it.id}}
        }
        repeat(3){sample->
            val began=SystemClock.elapsedRealtimeNanos();val groups=LocalBusinessDeck.groupsForArea(businesses,origin,"US");val ms=(SystemClock.elapsedRealtimeNanos()-began)/1e6
            assertEquals(1001,groups.size);assertEquals(businesses.toSet(),groups.flatMap {it.locations}.toSet())
            samples+=mapOf("kind" to "businessGrouping","sample" to sample,"elapsedMs" to ms,"cards" to groups.size)
        }
        val dir=File(context.getExternalFilesDir("validation"),"local-discovery-performance").apply {mkdirs()}
        File(dir,"$label-matching.json").writeText(Gson().toJson(mapOf("synthetic" to true,"timingsDescribeOnlyThisDevice" to true,
            "scope" to "Matching and grouping separately; excludes database reads, network and UI", "businesses" to businesses.size,"events" to events.size,"samples" to samples)))
        assertTrue("Independently labelled populations must be retained: "+mismatches.joinToString("; "),mismatches.isEmpty())
    }
    @Test fun measureAccumulatedCachePreparation():Unit=runBlocking(Dispatchers.IO) {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialDiscoveryBenchmark")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val label=args.getString("antisocialBenchmarkLabel")?.takeIf {it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))} ?: "measurement"
        val dir=File(context.getExternalFilesDir("validation"),"local-discovery-performance").apply {mkdirs()}
        val gson=Gson();val results=mutableListOf<Map<String,Any>>()
        val area=LocalArea("benchmark-area","Example City","Example City, Test Region",LocalPoint(10.0,20.0),25,"UTC",countryCode="ZZ")
        val day=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();val at=System.currentTimeMillis()
        val description="The public workshop publishes its sessions, equipment, registration information and opening schedule. ".repeat(12)
        for((businessCount,eventCount)in listOf(20 to 40,300 to 800,1200 to 2500)) {
            val name="discovery-profile-${System.nanoTime()}.db"
            val store=LocalDiscoveryStore(context,{at},name)
            try {
                val first=LocalBusiness("benchmark-business-0","Workshop alpha","workshop",description,"1 Example Street",area.name,area.point,sourceIds=listOf("benchmark-business-0"),checkedAt=at)
                val db=store.writableDatabase
                db.beginTransaction()
                try {
                    fun put(id:String,kind:String,payload:String,point:LocalPoint,body:String) {
                        db.insertOrThrow("records",null,ContentValues().apply {put("id",id);put("kind",kind);put("payload",payload);put("lat",point.latitude);put("lon",point.longitude);put("checked",at);put("expires",at+90L*86400000)})
                        db.insertOrThrow("search",null,ContentValues().apply {put("id",id);put("kind",kind);put("body",body)})
                    }
                    repeat(businessCount){i->
                        val id="benchmark-business-$i"
                        val point=LocalPoint(area.point.latitude+(i/50)*.001,area.point.longitude+(i%50)*.001)
                        val b=if(i==0)first else first.copy(id=id,name="Workshop location $i",address="${i+1} Example Street",point=point,sourceIds=listOf(id))
                        put(id,"business",gson.toJson(b),point,b.name+" "+description)
                    }
                    repeat(eventCount){i->
                        val id="benchmark-event-$i"
                        val event=LocalEvent(id,"Workshop session $i",LocalEventTime(day,allDay=true),description=description,venue="Example hall $i",address="${i+1} Example Street",locality=area.name,point=area.point,sourceIds=listOf(id),checkedAt=at)
                        put(id,"event",gson.toJson(event),area.point,event.title+" "+description)
                    }
                    db.setTransactionSuccessful()
                }finally {db.endTransaction()}
                store.save(first)
                fun elapsed(action:()->Unit):Double {val start=SystemClock.elapsedRealtimeNanos();action();return(SystemClock.elapsedRealtimeNanos()-start)/1e6}
                val measures=mutableListOf<Map<String,Double>>()
                repeat(3){
                    measures+=linkedMapOf(
                        "businessReadMs" to elapsed {assertEquals(businessCount,store.businesses(area).size)},
                        "eventReadMs" to elapsed {assertEquals(eventCount,store.events().size)},
                        "businessReconcileMs" to elapsed {store.reconcile(area)},
                        "eventReconcileMs" to elapsed {store.reconcileEvents()},
                        "sourceOnlyIngestMs" to elapsed {assertEquals(0 to 0,store.ingest(LocalExtraction(sources=listOf(LocalPublisherSource("https://benchmark.fixture.invalid/page"))),area))},
                        "availabilityWriteMs" to elapsed {store.cache("benchmark-account","usable",60000)}
                    )
                }
                assertEquals(first,store.businesses(null,true).single())
                results+=mapOf("businesses" to businessCount,"events" to eventCount,"measurements" to measures)
            }finally {store.close();context.deleteDatabase(name)}
        }
        File(dir,"$label.json").writeText(gson.toJson(mapOf("synthetic" to true,"timingsDescribeOnlyThisDevice" to true,"cases" to results)))
    }
}
