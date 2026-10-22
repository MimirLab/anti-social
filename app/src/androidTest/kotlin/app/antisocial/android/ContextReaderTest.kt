/* AntiSocial, GPL-3.0-or-later. Controller fixtures use no real model or source server. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextReaderTest {
    @Test fun oneRequestQueuesMissingWorkAndRepeatedTapsReuseItsCompletedPublication():Unit=runBlocking {
        val record=fixture();val model=TraceModel();reader.modelFactory={model}
        assertTrue(reader.queueRequested(record.id))
        assertFalse("Rapid taps cannot enqueue the same job twice",reader.queueRequested(record.id))
        read(record.id)
        val completed=ready(record.id);val calls=model.summaries.get();val reads=model.extractions.size
        assertTrue(completed.nextCheck>System.currentTimeMillis())
        assertFalse(reader.queueRequested(record.id))
        reader.open(record.id);delay(250)
        assertEquals(completed,reader.store.record(record.id));assertEquals(calls,model.summaries.get());assertEquals(reads,model.extractions.size)
        assertTrue("Explicit Refresh bypasses the fresh cache",reader.queueRequested(record.id,force=true))
        read(record.id)
        reader.store.save(ready(record.id).copy(nextCheck=System.currentTimeMillis()-1))
        assertTrue("Expired Context resumes without another Refresh tap",reader.queueRequested(record.id))
        read(record.id)
    }
    @Test fun aCompletedEmptyReviewUsesItsRetryFloorAndExplicitRefreshStillWorks():Unit=runBlocking {
        val record=fixture();val model=TraceModel()
        reader.modelFactory={model};read(record.id)
        reader.store.save(ready(record.id).copy(brief="",briefFindingIds=emptyList(),briefSources=emptyList()))
        assertFalse("Empty is a completed result, not an instruction to regenerate",reader.queueRequested(record.id))
        reader.store.stream(record.id,ContextStream("review:pending",record.snapshot.roots.single(),"review",status="limited"))
        assertFalse("A completed partial cycle must not restart on every open",reader.queueRequested(record.id))
        assertTrue("Explicit Refresh bypasses the retry floor",reader.queueRequested(record.id,force=true))
        read(record.id)
        reader.store.save(ready(record.id).copy(status="paused",brief="",message="Interrupted"))
        assertTrue(reader.queueRequested(record.id));read(record.id)
    }
    @Test fun anEmptyFailedReviewGetsFiveMinutesOfQuietBeforeAnAutomaticRetry():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single();var calls=0
        val base=TraceModel()
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items"))return fixtureBriefAdmission(user)!!
                calls++
                return if(input["kind"]?.asString=="post")"""{"status":"no_signal","findings":[]}""" else "malformed source review"
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,comment(root,1).copy(body="This passage is deliberately unreadable by the fixture model.")))}
        read(record.id);val done=ready(record.id)
        assertEquals("partial",done.status);assertEquals("",done.brief)
        assertTrue(reader.store.streams(record.id).any {it.kind=="review"})
        assertTrue(done.nextCheck-done.checkedAt in 299000L..301000L)
        val before=calls
        reader.open(record.id);delay(300)
        assertEquals(done,reader.store.record(record.id));assertEquals(before,calls)
        reader.store.save(done.copy(nextCheck=System.currentTimeMillis()-1))
        assertTrue(reader.queueRequested(record.id));read(record.id)
    }
    @Test fun rejectedArrivalUpdatesCoverageWithoutAnnouncingNewInformation():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single();val base=TraceModel()
        reader.discussionRead={_,_->Page(listOf(root))}
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items")) {
                    assertEquals("Rejecting the root finding must not erase the prepared subject",root.body,input["subject"]?.asString)
                    return repo.gson.toJson(mapOf("reviews" to input["items"].asJsonArray.map {mapOf("id" to it.asJsonObject["id"].asInt,"on_topic" to false,"adds_information" to true)}))
                }
                if(input["kind"]?.asString=="post")return """{"status":"no_signal","findings":[]}"""
                if(input.has("source_id")) {
                    val text=input["text"].asString
                    return repo.gson.toJson(mapOf("status" to "signal","findings" to listOf(mapOf("text" to text,"kind" to "context","quote" to text))))
                }
                return base.generate(system,user,maxTokens)
            }
        }}
        read(record.id);val first=ready(record.id)
        assertEquals("",first.brief);assertEquals(emptyList<String>(),first.briefFindingIds)
        reader.discussionRead={_,_->Page(listOf(root,comment(root,1).copy(body="The unrelated stock now costs 12 dollars.")))}
        read(record.id);val next=ready(record.id)
        assertEquals(first.briefSignalStamp,next.briefSignalStamp);assertEquals(first.updatedAt,next.updatedAt)
        assertEquals(emptyList<String>(),next.briefFindingIds)
        assertTrue(reader.store.documents(record.id).size>1)
    }
    @Test fun anAllNoiseReviewClearsAnOlderBriefAndResumesWithoutRejudgingItsComments():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        reader.store.save(record.copy(brief="An old catalogue of insults and reactions.",compositionPolicy="old-recipe"))
        reader.discussionRead={_,_->Page(listOf(root,comment(root,1).copy(body="That unrelated stock is down. What a loser.")))}
        val base=TraceModel();var selections=0
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items")){
                    selections++
                    return repo.gson.toJson(mapOf("reviews" to input["items"].asJsonArray.map {mapOf("id" to it.asJsonObject["id"].asInt,"on_topic" to true,"adds_information" to false)}))
                }
                return base.generate(system,user,maxTokens)
            }
        }}
        read(record.id)
        assertEquals("",ready(record.id).brief)
        assertTrue(reader.store.checkpointUnits(record.id)>0)
        val units=reader.store.checkpointUnits(record.id);val reviewed=base.extractions.size;val choices=selections
        read(record.id)
        assertEquals("",ready(record.id).brief);assertEquals(units,reader.store.checkpointUnits(record.id))
        assertEquals(reviewed,base.extractions.size);assertEquals(choices,selections)
    }
    @Test fun refreshRecoversLinksFromTheCurrentCachedSourceWhenAnOldSnapshotLostItsHeading():Unit=runBlocking {
        val base=fixture();val old=base.snapshot.roots.single()
        val urls=listOf("https://x.com/context_fixture/status/883${suffix}1","https://x.com/context_fixture/status/883${suffix}2","https://x.com/context_fixture/status/883${suffix}3")
        val current=old.copy(title="Roundup: "+urls.joinToString(" "),links=urls.take(1))
        repo.dao.putPost(PostRow(repo.key(current),current.platform.id,repo.gson.toJson(current),current.publishedAt,System.currentTimeMillis(),bytes=repo.gson.toJson(current).toByteArray().size.toLong()))
        val legacy=base.copy(snapshot=base.snapshot.copy(roots=listOf(old.copy(title="",links=urls.take(1))),links=urls.take(1)))
        reader.store.save(legacy)
        reader.modelFactory={error("Fixture stops after deterministic snapshot repair")}
        reader.run(legacy.id)
        val repaired=reader.store.record(legacy.id)!!
        assertTrue(repaired.snapshot.links.containsAll(urls))
        assertEquals(current, repaired.snapshot.roots.single())
        assertNotEquals(legacy.snapshot.revision,repaired.snapshot.revision)
    }
    @Test fun archivedContextDoesNotBorrowLinksFromTheCurrentCachedPost():Unit=runBlocking {
        val base=fixture();val old=base.snapshot.roots.single()
        val url="https://x.com/context_fixture/status/884${suffix}1"
        val current=old.copy(title="A newer post edition includes $url",links=listOf(url))
        repo.dao.putPost(PostRow(repo.key(current),current.platform.id,repo.gson.toJson(current),current.publishedAt,System.currentTimeMillis(),bytes=repo.gson.toJson(current).toByteArray().size.toLong()))
        val archived=base.copy(snapshot=base.snapshot.copy(variant="archive:fixture-$suffix"))
        reader.store.save(archived)
        reader.modelFactory={error("Fixture stops after deterministic snapshot check")}
        reader.run(archived.id)
        assertEquals(archived.snapshot,reader.store.record(archived.id)!!.snapshot)
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repo get() = (context as AntiSocialApplication).repo
    private val reader get() = repo.contextReader
    private val suffix = System.nanoTime().toString().takeLast(12)
    private var sequence = 0
    private lateinit var aiPrefs: Map<String, *>
    private lateinit var readerPrefs: String
    private lateinit var options: ContextOptions
    private lateinit var originalModel: () -> ContextModel
    private lateinit var originalImagePreparation: suspend ()->Unit
    private lateinit var originalDiscussion: suspend (Post, Page?) -> Page
    private lateinit var originalArticleRead:suspend (String,String,ContextArticleValidators,String?)->ContextArticleFetch
    private lateinit var originalPublicationRead:suspend (SourceRecordRequest)->PublicSourceRecord
    private lateinit var originalMaintenanceKeys: suspend (String,Int)->List<String>
    private lateinit var originalMaintenanceCursor: String
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private val posts = mutableListOf<Post>()
    private val owners = mutableListOf<String>()

    private class TraceModel : ContextModel {
        val extractions = CopyOnWriteArrayList<String>()
        val summaries = AtomicInteger()
        var beforeExtract: suspend (JsonObject) -> Unit = {}
        override suspend fun count(system: String, user: String) = 128
        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
                fixtureBriefAdmission(user)?.let {return it}
            val input = JsonParser.parseString(user).asJsonObject
            if (input.has("source_id")) {
                // S1 is the per-request source alias. Trace the fixture's
                // distinct source content to detect an accidental re-review.
                extractions += input["kind"].asString + ":" + input["text"].asString
                beforeExtract(input)
                if (input["kind"].asString == "comment") return """{"status":"no_signal","findings":[]}"""
                return JsonObject().apply {
                    addProperty("status", "signal")
                    add("findings", JsonArray().apply { add(JsonObject().apply {
                        addProperty("text", "The workshop announced a practical repair session.")
                        addProperty("kind", "context"); addProperty("quote", input["text"].asString)
                    }) })
                }.toString()
            }
            summaries.incrementAndGet()
            val findings = input.getAsJsonArray("findings")
            return JsonObject().apply {
                addProperty("text", findings.joinToString(" ") { it.asJsonObject["text"].asString })
                if(findings.all {it.asJsonObject.has("id")})add("used", JsonArray().apply { findings.forEach { add(it.asJsonObject["id"].asString) } })
            }.toString()
        }
    }

    @Before fun isolate() {
        assumeTrue("Controller fixtures require the isolated test device", InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice") == "true")
        runBlocking { repo.awaitReady() }
        aiPrefs = context.getSharedPreferences("ai-settings", Context.MODE_PRIVATE).all.toMap()
        readerPrefs = repo.prefs.export().toString(); options = reader.preferences.state.value
        originalModel = reader.modelFactory; originalDiscussion = reader.discussionRead
        originalArticleRead=reader.articleRead;originalPublicationRead=reader.publicationRead
        originalImagePreparation=reader.prepareImageReader;reader.prepareImageReader={}
        originalMaintenanceKeys=reader.maintenanceKeys;originalMaintenanceCursor=reader.preferences.savedMaintenanceCursor
        reader.maintenanceKeys={_,_->emptyList()};reader.preferences.savedMaintenanceCursor=""
        policy = repo.gateway.currentPolicy(); searchPolicy = repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.ai.enabled(false); reader.pauseAll()
        runBlocking { withTimeout(10_000) { reader.active.first { it.isEmpty() } } }
        repo.prefs.intervalMinutes = 0; repo.prefs.archiveChecks = false; repo.prefs.communityComments = false
        reader.preferences.update(ContextOptions()); reader.changed(); repo.cancelSearchPreparation()
        runBlocking { withContext(Dispatchers.Main) { repo.tor.enable(false) } }
        // Any accidental network leg fails before opening a direct socket.
        repo.gateway.configure(NetworkPolicy(tor = true, ready = false)); repo.gateway.configureTor(NetworkPolicy(tor = true, ready = false))
        repo.ai.preferences.provider("text", AiProviderSettings(enabled = true, id = "context-tests", baseUrl = "https://context-fixture.invalid/v1", model = "fixture-only"))
        repo.ai.preferences.selectProvider("text", "context-tests"); repo.ai.preferences.routing("text", AiRouting.PROVIDER_ONLY)
        reader.modelFactory = { TraceModel() }; reader.discussionRead = { root, _ -> Page(listOf(root)) }
        repo.ai.enabled(true)
    }

    @After fun restore() {
        if (!::aiPrefs.isInitialized) return
        reader.close(); reader.pauseAll(); repo.ai.enabled(false)
        runBlocking { withTimeout(10_000) { reader.active.first { it.isEmpty() } } }
        reader.modelFactory = originalModel; reader.discussionRead = originalDiscussion
        reader.articleRead=originalArticleRead;reader.publicationRead=originalPublicationRead
        reader.prepareImageReader=originalImagePreparation
        reader.maintenanceKeys=originalMaintenanceKeys;reader.preferences.savedMaintenanceCursor=originalMaintenanceCursor
        owners+=posts.flatMap {reader.store.forOriginals(listOf(repo.key(it)))}.map {it.id}
        runBlocking { posts.forEach { repo.dao.removeBookmark(repo.key(it)) }; repo.dao.removePosts(posts.map(repo::key)) }
        owners.forEach { reader.store.delete(it) }
        val edit = context.getSharedPreferences("ai-settings", Context.MODE_PRIVATE).edit().clear()
        aiPrefs.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value); is String -> edit.putString(key, value); is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
            else -> error("Unexpected AI fixture preference")
        } }; edit.commit()
        reader.preferences.update(options); reader.changed()
        runBlocking { withContext(Dispatchers.Main) {
            context.getSharedPreferences("antisocial", Context.MODE_PRIVATE).edit().clear().commit(); repo.prefs.restore(JSONObject(readerPrefs))
            repo.applyNetworkSettings(); repo.gateway.configure(policy); repo.gateway.configureTor(searchPolicy); scheduleRefresh(context, repo.prefs)
        }; repo.ai.refresh() }
    }

    private suspend fun fixture(body:String="A practical battery repair session has been announced."): ContextRecord {
        val now = System.currentTimeMillis(); val index = ++sequence
        val post = Post("context-controller-$suffix-$index", Platform.X, "https://x.com/context_fixture/status/992$suffix$index",
            "Workshop", body, publishedAt = now)
        posts += post
        val payload = repo.gson.toJson(post)
        repo.dao.putPost(PostRow(repo.key(post), post.platform.id, payload, now, now, bytes = payload.toByteArray().size.toLong()))
        return reader.store.resolve(ContextSnapshot(listOf(post), post.body, "", "revision-$suffix-$index")).also { owners += it.id }
    }
    private suspend fun savedPost(body: String="A practical battery repair session has been announced. Bring the service manual and a small screwdriver so the volunteer team can inspect loose connectors before recommending replacement parts.",platform: Platform=Platform.X,publishedAt:Long=System.currentTimeMillis()): Post {
        val index=++sequence;val id="994$suffix$index"
        val url=if(platform==Platform.BLUESKY)"https://bsky.app/profile/context-fixture.test/post/$id"else "https://x.com/context_fixture/status/$id"
        val post=Post(id,platform,url,"Workshop",body,publishedAt=publishedAt)
        posts+=post
        repo.dao.putBookmark(BookmarkRow(repo.key(post),platform.id,repo.gson.toJson(post),System.currentTimeMillis()))
        return post
    }
    private fun maintenanceScope(saved: List<Post>) {
        val keys=saved.map(repo::key).distinct().sorted()
        reader.maintenanceKeys={cursor,limit->(keys.filter {it>cursor}+keys.filter {it<=cursor}).take(limit)}
    }
    private fun savedOwner(post: Post)=reader.store.forOriginals(listOf(repo.key(post))).single()
    private fun comment(root: Post, index: Int, links: List<String> = emptyList()) = root.copy(
        id = "reply-$suffix-$index", canonicalUrl = "https://x.com/context_reply/status/993$suffix$index", author = "Reader $index",
        body = "Thanks for the update $index.", parentId = root.id, links = links, replyCount = null)
    private suspend fun read(id: String) { withTimeout(20_000) { reader.run(id) } }
    private fun signalArticle(url:String,references:List<SignalReference> = emptyList(),doi:String?=null)=ContextArticleFetch.Ready(ContextArticleEvidence(url,url,"Repair evidence",null,"Published source",null,
        listOf(ContextArticleBlock("The published measurements found that the original battery connector was loose. Reseating the connector restored operation without buying a replacement battery.")),System.currentTimeMillis(),references=references,publicationDoi=doi))
    private fun signalRef(url:String)=SignalReference(url,"Full report","Linked primary material","The report at $url contains the measurements.",100)
    @Test fun underlyingSourcesEnrichAnAlreadyPublishedBriefAndKeepTheirParentRelationships():Unit=runBlocking {
        val original=fixture();val primary="https://signal-fixture.invalid/article/$suffix";val source="https://agency.gov/report/$suffix"
        val record=reader.store.resolve(original.snapshot.copy(links=listOf(primary),revision="signal-first-$suffix"))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=mutableListOf<String>()
        reader.articleRead={url,_,_,_->calls+=url;if(url==source){entered.complete(Unit);release.await()};signalArticle(url,if(url==primary)listOf(signalRef(source),signalRef(source))else listOf(signalRef(primary)))}
        val job=launch {read(record.id)}
        withTimeout(10_000){entered.await()}
        assertTrue("The first brief must not wait for the citation crawl",reader.store.record(record.id)!!.brief.isNotBlank())
        release.complete(Unit);job.join();ready(record.id)
        assertEquals(listOf(primary,source),calls)
        val child=reader.store.document(record.id,"article:${Repository.hash(source)}")!!.document
        assertEquals("article:${Repository.hash(primary)}",child.metadata["contextParent"])
        assertEquals(CrossPosts.originalKey(record.snapshot.roots.single()),child.metadata["root"])
    }
    @Test fun derivedSourceDepthAndCardBudgetSurviveRefreshWithoutAnExpandingCrawl():Unit=runBlocking {
        val original=fixture();val primary="https://signal-fixture.invalid/report/$suffix/root"
        val record=reader.store.resolve(original.snapshot.copy(links=listOf(primary),revision="signal-cap-$suffix"));val calls=mutableListOf<String>()
        reader.articleRead={url,_,_,_->calls+=url;signalArticle(url,(1..12).map {signalRef("$url/child$it")})}
        read(record.id);ready(record.id)
        val routes=reader.store.streams(record.id).filter {it.kind=="link"}
        assertEquals(7,routes.size);assertEquals(7,calls.size)
        assertEquals(6,routes.count {it.work["signalDepth"]!=null});assertTrue(routes.all {(it.work["signalDepth"]?.toIntOrNull() ?: 0)<=2})
        calls.clear();read(record.id);ready(record.id)
        assertEquals(7,reader.store.streams(record.id).count {it.kind=="link"});assertEquals(7,calls.size)
    }
    @Test fun paperUpdatesLeadToTheActualNoticeAndRemainAttachedToTheCitedPaper():Unit=runBlocking {
        val original=fixture();val doi="10.1234/paper-$suffix";val primary="https://doi.org/$doi";val notice="https://doi.org/10.1234/notice-$suffix"
        val record=reader.store.resolve(original.snapshot.copy(links=listOf(primary),revision="signal-paper-$suffix"));val calls=mutableListOf<String>()
        reader.articleRead={url,_,_,_->calls+=url;signalArticle(url,doi=if(url==primary)doi else null)}
        reader.publicationRead={request->PublicSourceRecord(request,listOf(SourceFact("paper:correction","Correction","The publisher corrected the measurement table.","https://api.crossref.org/works/$doi",destination=notice)),System.currentTimeMillis())}
        read(record.id);ready(record.id)
        assertEquals(listOf(primary,notice),calls)
        val noticeDoc=reader.store.document(record.id,"article:${Repository.hash(notice)}")!!.document
        val update=reader.store.document(record.id,"publication-record:$doi")!!.document
        assertEquals(update.id,noticeDoc.metadata["contextParent"])
        assertEquals("article:${Repository.hash(primary)}",update.metadata["contextParent"])
        assertTrue(update.text.contains("Correction"));assertTrue(update.text.contains(notice))
    }
    @Test fun aPublicAnnotationFeedsItsCitationsIntoTheExistingReaderWithoutPretendingItIsRatedHelpful():Unit=runBlocking {
        val original=fixture();val url="https://agency.gov/report/$suffix"
        val root=original.snapshot.roots.single().copy(contextNotes=listOf(PublicContextNote("The public report contains a corrected measurement table.",original.snapshot.roots.single().canonicalUrl,listOf(url,url))))
        val record=reader.store.resolve(original.snapshot.copy(roots=listOf(root),revision="annotation-$suffix"));val calls=mutableListOf<String>()
        reader.articleRead={link,_,_,_->calls+=link;signalArticle(link)}
        read(record.id);ready(record.id)
        assertEquals(listOf(url),calls)
        val annotation=reader.store.documents(record.id).single {it.document.id.startsWith("annotation:")}.document
        assertEquals("comment",annotation.kind);assertFalse(annotation.text.contains("Helpful"))
        assertEquals(annotation.id,reader.store.document(record.id,"article:${Repository.hash(url)}")!!.document.metadata["contextParent"])
    }
    @Test fun failedSupplementalEvidenceCannotEraseTheUsableFirstBrief():Unit=runBlocking {
        val original=fixture();val primary="https://signal-fixture.invalid/report/$suffix";val failed="https://unavailable.example/report/$suffix"
        val record=reader.store.resolve(original.snapshot.copy(links=listOf(primary),revision="signal-failed-$suffix"))
        reader.articleRead={url,_,_,_->if(url==failed)error("Public source is unavailable")else signalArticle(url,listOf(signalRef(failed)))}
        read(record.id);val complete=ready(record.id)
        assertEquals("partial",complete.status);assertTrue(complete.brief.isNotBlank())
        assertTrue(reader.store.streams(record.id).any {it.root.canonicalUrl==failed&&it.status=="unavailable"})
        assertTrue(reader.store.document(record.id,"article:${Repository.hash(primary)}")!!.processed)
    }
    @Test fun anOlderWashedArticleIsReacquiredOnceSoItsSourcePathsCanBeDiscovered():Unit=runBlocking {
        val original=fixture();val url="https://signal-fixture.invalid/report/$suffix"
        val record=reader.store.resolve(original.snapshot.copy(links=listOf(url),revision="signal-upgrade-$suffix"))
        val old=ContextDocument("article:${Repository.hash(url)}","old","Previously washed article body.",url=url,kind="article",metadata=mapOf("root" to CrossPosts.originalKey(record.snapshot.roots.single()),"readingFormat" to ContextArticles.READING_FORMAT,"etag" to "old-tag","retrievalUrl" to url))
        reader.store.ingest(record.id,listOf(old));val validators=mutableListOf<ContextArticleValidators>()
        reader.articleRead={link,_,v,_->validators+=v;(signalArticle(link) as ContextArticleFetch.Ready).let {it.copy(document=it.document.copy(etag="new-tag"))}}
        read(record.id);ready(record.id)
        assertEquals(null,validators.single().etag)
        assertEquals(SignalDiscovery.POLICY,reader.store.document(record.id,old.id)!!.document.metadata["signalDiscoveryPolicy"])
        validators.clear();read(record.id);ready(record.id)
        assertEquals("new-tag",validators.single().etag)
    }
    private fun ready(id: String): ContextRecord = reader.store.record(id)!!.also {
        assertTrue("Expected a completed local read, got ${it.status}: ${it.message}", it.status in setOf("ready", "partial"))
    }
    private fun publicationModel(brief:String,reviews:AtomicInteger,summaries:AtomicInteger,
        window:Int=2048,plan:ContextProcessingPlan=ContextProcessingPlan.SINGLE,
        beforeSummary:suspend ()->Unit={}):ContextModel {
        val base=TraceModel()
        return object:ContextModel by base {
            override val contextTokens=window
            override val processingPlan=plan
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("source_id")) {
                    reviews.incrementAndGet()
                    if(input["kind"]?.asString!="comment")return base.generate(system,user,maxTokens)
                    val text=input["text"].asString
                    return JsonObject().apply {
                        addProperty("status","signal")
                        add("findings",JsonArray().apply {add(JsonObject().apply {
                            addProperty("text",text);addProperty("kind","context");addProperty("quote",text)
                        })})
                    }.toString()
                }
                summaries.incrementAndGet();beforeSummary()
                return JsonObject().apply {addProperty("text",brief)}.toString()
            }
        }
    }
    private fun fixtureSummary(input:JsonObject):String {
        val parts=input["findings"].asJsonArray.map {it.asJsonObject}
        val answered=parts.flatMap {it["reply_contexts"]?.asJsonArray?.mapNotNull {r->r.asJsonObject["text"]?.asString}.orEmpty()}.toSet()
        val text=parts.filterNot {it["kind"]?.asString=="question"&&it["text"].asString in answered}.map {node->
            val parents=node["reply_contexts"]?.asJsonArray?.mapNotNull {it.asJsonObject["text"]?.asString}.orEmpty()
            // Keep each question with its answer. Author names no longer
            // distinguish equal answers in the reader-writing payload.
            (parents+listOf(node["text"].asString)).joinToString("\n")
        }.distinct().joinToString("\n\n")
        return repo.gson.toJson(mapOf("text" to text))
    }
    private fun publicationReplies(root:Post)=(1..4).map {comment(root,it).copy(body="Point $it: the inspection covers this connector rather than every battery.")}

    @Test fun unreadCommentCannotBlockACondensedBriefOfThePostAndLinkedMaterial():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val article=ContextDocument("roundup-article-$suffix","v1","The repair guide specifies a 56 Wh battery and a T5 screwdriver. Disconnect the battery before replacing the cable.",author="Service manual",url="https://example.org/service-manual",kind="article")
        reader.store.ingest(record.id,listOf(article))
        reader.discussionRead={_,_->Page(listOf(root,comment(root,1).copy(body="Everyone is arguing about this announcement.")))}
        val concise="The repair session covers loose battery connectors. The service manual specifies a 56 Wh battery and T5 screwdriver; disconnect the battery before replacing the cable."
        var linkedRead=false
        reader.modelFactory={object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings")) {
                    val entries=input["findings"].asJsonArray
                    if(entries.any {it.asJsonObject["text"].asString.contains("56 Wh")})linkedRead=true
                    return repo.gson.toJson(mapOf("text" to if(linkedRead)concise else "The workshop announced a repair session."))
                }
                if(input.has("passages"))return """{"keep":[1]}"""
                if(input["kind"]?.asString=="comment")return "invalid review"
                val text=input["text"].asString
                return repo.gson.toJson(mapOf("status" to "signal","findings" to listOf(mapOf("text" to text,"quote" to text,"kind" to "brief"))))
            }
        }}
        read(record.id)
        val ready=ready(record.id)
        assertTrue(linkedRead);assertEquals(concise,ready.brief)
        assertTrue(reader.store.streams(record.id).any {it.kind=="review"})
        assertTrue(reader.store.view(record.id)!!.documents.any {it.document.id==article.id})
        assertFalse(ready.brief.contains("arguing"))
    }

    @Test fun aSourceEditDuringCompositionCannotMarryOldProseToNewSourceCopies():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single();var replies=publicationReplies(root)
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        val original="The original reviewed connector inspection."
        reader.modelFactory={publicationModel(original,AtomicInteger(),AtomicInteger())}
        read(record.id);ready(record.id)
        repo.dao.putBookmark(BookmarkRow(repo.key(root),root.platform.id,repo.gson.toJson(root),System.currentTimeMillis()))
        reader.store.protect(record.id,true)
        val before=reader.store.view(record.id)!!;val saved=reader.store.savedView(record.id)!!
        replies=replies.toMutableList().apply {this[0]=this[0].copy(body="Point 1: a newer inspection covers the power connector only.")}
        var edited=false
        reader.modelFactory={publicationModel("This composition used the earlier source revision.",AtomicInteger(),AtomicInteger(),beforeSummary={
            if(!edited) {
                edited=true
                val source=reader.store.documents(record.id).first {it.document.kind=="comment"}.document
                val latest="Point 1: a subsequent correction says that inspection is still incomplete."
                reader.store.ingest(record.id,listOf(source.copy(text=latest,revision=Repository.hash(latest))))
                replies=replies.toMutableList().apply {this[0]=this[0].copy(body=latest)}
            }
        })}
        read(record.id)
        assertTrue(edited)
        assertEquals("Source edits must invalidate in-flight composition even when the post is unchanged",original,reader.store.record(record.id)!!.brief)
        assertEquals(before.documents,reader.store.view(record.id)!!.documents)
        assertEquals(before.findings,reader.store.view(record.id)!!.findings)
        assertEquals(saved,reader.store.savedView(record.id))
        assertTrue(reader.store.record(record.id)!!.stale)
        val next="The refreshed context keeps the incomplete inspection correction."
        reader.modelFactory={publicationModel(next,AtomicInteger(),AtomicInteger())}
        read(record.id);ready(record.id)
        assertEquals(next,reader.store.record(record.id)!!.brief)
        assertTrue(reader.store.view(record.id)!!.documents.any {it.document.text.contains("subsequent correction")})
        assertEquals(saved,reader.store.savedView(record.id))
    }

    @Test fun aChangedQualificationOutsideTheSelectedQuoteReconsidersComposition():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        var replies=publicationReplies(root).map {it.copy(body=it.body+" The report originally describes a complete inspection.")}
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        val trace=TraceModel();val summaries=AtomicInteger()
        reader.modelFactory={object:ContextModel by trace {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input["kind"]?.asString=="comment") {
                    val quote=input["text"].asString.substringBefore('.')+"."
                    return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {
                        addProperty("text",quote);addProperty("quote",quote);addProperty("kind","context")
                    })})}.toString()
                }
                if(input.has("source_id"))return trace.generate(system,user,maxTokens)
                summaries.incrementAndGet();return """{"text":"Reports describe connector inspections with stated limitations."}"""
            }
        }}
        read(record.id);ready(record.id)
        val first=reader.store.view(record.id)!!;val count=summaries.get();assertTrue(count>0)
        val firstReceipt=reader.store.compactions(record.id).maxBy {it.level}.id
        replies=replies.toMutableList().apply {this[0]=this[0].copy(body=this[0].body.substringBefore('.')+". The qualification now limits this to a disconnected bench sample.")}
        read(record.id);ready(record.id)
        assertEquals(first.findings.associateBy {it.id},reader.store.view(record.id)!!.findings.associateBy {it.id})
        assertTrue("An identical retained quote does not make the complete source review identical",summaries.get()>count)
        assertNotEquals(firstReceipt,reader.store.compactions(record.id).maxBy {it.level}.id)
        val after=summaries.get();read(record.id);ready(record.id);assertEquals(after,summaries.get())
    }

    @Test fun completeSubjectAndParentReachLargeWindowsAndParentEditsReopenOnlyTheirReplies(): Unit=runBlocking {
        val record=fixture()
        val subject="Subject introduction. "+"Public background detail. ".repeat(180)+"The bridge is closed to cars but remains open to buses."
        val root=record.snapshot.roots.single().copy(body=subject)
        reader.store.save(record.copy(snapshot=record.snapshot.copy(roots=listOf(root),text=subject,revision="complete-background-$suffix")))
        val parentText="Parent introduction. "+"Local explanation. ".repeat(90)+"The school service uses the bus exception."
        var parent=comment(root,1).copy(body=parentText)
        val child=comment(root,2).copy(body="Does the exception include the school service?",parentId=parent.id)
        val other=comment(root,3).copy(body="A separate comment about the published timetable.")
        val trace=TraceModel();val prompts=CopyOnWriteArrayList<JsonObject>()
        trace.beforeExtract={prompts+=it}
        reader.modelFactory={object:ContextModel by trace {
            override val contextTokens=262144
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings"))return """{"text":"The bridge is closed to cars but remains open to buses."}"""
                return trace.generate(system,user,maxTokens)
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,parent,child,other))}
        read(record.id);ready(record.id)
        val first=prompts.single {it["text"].asString==child.body}
        assertTrue(first["parent_context"].asString.contains(subject))
        assertTrue(first["parent_context"].asString.contains(parentText))
        assertFalse(first.has("parent_truncated"));assertFalse(first.has("parent_unavailable"))
        val stored=reader.store.document(record.id,"comment:${CrossPosts.originalKey(child)}")!!.document
        assertEquals("",stored.parentText);assertNotNull(stored.metadata["contextParent"])
        parent=parent.copy(body=parentText+" The exception has now been withdrawn.")
        read(record.id);ready(record.id)
        assertEquals(2,prompts.count {it["text"].asString==child.body})
        assertEquals(1,prompts.count {it["text"].asString==other.body})
        assertEquals(1,prompts.count {it["kind"].asString=="post"})
        assertTrue(prompts.last {it["text"].asString==child.body}["parent_context"].asString.contains("exception has now been withdrawn"))
    }

    @Test fun nestedSavedSharedRepliesUseTheirActualLocallyCapturedParent(): Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single();val url=AnywhereUrls.root(root,repo.routes)
        val child=AnywhereComment("shared-child-$suffix","Does that include the side entrance?","Child reader")
        val parent=AnywhereComment("shared-parent-$suffix","The service manual excludes the side entrance from this measurement.","Parent reader",children=listOf(child))
        val page=AnywherePage(url,listOf(parent),CommentOrder.NEWEST,System.currentTimeMillis()-1000)
        capture(root,listOf(CapturedThread(root,emptyList(),capturedAt=page.fetchedAt,commentAnywhere=page)))
        repo.prefs.communityComments=true
        val trace=TraceModel();val prompts=CopyOnWriteArrayList<JsonObject>();trace.beforeExtract={prompts+=it};reader.modelFactory={trace}
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live source unavailable")}
        read(record.id);ready(record.id)
        val sent=prompts.single {it["text"].asString==child.text}
        assertTrue(sent["parent_context"].asString.contains(parent.text))
        assertTrue(sent["parent_context"].asString.contains(root.body))
        assertFalse(sent.has("parent_unavailable"))
        val doc=reader.store.document(record.id,"shared:${Repository.hash(url)}:${child.id}")!!.document
        assertEquals("shared:${Repository.hash(url)}:${parent.id}",doc.metadata["contextParent"])
        assertEquals("true",doc.metadata["savedCapture"])
    }

    @Test fun aParentArrivingOnALaterPageClearsOnlyItsResolvedReviewGap(): Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val parent=comment(root,1).copy(body="The rear entrance remains accessible.")
        val child=comment(root,2).copy(body="Does the same entrance serve wheelchairs?",parentId=parent.id,conversationId=root.id)
        val trace=TraceModel();val prompts=CopyOnWriteArrayList<JsonObject>();trace.beforeExtract={prompts+=it};reader.modelFactory={trace}
        reader.discussionRead={_,previous->if(previous==null)Page(listOf(root,child),next="parent-page")else Page(listOf(root,parent))}
        read(record.id);ready(record.id)
        val attempts=prompts.filter {it["text"].asString==child.body}
        assertEquals(2,attempts.size);assertTrue(attempts.first()["parent_unavailable"].asBoolean)
        assertFalse(attempts.last().has("parent_unavailable"));assertTrue(attempts.last()["parent_context"].asString.contains(parent.body))
        assertTrue(reader.store.streams(record.id).none {it.kind=="review"})
        assertTrue(reader.store.document(record.id,"comment:${CrossPosts.originalKey(child)}")!!.processed)
    }
    private suspend fun capture(root: Post,threads: List<CapturedThread>,fusionRoots: List<Post>?=null) {
        repo.dao.putBookmark(BookmarkRow(repo.key(root),root.platform.id,repo.gson.toJson(root),System.currentTimeMillis()))
        repo.dao.putSavedDetail(SavedDetailRow(repo.key(root),repo.gson.toJson(SavedCopyDetails("capture-$suffix",System.currentTimeMillis(),threads=threads,fusionRoots=fusionRoots))))
    }
    private fun usefulComments(model: TraceModel) {
        reader.modelFactory={object:ContextModel by model {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                val result=model.generate(system,user,maxTokens)
                if(input["kind"]?.asString!="comment")return result
                return JsonObject().apply {
                    addProperty("status","signal")
                    add("findings",JsonArray().apply {add(JsonObject().apply {
                        addProperty("text",input["text"].asString);addProperty("kind","clarification");addProperty("quote",input["text"].asString)
                    })})
                }.toString()
            }
        }}
    }

    @Test fun rejectedModelRequestsInvalidateCapacityWithoutReplayingOrLosingTheSource(): Unit=runBlocking {
        val old=reader.capability.value
        try {
            for(status in listOf(400,413,503)) {
                val record=fixture();val model=TraceModel()
                model.beforeExtract={throw SourceFailure(HealthStatus.UNAVAILABLE,"Source returned $status",httpStatus=status)}
                reader.modelFactory={model}
                reader.capability.value=ContextModelCapabilities.local("fixture",activeContextTokens=8192)
                read(record.id)
                val paused=reader.store.record(record.id)!!
                assertEquals("paused",paused.status);assertEquals(1,model.extractions.size)
                assertEquals(0L,paused.checkedAt);assertTrue(reader.store.pendingDocuments(record.id).isNotEmpty())
                if(status in setOf(400,413)) {
                    assertNull(reader.capability.value);assertTrue(paused.message.contains("Refresh checks its capacity"))
                }else assertEquals(8192,reader.capability.value!!.contextTokens)
            }
        }finally {reader.capability.value=old}
    }

    @Test fun unavailableLiveDiscussionStillReadsItsSavedRepliesAsDatedPartialSources(): Unit=runBlocking {
        val model=TraceModel();usefulComments(model)
        val record=fixture();val root=record.snapshot.roots.single();val at=System.currentTimeMillis()-2*CachePolicy.DAY
        val reply=comment(root,1).copy(body="The loose connector was repaired without replacing the battery.")
        capture(root,listOf(CapturedThread(root,listOf(reply),capturedAt=at,hasMore=true)))
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live discussion unavailable")}
        val before=repo.gateway.protectionStats();read(record.id)
        val result=ready(record.id);assertEquals("partial",result.status);assertTrue(result.saved)
        val stored=reader.store.document(record.id,"comment:${CrossPosts.originalKey(reply)}")!!
        assertTrue(stored.processed);assertEquals(reply.body,stored.document.text);assertEquals(at,stored.document.fetchedAt)
        assertEquals("true",stored.document.metadata["savedCapture"]);assertEquals(CrossPosts.originalKey(root),stored.document.metadata["root"])
        assertTrue(reader.store.findings(record.id).any {it.quotes[stored.document.id]==reply.body})
        val streams=reader.store.streams(record.id)
        assertEquals("unavailable",streams.single {it.kind=="platform"}.status)
        val saved=streams.single {it.kind=="saved"};assertEquals("limited",saved.status);assertEquals(at,saved.fetchedAt);assertTrue(saved.message.startsWith("Saved "))
        assertNotNull(reader.store.savedView(record.id));assertEquals(before,repo.gateway.protectionStats())
    }

    @Test fun olderCapturedRepliesNeverReplaceNewerCachedEditsOrTheirReceipts(): Unit=runBlocking {
        val model=TraceModel();usefulComments(model)
        val record=fixture();val root=record.snapshot.roots.single()
        val old=comment(root,1).copy(body="A new battery is required.")
        val newer=old.copy(body="Correction: the connector was reseated; a new battery is not required.")
        reader.discussionRead={_,_->Page(listOf(root,newer))};read(record.id)
        val id="comment:${CrossPosts.originalKey(newer)}";val beforeDocument=reader.store.document(record.id,id)!!
        val units=reader.store.checkpointUnits(record.id);val calls=model.extractions.size
        capture(root,listOf(CapturedThread(root,listOf(old),capturedAt=System.currentTimeMillis()-CachePolicy.DAY)))
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live source unavailable")}
        read(record.id);ready(record.id)
        assertEquals(beforeDocument,reader.store.document(record.id,id));assertEquals(units,reader.store.checkpointUnits(record.id));assertEquals(calls,model.extractions.size)
        assertTrue(reader.store.findings(record.id).any {it.quotes[id]==newer.body})
        assertTrue(reader.store.findings(record.id).none {it.quotes.values.contains(old.body)})
    }

    @Test fun savedThreadsCannotImportAnotherCardOrUnattachedReplies(): Unit=runBlocking {
        val model=TraceModel();usefulComments(model)
        val record=fixture();val root=record.snapshot.roots.single();val other=fixture().snapshot.roots.single()
        val expected=comment(root,1).copy(body="The connector repair is covered in the service manual.")
        val foreign=comment(other,2).copy(body="An unrelated account describes a different machine.")
        val unattached=comment(root,3).copy(parentId=other.id,body="This reply belongs to an unrelated conversation.")
        capture(root,listOf(CapturedThread(root,listOf(expected,unattached),capturedAt=System.currentTimeMillis()-1000),CapturedThread(other,listOf(foreign),capturedAt=System.currentTimeMillis()-1000)))
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live source unavailable")}
        read(record.id);ready(record.id)
        val comments=reader.store.documents(record.id).map {it.document}.filter {it.kind=="comment"}
        assertEquals(listOf(expected.body),comments.map {it.text})
        assertTrue(model.extractions.none {it.contains(foreign.body)||it.contains(unattached.body)})
    }

    @Test fun validatedSavedFusionCanSupplyAnotherRootAlreadyInThisCard(): Unit=runBlocking {
        val model=TraceModel();usefulComments(model)
        val x=savedPost();val blue=savedPost(x.body,Platform.BLUESKY)
        val record=reader.store.resolve(contextSnapshot(repo,x,listOf(x,blue)));owners+=record.id
        val reply=blue.copy(id="995$suffix",canonicalUrl="https://bsky.app/profile/repair-reader.test/post/995$suffix",author="Repair reader",body="The service manual lists the compatible connector.",parentId=blue.id)
        capture(x,listOf(CapturedThread(blue,listOf(reply),capturedAt=System.currentTimeMillis()-1000)),listOf(x,blue))
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live source unavailable")}
        read(record.id);ready(record.id)
        val document=reader.store.document(record.id,"comment:${CrossPosts.originalKey(reply)}")!!.document
        assertEquals(reply.body,document.text);assertEquals(CrossPosts.originalKey(blue),document.metadata["root"])
        assertTrue(reader.store.findings(record.id).any {it.quotes[document.id]==reply.body})
    }

    @Test fun savedCopyPassLimitRotatesToPreviouslyUnscannedRootsOnRefresh(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val roots=List(9){fixture().snapshot.roots.single()}
        val record=reader.store.resolve(ContextSnapshot(roots,roots.first().body,"","saved-many-$suffix"));owners+=record.id
        val replies=roots.mapIndexed {index,root->comment(root,index+1)}
        roots.zip(replies).forEach {(root,reply)->capture(root,listOf(CapturedThread(root,listOf(reply),capturedAt=System.currentTimeMillis()-1000)))}
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live source unavailable")}
        read(record.id);ready(record.id)
        val missing=replies.filter {reader.store.document(record.id,"comment:${CrossPosts.originalKey(it)}")==null}
        assertEquals(1,missing.size)
        assertTrue(reader.store.streams(record.id).any {it.id=="saved-copy-limit"&&it.status=="limited"})
        val units=reader.store.checkpointUnits(record.id)
        read(record.id);ready(record.id)
        assertTrue(replies.all {reader.store.document(record.id,"comment:${CrossPosts.originalKey(it)}")!=null})
        assertEquals(units+1,reader.store.checkpointUnits(record.id))
        assertTrue(reader.store.streams(record.id).filter {it.kind=="saved"}.all {it.status=="limited"})
    }

    @Test fun interruptedRefreshResumesWithoutReviewingSavedSourcesAgain(): Unit=runBlocking {
        val model=TraceModel();usefulComments(model)
        val record=fixture();val root=record.snapshot.roots.single();val at=System.currentTimeMillis()-1000
        val reply=comment(root,1).copy(body="The connector can be repaired before replacing the battery.")
        capture(root,listOf(CapturedThread(root,listOf(reply),capturedAt=at)))
        val entered=CompletableDeferred<Unit>();var interrupt=true
        reader.discussionRead={_,previous->if(previous==null)Page(listOf(root),next="saved-resume")else {
            if(interrupt){entered.complete(Unit);awaitCancellation()};Page(listOf(root))
        }}
        val work=launch {read(record.id)};withTimeout(10_000){entered.await()};work.cancelAndJoin()
        val calls=model.extractions.size;val units=reader.store.checkpointUnits(record.id)
        assertEquals(2,calls);assertEquals(2,units)
        val cycle=reader.store.record(record.id)!!.cycleStartedAt;assertTrue(cycle>0)
        val saved=reader.store.streams(record.id).single {it.kind=="saved"}
        assertEquals(cycle.toString(),reader.store.streams(record.id).single {it.kind=="platform"}.work["savedCycle"])
        interrupt=false;read(record.id);ready(record.id)
        assertEquals(calls,model.extractions.size);assertEquals(units,reader.store.checkpointUnits(record.id))
        assertEquals(saved,reader.store.streams(record.id).single {it.kind=="saved"})
    }

    @Test fun savedSharedRepliesRespectTheOptInAndKeepLiveCompatibleIdentifiers(): Unit=runBlocking {
        val model=TraceModel();usefulComments(model)
        val record=fixture();val root=record.snapshot.roots.single();val at=System.currentTimeMillis()-1000
        val url=AnywhereUrls.root(root,repo.routes)
        val shared=AnywherePage(url,listOf(AnywhereComment("saved-reply-$suffix","The service manual confirms the connector pinout.","Repair reader")),CommentOrder.NEWEST,at)
        capture(root,listOf(CapturedThread(root,emptyList(),capturedAt=at,commentAnywhere=shared)))
        reader.discussionRead={_,_->throw IllegalStateException("Fixture live source unavailable")}
        read(record.id);ready(record.id)
        assertTrue(reader.store.documents(record.id).none {it.document.id.startsWith("shared:")})
        repo.prefs.communityComments=true
        read(record.id);ready(record.id)
        val id="shared:${Repository.hash(url)}:saved-reply-$suffix"
        val document=reader.store.document(record.id,id)!!.document
        assertEquals(shared.items.single().text,document.text);assertEquals(url,document.url);assertEquals(at,document.fetchedAt)
        assertEquals("Repair reader",document.author);assertEquals("true",document.metadata["savedCapture"])
        assertEquals("unavailable",reader.store.streams(record.id).single {it.kind=="shared"}.status)
    }

    @Test fun bookmarkingBeforeCreatingContextProtectsItsVeryFirstPublication(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val post=savedPost()
        val record=reader.store.resolve(contextSnapshot(repo,post,listOf(post)));owners+=record.id
        model.beforeExtract={assertTrue("Saved admission must precede inference",reader.store.record(record.id)!!.saved)}
        var checkedFirst=false
        reader.discussionRead={_,_->
            assertTrue(reader.store.record(record.id)!!.saved)
            assertNotNull("First publication must already have a protected snapshot",reader.store.savedView(record.id))
            checkedFirst=true;throw IllegalStateException("Fixture live source unavailable")
        }
        read(record.id);ready(record.id);assertTrue(checkedFirst)
        assertNotNull(reader.store.savedView(record.id))
    }

    @Test fun unchangedNoiseOnlyRefreshGeneratesNoNewReviewOrSynthesis(): Unit = runBlocking {
        val model = TraceModel(); reader.modelFactory = { model }
        val record = fixture(); val root = record.snapshot.roots.single(); val reply = comment(root, 1)
        val sourceReads = AtomicInteger(); reader.discussionRead = { _, _ -> sourceReads.incrementAndGet(); Page(listOf(root, reply)) }
        val beforeNetwork = repo.gateway.protectionStats()
        read(record.id); val initial = ready(record.id)
        assertEquals(2, model.extractions.size); assertEquals(1, model.summaries.get()); assertFalse(initial.brief.isBlank())
        val units = reader.store.checkpointUnits(record.id)
        read(record.id); val refreshed = ready(record.id)
        assertEquals(2, sourceReads.get()); assertEquals(2, model.extractions.size); assertEquals(1, model.summaries.get())
        assertEquals(units, reader.store.checkpointUnits(record.id)); assertEquals(initial.brief, refreshed.brief)
        assertEquals(initial.findingStamp, refreshed.findingStamp); assertEquals(initial.updatedAt, refreshed.updatedAt)
        assertEquals(beforeNetwork, repo.gateway.protectionStats())
    }

    @Test fun changedEvidenceAndSpeakerRecomposeAnUnchangedFindingWithoutReplayingUnchangedRefreshes(): Unit = runBlocking {
        val trace=TraceModel()
        reader.modelFactory={object:ContextModel by trace {
            override suspend fun generate(system: String,user: String,maxTokens: Int): String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                val response=trace.generate(system,user,maxTokens)
                if(input["kind"]?.asString!="comment")return response
                val text=input["text"].asString
                return JsonObject().apply {
                    addProperty("status","signal")
                    add("findings",JsonArray().apply {add(JsonObject().apply {
                        // Deliberately unchanged finding words expose reuse
                        // incorrectly keyed only to generated prose.
                        addProperty("text","A commenter reports an inspection for ${text.substringBefore(':')}.")
                        addProperty("kind","experience");addProperty("quote",text)
                    })})
                }.toString()
            }
        }}
        val record=fixture();val root=record.snapshot.roots.single()
        var replies=(1..4).map {comment(root,it).copy(body="Port $it: the connector inspection is incomplete.")}
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        read(record.id);ready(record.id)
        assertTrue("Five findings should exercise actual compaction",trace.summaries.get()>0)
        var previous=trace.summaries.get()
        val oldRoot=reader.store.compactions(record.id).maxBy {it.level}.id
        replies=replies.toMutableList().apply {this[0]=this[0].copy(body="Port 1: the connector inspection has now been completed.")}
        read(record.id);ready(record.id)
        assertTrue("Changed supporting words must reach synthesis",trace.summaries.get()>previous)
        assertNotEquals(oldRoot,reader.store.compactions(record.id).maxBy {it.level}.id)
        assertTrue(reader.store.view(record.id)!!.findings.any {it.quotes.values.contains(replies[0].body)})
        previous=trace.summaries.get()
        replies=replies.toMutableList().apply {this[0]=this[0].copy(author="Casey, workshop volunteer")}
        read(record.id);ready(record.id)
        assertTrue("Changed speaker associations must reach synthesis",trace.summaries.get()>previous)
        previous=trace.summaries.get()
        val extractions=trace.extractions.size
        read(record.id);ready(record.id)
        assertEquals("An unchanged refresh must reuse its reviews",extractions,trace.extractions.size)
        assertEquals("An unchanged refresh must reuse its publication",previous,trace.summaries.get())
    }

    @Test fun selectingAnotherModelReconsidersOldReviewsAndRecomposesIdenticalFindingWords():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val replies=publicationReplies(root)
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        val firstReviews=AtomicInteger();val firstSummaries=AtomicInteger()
        val firstBrief="The first model reports a limited connector inspection."
        reader.modelFactory={publicationModel(firstBrief,firstReviews,firstSummaries)}
        read(record.id);ready(record.id)
        val before=reader.store.view(record.id)!!;assertTrue(firstSummaries.get()>0)
        val firstModel=repo.ai.contextIdentity()
        val selected=repo.ai.preferences.provider("text")
        repo.ai.preferences.provider("text",selected.copy(model="replacement-model-$suffix"))
        repo.ai.refresh();assertNotEquals(firstModel,repo.ai.contextIdentity())
        val nextReviews=AtomicInteger();val nextSummaries=AtomicInteger()
        val nextBrief="The replacement model reports that the inspection is limited to the stated connector."
        reader.modelFactory={publicationModel(nextBrief,nextReviews,nextSummaries)}
        read(record.id);ready(record.id)
        val after=reader.store.view(record.id)!!
        assertEquals(before.findings,after.findings)
        assertTrue("A different model must reconsider prior model-dependent source reviews",nextReviews.get()>0)
        assertTrue("A different model must compose its own overview even when findings are identical",nextSummaries.get()>0)
        assertEquals(nextBrief,after.record.brief);assertEquals(repo.ai.contextIdentity(),after.record.model)
        val reviewed=nextReviews.get();val composed=nextSummaries.get()
        read(record.id);ready(record.id)
        assertEquals("The next unchanged refresh reuses the new model's work",reviewed,nextReviews.get())
        assertEquals(composed,nextSummaries.get())
    }

    @Test fun windowAndRecipeChangesReconsiderReviewsWhileDiagnosticMetadataDoesNot():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        reader.discussionRead={_,_->Page(listOf(root)+publicationReplies(root))}
        val reviews=AtomicInteger();val summaries=AtomicInteger()
        var window=2048;var plan=ContextProcessingPlan.SINGLE
        reader.modelFactory={publicationModel("The inspection is limited to the stated connector.",reviews,summaries,window,plan)}
        read(record.id);ready(record.id)
        var reviewed=reviews.get();var composed=summaries.get()
        window=4096
        read(record.id);ready(record.id)
        assertTrue(reviews.get()>reviewed);assertTrue(summaries.get()>composed)
        reviewed=reviews.get();composed=summaries.get()
        plan=plan.copy(summaryGroupLimit=8)
        read(record.id);ready(record.id)
        assertTrue(reviews.get()>reviewed);assertTrue(summaries.get()>composed)
        reviewed=reviews.get();composed=summaries.get()
        plan=plan.copy(reason="Fresh metadata observation, same executable recipe")
        read(record.id);ready(record.id)
        assertEquals(reviewed,reviews.get());assertEquals(composed,summaries.get())
    }

    @Test fun legacyUnboundProseIsReplacedByBoundReceiptsOnce():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        reader.discussionRead={_,_->Page(listOf(root)+publicationReplies(root))}
        val reviews=AtomicInteger();val summaries=AtomicInteger()
        reader.modelFactory={publicationModel("The inspection is limited to the stated connector.",reviews,summaries)}
        read(record.id);ready(record.id)
        val before=reader.store.record(record.id)!!;val reviewed=reviews.get();val composed=summaries.get()
        reader.store.save(before.copy(brief="Legacy prose without a verified composition binding.",compositionPolicy=null))
        read(record.id);ready(record.id)
        assertEquals("Compatible source reviews survive legacy publication repair",reviewed,reviews.get())
        assertEquals("Receipts already bound to this exact model and recipe remain reusable",composed,summaries.get())
        assertEquals(before.brief,reader.store.record(record.id)!!.brief)
        assertNotNull(reader.store.record(record.id)!!.compositionPolicy)
        val repaired=summaries.get();read(record.id);ready(record.id);assertEquals(repaired,summaries.get())
    }

    private suspend fun failedReplacementReviewRetainsSources(failedOutput:String) {
        val record=fixture();val root=record.snapshot.roots.single()
        repo.dao.putBookmark(BookmarkRow(repo.key(root),root.platform.id,repo.gson.toJson(root),System.currentTimeMillis()))
        reader.discussionRead={_,_->Page(listOf(root)+publicationReplies(root))}
        reader.modelFactory={publicationModel("The original complete connector inspection.",AtomicInteger(),AtomicInteger())}
        read(record.id);ready(record.id)
        val before=reader.store.view(record.id)!!;val saved=reader.store.savedView(record.id)!!
        val selected=repo.ai.preferences.provider("text")
        repo.ai.preferences.provider("text",selected.copy(model="replacement-model-$suffix"));repo.ai.refresh()
        val base=TraceModel()
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                return if(input["kind"]?.asString=="comment")failedOutput else base.generate(system,user,maxTokens)
            }
        }}
        read(record.id);val pending=ready(record.id)
        assertEquals("partial",pending.status);assertFalse(pending.stale)
        assertTrue(pending.brief.isNotBlank());assertEquals(repo.ai.contextIdentity(),pending.model)
        assertEquals(before.record.brief,reader.store.earlierView(record.id)!!.record.brief)
        assertEquals(saved,reader.store.savedView(record.id))
        ContextStore(context).use {reopened->assertEquals(pending.brief,reopened.view(record.id)!!.record.brief);assertEquals(before.record.brief,reopened.earlierView(record.id)!!.record.brief);assertEquals(saved,reopened.savedView(record.id))}
        val next="The replacement model's complete connector inspection."
        reader.modelFactory={publicationModel(next,AtomicInteger(),AtomicInteger())}
        read(record.id);val resumed=ready(record.id)
        assertEquals(next,resumed.brief);assertFalse(resumed.stale);assertEquals(repo.ai.contextIdentity(),resumed.model)
        assertEquals(saved,reader.store.savedView(record.id))
    }

    @Test fun failedReplacementReviewRetainsThePublishedAndSavedSourcesUntilResume():Unit=runBlocking {
        failedReplacementReviewRetainsSources("invalid review")
    }

    @Test fun ambiguousReplacementReviewRetainsThePublishedAndSavedSourcesUntilResume():Unit=runBlocking {
        failedReplacementReviewRetainsSources("""{"status":"signal","status":"no_signal","findings":[]}""")
    }

    @Test fun changingModelsDuringCompositionCannotRelabelOrPublishTheOldResponse():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        reader.discussionRead={_,_->Page(listOf(root)+publicationReplies(root))}
        val reviews=AtomicInteger();val summaries=AtomicInteger()
        reader.modelFactory={publicationModel("The previous complete connector inspection.",reviews,summaries)}
        read(record.id);ready(record.id)
        val before=reader.store.view(record.id)!!
        reader.store.save(before.record.copy(compositionPolicy=null))
        // Force an actual response; same-profile compaction receipts would
        // otherwise repair the unbound publication without waking the model.
        reader.store.pruneCompactions(record.id,emptySet())
        reader.modelFactory={publicationModel("This response belongs to the old model.",reviews,summaries,beforeSummary={
            val selected=repo.ai.preferences.provider("text")
            repo.ai.preferences.provider("text",selected.copy(model="changed-during-summary-$suffix"));repo.ai.refresh()
        })}
        read(record.id)
        val after=reader.store.record(record.id)!!
        assertEquals("paused",after.status);assertTrue(after.message.startsWith("Model changed"))
        assertEquals(before.record.brief,after.brief);assertEquals(before.record.model,after.model)
        assertEquals(before.findings,reader.store.view(record.id)!!.findings)
        assertNotEquals(repo.ai.contextIdentity(),after.model)
    }

    @Test fun anObservationTypeChangeRecomposesEvenWhenFindingAndQuoteWordsStayTheSame():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        reader.discussionRead={_,_->Page(listOf(root)+publicationReplies(root))}
        val extract=ContextDocument("media-$suffix","1","The available text describes a connector inspection.",author="Media extract",kind="article",metadata=mapOf("root" to CrossPosts.originalKey(root),"medium" to "Published captions"))
        reader.store.ingest(record.id,listOf(extract))
        val reviews=AtomicInteger();val summaries=AtomicInteger();val inputs=CopyOnWriteArrayList<JsonObject>()
        val base=publicationModel("Sources describe the limited connector inspection.",reviews,summaries)
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject;if(input.has("findings"))inputs+=input
                return base.generate(system,user,maxTokens)
            }
        }}
        read(record.id);ready(record.id);val before=reader.store.view(record.id)!!;val composed=summaries.get()
        reader.store.ingest(record.id,listOf(extract.copy(metadata=extract.metadata+mapOf("medium" to "Local speech transcription"))))
        read(record.id);ready(record.id)
        assertEquals(before.findings.associateBy {it.id},reader.store.findings(record.id).associateBy {it.id})
        assertTrue(summaries.get()>composed)
        assertTrue(inputs.last().toString().contains("transcribed_audio"))
        val settled=summaries.get();read(record.id);ready(record.id);assertEquals(settled,summaries.get())
    }

    @Test fun aManyEditionBriefKeepsPublicationTimesInItsEvidence():Unit=runBlocking {
        val original=fixture();val first=original.snapshot.roots.single();val start=java.time.Instant.parse("2026-10-01T12:00:00Z")
        val roots=(0..62).map {i->first.copy(id=if(i==0)first.id else "996$suffix$i",canonicalUrl=if(i==0)first.canonicalUrl else "https://x.com/library/status/996$suffix$i",author="Library",body="The entrance reopens tomorrow.",publishedAt=start.plusSeconds(i*86_400L).toEpochMilli())}+
            first.copy(id="997$suffix",canonicalUrl="https://x.com/noticeboard/status/997$suffix",author="Undated noticeboard",body="The entrance reopens tomorrow.",publishedAt=null)
        posts+=roots.drop(1)
        val record=reader.store.resolve(original.snapshot.copy(roots=roots,text=roots.first().body,revision="many-editions-$suffix"));owners+=record.id
        reader.discussionRead={root,_->Page(listOf(root))}
        reader.modelFactory={object:ContextModel by TraceModel() {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                if(JsonParser.parseString(user).asJsonObject.has("findings"))return """{"text":"The entrance reopens tomorrow."}"""
                val text=JsonParser.parseString(user).asJsonObject["text"].asString
                return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {addProperty("text",text);addProperty("quote",text);addProperty("kind","brief")})})}.toString()
            }
        }}
        read(record.id);val result=ready(record.id)
        val editions=ContextSources.publications(reader.store.document(record.id,"merged:${record.id}")!!.document)
        assertTrue(editions.any {it.time?.instant==start})
        assertTrue(editions.any {it.time?.instant==start.plusSeconds(62*86_400L)})
        assertTrue(editions.any {it.speaker=="Undated noticeboard"&&it.time==null})
        assertFalse(result.brief.contains("2026"))
        assertEquals(64,ContextSources.publications(reader.store.document(record.id,"merged:${record.id}")!!.document).size)
    }

    @Test fun failedDateRereviewKeepsThePreviouslyPublishedContextUntilItsSourceCanResume():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single();var replies=publicationReplies(root)
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        reader.modelFactory={publicationModel("The complete original inspection context.",AtomicInteger(),AtomicInteger())}
        read(record.id);ready(record.id);val before=reader.store.view(record.id)!!
        replies=replies.toMutableList().apply {this[0]=this[0].copy(publishedAt=this[0].publishedAt!!+86_400_000L)}
        val base=publicationModel("An incomplete subset must not replace the published context.",AtomicInteger(),AtomicInteger())
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                return if(input["kind"]?.asString=="comment")"invalid review"else base.generate(system,user,maxTokens)
            }
        }}
        read(record.id);val pending=ready(record.id)
        assertEquals("partial",pending.status);assertFalse(pending.stale)
        assertTrue(pending.brief.isNotBlank());assertNotEquals(before.record.brief,pending.brief)
        assertEquals(before.record.brief,reader.store.earlierView(record.id)!!.record.brief)
        assertTrue(reader.store.hasUnreviewed(record.id,before.documents.map {it.document.id}))
        reader.modelFactory={publicationModel("The refreshed context includes the newly dated inspection.",AtomicInteger(),AtomicInteger())}
        read(record.id);val resumed=ready(record.id)
        assertFalse(resumed.stale);assertEquals("The refreshed context includes the newly dated inspection.",resumed.brief)
        assertFalse(reader.store.hasUnreviewed(record.id))
    }

    @Test fun changedPublicationTimeRecomposesIdenticalFindingWordsAndThenReusesThem():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        var replies=publicationReplies(root)
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        val reviews=AtomicInteger();val summaries=AtomicInteger();val inputs=CopyOnWriteArrayList<JsonObject>()
        val base=publicationModel("The inspection is limited to the stated connector.",reviews,summaries)
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val parsed=JsonParser.parseString(user).asJsonObject
                if(parsed.has("findings"))inputs+=parsed
                return base.generate(system,user,maxTokens)
            }
        }}
        read(record.id);ready(record.id)
        val before=reader.store.view(record.id)!!;val composed=summaries.get()
        replies=replies.toMutableList().apply {this[0]=this[0].copy(publishedAt=this[0].publishedAt!!+86_400_000L)}
        val expected=java.time.Instant.ofEpochMilli(replies[0].publishedAt!!).toString()
        read(record.id);ready(record.id)
        assertEquals(before.findings.associateBy {it.id},reader.store.findings(record.id).associateBy {it.id})
        assertTrue("A changed date cannot take the identical-meaning shortcut",summaries.get()>composed)
        assertTrue(reader.store.view(record.id)!!.documents.any {it.document.metadata["published"]==expected})
        assertTrue("Publication bookkeeping stays in evidence rather than becoming an inferred event date",inputs.last()["findings"].asJsonArray.none {it.asJsonObject.has("source_times")})
        val settled=summaries.get();read(record.id);ready(record.id);assertEquals(settled,summaries.get())
    }

    @Test fun mergedPostKeepsTheKnownTimesOfEveryEditionInItsEvidence():Unit=runBlocking {
        val original=fixture();val old=original.snapshot.roots.single()
        val first=old.copy(author="Library",body="The entrance reopens tomorrow.",publishedAt=java.time.Instant.parse("2026-10-19T12:00:00Z").toEpochMilli())
        val second=first.copy(id="other-$suffix",platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/library.test/post/$suffix",publishedAt=first.publishedAt!!+86_400_000L)
        val undated=first.copy(id="undated-$suffix",canonicalUrl="https://x.com/local_bulletin/status/999$suffix",author="Local bulletin",publishedAt=null)
        posts+=listOf(second,undated)
        val record=reader.store.resolve(original.snapshot.copy(roots=listOf(first,second,undated),text=first.body,revision="dated-editions-$suffix"))
        owners+=record.id
        reader.discussionRead={root,_->Page(listOf(root))}
        reader.modelFactory={object:ContextModel by TraceModel() {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings"))return """{"text":"The entrance reopens tomorrow."}"""
                val text=input["text"].asString
                return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {addProperty("text",text);addProperty("quote",text);addProperty("kind","brief")})})}.toString()
            }
        }}
        read(record.id);val result=ready(record.id)
        val document=reader.store.document(record.id,"merged:${record.id}")!!.document
        assertEquals(3,ContextSources.publications(document).size)
        val editions=ContextSources.publications(document)
        assertEquals(2,editions.count {it.time!=null})
        assertTrue(editions.any {it.speaker=="Local bulletin"&&it.time==null})
        assertFalse(result.brief.contains("2026"))
        assertTrue(result.brief.contains("tomorrow"));assertFalse(result.brief.contains("21 Oct"))
        assertEquals(1,reader.store.findings(record.id).size)
    }

    @Test fun exactAdditionalCopyPublishesItsAuthorAndEvidenceWithoutRewritingTheBrief():Unit=runBlocking {
        val trace=TraceModel()
        reader.modelFactory={object:ContextModel by trace {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                val baseline=trace.generate(system,user,maxTokens)
                if(input["kind"]?.asString!="comment")return baseline
                val text=input["text"].asString
                return JsonObject().apply {
                    addProperty("status","signal")
                    add("findings",JsonArray().apply {add(JsonObject().apply {
                        addProperty("text",text);addProperty("kind","context");addProperty("quote",text)
                    })})
                }.toString()
            }
        }}
        val record=fixture();val root=record.snapshot.roots.single()
        val originals=(1..4).map {comment(root,it).copy(body="Point $it: the inspection covers this particular connector, not every battery.")}
        var replies=originals
        reader.discussionRead={_,_->Page(listOf(root)+replies)}
        read(record.id);ready(record.id)
        assertTrue("Exercise generated compaction, not the short-ledger shortcut",trace.summaries.get()>0)
        val before=reader.store.view(record.id)!!;val summaries=trace.summaries.get();val extractions=trace.extractions.size
        val duplicate=originals.first().copy(id="copy-$suffix",canonicalUrl="https://x.com/another_reader/status/995$suffix",author="Another reader")
        replies=originals+duplicate
        read(record.id);ready(record.id)
        val after=reader.store.view(record.id)!!
        assertEquals("An exact copy must not wake source extraction",extractions,trace.extractions.size)
        assertEquals("An exact copy must not rewrite the brief",summaries,trace.summaries.get())
        assertEquals(before.record.brief,after.record.brief);assertNotEquals(before.record.findingStamp,after.record.findingStamp)
        val added=after.documents.single {it.document.author=="Another reader"}.document
        val contribution=after.findings.single {it.text==originals.first().body}
        assertEquals(2,contribution.sources.size);assertEquals(duplicate.body,contribution.quotes[added.id])
        val reopened=ContextStore(context)
        try {
            val restored=reopened.view(record.id)!!
            assertEquals(after.record.brief,restored.record.brief);assertEquals(after.record.findingStamp,restored.record.findingStamp)
            assertEquals(contribution,restored.findings.single {it.id==contribution.id})
        }finally {reopened.close()}
        read(record.id);ready(record.id)
        assertEquals(summaries,trace.summaries.get());assertEquals(extractions,trace.extractions.size)
        // Changed words and a genuine new qualification are not source copies.
        replies=originals+duplicate.copy(body=duplicate.body+" The inspection has since been withdrawn.")
        read(record.id);ready(record.id)
        assertTrue(trace.summaries.get()>summaries)
    }

    @Test fun interruptedPageFetchResumesItsCursorWithoutReviewingCommittedSourcesAgain(): Unit = runBlocking {
        val model = TraceModel(); reader.modelFactory = { model }
        val record = fixture(); val root = record.snapshot.roots.single(); val first = comment(root, 1); val second = comment(root, 2)
        val calls = CopyOnWriteArrayList<String>(); val enteredSecond = CompletableDeferred<Unit>(); var interruptSecond = true
        reader.discussionRead = { _, previous ->
            val cursor = previous?.next ?: "first"; calls += cursor
            if (previous == null) Page(listOf(root, first), next = "cursor-two") else {
                if (interruptSecond) { enteredSecond.complete(Unit); awaitCancellation() }
                Page(listOf(root, second))
            }
        }
        val job = launch { read(record.id) }
        withTimeout(10_000) { enteredSecond.await() }; job.cancelAndJoin()
        assertEquals(listOf("first", "cursor-two"), calls.toList())
        val platform = reader.store.streams(record.id).single { it.kind == "platform" }
        assertEquals("cursor-two", platform.previous!!.next)
        assertEquals(2, reader.store.checkpointUnits(record.id)); assertTrue(reader.store.documents(record.id).all { it.processed })
        interruptSecond = false; read(record.id); ready(record.id)
        assertEquals(listOf("first", "cursor-two", "cursor-two"), calls.toList())
        assertEquals(3, model.extractions.size); assertEquals(3, model.extractions.toSet().size)
        assertEquals(3, reader.store.checkpointUnits(record.id)); assertEquals(1, model.summaries.get())
    }

    @Test fun aSnapshotChangedDuringInferenceCannotBeMarkedReadyByTheOldRun(): Unit = runBlocking {
        val model = TraceModel(); reader.modelFactory = { model }
        val record = fixture(); val began = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        model.beforeExtract = { began.complete(Unit); release.await() }
        val job = launch { read(record.id) }
        withTimeout(10_000) { began.await() }
        val changed = record.snapshot.copy(text = "The workshop changed its repair arrangements.", revision = "changed-$suffix")
        assertEquals(record.id, reader.store.resolve(changed).id)
        release.complete(Unit); job.join()
        val waiting = reader.store.record(record.id)!!
        assertEquals(changed.revision, waiting.snapshot.revision); assertEquals("queued", waiting.status)
        assertTrue(waiting.brief.isBlank()); assertEquals(0L, waiting.checkedAt)
        model.beforeExtract = {}; read(record.id)
        val completed = ready(record.id); assertEquals(changed.revision, completed.snapshot.revision)
        assertEquals(changed.text, reader.store.document(record.id, "merged:${record.id}")!!.document.text)
    }

    @Test fun commentSourceLinksArePlannedWithoutModelSignalAndRespectTheExplicitCardCap(): Unit = runBlocking {
        val model = TraceModel(); reader.modelFactory = { model }
        reader.preferences.update(reader.preferences.state.value.copy(commentLinks = 8))
        val record = fixture(); val root = record.snapshot.roots.single()
        val urls = (1..12).map { "https://context-evidence.invalid/articles/$suffix/$it" }
        val reply = comment(root, 1, urls)
        reader.discussionRead = { _, _ -> Page(listOf(root, reply)) }
        val beforeNetwork = repo.gateway.protectionStats()
        read(record.id); ready(record.id)
        val streams = reader.store.streams(record.id)
        val links = streams.filter { it.kind == "link" }
        assertEquals(8, links.size); assertEquals(urls.take(8).toSet(), links.map { it.root.canonicalUrl }.toSet())
        assertTrue("Source omissions must remain visible", streams.any { it.status == "limited" && it.message.isNotBlank() })
        val commentDocument = reader.store.documents(record.id).single { it.document.kind == "comment" }
        assertTrue(commentDocument.processed)
        assertEquals(ContextUnitStatus.NO_SIGNAL, reader.store.outcomes(record.id, commentDocument.document.id, commentDocument.document.revision).single().status)
        assertEquals(beforeNetwork, repo.gateway.protectionStats())
    }

    @Test fun evictingOrdinaryPostCacheKeepsSavedContextAndItsOriginalSavedPublication(): Unit = runBlocking {
        val ordinary = fixture(); val saved = fixture(); val savedPost = saved.snapshot.roots.single()
        reader.store.publish(saved.copy(brief = "A useful saved repair explanation.", findingStamp = "baseline"))
        repo.dao.putBookmark(BookmarkRow(repo.key(savedPost), savedPost.platform.id, repo.gson.toJson(savedPost), System.currentTimeMillis()))
        withTimeout(10_000) { reader.store.revision.first { reader.store.record(saved.id)?.saved == true && reader.store.savedView(saved.id) != null } }
        reader.store.protect(saved.id, true)
        reader.store.publish(reader.store.record(saved.id)!!.copy(brief = "A newer repair explanation.", findingStamp = "updated"))
        repo.dao.removePosts((ordinary.snapshot.roots + saved.snapshot.roots).map(repo::key))
        reader.cleanup()
        assertNull(reader.store.record(ordinary.id)); assertTrue(reader.store.record(saved.id)!!.saved)
        assertEquals("A newer repair explanation.", reader.store.view(saved.id)!!.record.brief)
        assertEquals("A useful saved repair explanation.", reader.store.savedView(saved.id)!!.record.brief)
    }

    @Test fun cleanupDoesNotApplyAnOldPostCacheSnapshotToANewContext(): Unit=runBlocking {
        val old=fixture();repo.dao.removePosts(old.snapshot.roots.map(repo::key))
        val original=reader.cleanupCacheKeys
        var added: ContextRecord?=null
        reader.cleanupCacheKeys={
            val before=repo.dao.postCacheRows().map {it.key}.toSet()
            added=fixture()
            before
        }
        try {
            reader.cleanup()
            assertNull(reader.store.record(old.id))
            assertNotNull(reader.store.record(added!!.id))
            val doc=ContextDocument("cleanup-new-reply-$suffix","r1","The new workshop reply remains available.",kind="comment")
            reader.store.ingest(added!!.id,listOf(doc))
            assertEquals(doc,reader.store.document(added!!.id,doc.id)!!.document)
        }finally {reader.cleanupCacheKeys=original}
    }

    @Test fun cleanupPreservesARecordChangedOrProtectedWhileReadingThePostCache(): Unit=runBlocking {
        val changed=fixture();val protectedRecord=fixture()
        repo.dao.removePosts((changed.snapshot.roots+protectedRecord.snapshot.roots).map(repo::key))
        val original=reader.cleanupCacheKeys
        reader.cleanupCacheKeys={
            reader.store.save(changed.copy(brief="A newly published explanation.",updatedAt=System.currentTimeMillis(),status="partial"))
            reader.store.protect(protectedRecord.id,true)
            emptySet()
        }
        try {
            reader.cleanup()
            assertEquals("A newly published explanation.",reader.store.record(changed.id)!!.brief)
            assertTrue(reader.store.record(protectedRecord.id)!!.saved)
        }finally {reader.cleanupCacheKeys=original}
    }

    @Test fun manualRequestTakesOverAnAutomaticReadWithoutBusyLooping(): Unit = runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val record=fixture();val began=CompletableDeferred<Unit>()
        model.beforeExtract={began.complete(Unit);awaitCancellation()}
        val automatic=launch {reader.run(record.id,background=true)}
        withTimeout(10_000){began.await()}
        model.beforeExtract={}
        withTimeout(20_000){reader.run(record.id)}
        automatic.join()
        assertTrue(automatic.isCancelled)
        ready(record.id)
        assertTrue(reader.active.value.isEmpty())
    }

    @Test fun changedSupportingQuoteCanAddPracticalInformationToTheBrief(): Unit = runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val record=fixture();read(record.id)
        val first=reader.store.view(record.id)!!
        val revised=record.snapshot.copy(text=record.snapshot.text+" Bring a small screwdriver.",revision="support-changed-$suffix")
        reader.store.resolve(revised)
        read(record.id)
        val latest=reader.store.view(record.id)!!
        assertNotEquals(first.record.brief,latest.record.brief)
        assertTrue(latest.record.brief.contains("small screwdriver"))
        assertNotEquals(first.record.findingStamp,latest.record.findingStamp)
        assertFalse(latest.record.stale)
        assertTrue(latest.findings.flatMap {it.quotes.values}.any {it.contains("small screwdriver")})
        assertEquals(revised.text,latest.documents.single().document.text)
        assertEquals(2,model.summaries.get())
    }

    @Test fun inactiveTemporaryMediaExpiresWithoutDeletingSavedProgress(): Unit = runBlocking {
        val record=fixture();val post=record.snapshot.roots.single()
        repo.dao.putBookmark(BookmarkRow(repo.key(post),post.platform.id,repo.gson.toJson(post),System.currentTimeMillis()))
        withTimeout(10_000){reader.store.revision.first {reader.store.record(record.id)?.saved==true}}
        reader.store.save(reader.store.record(record.id)!!.copy(status="paused"))
        val directory=File(context.cacheDir,"context-work/${record.id}").apply {mkdirs()}
        File(directory,"temporary-audio").writeText("Transient acquired bytes, not an explicit offline download.")
        assertTrue(directory.setLastModified(System.currentTimeMillis()-25L*60*60*1000))
        reader.cleanup()
        assertFalse(directory.exists())
        assertTrue(reader.store.record(record.id)!!.saved)
        assertEquals("paused",reader.store.record(record.id)!!.status)
    }

    @Test fun oneInvalidModelAnswerStaysUnreviewedWhileLaterSourcesAdvanceAndCanBeRetried(): Unit = runBlocking {
        failedAnswerDoesNotStopSources(false)
    }
    @Test fun oneExhaustedModelAnswerStaysUnreviewedWhileLaterSourcesProduceABrief(): Unit = runBlocking {
        failedAnswerDoesNotStopSources(true)
    }
    private suspend fun failedAnswerDoesNotStopSources(exhausted:Boolean) {
        val baseline=TraceModel();var repaired=false
        reader.modelFactory={object:ContextModel by baseline {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input["kind"]?.asString!="comment")return baseline.generate(system,user,maxTokens)
                val text=input["text"].asString
                if(exhausted&&text.startsWith("First")&&!repaired)throw AiResponseLimitException()
                return JsonObject().apply {
                    addProperty("status","signal")
                    add("findings",JsonArray().apply {add(JsonObject().apply {
                        addProperty("text",text);addProperty("kind",if(text.startsWith("First")&&!repaired)"unsupported-kind"else "context");addProperty("quote",text)
                    })})
                }.toString()
            }
        }}
        val record=fixture();val root=record.snapshot.roots.single()
        val first=comment(root,1).copy(body="First source describes a compatible replacement connector.")
        val later=comment(root,2).copy(body="Disconnect the battery before removing the screws.")
        reader.discussionRead={_,_->Page(listOf(root,first,later))}
        read(record.id);assertEquals("partial",ready(record.id).status)
        assertTrue(reader.store.record(record.id)!!.brief.isNotBlank())
        val documents=reader.store.documents(record.id)
        assertFalse(documents.single {it.document.text==first.body}.processed)
        assertTrue(documents.single {it.document.text==later.body}.processed)
        assertTrue(reader.store.findings(record.id).any {it.quotes.values.contains(later.body)})
        assertEquals(1,reader.store.streams(record.id).count {it.kind=="review"})
        assertTrue(reader.store.pendingDocuments(record.id).isEmpty())
        val completed=reader.store.checkpointUnits(record.id)
        repaired=true;read(record.id);ready(record.id)
        assertTrue(reader.store.documents(record.id).all {it.processed})
        assertEquals(completed+1,reader.store.checkpointUnits(record.id))
        assertTrue(reader.store.streams(record.id).none {it.kind=="review"})
    }

    @Test fun savedMaintenanceBootstrapsUntouchedMergedCopiesAndKeepsTheirOriginals(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model};reader.close()
        // This control adds an exact edition at the same publication instant.
        // A genuinely different timestamp is tested as interpretive context.
        val x=savedPost();val blue=savedPost(x.body,Platform.BLUESKY,x.publishedAt!!)
        val original=repo.dao.bookmarksByKeys(listOf(repo.key(x))).single().payload
        repo.dao.putSavedDetail(SavedDetailRow(repo.key(x),repo.gson.toJson(SavedCopyDetails("saved-$suffix",System.currentTimeMillis(),fusionRoots=listOf(x,blue)))))
        maintenanceScope(listOf(x,blue));val calls=CopyOnWriteArrayList<Platform>()
        reader.discussionRead={root,_->calls+=root.platform;Page(listOf(root))}
        reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        val before=repo.gateway.protectionStats()
        withTimeout(20_000){reader.maintain()}
        val waiting=savedOwner(x);owners+=waiting.id
        assertEquals(waiting.id,savedOwner(blue).id)
        assertEquals("queued",waiting.status);assertTrue(waiting.brief.isNotBlank())
        assertEquals(listOf(Platform.BLUESKY),calls.toList())
        assertTrue(reader.store.streams(waiting.id).any {it.root.platform==Platform.X&&it.status=="pending"})
        assertTrue(reader.store.streams(waiting.id).any {it.root.platform==Platform.BLUESKY&&it.kind=="platform"&&it.status=="done"})
        val completed=reader.store.checkpointUnits(waiting.id)
        assertEquals(1,model.extractions.size)
        withTimeout(20_000){reader.maintain()}
        val record=ready(savedOwner(x).id)
        assertEquals(completed,reader.store.checkpointUnits(record.id))
        assertFalse(record.stale)
        assertEquals(record.id,savedOwner(blue).id)
        assertEquals(setOf(CrossPosts.originalKey(x),CrossPosts.originalKey(blue)),record.snapshot.roots.map(CrossPosts::originalKey).toSet())
        assertEquals(setOf(Platform.X,Platform.BLUESKY),calls.toSet());assertEquals(2,calls.size)
        assertTrue(record.saved);assertFalse(record.requested);assertNull(reader.selected.value)
        assertNotNull(reader.store.savedView(record.id));assertEquals(1,model.extractions.size);assertEquals(1,model.summaries.get())
        assertEquals(original,repo.dao.bookmarksByKeys(listOf(repo.key(x))).single().payload)
        assertEquals(before,repo.gateway.protectionStats())
    }

    @Test fun savedMaintenanceRequiresOptInAndEnabledAiBeforeCreatingAnyContext(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model};val saved=savedPost();maintenanceScope(listOf(saved))
        reader.maintain()
        assertTrue(reader.store.forOriginals(listOf(repo.key(saved))).isEmpty());assertTrue(model.extractions.isEmpty())
        reader.preferences.update(reader.preferences.state.value.copy(saved=true));repo.ai.enabled(false)
        reader.maintain()
        assertTrue(reader.store.forOriginals(listOf(repo.key(saved))).isEmpty());assertTrue(model.extractions.isEmpty())
    }

    @Test fun failedSavedCardsKeepRetryFloorsWhileThePersistentCursorReachesOtherCopies(): Unit=runBlocking {
        val baseline=TraceModel();val attempted=CopyOnWriteArrayList<String>()
        reader.modelFactory={object:ContextModel by baseline {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("source_id")) {
                    val text=input["text"].asString;attempted+=text
                    if(text.startsWith("Unavailable model fixture"))throw IllegalStateException("Fixture model could not read this passage")
                }
                return baseline.generate(system,user,maxTokens)
            }
        }}
        val broken=savedPost("Unavailable model fixture: the repair workshop is waiting for compatible parts.")
        val saved=listOf(broken)+List(4){savedPost("Repair workshop note ${it+1}: bring a screwdriver and disconnect the battery before opening the case.")}
        maintenanceScope(saved);reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        withTimeout(20_000){reader.maintain()}
        assertEquals(3,attempted.size);assertEquals(3,saved.count {reader.store.forOriginals(listOf(repo.key(it))).isNotEmpty()})
        val failed=savedOwner(broken);assertEquals("paused",failed.status);assertEquals(0L,failed.checkedAt)
        assertTrue(failed.nextCheck>System.currentTimeMillis());assertTrue(failed.saved);assertFalse(failed.requested)
        val cursor=reader.preferences.savedMaintenanceCursor
        assertEquals(cursor,ContextPreferences(context).savedMaintenanceCursor)
        withTimeout(20_000){reader.maintain()}
        assertEquals(5,attempted.size);assertEquals(1,attempted.count {it.startsWith("Unavailable model fixture")})
        assertTrue(saved.drop(1).all {savedOwner(it).checkedAt>0})
        assertEquals(0L,savedOwner(broken).checkedAt)
    }

    @Test fun savedMaintenanceRechecksRemovalAndOptOutBetweenCards(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val first=savedPost("First workshop notice: safely disconnect the battery before removing the cover.")
        val removed=savedPost("Second workshop notice: a compatible replacement is available.")
        val last=savedPost("Third workshop notice: a loose connector can be repaired.")
        maintenanceScope(listOf(first,removed,last));reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        model.beforeExtract={input->if(input["text"].asString.startsWith("First"))repo.dao.removeBookmark(repo.key(removed))}
        withTimeout(20_000){reader.maintain()}
        assertEquals(2,model.extractions.size);assertTrue(reader.store.forOriginals(listOf(repo.key(removed))).isEmpty())
        assertTrue(savedOwner(first).checkedAt>0);assertTrue(savedOwner(last).checkedAt>0)
        val untouched=savedPost("Fourth workshop notice: use the service manual to identify compatible parts.")
        val optOut=savedPost("Fifth workshop notice: the team can inspect an ageing battery.")
        maintenanceScope(listOf(untouched,optOut));reader.preferences.savedMaintenanceCursor=""
        model.beforeExtract={reader.preferences.update(reader.preferences.state.value.copy(saved=false))}
        withTimeout(20_000){reader.maintain()}
        assertEquals(3,model.extractions.size)
        assertTrue(reader.store.forOriginals(listOf(repo.key(optOut))).isEmpty())
    }

    @Test fun concurrentSavedMaintenancePassesDoNotReviewAnOwnerTwice(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model};val saved=List(3){savedPost("Workshop session ${it+1}: volunteers will inspect the battery connector before replacing parts.")}
        maintenanceScope(saved);reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        model.beforeExtract={if(!entered.isCompleted){entered.complete(Unit);release.await()}}
        val first=launch {reader.maintain()};withTimeout(10_000){entered.await()}
        val second=launch {reader.maintain()};release.complete(Unit)
        withTimeout(20_000){joinAll(first,second)}
        assertEquals(3,model.extractions.size)
        assertEquals(3,saved.map {savedOwner(it).id}.distinct().size)
        assertTrue(saved.all {savedOwner(it).checkedAt>0})
    }

    @Test fun savedMaintenanceKeepsANewerContextSnapshotInsteadOfRestoringTheSavedText(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model};val saved=savedPost("The repair workshop first announced a replacement battery.")
        val newer=saved.copy(body="The workshop later repaired the connector, so no replacement battery is needed.")
        val snapshot=contextSnapshot(repo,newer,listOf(newer))
        val existing=reader.store.resolve(snapshot,requested=false);owners+=existing.id
        maintenanceScope(listOf(saved));reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        withTimeout(20_000){reader.maintain()}
        val current=ready(existing.id)
        assertEquals(snapshot,current.snapshot)
        assertEquals(newer.body,reader.store.document(existing.id,"merged:${existing.id}")!!.document.text)
        assertEquals(saved.body,repo.library.copy(repo.key(saved))!!.post.body)
        assertTrue(current.saved);assertFalse(current.requested)
    }

    @Test fun savedMaintenanceBoundsItsKeyScanAndAdvancesPastInvalidLocalCopies(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val saved=List(4){savedPost("Workshop update ${it+1}: compatible screws and connectors are available for the repair session.")}
        val invalid=saved.first()
        repo.dao.putSavedDetail(SavedDetailRow(repo.key(invalid),repo.gson.toJson(SavedCopyDetails("invalid-$suffix",System.currentTimeMillis(),fusionRoots=listOf(saved[1])))))
        maintenanceScope(saved);reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        var requestedLimit=0;val original=reader.maintenanceKeys
        reader.maintenanceKeys={cursor,limit->requestedLimit=limit;original(cursor,limit)}
        withTimeout(20_000){reader.maintain()}
        assertEquals(32,requestedLimit);assertEquals(3,model.extractions.size)
        assertTrue(reader.store.forOriginals(listOf(repo.key(invalid))).isEmpty())
        assertTrue(saved.drop(1).all {savedOwner(it).checkedAt>0})
    }

    @Test fun interruptedSavedMaintenanceKeepsItsAttemptFloorWithoutClaimingASuccessfulCheck(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model};val saved=savedPost();maintenanceScope(listOf(saved))
        reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        val entered=CompletableDeferred<Unit>();model.beforeExtract={entered.complete(Unit);awaitCancellation()}
        val work=launch {reader.maintain()};withTimeout(10_000){entered.await()};work.cancelAndJoin()
        val interrupted=savedOwner(saved)
        assertTrue(interrupted.saved);assertEquals(0L,interrupted.checkedAt)
        assertTrue(interrupted.nextCheck>System.currentTimeMillis())
        assertTrue(reader.store.checkpointUnits(interrupted.id)==0)
        withTimeout(10_000){reader.maintain()}
        assertEquals(1,model.extractions.size)
    }

    @Test fun savedMaintenanceKeepsExactLongRssPermalinkCursors(): Unit=runBlocking {
        val model=TraceModel();reader.modelFactory={model}
        val url="https://context-fixture.invalid/articles/"+"a".repeat(4096)+"/$suffix"
        val saved=Post("rss-$suffix",Platform.RSS,url,"Workshop blog","The repair guide explains how to inspect and reseat the battery connector.",publishedAt=System.currentTimeMillis())
        posts+=saved
        repo.dao.putBookmark(BookmarkRow(repo.key(saved),saved.platform.id,repo.gson.toJson(saved),System.currentTimeMillis()))
        assertTrue(repo.key(saved).length>512)
        assertEquals(repo.key(saved),repo.dao.bookmarkKeysAfter(repo.key(saved).dropLast(1),1,ContextPreferences.MAX_SAVED_CURSOR).single())
        maintenanceScope(listOf(saved));reader.preferences.update(reader.preferences.state.value.copy(saved=true))
        withTimeout(20_000){reader.maintain()}
        assertTrue(savedOwner(saved).checkedAt>0)
        assertEquals(repo.key(saved),reader.preferences.savedMaintenanceCursor)
        assertEquals(repo.key(saved),ContextPreferences(context).savedMaintenanceCursor)
    }
    @Test fun matchingClosedAnswersRetainTheirDifferentQuestionsAndSurviveAnUnchangedRefresh():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val first=comment(root,1).copy(body="Is it safe to charge a swollen battery?",author="Alex")
        val second=comment(root,2).copy(body="Did you replace the entire battery?",author="Sam")
        val a=comment(root,3).copy(body="No.",parentId=first.id,author="Repair volunteer")
        val b=comment(root,4).copy(body="No.",parentId=second.id,author="Jamie")
        val calls=AtomicInteger()
        reader.modelFactory={object:ContextModel {
            override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,1,4,4,4096,"Micro reply fixture",true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings"))return fixtureSummary(input)
                calls.incrementAndGet()
                return if(system.contains("\"relevant\""))"""{"relevant":true}"""else """{"keep":[1]}"""
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,first,a,second,b))}
        read(record.id);val published=ready(record.id)
        assertTrue(published.brief.contains(first.body));assertTrue(published.brief.contains(second.body))
        assertFalse(published.brief.contains("Repair volunteer"));assertFalse(published.brief.contains("Jamie"))
        assertTrue(reader.store.documents(record.id).any {it.document.author=="Repair volunteer"})
        assertTrue(reader.store.documents(record.id).any {it.document.author=="Jamie"})
        assertEquals(2,Regex("No\\.").findAll(published.brief).count())
        // Composition is now a separate model task. The durable evidence,
        // rather than this fixture's joining stub, owns exact parent identity.
        assertTrue(published.brief.contains(first.body))
        val input=reader.store.publicationInput(record.id)!!
        assertEquals(2,input.replyEvidence.contexts.references.size)
        assertTrue(input.replyEvidence.contexts.frames.values.all {it.sources.size==1&&!it.languagePending})
        val before=calls.get();read(record.id);ready(record.id)
        assertEquals(before,calls.get());assertEquals(published.brief,reader.store.record(record.id)!!.brief)
    }

    @Test fun decoratedPraiseSkipsTextInferenceButItsOriginalSourceRemainsReadable():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val reply=comment(root,1).copy(body="😂😂 Amazing! 👏👏")
        val screens=CopyOnWriteArrayList<JsonObject>()
        reader.modelFactory={object:ContextModel {
            override val contextTokens=2048
            override val maxOutputTokens=512
            override val shortDecisionTokens=32
            override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,1,4,4,4096,"Short-noise fixture",true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings"))return """{"text":"A practical battery repair session has been announced."}"""
                screens+=input
                return if(input.has("comment"))"{\"relevant\":true}"else "{\"keep\":[1]}"
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,reply))}
        read(record.id);ready(record.id)
        assertTrue("Known decorated praise must not reach text inference",screens.isEmpty())
        val id="comment:${CrossPosts.originalKey(reply)}";val source=reader.store.document(record.id,id)!!.document
        assertEquals(reply.body,source.text)
        assertEquals(ContextUnitStatus.NO_SIGNAL,reader.store.outcomes(record.id,id,source.revision).single().status)
        ContextStore(context).use {reopened->assertEquals(reply.body,reopened.document(record.id,id)!!.document.text)}
        read(record.id);ready(record.id);assertTrue(screens.isEmpty())
    }

    @Test fun uncertainPostLanguageDoesNotVetoPreparedReplyEvidence():Unit=runBlocking {
        val record=fixture("Night lights 💡...");val root=record.snapshot.roots.single()
        val reply=comment(root,1).copy(body="I reseated the loose battery connector. The original battery is still working.",author="Jamie")
        val inputs=CopyOnWriteArrayList<JsonObject>()
        reader.modelFactory={object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val data=JsonParser.parseString(user).asJsonObject;inputs+=data
                if(!data.has("source_id"))return JsonObject().apply {addProperty("text",data["findings"].asJsonArray.joinToString(" "){it.asJsonObject["text"].asString})}.toString()
                val text=data["text"].asString
                return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {
                    addProperty("text",text);addProperty("kind",if(data["kind"].asString=="post")"brief"else "experience");addProperty("quote",text)
                })})}.toString()
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,reply))}
        read(record.id);val result=ready(record.id)
        assertTrue("A readable reply must be processed despite uncertain subject language",result.brief.contains(reply.body))
        val rootInput=inputs.first {it["kind"]?.asString=="post"};assertTrue(rootInput["source_language_unconfirmed"].asBoolean)
        val replyInput=inputs.first {it["kind"]?.asString=="comment"};assertTrue(replyInput["parent_uncertain"].asBoolean)
        assertFalse(reader.store.streams(record.id).any {it.id.startsWith("language:")})
        val before=inputs.size;read(record.id);ready(record.id);assertEquals(before,inputs.size)
    }

    @Test fun aLiteralSymbolAnswerIsNotLostToTheEarlyReactionFilter():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val question=comment(root,20).copy(body="Which pin label did the technician read?",author="Alex")
        val answer=comment(root,1).copy(body="+1",parentId=question.id,author="Repair volunteer")
        val screens=CopyOnWriteArrayList<JsonObject>()
        reader.modelFactory={object:ContextModel {
            override val contextTokens=2048
            override val maxOutputTokens=512
            override val shortDecisionTokens=32
            override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,1,4,4,4096,"Literal-symbol fixture",true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                fixtureBriefAdmission(user)?.let {
                    if(input["items"].asJsonArray.any {item->item.asJsonObject["text"].asString==answer.body}) {
                        if(input["support_review"]?.asBoolean==true)assertTrue(input["items"].asJsonArray.single().asJsonObject["reply_to"].asString.contains(question.body))
                        else screens+=input
                    }
                    return it
                }
                if(input.has("findings"))return fixtureSummary(input)
                assertTrue(input.has("comment"));screens+=input;return "{\"relevant\":true}"
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,answer,question))}
        read(record.id);val result=ready(record.id)
        assertEquals("The literal answer must reach contextual review",1,screens.size)
        val item=screens.single()["items"].asJsonArray.single().asJsonObject
        assertEquals(answer.body,item["text"].asString)
        assertTrue(item["reply_to"].asString.contains(question.body))
        assertTrue(screens.single()["subject"].asString.contains(root.body))
        val id="comment:${CrossPosts.originalKey(answer)}"
        assertEquals(answer.body,reader.store.findings(record.id).single {id in it.sources}.quotes[id])
        assertTrue(result.brief.contains(question.body));assertTrue(result.brief.contains(answer.body))
        val before=result.brief;read(record.id);ready(record.id)
        assertEquals(1,screens.size);assertEquals(before,reader.store.record(record.id)!!.brief)
    }

    @Test fun aLateParentOutsideTheFirstWorkWindowStillProtectsItsEarlierAnswer():Unit=runBlocking {
        val record=fixture();val root=record.snapshot.roots.single()
        val parent=comment(root,20).copy(body="Did you replace the entire battery?",author="Alex")
        val child=comment(root,1).copy(body="No.",parentId=parent.id,author="Repair volunteer")
        val noise=(2..10).map {comment(root,it).copy(body="lol")}
        val calls=AtomicInteger()
        reader.modelFactory={object:ContextModel {
            override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,1,4,4,4096,"Late parent fixture",true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings"))return fixtureSummary(input)
                calls.incrementAndGet();return if(system.contains("\"relevant\""))"""{"relevant":false}"""else """{"keep":[1]}"""
            }
        }}
        reader.discussionRead={_,_->Page(listOf(root,child)+noise+parent)}
        read(record.id);val result=ready(record.id)
        assertTrue(result.brief.contains(parent.body));assertFalse(result.brief.contains("Repair volunteer"));assertTrue(result.brief.contains("No."))
        assertTrue(reader.store.documents(record.id).any {it.document.author=="Repair volunteer"})
        assertEquals("The question and its supplied answer require no model judgment",0,calls.get())
        val view=reader.store.view(record.id)!!
        assertTrue(view.findings.any {it.text=="No."});assertEquals(1,view.replyEvidence!!.contexts.references.size)
        read(record.id);ready(record.id);assertEquals(0,calls.get());assertEquals(result.brief,reader.store.record(record.id)!!.brief)
    }

    private suspend fun editParentDuringExtraction(noSignal:Boolean,malformed:Boolean=false) {
        val record=fixture();val root=record.snapshot.roots.single()
        var parent=comment(root,1).copy(body="This inspection covers production equipment.",author="Service desk")
        var child=comment(root,2).copy(body="I checked the connector myself.",parentId=parent.id,author="Repair volunteer")
        val oldClaim="The volunteer checked production equipment.";val newClaim="The volunteer checked a disconnected prototype."
        var armed=false;var edited=false;val childCalls=AtomicInteger()
        reader.discussionRead={_,_->Page(listOf(root,parent,child))}
        val base=TraceModel()
        reader.modelFactory={object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val input=JsonParser.parseString(user).asJsonObject
                if(!input.has("source_id"))return JsonObject().apply {addProperty("text",input["findings"].asJsonArray.joinToString(" "){it.asJsonObject["text"].asString})}.toString()
                if(input["kind"].asString!="comment")return base.generate(system,user,maxTokens)
                val text=input["text"].asString;val isChild=text==child.body
                val old=isChild&&input["parent_context"].asString.contains("production equipment")
                if(isChild)childCalls.incrementAndGet()
                if(isChild&&armed) {
                    armed=false;edited=true
                    val stored=reader.store.documents(record.id).first {it.document.text==parent.body}.document
                    parent=parent.copy(body="This inspection covers only a disconnected prototype.")
                    val changed=stored.copy(revision=ContentRevisions.signature(parent),text=parent.body)
                    reader.store.ingest(record.id,listOf(changed))
                    // A second local preparer can finish the new English view
                    // while the first model request is still in flight.
                    if(malformed)reader.store.saveReading(record.id,changed,"body",changed.text,ContextReadingText.identity(changed.text,EnglishReadingPreparer.POLICY))
                }
                if(isChild&&old&&edited&&malformed)return "not a valid review"
                if(isChild&&old&&edited&&noSignal)return """{"status":"no_signal","findings":[]}"""
                return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {
                    addProperty("text",if(isChild)if(old)oldClaim else newClaim else text);addProperty("kind","context");addProperty("quote",text)
                })})}.toString()
            }
        }}
        read(record.id);val original=ready(record.id)
        repo.dao.putBookmark(BookmarkRow(repo.key(root),root.platform.id,repo.gson.toJson(root),System.currentTimeMillis()))
        reader.store.protect(record.id,true);val saved=reader.store.savedView(record.id)!!
        val source=reader.store.documents(record.id).single {it.document.text==child.body}.document
        child=child.copy(body="I checked the same connector myself.")
        reader.store.ingest(record.id,listOf(source.copy(revision=ContentRevisions.signature(child),text=child.body)))
        armed=true;read(record.id)
        assertTrue("The fixture must change the parent while its child is being read",edited)
        assertFalse("An old parent interpretation must not reappear in the working projection",reader.store.findings(record.id).any {it.text==oldClaim})
        assertFalse("A stale model reply cannot complete the unchanged child",reader.store.document(record.id,source.id)!!.processed)
        assertTrue("A changed source stays pending even when the old response is malformed",reader.store.pendingDocuments(record.id).any {it.document.id==source.id})
        assertEquals("queued",reader.store.record(record.id)!!.status)
        assertEquals("The earlier publication stays readable",original.brief,reader.store.record(record.id)!!.brief)
        assertEquals(saved,reader.store.savedView(record.id))
        read(record.id);val updated=ready(record.id)
        assertTrue(reader.store.findings(record.id).any {it.text==newClaim})
        assertFalse(reader.store.findings(record.id).any {it.text==oldClaim})
        // A malformed complete English comment gets one bounded format retry.
        // Both old-context attempts must still fail the source freshness check.
        assertEquals(if(malformed)4 else 3,childCalls.get())
        assertTrue(updated.brief.contains("prototype"));assertEquals(saved,reader.store.savedView(record.id))
    }
    @Test fun aParentEditDuringExtractionCannotCommitAnOldInterpretation():Unit=runBlocking {editParentDuringExtraction(false)}
    @Test fun aParentEditCannotCompleteAnOldNoSignalReview():Unit=runBlocking {editParentDuringExtraction(true)}
    @Test fun aMalformedReplyForChangedInputStaysQueuedRatherThanDeferred():Unit=runBlocking {editParentDuringExtraction(false,true)}

    @Test fun shortPreparedRepliesAreAdmittedAgainstTheActualSubject():Unit=runBlocking {
        val cases=listOf(
            Triple("pharmacy","A pharmacy announces that a medicine is back in stock.","I checked the front shelf at 10:40 and it was empty.\n\nI left at 10:45 and cannot say whether the delivery arrived later."),
            Triple("trial","A camera maker announces longer battery life.","I got 310 photographs from one charge using the old lens.\n\nThis trial disabled the screen and wireless connection; it does not establish typical battery life."),
            Triple("condition","The council discusses reopening a flooded footpath.","You are all wonderful, brilliant team!\n\nIf the river gauge stays below 1.2 metres overnight.\n\nThe path can reopen at 08:00; the overnight check has not happened."))
        val probes=JsonArray()
        for((name,subject,body)in cases) {
            val record=fixture(subject);val root=record.snapshot.roots.single();val child=comment(root,++sequence).copy(body=body)
            reader.discussionRead={_,_->Page(listOf(root,child))}
            val before=probes.size()
            reader.modelFactory={object:ContextModel {
                override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,1,4,4,4096,"Actual input capture",true)
                override val shortDecisionTokens=32
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                    val input=JsonParser.parseString(user).asJsonObject
                    if(input.has("findings"))return repo.gson.toJson(mapOf("text" to input["findings"].asJsonArray.joinToString(" "){it.asJsonObject["text"].asString}))
                    check(input.has("subject")&&input.has("items")){"Unexpected model responsibility in the capture fixture"}
                    probes.add(JsonObject().apply {addProperty("name",name);addProperty("system",system);add("input",input);addProperty("maxTokens",maxTokens)})
                    assertTrue(input["subject"].asString.contains(subject))
                    return fixtureBriefAdmission(user)!!
                }
            }}
            read(record.id);ready(record.id);assertEquals(before+2,probes.size())
            assertTrue(probes.drop(before).any {probe->probe.asJsonObject["input"].asJsonObject["items"].asJsonArray.any {it.asJsonObject["text"].asString==body}})
        }
        val file=File(requireNotNull(context.getExternalFilesDir("validation")),"context-actual-prompt-shape.json")
        file.writeText(repo.gson.toJson(probes))
    }

}
