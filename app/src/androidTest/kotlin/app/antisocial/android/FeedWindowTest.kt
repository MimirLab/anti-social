package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class FeedWindowTest {
    @Test fun aNewSourceMembershipStillArrivesWithoutReparsingUnchangedContent()=runBlocking {
        val repo=(ApplicationProvider.getApplicationContext<Context>() as AntiSocialApplication).repo
        repo.awaitReady()
        val suffix=System.nanoTime().toString();val person="feed-membership-$suffix"
        val target=repo.routes.target(Platform.X,"perf_fixture")
        val follows=(1..2).map {Follow("$person-$it",target,"Window fixture",personId=person)}
        val post=Post(suffix,Platform.X,"https://x.com/perf_fixture/status/$suffix","Workshop","Shared source body",publishedAt=System.currentTimeMillis())
        val snapshots=CopyOnWriteArrayList<FollowedPostSnapshot>()
        var observer:Job?=null
        try {
            follows.forEach {repo.dao.putFollow(FollowRow(it.id,target.platform.id,repo.gson.toJson(it)))}
            repo.store(Page(listOf(post)),follows.first().id,true)
            observer=launch(Dispatchers.Default){repo.followedSnapshot(personId=person).collect {snapshots+=it}}
            withTimeout(10_000){while(snapshots.lastOrNull()?.memberships?.get(repo.key(post))!=setOf(follows.first().id))delay(10)}
            val original=snapshots.last().posts.single()
            repo.dao.putPostFollow(PostFollowRow(repo.key(post),follows.last().id))
            withTimeout(10_000){while(snapshots.lastOrNull()?.memberships?.get(repo.key(post))!=follows.map {it.id}.toSet())delay(10)}
            assertSame("A new linked source must arrive independently of payload decoding",original,snapshots.last().posts.single())
        }finally {
            observer?.cancelAndJoin();follows.forEach {repo.dao.removeFollow(it.id)};repo.dao.removePosts(listOf(repo.key(post)))
            for(table in listOf("seen","history"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(post)))
        }
    }
    @Test fun readingTouchesDoNotRepublishDecodedPostsButChangedSourceMaterialDoes()=runBlocking {
        val repo=(ApplicationProvider.getApplicationContext<Context>() as AntiSocialApplication).repo
        repo.awaitReady()
        val suffix=System.nanoTime().toString();val person="feed-window-$suffix"
        val target=repo.routes.target(Platform.X,"perf_fixture")
        val follow=Follow(person,target,"Window fixture",personId=person)
        val post=Post(suffix,Platform.X,"https://x.com/perf_fixture/status/$suffix","Workshop","Initial source body",publishedAt=System.currentTimeMillis())
        val snapshots=CopyOnWriteArrayList<List<Post>>()
        var observer:Job?=null
        try {
            repo.dao.putFollow(FollowRow(follow.id,target.platform.id,repo.gson.toJson(follow)))
            repo.store(Page(listOf(post)),follow.id,true)
            observer=launch(Dispatchers.Default){repo.posts(null,personId=person).collect {snapshots+=it}}
            withTimeout(10_000){while(snapshots.isEmpty())delay(10)}
            assertEquals(post,snapshots.single().single())
            repeat(8){repo.dao.touchPost(repo.key(post),System.currentTimeMillis()+it);delay(15)}
            delay(300);assertEquals("Bookkeeping cannot invalidate the reader's source snapshot",1,snapshots.size)
            val changed=post.copy(body="The source published a corrected body")
            repo.store(Page(listOf(changed)),follow.id,true)
            withTimeout(10_000){while(snapshots.last().single().body!=changed.body)delay(10)}
            assertEquals(2,snapshots.size)
        }finally {
            observer?.cancelAndJoin();repo.dao.removeFollow(follow.id);repo.dao.removePosts(listOf(repo.key(post)))
            for(table in listOf("seen","history"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(post)))
        }
    }
}
