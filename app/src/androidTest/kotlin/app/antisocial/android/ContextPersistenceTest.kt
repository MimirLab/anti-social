/* AntiSocial, GPL-3.0-or-later. Real SQLite persistence with isolated local fixtures. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContextPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val databaseName = "context-persistence-$suffix.db"
    private lateinit var store: ContextStore
    private fun post(index: Int) = Post("persistence-$suffix-$index", Platform.X,
        "https://x.com/context_persistence/status/991${suffix.take(10)}$index", "Workshop $index", "Repair announcement $index.")
    private val first by lazy { post(1) }
    private val second by lazy { post(2) }
    private fun snapshot(vararg roots: Post) = ContextSnapshot(roots.toList(), roots.joinToString("\n") { it.body }, "", roots.joinToString("|") { it.id })
    private fun document(id: String, root: Post, kind: String = "comment", text: String = "The original connector is working.") =
        ContextDocument(id, "r1", text, author = "Jamie", url = root.canonicalUrl, kind = kind, metadata = mapOf("root" to CrossPosts.originalKey(root)))
    private fun receipt(owner: String, document: ContextDocument, id: String = "unit-${document.id}"): ContextUnitOutcome {
        val finding = ContextFinding("finding-${document.id}", "An account reports: ${document.text}", "experience", listOf(document.id), mapOf(document.id to document.text))
        return ContextUnitOutcome(id, document.id, document.revision, 0, document.text.length, ContextUnitStatus.SIGNAL, listOf(finding)).also {
            store.commitUnit(owner, it); store.processed(owner, document.id, document.revision)
        }
    }
    @Before fun createDatabase() { store = ContextStore(context, databaseName) }
    @After fun removeDatabase() { if (::store.isInitialized) store.close(); context.deleteDatabase(databaseName) }

    @Test fun editedParentsInvalidateTheirRepliesWithoutReplayingUnrelatedCommentsOrLosingThePublication() {
        val record=store.resolve(snapshot(first))
        val post=document(ContextSources.post(record.id),first,"post","The workshop is open for repairs.")
        val parent=document("parent",first,text="The front entrance is closed.").copy(metadata=mapOf("contextKey" to "parent-key"))
        val child=document("child",first,text="Use the rear courtyard.").copy(metadata=mapOf("contextParent" to "parent-key"))
        val other=document("unrelated",first,text="Bring the service manual.")
        store.ingest(record.id,listOf(post,parent,child,other));listOf(parent,child,other).forEach {receipt(record.id,it)}
        store.publish(record.copy(brief="Original explanation.",findingStamp="before"))
        store.ingest(record.id,listOf(parent.copy(revision="r2",text="The front entrance has reopened.")))
        assertFalse(store.document(record.id,child.id)!!.processed)
        assertTrue(store.outcomes(record.id,child.id,child.revision).isEmpty())
        assertTrue(store.document(record.id,other.id)!!.processed)
        assertEquals(1,store.outcomes(record.id,other.id,other.revision).size)
        assertEquals(child.text,store.view(record.id)!!.documents.first {it.document.id==child.id}.document.text)
        assertTrue(store.record(record.id)!!.stale)
        val reply=ContextBackgroundResolver(store,record.id).background(child)!!
        assertEquals("The front entrance has reopened.",reply.reply!!.text)
        store.ingest(record.id,listOf(post.copy(revision="r2",text="The workshop has moved.")))
        assertFalse(store.document(record.id,other.id)!!.processed)
        assertAccounting()
    }

    @Test fun aLateParentReopensAnEarlierNoSignalReviewAndAmbiguousKeysNeverChooseASource() {
        val record=store.resolve(snapshot(first));val post=document(ContextSources.post(record.id),first,"post")
        val child=document("late-child",first).copy(metadata=mapOf("contextParent" to "late-key"))
        store.ingest(record.id,listOf(post,child))
        assertTrue(ContextBackgroundResolver(store,record.id).background(child)!!.unavailable)
        store.commitUnit(record.id,ContextUnitOutcome("no-signal",child.id,child.revision,0,child.text.length,ContextUnitStatus.NO_SIGNAL,emptyList()))
        store.processed(record.id,child.id,child.revision)
        val parent=document("arriving-parent",first,text="The charger must be unplugged first.").copy(metadata=mapOf("contextKey" to "late-key"))
        store.ingest(record.id,listOf(parent))
        assertFalse(store.document(record.id,child.id)!!.processed);assertEquals(0,store.checkpointUnits(record.id))
        assertEquals(parent.text,ContextBackgroundResolver(store,record.id).background(child)!!.reply!!.text)
        store.ingest(record.id,listOf(parent.copy(id="ambiguous-parent",text="Different source sharing this key.")))
        assertNull(store.documentForContextKey(record.id,"late-key"))
        assertTrue(ContextBackgroundResolver(store,record.id).background(child)!!.unavailable)
        assertAccounting()
    }

    @Test fun freshnessUpdatesKeepReviewsWhileAttributionOrEvidenceQualificationsInvalidateThem() {
        val record=store.resolve(snapshot(first));val doc=document("freshness",first).copy(metadata=mapOf("etag" to "old"))
        store.ingest(record.id,listOf(doc));val done=receipt(record.id,doc)
        val fresh=doc.copy(fetchedAt=12345,url="https://x.com/updated_source/status/991123",metadata=mapOf("etag" to "new","lastModified" to "later"))
        store.ingest(record.id,listOf(fresh))
        assertEquals(fresh,store.document(record.id,doc.id)!!.document)
        assertTrue(store.document(record.id,doc.id)!!.processed);assertEquals(listOf(done),store.outcomes(record.id,doc.id,doc.revision))
        store.ingest(record.id,listOf(fresh.copy(author="Corrected attribution",metadata=fresh.metadata+mapOf("uncertain" to "true"))))
        assertFalse(store.document(record.id,doc.id)!!.processed);assertTrue(store.findings(record.id).isEmpty())
        assertAccounting()
    }

    @Test fun largeSubjectsAreStoredOnceAndTheirResolvedSourceObjectIsShared() {
        val record=store.resolve(snapshot(first));val text="Useful background. ".repeat(4000)
        val post=document(ContextSources.post(record.id),first,"post",text)
        val replies=List(100){document("small-reply-$it",first,text="A short public observation $it.")}
        store.ingest(record.id,listOf(post)+replies)
        val resolver=ContextBackgroundResolver(store,record.id)
        assertSame(resolver.background(replies[0])!!.post,resolver.background(replies[1])!!.post)
        assertTrue(replies.all {store.document(record.id,it.id)!!.document.parentText.isEmpty()})
        assertTrue("Subject text must not be duplicated into every reply",store.logicalBytes(false)<text.length*3L)
        assertAccounting()
    }

    @Test fun versionThreeMigrationBuildsRelationshipsWithoutDestroyingSavedReceiptsOrByteAccounting() {
        val record=store.resolve(snapshot(first));val post=document(ContextSources.post(record.id),first,"post")
        val child=document("v3-child",first);store.ingest(record.id,listOf(post,child));val done=receipt(record.id,child)
        store.publish(record.copy(brief="Preserved version three explanation."));store.protect(record.id,true)
        store.close()
        context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use {db->
            for((table,column) in listOf("context_source_relations" to "parentKey","context_source_keys" to "sourceKey")) {
                db.execSQL("UPDATE context_bytes SET bytes=bytes-COALESCE((SELECT SUM(length(CAST($column AS BLOB))) FROM $table WHERE owner=context_bytes.owner),0)")
                db.execSQL("DROP TABLE $table")
            }
            db.execSQL("DROP TABLE review_claims")
            db.execSQL("DROP TABLE earlier_publications");db.version=3
        }
        store=ContextStore(context,databaseName)
        assertEquals(7,store.readableDatabase.version)
        assertEquals(done,store.outcomes(record.id,child.id,child.revision).single())
        assertTrue(store.document(record.id,child.id)!!.processed)
        assertEquals(post.text,ContextBackgroundResolver(store,record.id).background(child)!!.post!!.text)
        assertEquals("Preserved version three explanation.",store.savedView(record.id)!!.record.brief)
        assertAccounting()
        store.ingest(record.id,listOf(post.copy(revision="r2",text="The source post has changed.")))
        assertFalse(store.document(record.id,child.id)!!.processed);assertAccounting()
    }

    @Test fun updatingTheParentRecordDoesNotCascadeItsSourceReceiptsOrStreams() {
        val record = store.resolve(snapshot(first)); val doc = document("source-a", first)
        store.ingest(record.id, listOf(doc)); val outcome = receipt(record.id, doc)
        store.stream(record.id, ContextStream("discussion-a", first, status = "done", count = 1))
        store.save(record.copy(status = "paused", message = "Progress saved", checkedAt = 1234))
        assertEquals(outcome, store.outcomes(record.id, doc.id, doc.revision).single())
        assertTrue(store.document(record.id, doc.id)!!.processed)
        assertEquals(1, store.streams(record.id).single().count)
        assertEquals(record.id, store.resolve(snapshot(first)).id)
    }

    @Test fun reopeningTheDatabaseRetainsSourcesUnitsContinuationAndCompactionReceipts() {
        val record = store.resolve(snapshot(first)); val doc = document("reopen-source", first)
        store.ingest(record.id, listOf(doc)); val outcome = receipt(record.id, doc)
        val continuation = ContextStream("continuation", first, previous = Page(emptyList(), next = "cursor-two"),
            cursors = setOf("cursor-two"), pendingParents = listOf("reply-a"), count = 1,
            work = mapOf("mediaIndex" to "2"))
        store.stream(record.id, continuation)
        val summary = ContextSummaryReceipt("compaction-a", listOf(outcome.unitId), "The connector works.", listOf(doc.id), listOf(outcome.findings.single().id), 1)
        store.commitCompaction(record.id, summary)
        store.publish(record.copy(brief = "The connector works.", findingStamp = "published")); store.protect(record.id, true)
        store.close(); store = ContextStore(context, databaseName)
        assertEquals(doc, store.document(record.id, doc.id)!!.document)
        assertEquals(outcome, store.outcomes(record.id, doc.id, doc.revision).single())
        assertEquals(continuation, store.streams(record.id).single())
        assertEquals(summary, store.compactions(record.id).single())
        assertEquals("The connector works.", store.savedView(record.id)!!.record.brief)
    }

    @Test fun abbreviatedProviderEvidenceRestoresItsOmittedContextAndSurvivesReopening():Unit=runBlocking {
        val record=store.resolve(snapshot(first))
        val opening="Reseating a connector is not a remedy for a damaged cell."
        val ending="I have not inspected this replacement batch"
        val source=document("omitted-provider-quote",first,text="$opening This is general safety advice; $ending and am not claiming that the entire batch is defective.")
        store.ingest(record.id,listOf(source))
        var calls=0
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=256
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                calls++
                return """{"status":"signal","findings":[{"text":"The volunteer has not inspected the batch.","kind":"context","quote":"$opening … $ending"}]}"""
            }
        }
        val result=ContextEngine(model).process(listOf(source),onUnit={store.commitUnit(record.id,it)})
        store.processed(record.id,source.id,source.revision)
        val quote="$opening This is general safety advice; $ending"
        assertEquals(quote,result.findings.single().quotes[source.id])
        store.close();store=ContextStore(context,databaseName)
        val restored=store.outcomes(record.id,source.id,source.revision)
        assertEquals(quote,restored.single().findings.single().quotes[source.id])
        assertTrue(source.text.contains(quote))
        ContextEngine(model).process(listOf(source),store.findings(record.id),restored)
        assertEquals("A restored exact source receipt must not regenerate",1,calls)
    }

    @Test fun editingASourceInvalidatesCurrentFindingsButKeepsThePublishedExactQuote() {
        val record = store.resolve(snapshot(first)); val doc = document("edited-source", first)
        store.ingest(record.id, listOf(doc)); receipt(record.id, doc)
        store.publish(record.copy(brief = "The original connector works.", findingStamp = "old-publication"))
        store.ingest(record.id, listOf(doc.copy(revision = "r2", text = "The account has withdrawn its earlier claim.")))
        assertTrue(store.findings(record.id).isEmpty()); assertEquals(0, store.checkpointUnits(record.id))
        assertEquals("r2", store.document(record.id, doc.id)!!.document.revision)
        val publication = store.view(record.id)!!
        assertTrue(publication.record.stale)
        assertEquals("The original connector works.", publication.record.brief)
        assertEquals(doc.text, publication.documents.single().document.text)
        assertEquals(doc.text, publication.findings.single().quotes[doc.id])
    }

    @Test fun splittingACardKeepsOnlyItsOwnOriginalAndNeverTheOldAggregate() {
        val aggregate = store.resolve(snapshot(first, second))
        val combined = document("merged:${aggregate.id}", first, "post", "Both original announcements together.")
        val sourceA = document("comment-a", first); val sourceB = document("comment-b", second, text = "A different publisher's evidence.")
        store.ingest(aggregate.id, listOf(combined, sourceA, sourceB))
        listOf(combined, sourceA, sourceB).forEach { receipt(aggregate.id, it) }
        store.publish(aggregate.copy(brief = "A combined old publication."))
        val split = store.resolve(snapshot(first))
        assertNotEquals(aggregate.id, split.id); assertTrue(split.brief.isBlank())
        assertEquals(listOf(sourceA.id), store.documents(split.id).map { it.document.id })
        assertEquals(listOf(sourceA.id), store.findings(split.id).flatMap { it.sources })
        assertEquals(1, store.checkpointUnits(split.id))
        assertEquals(3, store.documents(aggregate.id).size)
    }

    @Test fun mergingOriginalCardsReusesTheirCurrentSourceReceiptsAndUnifiesAliases() {
        val oldA = store.resolve(snapshot(first)); val oldB = store.resolve(snapshot(second))
        val sourceA = document("separate-a", first); val sourceB = document("separate-b", second, text = "Another source confirms the part number.")
        store.ingest(oldA.id, listOf(sourceA)); receipt(oldA.id, sourceA)
        store.ingest(oldB.id, listOf(sourceB)); receipt(oldB.id, sourceB)
        val merged = store.resolve(snapshot(first, second))
        assertEquals(setOf(sourceA.id, sourceB.id), store.documents(merged.id).map { it.document.id }.toSet())
        assertTrue(store.documents(merged.id).all { it.processed }); assertEquals(2, store.checkpointUnits(merged.id))
        assertEquals(setOf(sourceA.id, sourceB.id), store.findings(merged.id).flatMap { it.sources }.toSet())
        assertEquals(merged.id, store.resolve(snapshot(first, second)).id)
        listOf(first, second).forEach { root ->
            store.readableDatabase.rawQuery("SELECT owner FROM aliases WHERE alias=?", arrayOf("current|${CrossPosts.originalKey(root)}")).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(merged.id, cursor.getString(0))
            }
        }
        assertAccounting()
        store.ingest(merged.id,listOf(sourceB.copy(revision="r2",text="The second publisher withdrew its earlier evidence.")))
        assertEquals(listOf(sourceA.id),store.findings(merged.id).flatMap {it.sources})
        assertEquals(listOf(sourceB.id),store.findings(oldB.id).flatMap {it.sources})
        store.close();store=ContextStore(context,databaseName)
        assertEquals(listOf(sourceA.id),store.findings(merged.id).flatMap {it.sources});assertAccounting()
    }

    @Test fun savedPublicationIsAnImmutableBaselineWhileLatestContextCanChange() {
        val saved = store.resolve(snapshot(first)); val ordinary = store.resolve(snapshot(second))
        val doc = document("saved-source", first); store.ingest(saved.id, listOf(doc)); receipt(saved.id, doc)
        store.publish(saved.copy(brief = "Saved edition.", findingStamp = "saved-stamp")); store.protect(saved.id, true)
        store.publish(store.record(saved.id)!!.copy(brief = "Latest edition.", findingStamp = "latest-stamp")); store.protect(saved.id, true)
        store.delete(ordinary.id)
        assertNull(store.record(ordinary.id)); assertTrue(store.record(saved.id)!!.saved)
        assertEquals("Latest edition.", store.view(saved.id)!!.record.brief)
        assertEquals("Saved edition.", store.savedView(saved.id)!!.record.brief)
        assertEquals(doc.text, store.savedView(saved.id)!!.documents.single().document.text)
    }

    @Test fun aReceiptFromAnOldRevisionCannotBeCommittedOverAnEditedSource() {
        val record = store.resolve(snapshot(first)); val doc = document("stale-receipt", first)
        store.ingest(record.id, listOf(doc)); val old = receipt(record.id, doc)
        store.ingest(record.id, listOf(doc.copy(revision = "r2", text = "The corrected current source.")))
        assertThrows(IllegalArgumentException::class.java) { store.commitUnit(record.id, old) }
        assertEquals(0, store.checkpointUnits(record.id)); assertTrue(store.findings(record.id).isEmpty())
        assertFalse(store.document(record.id, doc.id)!!.processed)
    }

    @Test fun legacyVersionOneMigrationPreservesEvidenceAndAddsPublicationAndCompactionStorage() {
        store.close(); context.deleteDatabase(databaseName)
        val record = ContextRecord("legacy-owner", snapshot(first), brief = "Legacy reader explanation.")
        val doc = document("legacy-source", first)
        val finding = ContextFinding("legacy-finding", "A connector repair worked.", "experience", listOf(doc.id), mapOf(doc.id to doc.text))
        val outcome = ContextUnitOutcome("legacy-unit", doc.id, doc.revision, 0, doc.text.length, ContextUnitStatus.SIGNAL, listOf(finding))
        val gson = Gson()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE contexts(id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE aliases(alias TEXT PRIMARY KEY NOT NULL,owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE documents(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,revision TEXT NOT NULL,payload TEXT NOT NULL,processed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,id))")
            db.execSQL("CREATE TABLE units(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,document TEXT NOT NULL,revision TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
            db.execSQL("CREATE INDEX units_document ON units(owner,document)")
            db.execSQL("CREATE TABLE findings(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
            db.execSQL("CREATE TABLE streams(owner TEXT NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))")
            db.execSQL("CREATE TABLE saved_snapshots(owner TEXT PRIMARY KEY NOT NULL REFERENCES contexts(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO contexts(id,payload) VALUES(?,?)", arrayOf(record.id, gson.toJson(record)))
            db.execSQL("INSERT INTO documents(owner,id,revision,payload,processed) VALUES(?,?,?,?,1)", arrayOf(record.id, doc.id, doc.revision, gson.toJson(doc)))
            db.execSQL("INSERT INTO units(owner,id,document,revision,payload) VALUES(?,?,?,?,?)", arrayOf(record.id, outcome.unitId, doc.id, doc.revision, gson.toJson(outcome)))
            db.version = 1
        }
        store = ContextStore(context, databaseName)
        assertEquals(record, store.record(record.id)); assertTrue(store.document(record.id, doc.id)!!.processed)
        assertEquals(outcome, store.outcomes(record.id, doc.id, doc.revision).single())
        val compacted = ContextSummaryReceipt("migrated-compaction", listOf(outcome.unitId), record.brief, listOf(doc.id), listOf(finding.id), 1)
        store.commitCompaction(record.id, compacted); store.publish(record.copy(findingStamp = "migrated"))
        assertEquals(compacted, store.compactions(record.id).single()); assertEquals(doc.text, store.view(record.id)!!.documents.single().document.text)
        assertEquals(listOf(finding.id), store.findings(record.id).map { it.id })
        assertAccounting()
        assertEquals(7, store.readableDatabase.version)
    }

    @Test fun noisyThreadsReadOnlyMaterializedSignalAndKeepConstantSizeBudgetChecks() {
        val record=store.resolve(snapshot(first));val signal=document("useful-source",first)
        store.ingest(record.id,listOf(signal));receipt(record.id,signal)
        val expected=store.findings(record.id)
        val noise=(0 until 1000).map {document("noise-$it",first,text="lol")}
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            store.ingest(record.id,noise)
            noise.forEachIndexed {index,d->
                store.commitUnit(record.id,ContextUnitOutcome("noise-unit-$index",d.id,d.revision,0,d.text.length,ContextUnitStatus.NO_SIGNAL,emptyList()))
                store.processed(record.id,d.id,d.revision)
                assertEquals(expected,store.findings(record.id))
                assertTrue(store.logicalBytes(false)>0)
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        assertEquals(1001,store.checkpointUnits(record.id))
        db.rawQuery("SELECT COUNT(*) FROM finding_sources WHERE owner=?",arrayOf(record.id)).use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        // These old empty receipts and uncited source bodies must never be
        // decoded merely to read current signal or account for storage.
        db.execSQL("UPDATE units SET payload='not-json' WHERE owner=? AND document LIKE 'noise-%'",arrayOf(record.id))
        db.execSQL("UPDATE documents SET payload='not-json' WHERE owner=? AND id LIKE 'noise-%'",arrayOf(record.id))
        assertTrue(store.pendingDocuments(record.id).isEmpty())
        val pending=document("still-unread",first,text="A newly returned source remains unread.")
        store.ingest(record.id,listOf(pending))
        assertEquals(listOf(ContextStoredDocument(pending,false)),store.pendingDocuments(record.id))
        db.rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM documents WHERE owner=? AND processed=0 ORDER BY rowid",arrayOf(record.id)).use {c->
            val details=buildList {while(c.moveToNext())add(c.getString(3))}.joinToString(" ")
            assertTrue("Pending work must use its owner/processed index: $details",details.contains("documents_pending"))
        }
        repeat(20){assertEquals(expected,store.findings(record.id));assertTrue(store.logicalBytes(false)>0)}
        store.publish(record.copy(brief="A useful repair report."))
        assertEquals(listOf(signal.id),store.view(record.id)!!.documents.map {it.document.id})
        assertAccounting()
        store.close();store=ContextStore(context,databaseName)
        assertEquals(expected,store.findings(record.id));assertEquals(listOf(ContextStoredDocument(pending,false)),store.pendingDocuments(record.id));assertAccounting()
    }

    @Test fun repeatedProvenanceIsBoundedAndEditsOrReceiptReplacementUpdateOnlyItsSupport() {
        val record=store.resolve(snapshot(first));val a=document("support-a",first);val b=document("support-b",first)
        store.ingest(record.id,listOf(a,b))
        val finding=ContextFinding("shared-finding","Two accounts report the original connector is working.","experience",listOf(a.id),mapOf(a.id to a.text))
        val unitA=ContextUnitOutcome("unit-a",a.id,a.revision,0,a.text.length,ContextUnitStatus.SIGNAL,listOf(finding))
        val unitB=ContextUnitOutcome("unit-b",b.id,b.revision,0,b.text.length,ContextUnitStatus.REPEATED,listOf(finding.copy(sources=listOf(a.id,b.id),quotes=mapOf(a.id to a.text,b.id to b.text))))
        store.commitUnit(record.id,unitA);store.commitUnit(record.id,unitB)
        assertEquals(setOf(a.id,b.id),store.findings(record.id).single().sources.toSet())
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM finding_sources WHERE owner=?",arrayOf(record.id)).use {assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0))}
        store.publish(record.copy(brief="The original connector is working."));store.protect(record.id,true)
        val changed=a.copy(revision="r2",text="That account withdrew its earlier claim.")
        store.ingest(record.id,listOf(changed))
        assertEquals(listOf(b.id),store.findings(record.id).single().sources)
        assertEquals(b.text,store.findings(record.id).single().quotes[b.id])
        assertEquals(setOf(a.id,b.id),store.view(record.id)!!.findings.single().sources.toSet())
        assertEquals(a.text,store.savedView(record.id)!!.documents.single {it.document.id==a.id}.document.text)
        store.commitUnit(record.id,unitB.copy(status=ContextUnitStatus.NO_SIGNAL,findings=emptyList()))
        assertTrue(store.findings(record.id).isEmpty())
        receipt(record.id,changed)
        assertEquals(changed.text,store.findings(record.id).single().quotes[a.id])
        // Treat a changed body conservatively even if a source reuses its revision.
        store.ingest(record.id,listOf(changed.copy(text="The earlier statement was removed.")))
        assertTrue(store.findings(record.id).isEmpty());assertAccounting()
    }

    @Test fun invalidQuotesNeverEnterTheProjectionAndMediaInvalidationKeepsOtherEvidence() {
        val record=store.resolve(snapshot(first));val comment=document("comment",first)
        val image=document("image-text",first,kind="image",text="Visible repair instructions.").copy(metadata=mapOf("root" to CrossPosts.originalKey(first),"resource" to "https://media.example/image","sourceSha256" to "old-sha"))
        store.ingest(record.id,listOf(comment,image));receipt(record.id,comment);receipt(record.id,image)
        val forged=ContextFinding("forged","An unsupported claim.","context",listOf(comment.id,"missing"),mapOf(comment.id to "This quote does not occur.","missing" to "Invented evidence."))
        store.commitUnit(record.id,ContextUnitOutcome("forged-unit",comment.id,comment.revision,0,comment.text.length,ContextUnitStatus.SIGNAL,listOf(forged)))
        assertFalse(store.findings(record.id).any {it.id==forged.id})
        store.publish(record.copy(brief="The repair has text and image evidence."));store.protect(record.id,true)
        store.invalidateMedia(record.id,"https://media.example/image","new-sha")
        assertEquals(listOf(comment.id),store.findings(record.id).flatMap {it.sources})
        assertNull(store.document(record.id,image.id));assertEquals(2,store.savedView(record.id)!!.documents.size)
        assertEquals(image.text,store.view(record.id)!!.documents.single {it.document.id==image.id}.document.text)
        assertAccounting()
    }

    @Test fun byteAccountingTracksReplacementsProtectionRollbackAndCascadingDeletion() {
        val record=store.resolve(snapshot(first));assertAccounting()
        val doc=document("accounted",first,text="A repair observation with unicode 🌱.")
        store.ingest(record.id,listOf(doc));val unit=receipt(record.id,doc);assertAccounting()
        val before=store.logicalBytes(false);store.commitUnit(record.id,unit)
        assertEquals(before,store.logicalBytes(false));assertAccounting()
        store.stream(record.id,ContextStream("same-stream",first,count=1));store.stream(record.id,ContextStream("same-stream",first,count=120,message="More entries have arrived."));assertAccounting()
        val compacted=ContextSummaryReceipt("same-summary",listOf(unit.unitId),"A repair observation.",listOf(doc.id),unit.findings.map {it.id},1)
        store.commitCompaction(record.id,compacted);store.commitCompaction(record.id,compacted.copy(text="A longer and more useful repair observation."));assertAccounting()
        store.publish(record.copy(brief="The repair worked."));store.protect(record.id,true);assertAccounting()
        assertEquals(0L,store.logicalBytes(false));assertTrue(store.logicalBytes(true)>0)
        val invalid=unit.copy(revision="stale")
        val protectedBytes=store.logicalBytes(true)
        assertThrows(IllegalArgumentException::class.java){store.commitUnit(record.id,invalid)}
        assertEquals(protectedBytes,store.logicalBytes(true));assertAccounting()
        store.removeStream(record.id,"same-stream");store.pruneCompactions(record.id,emptySet());assertAccounting()
        store.protect(record.id,false);assertAccounting();assertEquals(0L,store.logicalBytes(true))
        store.delete(record.id);assertEquals(0L,store.logicalBytes(false));assertEquals(0L,store.logicalBytes(true))
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM finding_sources",emptyArray()).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }

    @Test fun versionTwoReceiptBootstrapRunsOnceAndPreservesPublishedSourceCopies() {
        val record=store.resolve(snapshot(first));val doc=document("v2-source",first)
        store.ingest(record.id,listOf(doc));receipt(record.id,doc);store.publish(record.copy(brief="A version two explanation."));store.protect(record.id,true)
        val expected=store.findings(record.id);store.close()
        context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use {db->
            val triggers=db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger'",emptyArray()).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
            triggers.forEach {db.execSQL("DROP TRIGGER \"$it\"")}
            db.execSQL("DROP TABLE context_source_relations");db.execSQL("DROP TABLE context_source_keys");db.execSQL("DROP TABLE finding_sources");db.execSQL("DROP TABLE context_bytes");db.execSQL("DELETE FROM findings");db.execSQL("DROP TABLE review_claims");db.execSQL("DROP TABLE earlier_publications");db.version=2
        }
        store=ContextStore(context,databaseName)
        assertEquals(expected,store.findings(record.id));assertEquals(doc.text,store.savedView(record.id)!!.documents.single().document.text)
        assertEquals(7,store.readableDatabase.version);assertAccounting()
    }

    @Test fun savingBeforeGenerationProtectsTheFirstPublicationAndRejectsAnOutdatedPublisher() {
        val record=store.resolve(snapshot(first))
        store.protect(record.id,true)
        assertNull(store.savedView(record.id))
        val firstPublication=store.record(record.id)!!.copy(brief="The first usable saved Context.")
        val input=store.publicationInput(record.id)!!
        assertTrue(store.publishIfCurrent(firstPublication,record.snapshot.revision,input))
        store.publish(store.record(record.id)!!.copy(brief="Later live additions."))
        assertEquals(firstPublication.brief,store.savedView(record.id)!!.record.brief)
        val changed=record.snapshot.copy(revision="changed-while-publishing",text="A revised source post.")
        store.resolve(changed)
        assertFalse(store.publishIfCurrent(firstPublication,record.snapshot.revision,input))
        assertEquals(changed.revision,store.record(record.id)!!.snapshot.revision)
        assertAccounting()
    }

    @Test fun publicationGuardRejectsSourceEditsFromAnotherDatabaseHelperAndKeepsTheSavedCopy() {
        val record=store.resolve(snapshot(first));val source=document("inflight-source",first)
        store.ingest(record.id,listOf(source));receipt(record.id,source)
        store.publish(record.copy(brief="The previous coherent interpretation."));store.protect(record.id,true)
        val input=store.publicationInput(record.id)!!;val before=store.view(record.id)!!;val saved=store.savedView(record.id)!!
        val other=ContextStore(context,databaseName)
        try {other.ingest(record.id,listOf(source.copy(revision="r2",text="The original connector has now failed.")))}finally {other.close()}
        assertFalse(store.publishIfCurrent(input.record.copy(brief="A reply based on r1."),record.snapshot.revision,input))
        assertEquals(before.findings,store.view(record.id)!!.findings)
        assertEquals(before.documents,store.view(record.id)!!.documents)
        assertEquals(before.record.brief,store.record(record.id)!!.brief)
        assertEquals(saved,store.savedView(record.id));assertAccounting()
    }

    @Test fun publicationGuardAllowsFreshProvenanceAndPreservesChoicesMadeWhileInferenceWaited() {
        val record=store.resolve(snapshot(first));val source=document("provenance-source",first)
        store.ingest(record.id,listOf(source));receipt(record.id,source)
        store.publish(record.copy(brief="The earlier brief."))
        val input=store.publicationInput(record.id)!!
        val fresh=source.copy(fetchedAt=123456,url="https://x.com/current_source/status/123",metadata=source.metadata+mapOf("etag" to "new-validator"))
        store.ingest(record.id,listOf(fresh));store.protect(record.id,true)
        val saved=store.savedView(record.id)!!
        store.save(store.record(record.id)!!.copy(requested=false,nextCheck=654321))
        assertTrue(store.publishIfCurrent(input.record.copy(brief="The new coherent brief."),record.snapshot.revision,input))
        val current=store.record(record.id)!!
        assertTrue(current.saved);assertFalse(current.requested);assertEquals(654321L,current.nextCheck)
        assertEquals(fresh,store.view(record.id)!!.documents.single().document)
        assertEquals(saved,store.savedView(record.id));assertAccounting()
    }

    @Test fun publicationGuardChecksReviewedFindingsAndPolicyEvenWhenSourceWordsAreUnchanged() {
        val record=store.resolve(snapshot(first));val source=document("review-source",first)
        store.ingest(record.id,listOf(source));val reviewed=receipt(record.id,source)
        val input=store.publicationInput(record.id)!!
        val revised=reviewed.copy(findings=reviewed.findings.map {it.copy(id="revised-finding",text="The report applies only to this connector.")})
        store.commitUnit(record.id,revised)
        assertEquals(input.documents,store.publicationInput(record.id)!!.documents)
        assertFalse(store.publishIfCurrent(input.record.copy(brief="Old review prose."),record.snapshot.revision,input))
        val next=store.publicationInput(record.id)!!
        store.processingPolicy(record.id,"new-model-recipe")
        assertFalse(store.publishIfCurrent(next.record.copy(brief="Old recipe prose."),record.snapshot.revision,next))
        assertAccounting()
    }

    @Test fun publicationSnapshotRetainsEveryCitedSourceAcrossMultipleBoundedQueriesAndReopening() {
        val record=store.resolve(snapshot(first))
        val sources=(0 until 805).map {document("cited-$it",first,"article","Distinct cited observation $it.")}
        store.ingest(record.id,sources);sources.forEach {receipt(record.id,it)}
        val input=store.publicationInput(record.id)!!
        assertEquals(sources.map {it.id}.toSet(),input.documents.map {it.document.id}.toSet())
        assertEquals(805,input.findings.size)
        assertTrue(store.publishIfCurrent(record.copy(brief="The collected source interpretation."),record.snapshot.revision,input))
        store.close();store=ContextStore(context,databaseName)
        assertEquals(805,store.view(record.id)!!.documents.size)
        assertEquals(input.findings,store.view(record.id)!!.findings);assertAccounting()
    }

    @Test fun aNewParentReviewInvalidatesPublicationEvenWhenChildTextAndFindingsStayIdentical() {
        val record=store.resolve(snapshot(first))
        val parent=document("review-parent",first,"article","The test covered a complete installation.").copy(metadata=mapOf("contextKey" to "review-parent-key"))
        val child=document("review-child",first,text="I followed those test instructions.").copy(metadata=mapOf("contextParent" to "review-parent-key"))
        store.ingest(record.id,listOf(parent,child));val unit=receipt(record.id,child)
        store.publish(record.copy(brief="The earlier parent interpretation."));store.protect(record.id,true)
        val input=store.publicationInput(record.id)!!;val saved=store.savedView(record.id)!!
        store.ingest(record.id,listOf(parent.copy(revision="r2",text="The test covered an isolated prototype.")))
        store.commitUnit(record.id,unit.copy(unitId="review-under-revised-parent"));store.processed(record.id,child.id,child.revision)
        val next=store.publicationInput(record.id)!!
        assertEquals(input.findings,next.findings);assertEquals(input.documents,next.documents)
        assertNotEquals(input.reviewIdentities,next.reviewIdentities)
        assertFalse(store.publishIfCurrent(input.record.copy(brief="An earlier review's delayed reply."),record.snapshot.revision,input))
        assertEquals(saved,store.savedView(record.id));assertAccounting()
    }

    @Test fun anEmptyTailReviewDoesNotTurnUnchangedEvidenceIntoNewPublishedMeaning() {
        val record=store.resolve(snapshot(first));val statement="The connector inspection is complete."
        val source=document("review-tail",first,"article",statement+"\n\nThanks!")
        store.ingest(record.id,listOf(source))
        val finding=ContextFinding("review-tail-finding",statement,"context",listOf(source.id),mapOf(source.id to statement))
        store.commitUnit(record.id,ContextUnitOutcome("signal-prefix",source.id,source.revision,0,statement.length,ContextUnitStatus.SIGNAL,listOf(finding)))
        val input=store.publicationInput(record.id)!!
        store.commitUnit(record.id,ContextUnitOutcome("empty-tail",source.id,source.revision,statement.length+2,source.text.length,ContextUnitStatus.NO_SIGNAL,emptyList()))
        val next=store.publicationInput(record.id)!!
        assertEquals(input.reviewIdentities,next.reviewIdentities)
        assertTrue(input.sameEvidence(next));assertAccounting()
    }

    @Test fun repeatedUsefulReceiptsKeepOnlyTheirOwnSupportWhileProjectionRetainsEverySource() {
        val record=store.resolve(snapshot(first))
        val supports=linkedMapOf<String,String>()
        repeat(100) {index->
            val doc=document("useful-repeat-$index",first,text="The service manual specifies a compatible replacement connector.")
            store.ingest(record.id,listOf(doc));supports[doc.id]=doc.text
            val finding=ContextFinding("same-useful-point",doc.text,"context",supports.keys.toList(),supports.toMap())
            store.commitUnit(record.id,ContextUnitOutcome("repeat-unit-$index",doc.id,doc.revision,0,doc.text.length,ContextUnitStatus.REPEATED,listOf(finding)))
            val receipt=store.outcomes(record.id,doc.id,doc.revision).single()
            assertEquals(listOf(doc.id),receipt.findings.single().sources)
            assertEquals(mapOf(doc.id to doc.text),receipt.findings.single().quotes)
        }
        assertEquals(100,store.findings(record.id).single().sources.size)
        assertEquals(supports,store.findings(record.id).single().quotes)
        assertAccounting()
    }

    @Test fun readingViewsResumeTranslatedChunksAndKeepOriginalAndEnglishEvidenceAcrossReopen() {
        val record=store.resolve(snapshot(first));val original="La batería es de 56 Wh, no de 60 Wh.";val english="The battery is 56 Wh, not 60 Wh."
        val doc=document("spanish-reading",first,text=original);store.ingest(record.id,listOf(doc))
        val translation=ReadingTranslation("<p>$english</p>","offline-fixture")
        store.saveReadingChunk(record.id,doc,"body","a".repeat(64),translation)
        store.close();store=ContextStore(context,databaseName)
        assertEquals(translation,store.readingChunk(record.id,doc,"body","a".repeat(64)))
        val reading=ContextReadingText(ContextReadingText.digest(original),english,listOf(ContextReadingSpan(0,original.length,0,english.length,"es",true)),EnglishReadingPreparer.POLICY+"|offline-fixture")
        store.saveReading(record.id,doc,"body",original,reading)
        assertNull(store.readingChunk(record.id,doc,"body","a".repeat(64)))
        val finding=ContextFinding("translated-finding",english,"warning",listOf(doc.id),mapOf(doc.id to original),mapOf(doc.id to english))
        store.commitUnit(record.id,ContextUnitOutcome("translated-unit",doc.id,doc.revision,0,original.length,ContextUnitStatus.SIGNAL,listOf(finding)))
        store.processed(record.id,doc.id,doc.revision);store.protect(record.id,true);assertAccounting()
        store.close();store=ContextStore(context,databaseName)
        assertEquals(reading,store.reading(record.id,doc,"body",original));assertEquals(english,store.findings(record.id).single().readingQuotes!![doc.id])
        assertEquals(original,store.findings(record.id).single().quotes[doc.id]);assertTrue(store.record(record.id)!!.saved)
        store.ingest(record.id,listOf(doc.copy(revision="changed",text="Nueva versión.")))
        assertNull(store.reading(record.id,doc,"body",original));assertTrue(store.findings(record.id).isEmpty());assertAccounting()
    }
    @Test fun identityReadingCacheDoesNotStoreAnotherCopyOfALargeEnglishSource() {
        val record=store.resolve(snapshot(first));val text="A readable English source. ".repeat(4000);val doc=document("identity-reading",first,text=text)
        store.ingest(record.id,listOf(doc));val before=store.logicalBytes(false)
        val reading=ContextReadingText.identity(text,EnglishReadingPreparer.POLICY)
        store.saveReading(record.id,doc,"body",text,reading)
        assertEquals(reading,store.reading(record.id,doc,"body",text));assertTrue(store.logicalBytes(false)-before<1024);assertAccounting()
        assertNull(store.reading(record.id,doc,"body",text+"edited"))
    }
    @Test fun aNewProcessingPolicyRetainsPagesAndSavedPublicationButReopensOldFindings() {
        val record=store.resolve(snapshot(first));val doc=document("old-processing",first,text="¿Tiene acceso?")
        store.ingest(record.id,listOf(doc));receipt(record.id,doc);store.publish(record.copy(brief="Previously captured explanation."));store.protect(record.id,true)
        store.processingPolicy(record.id,EnglishReadingPreparer.POLICY)
        assertTrue(store.findings(record.id).isEmpty());assertFalse(store.document(record.id,doc.id)!!.processed)
        assertEquals(doc,store.document(record.id,doc.id)!!.document);assertEquals("Previously captured explanation.",store.savedView(record.id)!!.record.brief)
        assertTrue(store.record(record.id)!!.stale);assertAccounting()
        receipt(record.id,doc);store.processingPolicy(record.id,EnglishReadingPreparer.POLICY)
        assertTrue(store.document(record.id,doc.id)!!.processed)
    }

    @Test fun aPublishedReplyKeepsItsEnglishFrameAndOriginalParentAfterEditsAndReopening() {
        val record=store.resolve(snapshot(first))
        val parent=document("parent-original",first,text="¿Cambiaste toda la batería?").copy(metadata=mapOf("contextKey" to "question-alias"))
        val child=document("answer-copy",first,text="No.").copy(metadata=mapOf("contextParent" to "question-alias"))
        store.ingest(record.id,listOf(parent,child))
        val english="Did you replace the entire battery?"
        val reading=ContextReadingText(ContextReadingText.digest(parent.text),english,listOf(ContextReadingSpan(0,parent.text.length,0,english.length,"es",true)),EnglishReadingPreparer.POLICY)
        store.saveReading(record.id,parent,"body",parent.text,reading)
        receipt(record.id,child)
        val input=store.publicationInput(record.id)!!
        assertEquals(english,input.replyEvidence.contexts.source(child.id)!!.text)
        assertEquals(listOf(parent),input.replyEvidence.documents)
        store.publish(record.copy(brief="Jamie answered the replacement question."));store.protect(record.id,true)
        val saved=store.savedView(record.id)!!
        store.processed(record.id,parent.id,parent.revision)
        assertTrue("An empty parent review is bookkeeping, not new meaning",input.sameEvidence(store.publicationInput(record.id)!!))
        store.ingest(record.id,listOf(parent.copy(revision="r2",text="¿Cambiaste el conector?")))
        assertFalse(store.publishIfCurrent(record.copy(brief="Stale answer"),record.snapshot.revision,input))
        store.close();store=ContextStore(context,databaseName)
        assertEquals(saved,store.savedView(record.id));assertEquals(parent,store.view(record.id)!!.replyEvidence!!.documents.single())
        assertEquals(english,store.view(record.id)!!.replyEvidence!!.contexts.source(child.id)!!.text)
        assertAccounting()
    }
    @Test fun absentEnglishPreparationNeverLeaksTheOriginalParentIntoComposition() {
        val record=store.resolve(snapshot(first))
        val parent=document("spanish-parent",first,text="¿Cambiaste toda la batería?")
        val child=document("english-answer",first,text="No.").copy(metadata=mapOf("contextParent" to parent.id))
        store.ingest(record.id,listOf(parent,child));receipt(record.id,child)
        val input=store.publicationInput(record.id)!!;val frame=input.replyEvidence.contexts.source(child.id)!!
        assertTrue(frame.languagePending);assertTrue(frame.unavailable);assertEquals("",frame.text)
        assertEquals(listOf(parent),input.replyEvidence.documents)
        store.saveReading(record.id,parent,"body",parent.text,ContextReadingText(ContextReadingText.digest(parent.text),"Did you replace the entire battery?",listOf(ContextReadingSpan(0,parent.text.length,0,"Did you replace the entire battery?".length,"es",true)),EnglishReadingPreparer.POLICY))
        assertFalse("Prepared context changes the publication guard",input.sameEvidence(store.publicationInput(record.id)!!))
        assertAccounting()
    }

    @Test fun reviewOrderResolvesAliasesGloballyBeforeApplyingAWorkLimit() {
        val record=store.resolve(snapshot(first))
        val parent=document("question",first,text="Did you replace the battery?").copy(metadata=mapOf("contextKey" to "parent-alias"))
        val child=document("answer",first,text="No.").copy(metadata=mapOf("contextParent" to "parent-alias"))
        store.ingest(record.id,listOf(child,parent))
        val preferred=store.pendingDocuments(record.id)
        assertEquals(listOf(child.id,parent.id),preferred.map {it.document.id})
        assertEquals(parent.id,store.reviewOrder(record.id,preferred).take(1).single().document.id)
        val impostor=document("ambiguous",first,text="An unrelated question.").copy(metadata=mapOf("contextKey" to "parent-alias"))
        store.ingest(record.id,listOf(impostor));store.processed(record.id,impostor.id,impostor.revision)
        assertEquals("A processed conflicting alias is still ambiguous",preferred.map {it.document.id},store.reviewOrder(record.id,preferred).map {it.document.id})
        assertAccounting()
    }
    @Test fun reviewOrderBatchesRelationshipQueriesBelowTheAndroidBindLimit() {
        val record=store.resolve(snapshot(first))
        val parents=(1..805).map {document("parent-$it",first,text="Question $it?")}
        val children=parents.map {parent->document("child-${parent.id}",first,text="No.").copy(metadata=mapOf("contextParent" to parent.id))}
        store.ingest(record.id,children+parents)
        val order=store.reviewOrder(record.id,store.pendingDocuments(record.id)).map {it.document.id}
        assertEquals(1610,order.size);val positions=order.withIndex().associate {it.value to it.index}
        parents.zip(children).forEach {(parent,child)->assertTrue(positions.getValue(parent.id)<positions.getValue(child.id))}
        assertAccounting()
    }

    private fun prepareReview(owner:String,doc:ContextDocument):ContextPreparedReview {
        store.saveReading(owner,doc,"body",doc.text,ContextReadingText.identity(doc.text,EnglishReadingPreparer.POLICY))
        store.saveReading(owner,doc,"title",doc.title,ContextReadingText.identity(doc.title,EnglishReadingPreparer.POLICY))
        return preparedReview(owner,doc)
    }
    private fun preparedReview(owner:String,doc:ContextDocument):ContextPreparedReview {
        val body=store.reading(owner,doc,"body",doc.text)!!
        val title=store.reading(owner,doc,"title",doc.title)!!.text
        val background=ContextBackgroundResolver(store,owner).cachedReadingBackground(doc)
        return ContextPreparedReview(doc,ContextReadingInput(body,title),background)
    }
    @Test fun unconfirmedPreparationSurvivesReopeningAndCannotMasqueradeAsConfirmedInput() {
        val record=store.resolve(snapshot(first));val policy="unconfirmed-preparation";store.processingPolicy(record.id,policy)
        val doc=document("unconfirmed",first,"post","The battery is still working.").copy(title="Night lights 💡...")
        store.ingest(record.id,listOf(doc))
        store.saveReading(record.id,doc,"body",doc.text,ContextReadingText.identity(doc.text,EnglishReadingPreparer.POLICY,true))
        store.saveReading(record.id,doc,"title",doc.title,ContextReadingText.identity(doc.title,EnglishReadingPreparer.POLICY,true))
        store.close();store=ContextStore(context,databaseName)
        val body=store.reading(record.id,doc,"body",doc.text)!!;val title=store.reading(record.id,doc,"title",doc.title)!!
        assertTrue(body.languageUnconfirmed);assertTrue(title.languageUnconfirmed)
        val session=ContextReviewSession(record.snapshot.revision,policy)
        val wrong=ContextPreparedReview(doc,ContextReadingInput(body,title.text),null)
        assertNull(store.beginReviews(record.id,session,listOf(wrong)))
        val valid=wrong.copy(reading=ContextReadingInput(body,title.text,titleLanguageUnconfirmed=true))
        assertNotNull(store.beginReviews(record.id,session,listOf(valid)))
        assertEquals(doc.text,store.document(record.id,doc.id)!!.document.text)
    }
    @Test fun aSecondHelperCanRevokeSignalAndNoSignalWorkBeforeItsUnitOrCompletionCommits() {
        val record=store.resolve(snapshot(first));val policy="guard-fixture";store.processingPolicy(record.id,policy)
        val parent=document("guard-parent",first,text="The ramp is open.").copy(metadata=mapOf("contextKey" to "guard-key"))
        val child=document("guard-child",first,text="I used it at noon.").copy(metadata=mapOf("contextParent" to "guard-key"))
        store.ingest(record.id,listOf(parent,child));prepareReview(record.id,parent);val input=prepareReview(record.id,child)
        val session=ContextReviewSession(record.snapshot.revision,policy)
        val tokens=store.beginReviews(record.id,session,listOf(input))!!
        ContextStore(context,databaseName).use {other->other.ingest(record.id,listOf(parent.copy(revision="r2",text="Only a disconnected prototype was tested.")))}
        for(status in listOf(ContextUnitStatus.SIGNAL,ContextUnitStatus.NO_SIGNAL)) {
            val findings=if(status==ContextUnitStatus.SIGNAL)listOf(ContextFinding("stale","The ramp is open.","context",listOf(child.id),mapOf(child.id to child.text)))else emptyList()
            val outcome=ContextUnitOutcome("stale-$status",child.id,child.revision,0,child.text.length,status,findings)
            assertFalse(store.commitReviewedUnit(record.id,outcome,tokens.getValue(child.id)))
        }
        assertFalse(store.finishReview(record.id,child.id,child.revision,tokens.getValue(child.id)))
        assertFalse(store.document(record.id,child.id)!!.processed);assertTrue(store.outcomes(record.id,child.id,child.revision).isEmpty())
        assertAccounting()
    }
    @Test fun provenanceAndReaderChoicesPreserveClaimsWhileFreshSessionsSupersedeOldTokens() {
        val record=store.resolve(snapshot(first));val policy="guard-fixture";store.processingPolicy(record.id,policy)
        val doc=document("current-guard",first,text="The connector is seated.");store.ingest(record.id,listOf(doc));val input=prepareReview(record.id,doc)
        val session=ContextReviewSession(record.snapshot.revision,policy);val old=store.beginReviews(record.id,session,listOf(input))!!
        store.ingest(record.id,listOf(doc.copy(fetchedAt=900,url="https://example.org/new-provenance",metadata=doc.metadata+mapOf("etag" to "fresh"))))
        store.save(store.record(record.id)!!.copy(status="processing",nextCheck=1234));store.protect(record.id,true)
        assertTrue(store.reviewsCurrent(record.id,old));assertAccounting()
        val fresh=store.beginReviews(record.id,session,listOf(input))!!;assertNotEquals(old,fresh)
        store.releaseReviews(record.id,old);assertTrue(store.reviewsCurrent(record.id,fresh));assertFalse(store.reviewsCurrent(record.id,old))
        val finding=ContextFinding("current","The connector is seated.","context",listOf(doc.id),mapOf(doc.id to doc.text))
        val outcome=ContextUnitOutcome("current",doc.id,doc.revision,0,doc.text.length,ContextUnitStatus.SIGNAL,listOf(finding))
        assertTrue(store.commitReviewedUnit(record.id,outcome,fresh.getValue(doc.id)))
        assertTrue(store.finishReview(record.id,doc.id,doc.revision,fresh.getValue(doc.id)))
        assertFalse(store.reviewsCurrent(record.id,fresh));assertTrue(store.document(record.id,doc.id)!!.processed);assertAccounting()
    }
    @Test fun changedEnglishViewsAndKnownSourceChangesRevokeTheirInflightWindow() {
        val record=store.resolve(snapshot(first));val policy="guard-fixture";store.processingPolicy(record.id,policy)
        val doc=document("reading-guard",first,text="The connector is seated.");val other=document("known-guard",first,text="A separate report.")
        store.ingest(record.id,listOf(doc,other));val input=prepareReview(record.id,doc);val session=ContextReviewSession(record.snapshot.revision,policy)
        val firstToken=store.beginReviews(record.id,session,listOf(input))!!
        store.ingest(record.id,listOf(other.copy(revision="r2",text="A correction to the separate report.")))
        assertFalse(store.reviewsCurrent(record.id,firstToken))
        val second=store.beginReviews(record.id,session,listOf(input))!!
        store.saveReading(record.id,doc,"body",doc.text,ContextReadingText.identity(doc.text,EnglishReadingPreparer.POLICY))
        assertTrue("Identical cached input is reusable",store.reviewsCurrent(record.id,second))
        store.saveReading(record.id,doc,"body",doc.text,ContextReadingText.identity(doc.text,EnglishReadingPreparer.POLICY+"|new-recipe"))
        assertFalse(store.reviewsCurrent(record.id,second));assertNull(store.beginReviews(record.id,session,listOf(input)))
        assertAccounting()
    }
    @Test fun policyAndSnapshotChangesRevokeClaimsButKeepSourceCopies() {
        val record=store.resolve(snapshot(first));val policy="guard-fixture";store.processingPolicy(record.id,policy)
        val doc=document("session-guard",first);store.ingest(record.id,listOf(doc));val input=prepareReview(record.id,doc)
        val session=ContextReviewSession(record.snapshot.revision,policy);val tokens=store.beginReviews(record.id,session,listOf(input))!!
        store.save(store.record(record.id)!!.copy(processingPolicy="another-policy"));assertFalse(store.reviewsCurrent(record.id,tokens))
        val nextSession=session.copy(policy="another-policy");val next=store.beginReviews(record.id,nextSession,listOf(input))!!
        store.resolve(record.snapshot.copy(revision="new-post-revision"));assertFalse(store.reviewsCurrent(record.id,next))
        assertNotNull(store.document(record.id,doc.id));assertAccounting()
    }
    private fun failedReview(doc:ContextDocument,token:String)=ContextReviewFailure(doc.id,doc.revision,token,
        ContextStream("review:${doc.id}",first,"review",status="limited",limited=true,work=mapOf("document" to doc.id,"revision" to doc.revision)))
    @Test fun aCurrentFailedBatchKeepsCompletedUnitsAndCanBeRetried() {
        val record=store.resolve(snapshot(first));val policy="failure-guard";store.processingPolicy(record.id,policy)
        val a=document("failed-a",first);val b=document("failed-b",first)
        store.ingest(record.id,listOf(a,b));val inputs=listOf(a,b).map {prepareReview(record.id,it)}
        val session=ContextReviewSession(record.snapshot.revision,policy);val tokens=store.beginReviews(record.id,session,inputs)!!
        val finding=ContextFinding("partial","A retained observation.","context",listOf(a.id),mapOf(a.id to a.text))
        val outcome=ContextUnitOutcome("partial",a.id,a.revision,0,a.text.length,ContextUnitStatus.SIGNAL,listOf(finding))
        assertTrue(store.commitReviewedUnit(record.id,outcome,tokens.getValue(a.id)))
        assertTrue(store.deferReviewedSources(record.id,listOf(a,b).map {failedReview(it,tokens.getValue(it.id))}))
        assertTrue(store.pendingDocuments(record.id).isEmpty());assertTrue(store.documents(record.id).none {it.processed})
        assertEquals(listOf(outcome),store.outcomes(record.id,a.id,a.revision));assertEquals(2,store.streams(record.id).size)
        assertFalse(store.reviewsCurrent(record.id,tokens));assertAccounting()
        store.retryDeferred(record.id);assertEquals(setOf(a.id,b.id),store.pendingDocuments(record.id).map {it.document.id}.toSet())
        val fresh=store.beginReviews(record.id,session,inputs)!!
        assertFalse(store.deferReviewedSources(record.id,listOf(a,b).map {failedReview(it,tokens.getValue(it.id))}))
        assertTrue(store.reviewsCurrent(record.id,fresh));assertEquals(2,store.pendingDocuments(record.id).size);assertAccounting()
    }
    @Test fun oneRevokedFailureCannotDeferAnyOtherMemberOfItsBatch() {
        val record=store.resolve(snapshot(first));val policy="failure-guard";store.processingPolicy(record.id,policy)
        val a=document("batch-a",first);val b=document("batch-b",first)
        store.ingest(record.id,listOf(a,b));val inputs=listOf(a,b).map {prepareReview(record.id,it)}
        val tokens=store.beginReviews(record.id,ContextReviewSession(record.snapshot.revision,policy),inputs)!!
        assertTrue(store.finishReview(record.id,b.id,b.revision,tokens.getValue(b.id)))
        assertFalse(store.deferReviewedSources(record.id,listOf(a,b).map {failedReview(it,tokens.getValue(it.id))}))
        assertEquals(listOf(a.id),store.pendingDocuments(record.id).map {it.document.id})
        assertTrue(store.document(record.id,b.id)!!.processed);assertTrue(store.streams(record.id).isEmpty())
        assertTrue(store.reviewsCurrent(record.id,mapOf(a.id to tokens.getValue(a.id))));assertAccounting()
    }
    @Test fun versionFiveMigrationPreservesPublicationAndSavedEvidenceBeforeAnyNewReview() {
        val record=store.resolve(snapshot(first));val doc=document("before-migration",first);store.ingest(record.id,listOf(doc));receipt(record.id,doc)
        store.publish(record.copy(brief="The earlier recorded explanation."));store.protect(record.id,true)
        val view=store.view(record.id);val saved=store.savedView(record.id);val findings=store.findings(record.id)
        store.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("DROP TABLE review_claims");db.execSQL("DROP TABLE earlier_publications");db.version=5
        }
        store=ContextStore(context,databaseName)
        assertEquals(7,store.readableDatabase.version);assertEquals(view,store.view(record.id));assertEquals(saved,store.savedView(record.id));assertEquals(findings,store.findings(record.id));assertAccounting()
    }
    private fun assertAccounting() {
        val records=store.all()
        for(saved in listOf(false,true)) {
            val expected=records.filter {it.saved==saved}.sumOf {record->
                val owner=record.id
                val contextBytes=store.readableDatabase.rawQuery("SELECT length(CAST(payload AS BLOB)) FROM contexts WHERE id=?",arrayOf(owner)).use {assertTrue(it.moveToFirst());it.getLong(0)}
                val payloadBytes=listOf("documents","units","findings","streams","saved_snapshots","compactions","publications","earlier_publications","reading_views","reading_chunks").sumOf {table->store.readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM $table WHERE owner=?",arrayOf(owner)).use {assertTrue(it.moveToFirst());it.getLong(0)}}
                val quoteBytes=store.readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(quote AS BLOB))+length(CAST(reading_quote AS BLOB))),0) FROM finding_sources WHERE owner=?",arrayOf(owner)).use {assertTrue(it.moveToFirst());it.getLong(0)}
                val relationBytes=listOf("context_source_keys" to "sourceKey","context_source_relations" to "parentKey").sumOf {(table,column)->store.readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST($column AS BLOB))),0) FROM $table WHERE owner=?",arrayOf(owner)).use {assertTrue(it.moveToFirst());it.getLong(0)}}
                contextBytes+payloadBytes+quoteBytes+relationBytes+store.readableDatabase.rawQuery("SELECT COALESCE(SUM(length(CAST(document AS BLOB))+length(CAST(token AS BLOB))+length(CAST(payload AS BLOB))),0) FROM review_claims WHERE owner=?",arrayOf(owner)).use {assertTrue(it.moveToFirst());it.getLong(0)}
            }
            assertEquals("Materialized byte total must match current UTF-8 payloads",expected,store.logicalBytes(saved))
        }
    }
}
