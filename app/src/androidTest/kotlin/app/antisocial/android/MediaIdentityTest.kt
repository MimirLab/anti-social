/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.antisocial.core.NetworkPolicy
import kotlinx.coroutines.runBlocking
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.Media
import app.antisocial.core.Platform
import app.antisocial.core.Post
import app.antisocial.core.VisualFrames
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect

/** Real Android decoders and scaling: no public hosts or extra image fetches are involved. */
@RunWith(AndroidJUnit4::class)
class MediaIdentityTest {
    @Test fun matchingChangesIgnoreUnrelatedImagesButRetainRelevantCopiesAndExpiry()=runBlocking {
        var now=100L;val bank=MediaIdentityStore(ttlMs=10,clock={now});val picture=landscape()
        val url="https://images.example.org/scoped-picture.jpg";val other="https://images.example.net/another-picture.jpg"
        val changes=Channel<Unit>(Channel.UNLIMITED)
        val reader=launch {bank.matchingSnapshots(listOf(post(url))).collect {changes.send(Unit)}}
        suspend fun next() {withTimeout(5_000){changes.receive()}}
        suspend fun quiet() {assertNull("An unrelated observation must not reschedule this matcher",withTimeoutOrNull(200){changes.receive()})}
        try {
            next()
            bank.observe(other,picture);quiet()
            bank.observe(url,picture);next();assertTrue(bank.knows(url))
            now++;bank.observe(url,picture);quiet()
            val changed=landscape().also {Canvas(it).drawRect(0f,0f,it.width*.7f,it.height.toFloat(),Paint().apply {color=Color.RED})}
            try {bank.observe(url,changed);next()}finally {changed.recycle()}
            now+=10;assertFalse(bank.knows(url));next()
            bank.clear();quiet()
        }finally {reader.cancelAndJoin();changes.close();picture.recycle()}
    }

    @Test fun matchingChangesFollowEffectiveAssetAliasesAndConflictsWithoutFetching()=runBlocking {
        val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
        val first="https://scontent-a.xx.fbcdn.net$path?oh=first"
        val alias="https://scontent-b.cdninstagram.com$path?oh=second"
        val conflicting="https://scontent-c.xx.fbcdn.net$path?oh=third"
        val bank=MediaIdentityStore(maxEntries=2);val picture=landscape();val changes=Channel<Unit>(Channel.UNLIMITED)
        val reader=launch {bank.matchingSnapshots(listOf(post(alias))).collect {changes.send(Unit)}}
        suspend fun next() {withTimeout(5_000){changes.receive()}}
        try {
            next();bank.observe(first,picture);next()
            assertFalse(bank.knows(alias));assertNotNull(bank.captured(post(alias)).media.single().visualFingerprint)
            val changed=landscape().also {Canvas(it).drawRect(0f,0f,it.width*.7f,it.height.toFloat(),Paint().apply {color=Color.RED})}
            try {bank.observe(conflicting,changed);next();assertNull(bank.captured(post(alias)).media.single().visualFingerprint)}finally {changed.recycle()}
            // Eviction resolves the earlier ambiguity; the remaining observed
            // copy becomes evidence and must notify the scoped matcher.
            bank.observe("https://images.example.org/unrelated.jpg",picture);next()
            val remaining=bank.captured(post(conflicting)).media.single()
            val recovered=bank.captured(post(alias)).media.single()
            assertNotNull("The edited picture retains its detailed attachment observation",remaining.attachmentFingerprint)
            assertEquals(remaining.attachmentFingerprint,recovered.attachmentFingerprint)
            assertNull("Weak artwork does not become general post identity",recovered.visualFingerprint)
            bank.clear();next();assertNull(bank.captured(post(alias)).media.single().visualFingerprint)
        }finally {reader.cancelAndJoin();changes.close();picture.recycle()}
    }

    @Test fun aMatchingPassCannotMixItsCapturedFieldsWithNewerLiveEvidence()=runBlocking {
        val bank=MediaIdentityStore();val picture=landscape()
        val first=post("https://images.example.org/first.jpg");val second=post("https://images.example.net/second.jpg",Platform.FACEBOOK)
        val snapshots=Channel<MediaIdentitySnapshot>(Channel.UNLIMITED)
        val reader=launch {bank.matchingSnapshots(listOf(first,second)).collect {snapshots.send(it)}}
        suspend fun next()=withTimeout(5_000){snapshots.receive()}
        try {
            assertFalse(next().same(first,second))
            bank.observe(first.media.single().url,picture);assertFalse(next().same(first,second))
            bank.observe(second.media.single().url,picture)
            val before=next();assertTrue(before.same(first,second))
            val oldFields=before.posts
            val changed=landscape().also {Canvas(it).drawRect(0f,0f,it.width*.7f,it.height.toFloat(),Paint().apply {color=Color.RED})}
            try {bank.observe(second.media.single().url,changed)}finally {changed.recycle()}
            val after=next();assertFalse(after.same(first,second));assertFalse(bank.same(first,second))
            assertNotNull(after.posts[1].media.single().attachmentFingerprint)
            assertTrue("The earlier pass retains exactly its captured comparison",before.same(first,second))
            assertEquals(oldFields,before.posts);assertNotEquals(before.posts,after.posts)
            bank.clear();next();assertFalse(bank.same(first,second));assertTrue(before.same(first,second))
        }finally {reader.cancelAndJoin();snapshots.close();picture.recycle()}
    }

    @Test fun refreshedSignedImageUrlsRetainObservedArtworkWithoutTransferringFileValidation() {
        val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
        val first="https://scontent-a.xx.fbcdn.net$path?oh=old&oe=one"
        val fresh="https://scontent-b.cdninstagram.com$path?stp=dst-jpg_e35_p1080x1080_tt6&oh=new&oe=two"
        val x="https://images.example.org/picture.jpg";val picture=landscape();val bank=MediaIdentityStore()
        try {
            bank.observe(first,picture);bank.observe(x,picture)
            val refreshed=post(fresh,Platform.INSTAGRAM)
            assertFalse("Actual reads still inspect the exact new URL",bank.knows(fresh))
            assertEquals(bank.captured(post(first)).media.single().visualFingerprint,bank.captured(refreshed).media.single().visualFingerprint)
            assertTrue(bank.same(post(x),refreshed))
            assertNull("File-specific provenance is never copied",bank.captured(refreshed).media.single().aiMetadata)
            assertNull(bank.captured(post(fresh+"&stp=cp0_dst-jpg_s640x640")).media.single().visualFingerprint)
            assertNull(bank.captured(post(fresh.replace(".cdninstagram.com",".example.org"))).media.single().visualFingerprint)
            val changed=landscape().also {Canvas(it).drawRect(0f,0f,it.width*.7f,it.height.toFloat(),Paint().apply {color=Color.RED})}
            try {
                bank.observe(fresh,changed)
                assertFalse("An actually different decoded copy overrides earlier alias evidence",bank.same(post(x),refreshed))
                assertNull("Conflicting observations block unobserved aliases",bank.captured(post(first.replace("oh=old","oh=third"))).media.single().visualFingerprint)
            }finally {changed.recycle()}
        }finally {picture.recycle()}
    }

    @Test fun artworkAliasEvidenceExpiresAndEvictsWithItsOriginalObservation() {
        val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
        val first="https://scontent-a.xx.fbcdn.net$path?oh=first"
        val alias="https://scontent-b.xx.fbcdn.net$path?oh=second"
        var now=100L;val bank=MediaIdentityStore(maxEntries=1,ttlMs=10,clock={now});val picture=landscape()
        try {
            bank.observe(first,picture);assertNotNull(bank.captured(post(alias)).media.single().visualFingerprint)
            now+=10;assertNull(bank.captured(post(alias)).media.single().visualFingerprint)
            bank.observe(first,picture);bank.observe("https://images.example.org/unrelated.jpg",picture)
            assertNull(bank.captured(post(alias)).media.single().visualFingerprint)
            bank.observe(first,picture);bank.clear();assertNull(bank.captured(post(alias)).media.single().visualFingerprint)
        }finally {picture.recycle()}
    }

    @Test fun aDeclaredVideoPreviewUsesTheVideoSlotWithoutErasingAnActualPhotograph() {
        val clip=post("https://videos.example.org/clip.mp4",Platform.X).copy(media=listOf(Media("https://videos.example.org/clip.mp4","video","https://images.example.org/player.jpg")))
        val p=app.antisocial.core.Provenance(app.antisocial.core.Source(Platform.INSTAGRAM,"rss-bridge","https://reader.example"),1793000000000,"https://reader.example/feed")
        val preview=post("https://images.example.org/another-frame.jpg",Platform.INSTAGRAM).copy(title="▶ A public workshop announcement",provenance=p)
        val inventory=collectedAttachments(listOf(clip,preview))
        assertEquals(2,inventory.size)
        assertEquals(1,collectedMediaGroups(inventory).size)
        assertEquals("Video preview from Instagram",inventory.single {it.videoPreview}.sourceLabel)
        assertEquals(2,collectedMediaGroups(collectedAttachments(listOf(clip,preview.copy(title="A public workshop photograph")))).size)
        assertEquals(2,collectedMediaGroups(collectedAttachments(listOf(clip,preview.copy(body="▶ A literal caption on a photograph")))).size)
        assertEquals(2,collectedMediaGroups(collectedAttachments(listOf(preview,preview.copy(canonicalUrl=preview.canonicalUrl+"2",media=listOf(Media("https://images.example.org/different-frame.jpg")))))).size)
    }
    @Test fun alternatePlatformVideoEditionsShareASlotDespiteDifferentOpeningFrames() {
        val first=post("https://videos.example.org/first.mp4",Platform.X).copy(media=listOf(Media("https://videos.example.org/first.mp4","video","https://images.example.org/musician.jpg")))
        val second=post("https://videos.example.org/second.mp4",Platform.FACEBOOK).copy(media=listOf(Media("https://videos.example.org/second.mp4","video","https://images.example.org/ensemble.jpg")))
        val inventory=collectedAttachments(listOf(first,second))
        assertEquals(2,inventory.size)
        for(input in listOf(inventory,inventory.reversed())) {
            val groups=collectedMediaGroups(input)
            assertEquals(1,groups.size)
            assertEquals(inventory.toSet(),groups.single().editions.toSet())
        }
    }
    @Test fun aSourceWithSeveralVideosRetainsEachDistinctVisualSlot() {
        val a=post("https://videos.example.org/a.mp4",Platform.X).copy(media=listOf(
            Media("https://videos.example.org/a.mp4","video","https://images.example.org/a.jpg"),
            Media("https://videos.example.org/b.mp4","video","https://images.example.org/b.jpg")))
        val b=post("https://videos.example.org/c.mp4",Platform.FACEBOOK).copy(media=listOf(Media("https://videos.example.org/c.mp4","video","https://images.example.org/c.jpg")))
        assertEquals(3,collectedMediaGroups(collectedAttachments(listOf(a,b))).size)
    }
    @Test fun matchingPostersDoNotIdentifyTwoDifferentPlayableRecordings() {
        val preview="https://images.example.org/shared-cover.jpg"
        val a=post("https://video.example.org/first.mp4",Platform.X).copy(media=listOf(Media("https://video.example.org/first.mp4","video",preview)))
        val b=post("https://video.example.org/second.mp4",Platform.FACEBOOK).copy(media=listOf(Media("https://video.example.org/second.mp4","video",preview)))
        assertEquals(2,collectedAttachments(listOf(a,b)).size)
        assertEquals(2,collectedAttachments(listOf(a.copy(media=a.media.map {it.copy(durationMs=90000)}),b.copy(media=b.media.map {it.copy(durationMs=90000)}))).size)
        val inventory=collectedAttachments(listOf(a,b));val covers=collectedMediaGroups(inventory)
        assertEquals("A repeated cover occupies one visual slot",1,covers.size)
        assertEquals(setOf(a.media.single().url,b.media.single().url),covers.single().editions.map {it.media.url}.toSet())
    }
    @Test fun aStillPreviewRetainsItsSourceWithoutClaimingThatItPublishedTheVideo() {
        val picture=landscape();val bank=MediaIdentityStore()
        val preview="https://images.example.org/poster.jpg";val still="https://images.example.net/still.jpg"
        try {
            bank.observe(preview,picture);bank.observe(still,picture)
            val video=post("https://video.example.org/clip.mp4",Platform.X).copy(media=listOf(Media("https://video.example.org/clip.mp4","video",preview)))
            val image=post(still,Platform.INSTAGRAM)
            for(posts in listOf(listOf(video,image),listOf(image,video))) {
                val result=collectedAttachments(posts,bank).single()
                assertEquals("video",result.media.type)
                assertEquals(setOf(Platform.X),result.platforms)
                assertEquals(setOf(Platform.INSTAGRAM),result.previewPlatforms)
                assertEquals(setOf(video.identityKey,image.identityKey),result.originalKeys)
                assertEquals("Video from X · preview from Instagram",result.sourceLabel)
            }
            val other=video.copy(platform=Platform.FACEBOOK,media=video.media.map {it.copy(url="https://another.example.org/different.mp4",durationMs=90_000)})
            val short=video.copy(media=video.media.map {it.copy(durationMs=10_000)})
            assertEquals("An ambiguous still must not choose between different clips",3,collectedAttachments(listOf(image,short,other),bank).size)
        }finally {picture.recycle()}
    }

    @Test fun darkPicturesDeduplicateInsideACollectionWithoutEstablishingPostIdentity() {
        fun moon(shift:Float)=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).also {bitmap->
            val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(3,24,40))
            val paint=Paint().apply {isAntiAlias=true;color=Color.rgb(109,84,53)}
            canvas.drawCircle(300f+shift,140f,31f,paint)
            paint.color=Color.rgb(1,8,11);canvas.drawRect(0f,340f,600f,400f,paint)
            for(i in 0..12)canvas.drawRect(i*47f,300f+i%3*8,8f+i*47,350f,paint)
        }
        val source=moon(0f);val other=moon(150f)
        val small=Bitmap.createScaledBitmap(source,360,240,true)
        val copy=ByteArrayOutputStream().use {out->small.compress(Bitmap.CompressFormat.JPEG,82,out);BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size())}
        val bank=MediaIdentityStore()
        try {
            bank.observe(a,source);bank.observe(b,copy);bank.observe("https://third.invalid/different.jpg",other)
            val first=post(a);val second=post(b,Platform.FACEBOOK)
            assertFalse("A dim picture alone cannot prove that posts match",bank.same(first,second))
            assertNull(bank.captured(first).media.single().visualFingerprint)
            assertNotNull(bank.captured(first).media.single().attachmentFingerprint)
            val attachments=collectedAttachments(listOf(first,second),bank)
            assertEquals(1,attachments.size);assertEquals(setOf(Platform.X,Platform.FACEBOOK),attachments.single().platforms)
            assertEquals(2,collectedAttachments(listOf(first,post("https://third.invalid/different.jpg")),bank).size)
            val heading="THE WORKSHOP SHARED REPAIR EXAMPLES WITH THE COMMUNITY"
            val gallery=bank.captured(first.copy(canonicalUrl="https://x.com/fixture/status/111",publishedAt=1790000000000,body=heading,media=first.media+Media("https://third.invalid/another.jpg")))
            val singleton=bank.captured(second.copy(canonicalUrl="https://www.facebook.com/fixture/posts/222",publishedAt=1790000001000,body=heading))
            assertTrue("Caption-qualified gallery containment can use detailed attachment evidence",app.antisocial.core.CrossPosts.galleryCollection(listOf(gallery,singleton)))
            assertEquals(1,app.antisocial.core.CrossPosts.group(listOf(gallery,singleton),emptyList()).size)
            assertFalse("A blank weak picture cannot attach through a shared logo or background",app.antisocial.core.CrossPosts.galleryCollection(listOf(gallery,singleton.copy(body=""))))
            assertFalse(bank.same(first,second))
            val captured=listOf(first,second).map(bank::captured);bank.clear()
            assertEquals("The same bounded observation works in protected copies",1,collectedAttachments(captured).size)
        }finally {source.recycle();other.recycle();small.recycle();copy.recycle()}
    }

    @Test fun collectedVideoRenditionsAndObservedPosterCopiesHaveOnePlayerAndAllOrigins() {
        val picture=landscape();val bank=MediaIdentityStore()
        val posterA="https://images.example.org/a.jpg";val posterB="https://images.example.net/b.jpg"
        try {
            bank.observe(posterA,picture);bank.observe(posterB,picture)
            val a=post("https://video.twimg.com/ext_tw_video/123456789/vid/720x400/a.mp4").copy(media=listOf(Media("https://video.twimg.com/ext_tw_video/123456789/vid/720x400/a.mp4","video",posterA)))
            val b=post("https://video.twimg.com/ext_tw_video/123456789/vid/360x200/b.mp4",Platform.FACEBOOK).copy(media=listOf(Media("https://video.twimg.com/ext_tw_video/123456789/vid/360x200/b.mp4","video",posterB)))
            val c=post("https://scontent-a.xx.fbcdn.net/v/t42.0-2/987654321.mp4?oe=abc",Platform.INSTAGRAM).copy(media=listOf(Media("https://scontent-a.xx.fbcdn.net/v/t42.0-2/987654321.mp4?oe=abc","video",posterB)))
            val copies=collectedAttachments(listOf(a,b,c),bank)
            assertEquals(2,copies.size);assertEquals(setOf(Platform.X,Platform.FACEBOOK),copies.first().platforms)
            assertEquals(setOf(Platform.INSTAGRAM),copies.last().platforms)
            assertTrue(copies.first().sourceLabel.startsWith("Video from "))
            assertEquals(1,collectedMediaGroups(copies).size)
            val long=c.copy(media=c.media.map {it.copy(durationMs=90_000)})
            val short=a.copy(media=a.media.map {it.copy(durationMs=10_000)})
            assertEquals("Different known running times retain separate players",2,collectedAttachments(listOf(short,long),bank).size)
        }finally {picture.recycle()}
    }

    @Test fun deduplicatedPicturesKeepOnlyTheirActualSourcePlatforms() {
        val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
        val first="https://scontent-a.xx.fbcdn.net$path"
        val copy="https://scontent-b.cdninstagram.com$path?stp=dst-jpg_e35_p1080x1080_tt6"
        val distinct="https://images.example.org/a-different-picture.jpg"
        val x=post(distinct,Platform.X).copy(canonicalUrl="https://x.com/fixture/status/123")
        val fb=post(first,Platform.FACEBOOK).copy(canonicalUrl="https://www.facebook.com/fixture/posts/456")
        val ig=post(copy,Platform.INSTAGRAM).copy(canonicalUrl="https://www.instagram.com/p/AbCdEfGhijk/")
        val result=collectedAttachments(listOf(x,fb,ig))
        assertEquals(2,result.size)
        assertEquals(setOf(Platform.X),result.first().platforms)
        assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),result.last().platforms)
        assertEquals(setOf(fb.identityKey,ig.identityKey),result.last().originalKeys)
        assertEquals("Image from Facebook · Instagram",result.last().sourceLabel)
        assertEquals(setOf(Platform.X,Platform.FACEBOOK),collectedAttachments(listOf(x,fb.copy(media=x.media))).single().platforms)
    }
    @Test fun aRemovedPixelCopyStillConnectsItsCdnAliasWithoutSwallowingContraryEvidence() {
        val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
        val pixels=ByteArray(4096){100};val changed=pixels.clone().apply {this[150]=0}
        val frame=VisualFrames.encode(1.25,40.0,0,0,List(48){100},pixels)!!
        val edit=VisualFrames.encode(1.25,40.0,0,0,List(48){100},changed)!!
        val a=Media("https://images.example.org/one.jpg",visualFingerprint=frame)
        val b=Media("https://scontent-a.xx.fbcdn.net$path",visualFingerprint=frame)
        val c=Media("https://scontent-b.cdninstagram.com$path")
        fun records(vararg media:Media)=media.map {post(it.url).copy(media=listOf(it))}
        assertEquals(listOf(a),collectedMedia(records(a,b,c)))
        assertEquals(listOf(a,c.copy(visualFingerprint=edit)),collectedMedia(records(a,b,c.copy(visualFingerprint=edit))))
        assertTrue(records(a,b,c).last().media.single().visualFingerprint==null)
    }

    @Test fun anIntermediateReencodingCannotBridgeTwoContradictoryPictures() {
        val pixels=ByteArray(4096){100}
        fun frame(value:Int)=VisualFrames.encode(1.25,40.0,0,0,List(48){100},pixels.clone().apply {this[150]=value.toByte()})!!
        val first=Media(a,visualFingerprint=frame(20))
        val second=Media(b,visualFingerprint=frame(100))
        val bridge=Media("https://third.invalid/middle.jpg",visualFingerprint=frame(60))
        assertFalse(VisualFrames.sameImage(first.visualFingerprint,second.visualFingerprint))
        assertTrue(VisualFrames.sameImage(first.visualFingerprint,bridge.visualFingerprint));assertTrue(VisualFrames.sameImage(second.visualFingerprint,bridge.visualFingerprint))
        assertEquals(3,collectedMedia(listOf(first,second,bridge).map {post(it.url).copy(media=listOf(it))}).size)
    }

    @Test fun qualifiedMetaCopiesCollapseWithoutDecodingWhileOtherObjectsAndCropsRemain() {
        val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
        val first="https://scontent-a.xx.fbcdn.net$path"
        val copy="https://scontent-b.cdninstagram.com$path?stp=dst-jpg_e35_p1080x1080_tt6&oh=other"
        val other=copy.replace("234567890","234567891")
        val crop=first+"?stp=dst-jpg_c0.0.640.640a"
        val posts=listOf(first,copy,other,crop).map(::post)
        assertEquals(3,collectedMedia(posts).size)
        assertEquals(listOf(first,other,crop),collectedMedia(posts).map {it.url})
        assertTrue(posts.all {it.media.single().visualFingerprint==null})
    }
    @Test fun detailedDarkAstronomyPictureDeduplicatesAfterReencoding() {
        val source=InstrumentationRegistry.getInstrumentation().context.assets.open("nasa-thats-our-jam.jpg").use(BitmapFactory::decodeStream)
        val small=Bitmap.createScaledBitmap(source,360,268,true)
        val copy=ByteArrayOutputStream().use {out->small.compress(Bitmap.CompressFormat.JPEG,80,out);BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size())}
        try {
            val store=MediaIdentityStore();store.observe(a,source);store.observe(b,copy)
            assertTrue(store.knows(a));assertEquals(1,collectedMedia(listOf(post(a),post(b)),store).size)
        }finally {source.recycle();small.recycle();copy.recycle()}
    }
    private val a="https://one.invalid/photo.jpg?version=one"
    private val b="https://two.invalid/independently-encoded.webp"
    private fun post(url: String,platform: Platform=Platform.X)=Post("fixture",platform,"https://fixture.invalid/post","Fixture author","A substantial shared caption",media=listOf(Media(url)))

    @Test fun reportedWhiteHouseImagesMatchAcrossResizingWithoutCollapsingDifferentPictures() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val names=listOf("whitehouse-image-copy-0.jpg","whitehouse-clemson.jpg","whitehouse-migrant-children.jpg")
        val store=MediaIdentityStore()
        val originals=names.map {name->assets.open(name).use(BitmapFactory::decodeStream)}
        try {
            originals.forEachIndexed {index,source->
                val first=post("https://first.invalid/$index.jpg")
                val second=post("https://other.invalid/$index.webp",Platform.FACEBOOK)
                val resized=Bitmap.createScaledBitmap(source,320,(source.height*320.0/source.width).toInt(),true)
                val encoded=ByteArrayOutputStream().use {out->resized.compress(Bitmap.CompressFormat.JPEG,78,out);out.toByteArray()}
                val decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.size)
                try {
                    store.observe(first.media.single().url,source);store.observe(second.media.single().url,decoded)
                    val fp=store.captured(first).media.single().visualFingerprint!!;val sp=store.captured(second).media.single().visualFingerprint!!
                    val x=java.util.Base64.getDecoder().decode(fp.substringAfterLast('|'));val y=java.util.Base64.getDecoder().decode(sp.substringAfterLast('|'))
                    val differences=x.indices.map {k->kotlin.math.abs((x[k].toInt() and 255)-(y[k].toInt() and 255))}
                    val tiles=(0 until 8).flatMap {ty->(0 until 8).map {tx->(0 until 8).sumOf {dy->(0 until 8).sumOf {dx->differences[(ty*8+dy)*64+tx*8+dx]}}/64.0}}
                    assertEquals("Picture $index: valid=${VisualFrames.valid(fp)}/${VisualFrames.valid(sp)}, mean=${differences.average()}, max=${differences.max()}, patch=${tiles.max()}",1,collectedMedia(listOf(first,second),store).size)
                }finally {resized.recycle();decoded.recycle()}
            }
            val different=originals.indices.map {post("https://first.invalid/$it.jpg")}
            assertEquals("The three reported posts contain three distinct pictures",3,collectedMedia(different,store).size)
            for(i in different.indices)for(j in i+1 until different.size)assertFalse(store.same(different[i],different[j]))
        }finally {originals.forEach {it.recycle()}}
    }

    @Test fun actualInterviewFramesSupportContextCollectionsWithoutBecomingIdenticalPictures() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val first=assets.open("bessent-0.jpg").use {BitmapFactory.decodeStream(it)}
        val second=assets.open("bessent-1.jpg").use {BitmapFactory.decodeStream(it)}
        val store=MediaIdentityStore()
        try {
            store.observe(a,first);store.observe(b,second)
            assertFalse("Nearby frames are not the exact same picture",store.same(post(a),post(b)))
            val before=post(a);val left=store.captured(before);val right=store.captured(post(b))
            assertNull(before.media.single().visualFingerprint)
            assertTrue(VisualFrames.same(left.media.single().visualFingerprint,right.media.single().visualFingerprint,nearbyFrame=true))
            store.clear()
            assertTrue("Captured evidence survives the disposable in-memory bank",VisualFrames.same(left.media.single().visualFingerprint,right.media.single().visualFingerprint,nearbyFrame=true))
        }finally {first.recycle();second.recycle()}
    }

    @Test fun correctedInfographicFiguresAndCropsKeepTheirOwnImages() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val source=assets.open("whitehouse-migrant-children.jpg").use(BitmapFactory::decodeStream)
        val corrected=source.copy(Bitmap.Config.ARGB_8888,true)
        val paint=Paint().apply {isAntiAlias=true;color=Color.rgb(8,10,11)}
        val canvas=Canvas(corrected);canvas.drawRect(870f,340f,920f,400f,paint)
        paint.color=Color.WHITE;paint.textSize=46f;paint.typeface=android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("9",870f,386f,paint)
        val cropped=Bitmap.createBitmap(source,50,32,source.width-100,source.height-64)
        try {
            for(other in listOf(corrected,cropped)) {
                val store=MediaIdentityStore();store.observe(a,source);store.observe(b,other)
                assertEquals("An edited number or a crop is retained even inside an already merged post",2,collectedMedia(listOf(post(a),post(b)),store).size)
            }
        }finally {source.recycle();corrected.recycle();cropped.recycle()}
    }

    @Test fun resizedReencodedPicturesMatchWithoutMutatingTheSharedBitmap() {
        val source=landscape();val before=pixels(source)
        val resized=Bitmap.createScaledBitmap(source,256,160,true)
        val jpeg=ByteArrayOutputStream().use {out->
            assertTrue(resized.compress(Bitmap.CompressFormat.JPEG,83,out))
            BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size())
        }
        val store=MediaIdentityStore()
        try {
            store.observe(a,source);store.observe(b,jpeg)
            assertTrue("Different hosts, resolution and JPEG encoding should preserve the picture",store.same(post(a),post(b,Platform.BLUESKY)))
            assertArrayEquals(before,pixels(source));assertFalse(source.isRecycled)
            assertEquals(512,source.width);assertEquals(320,source.height)
        }finally {source.recycle();resized.recycle();jpeg.recycle()}
    }

    @Test fun differentScenesAndRecoloredPicturesDoNotMatch() {
        val source=landscape();val different=landscape(alternate=true)
        val recolored=source.copy(Bitmap.Config.ARGB_8888,true)
        val colors=pixels(recolored).map {color->Color.rgb(Color.blue(color),Color.red(color),Color.green(color))}.toIntArray()
        recolored.setPixels(colors,0,recolored.width,0,0,recolored.width,recolored.height)
        val store=MediaIdentityStore()
        try {
            store.observe(a,source);store.observe(b,different)
            assertFalse("Two photographs with different subjects must remain separate",store.same(post(a),post(b)))
            store.observe(b,recolored)
            assertFalse("Shape alone cannot establish that colored artwork is the same picture",store.same(post(a),post(b)))
        }finally {source.recycle();different.recycle();recolored.recycle()}
    }
    @Test fun detailedGrayscalePhotographsAreNotRejectedAsSimpleLogos() {
        val color=landscape();val gray=color.copy(Bitmap.Config.ARGB_8888,true)
        val values=pixels(gray).map {c->val v=(Color.red(c)*.299+Color.green(c)*.587+Color.blue(c)*.114).toInt();Color.rgb(v,v,v)}.toIntArray()
        gray.setPixels(values,0,gray.width,0,0,gray.width,gray.height)
        val smaller=Bitmap.createScaledBitmap(gray,256,160,true);val store=MediaIdentityStore()
        try {
            store.observe(a,gray);store.observe(b,smaller)
            assertTrue("A black-and-white photograph is still a full media identity",store.same(post(a),post(b)))
        }finally {color.recycle();gray.recycle();smaller.recycle()}
    }
    @Test fun aColdComparisonBankRestoresOlderCachedPairsBeyondTheFirst128Urls()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=(context as AntiSocialApplication).repo
        repo.awaitReady();val old=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true))
        val prefix="https://cache-identity-${System.nanoTime()}.invalid/"
        val urls=(0..170).map {prefix+it+".jpg"};val file=File(context.cacheDir,"dedup-bank-${System.nanoTime()}.jpg")
        val source=landscape();val keys=urls.takeLast(2).map(Repository::hash)
        try {
            file.outputStream().use {source.compress(Bitmap.CompressFormat.JPEG,90,it)}
            keys.forEach {repo.dao.putMedia(MediaRow(it,file.path,file.length(),System.currentTimeMillis()))}
            repo.images.identities.clear()
            repo.images.restoreCachedIdentities(urls.map(::post))
            assertTrue(repo.images.identities.same(post(urls[169]),post(urls[170])))
            assertNull(repo.availableMedia(urls[0]))
        }finally {
            repo.dao.removeMedia(keys);repo.images.identities.clear();file.delete();source.recycle();repo.gateway.configure(old)
        }
    }

    @Test fun blanksSmallPicturesTransparentArtAndSimpleLogosAreNotIdentityEvidence() {
        val blank=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GRAY)}
        val full=landscape();val tiny=Bitmap.createScaledBitmap(full,32,20,true);full.recycle()
        val transparent=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val logo=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(logo).drawCircle(128f,128f,60f,Paint().apply {color=Color.BLUE;isAntiAlias=true})
        try {
            listOf(blank,tiny,transparent,logo).forEach {bitmap->
                val store=MediaIdentityStore();store.observe(a,bitmap);store.observe(b,bitmap)
                assertFalse("Repeated placeholders or small logos cannot merge posts",store.same(post(a),post(b)))
            }
        }finally {listOf(blank,tiny,transparent,logo).forEach {it.recycle()}}
    }

    @Test fun aspectChangesAndUnobservedOrRewrittenUrlsAreNotMatches() {
        val source=landscape();val stretched=Bitmap.createScaledBitmap(source,256,256,true)
        val store=MediaIdentityStore()
        try {
            store.observe(a,source)
            assertFalse(store.same(post(a),post(b)))
            assertFalse("Queries stay part of the exact observed URL",store.same(post(a),post(a.substringBefore('?'))))
            store.observe(b,stretched)
            assertFalse("Cropped or stretched pictures require separate evidence",store.same(post(a),post(b)))
            store.observe(b,source);assertTrue(store.same(post(a),post(b)))
        }finally {source.recycle();stretched.recycle()}
    }

    @Test fun onlyPostMediaAndExplicitVideoPreviewsContributeNeverAvatars() {
        val source=landscape();val store=MediaIdentityStore()
        try {
            store.observe(a,source);store.observe(b,source)
            val avatarOnly=post(b).copy(media=emptyList(),avatar=b)
            assertFalse(store.same(post(a),avatarOnly))
            val video=post(b).copy(media=listOf(Media("https://two.invalid/clip.mp4","video",preview=b)))
            assertTrue(store.same(post(a),video))
            assertFalse(store.same(post(a),video.copy(media=listOf(Media(b,"video")))))
        }finally {source.recycle()}
    }

    @Test fun observationsExpireAreBoundedAndClearWithARevision() {
        var now=1000L
        val source=landscape();val store=MediaIdentityStore(maxEntries=2,ttlMs=100,clock={now})
        try {
            store.observe(a,source);store.observe(b,source)
            assertTrue(store.same(post(a),post(b)))
            val initial=store.revision.value
            store.observe(b,source);assertEquals("Identical cache hits should not keep regrouping the feed",initial,store.revision.value)
            store.observe("https://third.invalid/photo",source)
            assertFalse("The oldest URL observation should leave the bounded store",store.same(post(a),post(b)))
            assertTrue(store.revision.value>initial)
            store.observe(a,source);assertTrue(store.same(post(a),post("https://third.invalid/photo")))
            now+=100
            assertFalse("Old evidence expires even without another image arriving",store.same(post(a),post("https://third.invalid/photo")))
            store.observe(a,source);store.observe(b,source);assertTrue(store.same(post(a),post(b)))
            val beforeClear=store.revision.value;store.clear()
            assertFalse(store.same(post(a),post(b)));assertTrue(store.revision.value>beforeClear)
        }finally {source.recycle()}
    }

    private fun pixels(bitmap: Bitmap)=IntArray(bitmap.width*bitmap.height).also {bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}

    /** Textured landscape-like fixture with sky, hills, buildings and trees. */
    private fun landscape(alternate: Boolean=false): Bitmap {
        val width=512;val height=320
        val colors=IntArray(width*height)
        for(y in 0 until height)for(x in 0 until width) {
            val u=x.toDouble()/width;val v=y.toDouble()/height
            val texture=((x*13+y*23+(x*y)%31)%19)-9
            val horizon=if(alternate).38+sin(u*16)*.13 else .48+sin(u*9)*.09
            val color=when {
                v<horizon->Color.rgb((95+v*100).toInt(),(145+v*110).toInt(),(225-v*30).toInt())
                else->Color.rgb((70+v*80).toInt(),(110+v*70).toInt(),(35+v*45).toInt())
            }
            colors[y*width+x]=Color.rgb((Color.red(color)+texture).coerceIn(0,255),(Color.green(color)+texture).coerceIn(0,255),(Color.blue(color)+texture).coerceIn(0,255))
        }
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {bitmap->
            bitmap.setPixels(colors,0,width,0,0,width,height)
            val canvas=Canvas(bitmap);val paint=Paint().apply {isAntiAlias=true}
            paint.color=Color.rgb(178,61,38)
            canvas.drawRect(if(alternate)300f else 80f,155f,if(alternate)450f else 210f,275f,paint)
            paint.color=Color.rgb(235,211,146);canvas.drawRect(if(alternate)335f else 110f,180f,if(alternate)380f else 150f,225f,paint)
            paint.color=Color.rgb(28,71,39)
            for(index in 0 until 4)canvas.drawOval((290+index*43).toFloat(),(95+index*17).toFloat(),(330+index*43).toFloat(),(200+index*17).toFloat(),paint)
            paint.color=Color.rgb(237,189,51);canvas.drawCircle(if(alternate)90f else 410f,50f,25f,paint)
        }
    }
}
