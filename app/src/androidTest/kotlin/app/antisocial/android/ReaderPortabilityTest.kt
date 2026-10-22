/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Storage-level interchange tests preserve unrelated records and never fetch a source. */
@RunWith(AndroidJUnit4::class)
class ReaderPortabilityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private var memoryScope: CoroutineScope?=null
    private val memoryFiles=mutableListOf<File>()
    private val followIds=mutableSetOf<String>()
    private val savedKeys=mutableSetOf<String>()
    private val collectionIds=mutableSetOf<String>()
    private val suffix=UUID.randomUUID().toString().replace("-","").take(10)
    private val now=System.currentTimeMillis()
    private val person="portable-person-$suffix"
    private val handle="lp${suffix.take(8)}"
    private val x=source(Target(Platform.X,TargetKind.PROFILE,handle,"https://x.com/$handle"),"Portable developer",group="Work",personId=person,personName="Portable developer")
    private val blue=source(Target(Platform.BLUESKY,TargetKind.PROFILE,"$handle.test","https://bsky.app/profile/$handle.test"),"Portable developer",group="Work",enabled=false,personId=person,personName="Portable developer")
    private val rss=source(Target(Platform.RSS,TargetKind.FEED,"https://blog.example.org/$suffix/feed.xml","https://blog.example.org/$suffix/feed.xml"),"Portable blog",group="Blogs / Software",feedUrl="https://blog.example.org/$suffix/feed.xml")

    @Before fun preserve() {
        runBlocking {repo.awaitReady();repo.feedMemory.awaitReady()}
        preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory
        ui.runOnIdle {
            ui.activity.setContent {Text("Local reader portability fixtures")}
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyDays=3650
            repo.gateway.configure(NetworkPolicy(tor=true)) // No ready transport: fixtures fail closed.
        }
        freshMemory()
    }
    @After fun restore() {
        ui.runOnIdle {ui.activity.setContent {Text("Reader portability fixtures complete")}}
        runBlocking {
            repo.feedMemory.flush();memoryScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            repo.feedMemory=originalMemory
            savedKeys.forEach {key->repo.dao.deleteDocuments(key,"saved");repo.dao.removeBookmark(key)}
            collectionIds.forEach {repo.dao.removeCollection(it)}
            followIds.forEach {repo.dao.removeFollow(it)}
        }
        memoryFiles.flatMap {listOf(it,File(it.path+".bak"),File(it.path+".new"))}.forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.channels.refresh();repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(JsonParser.parseString(preferences),JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun source(target: Target,name: String,group: String="",enabled: Boolean=true,feedUrl: String?=null,personId: String?=null,personName: String?=null)=Follow(if(target.platform==Platform.RSS)RssSources.followId(target.canonicalUrl)else "${target.platform.id}:${target.canonicalUrl}",target,name,group,enabled,feedUrl,personId,personName)
    private fun freshMemory() {
        runBlocking {if(memoryScope!=null){repo.feedMemory.flush();memoryScope?.coroutineContext?.get(Job)?.cancelAndJoin()}}
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"reader-portability").apply {mkdirs()}
        val file=File(directory,"memory-$suffix-${memoryFiles.size}.json").also {memoryFiles+=it}
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope!!,file)
        runBlocking {repo.feedMemory.awaitReady()}
    }
    private suspend fun putSources(vararg sources: Follow) {sources.forEach {followIds+=it.id;repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}}
    private fun post(index: Int=1,body: String="Release notes: replacing a laptop battery"): Post {
        val digits=suffix.map {it.code%10}.joinToString("")
        val id="97$digits${100+index}"
        return Post(id,Platform.X,"https://x.com/$handle/status/$id",handle,body,publishedAt=now-index*1_000L)
    }
    private suspend fun putSaved(post: Post,collection: LibraryCollectionRow,details: SavedCopyDetails?=null) {
        val key=repo.key(post);savedKeys+=key;collectionIds+=collection.id
        repo.dao.putCollection(collection);repo.dao.putBookmark(BookmarkRow(key,post.platform.id,repo.gson.toJson(post),now))
        details?.let {repo.dao.putSavedDetail(SavedDetailRow(key,repo.gson.toJson(it)))}
        repo.dao.setCollectionLinks(key,listOf(collection.id))
    }
    private fun bytes(document: PortableDocument)=PortableReader.encode(document).toByteArray(Charsets.UTF_8)
    private fun libraryPayload(collections: List<LibraryCollectionRow>,saved: List<PortableSaved>)=JsonObject().apply {add("library",repo.gson.toJsonTree(PortableLibrary(collections,saved)))}
    private suspend fun localSources()=repo.dao.allFollows().map {repo.gson.fromJson(it.payload,Follow::class.java)}
    private data class Snapshot(val preferences: JsonElement,val follows: List<FollowRow>,val saved: List<BookmarkRow>,val details: List<SavedDetailRow>,val collections: List<LibraryCollectionRow>,val links: List<SavedCollectionRow>,val assets: List<SavedAssetRow>,val documents: List<LocalDocumentRow>,val progress: JsonElement)
    private suspend fun snapshot()=Snapshot(JsonParser.parseString(repo.prefs.export().toString()),repo.dao.allFollows().sortedBy {it.id},repo.dao.allBookmarks().sortedBy {it.key},repo.dao.allSavedDetails().sortedBy {it.key},repo.dao.allCollections().sortedBy {it.id},repo.dao.allCollectionLinks().sortedWith(compareBy<SavedCollectionRow>{it.ownerKey}.thenBy {it.collectionId}),repo.dao.allSavedAssets().sortedWith(compareBy<SavedAssetRow>{it.ownerKey}.thenBy {it.assetKey}),repo.dao.allDocuments().sortedBy {it.rowid},repo.gson.toJsonTree(repo.feedMemory.portableSnapshot()))
    private suspend fun rejected(action: suspend ()->Unit) {try {action();fail("Malformed state must be rejected before any local write.")}catch(expected: IllegalArgumentException){/* Expected complete validation failure. */}}

    @Test fun readerRoundTripPreservesFoldersBundlesRulesSavedThreadsAndOptInProgressWithoutFetching()=runBlocking {
        putSources(x,blue,rss)
        val rule=repo.channels.save(PersonalChannel("portable-channel-$suffix","Portable releases $suffix",subscriptionIds=setOf(person,rss.id),keywords=listOf("release"),excludeHashtags=listOf("ad"),excludeContentTypes=setOf(ChannelContentType.VIDEO)))
        repo.prefs.theme="dark";repo.prefs.tor=true;repo.prefs.originalFallback=false
        repo.prefs.setList("portable-disabled-$suffix",false);assertTrue(repo.prefs.addList("https://lists.example.org/$suffix.txt"))
        val earlierInspection=AiMetadata(disclosures=listOf(AiDisclosure(AiUse.EDITED,AiEvidence.CONTENT_CREDENTIALS,label="Fixture editing declaration")),credentialState=AiCredentialState.VALID,trust=AiSignerTrust.TRUSTED,signer="Fixture signer",inspectedAt=now)
        val originalPost=post()
        val saved=originalPost.copy(media=listOf(Media("https://images.example.org/$suffix/battery.jpg",description="Battery connectors",aiMetadata=earlierInspection)),
            contextNotes=listOf(PublicContextNote("The service manual supplies the connector diagram.",originalPost.canonicalUrl,listOf("https://maker.example/manual"))))
        val reply=post(2,"Disconnect the battery before replacing it.").copy(parentId=saved.id,
            conversationId="post:991$suffix",relationId="native-reply-$suffix",sourceParentId="native-parent-$suffix",
            extractionCoverage=ExtractionCoverage(text=ExtractionStatus.PRESENT,replyParent=ExtractionStatus.UNAVAILABLE))
        val threads=listOf(CapturedThread(saved,listOf(reply),capturedAt=now,hasMore=true))
        val selected=repo.library.availableAssets(saved).take(1)
        val detail=SavedCopyDetails("portable-token-$suffix",now,threads,selected)
        val collection=LibraryCollectionRow("portable-collection-$suffix","Portable Projects $suffix",now)
        putSaved(saved,collection,detail)
        val context="person:$person";val key=repo.key(saved)
        repo.feedMemory.reconcile(context,listOf(key,repo.key(reply)));repo.feedMemory.markRead(context,listOf(key));repo.feedMemory.anchor(context,key,43);repo.feedMemory.keepReturn(context)
        val token=repo.feedMemory.begin(listOf(x.id));repo.feedMemory.success(x.id,token,now)
        val checked=repo.gateway.protectionStats().checked
        val without=PortableReader.parse(repo.portability.exportState(false));assertNull(without.progress)
        val full=PortableReader.parse(repo.portability.exportState(true))
        val exportedPayload=requireNotNull(full.statePayload);val exportedProgress=requireNotNull(full.progress)
        assertEquals("Blogs / Software",full.sources.single {it.id==rss.id}.group)
        assertEquals(person,full.sources.single {it.id==x.id}.personId);assertFalse(full.sources.single {it.id==blue.id}.enabled)
        assertTrue(full.channels.any {it.id==rule.id&&it.excludeContentTypes==setOf(ChannelContentType.VIDEO)})
        assertFalse(exportedPayload.getAsJsonObject("settings").has("tor"));assertFalse(exportedPayload.getAsJsonObject("settings").has("originalFallback"))
        assertFalse(exportedProgress.has("refresh"));assertFalse(exportedProgress.has("token"))
        val payload=exportedPayload.deepCopy().apply {add("library",repo.gson.toJsonTree(PortableLibrary(listOf(collection),listOf(PortableSaved(saved,now,detail,listOf(collection.id))))))}
        val fixture=full.copy(sources=full.sources.filter {it.id in setOf(x.id,blue.id,rss.id)},channels=listOf(rule),statePayload=payload)
        File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"reader-portability/roundtrip-fixture.json").writeBytes(bytes(fixture))
        listOf(x,blue,rss).forEach {repo.dao.removeFollow(it.id)};repo.dao.removeBookmark(key);repo.dao.removeCollection(collection.id);repo.channels.delete(rule.id)
        freshMemory();repo.prefs.theme="light";repo.prefs.tor=false
        val preview=repo.portability.preview(bytes(fixture));assertEquals(3,preview.sources.size);assertEquals(1,preview.channels);assertEquals(1,preview.collections);assertEquals(1,preview.savedPosts);assertTrue(preview.hasProgress)
        val result=repo.portability.apply(preview,preview.sources.map {it.id}.toSet())
        assertEquals(3,result.addedSources);assertEquals(1,result.channels);assertEquals(1,result.collections);assertEquals(1,result.savedPosts)
        val loaded=localSources();assertEquals(person,loaded.single {it.id==x.id}.personId);assertEquals(person,loaded.single {it.id==blue.id}.personId);assertFalse(loaded.single {it.id==blue.id}.enabled);assertEquals("Blogs / Software",loaded.single {it.id==rss.id}.group)
        assertEquals("light",repo.prefs.theme);assertFalse(repo.prefs.tor);assertFalse(repo.prefs.originalFallback)
        assertTrue("portable-disabled-$suffix" in repo.prefs.disabledLists());assertTrue("https://lists.example.org/$suffix.txt" in repo.prefs.customLists())
        assertNull(repo.feedMemory.get(context).anchorKey)
        val restored=repo.library.details(key)!!;assertEquals(reply.body,restored.threads.single().replies.single().body);assertTrue(restored.threads.single().hasMore);assertEquals(selected,restored.selectedAssets)
        val importedPost=repo.gson.fromJson(repo.dao.bookmarksByKeys(listOf(key)).single().payload,Post::class.java)
        assertEquals(saved.contextNotes,importedPost.contextNotes)
        assertEquals(saved.contextNotes,restored.threads.single().root.contextNotes)
        assertEquals(AiCredentialState.UNCHECKED,importedPost.media.single().aiMetadata!!.credentialState);assertEquals(AiSignerTrust.NOT_EVALUATED,importedPost.media.single().aiMetadata!!.trust)
        assertEquals(AiCredentialState.UNCHECKED,restored.threads.single().root.media.single().aiMetadata!!.credentialState);assertEquals(AiSignerTrust.NOT_EVALUATED,restored.threads.single().root.media.single().aiMetadata!!.trust)
        assertEquals("Fixture editing declaration",importedPost.media.single().aiMetadata!!.disclosures.single().label)
        assertEquals(reply,restored.threads.single().replies.single())
        val missingAsset=repo.dao.savedAssetsByOwners(listOf(key)).single();assertEquals("MISSING",missingAsset.status);assertEquals(0L,missingAsset.bytes)
        assertTrue(repo.library.entries("battery",scope="saved",collectionId=collection.id).first().any {it.key==key})
        val repeated=repo.portability.apply(preview,preview.sources.map {it.id}.toSet(),PortableImportOptions(progress=true))
        assertEquals(0,repeated.addedSources);assertEquals(0,repeated.channels);assertEquals(0,repeated.collections);assertEquals(0,repeated.savedPosts)
        assertEquals(key,repo.feedMemory.get(context).anchorKey);assertEquals(43,repo.feedMemory.get(context).anchorOffset);assertEquals(key,repo.feedMemory.get(context).resumeReturn?.key);assertTrue(key in repo.feedMemory.get(context).readKeys)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[x.id]?.status)
        assertEquals(checked,repo.gateway.protectionStats().checked)
    }

    @Test fun sourcePackSharesOnlyChosenPublicSourcesAndKeepsExistingLocalChoices()=runBlocking {
        val privateX=x.copy(enabled=false,group="Private folder $suffix",feedUrl="https://private-feed.example.org/$suffix.xml")
        putSources(privateX,blue,rss)
        val checked=repo.gateway.protectionStats().checked
        val packBytes=repo.portability.exportPack(setOf(privateX.id,blue.id),"Portable recommendations")
        val json=String(packBytes);assertFalse(json.contains("Private folder"));assertFalse(json.contains("private-feed.example.org"));assertFalse(json.contains(person));assertFalse(json.contains("progress"));assertFalse(json.contains("settings"))
        val preview=repo.portability.preview(packBytes);assertFalse(preview.isState);assertEquals(2,preview.sources.size);assertFalse(preview.hasProgress)
        assertTrue(preview.sources.all {it.personId==null&&it.personName==null&&it.group.isEmpty()&&it.enabled&&it.feedUrl==null})
        val before=repo.dao.allFollows().size;val source=preview.sources.single {it.target.platform==Platform.X}
        val result=repo.portability.apply(preview,setOf(source.id))
        assertEquals(0,result.addedSources);assertEquals(1,result.existingSources);assertEquals(before,repo.dao.allFollows().size)
        assertEquals(privateX,localSources().single {it.id==privateX.id});assertEquals(blue,localSources().single {it.id==blue.id})
        val opml=String(repo.portability.exportOpml(setOf(x.id,rss.id)))
        assertTrue(opml.contains("Blogs / Software"));assertTrue(opml.contains(rss.target.canonicalUrl));assertFalse(opml.contains(x.target.canonicalUrl))
        assertEquals(checked,repo.gateway.protectionStats().checked)
    }

    @Test fun importingASelectedSubsetTwiceKeepsMissingChannelSourcesExplicitAndIdempotent()=runBlocking {
        val channel=PersonalChannel("portable-subset-$suffix","Portable subset $suffix",subscriptionIds=setOf(rss.id),keywords=listOf("release"))
        val document=PortableDocument(PortableKind.STATE,now,listOf(x,rss),listOf(channel))
        val preview=repo.portability.preview(bytes(document));followIds+=x.id
        val first=repo.portability.apply(preview,setOf(x.id));assertEquals(1,first.addedSources);assertEquals(1,first.channels)
        val imported=repo.channels.channels.value.single {it.name==channel.name}
        assertTrue(imported.subscriptionIds.isNotEmpty());assertFalse(imported.subscriptionIds.contains(x.id))
        assertTrue(PersonalChannels.accounts(imported,localSources()).isEmpty())
        val second=repo.portability.apply(preview,setOf(x.id));assertEquals(0,second.addedSources);assertEquals(0,second.channels)
        assertEquals(1,repo.channels.channels.value.count {it.name==channel.name})
        assertEquals(imported,repo.channels.channels.value.single {it.name==channel.name})
    }

    @Test fun collectionNameCollisionsMergeMembershipAndRepeatedImportKeepsTheLocalSavedCopy()=runBlocking {
        val localCollection=LibraryCollectionRow("portable-local-$suffix","Portable recipes $suffix",now)
        val incomingCollection=LibraryCollectionRow("portable-incoming-$suffix","PORTABLE RECIPES $suffix",now-1_000)
        collectionIds+=localCollection.id;collectionIds+=incomingCollection.id;repo.dao.putCollection(localCollection)
        val saved=post(3,"Replace a laptop battery with the correct connector.");val key=repo.key(saved);savedKeys+=key
        val document=PortableDocument(PortableKind.STATE,now,emptyList(),statePayload=libraryPayload(listOf(incomingCollection),listOf(PortableSaved(saved,now,collectionIds=listOf(incomingCollection.id)))))
        val preview=repo.portability.preview(bytes(document));val first=repo.portability.apply(preview,emptySet())
        assertEquals(0,first.collections);assertEquals(1,first.savedPosts)
        assertEquals(localCollection,repo.dao.collectionNamed(incomingCollection.name));assertEquals(listOf(SavedCollectionRow(key,localCollection.id)),repo.dao.allCollectionLinks().filter {it.ownerKey==key})
        val changed=saved.copy(body="My local annotation stays here.")
        repo.dao.putBookmark(BookmarkRow(key,changed.platform.id,repo.gson.toJson(changed),now+10))
        val before=repo.dao.savedDetail(key)
        val second=repo.portability.apply(preview,emptySet());assertEquals(0,second.savedPosts);assertEquals(0,second.collections)
        assertEquals(changed,repo.gson.fromJson(repo.dao.bookmarksByKeys(listOf(key)).single().payload,Post::class.java));assertEquals(before,repo.dao.savedDetail(key))
        assertEquals(1,repo.dao.allCollections().count {it.name.equals(localCollection.name,true)})
    }

    @Test fun malformedLateLibraryAndProgressAreRejectedBeforePreferencesOrDatabaseWrites()=runBlocking {
        val collection=LibraryCollectionRow("portable-atomic-$suffix","Portable atomic $suffix",now)
        val saved=post(4);val entry=PortableSaved(saved,now,collectionIds=listOf(collection.id))
        followIds+=x.id;collectionIds+=collection.id;savedKeys+=repo.key(saved);savedKeys+=repo.key(post(5))
        val payload=libraryPayload(listOf(collection),listOf(entry)).apply {add("settings",JsonObject().apply {addProperty("theme","dark")})}
        val valid=PortableDocument(PortableKind.STATE,now,listOf(x),statePayload=payload)
        val preview=repo.portability.preview(bytes(valid));val before=snapshot()
        val malformedLibrary=valid.copy(statePayload=payload.deepCopy().apply {getAsJsonObject("library").getAsJsonArray("saved").add(repo.gson.toJsonTree(entry.copy(post=post(5),collectionIds=listOf("missing-collection-$suffix"))))})
        rejected {repo.portability.apply(preview.copy(document=malformedLibrary),setOf(x.id),PortableImportOptions(settings=true))}
        assertEquals(before,snapshot())
        val badMemory=FeedMemory(initialized=true,acceptedKeys=listOf(repo.key(saved)),anchorKey=repo.key(saved),updatedAt=now)
        val progress=repo.gson.toJsonTree(PortableProgress(feeds=mapOf("all" to badMemory))).asJsonObject
        progress.getAsJsonObject("feeds").getAsJsonObject("all").addProperty("anchorOffset",Long.MAX_VALUE)
        rejected {repo.portability.apply(preview.copy(document=valid.copy(progress=progress)),setOf(x.id),PortableImportOptions(settings=true,progress=true))}
        assertEquals(before,snapshot())
        rejected {repo.portability.preview(bytes(malformedLibrary))};assertEquals(before,snapshot())
    }

    @Test fun progressOptInUnionsReadMarksAndKeepsAnExistingReadersPosition()=runBlocking {
        val older=post(6);val newer=post(7);val olderKey=repo.key(older);val newerKey=repo.key(newer)
        repo.feedMemory.reconcile("all",listOf(newerKey,olderKey));repo.feedMemory.anchor("all",newerKey,17)
        val imported=FeedMemory(initialized=true,acceptedKeys=listOf(newerKey,olderKey),readKeys=setOf(olderKey),anchorKey=olderKey,anchorOffset=91,updatedAt=now-10_000)
        val document=PortableDocument(PortableKind.STATE,now,emptyList(),progress=repo.gson.toJsonTree(PortableProgress(feeds=mapOf("all" to imported),readKeys=setOf(olderKey),readAt=now)).asJsonObject)
        val preview=repo.portability.preview(bytes(document))
        repo.portability.apply(preview,emptySet());assertFalse(olderKey in repo.feedMemory.get("all").readKeys)
        repo.portability.apply(preview,emptySet(),PortableImportOptions(progress=true))
        assertEquals(newerKey,repo.feedMemory.get("all").anchorKey);assertEquals(17,repo.feedMemory.get("all").anchorOffset);assertTrue(olderKey in repo.feedMemory.get("all").readKeys)
    }

    @Test fun legacySavedRelationshipFieldsRemainOptionalAndNewSnapshotFieldsAreTyped() {
        val legacy=repo.gson.toJsonTree(post(20).copy(extractionCoverage=ExtractionCoverage(text=ExtractionStatus.PRESENT))).asJsonObject
        listOf("conversationId","relationId","sourceParentId").forEach(legacy::remove)
        legacy.getAsJsonObject("extractionCoverage").remove("replyParent")
        strictSavedModel(legacy,Post::class.java)
        val old=repo.gson.fromJson(legacy,Post::class.java)
        assertNull(old.conversationId);assertNull(old.extractionCoverage!!.replyParent)
        assertNull(old.contextNotes)
        val annotated=repo.gson.toJsonTree(old.copy(contextNotes=listOf(PublicContextNote("A source annotation",old.canonicalUrl)))).asJsonObject
        strictSavedModel(annotated,Post::class.java)
        annotated.getAsJsonArray("contextNotes")[0].asJsonObject.addProperty("status",123)
        try {strictSavedModel(annotated,Post::class.java);fail("Annotation statuses must remain strings.")}catch(expected:IllegalArgumentException) {}
        val root=post(21).copy(parentId="post:parent",conversationId="post:root",relationId="native:reply",sourceParentId="native:parent",
            extractionCoverage=ExtractionCoverage(replyParent=ExtractionStatus.UNAVAILABLE))
        val captured=repo.gson.toJsonTree(PostSnapshot.from(root)).asJsonObject
        strictSavedModel(captured,PostSnapshot::class.java)
        assertEquals(PostSnapshot.from(root),repo.gson.fromJson(captured,PostSnapshot::class.java))
        captured.getAsJsonObject("relationship").addProperty("sourceParentId",123)
        try {strictSavedModel(captured,PostSnapshot::class.java);fail("Native reply IDs must remain strings.")}catch(expected: IllegalArgumentException) {}
    }

    @Test fun savedScalarCoercionsAndUnknownNestedFieldsCannotReachAnyImportWrite()=runBlocking {
        val first=post(8);val last=post(9).copy(media=listOf(Media("https://images.example.org/$suffix/manifest.jpg")))
        followIds+=x.id;savedKeys+=repo.key(first);savedKeys+=repo.key(last)
        val valid=PortableDocument(PortableKind.STATE,now,listOf(x),statePayload=libraryPayload(emptyList(),listOf(PortableSaved(first,now),PortableSaved(last,now))))
        val preview=repo.portability.preview(bytes(valid));val before=snapshot()
        val changes=listOf<(JsonObject)->Unit>(
            {it.addProperty("body",123)},
            {it.getAsJsonArray("media")[0].asJsonObject.addProperty("type",7)},
            {it.addProperty("preview","false")},
            {it.addProperty("publishedAt",1.5)},
            {it.getAsJsonArray("media")[0].asJsonObject.addProperty("unrecognizedFetchUrl","https://extra.example.org/$suffix")}
        )
        changes.forEach {change->
            val payload=valid.statePayload!!.deepCopy()
            change(payload.getAsJsonObject("library").getAsJsonArray("saved")[1].asJsonObject.getAsJsonObject("post"))
            rejected {repo.portability.apply(preview.copy(document=valid.copy(statePayload=payload)),setOf(x.id))}
            assertEquals(before,snapshot())
        }
    }

    @Test fun unselectedExcludedSourcesStillMapToExistingLocalSubscriptionsAndVetoFusion()=runBlocking {
        val exportedExcluded="portable-export-excluded-$suffix"
        val localExcluded="portable-local-excluded-$suffix"
        val receiver=blue.copy(personId=localExcluded,personName="My local creator label",group="My private folder",enabled=false,feedUrl="https://local-feed.example.org/$suffix.xml")
        putSources(receiver);followIds+=x.id
        val incoming=blue.copy(personId=exportedExcluded,personName="Exporter label",group="Export folder",enabled=true,feedUrl=null)
        val rule=PersonalChannel("portable-exclusion-$suffix","Portable exclusion $suffix",subscriptionIds=setOf(person),excludeSubscriptionIds=setOf(exportedExcluded),keywords=listOf("release"))
        val preview=repo.portability.preview(bytes(PortableDocument(PortableKind.STATE,now,listOf(x,incoming),listOf(rule))))
        val count=repo.dao.allFollows().size
        val result=repo.portability.apply(preview,setOf(x.id))
        assertEquals(1,result.addedSources);assertEquals(count+1,repo.dao.allFollows().size)
        val follows=localSources();assertEquals(receiver,follows.single {it.id==receiver.id});assertEquals(1,follows.count {it.target.canonicalUrl==receiver.target.canonicalUrl})
        val imported=repo.channels.channels.value.single {it.name==rule.name}
        assertEquals(setOf(person),imported.subscriptionIds);assertEquals(setOf(localExcluded),imported.excludeSubscriptionIds)
        val primary=post(10,"Release notes from the selected creator.")
        val excluded=post(11,"Release notes with extra context.").copy(platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/$handle.test/post/3portable$suffix",author="$handle.test")
        val memberships=mapOf(PersonalChannels.postKey(primary) to setOf(x.id),PersonalChannels.postKey(excluded) to setOf(receiver.id))
        assertNotNull(PersonalChannels.match(imported,listOf(primary),follows,memberships))
        assertNull("An already-subscribed excluded edition must veto the fused group, even when that source was not selected for import and is paused locally.",PersonalChannels.match(imported,listOf(primary,excluded),follows,memberships))
    }

    @Test fun importedRetrievalTargetsComeFromTheShownCanonicalUrlAndPlatformMismatchesAreAtomic()=runBlocking {
        val canonical="https://bsky.app/profile/good$suffix.test"
        val expected=repo.routes.target(Platform.BLUESKY,canonical)
        val misleading=Follow("portable-misleading-$suffix",expected.copy(identity="profile/other$suffix.test"),"Shown public account")
        val document=PortableDocument(PortableKind.STATE,now,listOf(misleading))
        val checked=repo.gateway.protectionStats().checked
        val preview=repo.portability.preview(bytes(document))
        assertEquals(expected,preview.sources.single().target)
        val importedId="${Platform.BLUESKY.id}:${expected.canonicalUrl}";followIds+=importedId
        val result=repo.portability.apply(preview,setOf(preview.sources.single().id))
        assertEquals(1,result.addedSources)
        assertEquals(expected,localSources().single {it.id==importedId}.target)
        assertFalse(localSources().any {it.target.platform==Platform.BLUESKY&&it.target.identity==misleading.target.identity})
        val crossPlatform=misleading.copy(target=misleading.target.copy(canonicalUrl="https://x.com/$handle"))
        val invalid=document.copy(sources=listOf(crossPlatform));val before=snapshot()
        rejected {repo.portability.preview(bytes(invalid))};assertEquals(before,snapshot())
        rejected {repo.portability.apply(preview.copy(document=invalid),setOf(crossPlatform.id))};assertEquals(before,snapshot())
        assertEquals(checked,repo.gateway.protectionStats().checked)
    }
}
