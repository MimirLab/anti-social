package app.antisocial.android

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageQueriesTest {
    @Test fun storageTotalsAndFollowedFeedsUseMetadataWithoutMixingReadHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao()
            dao.putPost(PostRow("followed","twitter","{\"body\":\"😀\"}",20,1,bytes=19,followId="following"))
            dao.putPost(PostRow("shared-link","twitter","Read history",30,1,bytes=29))
            dao.putBookmark(BookmarkRow("saved","twitter","😀中文",1))
            dao.putMedia(MediaRow("ordinary","ordinary",50,1))
            dao.putMedia(MediaRow("saved","saved",70,1,saved=true))
            assertEquals(48L,dao.postBytes())
            assertEquals("😀中文".toByteArray().size.toLong(),dao.bookmarkBytes())
            assertEquals(50L,dao.mediaBytes(false));assertEquals(70L,dao.mediaBytes(true))
            assertEquals(setOf("followed","shared-link"),dao.postCacheRows().map {it.key}.toSet())
            assertEquals(listOf("followed"),dao.followedPosts("twitter",listOf("following")).first().map {it.key})
            assertTrue(dao.followedPosts("twitter",emptyList()).first().isEmpty())
        }finally {db.close()}
    }
    @Test fun oneCanonicalPostRetainsEveryFollowMembershipAcrossContentAndHistoryUpserts()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao()
            val original=PostRow("same-post","twitter","First readable body",20,1,bytes=19,followId="person-a")
            dao.putPost(original)
            dao.putPost(original.copy(payload="Current readable body",followId="group-b"))
            dao.putPost(dao.post(original.key)!!.copy(lastViewedAt=90,followId=null))
            for(id in listOf("person-a","group-b")) {
                val scoped=dao.followedPosts("twitter",listOf(id)).first()
                assertEquals(listOf("same-post"),scoped.map {it.key})
                assertEquals("Current readable body",scoped.single().payload)
            }
            assertEquals(listOf("same-post"),dao.followedPosts(listOf("person-a","group-b")).first().map {it.key})
            assertEquals(1,dao.allPosts().size)
            assertTrue(dao.followedPosts("reddit",listOf("person-a","group-b")).first().isEmpty())
        }finally {db.close()}
    }
    @Test fun removingOneFollowKeepsOtherMembershipAndTheOrdinaryPostBody()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao()
            dao.putFollow(FollowRow("a","twitter","A"));dao.putFollow(FollowRow("b","twitter","B"))
            val post=PostRow("shared","twitter","Retained body",20,1,bytes=13,followId="b")
            dao.putPost(post);dao.putPost(post.copy(followId="a"))
            dao.removeFollow("a")
            assertTrue(dao.followedPosts(listOf("a")).first().isEmpty())
            assertEquals(listOf("shared"),dao.followedPosts(listOf("b")).first().map {it.key})
            val retained=dao.post("shared")!!
            assertEquals("Retained body",retained.payload);assertNull(retained.followId)
            dao.putPost(retained.copy(lastViewedAt=100))
            assertTrue("A history metadata update cannot resurrect a removed follow",dao.followedPosts(listOf("a")).first().isEmpty())
            assertEquals(listOf("b"),dao.allFollows().map {it.id})
        }finally {db.close()}
    }
    @Test fun deletingCachedPostsCascadesMembershipWithoutLeavingAStaleFeedReference()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao();val post=PostRow("expired","twitter","body",20,1,bytes=4,followId="a")
            dao.putPost(post);dao.putPost(post.copy(followId="b"));dao.removePosts(listOf(post.key))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM post_follows").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            dao.putPost(post.copy(followId=null))
            assertTrue(dao.followedPosts(listOf("a","b")).first().isEmpty())
            assertNotNull(dao.post(post.key))
        }finally {db.close()}
    }
    @Test fun combinedMembershipQueriesDeduplicateBeforeTheirGlobalChronologicalLimit()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AntiSocialDatabase::class.java).build()
        try {
            val dao=db.dao()
            db.withTransaction {
                repeat(1002) {index->
                    val post=PostRow("post-$index",if(index%2==0)"twitter"else "reddit","body",index.toLong(),1,bytes=4,followId="a")
                    dao.putPost(post);dao.putPost(post.copy(followId="b"))
                }
                dao.putPost(PostRow("undated","twitter","body",null,10_000,bytes=4,followId="a"))
                dao.putPost(PostRow("unfollowed","twitter","history",20_000,1,bytes=7))
            }
            val all=dao.followedPosts(listOf("a","b")).first()
            assertEquals(1000,all.size);assertEquals(1000,all.map {it.key}.distinct().size)
            assertEquals("post-1001",all.first().key);assertEquals("post-2",all.last().key)
            assertTrue(all.zipWithNext().all {(a,b)->a.publishedAt!!>b.publishedAt!!})
            val x=dao.followedPosts("twitter",listOf("a","b")).first()
            assertEquals(502,x.size);assertEquals("undated",x.last().key)
        }finally {db.close()}
    }
}
