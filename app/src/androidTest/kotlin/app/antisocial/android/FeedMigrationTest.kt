package app.antisocial.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeedMigrationTest {
    @Test fun versionOneUpgradePreservesLocalDataAndBackfillsOnlyExistingFollows()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="feed-migration-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,AntiSocialDatabase::class.java,name).build()
        try {
            val followed=PostRow("followed","twitter","Complete public post",200,100,150,20,"active")
            val orphan=PostRow("former-follow","reddit","Retained ordinary body",180,90,bytes=22,followId="removed")
            val direct=PostRow("direct","twitter","Shared link history",300,110,bytes=19)
            val follow=FollowRow("active","twitter","Local subscription")
            val saved=BookmarkRow("saved","twitter","Protected saved copy",120)
            val history=HistoryRow("direct","twitter","https://x.com/example/status/3","History title",160)
            val health=HealthRow("source","Known source",140)
            val media=MediaRow("offline","local-file",80,100,130,true)
            val seen=SeenRow("expired","twitter",80,true)
            db.dao().apply {
                putFollow(follow);putPost(followed);putPost(orphan);putPost(direct)
                putBookmark(saved);putHistory(history);putHealth(health);putMedia(media);putSeen(seen)
            }
            // Version 1 has precisely the same seven original table definitions.
            // Remove the sole v2 addition and mark the on-disk schema as v1; the
            // real Room open below runs and validates the registered migration.
            db.openHelper.writableDatabase.apply {execSQL("DROP TABLE post_follows");execSQL("PRAGMA user_version = 1")}
            db.close()
            db=Room.databaseBuilder(context,AntiSocialDatabase::class.java,name)
                .addMigrations(AntiSocialDatabase.MIGRATION_1_2,AntiSocialDatabase.MIGRATION_2_3).build()
            val dao=db.dao()
            assertEquals(setOf(followed,orphan,direct),dao.allPosts().toSet())
            assertEquals(listOf(follow),dao.allFollows());assertEquals(listOf(saved),dao.allBookmarks())
            assertEquals(listOf(history),dao.allHistory());assertEquals(listOf(health),dao.allHealth())
            assertEquals(listOf(media),dao.allMedia());assertEquals(seen,dao.seen(seen.key))
            assertEquals(listOf("followed"),dao.followedPosts(listOf("active")).first().map {it.key})
            assertTrue(dao.followedPosts(listOf("removed")).first().isEmpty())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM post_follows").use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
            assertEquals(3,db.openHelper.readableDatabase.version)
            // Multi-source membership must continue working after migration,
            // without rewriting or deleting the migrated ordinary body.
            dao.putPost(followed.copy(followId="second"))
            assertEquals(listOf("followed"),dao.followedPosts(listOf("active")).first().map {it.key})
            assertEquals(listOf("followed"),dao.followedPosts(listOf("second")).first().map {it.key})
        }finally {db.close();context.deleteDatabase(name)}
    }
}
