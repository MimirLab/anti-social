/* AntiSocial, GPL-3.0-or-later. Bounded local identity graph; saved snapshots are independently protected. */
package app.antisocial.android

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.security.MessageDigest

internal data class LocalSavedDiscovery(val id:String,val savedAt:Long,val business:LocalBusiness?=null,val event:LocalEvent?=null)

internal class LocalDiscoveryStore(context:Context,private val now:()->Long=System::currentTimeMillis,databaseName:String="local-discovery-v1.db"):SQLiteOpenHelper(context,databaseName,null,9) {
    private val gson=Gson()
    private var reconciledEvents:ByteArray?=null
    private var reconciledBusinesses:Pair<LocalArea,ByteArray>?=null
    val revision=MutableStateFlow(0L)
    override fun onConfigure(db:SQLiteDatabase){db.enableWriteAheadLogging();db.rawQuery("PRAGMA journal_size_limit=1048576",null).use {it.moveToFirst()}}
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,payload TEXT NOT NULL,lat REAL,lon REAL,checked INTEGER NOT NULL,expires INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX region ON records(kind,lat,lon)")
        db.execSQL("CREATE TABLE aliases(alias TEXT PRIMARY KEY,owner TEXT NOT NULL)")
        db.execSQL("CREATE TABLE copies(id TEXT PRIMARY KEY,kind TEXT NOT NULL,payload TEXT NOT NULL,saved INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sources(url TEXT NOT NULL,area TEXT NOT NULL,payload TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(url,area))")
        db.execSQL("CREATE TABLE cache(id TEXT PRIMARY KEY,payload TEXT NOT NULL,expires INTEGER NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE search USING fts4(id,kind,body,tokenize=unicode61)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        if(oldVersion<2)db.execSQL("ALTER TABLE sources ADD COLUMN expires INTEGER NOT NULL DEFAULT 0")
        if(oldVersion<9) {
            // Re-read formerly successful pages with the corrected ownership
            // and business/social/visit/calendar/opportunity projections on the next explicit search. Keep failure backoffs.
            val updates=mutableListOf<Triple<String,String,String>>()
            db.rawQuery("SELECT url,area,payload FROM sources",null).use {c->while(c.moveToNext()) {
                val source=runCatching {gson.fromJson(c.getString(2),LocalPublisherSource::class.java)}.getOrNull() ?: continue
                if(source.failures==0)updates+=Triple(c.getString(0),c.getString(1),gson.toJson(source.copy(nextCheckAt=0)))
            }}
            updates.forEach {(url,area,payload)->db.update("sources",ContentValues().apply {put("payload",payload)},"url=? AND area=?",arrayOf(url,area))}
        }
    }
    private fun rows(sql:String,args:Array<String> = emptyArray()):List<String> = readableDatabase.rawQuery(sql,args).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    private data class Selection(val tail:String,val args:Array<String>)
    private fun eventSelection(at:Long)=Selection(" FROM records WHERE kind='event' AND expires>? ORDER BY checked DESC LIMIT 5000",arrayOf(at.toString()))
    private fun businessSelection(area:LocalArea?,at:Long):Selection {
        val prefix=" FROM records WHERE kind='business' AND expires>?"
        if(area==null)return Selection(prefix+" ORDER BY checked DESC LIMIT 2000",arrayOf(at.toString()))
        val lat=area.radiusKm/110.0+.01;val lon=lat/kotlin.math.cos(Math.toRadians(area.point.latitude)).coerceAtLeast(.1)
        // Longitude wrap is filtered by haversine after decoding.
        val wrap=area.point.longitude-lon< -180||area.point.longitude+lon>180
        return Selection(prefix+" AND (lat IS NULL OR lat BETWEEN ? AND ?)"+(if(wrap)""else " AND (lon IS NULL OR lon BETWEEN ? AND ?)")+" ORDER BY checked DESC LIMIT 2000",
            (listOf(at.toString(),(area.point.latitude-lat).toString(),(area.point.latitude+lat).toString())+if(wrap)emptyList()else listOf((area.point.longitude-lon).toString(),(area.point.longitude+lon).toString())).toTypedArray())
    }
    /** Only a small receipt is retained, never a second cache of decoded records.
     * Hash the exact bounded input, including same-length edits and expiry, in
     * the reconciliation transaction. Revision counters alone miss other writers.
     * Framing keeps arbitrary JSON/identifier boundaries unambiguous. */
    private data class ReconciliationInput(val signature:ByteArray,val payloads:List<String>)
    private fun reconciliationInput(db:SQLiteDatabase,selection:Selection,saved:Set<String>,retainPayloads:Boolean=true):ReconciliationInput {
        val digest=MessageDigest.getInstance("SHA-256")
        val payloads=mutableListOf<String>()
        fun field(value:String) {
            val bytes=value.toByteArray(Charsets.UTF_8);val n=bytes.size
            digest.update(byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),(n ushr 8).toByte(),n.toByte()));digest.update(bytes)
        }
        field(saved.size.toString());saved.sorted().forEach(::field)
        db.rawQuery("SELECT id,payload,expires"+selection.tail,selection.args).use {c->while(c.moveToNext()) {
            val payload=c.getString(1)
            field(c.getString(0));field(payload);field(c.getLong(2).toString())
            if(retainPayloads)payloads+=payload
        }}
        return ReconciliationInput(digest.digest(),payloads)
    }
    private fun businessValues(values:List<String>,area:LocalArea?)=values.mapNotNull {runCatching {LocalBusinessOwnership.repairLegacy(gson.fromJson(it,LocalBusiness::class.java)).let {b->b.copy(websites=b.websites.flatMap {url->LocalDiscoveryIdentity.urls(url)}.distinct().take(12))}}.getOrNull()}.filter {area==null||LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)}
    private fun eventValues(values:List<String>)=values.mapNotNull {runCatching {gson.fromJson(it,LocalEvent::class.java)}.getOrNull()}
    @Synchronized fun cache(key:String):String?=rows("SELECT payload FROM cache WHERE id=? AND expires>?",arrayOf(key,now().toString())).firstOrNull()
    @Synchronized fun cache(key:String,value:String,ttl:Long) {require(value.length<=256*1024);writableDatabase.insertWithOnConflict("cache",null,ContentValues().apply {put("id",key);put("payload",value);put("expires",now()+ttl)},SQLiteDatabase.CONFLICT_REPLACE);trimCache()}
    /** A completion receipt must not outlive rejection of its query's facts. */
    @Synchronized fun cache(key:String,value:String,ttl:Long,admit:()->Boolean) {
        if(!admit())return
        val db=writableDatabase;db.beginTransaction()
        try {cache(key,value,ttl);if(admit())db.setTransactionSuccessful()}finally {db.endTransaction()}
    }
    @Synchronized fun businesses(area:LocalArea?,saved:Boolean=false):List<LocalBusiness> {
        val values=if(saved)rows("SELECT payload FROM copies WHERE kind='business' ORDER BY saved DESC LIMIT 2000")else businessSelection(area,now()).let {rows("SELECT payload"+it.tail,it.args)}
        return businessValues(values,area)
    }
    @Synchronized fun events(saved:Boolean=false):List<LocalEvent> = eventValues(if(saved)rows("SELECT payload FROM copies WHERE kind='event' ORDER BY saved DESC LIMIT 2000")else eventSelection(now()).let {rows("SELECT payload"+it.tail,it.args)})
    @Synchronized fun announcements(area:LocalArea):List<LocalAnnouncementCandidate> = rows("SELECT payload FROM records WHERE kind='announcement' AND expires>? ORDER BY checked DESC LIMIT 200",arrayOf(now().toString()))
        .mapNotNull {runCatching {gson.fromJson(it,LocalAnnouncementCandidate::class.java)}.getOrNull()}
        .filter {candidate->business(candidate.publisherId)?.let {LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)}==true}
    @Synchronized fun save(business:LocalBusiness)=copy(business.id,"business",gson.toJson(business))
    @Synchronized fun save(event:LocalEvent)=copy(event.id,"event",gson.toJson(event))
    private fun copy(id:String,kind:String,json:String) {
        require(json.length<=256*1024)
        require(savedIds().size<2000||id in savedIds()){"Your local discovery library is full. Remove a saved item before saving another."}
        writableDatabase.insertWithOnConflict("copies",null,ContentValues().apply {put("id",id);put("kind",kind);put("payload",json);put("saved",now())},SQLiteDatabase.CONFLICT_REPLACE);changed()
    }
    @Synchronized fun removeSaved(id:String){writableDatabase.delete("copies","id=?",arrayOf(id));changed()}
    /** Library reads captured copies directly. Never joins live records, checks
     * accounts, reconciles discoveries or starts a menu/source request. */
    @Synchronized fun savedDiscoveries(query:String=""):List<LocalSavedDiscovery> =
        readableDatabase.rawQuery("SELECT id,kind,payload,saved FROM copies ORDER BY saved DESC LIMIT 2000",null).use {c->val eventSearch=LocalDiscoveryFilter.prepare(query);buildList {
            while(c.moveToNext())runCatching {
                val id=c.getString(0);val saved=c.getLong(3)
                if(c.getString(1)=="business") {
                    val b=LocalBusinessOwnership.repairLegacy(gson.fromJson(c.getString(2),LocalBusiness::class.java))
                    val body=LocalBusinessRouting.text(b)+" "+(b.phones+b.emails+b.websites+b.socials).joinToString(" ")+" "+b.menus.orEmpty().joinToString(" "){m->m.title+" "+m.text+" "+m.items.joinToString(" "){it.name+" "+it.description}}
                    if(LocalBusinessRouting.matches(query,b))add(LocalSavedDiscovery(id,saved,business=b))
                }else if(c.getString(1)=="event") {
                    val v=gson.fromJson(c.getString(2),LocalEvent::class.java)
                    val body=listOf(v.title,v.description,v.venue,v.address,v.locality,v.organizer,v.price,v.age,v.start.value).joinToString(" ")
                    if(eventSearch.matches(body))add(LocalSavedDiscovery(id,saved,event=v))
                }
            }
        }}
    @Synchronized fun savedIds()=rows("SELECT id FROM copies").toSet()
    @Synchronized fun sources(area:LocalArea):List<LocalPublisherSource> = rows("SELECT payload FROM sources WHERE area=? AND expires>? LIMIT 768",arrayOf(area.cacheKey,now().toString())).mapNotNull {runCatching {gson.fromJson(it,LocalPublisherSource::class.java)}.getOrNull()}
    @Synchronized fun offerSources(area:LocalArea,sources:List<LocalPublisherSource>,admit:()->Boolean={true}) {
        if(sources.isEmpty()||!admit())return
        val db=writableDatabase;db.beginTransaction()
        try {sources.forEach {offerSource(area,it)};if(admit())db.setTransactionSuccessful()}finally {db.endTransaction()}
    }
    @Synchronized fun offerSource(area:LocalArea,source:LocalPublisherSource) {
        val old=rows("SELECT payload FROM sources WHERE url=? AND area=? AND expires>?",arrayOf(source.url,area.cacheKey,now().toString())).firstOrNull()?.let {runCatching {gson.fromJson(it,LocalPublisherSource::class.java)}.getOrNull()}
        if(old==null)source(area,source)else if(source.businessId!=null&&(old.businessId==null||business(old.businessId)==null||source.kind==LocalSourceKind.MENU&&(old.kind!=LocalSourceKind.MENU||source.menuBranch==true&&old.menuBranch!=true)))
            source(area,old.copy(businessId=source.businessId,kind=source.kind,label=source.label.ifBlank {old.label},discoveredFrom=source.discoveredFrom ?: old.discoveredFrom,menuBranch=source.menuBranch ?: old.menuBranch,nextCheckAt=if(source.kind!=old.kind&&old.failures==0)0 else old.nextCheckAt))
    }
    /** Query-owned route outcomes recheck admission after acquiring this monitor
     * and before committing either the hint or its scheduling observation. */
    @Synchronized fun sourceResult(area:LocalArea,source:LocalPublisherSource,observed:LocalRouteYield?=null,saveHint:Boolean=true,projectionKey:String?=null,admit:()->Boolean) {
        if(!admit())return
        val db=writableDatabase;db.beginTransaction()
        try {
            if(observed!=null)cache(LocalBusinessEnrichment.key(source),gson.toJson(observed),14L*86400000)
            if(saveHint)source(area,source)
            if(projectionKey!=null)cache(projectionKey,"read",6*3600000)
            if(admit())db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    @Synchronized fun source(area:LocalArea,source:LocalPublisherSource) {
        val payload=gson.toJson(source);require(payload.length<=16*1024)
        writableDatabase.insertWithOnConflict("sources",null,ContentValues().apply {put("url",source.url);put("area",area.cacheKey);put("payload",payload);put("expires",now()+30L*86400000)},SQLiteDatabase.CONFLICT_REPLACE)
        trimSources()
    }
    @Synchronized fun business(id:String?):LocalBusiness?=id?.let {key->
        val owner=rows("SELECT owner FROM aliases WHERE alias=?",arrayOf(key)).firstOrNull() ?: key
        rows("SELECT payload FROM records WHERE id=? AND kind='business' AND expires>?",arrayOf(owner,now().toString())).firstOrNull()?.let {v->gson.fromJson(v,LocalBusiness::class.java)}
    }
    /** Reconcile cached occurrences too; saved snapshot bytes remain untouched. */
    @Synchronized fun reconcileEvents():Boolean {
        val db=writableDatabase;val outer=db.inTransaction();val selection=eventSelection(now())
        var repaired=false;var receipt:ByteArray?=null
        db.beginTransactionNonExclusive()
        try {
            val saved=savedIds();val input=reconciliationInput(db,selection,saved)
            if(!input.signature.contentEquals(reconciledEvents)) {
                val original=eventValues(input.payloads).sortedBy {if(it.id in saved)0 else 1}
                val merged=LocalDiscoveryMerge.events(original)
                if(merged.size!=original.size) {
                    val retained=merged.mapTo(mutableSetOf()){it.id};val expiry=mutableMapOf<String,Long>()
                    db.rawQuery("SELECT id,expires FROM records WHERE kind='event'",null).use {c->while(c.moveToNext())expiry[c.getString(0)]=c.getLong(1)}
                    original.filter {it.id !in retained}.forEach {old->db.delete("records","id=?",arrayOf(old.id));db.delete("search","id=?",arrayOf(old.id))}
                    merged.forEach {event->
                        // Reconciliation is not a refresh and cannot renew stale data.
                        put(event.id,"event",gson.toJson(event),event.point,event.checkedAt,expiry[event.id] ?: now(),event.title+" "+event.description+" "+event.venue+" "+event.locality)
                    }
                    repaired=true
                }
            }
            receipt=if(repaired)reconciliationInput(db,selection,saved,false).signature else input.signature
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        // A nested caller may still roll back its outer transaction.
        if(!outer)reconciledEvents=receipt
        return repaired
    }
    /** Repair earlier ordinary records without rewriting protected saved copies. */
    @Synchronized fun reconcile(area:LocalArea):Boolean {
        val db=writableDatabase;val outer=db.inTransaction();val selection=businessSelection(area,now())
        var repaired=false;var receipt:ByteArray?=null
        db.beginTransactionNonExclusive()
        try {
            val saved=savedIds();val input=reconciliationInput(db,selection,saved)
            if(reconciledBusinesses?.first!=area||!input.signature.contentEquals(reconciledBusinesses?.second)) {
                val original=businessValues(input.payloads,area).sortedBy {if(it.id in saved)0 else 1}
                val merged=LocalDiscoveryMerge.businesses(original)
                if(merged.size!=original.size) {
                    val retained=merged.associateBy {it.id};val prepared=merged.associate {it.id to LocalBusinessIdentity.prepare(it)}
                    val winners=original.associate {old->
                        val winner=retained[old.id]?.id ?: LocalBusinessIdentity.prepare(old).let {identity->merged.firstOrNull {LocalBusinessIdentity.same(prepared.getValue(it.id),identity)}?.id} ?: old.id
                        old.id to winner
                    }
                    winners.filter {it.key!=it.value}.forEach {(old,winner)->
                        db.delete("records","id=?",arrayOf(old));db.delete("search","id=?",arrayOf(old));db.execSQL("UPDATE aliases SET owner=? WHERE owner=?",arrayOf(winner,old))
                    }
                    merged.forEach {place->
                        put(place.id,"business",gson.toJson(place),place.point,place.checkedAt,now()+30L*86400000,LocalBusinessRouting.text(place))
                        place.sourceIds.forEach {key->db.insertWithOnConflict("aliases",null,ContentValues().apply {put("alias",key);put("owner",place.id)},SQLiteDatabase.CONFLICT_REPLACE)}
                    }
                    val updates=mutableListOf<Triple<String,String,String>>()
                    db.rawQuery("SELECT url,area,payload FROM sources",null).use {cursor->while(cursor.moveToNext()) {
                        val source=runCatching {gson.fromJson(cursor.getString(2),LocalPublisherSource::class.java)}.getOrNull() ?: continue
                        val winner=winners[source.businessId] ?: continue
                        if(winner!=source.businessId)updates+=Triple(cursor.getString(0),cursor.getString(1),gson.toJson(source.copy(businessId=winner)))
                    }}
                    updates.forEach {(url,region,payload)->db.update("sources",ContentValues().apply {put("payload",payload)},"url=? AND area=?",arrayOf(url,region))}
                    repaired=true
                }
            }
            receipt=if(repaired)reconciliationInput(db,selection,saved,false).signature else input.signature
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        if(!outer)reconciledBusinesses=area to requireNotNull(receipt)
        return repaired
    }
    /** Generation admission runs after acquiring the store monitor and again
     * at commit. A callback waiting behind another write cannot revive itself
     * after the reader changes query. The predicate must be cheap and local. */
    @Synchronized fun ingest(extraction:LocalExtraction,area:LocalArea,admit:()->Boolean={true}):Pair<Int,Int> {
        if(!admit())return 0 to 0
        val hasBusinesses=extraction.businesses.isNotEmpty();val hasEvents=extraction.events.isNotEmpty()
        // A link-only or valid-empty page cannot change card facts. In
        // particular it must not decode/reconcile every cached record first.
        if(!hasBusinesses&&!hasEvents&&extraction.announcements.isEmpty()) {
            offerSources(area,extraction.sources.take(48),admit);return 0 to 0
        }
        var altered=false
        if(hasBusinesses)altered=reconcile(area)||altered
        if(hasEvents)altered=reconcileEvents()||altered
        val repaired=altered
        val db=writableDatabase;var places=0;var sessions=0;var committed=false
        val identities=mutableMapOf<String,String>()
        db.beginTransaction()
        try {
            if(!admit()){if(repaired)changed();return 0 to 0}
            if(hasBusinesses) {
                val known=businesses(area).associateBy {it.id}.toMutableMap()
                val prepared=mutableMapOf<String,LocalBusinessIdentity.Prepared>()
                val names=mutableMapOf<String,MutableSet<String>>();val links=mutableMapOf<String,MutableSet<String>>();val streets=mutableMapOf<String,MutableSet<String>>();val cells=mutableMapOf<String,MutableSet<String>>()
                fun index(place:LocalBusiness,identity:LocalBusinessIdentity.Prepared=LocalBusinessIdentity.prepare(place)){prepared[place.id]=identity;names.getOrPut(identity.name){mutableSetOf()}.add(place.id);identity.street?.let {streets.getOrPut(it){mutableSetOf()}.add(place.id)};identity.cells.forEach {cells.getOrPut(it){mutableSetOf()}.add(place.id)};identity.links.forEach {links.getOrPut(it){mutableSetOf()}.add(place.id)}}
                known.values.forEach {index(it)}
                for(incoming in extraction.businesses.take(2000)) {
                    if(!LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),incoming))continue
                    val identity=LocalBusinessIdentity.prepare(incoming)
                    val alias=incoming.sourceIds.firstNotNullOfOrNull {key->rows("SELECT owner FROM aliases WHERE alias=?",arrayOf(key)).firstOrNull()}
                    val candidateIds=names[identity.name].orEmpty()+streets[identity.street].orEmpty()+identity.links.flatMap {links[it].orEmpty()}+identity.cells.flatMap {cells[it].orEmpty()}
                    val old=alias?.let {known[it] ?: business(it)} ?: candidateIds.asSequence().mapNotNull {known[it]}.firstOrNull {LocalBusinessIdentity.same(prepared.getValue(it.id),identity)}
                    val result=old?.let {LocalDiscoveryMerge.business(it,incoming)} ?: incoming
                    put(result.id,"business",gson.toJson(result),result.point,result.checkedAt,now()+30L*86400000,result.name+" "+result.category+" "+result.description+" "+result.address+" "+result.keywords.joinToString(" "))
                    result.sourceIds.forEach {key->db.insertWithOnConflict("aliases",null,ContentValues().apply {put("alias",key);put("owner",result.id)},SQLiteDatabase.CONFLICT_REPLACE)}
                    db.insertWithOnConflict("aliases",null,ContentValues().apply {put("alias",incoming.id);put("owner",result.id)},SQLiteDatabase.CONFLICT_REPLACE)
                    identities[incoming.id]=result.id;known[result.id]=result;index(result,if(old==null)identity else LocalBusinessIdentity.prepare(result));places++
                }
            }
            if(hasEvents) {
                val existing=events().associateBy {it.id}.toMutableMap()
                val pendingAnnouncements=announcements(area)
                val eventAliases=mutableMapOf<String,String>();val eventKeys=mutableMapOf<String,MutableSet<String>>()
                fun indexEvent(event:LocalEvent){event.sourceIds.forEach {eventAliases[it]=event.id};LocalEventIdentity.keys(event).forEach {eventKeys.getOrPut(it){mutableSetOf()}.add(event.id)}}
                existing.values.forEach(::indexEvent)
                for(incoming in extraction.events.take(512)) {
                    val candidates=(listOf(incoming.id)+incoming.sourceIds.mapNotNull {eventAliases[it]}+LocalEventIdentity.keys(incoming).flatMap {eventKeys[it].orEmpty()}).distinct()
                    val matches=candidates.mapNotNull(existing::get).filter {LocalDiscoveryMerge.sameEvent(it,incoming)}
                    // Moves/cancellations can update known occurrences outside
                    // the region; new distant occurrences remain inadmissible.
                    if(matches.isEmpty()&&!LocalDiscoveryFilter.geographic(area,incoming))continue
                    val result=matches.fold(incoming){acc,old->LocalDiscoveryMerge.event(old,acc)}
                    matches.filter {it.id!=result.id}.forEach {old->existing.remove(old.id);db.delete("records","id=?",arrayOf(old.id));db.delete("search","id=?",arrayOf(old.id))}
                    val expires=maxOf(now()+7L*86400000,(result.end ?: result.start).instant()?.toEpochMilli()?.plus(7L*86400000) ?: now()+37L*86400000).coerceAtMost(now()+90L*86400000)
                    put(result.id,"event",gson.toJson(result),result.point,result.checkedAt,expires,result.title+" "+result.description+" "+result.venue+" "+result.locality)
                    existing[result.id]=result;indexEvent(result);sessions++
                    db.delete("records","id=? AND kind='announcement'",arrayOf(incoming.id))
                    pendingAnnouncements.filter {it.sourceUrl in incoming.urls&&(it.date==null||it.date==incoming.start.date()?.toString())}.forEach {old->
                        db.delete("records","id=? AND kind='announcement'",arrayOf(old.id));if(old.id!=result.id)db.delete("search","id=?",arrayOf(old.id))
                    }
                }
            }
            for(candidate in extraction.announcements.take(40)) {
                if(rows("SELECT id FROM records WHERE id=? AND kind='event' AND expires>?",arrayOf(candidate.id,now().toString())).isNotEmpty()||business(candidate.publisherId)?.let {LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)}!=true)continue
                put(candidate.id,"announcement",gson.toJson(candidate),null,candidate.checkedAt,now()+7L*86400000,candidate.title+" "+candidate.text);altered=true
            }
            extraction.sources.take(48).forEach {candidate->offerSource(area,candidate.copy(businessId=identities[candidate.businessId] ?: candidate.businessId))}
            if(admit()){db.setTransactionSuccessful();committed=true}
        }finally {db.endTransaction()}
        if(!committed){if(repaired)changed();return 0 to 0}
        val expired=trim()
        if(altered||places>0||sessions>0||expired)changed()
        return places to sessions
    }
    private fun put(id:String,kind:String,payload:String,point:LocalPoint?,checked:Long,expires:Long,text:String) {
        require(payload.length<=256*1024)
        val db=writableDatabase
        db.insertWithOnConflict("records",null,ContentValues().apply {put("id",id);put("kind",kind);put("payload",payload);put("lat",point?.latitude);put("lon",point?.longitude);put("checked",checked);put("expires",expires)},SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("search","id=?",arrayOf(id));db.insert("search",null,ContentValues().apply {put("id",id);put("kind",kind);put("body",text.take(32000))})
    }
    private fun trimCache() {
        val db=writableDatabase
        db.delete("cache","expires<?",arrayOf(now().toString()))
        db.execSQL("DELETE FROM cache WHERE id IN (SELECT id FROM cache ORDER BY expires DESC LIMIT -1 OFFSET 256)")
        while(readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM cache",null).use {it.moveToFirst();it.getLong(0)}>8L*1024*1024)db.execSQL("DELETE FROM cache WHERE id IN (SELECT id FROM cache ORDER BY expires LIMIT 16)")
    }
    private fun trimSources() {
        val db=writableDatabase
        db.delete("sources","expires<?",arrayOf(now().toString()))
        db.execSQL("DELETE FROM sources WHERE rowid IN (SELECT rowid FROM sources ORDER BY rowid DESC LIMIT -1 OFFSET 768)")
    }
    @Synchronized fun trim():Boolean {
        val db=writableDatabase
        var removed=db.delete("records","expires<?",arrayOf(now().toString()))
        removed+=db.delete("records","id IN (SELECT id FROM records WHERE kind='announcement' ORDER BY checked DESC LIMIT -1 OFFSET 200)",null)
        removed+=db.delete("records","id IN (SELECT id FROM records ORDER BY checked DESC LIMIT -1 OFFSET 7000)",null)
        var size=readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM records",null).use {it.moveToFirst();it.getLong(0)}
        while(size>32L*1024*1024) {removed+=db.delete("records","id IN (SELECT id FROM records ORDER BY checked LIMIT 64)",null);size=readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM records",null).use {it.moveToFirst();it.getLong(0)}}
        db.execSQL("DELETE FROM aliases WHERE owner NOT IN (SELECT id FROM records)");db.execSQL("DELETE FROM search WHERE id NOT IN (SELECT id FROM records)")
        trimCache();trimSources()
        return removed>0
    }
    @Synchronized fun cleanup(clear:Boolean=false) {if(clear){val db=writableDatabase;db.beginTransaction();try {listOf("records","aliases","search","sources","cache").forEach {db.delete(it,null,null)};db.setTransactionSuccessful()}finally {db.endTransaction()};changed()}else if(trim())changed()}
    @Synchronized fun bytes(saved:Boolean=false):Long {val tables=if(saved)listOf("copies")else listOf("records","sources","cache");return tables.sumOf {table->readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM $table",null).use {it.moveToFirst();it.getLong(0)}}+if(saved)0 else readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM search",null).use {it.moveToFirst();it.getLong(0)}}
    private fun changed(){revision.value=revision.value+1}
}
