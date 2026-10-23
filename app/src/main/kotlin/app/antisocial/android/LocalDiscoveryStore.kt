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

internal data class LocalSavedDiscovery(val id:String,val savedAt:Long,val business:LocalBusiness?=null,val event:LocalEvent?=null)

internal class LocalDiscoveryStore(context:Context,private val now:()->Long=System::currentTimeMillis,databaseName:String="local-discovery-v1.db"):SQLiteOpenHelper(context,databaseName,null,8) {
    private val gson=Gson()
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
        if(oldVersion<8) {
            // Re-read formerly successful pages with the corrected ownership
            // and visit/calendar/opportunity projections on the next explicit search. Keep failure backoffs.
            val updates=mutableListOf<Triple<String,String,String>>()
            db.rawQuery("SELECT url,area,payload FROM sources",null).use {c->while(c.moveToNext()) {
                val source=runCatching {gson.fromJson(c.getString(2),LocalPublisherSource::class.java)}.getOrNull() ?: continue
                if(source.failures==0)updates+=Triple(c.getString(0),c.getString(1),gson.toJson(source.copy(nextCheckAt=0)))
            }}
            updates.forEach {(url,area,payload)->db.update("sources",ContentValues().apply {put("payload",payload)},"url=? AND area=?",arrayOf(url,area))}
        }
    }
    private fun rows(sql:String,args:Array<String> = emptyArray()):List<String> = readableDatabase.rawQuery(sql,args).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    @Synchronized fun cache(key:String):String?=rows("SELECT payload FROM cache WHERE id=? AND expires>?",arrayOf(key,now().toString())).firstOrNull()
    @Synchronized fun cache(key:String,value:String,ttl:Long) {require(value.length<=256*1024);writableDatabase.insertWithOnConflict("cache",null,ContentValues().apply {put("id",key);put("payload",value);put("expires",now()+ttl)},SQLiteDatabase.CONFLICT_REPLACE);trim()}
    @Synchronized fun businesses(area:LocalArea?,saved:Boolean=false):List<LocalBusiness> {
        val values=if(saved)rows("SELECT payload FROM copies WHERE kind='business' ORDER BY saved DESC LIMIT 2000")else if(area==null)rows("SELECT payload FROM records WHERE kind='business' AND expires>? ORDER BY checked DESC LIMIT 2000",arrayOf(now().toString()))else {
            val lat=area.radiusKm/110.0+.01;val lon=lat/kotlin.math.cos(Math.toRadians(area.point.latitude)).coerceAtLeast(.1)
            // Longitude wrap is filtered by haversine below; avoid excluding the
            // other side of the date line at this inexpensive candidate stage.
            val wrap=area.point.longitude-lon< -180||area.point.longitude+lon>180
            rows("SELECT payload FROM records WHERE kind='business' AND expires>? AND (lat IS NULL OR lat BETWEEN ? AND ?)"+(if(wrap)""else " AND (lon IS NULL OR lon BETWEEN ? AND ?)")+" ORDER BY checked DESC LIMIT 2000",
                (listOf(now().toString(),(area.point.latitude-lat).toString(),(area.point.latitude+lat).toString())+if(wrap)emptyList()else listOf((area.point.longitude-lon).toString(),(area.point.longitude+lon).toString())).toTypedArray())
        }
        return values.mapNotNull {runCatching {LocalBusinessOwnership.repairLegacy(gson.fromJson(it,LocalBusiness::class.java)).let {b->b.copy(websites=b.websites.flatMap {url->LocalDiscoveryIdentity.urls(url)}.distinct().take(12))}}.getOrNull()}.filter {area==null||LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)}
    }
    @Synchronized fun events(saved:Boolean=false):List<LocalEvent> = (if(saved)rows("SELECT payload FROM copies WHERE kind='event' ORDER BY saved DESC LIMIT 2000")else rows("SELECT payload FROM records WHERE kind='event' AND expires>? ORDER BY checked DESC LIMIT 5000",arrayOf(now().toString())))
        .mapNotNull {runCatching {gson.fromJson(it,LocalEvent::class.java)}.getOrNull()}
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
        readableDatabase.rawQuery("SELECT id,kind,payload,saved FROM copies ORDER BY saved DESC LIMIT 2000",null).use {c->buildList {
            while(c.moveToNext())runCatching {
                val id=c.getString(0);val saved=c.getLong(3)
                if(c.getString(1)=="business") {
                    val b=LocalBusinessOwnership.repairLegacy(gson.fromJson(c.getString(2),LocalBusiness::class.java))
                    val body=LocalBusinessRouting.text(b)+" "+(b.phones+b.emails+b.websites+b.socials).joinToString(" ")+" "+b.menus.orEmpty().joinToString(" "){m->m.title+" "+m.text+" "+m.items.joinToString(" "){it.name+" "+it.description}}
                    if(LocalDiscoveryFilter.matches(query,body))add(LocalSavedDiscovery(id,saved,business=b))
                }else if(c.getString(1)=="event") {
                    val v=gson.fromJson(c.getString(2),LocalEvent::class.java)
                    val body=listOf(v.title,v.description,v.venue,v.address,v.locality,v.organizer,v.price,v.age,v.start.value).joinToString(" ")
                    if(LocalDiscoveryFilter.matches(query,body))add(LocalSavedDiscovery(id,saved,event=v))
                }
            }
        }}
    @Synchronized fun savedIds()=rows("SELECT id FROM copies").toSet()
    @Synchronized fun sources(area:LocalArea):List<LocalPublisherSource> = rows("SELECT payload FROM sources WHERE area=? LIMIT 768",arrayOf(area.cacheKey)).mapNotNull {runCatching {gson.fromJson(it,LocalPublisherSource::class.java)}.getOrNull()}
    @Synchronized fun offerSource(area:LocalArea,source:LocalPublisherSource) {
        val old=rows("SELECT payload FROM sources WHERE url=? AND area=?",arrayOf(source.url,area.cacheKey)).firstOrNull()?.let {runCatching {gson.fromJson(it,LocalPublisherSource::class.java)}.getOrNull()}
        if(old==null)source(area,source)else if(source.businessId!=null&&(old.businessId==null||business(old.businessId)==null||source.kind==LocalSourceKind.MENU&&(old.kind!=LocalSourceKind.MENU||source.menuBranch==true&&old.menuBranch!=true)))
            source(area,old.copy(businessId=source.businessId,kind=source.kind,label=source.label.ifBlank {old.label},discoveredFrom=source.discoveredFrom ?: old.discoveredFrom,menuBranch=source.menuBranch ?: old.menuBranch,nextCheckAt=if(source.kind!=old.kind&&old.failures==0)0 else old.nextCheckAt))
    }
    @Synchronized fun source(area:LocalArea,source:LocalPublisherSource) {
        val payload=gson.toJson(source);require(payload.length<=16*1024)
        writableDatabase.insertWithOnConflict("sources",null,ContentValues().apply {put("url",source.url);put("area",area.cacheKey);put("payload",payload);put("expires",now()+30L*86400000)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun business(id:String?):LocalBusiness?=id?.let {key->
        val owner=rows("SELECT owner FROM aliases WHERE alias=?",arrayOf(key)).firstOrNull() ?: key
        rows("SELECT payload FROM records WHERE id=? AND kind='business'",arrayOf(owner)).firstOrNull()?.let {v->gson.fromJson(v,LocalBusiness::class.java)}
    }
    /** Reconcile cached occurrences too; saved snapshot bytes remain untouched. */
    @Synchronized fun reconcileEvents() {
        val saved=savedIds();val original=events().sortedBy {if(it.id in saved)0 else 1}
        val merged=LocalDiscoveryMerge.events(original)
        if(merged.size==original.size)return
        val retained=merged.mapTo(mutableSetOf()){it.id}
        val expiry=mutableMapOf<String,Long>()
        readableDatabase.rawQuery("SELECT id,expires FROM records WHERE kind='event'",null).use {c->while(c.moveToNext())expiry[c.getString(0)]=c.getLong(1)}
        val db=writableDatabase;db.beginTransaction()
        try {
            original.filter {it.id !in retained}.forEach {old->db.delete("records","id=?",arrayOf(old.id));db.delete("search","id=?",arrayOf(old.id))}
            merged.forEach {event->
                // Reconciliation is not a refresh and cannot renew stale data.
                val expires=expiry[event.id] ?: now()
                put(event.id,"event",gson.toJson(event),event.point,event.checkedAt,expires,event.title+" "+event.description+" "+event.venue+" "+event.locality)
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    /** Repair earlier ordinary records without rewriting protected saved copies. */
    @Synchronized fun reconcile(area:LocalArea) {
        val saved=savedIds();val original=businesses(area).sortedBy {if(it.id in saved)0 else 1}
        val merged=LocalDiscoveryMerge.businesses(original)
        if(merged.size==original.size)return
        val winners=original.associate {old->old.id to (merged.firstOrNull {it.id==old.id||LocalDiscoveryMerge.sameBusiness(it,old)}?.id ?: old.id)}
        val db=writableDatabase;db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    @Synchronized fun ingest(extraction:LocalExtraction,area:LocalArea):Pair<Int,Int> {
        reconcile(area)
        reconcileEvents()
        val db=writableDatabase;var places=0;var sessions=0
        db.beginTransaction()
        try {
            val known=businesses(area).associateBy {it.id}.toMutableMap()
            val names=mutableMapOf<String,MutableSet<String>>();val links=mutableMapOf<String,MutableSet<String>>();val streets=mutableMapOf<String,MutableSet<String>>();val cells=mutableMapOf<String,MutableSet<String>>()
            fun index(place:LocalBusiness){names.getOrPut(LocalDiscoveryMerge.businessName(place.name)){mutableSetOf()}.add(place.id);LocalDiscoveryMerge.street(place.address)?.let {streets.getOrPut(it){mutableSetOf()}.add(place.id)};LocalDiscoveryMerge.locationKeys(place.point).forEach {cells.getOrPut(it){mutableSetOf()}.add(place.id)};(place.websites+place.socials).forEach {url->LocalDiscoveryIdentity.url(url)?.let {links.getOrPut(it){mutableSetOf()}.add(place.id)}}}
            known.values.forEach(::index)
            val identities=mutableMapOf<String,String>()
            for(incoming in extraction.businesses.take(2000)) {
                if(!LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),incoming))continue
                val alias=incoming.sourceIds.firstNotNullOfOrNull {key->rows("SELECT owner FROM aliases WHERE alias=?",arrayOf(key)).firstOrNull()}
                val candidateIds=names[LocalDiscoveryMerge.businessName(incoming.name)].orEmpty()+streets[LocalDiscoveryMerge.street(incoming.address)].orEmpty()+(incoming.websites+incoming.socials).flatMap {links[LocalDiscoveryIdentity.url(it)].orEmpty()}+LocalDiscoveryMerge.locationKeys(incoming.point).flatMap {cells[it].orEmpty()}
                val old=alias?.let {known[it] ?: business(it)} ?: candidateIds.asSequence().mapNotNull {known[it]}.firstOrNull {LocalDiscoveryMerge.sameBusiness(it,incoming)}
                val result=old?.let {LocalDiscoveryMerge.business(it,incoming)} ?: incoming
                put(result.id,"business",gson.toJson(result),result.point,result.checkedAt,now()+30L*86400000,result.name+" "+result.category+" "+result.description+" "+result.address+" "+result.keywords.joinToString(" "))
                result.sourceIds.forEach {key->db.insertWithOnConflict("aliases",null,ContentValues().apply {put("alias",key);put("owner",result.id)},SQLiteDatabase.CONFLICT_REPLACE)}
                db.insertWithOnConflict("aliases",null,ContentValues().apply {put("alias",incoming.id);put("owner",result.id)},SQLiteDatabase.CONFLICT_REPLACE)
                identities[incoming.id]=result.id;known[result.id]=result;index(result);places++

            }
            val existing=events().associateBy {it.id}.toMutableMap()
            val pendingAnnouncements=announcements(area)
            val eventAliases=mutableMapOf<String,String>();val eventKeys=mutableMapOf<String,MutableSet<String>>()
            fun indexEvent(event:LocalEvent){event.sourceIds.forEach {eventAliases[it]=event.id};LocalEventIdentity.keys(event).forEach {eventKeys.getOrPut(it){mutableSetOf()}.add(event.id)}}
            existing.values.forEach(::indexEvent)
            for(incoming in extraction.events.take(512)) {
                val candidates=(listOf(incoming.id)+incoming.sourceIds.mapNotNull {eventAliases[it]}+LocalEventIdentity.keys(incoming).flatMap {eventKeys[it].orEmpty()}).distinct()
                val matches=candidates.mapNotNull(existing::get).filter {LocalDiscoveryMerge.sameEvent(it,incoming)}
                // Regional feeds can contain distant events. Retain only local
                // occurrences, but allow a known event's move/cancellation to
                // update its existing row even when the new venue is outside.
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
            for(candidate in extraction.announcements.take(40)) {
                if(candidate.id in existing||business(candidate.publisherId)?.let {LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)}!=true)continue
                put(candidate.id,"announcement",gson.toJson(candidate),null,candidate.checkedAt,now()+7L*86400000,candidate.title+" "+candidate.text)
            }
            extraction.sources.take(48).forEach {candidate->offerSource(area,candidate.copy(businessId=identities[candidate.businessId] ?: candidate.businessId))}
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        trim();changed();return places to sessions
    }
    private fun put(id:String,kind:String,payload:String,point:LocalPoint?,checked:Long,expires:Long,text:String) {
        require(payload.length<=256*1024)
        val db=writableDatabase
        db.insertWithOnConflict("records",null,ContentValues().apply {put("id",id);put("kind",kind);put("payload",payload);put("lat",point?.latitude);put("lon",point?.longitude);put("checked",checked);put("expires",expires)},SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("search","id=?",arrayOf(id));db.insert("search",null,ContentValues().apply {put("id",id);put("kind",kind);put("body",text.take(32000))})
    }
    @Synchronized fun trim() {
        val db=writableDatabase
        db.delete("records","expires<?",arrayOf(now().toString()));db.delete("cache","expires<?",arrayOf(now().toString()))
        db.delete("sources","expires<?",arrayOf(now().toString()))
        db.execSQL("DELETE FROM records WHERE id IN (SELECT id FROM records WHERE kind='announcement' ORDER BY checked DESC LIMIT -1 OFFSET 200)")
        db.execSQL("DELETE FROM records WHERE id IN (SELECT id FROM records ORDER BY checked DESC LIMIT -1 OFFSET 7000)")
        var size=readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM records",null).use {it.moveToFirst();it.getLong(0)}
        while(size>32L*1024*1024) {db.execSQL("DELETE FROM records WHERE id IN (SELECT id FROM records ORDER BY checked LIMIT 64)");size=readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM records",null).use {it.moveToFirst();it.getLong(0)}}
        db.execSQL("DELETE FROM aliases WHERE owner NOT IN (SELECT id FROM records)");db.execSQL("DELETE FROM search WHERE id NOT IN (SELECT id FROM records)")
        db.execSQL("DELETE FROM cache WHERE id IN (SELECT id FROM cache ORDER BY expires DESC LIMIT -1 OFFSET 256)")
        while(readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM cache",null).use {it.moveToFirst();it.getLong(0)}>8L*1024*1024)db.execSQL("DELETE FROM cache WHERE id IN (SELECT id FROM cache ORDER BY expires LIMIT 16)")
        db.execSQL("DELETE FROM sources WHERE rowid IN (SELECT rowid FROM sources ORDER BY rowid DESC LIMIT -1 OFFSET 768)")
    }
    @Synchronized fun cleanup(clear:Boolean=false) {if(clear){val db=writableDatabase;db.beginTransaction();try {listOf("records","aliases","search","sources","cache").forEach {db.delete(it,null,null)};db.setTransactionSuccessful()}finally {db.endTransaction()};changed()}else trim()}
    @Synchronized fun bytes(saved:Boolean=false):Long {val tables=if(saved)listOf("copies")else listOf("records","sources","cache");return tables.sumOf {table->readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM $table",null).use {it.moveToFirst();it.getLong(0)}}+if(saved)0 else readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM search",null).use {it.moveToFirst();it.getLong(0)}}
    private fun changed(){revision.value=revision.value+1}
}
