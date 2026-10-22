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

/** Real Android decoders and scaling: no public hosts or extra image fetches are involved. */
@RunWith(AndroidJUnit4::class)
class MediaIdentityTest {
    private val a="https://one.invalid/photo.jpg?version=one"
    private val b="https://two.invalid/independently-encoded.webp"
    private fun post(url: String,platform: Platform=Platform.X)=Post("fixture",platform,"https://fixture.invalid/post","Fixture author","A substantial shared caption",media=listOf(Media(url)))

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
