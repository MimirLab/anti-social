package app.antisocial.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native persisted-state validation. No server requests or local import writes. */
@RunWith(AndroidJUnit4::class)
class YouTubeSavedStateTest {
    private val id="dQw4w9WgXcQ"
    private val ghost="https://ghostarchive.org/varchive/$id"
    private val clip="https://ghostvideo.b-cdn.net/video/$id/$id.mp4"
    private val now=System.currentTimeMillis()
    private val gson=Gson()
    private val repo get()=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo
    private fun post(audio: String?="https://cdn.example.org/audio.m4a")=Post(id,Platform.YOUTUBE,YouTubeUrls.canonical(id),"A creator","A saved caption",media=listOf(Media("https://cdn.example.org/video.mp4","video",audioUrl=audio,audioMimeType=audio?.let {"audio/mp4"})))
    private fun document(post: Post)=PortableReader.encode(PortableDocument(PortableKind.STATE,now,emptyList(),statePayload=JsonObject().apply {
        add("library",gson.toJsonTree(PortableLibrary(saved=listOf(PortableSaved(post,now)))))
    })).toByteArray()

    @Test fun splitAudioFieldsRoundTripAndOldSavedJsonRemainsReadable() {
        val json=gson.toJsonTree(post()).asJsonObject
        strictSavedModel(json,Post::class.java)
        assertEquals(post(),gson.fromJson(json,Post::class.java))
        json.getAsJsonArray("media")[0].asJsonObject.apply {remove("audioUrl");remove("audioMimeType")}
        strictSavedModel(json,Post::class.java)
        assertNull(gson.fromJson(json,Post::class.java).media.single().audioUrl)
    }
    @Test fun splitAudioScalarCoercionIsRejectedBeforeGson() {
        val json=gson.toJsonTree(post()).asJsonObject
        json.getAsJsonArray("media")[0].asJsonObject.addProperty("audioUrl",123)
        assertThrows(IllegalArgumentException::class.java){strictSavedModel(json,Post::class.java)}
    }
    @Test fun unsafeAudioUrlsAndAudioOnAnImageCannotEnterPortableSavedState(): Unit=runBlocking {
        repo.awaitReady()
        for(audio in listOf("file:///private/audio.m4a","https://user:secret@cdn.example.org/audio.m4a","https://cdn.example.org/"+"x".repeat(17*1024))) {
            assertThrows(IllegalArgumentException::class.java){runBlocking {repo.portability.preview(document(post(audio)))}}
        }
        val image=post().let {it.copy(media=listOf(it.media.single().copy(type="image")))}
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.portability.preview(document(image))}}
    }
    @Test fun validSplitAudioIsPreservedByPortablePreview(): Unit=runBlocking {
        repo.awaitReady();val preview=repo.portability.preview(document(post()))
        val encoded=preview.document.statePayload!!.getAsJsonObject("library").getAsJsonArray("saved")[0].asJsonObject.getAsJsonObject("post")
        assertEquals(post().media.single().audioUrl,gson.fromJson(encoded,Post::class.java).media.single().audioUrl)
    }
    @Test fun exactGhostarchiveRecoverySurvivesSavedStateValidation() {
        val result=RecoveredYouTubeVideo(id,Media(clip,"video",mimeType="video/mp4"),ghost,"Ghostarchive",now-1_000,"A title","A caption","An author")
        val post=result.asPost(now=now)
        assertTrue(RecoveredArchives.valid(post,now))
        assertFalse(RecoveredArchives.valid(post.copy(canonicalUrl=YouTubeUrls.canonical("aaaaaaaaaaa")),now))
        assertFalse(RecoveredArchives.valid(post.copy(media=listOf(post.media.single().copy(audioUrl="https://unrelated.example/audio.webm",audioMimeType="audio/webm"))),now))
        assertFalse(RecoveredArchives.valid(post.copy(recoveredArchive=post.recoveredArchive!!.copy(provider="Internet Archive")),now))
    }
    @Test fun splitWaybackRecoveryKeepsBothTracksBoundToTheRequestedId() {
        val captured=1_672_628_645_000L
        val video="https://web.archive.org/web/20230102030405id_/https://r1.googlevideo.com/videoplayback?video_id=$id&itag=248"
        val audio="https://web.archive.org/web/20230102030406id_/https://r1.googlevideo.com/videoplayback?video_id=$id&itag=251"
        val result=RecoveredYouTubeVideo(id,Media(video,"video",mimeType="video/webm",audioUrl=audio,audioMimeType="audio/webm"),video,"Wayback Machine",captured)
        val post=result.asPost(now=now)
        assertTrue(RecoveredArchives.valid(post,now))
        assertFalse(RecoveredArchives.valid(post.copy(media=listOf(post.media.single().copy(audioUrl=audio.replace(id,"aaaaaaaaaaa")))),now))
        assertFalse(RecoveredArchives.valid(post.copy(recoveredArchive=post.recoveredArchive!!.copy(capturedAt=captured+1)),now))
    }
}
