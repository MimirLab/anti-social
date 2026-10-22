package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Uses isolated public-post identities and local files; fixture saves never need a remote reader. */
@RunWith(AndroidJUnit4::class)
class SavedLibraryTest {
    private val repo get()=(ApplicationProvider.getApplicationContext<Context>() as AntiSocialApplication).repo
    private val prefix=System.nanoTime().toString().takeLast(12)
    private val sequence=AtomicInteger()
    private val marker="savelibrary$prefix"
    private val fixtures=linkedMapOf<String,Post>()
    private val collections=mutableSetOf<String>()
    private val mediaKeys=mutableSetOf<String>()
    private val tempFiles=mutableListOf<File>()
    private lateinit var preferences: String
    private lateinit var oldHistory: List<HistoryRow>
    private lateinit var oldRetained: List<RetainedHistoryRow>
    private lateinit var oldHistoryDocuments: List<LocalDocumentRow>
    private lateinit var oldFollows: List<FollowRow>
    private lateinit var oldBookmarks: List<BookmarkRow>
    private lateinit var policy: NetworkPolicy
    private lateinit var oldLoader: suspend (Target,(String)->Unit)->Page

    @Before fun preserve()=runBlocking {
        repo.awaitReady()
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();oldLoader=repo.directLoader
        oldHistory=repo.dao.allHistory();oldRetained=repo.dao.allRetainedHistory();oldHistoryDocuments=repo.dao.documentsOfKind("history")
        oldFollows=repo.dao.allFollows();oldBookmarks=repo.dao.allBookmarks()
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false;repo.prefs.historyDays=3650
        scheduleRefresh(repo.context,repo.prefs)
    }
    @After fun restore()=runBlocking {
        withContext(NonCancellable) {
            fixtures.keys.forEach {repo.library.cancel(it)}
            repo.db.withTransaction {
                for(key in fixtures.keys) {
                    repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history")
                    repo.dao.removeRetainedHistory(listOf(key));repo.dao.removeBookmark(key);repo.dao.removePosts(listOf(key))
                    repo.db.openHelper.writableDatabase.execSQL("DELETE FROM seen WHERE `key`=?",arrayOf(key))
                }
                collections.forEach {repo.dao.removeCollection(it)}
                // History clear and capacity checks are global operations. Restore
                // every pre-test history record, not merely the fixture records.
                repo.dao.clearHistory();oldHistory.forEach {repo.dao.putHistory(it)}
                repo.dao.clearRetainedHistory();oldRetained.forEach {repo.dao.putRetainedHistory(it)}
                repo.dao.clearDocumentKind("history");oldHistoryDocuments.forEach {repo.dao.putDocument(it)}
            }
            for(key in mediaKeys) {
                repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))
                File(repo.context.filesDir,"saved/$key").delete();File(repo.context.cacheDir,"content/$key").delete()
            }
            tempFiles.forEach {it.delete()}
            repo.directLoader=oldLoader;repo.gateway.configure(policy)
            repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(repo.context,repo.prefs)
            repo.library.notifyChanged();repo.updateUsage()
            assertEquals(oldHistory.toSet(),repo.dao.allHistory().toSet())
            assertEquals(oldRetained.toSet(),repo.dao.allRetainedHistory().toSet())
            assertEquals(oldFollows.toSet(),repo.dao.allFollows().toSet())
            assertTrue("Unrelated saved posts must remain",repo.dao.allBookmarks().map {it.key}.containsAll(oldBookmarks.map {it.key}))
            assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
        }
    }
    private fun post(label: String="saved",body: String="$marker original local copy"): Post {
        val id="94$prefix${sequence.incrementAndGet().toString().padStart(4,'0')}"
        return Post(id,Platform.X,"https://x.com/antisocial_saved_library_fixture/status/$id","Attribution $label",body,publishedAt=System.currentTimeMillis()).also {fixtures[repo.key(it)]=it}
    }
    private suspend fun save(post: Post,assets: List<SelectedAsset> = emptyList(),threads: List<CapturedThread> = emptyList(),ids: Set<String> = emptySet()): String {
        val owner=repo.library.save(post,assets,threads,ids);repo.library.cancel(owner);return owner
    }
    private suspend fun results(query: String,scope: String="all",collection: String?=null)=withTimeout(10_000){repo.library.entries(query,scope,collection).first()}
    private suspend fun protectedImage(url: String): File {
        val key=Repository.hash(url);mediaKeys+=key
        val file=File(repo.context.filesDir,"saved/$key").apply {parentFile?.mkdirs()}
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(android.graphics.Color.BLUE);file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally {bitmap.recycle()}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis(),saved=true))
        return file
    }
    private suspend fun assertCapturedSource(original: Post,saved: Post) {
        assertEquals(original.media.size,saved.media.size)
        saved.media.forEachIndexed {index,media->
            val expected=original.media[index]
            val local=repo.availableMedia(media.url)
            if(local==null)assertEquals("An unavailable attachment must not gain an inspection",expected.aiMetadata,media.aiMetadata)
            else {
                assertNull("These fixtures supply no pre-existing AI declaration",expected.aiMetadata)
                val observation=requireNotNull(media.aiMetadata)
                assertTrue(observation.inspectedAt in 1..System.currentTimeMillis())
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(local.readBytes()).joinToString(""){"%02x".format(it)}
                assertEquals("The annotation records exact fixture bytes without inventing AI use",AiMetadata(inspectedAt=observation.inspectedAt,sha256=sha),observation)
            }
        }
        assertEquals("Every original source field remains intact",original,saved.copy(media=saved.media.mapIndexed {index,media->media.copy(aiMetadata=original.media[index].aiMetadata)}))
    }

    @Test fun aCopyKeepsTextAttributionGallerySelectionCapturedRepliesAndCollectionSearchLocally()=runBlocking {
        val image="https://saved-library-$prefix.invalid/selected.png";val unselected="https://saved-library-$prefix.invalid/unselected.png"
        val file=protectedImage(image)
        val original=post().copy(media=listOf(Media(image,description="Selected gallery photo"),Media(unselected,description="Other gallery photo")))
        val reply=post("reply","$marker quasarreply public reply").copy(parentId=original.id)
        val thread=CapturedThread(original,listOf(reply),capturedAt=System.currentTimeMillis(),hasMore=true)
        val collection=repo.library.createCollection("Saved test $prefix").also {collections+=it}
        assertEquals(collection,repo.library.createCollection("saved test $prefix"))
        val selected=repo.library.availableAssets(original,listOf(thread)).filter {it.url==image}
        val owner=save(original,selected,listOf(thread),setOf(collection))
        val copy=requireNotNull(repo.library.copy(owner))
        assertCapturedSource(original,copy.post);assertEquals(original.canonicalUrl,copy.post.canonicalUrl);assertEquals(original.author,copy.post.author)
        assertEquals(2,copy.post.media.size);assertEquals(listOf(image),copy.details!!.selectedAssets.map {it.url})
        assertEquals(listOf(reply),copy.details!!.threads.single().replies);assertTrue(copy.details!!.threads.single().hasMore)
        assertEquals(setOf(collection),copy.collections.map {it.id}.toSet())
        assertEquals("COMPLETE",copy.assets.single().status);assertEquals(file.length(),copy.assets.single().bytes)
        assertNotNull(repo.library.assetFile(image));assertNull(repo.library.assetFile(unselected))
        val hit=results("quasarreply","saved",collection).single {it.key==owner}
        assertEquals(repo.key(reply),repo.key(requireNotNull(hit.matchedPost)))
        assertNull("List results must not deserialize a captured thread manifest",hit.details)
        assertEquals(reply.canonicalUrl,hit.matchedPost!!.canonicalUrl);assertEquals(reply.author,hit.matchedPost!!.author)
        assertEquals(reply.body,hit.matchedPost!!.body);assertNull("The search index does not store publication time",hit.matchedPost!!.publishedAt)
        assertTrue(results("quasarreply","history",collection).isEmpty())
        val backup=repo.gson.fromJson(String(repo.exportBackup()),Backup::class.java)
        val metadata=backup.savedDetails.orEmpty().single {it.key==owner}
        assertEquals(copy.details,repo.gson.fromJson(metadata.payload,SavedCopyDetails::class.java))
        assertTrue(backup.collectionLinks.orEmpty().any {it.ownerKey==owner&&it.collectionId==collection})
        save(original,emptyList(),ids=setOf(collection))
        assertNull("Deselecting a formerly downloaded image releases its unshared bytes",repo.library.assetFile(image))
        assertEquals("The original captured inspection survives attachment deselection",copy.post,repo.library.copy(owner)!!.post)
        assertEquals(listOf(reply),repo.library.copy(owner)!!.details!!.threads.single().replies)
    }

    @Test fun ordinaryContentEvictionCannotRemoveTheProtectedSavedEditionOrSelectedImage()=runBlocking {
        val url="https://saved-library-$prefix.invalid/protected.png";val image=protectedImage(url)
        val original=post().copy(media=listOf(Media(url)))
        val owner=save(original,repo.library.availableAssets(original))
        val captured=repo.library.copy(owner)!!.post
        assertCapturedSource(original,captured)
        val json=repo.gson.toJson(original)
        val old=System.currentTimeMillis()-366*CachePolicy.DAY
        repo.dao.putPost(PostRow(owner,original.platform.id,json,original.publishedAt,old,old,json.toByteArray().size.toLong()))
        repo.cleanup()
        assertNull(repo.dao.post(owner));assertEquals(captured,repo.library.copy(owner)!!.post)
        assertTrue(image.isFile);assertNotNull(repo.library.assetFile(url))
        assertTrue(results(marker,"saved").any {it.key==owner})
    }

    @Test fun copiedPostsFromDifferentAccountsKeepAttributionThreadsAndUnknownDatesInPortableSavedCopies()=runBlocking {
        val shared="$marker The neighborhood repair workshop explained how residents can replace an aging laptop battery, disconnect the old power pack, arrange the tiny screws carefully, and test the replacement before fitting the bottom cover."
        val image="https://saved-library-$prefix.invalid/copied-workshop.png"
        val first=post("publisher",shared).let {it.copy(canonicalUrl="https://x.com/repair_source/status/${it.id}",publishedAt=System.currentTimeMillis()-45*CachePolicy.DAY,media=listOf(Media(image)))}
        val second=post("reposter",shared).let {it.copy(canonicalUrl="https://x.com/repair_reposter/status/${it.id}",publishedAt=null,media=listOf(Media(image)))}
        val originals=listOf(first,second).onEach {fixtures[repo.key(it)]=it}
        assertFalse(CrossPosts.sameSourceRepeats(originals))
        assertTrue(CrossPosts.sameContentCopies(originals))
        assertEquals(1,CrossPosts.group(originals,emptyList()).size)
        val threads=originals.mapIndexed {index,root->CapturedThread(root,listOf(post("copyreply$index","$marker copyreply$index useful public answer").copy(parentId=root.id)),capturedAt=System.currentTimeMillis())}
        val owner=repo.library.save(first,emptyList(),threads,fusionRoots=originals);repo.library.cancel(owner)
        val before=requireNotNull(repo.library.copy(owner))
        assertEquals(originals,before.details!!.fusionRoots)
        assertEquals(threads,before.details!!.threads)
        val exported=PortableReader.parse(repo.portability.exportState(false))
        val all=repo.gson.fromJson(exported.statePayload!!.get("library"),PortableLibrary::class.java)
        val payload=com.google.gson.JsonObject().apply {add("library",repo.gson.toJsonTree(PortableLibrary(emptyList(),all.saved.filter {repo.key(it.post)==owner})))}
        val document=PortableDocument(PortableKind.STATE,System.currentTimeMillis(),emptyList(),statePayload=payload)
        val preview=repo.portability.preview(PortableReader.encode(document).toByteArray(Charsets.UTF_8))
        assertEquals(1,preview.savedPosts)
        repo.db.withTransaction {repo.dao.removeBookmark(owner);repo.dao.deleteDocuments(owner,"saved");repo.dao.removePosts(originals.map(repo::key))}
        assertEquals(1,repo.portability.apply(preview,emptySet(),PortableImportOptions(channels=false,filters=false,collections=true,settings=false,progress=false)).savedPosts)
        val restored=requireNotNull(repo.library.copy(owner))
        assertEquals(originals,restored.details!!.fusionRoots)
        assertEquals(threads,restored.details!!.threads)
        assertTrue(results("copyreply1","saved").any {it.key==owner})
    }

    @Test fun repeatedSameSourceOriginalsKeepTheirThreadsMediaArchivesAndPortableCopiesAfterCacheEviction()=runBlocking {
        val shared="$marker At our public repair workshop, neighbors learned how to replace a laptop battery, organize the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val common="https://saved-library-$prefix.invalid/repeated-workshop.png"
        val protected=protectedImage(common)
        val account="saverep${prefix.takeLast(7)}"
        val at=System.currentTimeMillis()
        fun source(index: Int,body: String): Post {
            val raw=post("repeat$index",body)
            return raw.copy(canonicalUrl="https://x.com/$account/status/${raw.id}",publishedAt=at-index*1_000,
                media=listOf(Media(common),Media("https://saved-library-$prefix.invalid/original-$index.png"))).also {fixtures[repo.key(it)]=it}
        }
        val primary=source(0,shared)
        val second=source(1,"Workshop notebook.\n\n$shared\n\nBring the cobalttoolkit for our next session.")
        val last=source(2,"Repair checklist.\n\n$shared\n\nThe copperlatch supplies are ready.")
        val third=last.copy(archivedVersion=ArchivedVersion("Internet Archive",at-86_400_000,
            "https://web.archive.org/web/20260202024000id_/${last.canonicalUrl}",PostSnapshot.from(last.copy(body="Earlier original workshop announcement.",media=emptyList())),ArchiveContent.signature(last)))
            .also {fixtures[repo.key(it)]=it}
        val originals=listOf(primary,second,third)
        assertTrue(CrossPosts.sameSourceRepeats(originals))
        assertEquals(1,CrossPosts.group(originals,emptyList()).size)
        val threads=originals.mapIndexed {index,root->
            val reply=post("repeat-reply-$index","$marker replystream$index captured public answer").copy(parentId=root.id)
            CapturedThread(root,listOf(reply),capturedAt=at,hasMore=true)
        }
        val selected=repo.library.availableAssets(primary,threads,originals).filter {it.url==common}
        val folder=repo.library.createCollection("Repeated Projects $prefix").also {collections+=it}
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        val owner=repo.library.save(primary,selected,threads,setOf(folder),originals);repo.library.cancel(owner)
        val before=repo.library.copy(owner)!!
        val captured=before.details!!.fusionRoots!!
        assertEquals(originals.map(repo::key),captured.map(repo::key))
        originals.zip(captured).forEach {(original,saved)->assertCapturedSource(original,saved)}
        assertEquals(threads,before.details!!.threads);assertEquals(third.archivedVersion,captured.last().archivedVersion)
        assertEquals(1,before.details!!.selectedAssets.size);assertTrue(protected.isFile)
        assertTrue(results("cobalttoolkit","saved").any {it.key==owner})
        assertTrue(results("copperlatch","saved").any {it.key==owner})
        assertTrue(results("replystream2","saved").any {it.key==owner})
        val export=PortableReader.parse(repo.portability.exportState(false))
        val library=repo.gson.fromJson(export.statePayload!!.get("library"),PortableLibrary::class.java)
        val saved=library.saved.single {repo.key(it.post)==owner}
        val payload=com.google.gson.JsonObject().apply {add("library",repo.gson.toJsonTree(PortableLibrary(library.collections.filter {it.id==folder},listOf(saved))))}
        val document=PortableDocument(PortableKind.STATE,System.currentTimeMillis(),emptyList(),statePayload=payload)
        val preview=repo.portability.preview(PortableReader.encode(document).toByteArray(Charsets.UTF_8))
        assertEquals(1,preview.savedPosts);assertFalse(preview.hasProgress)
        val checked=repo.gson.fromJson(preview.document.statePayload!!.get("library"),PortableLibrary::class.java).saved.single()
        assertEquals(captured.map(repo::key),checked.details!!.fusionRoots!!.map(repo::key))
        repo.db.withTransaction {repo.dao.removeBookmark(owner);repo.dao.deleteDocuments(owner,"saved");repo.dao.removePosts(originals.map(repo::key))}
        val imported=repo.portability.apply(preview,emptySet(),PortableImportOptions(channels=false,filters=false,collections=true,settings=false,progress=false))
        assertEquals(1,imported.savedPosts)
        val restored=repo.library.copy(owner)!!
        assertEquals(capturedPortablePost(before.post),restored.post)
        assertEquals(capturedPortableDetails(before.details!!).copy(token=restored.details!!.token),restored.details)
        assertEquals(setOf(folder),restored.collections.map {it.id}.toSet())
        assertEquals(setOf("COMPLETE"),restored.assets.map {it.status}.toSet())
        val remoteReads=AtomicInteger();repo.directLoader={_,_->remoteReads.incrementAndGet();error("Saved originals must remain local")}
        assertTrue(results("replystream2","saved").any {it.key==owner});assertEquals(0,remoteReads.get())
        repo.cleanup(clearAll=true)
        assertEquals(restored.post,repo.library.copy(owner)!!.post);assertTrue(protected.isFile)
        assertEquals(3,repo.library.copy(owner)!!.details!!.fusionRoots!!.size)
    }

    @Test fun repeatedSourceManifestsRejectUnrelatedContentRepliesUnprovenDatesAndDuplicateOriginals()=runBlocking {
        val shared="$marker A detailed public update about our community repair workshop explains how neighbors replace a laptop battery, organize the screws, disconnect the pack, and fit the replacement before closing the cover."
        val first=post(body=shared)
        val second=post(body=shared).copy(publishedAt=first.publishedAt!!-1_000).also {fixtures[repo.key(it)]=it}
        val roots=listOf(first,second)
        assertTrue(CrossPosts.sameSourceRepeats(roots))
        val owner=repo.library.save(first,emptyList(),fusionRoots=roots);repo.library.cancel(owner)
        val saved=repo.library.copy(owner)!!
        val before=repo.dao.bookmarksByKeys(listOf(owner)).single() to repo.dao.savedDetail(owner)
        val invalid=listOf(
            listOf(first,second.copy(canonicalUrl="https://x.com/unrelatedwriter/status/${second.id}",author=first.author,body="This claim is wrong. Our workshop cancelled the laptop battery session and replaced it with a pottery class.")),
            listOf(first,second.copy(parentId=first.id)),
            listOf(first,second.copy(preview=true)),
            listOf(first,second.copy(publishedAt=null)),
            listOf(first,second.copy(publishedAt=first.publishedAt!!-CrossPosts.MAX_COPY_WINDOW-1)),
            listOf(first,first)
        )
        for(editions in invalid) {
            val details=saved.details!!.copy(fusionRoots=editions)
            val payload=com.google.gson.JsonObject().apply {add("library",repo.gson.toJsonTree(PortableLibrary(saved=listOf(PortableSaved(saved.post,saved.savedAt,details)))))}
            val document=PortableDocument(PortableKind.STATE,System.currentTimeMillis(),emptyList(),statePayload=payload)
            assertTrue(runCatching {repo.portability.preview(PortableReader.encode(document).toByteArray(Charsets.UTF_8))}.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before,repo.dao.bookmarksByKeys(listOf(owner)).single() to repo.dao.savedDetail(owner))
        }
        val tooMany=(0..CrossPosts.MAX_EDITIONS).map {index->
            first.copy(id="oversized-$index",canonicalUrl="https://x.com/antisocial_saved_library_fixture/status/95${prefix}${index.toString().padStart(3,'0')}")
        }
        assertTrue(runCatching {repo.library.normalizedFusionRoots(tooMany.first(),tooMany)}.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun aCuratorsRepostedClipAndShortQuotationRemainSeparateOriginalsInsideOneSavedCopy()=runBlocking {
        val spoken="We didn't just talk about closing the border — we actually did it. We didn't just talk about getting opioid overdose deaths down — we actually did it. We didn't just talk about bringing trillions of dollars of new investment into the country — we actually did it."
        val longCaption=".@VP: \"$spoken\""
        val shortCaption="“We didn't just talk about closing the border — we actually did it.” - @VP 🎯"
        val thumbnail="https://pbs.twimg.com/amplify_video_thumb/95${prefix}000/img/fixture.jpg"
        val route=Source(Platform.X,"nitter","https://nitter.fixture.invalid")
        val provenance=Provenance(route,System.currentTimeMillis(),"https://nitter.fixture.invalid/WhiteHouse/rss")
        fun original(author: String,caption: String,repost: Boolean,index: Int): Post {
            val raw=post("curator-$index",caption)
            return raw.copy(canonicalUrl="https://x.com/$author/status/${raw.id}",author="@$author",body="$caption Video",
                title=if(repost)"RT by @WhiteHouse: $caption"else caption,publishedAt=System.currentTimeMillis()-index*1_000,
                media=listOf(Media(thumbnail)),provenance=provenance).also {fixtures[repo.key(it)]=it}
        }
        val long=original("RapidResponse47",longCaption,true,1)
        val short=original("WhiteHouse",shortCaption,false,0)
        val roots=listOf(long,short)
        assertTrue(CrossPosts.sameSourceRepeats(roots));assertNull(CrossPostFusion.compose(roots))
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        val owner=repo.library.save(long,emptyList(),fusionRoots=roots);repo.library.cancel(owner)
        assertEquals(roots,repo.library.copy(owner)!!.details!!.fusionRoots)
        val before=repo.library.copy(owner)!!
        val payload=com.google.gson.JsonObject().apply {add("library",repo.gson.toJsonTree(PortableLibrary(saved=listOf(PortableSaved(before.post,before.savedAt,before.details)))))}
        val document=PortableDocument(PortableKind.STATE,System.currentTimeMillis(),emptyList(),statePayload=payload)
        val preview=repo.portability.preview(PortableReader.encode(document).toByteArray(Charsets.UTF_8))
        assertEquals(1,preview.savedPosts)
        assertEquals(roots,repo.library.normalizedFusionRoots(before.post,before.details!!.fusionRoots!!))
        assertEquals("@RapidResponse47",before.post.author);assertEquals(long.canonicalUrl,before.post.canonicalUrl)
        assertTrue(before.details!!.fusionRoots!!.any {it.author=="@WhiteHouse"&&it.canonicalUrl==short.canonicalUrl})
        // A verified short X quote must not erase its fuller X original when a
        // second platform carries the full caption too. Validate the complete
        // repeated subset before using one caption per platform for composition.
        val blue=Post("RepeatSaved$prefix",Platform.BLUESKY,"https://bsky.app/profile/repair.test/post/RepeatSaved$prefix","repair.test",longCaption,publishedAt=long.publishedAt!!-1_000)
            .also {fixtures[repo.key(it)]=it}
        val mixed=roots+blue
        assertNull(CrossPostFusion.compose(mixed))
        assertNotNull(CrossPostFusion.compose(repo.library.fusionCaptionRoots(mixed)))
        assertEquals(mixed,repo.library.normalizedFusionRoots(long,mixed))
        repo.library.save(long,emptyList(),fusionRoots=mixed);repo.library.cancel(owner)
        val expanded=repo.library.copy(owner)!!
        assertEquals(mixed,expanded.details!!.fusionRoots)
        val mixedPayload=com.google.gson.JsonObject().apply {add("library",repo.gson.toJsonTree(PortableLibrary(saved=listOf(PortableSaved(expanded.post,expanded.savedAt,expanded.details)))))}
        val mixedDocument=PortableDocument(PortableKind.STATE,System.currentTimeMillis(),emptyList(),statePayload=mixedPayload)
        assertEquals(1,repo.portability.preview(PortableReader.encode(mixedDocument).toByteArray(Charsets.UTF_8)).savedPosts)
    }

    @Test fun fusedCopiesKeepEverySourceEditionPairedArchiveAndSearchableContextAcrossBackupAndCacheEviction()=runBlocking {
        val shared="$marker At our public repair workshop, neighbors learned how to replace a laptop battery, organize the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val firstImage="https://saved-library-$prefix.invalid/source-x.png"
        val secondImage="https://saved-library-$prefix.invalid/source-instagram.png"
        protectedImage(firstImage);protectedImage(secondImage)
        val primary=post(body=shared).copy(media=listOf(Media(firstImage)))
        val source=Post("LibSaved$prefix",Platform.INSTAGRAM,"https://www.instagram.com/p/LibSaved$prefix/","repairworkshop",
            "Workshop notebook.\n\n$shared\n\nBring the cobalttoolkit for the next session.",publishedAt=primary.publishedAt!!-1_000,media=listOf(Media(secondImage)))
        val paired=source.copy(archivedVersion=ArchivedVersion("Internet Archive",1_770_000_000_000L,
            "https://web.archive.org/web/20260202024000id_/${source.canonicalUrl}",
            PostSnapshot.from(source.copy(body="Earlier archived workshop announcement.",media=emptyList())),ArchiveContent.signature(source)))
        fixtures[repo.key(paired)]=paired
        val editions=listOf(primary,paired)
        val assets=repo.library.availableAssets(primary,fusionRoots=editions)
        val owner=repo.library.save(primary,assets,fusionRoots=editions);repo.library.cancel(owner)
        val before=repo.library.copy(owner)!!
        val capturedEditions=before.details!!.fusionRoots!!
        assertEquals(editions.map(repo::key),capturedEditions.map(repo::key))
        editions.zip(capturedEditions).forEach {(original,saved)->assertCapturedSource(original,saved)}
        assertEquals(paired.archivedVersion,before.details!!.fusionRoots!!.last().archivedVersion)
        assertEquals(setOf(firstImage,secondImage),before.details!!.selectedAssets.map {it.url}.toSet())
        assertTrue(results("cobalttoolkit","saved").any {it.key==owner&&it.matchedPost?.platform==Platform.INSTAGRAM})
        val backup=repo.gson.fromJson(String(repo.exportBackup()),Backup::class.java)
        val detailRow=backup.savedDetails.orEmpty().single {it.key==owner}
        val bookmark=backup.bookmarks.single {it.key==owner}
        val minimal=Backup(settings=backup.settings,follows=emptyList(),bookmarks=listOf(bookmark),history=emptyList(),savedDetails=listOf(detailRow))
        repo.importBackup(repo.gson.toJson(minimal).toByteArray());repo.library.cancel(owner)
        repo.dao.removePosts(listOf(owner,repo.key(paired)))
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        var remoteReads=0
        repo.directLoader={_,_->remoteReads++;error("A saved source edition must remain local")}
        val restored=repo.library.copy(owner)!!
        assertEquals(capturedEditions,restored.details!!.fusionRoots)
        assertNotNull(CrossPostFusion.compose(restored.details!!.fusionRoots!!))
        assertTrue(results("cobalttoolkit","saved").any {it.key==owner})
        assertNotNull(repo.library.assetFile(secondImage));assertEquals(0,remoteReads)
        // Re-saving a later live rendition must not edit an existing local half.
        val changed=paired.copy(body="$shared\n\nLater changedcontext edition.",archivedVersion=null)
        repo.library.save(primary,assets,fusionRoots=listOf(primary,changed));repo.library.cancel(owner)
        assertEquals(capturedEditions,repo.library.copy(owner)!!.details!!.fusionRoots)
        assertFalse(results("changedcontext","saved").any {it.key==owner})
        val invalid=minimal.copy(savedDetails=listOf(detailRow.copy(payload=repo.gson.toJson(before.details!!.copy(fusionRoots=listOf(paired))))))
        assertTrue(runCatching {repo.importBackup(repo.gson.toJson(invalid).toByteArray())}.exceptionOrNull() is IllegalArgumentException)
        assertEquals(capturedEditions,repo.library.copy(owner)!!.details!!.fusionRoots)
    }

    @Test fun aLaterPairedArchiveKeepsCapturedFusionEditionsReadableAndImportableWithoutAcceptingChangedText()=runBlocking {
        val shared="$marker At our public repair workshop, neighbors learned how to replace a laptop battery, organize the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val primary=post(body=shared)
        val source=Post("ArchiveFusion$prefix",Platform.INSTAGRAM,"https://www.instagram.com/p/ArchiveFusion$prefix/","repairworkshop",
            "Workshop notebook.\n\n$shared\n\nBring the cobalttoolkit for our next session.",publishedAt=primary.publishedAt!!-1_000).also {fixtures[repo.key(it)]=it}
        val editions=listOf(primary,source)
        val owner=repo.library.save(primary,emptyList(),fusionRoots=editions);repo.library.cancel(owner)
        assertNull(repo.library.copy(owner)!!.post.archivedVersion)
        val archive=ArchivedVersion("Internet Archive",1_770_000_000_000L,
            "https://web.archive.org/web/20260202024000id_/${primary.canonicalUrl}",
            PostSnapshot.from(primary.copy(body="Earlier workshop announcement.")))
        val history=ArchiveHistoryStore(repo,ArchiveCheckPolicy(),budgetFile=null).apply {lookup={archive}}
        mediaKeys+="archive-"+Repository.hash(owner)
        repo.prefs.archiveChecks=true
        history.check(primary)
        val later=repo.library.copy(owner)!!
        assertNotNull(later.post.archivedVersion)
        assertEquals(editions,later.details!!.fusionRoots)
        // The reader uses this same normalizer when an archive has been added
        // to the bookmark independently of its captured source manifest.
        val readable=repo.library.normalizedFusionRoots(later.post,later.details!!.fusionRoots!!)
        assertEquals(2,readable.size);assertEquals(later.post.archivedVersion,readable.first().archivedVersion)
        assertEquals(shared,readable.first().body);assertEquals(source,readable.last())
        assertNotNull(CrossPostFusion.compose(readable))
        val backup=repo.gson.fromJson(String(repo.exportBackup()),Backup::class.java)
        val detail=backup.savedDetails.orEmpty().single {it.key==owner}
        val minimal=Backup(settings=backup.settings,follows=emptyList(),bookmarks=listOf(backup.bookmarks.single {it.key==owner}),history=emptyList(),savedDetails=listOf(detail))
        repo.importBackup(repo.gson.toJson(minimal).toByteArray());repo.library.cancel(owner)
        val imported=repo.library.copy(owner)!!
        assertEquals(later.post,imported.post);assertEquals(readable,imported.details!!.fusionRoots)
        assertTrue(results("cobalttoolkit","saved").any {it.key==owner})
        fun badBackup(edited: Post)=minimal.copy(savedDetails=listOf(detail.copy(payload=repo.gson.toJson(later.details!!.copy(fusionRoots=listOf(edited,source))))))
        val changedText=primary.copy(body="$shared Changed original text.")
        assertTrue(runCatching {repo.importBackup(repo.gson.toJson(badBackup(changedText)).toByteArray())}.exceptionOrNull() is IllegalArgumentException)
        val invalidPair=primary.copy(archivedVersion=later.post.archivedVersion!!.copy(currentSignature="not-the-source-signature"))
        assertTrue(runCatching {repo.importBackup(repo.gson.toJson(badBackup(invalidPair)).toByteArray())}.exceptionOrNull() is IllegalArgumentException)
        assertEquals(readable,repo.library.copy(owner)!!.details!!.fusionRoots)
    }

    @Test fun savingAgainDoesNotReplaceTheFirstCapturedPostEdition()=runBlocking {
        val original=post(body="$marker firstedition exact saved text")
        val firstReply=post("reply","$marker firstreplyedition retained text").copy(parentId=original.id)
        val removedReply=post("reply","$marker removedreplyedition retained text").copy(parentId=original.id)
        val thread=CapturedThread(original,listOf(firstReply,removedReply),capturedAt=System.currentTimeMillis(),hasMore=true)
        val owner=save(original,threads=listOf(thread));val first=repo.library.copy(owner)!!
        val changed=original.copy(body="$marker lateredition edited public text",author="Changed display name")
        val appended=post("reply","$marker appendedreply new capture").copy(parentId=original.id)
        save(changed,threads=listOf(CapturedThread(changed,listOf(firstReply.copy(body="$marker changedreply should not replace"),appended),capturedAt=System.currentTimeMillis()+1,hasMore=false)))
        val after=repo.library.copy(owner)!!
        assertEquals(original,after.post);assertEquals(first.savedAt,after.savedAt);assertEquals(first.details!!.capturedAt,after.details!!.capturedAt)
        val captured=after.details!!.threads.single()
        assertEquals(original,captured.root);assertEquals(setOf(firstReply,removedReply,appended),captured.replies.toSet())
        assertEquals(thread.capturedAt,captured.capturedAt);assertTrue(captured.hasMore)
        assertTrue(results("firstedition","saved").any {it.key==owner})
        assertFalse(results("lateredition","saved").any {it.key==owner})
        assertTrue(results("firstreplyedition","saved").any {it.key==owner})
        assertTrue(results("removedreplyedition","saved").any {it.key==owner})
        assertTrue(results("appendedreply","saved").any {it.key==owner})
        assertFalse(results("changedreply","saved").any {it.key==owner})
    }

    @Test fun optedInHistoryHasExpiryAndByteBoundsAndClearingItKeepsSavedCopies()=runBlocking {
        val protected=post(body="$marker protectedcopy remains saved");val owner=save(protected)
        val visited=post("history","$marker retainedvisit battery repair instructions")
        repo.library.recordHistory(visited,System.currentTimeMillis())
        assertNull(repo.dao.retainedHistory(repo.key(visited)))
        repo.prefs.historyFullText=true;repo.prefs.historyTextMb=5
        repo.library.recordHistory(visited,System.currentTimeMillis())
        assertTrue(results("retainedvisit","history").any {it.key==repo.key(visited)})
        val expired=post("old-history","$marker expiredvisit")
        repo.library.recordHistory(expired,System.currentTimeMillis()-3651*CachePolicy.DAY)
        assertNull(repo.dao.retainedHistory(repo.key(expired)))
        val large="x".repeat(800*1024)
        repo.db.withTransaction {
            repeat(7) {index->
                val p=post("bounded","$marker boundedhistory$index $large");val json=repo.gson.toJson(p)
                repo.dao.putRetainedHistory(RetainedHistoryRow(repo.key(p),p.platform.id,json,System.currentTimeMillis()+index,json.toByteArray().size.toLong()))
            }
        }
        repo.library.pruneHistory()
        assertTrue(repo.dao.allRetainedHistory().sumOf {it.bytes}<=5L*1024*1024)
        assertTrue(repo.dao.allRetainedHistory().size<=2000)
        repo.library.clearHistory()
        assertTrue(repo.dao.allRetainedHistory().isEmpty());assertTrue(repo.dao.documentsOfKind("history").isEmpty());assertTrue(repo.dao.allHistory().isEmpty())
        assertEquals(protected,repo.library.copy(owner)!!.post)
        assertTrue(results("protectedcopy","saved").any {it.key==owner})
    }

    @Test fun removingACopyInvalidatesItsTokenAndRejectsADelayedMediaCommit()=runBlocking {
        val url="https://saved-library-$prefix.invalid/late.png";protectedImage(url)
        val p=post().copy(media=listOf(Media(url)));val owner=save(p,repo.library.availableAssets(p))
        val token=repo.library.copy(owner)!!.details!!.token
        val temp=File(repo.context.cacheDir,"saved-library-$prefix.part").apply {writeBytes(byteArrayOf(9,8,7))}.also {tempFiles+=it}
        repo.library.remove(owner)
        assertFalse(repo.library.validToken(owner,token));assertNull(repo.library.copy(owner))
        assertNull(repo.library.commitAsset(owner,token,url,temp))
        assertNull(repo.library.assetFile(url));assertTrue(temp.isFile)
        assertFalse(results(marker,"saved").any {it.key==owner})
    }

    @Test fun readingAndSearchingASavedCopyUsesNoRemotePostReaderWithNetworkFailClosed()=runBlocking {
        val p=post().copy(media=listOf(Media("https://saved-library-$prefix.invalid/not-selected.png")))
        val owner=save(p)
        val remoteReads=AtomicInteger()
        repo.directLoader={_,_->remoteReads.incrementAndGet();error("A local reader must not fetch the original")}
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        val copy=repo.library.copy(owner)!!
        assertEquals(p,copy.post);assertTrue(copy.details!!.selectedAssets.isEmpty())
        assertTrue(results(marker,"saved").any {it.key==owner})
        assertEquals(0,remoteReads.get())
    }
    @Test fun wrongTypedBackupSettingsCannotPartiallyImportSavedCopiesOrCollections()=runBlocking {
        val original=post(body="$marker existing durable saved copy");save(original)
        val candidate=post(body="$marker must not be imported")
        val row=BookmarkRow(repo.key(candidate),candidate.platform.id,repo.gson.toJson(candidate),System.currentTimeMillis())
        val folder=LibraryCollectionRow("invalid-backup-$prefix","Invalid backup $prefix",System.currentTimeMillis()).also {collections+=it.id}
        val base=com.google.gson.JsonParser.parseString(String(repo.exportBackup())).asJsonObject
        base.getAsJsonArray("bookmarks").add(repo.gson.toJsonTree(row));base.getAsJsonArray("collections").add(repo.gson.toJsonTree(folder))
        val badSet=com.google.gson.JsonArray().apply {add("valid-list");add(7)}
        val cases=listOf(
            "historyFullText" to com.google.gson.JsonPrimitive("true"),
            "cacheMb" to com.google.gson.JsonPrimitive("500"),
            "disabledLists" to badSet,
            "tor" to com.google.gson.JsonPrimitive("false"),
            "offset:${Platform.X.id}" to com.google.gson.JsonPrimitive("25"),
            "readingGroup:${Platform.X.id}" to com.google.gson.JsonPrimitive(false),
            "unsupported-setting" to com.google.gson.JsonObject()
        )
        val beforePrefs=com.google.gson.JsonParser.parseString(repo.prefs.export().toString())
        val beforeSaved=repo.dao.allBookmarks().toSet();val beforeFolders=repo.dao.allCollections().toSet()
        for((key,value) in cases) {
            val backup=base.deepCopy();backup.getAsJsonObject("settings").add(key,value)
            val failure=runCatching {repo.importBackup(backup.toString().toByteArray())}.exceptionOrNull()
            assertTrue("Wrong type for $key must be rejected before import",failure is IllegalArgumentException)
            assertEquals(beforePrefs,com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
            assertEquals(beforeSaved,repo.dao.allBookmarks().toSet());assertEquals(beforeFolders,repo.dao.allCollections().toSet())
            assertNull(repo.dao.savedDetail(row.key));assertNull(repo.dao.collection(folder.id))
        }
    }
}
