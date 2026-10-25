package app.antisocial.android

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.LocalSearch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalLibraryMigrationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun document(owner: String="saved",entry: String=owner,kind: String="saved",body: String="Café public archive",at: Long=200)=
        LocalDocumentRow(ownerKey=owner,entryKey=entry,kind=kind,platform="twitter",title="Local copy",body=body,author="Public author",url="https://x.com/example/status/1",sortAt=at)

    @Test fun versionTwoUpgradePreservesEveryExistingTableAndCreatesAWorkingFtsIndex()=runBlocking {
        val name="local-library-migration-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,AntiSocialDatabase::class.java,name).build()
        try {
            val follow=FollowRow("follow","twitter","subscription")
            val post=PostRow("post","twitter","complete ordinary post",100,80,90,22,"follow")
            val saved=BookmarkRow("saved","twitter","durable saved text",120)
            val history=HistoryRow("post","twitter","https://x.com/example/status/1","Viewed title",130)
            val health=HealthRow("source","health",140)
            val media=MediaRow("offline","protected-file",99,90,100,true)
            val seen=SeenRow("seen","twitter",70,true)
            db.dao().apply {putFollow(follow);putPost(post);putBookmark(saved);putHistory(history);putHealth(health);putMedia(media);putSeen(seen)}
            // These are exactly the v2 tables; only additive v3 tables are removed.
            db.openHelper.writableDatabase.apply {
                for(table in listOf("local_documents_fts","local_documents","saved_assets","saved_details","saved_collections","library_collections","retained_history"))execSQL("DROP TABLE `$table`")
                execSQL("PRAGMA user_version=2")
            }
            db.close()
            db=Room.databaseBuilder(context,AntiSocialDatabase::class.java,name).addMigrations(AntiSocialDatabase.MIGRATION_1_2,AntiSocialDatabase.MIGRATION_2_3).build()
            val dao=db.dao()
            assertEquals(listOf(follow),dao.allFollows());assertEquals(listOf(post),dao.allPosts())
            assertEquals(listOf(saved),dao.allBookmarks());assertEquals(listOf(history),dao.allHistory())
            assertEquals(listOf(health),dao.allHealth());assertEquals(listOf(media),dao.allMedia());assertEquals(seen,dao.seen("seen"))
            assertEquals(listOf("post"),dao.followedPosts(listOf("follow")).first().map {it.key})
            assertTrue(dao.allSavedDetails().isEmpty());assertTrue(dao.allCollections().isEmpty());assertTrue(dao.allSavedAssets().isEmpty());assertTrue(dao.allRetainedHistory().isEmpty())
            dao.putDocument(document())
            val original=dao.document("saved","saved","saved")!!
            assertTrue(original.rowid>0)
            assertEquals(listOf("saved"),dao.searchDocuments(LocalSearch.query("caf")!!).first().map {it.ownerKey})
            dao.putDocument(document(body="Replacement gamma"))
            assertEquals(original.rowid,dao.document("saved","saved","saved")!!.rowid)
            assertTrue(dao.searchDocuments(LocalSearch.query("caf")!!).first().isEmpty())
            assertEquals(1,dao.searchDocuments(LocalSearch.query("gamma")!!).first().size)
            dao.deleteDocuments("saved","saved")
            assertTrue(dao.searchDocuments(LocalSearch.query("gamma")!!).first().isEmpty())
            assertEquals(3,db.openHelper.readableDatabase.version)
        }finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun collectionAndSelectedAssetMetadataIsAtomicAndCascadesWithItsSavedOwner()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao();dao.putBookmark(BookmarkRow("saved","twitter","Protected root",100))
            dao.putSavedDetail(SavedDetailRow("saved","Captured available thread"))
            dao.putCollection(LibraryCollectionRow("one","Research",100));dao.putCollection(LibraryCollectionRow("two","Reading",110))
            assertEquals("one",dao.collectionNamed("research")!!.id)
            runCatching {dao.putCollection(LibraryCollectionRow("duplicate","RESEARCH",120))}
            assertEquals(setOf("one","two"),dao.allCollections().map {it.id}.toSet())
            assertNull(dao.collection("duplicate"));assertEquals("Research",dao.collectionNamed("RESEARCH")!!.name)
            dao.setCollectionLinks("saved",listOf("one","one","two"))
            assertEquals(2,dao.allCollectionLinks().size)
            assertTrue(runCatching {dao.setCollectionLinks("saved",listOf("missing"))}.isFailure)
            assertEquals(2,dao.allCollectionLinks().size)
            val asset=SavedAssetRow("saved","image","new-token","https://images.example.org/1.png","https://x.com/example/status/1","image","pending",updatedAt=100)
            dao.putSavedAsset(asset)
            assertEquals(0,dao.updateSavedAsset("saved","image","old-token","complete",99,null,110))
            assertEquals("pending",dao.asset("saved","image")!!.status)
            assertEquals(1,dao.updateSavedAsset("saved","image","new-token","complete",99,null,120))
            assertEquals(99L,dao.assets("saved").first().single().bytes)
            dao.removeCollection("one")
            assertEquals(listOf("two"),dao.allCollectionLinks().map {it.collectionId})
            dao.removeBookmark("saved")
            assertNull(dao.savedDetail("saved"));assertTrue(dao.allCollectionLinks().isEmpty());assertTrue(dao.allSavedAssets().isEmpty())
            assertEquals(listOf("two"),dao.allCollections().map {it.id})
        }finally {db.close()}
    }

    @Test fun localSearchIncludesCapturedCommentsAndOnlyRequestedRetainedHistoryAndCollections()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao();dao.putBookmark(BookmarkRow("saved","twitter","root",100))
            dao.putCollection(LibraryCollectionRow("collection","Study",100));dao.setCollectionLinks("saved",listOf("collection"))
            dao.putDocument(document(body="Root alpha",at=200));dao.putDocument(document(entry="comment",body="Captured comment gamma",at=210))
            dao.putRetainedHistory(RetainedHistoryRow("visited","twitter","Retained history gamma",220,22))
            dao.putDocument(document(owner="visited",kind="history",body="Retained history gamma",at=220))
            val query=LocalSearch.query("gamma")!!
            assertEquals(listOf("comment"),dao.searchDocuments(query).first().map {it.entryKey})
            assertEquals(listOf("comment"),dao.searchDocuments(LocalSearch.query("captured gamma")!!).first().map {it.entryKey})
            assertTrue(dao.searchDocuments(LocalSearch.query("root missing")!!).first().isEmpty())
            assertEquals(listOf("visited","comment"),dao.searchDocuments(query,includeHistory=true).first().map {it.entryKey})
            assertEquals(listOf("comment"),dao.searchDocuments(query,includeHistory=true,collectionId="collection").first().map {it.entryKey})
            assertTrue(dao.searchDocuments(query,includeSaved=false,includeHistory=false).first().isEmpty())
            assertNull(LocalSearch.query("***"));assertEquals(1,dao.browseDocuments().first().size)
            assertTrue(dao.searchDocuments(LocalSearch.query("OR NOT body:*")!!,includeHistory=true).first().isEmpty())
            assertTrue(dao.browseDocuments(limit=-1).first().isEmpty())
            dao.clearDocumentKind("history");dao.clearRetainedHistory()
            assertTrue(dao.allRetainedHistory().isEmpty());assertEquals(2,dao.allDocuments().size)
        }finally {db.close()}
    }
    @Test fun conceptAndPluralPathsRunOnTheRealFtsIndexWhileQuotedNamesStayExact()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao()
            val rows=mapOf("literal" to "The rocket launch opens tomorrow", "related" to "A successful spaceflight", "separated" to "Rocket recovery after an unrelated launch", "singular" to "A burger", "plural" to "Fresh burgers", "pastries" to "Fresh pastries", "pastry" to "A pastry")
            rows.forEach {(key,body)->dao.putBookmark(BookmarkRow(key,"twitter","root",100));dao.putDocument(document(owner=key,body=body))}
            suspend fun owners(q:String)=dao.searchDocuments(q).first().map {it.ownerKey}.toSet()
            assertEquals(setOf("singular","plural"),owners(LocalSearch.query("burger")!!))
            assertEquals(setOf("singular","plural"),owners(LocalSearch.query("burgers")!!))
            val related=dao.searchDocumentPaths(librarySearchPaths(LocalSearch.expandedQueries("rocket launch"))).first().map {it.ownerKey}.toSet()
            assertTrue("related" in related)
            assertEquals(setOf("literal"),owners(LocalSearch.query("\"rocket launch\"")!!))
            assertEquals(setOf("pastry","pastries"),owners(LocalSearch.query("pastry")!!)+dao.searchDocumentPaths(librarySearchPaths(LocalSearch.expandedQueries("pastry"))).first().map {it.ownerKey}.toSet())
            assertTrue(LocalSearch.expandedQueries("\"rocket launch\"").isEmpty())
            val excluded=dao.searchDocumentPaths(librarySearchPaths(LocalSearch.literalQueries("rocket -unrelated"),excluded=LocalSearch.excludedQueries("rocket -unrelated"))).first().map {it.ownerKey}.toSet()
            assertEquals(setOf("literal"),excluded)
        }finally {db.close()}
    }
    @Test fun oneLargeCapturedThreadCannotConsumeEveryLocalSearchResultSlot()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao()
            db.withTransaction {
                dao.putDocument(document(owner="large",body="searchable root",at=300))
                repeat(300){i->dao.putDocument(document(owner="large",entry="reply-$i",body="searchable captured reply $i",at=300))}
                dao.putDocument(document(owner="second",body="searchable separate copy",at=200))
            }
            val hits=dao.searchDocuments(LocalSearch.query("searchable")!!).first()
            assertEquals(setOf("large","second"),hits.map {it.ownerKey}.toSet());assertEquals(2,hits.size)
            assertEquals(setOf("large","second"),dao.browseDocuments().first().map {it.ownerKey}.toSet())
        }finally {db.close()}
    }
}
