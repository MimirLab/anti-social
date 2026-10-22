/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

internal data class ContextSnapshot(val roots: List<Post>,val text: String,val title: String,val revision: String,
    val variant: String="current",val links: List<String> = emptyList(),val media: List<Media> = emptyList())
internal data class ContextRecord(val id: String,val snapshot: ContextSnapshot,val brief: String="",val updatedAt: Long=0,
    val checkedAt: Long=0,val status: String="queued",val message: String="Preparing Context",val model: String="",
    val findingStamp: String="",val saved: Boolean=false,val requested: Boolean=true,val nextCheck: Long=0,val unchanged: Int=0,val cycleStartedAt: Long=0,val stale: Boolean=false,val processingPolicy: String?=null,
    val compositionPolicy: String?=null,val briefFindingIds:List<String>?=null,val briefSignalStamp:String?=null,val briefSources:List<String>?=null,val deliveryPolicy:String?=null)
internal data class ContextStream(val id: String,val root: Post,val kind: String="platform",val previous: Page?=null,
    val cursors: Set<String> = emptySet(),val pendingParents: List<String> = emptyList(),val visitedParents: Set<String> = emptySet(),
    val rootLimit: Int=30,val status: String="pending",val message: String="Waiting to read",val fetchedAt: Long=0,val count: Int=0,
    val limited: Boolean=false,val originKey: String=CrossPosts.originalKey(root),val work: Map<String,String> = emptyMap(),val commentContext: String="",val commentDocument: String?=null)
internal data class ContextStoredDocument(val document: ContextDocument,val processed: Boolean)
internal data class ContextReplyEvidence(val contexts:ContextReplyContexts=ContextReplyContexts(),val documents:List<ContextDocument> = emptyList())
internal data class ContextView(val record: ContextRecord,val findings: List<ContextFinding>,val documents: List<ContextStoredDocument>,val streams: List<ContextStream>,val reviewIdentities:Map<String,String>?=null,val replyEvidence:ContextReplyEvidence?=null)
/** A composition reads this immutable local snapshot. Retrieval bookkeeping
 * may advance while inference waits; changed evidence must be reviewed again. */
internal data class ContextPublicationInput(val record:ContextRecord,val findings:List<ContextFinding>,val documents:List<ContextStoredDocument>,val reviewIdentities:Map<String,String>,val replyEvidence:ContextReplyEvidence=ContextReplyEvidence()) {
    fun sameEvidence(other:ContextPublicationInput):Boolean = record.id==other.record.id&&record.processingPolicy==other.record.processingPolicy&&
        reviewIdentities==other.reviewIdentities&&replyEvidence.contexts==other.replyEvidence.contexts&&
        replyEvidence.documents.associate {it.id to ContextSources.review(it)}==other.replyEvidence.documents.associate {it.id to ContextSources.review(it)}&&
        findings.associateBy {it.id}==other.findings.associateBy {it.id}&&
        documents.associate {it.document.id to it.copy(document=ContextSources.review(it.document))}==
        other.documents.associate {it.document.id to it.copy(document=ContextSources.review(it.document))}
}

/** Independent versioned database: each source page, processing receipt and
 * publication is committed atomically. The reader's existing Room schema stays intact. */
internal class ContextStore(context: Context,databaseName: String="context-v1.db"): SQLiteOpenHelper(context,databaseName,null,7) {
    private val gson=Gson()
    val revision=MutableStateFlow(0L)
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        // REPLACE must fire the old row's DELETE accounting trigger too.
        db.execSQL("PRAGMA recursive_triggers=ON")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contexts(id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE aliases(alias TEXT PRIMARY KEY NOT NULL,owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE documents(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,revision TEXT NOT NULL,payload TEXT NOT NULL,processed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE INDEX documents_pending ON documents(owner,processed)")
        db.execSQL("CREATE TABLE units(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,document TEXT NOT NULL,revision TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE INDEX units_document ON units(owner,document)")
        db.execSQL("CREATE TABLE findings(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE streams(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE saved_snapshots(owner TEXT PRIMARY KEY NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE compactions(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE publications(owner TEXT PRIMARY KEY NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        createProjection(db)
        createAccounting(db)
        createRelations(db)
        createReadingViews(db)
        createReviewClaims(db)
        createEarlierPublication(db)
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        require(oldVersion in 1..6&&newVersion==7){"Unsupported Context migration"}
        if(oldVersion==6){createEarlierPublication(db);return}
        if(oldVersion<3) {
        db.execSQL("CREATE TABLE IF NOT EXISTS compactions(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS publications(owner TEXT PRIMARY KEY NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS documents_pending ON documents(owner,processed)")
        createProjection(db)
        val owners=db.rawQuery("SELECT id FROM contexts",emptyArray()).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        owners.forEach {rebuildProjection(db,it)}
        createAccounting(db)
        }
        if(oldVersion<4)createRelations(db)
        createReadingViews(db)
        val columns=db.rawQuery("PRAGMA table_info(finding_sources)",emptyArray()).use {c->buildSet {while(c.moveToNext())add(c.getString(1))}}
        if("reading_quote" !in columns)db.execSQL("ALTER TABLE finding_sources ADD COLUMN reading_quote TEXT NOT NULL DEFAULT ''")
        for(event in listOf("insert","delete","update"))db.execSQL("DROP TRIGGER IF EXISTS finding_sources_bytes_$event")
        accountingTriggers(db,"finding_sources","quote","reading_quote")
        createReviewClaims(db)
        createEarlierPublication(db)
    }
    private fun createEarlierPublication(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE earlier_publications(owner TEXT PRIMARY KEY NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        accountingTriggers(db,"earlier_publications","payload")
    }
    private fun createReviewClaims(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE review_claims(owner TEXT NOT NULL,document TEXT NOT NULL,token TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,document),FOREIGN KEY(owner,document) REFERENCES documents(owner,id) ON DELETE CASCADE)")
        accountingTriggers(db,"review_claims","document","token","payload")
    }
    private fun createReadingViews(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_views(owner TEXT NOT NULL,document TEXT NOT NULL,field TEXT NOT NULL,revision TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,document,field),FOREIGN KEY(owner,document) REFERENCES documents(owner,id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_chunks(owner TEXT NOT NULL,document TEXT NOT NULL,field TEXT NOT NULL,revision TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,document,field,id),FOREIGN KEY(owner,document) REFERENCES documents(owner,id) ON DELETE CASCADE)")
        for(table in listOf("reading_views","reading_chunks"))if(!db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name=?",arrayOf("${table}_bytes_insert")).use {it.moveToFirst()}) {
            db.execSQL("UPDATE context_bytes SET bytes=bytes+COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM $table WHERE owner=context_bytes.owner),0)")
            accountingTriggers(db,table,"payload")
        }
    }
    private fun createRelations(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE context_source_keys(owner TEXT NOT NULL,sourceKey TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(owner,sourceKey,document),FOREIGN KEY(owner,document) REFERENCES documents(owner,id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX context_keys_document ON context_source_keys(owner,document)")
        db.execSQL("CREATE TABLE context_source_relations(owner TEXT NOT NULL,document TEXT NOT NULL,parentKey TEXT NOT NULL,PRIMARY KEY(owner,document,parentKey),FOREIGN KEY(owner,document) REFERENCES documents(owner,id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX context_parent_key ON context_source_relations(owner,parentKey)")
        db.rawQuery("SELECT owner,payload FROM documents",emptyArray()).use {c->while(c.moveToNext())registerRelations(db,c.getString(0),gson.fromJson(c.getString(1),ContextDocument::class.java))}
        for((table,column) in listOf("context_source_keys" to "sourceKey","context_source_relations" to "parentKey")) {
            db.execSQL("UPDATE context_bytes SET bytes=bytes+COALESCE((SELECT SUM(length(CAST($column AS BLOB))) FROM $table WHERE owner=context_bytes.owner),0)")
            accountingTriggers(db,table,column)
        }
    }
    private fun registerRelations(db: SQLiteDatabase,owner: String,document: ContextDocument) {
        db.delete("context_source_keys","owner=? AND document=?",arrayOf(owner,document.id))
        db.delete("context_source_relations","owner=? AND document=?",arrayOf(owner,document.id))
        for(key in ContextSources.keys(document)) {
            require(key.length in 1..1024){"Invalid local Context source key"}
            db.insertOrThrow("context_source_keys",null,values("owner" to owner,"sourceKey" to key,"document" to document.id))
        }
        for(parent in ContextSources.parents(owner,document)) {
            require(parent.length in 1..1024){"Invalid local Context parent key"}
            db.insertOrThrow("context_source_relations",null,values("owner" to owner,"parentKey" to parent,"document" to document.id))
        }
    }
    private fun dependents(db: SQLiteDatabase,owner: String,keys: Collection<String>): Set<String> = keys.flatMap {key->
        db.rawQuery("SELECT document FROM context_source_relations WHERE owner=? AND parentKey=?",arrayOf(owner,key)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    }.toSet()
    @Synchronized fun hasContextKey(owner: String,key: String): Boolean =readableDatabase.rawQuery(
        "SELECT document FROM context_source_keys WHERE owner=? AND sourceKey=? LIMIT 2",arrayOf(owner,key)).use {it.moveToFirst()&&!it.moveToNext()}
    @Synchronized fun documentForContextKey(owner: String,key: String): ContextDocument? {
        val matches=readableDatabase.rawQuery("SELECT d.payload FROM context_source_keys k JOIN documents d ON d.owner=k.owner AND d.id=k.document WHERE k.owner=? AND k.sourceKey=? LIMIT 2",arrayOf(owner,key)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        // An ambiguous alias is missing context, never permission to choose an
        // unrelated parent. Direct document keys follow the same rule.
        return matches.singleOrNull()?.let {gson.fromJson(it,ContextDocument::class.java)}
    }
    private fun documentsForContextKeys(owner:String,keys:List<String>):Map<String,ContextDocument?> = keys.distinct().chunked(400).flatMap {part->
        readableDatabase.rawQuery("SELECT k.sourceKey,d.payload FROM context_source_keys k JOIN documents d ON d.owner=k.owner AND d.id=k.document WHERE k.owner=? AND k.sourceKey IN (${part.joinToString(","){"?"}})",
            (listOf(owner)+part).toTypedArray()).use {c->buildList {while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
    }.groupBy({it.first},{it.second}).mapValues {(_,matches)->matches.singleOrNull()?.let {gson.fromJson(it,ContextDocument::class.java)}}
    private fun createProjection(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE finding_sources(owner TEXT NOT NULL,unit TEXT NOT NULL,finding TEXT NOT NULL,source TEXT NOT NULL,quote TEXT NOT NULL,reading_quote TEXT NOT NULL DEFAULT '',PRIMARY KEY(owner,unit,finding,source),FOREIGN KEY(owner,unit) REFERENCES units(owner,id) ON DELETE CASCADE,FOREIGN KEY(owner,source) REFERENCES documents(owner,id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX finding_sources_source ON finding_sources(owner,source)")
        db.execSQL("CREATE INDEX finding_sources_finding ON finding_sources(owner,finding)")
    }
    private fun createAccounting(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE context_bytes(owner TEXT PRIMARY KEY NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,bytes INTEGER NOT NULL,saved INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX context_bytes_saved ON context_bytes(saved)")
        db.rawQuery("SELECT id,payload FROM contexts",emptyArray()).use {c->while(c.moveToNext()) {
            val record=gson.fromJson(c.getString(1),ContextRecord::class.java)
            db.insertOrThrow("context_bytes",null,values("owner" to c.getString(0),"bytes" to c.getString(1).toByteArray().size,"saved" to if(record.saved)1 else 0))
        }}
        val tables=listOf("documents","units","findings","streams","saved_snapshots","compactions","publications")
        for(table in tables) {
            db.execSQL("UPDATE context_bytes SET bytes=bytes+COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM $table WHERE owner=context_bytes.owner),0)")
            accountingTriggers(db,table,"payload")
        }
        db.execSQL("UPDATE context_bytes SET bytes=bytes+COALESCE((SELECT SUM(length(CAST(quote AS BLOB))) FROM finding_sources WHERE owner=context_bytes.owner),0)")
        accountingTriggers(db,"finding_sources","quote","reading_quote")
        db.execSQL("CREATE TRIGGER context_bytes_insert AFTER INSERT ON contexts BEGIN INSERT INTO context_bytes(owner,bytes,saved) VALUES(NEW.id,length(CAST(NEW.payload AS BLOB)),0); END")
        db.execSQL("CREATE TRIGGER context_bytes_update AFTER UPDATE OF payload ON contexts BEGIN UPDATE context_bytes SET bytes=bytes+length(CAST(NEW.payload AS BLOB))-length(CAST(OLD.payload AS BLOB)) WHERE owner=NEW.id; END")
    }
    private fun accountingTriggers(db: SQLiteDatabase,table: String,column: String,vararg extra: String) {
        val columns=listOf(column)+extra
        fun size(row: String)=columns.joinToString("+"){"length(CAST($row.$it AS BLOB))"}
        db.execSQL("CREATE TRIGGER ${table}_bytes_insert AFTER INSERT ON $table BEGIN UPDATE context_bytes SET bytes=bytes+(${size("NEW")}) WHERE owner=NEW.owner; END")
        db.execSQL("CREATE TRIGGER ${table}_bytes_delete AFTER DELETE ON $table BEGIN UPDATE context_bytes SET bytes=bytes-(${size("OLD")}) WHERE owner=OLD.owner; END")
        db.execSQL("CREATE TRIGGER ${table}_bytes_update AFTER UPDATE OF ${columns.joinToString(",")} ON $table BEGIN UPDATE context_bytes SET bytes=bytes+(${size("NEW")})-(${size("OLD")}) WHERE owner=NEW.owner; END")
    }
    private fun values(vararg fields: Pair<String,Any?>)=ContentValues().apply {fields.forEach {(k,v)->when(v){null->putNull(k);is Int->put(k,v);else->put(k,v.toString())}}}
    private inline fun <T> transaction(action: (SQLiteDatabase)->T): T {val db=writableDatabase;db.beginTransaction();return try {action(db).also {db.setTransactionSuccessful()}}finally {db.endTransaction();revision.value++}}
    private fun <T> rows(sql: String,args: Array<String>,type: Class<T>): List<T> = readableDatabase.rawQuery(sql,args).use {c->buildList {while(c.moveToNext())add(gson.fromJson(c.getString(0),type))}}
    @Synchronized fun all(): List<ContextRecord> = rows("SELECT payload FROM contexts",emptyArray(),ContextRecord::class.java)
    @Synchronized fun record(id: String): ContextRecord?=rows("SELECT payload FROM contexts WHERE id=?",arrayOf(id),ContextRecord::class.java).firstOrNull()
    /** Bounded alias lookup without hydrating unrelated Context payloads. */
    @Synchronized fun forOriginals(keys: List<String>,variant: String="current"): List<ContextRecord> = keys.distinct().take(CrossPosts.MAX_EDITIONS).mapNotNull {key->
        readableDatabase.rawQuery("SELECT owner FROM aliases WHERE alias=?",arrayOf("$variant|$key")).use {if(it.moveToFirst())it.getString(0)else null}
    }.distinct().mapNotNull(::record)
    @Synchronized fun save(record: ContextRecord)=transaction {db->putRecord(db,record)}
    /** Reserve a due automatic attempt without overwriting a concurrent source,
     * publication or explicit-request update with an older caller's record. */
    @Synchronized fun reserveMaintenance(owner: String,now: Long,retryAfter: Long): Boolean {
        val current=record(owner) ?: return false
        if(current.nextCheck>now)return false
        save(current.copy(nextCheck=maxOf(current.nextCheck,retryAfter)))
        return true
    }
    /** A foreground card may change while saved details are being decoded.
     * Automatic bootstrap must never replace that newer source snapshot. */
    @Synchronized fun resolveMaintenance(snapshot: ContextSnapshot,expected: Map<String,String>): ContextRecord? {
        val current=forOriginals(snapshot.roots.map(CrossPosts::originalKey),snapshot.variant)
        if(current.map {it.id}.toSet()!=expected.keys||current.any {expected[it.id]!=it.snapshot.revision})return null
        return resolve(snapshot,requested=false)
    }
    /** Newly discovered saved editions extend completed work; they must not
     * restart successful streams just because their sibling was checked first. */
    @Synchronized fun continueMaintenance(owner: String,expectedRevision: String,now: Long): ContextRecord? {
        val current=record(owner)?.takeIf {it.snapshot.revision==expectedRevision} ?: return null
        return current.copy(nextCheck=0,status="queued",message="Another saved source is waiting to be read",
            cycleStartedAt=current.cycleStartedAt.takeIf {it>0} ?: now).also(::save)
    }
    private fun putRecord(db: SQLiteDatabase,record: ContextRecord) {
        // REPLACE deletes the parent and cascades its evidence. Use UPDATE on
        // API 26 too, where SQLite's newer UPSERT syntax is unavailable.
        if(db.update("contexts",values("payload" to gson.toJson(record)),"id=?",arrayOf(record.id))==0)
            db.insertOrThrow("contexts",null,values("id" to record.id,"payload" to gson.toJson(record)))
        db.update("context_bytes",values("saved" to if(record.saved)1 else 0),"owner=?",arrayOf(record.id))
        // A snapshot/policy change revokes old work. Status, saving and
        // scheduling choices do not revoke otherwise current model input.
        db.delete("review_claims","owner=? AND payload!=?",arrayOf(record.id,gson.toJson(ContextReviewSession(record.snapshot.revision,record.processingPolicy.orEmpty()))))
    }
    @Synchronized fun resolve(snapshot: ContextSnapshot,requested: Boolean=true): ContextRecord=transaction {db->
        val keys=snapshot.roots.map {"${snapshot.variant}|${CrossPosts.originalKey(it)}"}.toSet()
        require(keys.isNotEmpty())
        val owners=keys.mapNotNull {key->db.rawQuery("SELECT owner FROM aliases WHERE alias=?",arrayOf(key)).use {if(it.moveToFirst())it.getString(0)else null}}.distinct()
        val exact=owners.mapNotNull(::record).firstOrNull {old->old.snapshot.roots.map(CrossPosts::originalKey).toSet()==snapshot.roots.map(CrossPosts::originalKey).toSet()}
        val reusable=exact ?: owners.mapNotNull(::record).firstOrNull {old->snapshot.roots.map(CrossPosts::originalKey).containsAll(old.snapshot.roots.map(CrossPosts::originalKey))}
        val target=reusable ?: ContextRecord(UUID.randomUUID().toString(),snapshot,requested=requested)
        putRecord(db,target)
        // Merge evidence only for originals present in the new card. A split
        // gets a fresh synthesis, while its unchanged source receipts survive.
        var copied=false
        for(owner in owners.filter {it!=target.id}) {
            val samePolicy=record(owner)?.processingPolicy==target.processingPolicy
            val allowed=snapshot.roots.map(CrossPosts::originalKey).toSet()
            val selected=documents(owner).filter {it.document.kind!="post"&&it.document.metadata["root"] in allowed}
            for(item in selected) {
                val d=item.document
                val current=document(target.id,d.id)
                if(current==null)db.insertWithOnConflict("documents",null,values("owner" to target.id,"id" to d.id,"revision" to d.revision,"payload" to gson.toJson(d),"processed" to if(item.processed&&samePolicy)1 else 0),SQLiteDatabase.CONFLICT_IGNORE)
                if(current==null)db.execSQL("INSERT OR IGNORE INTO reading_views(owner,document,field,revision,payload) SELECT ?,document,field,revision,payload FROM reading_views WHERE owner=? AND document=?",arrayOf(target.id,owner,d.id))
                document(target.id,d.id)?.document?.let {registerRelations(db,target.id,it)}
                if(samePolicy)outcomes(owner,d.id,d.revision).forEach {u->db.insertWithOnConflict("units",null,values("owner" to target.id,"id" to u.unitId,"document" to u.documentId,"revision" to u.revision,"payload" to gson.toJson(u)),SQLiteDatabase.CONFLICT_IGNORE)}
                copied=true
            }
        }
        if(copied){rebuildProjection(db,target.id);db.delete("review_claims","owner=?",arrayOf(target.id))}
        keys.forEach {db.insertWithOnConflict("aliases",null,values("alias" to it,"owner" to target.id),SQLiteDatabase.CONFLICT_REPLACE)}
        val changed=target.snapshot.revision!=snapshot.revision
        target.copy(snapshot=snapshot,requested=target.requested||requested,status=if(changed)"queued"else target.status,
            stale=target.stale||changed&&target.brief.isNotBlank(),message=if(changed)"Post updated · checking Context"else target.message).also {putRecord(db,it)}
    }
    @Synchronized fun documents(owner: String): List<ContextStoredDocument> = readableDatabase.rawQuery("SELECT payload,processed FROM documents WHERE owner=? ORDER BY rowid",arrayOf(owner)).use {c->buildList {while(c.moveToNext())add(ContextStoredDocument(gson.fromJson(c.getString(0),ContextDocument::class.java),c.getInt(1)==1))}}
    @Synchronized fun pendingDocuments(owner: String): List<ContextStoredDocument> = readableDatabase.rawQuery("SELECT payload FROM documents WHERE owner=? AND processed=0 ORDER BY rowid",arrayOf(owner)).use {c->buildList {while(c.moveToNext())add(ContextStoredDocument(gson.fromJson(c.getString(0),ContextDocument::class.java),false))}}
    /** Use the indexed, uniquely resolved relationships before applying the
     * work limit. Parent aliases can be ambiguous outside this pending subset;
     * never resolve them by looking only at the candidate list. */
    @Synchronized fun reviewOrder(owner:String,preferred:List<ContextStoredDocument>):List<ContextStoredDocument> {
        val parents=mutableMapOf<String,MutableSet<String>>()
        for(part in preferred.map {it.document.id}.chunked(400))readableDatabase.rawQuery(
            "SELECT r.document,k.document FROM context_source_relations r JOIN context_source_keys k ON k.owner=r.owner AND k.sourceKey=r.parentKey WHERE r.owner=? AND r.document IN (${part.joinToString(","){"?"}}) AND NOT EXISTS (SELECT 1 FROM context_source_keys a WHERE a.owner=k.owner AND a.sourceKey=k.sourceKey AND a.document!=k.document)",
            (listOf(owner)+part).toTypedArray()).use {c->while(c.moveToNext())parents.getOrPut(c.getString(0)){linkedSetOf()}.add(c.getString(1))}
        return ContextSourceOrder.order(preferred,{it.document.id}) {parents[it.document.id].orEmpty()}
    }
    /** Status-only admission: never decode full bodies just to see whether an
     * earlier cited source is still awaiting review. Chunk below API26's bind limit. */
    @Synchronized fun hasUnreviewed(owner:String,sources:Collection<String>?=null):Boolean {
        if(sources==null)return readableDatabase.rawQuery("SELECT 1 FROM documents WHERE owner=? AND processed!=1 LIMIT 1",arrayOf(owner)).use {it.moveToFirst()}
        return sources.distinct().chunked(400).any {part->readableDatabase.rawQuery("SELECT 1 FROM documents WHERE owner=? AND processed!=1 AND id IN (${part.joinToString(","){"?"}}) LIMIT 1",(listOf(owner)+part).toTypedArray()).use {it.moveToFirst()}}
    }
    @Synchronized fun document(owner: String,id: String): ContextStoredDocument?=readableDatabase.rawQuery("SELECT payload,processed FROM documents WHERE owner=? AND id=?",arrayOf(owner,id)).use {c->if(c.moveToFirst())ContextStoredDocument(gson.fromJson(c.getString(0),ContextDocument::class.java),c.getInt(1)==1)else null}
    @Synchronized fun ingest(owner: String,documents: List<ContextDocument>,stream: ContextStream?=null)=transaction {db->
        val changedKeys=linkedSetOf<String>()
        val invalid=linkedSetOf<String>()
        var changedExisting=false
        documents.forEach {d->
            val old=document(owner,d.id)
            if(old==null||ContextSources.review(old.document)!=ContextSources.review(d)) {
                changedKeys+=ContextSources.keys(d)
                old?.let {changedKeys+=ContextSources.keys(it.document)}
                invalid+=d.id
                changedExisting=changedExisting||old!=null
                invalidateDocument(db,owner,d.id)
                db.insertWithOnConflict("documents",null,values("owner" to owner,"id" to d.id,"revision" to d.revision,"payload" to gson.toJson(d),"processed" to 0),SQLiteDatabase.CONFLICT_REPLACE)
                registerRelations(db,owner,d)
            }else if(old.document!=d) {
                if(ContextSources.keys(old.document)!=ContextSources.keys(d)) {
                    changedKeys+=ContextSources.keys(old.document);changedKeys+=ContextSources.keys(d)
                    registerRelations(db,owner,d)
                }
                // Fresh validators/retrieval dates update provenance without
                // invalidating an unchanged semantic review.
                db.update("documents",values("payload" to gson.toJson(d)),"owner=? AND id=?",arrayOf(owner,d.id))
            }
        }
        val dependent=dependents(db,owner,changedKeys)-invalid
        // Known findings are also model input. Conservatively revoke this
        // owner's in-flight window on any semantic source/alias change, while
        // keeping completed unaffected receipts reusable.
        if(changedKeys.isNotEmpty())db.delete("review_claims","owner=?",arrayOf(owner))
        for(id in dependent) {
            invalidateDocument(db,owner,id)
            db.update("documents",values("processed" to 0),"owner=? AND id=?",arrayOf(owner,id))
        }
        if(changedExisting||dependent.isNotEmpty())record(owner)?.takeIf {it.brief.isNotBlank()}?.let {putRecord(db,it.copy(stale=true))}
        stream?.let {db.insertWithOnConflict("streams",null,values("owner" to owner,"id" to it.id,"payload" to gson.toJson(it)),SQLiteDatabase.CONFLICT_REPLACE)}
    }
    @Synchronized fun streams(owner: String): List<ContextStream> = rows("SELECT payload FROM streams WHERE owner=? ORDER BY rowid",arrayOf(owner),ContextStream::class.java)
    @Synchronized fun stream(owner: String,value: ContextStream)=ingest(owner,emptyList(),value)
    @Synchronized fun removeStream(owner: String,id: String) {if(writableDatabase.delete("streams","owner=? AND id=?",arrayOf(owner,id))>0)revision.value++}
    @Synchronized fun invalidateMedia(owner: String,resource: String,sourceSha: String)=transaction {db->
        documents(owner).filter {it.document.metadata["resource"]==resource&&it.document.metadata["sourceSha256"]!=sourceSha}.forEach {item->
            db.delete("review_claims","owner=?",arrayOf(owner))
            for(child in dependents(db,owner,ContextSources.keys(item.document))) {
                invalidateDocument(db,owner,child)
                db.update("documents",values("processed" to 0),"owner=? AND id=?",arrayOf(owner,child))
            }
            invalidateDocument(db,owner,item.document.id)
            db.delete("documents","owner=? AND id=?",arrayOf(owner,item.document.id))
        }
        record(owner)?.takeIf {it.brief.isNotBlank()}?.let {putRecord(db,it.copy(stale=true))}
    }
    @Synchronized fun outcomes(owner: String,document: String,revision: String): List<ContextUnitOutcome> = rows("SELECT payload FROM units WHERE owner=? AND document=? AND revision=?",arrayOf(owner,document,revision),ContextUnitOutcome::class.java)
    @Synchronized fun reading(owner: String,document: ContextDocument,field: String,source: String): ContextReadingText? {
        require(field in setOf("body","title","parent"))
        val payload=readableDatabase.rawQuery("SELECT payload FROM reading_views WHERE owner=? AND document=? AND field=? AND revision=?",arrayOf(owner,document.id,field,document.revision)).use {if(it.moveToFirst())it.getString(0)else null} ?: return null
        return runCatching {
            val json=com.google.gson.JsonParser.parseString(payload).asJsonObject
            val reading=if(json["identity"]?.asBoolean==true) {
                require(json["sourceHash"].asString==ContextReadingText.digest(source))
                ContextReadingText.identity(source,json["recipe"].asString,json["languageUnconfirmed"]?.asBoolean==true)
            }else gson.fromJson(json,ContextReadingText::class.java)
            reading.takeIf {(it.recipe==EnglishReadingPreparer.POLICY||it.recipe.startsWith(EnglishReadingPreparer.POLICY+"|"))&&it.validate(source).let {true}}
        }.getOrNull()
    }
    @Synchronized fun saveReading(owner: String,document: ContextDocument,field: String,source: String,reading: ContextReadingText)=transaction {db->
        require(field in setOf("body","title","parent"));reading.validate(source)
        require(document(owner,document.id)?.document?.revision==document.revision){"Source changed during translation"}
        val payload=if(reading.spans.none {it.translated})gson.toJson(mapOf("identity" to true,"sourceHash" to reading.sourceHash,"recipe" to reading.recipe,"languageUnconfirmed" to reading.languageUnconfirmed))else gson.toJson(reading)
        val previous=db.rawQuery("SELECT payload FROM reading_views WHERE owner=? AND document=? AND field=?",arrayOf(owner,document.id,field)).use {if(it.moveToFirst())it.getString(0)else null}
        if(previous!=payload) {
            db.delete("review_claims","owner=?",arrayOf(owner))
            val affected=setOf(document.id)+if(field=="body")dependents(db,owner,ContextSources.keys(document))else emptySet()
            affected.forEach {id->invalidateDocument(db,owner,id);db.update("documents",values("processed" to 0),"owner=? AND id=?",arrayOf(owner,id))}
            record(owner)?.takeIf {it.brief.isNotBlank()}?.let {putRecord(db,it.copy(stale=true))}
        }
        db.delete("reading_chunks","owner=? AND document=? AND field=?",arrayOf(owner,document.id,field))
        db.insertWithOnConflict("reading_views",null,values("owner" to owner,"document" to document.id,"field" to field,"revision" to document.revision,"payload" to payload),SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun readingChunk(owner:String,document:ContextDocument,field:String,id:String):ReadingTranslation?=rows("SELECT payload FROM reading_chunks WHERE owner=? AND document=? AND field=? AND revision=? AND id=?",arrayOf(owner,document.id,field,document.revision,id),ReadingTranslation::class.java).singleOrNull()
    @Synchronized fun saveReadingChunk(owner:String,document:ContextDocument,field:String,id:String,translation:ReadingTranslation)=transaction {db->
        require(field in setOf("body","title","parent")&&id.matches(Regex("[a-f0-9]{64}")))
        require(document(owner,document.id)?.document?.revision==document.revision)
        db.insertWithOnConflict("reading_chunks",null,values("owner" to owner,"document" to document.id,"field" to field,"revision" to document.revision,"id" to id,"payload" to gson.toJson(translation)),SQLiteDatabase.CONFLICT_REPLACE)
    }
    /** Model/window/recipe changes invalidate derived reviews. Source pages,
     * translations, stream cursors, publications and saved captures stay. */
    @Synchronized fun processingPolicy(owner: String,policy: String)=transaction {db->
        val record=record(owner) ?: return@transaction
        if(record.processingPolicy==policy)return@transaction
        db.delete("units","owner=?",arrayOf(owner));db.delete("findings","owner=?",arrayOf(owner));db.delete("compactions","owner=?",arrayOf(owner))
        db.update("documents",values("processed" to 0),"owner=?",arrayOf(owner))
        putRecord(db,record.copy(processingPolicy=policy,stale=record.brief.isNotBlank()))
    }
    /** Low-level fixture/import path. A separate writer must revoke work whose
     * known-finding input could now differ. Reader work uses commitReviewedUnit. */
    @Synchronized fun commitUnit(owner: String,outcome: ContextUnitOutcome)=transaction {db->
        db.delete("review_claims","owner=?",arrayOf(owner));commitUnit(db,owner,outcome)
    }
    private fun commitUnit(db:SQLiteDatabase,owner:String,outcome:ContextUnitOutcome) {
        require(document(owner,outcome.documentId)?.document?.revision==outcome.revision){"The source changed while Context was reading"}
        val affected=projectionIds(db,"SELECT finding FROM finding_sources WHERE owner=? AND unit=?",arrayOf(owner,outcome.unitId)).toMutableSet()
        // Each receipt owns only this unit's evidence. The projection unions
        // provenance; copying its growing history into every repeat is quadratic.
        val receipt=outcome.copy(findings=outcome.findings.mapNotNull {finding->finding.quotes[outcome.documentId]?.let {quote->finding.copy(sources=listOf(outcome.documentId),quotes=mapOf(outcome.documentId to quote),readingQuotes=finding.readingQuotes?.filterKeys {it==outcome.documentId})}})
        db.insertWithOnConflict("units",null,values("owner" to owner,"id" to receipt.unitId,"document" to receipt.documentId,"revision" to receipt.revision,"payload" to gson.toJson(receipt)),SQLiteDatabase.CONFLICT_REPLACE)
        val templates=linkedMapOf<String,ContextFinding>()
        addEvidence(db,owner,receipt,templates,mutableMapOf())
        affected.addAll(templates.keys)
        refreshProjection(db,owner,affected,templates)
    }
    /** Validate prepared input once per bounded window, then use small,
     * revocable tokens for every unit and the final completed-source marker. */
    @Synchronized fun beginReviews(owner:String,session:ContextReviewSession,inputs:List<ContextPreparedReview>):Map<String,String>?=transaction {db->
        require(inputs.size<=8&&inputs.map {it.document.id}.distinct().size==inputs.size)
        val record=record(owner) ?: return@transaction null
        if(record.snapshot.revision!=session.snapshot||record.processingPolicy!=session.policy)return@transaction null
        val resolver=ContextBackgroundResolver(this,owner)
        for(input in inputs) {
            val current=document(owner,input.document.id)?.document ?: return@transaction null
            val body=reading(owner,current,"body",current.text) ?: return@transaction null
            val title=reading(owner,current,"title",current.title) ?: return@transaction null
            val background=resolver.cachedReadingBackground(current)
            val fallback=if(background==null&&current.parentText.isNotBlank())reading(owner,current,"parent",current.parentText) ?: return@transaction null else null
            if(!input.sameInput(ContextPreparedReview(current,ContextReadingInput(body,title.text,fallback?.text.orEmpty(),title.languageUnconfirmed,fallback?.languageUnconfirmed==true),background)))return@transaction null
        }
        db.delete("review_claims","owner=?",arrayOf(owner))
        inputs.associate {input->val token=UUID.randomUUID().toString()
            db.insertOrThrow("review_claims",null,values("owner" to owner,"document" to input.document.id,"token" to token,"payload" to gson.toJson(session)))
            input.document.id to token
        }
    }
    private fun currentReview(db:SQLiteDatabase,owner:String,source:String,token:String)=db.rawQuery(
        "SELECT 1 FROM review_claims WHERE owner=? AND document=? AND token=?",arrayOf(owner,source,token)).use {it.moveToFirst()}
    @Synchronized fun reviewsCurrent(owner:String,tokens:Map<String,String>)=tokens.all {(source,token)->currentReview(readableDatabase,owner,source,token)}
    @Synchronized fun commitReviewedUnit(owner:String,outcome:ContextUnitOutcome,token:String):Boolean=transaction {db->
        if(!currentReview(db,owner,outcome.documentId,token))return@transaction false
        commitUnit(db,owner,outcome);true
    }
    @Synchronized fun finishReview(owner:String,source:String,revision:String,token:String):Boolean=transaction {db->
        if(!currentReview(db,owner,source,token))return@transaction false
        val changed=db.update("documents",values("processed" to 1),"owner=? AND id=? AND revision=?",arrayOf(owner,source,revision))==1
        db.delete("review_claims","owner=? AND document=? AND token=?",arrayOf(owner,source,token));changed
    }
    /** A failed interpretation belongs to its exact input too. Validate the
     * whole batch before changing retry markers, leaving edited/replaced work
     * pending rather than deferring it because an older request failed. */
    @Synchronized fun deferReviewedSources(owner:String,failures:List<ContextReviewFailure>):Boolean=transaction {db->
        require(failures.size in 1..8&&failures.map {it.source}.distinct().size==failures.size)
        require(failures.map {it.stream.id}.distinct().size==failures.size)
        for(failure in failures) {
            require(failure.stream.kind=="review"&&failure.stream.work["document"]==failure.source&&failure.stream.work["revision"]==failure.revision)
            if(!currentReview(db,owner,failure.source,failure.token))return@transaction false
            val present=db.rawQuery("SELECT 1 FROM documents WHERE owner=? AND id=? AND revision=?",arrayOf(owner,failure.source,failure.revision)).use {it.moveToFirst()}
            if(!present)return@transaction false
        }
        for(failure in failures) {
            db.update("documents",values("processed" to 2),"owner=? AND id=? AND revision=?",arrayOf(owner,failure.source,failure.revision))
            db.insertWithOnConflict("streams",null,values("owner" to owner,"id" to failure.stream.id,"payload" to gson.toJson(failure.stream)),SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("review_claims","owner=? AND document=? AND token=?",arrayOf(owner,failure.source,failure.token))
        }
        true
    }
    @Synchronized fun releaseReviews(owner:String,tokens:Map<String,String>)=transaction {db->
        tokens.forEach {(source,token)->db.delete("review_claims","owner=? AND document=? AND token=?",arrayOf(owner,source,token))};Unit
    }
    @Synchronized fun processed(owner: String,id: String,revision: String) {writableDatabase.update("documents",values("processed" to 1),"owner=? AND id=? AND revision=?",arrayOf(owner,id,revision));this.revision.value++}
    /** 2 means received but not reviewed. It is never a successful receipt;
     * isolate a malformed model answer so other sources can still advance. */
    @Synchronized fun deferReview(owner: String,id: String,revision: String) {writableDatabase.update("documents",values("processed" to 2),"owner=? AND id=? AND revision=?",arrayOf(owner,id,revision));this.revision.value++}
    @Synchronized fun retryDeferred(owner: String) {writableDatabase.update("documents",values("processed" to 0),"owner=? AND processed=2",arrayOf(owner));revision.value++}
    @Synchronized fun compactions(owner: String): List<ContextSummaryReceipt> = rows("SELECT payload FROM compactions WHERE owner=?",arrayOf(owner),ContextSummaryReceipt::class.java)
    @Synchronized fun commitCompaction(owner: String,receipt: ContextSummaryReceipt) {writableDatabase.insertWithOnConflict("compactions",null,values("owner" to owner,"id" to receipt.id,"payload" to gson.toJson(receipt)),SQLiteDatabase.CONFLICT_REPLACE);revision.value++}
    @Synchronized fun checkpointUnits(owner: String): Int=readableDatabase.rawQuery("SELECT COUNT(*) FROM units u JOIN documents d ON d.owner=u.owner AND d.id=u.document AND d.revision=u.revision WHERE u.owner=?",arrayOf(owner)).use {if(it.moveToFirst())it.getInt(0)else 0}
    @Synchronized fun pruneCompactions(owner: String,keep: Set<String>)=transaction {db->
        val ids=db.rawQuery("SELECT id FROM compactions WHERE owner=?",arrayOf(owner)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        ids.filter {it !in keep}.forEach {db.delete("compactions","owner=? AND id=?",arrayOf(owner,it))}
    }
    /** Current findings are materialized when evidence changes. Empty review
     * receipts never have to be decoded again merely to read this projection. */
    @Synchronized fun findings(owner: String): List<ContextFinding> = rows("SELECT payload FROM findings WHERE owner=? ORDER BY rowid",arrayOf(owner),ContextFinding::class.java)
    private fun projectionIds(db: SQLiteDatabase,sql: String,args: Array<String>): Set<String> = db.rawQuery(sql,args).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}
    private fun invalidateDocument(db: SQLiteDatabase,owner: String,source: String) {
        db.delete("review_claims","owner=? AND document=?",arrayOf(owner,source))
        val affected=projectionIds(db,"SELECT finding FROM finding_sources WHERE owner=? AND source=? UNION SELECT e.finding FROM finding_sources e JOIN units u ON u.owner=e.owner AND u.id=e.unit WHERE u.owner=? AND u.document=?",arrayOf(owner,source,owner,source))
        db.delete("units","owner=? AND document=?",arrayOf(owner,source))
        db.delete("finding_sources","owner=? AND source=?",arrayOf(owner,source))
        refreshProjection(db,owner,affected,emptyMap())
    }
    private fun addEvidence(db: SQLiteDatabase,owner: String,outcome: ContextUnitOutcome,templates: MutableMap<String,ContextFinding>,documents: MutableMap<String,ContextDocument?>) {
        for(finding in outcome.findings) {
            // A receipt attests to its own exact source span. Inherited
            // provenance already has that source's receipt, so duplicating
            // every earlier quote in each repeat would grow quadratically.
            for(source in finding.sources.filter {it==outcome.documentId}.distinct()) {
                val quote=finding.quotes[source] ?: continue
                if(quote.isBlank())continue
                val document=documents.getOrPut(source) {db.rawQuery("SELECT payload FROM documents WHERE owner=? AND id=?",arrayOf(owner,source)).use {if(it.moveToFirst())gson.fromJson(it.getString(0),ContextDocument::class.java)else null}}
                if(document?.text?.contains(quote)!=true)continue
                templates.putIfAbsent(finding.id,finding)
                db.insertWithOnConflict("finding_sources",null,values("owner" to owner,"unit" to outcome.unitId,"finding" to finding.id,"source" to source,"quote" to quote,"reading_quote" to finding.readingQuotes?.get(source).orEmpty()),SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }
    private fun refreshProjection(db: SQLiteDatabase,owner: String,ids: Set<String>,templates: Map<String,ContextFinding>) {
        for(id in ids) {
            val current=db.rawQuery("SELECT payload FROM findings WHERE owner=? AND id=?",arrayOf(owner,id)).use {if(it.moveToFirst())gson.fromJson(it.getString(0),ContextFinding::class.java)else null}
            val template=current ?: templates[id]
            val evidence=db.rawQuery("SELECT source,quote,reading_quote FROM finding_sources WHERE owner=? AND finding=? ORDER BY rowid",arrayOf(owner,id)).use {c->buildMap {while(c.moveToNext())put(c.getString(0),c.getString(1) to c.getString(2))}}
            val quotes=evidence.mapValues {it.value.first}
            if(template==null||quotes.isEmpty())db.delete("findings","owner=? AND id=?",arrayOf(owner,id))
            else {
                val projected=template.copy(sources=quotes.keys.sorted(),quotes=quotes.toSortedMap(),readingQuotes=evidence.mapValues {it.value.second}.filterValues {it.isNotEmpty()}.takeIf {it.isNotEmpty()})
                val payload=gson.toJson(projected)
                if(db.update("findings",values("payload" to payload),"owner=? AND id=?",arrayOf(owner,id))==0)
                    db.insertOrThrow("findings",null,values("owner" to owner,"id" to id,"payload" to payload))
            }
        }
    }
    /** One migration/merge pass bootstraps older receipts. Ordinary reads and
     * newly committed NO_SIGNAL units never scan this historical ledger. */
    private fun rebuildProjection(db: SQLiteDatabase,owner: String) {
        db.delete("finding_sources","owner=?",arrayOf(owner))
        db.delete("findings","owner=?",arrayOf(owner))
        val templates=linkedMapOf<String,ContextFinding>()
        val documents=mutableMapOf<String,ContextDocument?>()
        db.rawQuery("SELECT u.payload FROM units u JOIN documents d ON d.owner=u.owner AND d.id=u.document AND d.revision=u.revision WHERE u.owner=? ORDER BY u.rowid",arrayOf(owner)).use {c->while(c.moveToNext())addEvidence(db,owner,gson.fromJson(c.getString(0),ContextUnitOutcome::class.java),templates,documents)}
        refreshProjection(db,owner,templates.keys,templates)
    }
    private fun publicationInput(record:ContextRecord):ContextPublicationInput {
        val findings=findings(record.id)
        val sources=findings.flatMap {it.sources}.distinct()
        // Batch below API26's bind limit instead of hydrating one source per
        // query. Only cited sources participate in this publication snapshot.
        val documents=sources.chunked(400).flatMap {part->readableDatabase.rawQuery(
            "SELECT payload,processed FROM documents WHERE owner=? AND id IN (${part.joinToString(","){"?"}})",
            (listOf(record.id)+part).toTypedArray()).use {c->buildList {while(c.moveToNext())add(ContextStoredDocument(gson.fromJson(c.getString(0),ContextDocument::class.java),c.getInt(1)==1))}}}
        val byId=documents.associateBy {it.document.id}
        val reviewUnits=mutableMapOf<String,MutableSet<String>>()
        for(part in sources.chunked(400))readableDatabase.rawQuery(
            "SELECT DISTINCT e.source,e.unit FROM finding_sources e JOIN units u ON u.owner=e.owner AND u.id=e.unit AND u.document=e.source JOIN documents d ON d.owner=u.owner AND d.id=u.document AND d.revision=u.revision WHERE e.owner=? AND e.source IN (${part.joinToString(","){"?"}})",
            (listOf(record.id)+part).toTypedArray()).use {c->while(c.moveToNext())reviewUnits.getOrPut(c.getString(0)){linkedSetOf()}.add(c.getString(1))}
        val reviews=reviewUnits.mapNotNull {(source,ids)->ContextReviewIdentity.of(ids)?.let {source to it}}.toMap()
        val linked=documentsForContextKeys(record.id,documents.flatMap {ContextSources.parents(record.id,it.document)})
        val resolver=ContextBackgroundResolver(this,record.id) {linked[it]}
        val entries=documents.mapNotNull {item->val doc=item.document
            val original=resolver.background(doc)?.reply ?: return@mapNotNull null
            val background=resolver.cachedReadingBackground(doc)
            val parent=doc.metadata["contextParent"]?.let {linked[it]}
            val frame=ContextReplyContext.from(background,listOfNotNull(parent?.id),doc.metadata["contextParent"] ?: doc.id) ?: return@mapNotNull null
            doc.id to frame.copy(languagePending=!original.unavailable&&original.text.isNotBlank()&&background?.reply?.unavailable==true)
        }.toMap()
        val parentIds=entries.values.flatMap {it.sources}.toSet()-byId.keys
        val evidence=ContextReplyEvidence(ContextReplyContexts.collect(entries),linked.values.filterNotNull().filter {it.id in parentIds}.distinctBy {it.id}.sortedBy {it.id})
        return ContextPublicationInput(record,findings,sources.mapNotNull(byId::get),reviews,evidence)
    }
    @Synchronized fun publicationInput(owner:String):ContextPublicationInput? {
        // A database transaction also excludes writers using another helper
        // instance; the Kotlin monitor alone only protects this instance.
        val db=writableDatabase;db.beginTransactionNonExclusive()
        return try {record(owner)?.let(::publicationInput).also {db.setTransactionSuccessful()}}finally {db.endTransaction()}
    }
    private fun liveView(record: ContextRecord): ContextView {
        val input=publicationInput(record)
        return ContextView(record,input.findings,input.documents,streams(record.id),input.reviewIdentities,input.replyEvidence)
    }
    /** Current validated findings have their own immutable source copies even
     * while a previous brief's publication remains protected from replacement. */
    @Synchronized fun readingView(owner:String):ContextView?=publicationInput(owner)?.let {
        ContextView(it.record,it.findings,it.documents,streams(owner),it.reviewIdentities,it.replyEvidence)
    }
    @Synchronized fun view(owner: String): ContextView?=record(owner)?.let {record->
        val published=rows("SELECT payload FROM publications WHERE owner=?",arrayOf(owner),ContextView::class.java).firstOrNull()
        if(published!=null&&published.record.brief==record.brief)published.copy(record=record,streams=streams(owner))else liveView(record)
    }
    private fun publish(db:SQLiteDatabase,record:ContextRecord,input:ContextPublicationInput) {
        val previous=rows("SELECT payload FROM publications WHERE owner=?",arrayOf(record.id),ContextView::class.java).firstOrNull()
        if(previous!=null&&previous.record.brief.isNotBlank()&&previous.record.brief!=record.brief&&
            (record.cycleStartedAt==0L||previous.record.cycleStartedAt!=record.cycleStartedAt||earlierView(record.id)==null))
            db.insertWithOnConflict("earlier_publications",null,values("owner" to record.id,"payload" to gson.toJson(previous)),SQLiteDatabase.CONFLICT_REPLACE)
        putRecord(db,record.copy(stale=false))
        val published=ContextView(record.copy(stale=false),input.findings,input.documents,streams(record.id),input.reviewIdentities,input.replyEvidence)
        db.insertWithOnConflict("publications",null,values("owner" to record.id,"payload" to gson.toJson(published)),SQLiteDatabase.CONFLICT_REPLACE)
        if(record.saved&&record.brief.isNotBlank())db.insertWithOnConflict("saved_snapshots",null,values("owner" to record.id,"payload" to gson.toJson(published)),SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun publish(record:ContextRecord)=transaction {db->publish(db,record,publicationInput(record))}
    @Synchronized fun publishIfCurrent(record: ContextRecord,expectedRevision: String,expectedInput:ContextPublicationInput): Boolean =transaction {db->
        val current=record(record.id) ?: return@transaction false
        if(current.snapshot.revision!=expectedRevision)return@transaction false
        val input=publicationInput(current)
        if(!expectedInput.sameEvidence(input))return@transaction false
        // Saving, scheduling and reading choices can change while the model is
        // preparing its reply. Publish only the generated fields over current
        // reader state, never the stale record captured before that wait.
        publish(db,current.copy(brief=record.brief,updatedAt=record.updatedAt,findingStamp=record.findingStamp,
            model=record.model,compositionPolicy=record.compositionPolicy,briefFindingIds=record.briefFindingIds,briefSignalStamp=record.briefSignalStamp,briefSources=record.briefSources),input)
        true
    }
    @Synchronized fun protect(owner: String,saved: Boolean) {
        val r=record(owner) ?: return
        transaction {db->putRecord(db,r.copy(saved=saved));if(saved&&r.brief.isNotBlank())db.insertWithOnConflict("saved_snapshots",null,values("owner" to owner,"payload" to gson.toJson(view(owner))),SQLiteDatabase.CONFLICT_IGNORE)else if(!saved)db.delete("saved_snapshots","owner=?",arrayOf(owner))}
    }
    @Synchronized fun savedView(owner: String): ContextView?=rows("SELECT payload FROM saved_snapshots WHERE owner=?",arrayOf(owner),ContextView::class.java).firstOrNull()
    @Synchronized fun earlierView(owner:String):ContextView?=rows("SELECT payload FROM earlier_publications WHERE owner=?",arrayOf(owner),ContextView::class.java).firstOrNull()
    @Synchronized fun delete(owner: String) {writableDatabase.delete("contexts","id=?",arrayOf(owner));revision.value++}
    /** Cleanup may wait on the separate post-cache database. Never apply its
     * old eligibility decision to a newly changed or protected record. */
    @Synchronized fun deleteIfUnchanged(expected: ContextRecord): Boolean {
        if(expected.saved||record(expected.id)!=expected)return false
        val deleted=writableDatabase.delete("contexts","id=?",arrayOf(expected.id))>0
        if(deleted)revision.value++
        return deleted
    }
    @Synchronized fun logicalBytes(saved: Boolean): Long=readableDatabase.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM context_bytes WHERE saved=?",arrayOf(if(saved)"1"else "0")).use {if(it.moveToFirst())it.getLong(0)else 0L}
}
