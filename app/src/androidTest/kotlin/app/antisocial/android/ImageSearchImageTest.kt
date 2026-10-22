/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.Media
import app.antisocial.core.NetworkEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Entirely synthetic images; no public image or search provider is contacted. */
@RunWith(AndroidJUnit4::class)
class ImageSearchImageTest {
    @Test fun transparentPixelsAreWhiteAndTheSharedImageIsUnchanged() {
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.TRANSPARENT)}
        try {
            val result=prepareImageSearchBitmap(source)
            val decoded=BitmapFactory.decodeByteArray(result.bytes,0,result.bytes.size)
            try {
                assertEquals("image/jpeg",result.mimeType)
                assertEquals(64,decoded.width);assertEquals(64,decoded.height)
                assertColorNear(Color.WHITE,decoded.getPixel(32,32))
                assertFalse(source.isRecycled);assertEquals(Color.TRANSPARENT,source.getPixel(32,32))
            }finally {decoded.recycle()}
        }finally {source.recycle()}
    }

    @Test fun partialTransparencyCompositesOnWhiteInsteadOfBlack() {
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.argb(128,255,0,0))}
        try {
            val result=prepareImageSearchBitmap(source)
            val decoded=BitmapFactory.decodeByteArray(result.bytes,0,result.bytes.size)
            try {assertColorNear(Color.rgb(255,127,127),decoded.getPixel(32,32))}finally {decoded.recycle()}
            assertFalse(source.isRecycled);assertEquals(128,Color.alpha(source.getPixel(32,32)))
        }finally {source.recycle()}
    }

    @Test fun aLargeNoisyPictureHasBoundedDimensionsAndUploadBytes() {
        val source=Bitmap.createBitmap(2048,1536,Bitmap.Config.ARGB_8888)
        try {
            val random=Random(5183L)
            val row=IntArray(source.width)
            for(y in 0 until source.height) {
                for(x in row.indices)row[x]=random.nextInt() or (0xff shl 24)
                source.setPixels(row,0,row.size,0,y,row.size,1)
            }
            val result=prepareImageSearchBitmap(source)
            assertTrue(result.bytes.isNotEmpty());assertTrue(result.bytes.size<=IMAGE_SEARCH_MAX_BYTES)
            assertEquals(0xff,result.bytes[0].toInt() and 255);assertEquals(0xd8,result.bytes[1].toInt() and 255)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(result.bytes,0,result.bytes.size,bounds)
            assertEquals(1024,bounds.outWidth);assertEquals(768,bounds.outHeight)
            assertFalse(source.isRecycled);assertEquals(2048,source.width)
        }finally {source.recycle()}
    }

    @Test fun anExtremelyNarrowImageStillHasPositiveDimensions() {
        val source=Bitmap.createBitmap(1,2048,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        try {
            val result=prepareImageSearchBitmap(source)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(result.bytes,0,result.bytes.size,bounds)
            assertEquals(1,bounds.outWidth);assertEquals(1024,bounds.outHeight)
        }finally {source.recycle()}
    }

    @Test fun decodingAndRenderingDiscardGpsDeviceAndSourceMetadata() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.cacheDir,"image-search-exif-${UUID.randomUUID()}.jpg")
        val marker="https://private.example/synthetic-source-location"
        val source=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GREEN)}
        try {
            fixture.outputStream().use {assertTrue(source.compress(Bitmap.CompressFormat.JPEG,90,it))}
            ExifInterface(fixture.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE,"37/1,46/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"122/1,25/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"W")
                setAttribute(ExifInterface.TAG_MAKE,"Synthetic private device")
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,marker)
                saveAttributes()
            }
            assertTrue(ExifInterface(fixture.path).getLatLong(FloatArray(2)))
            assertEquals(marker,ExifInterface(fixture.path).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            val decoded=BitmapFactory.decodeFile(fixture.path)
            try {
                val result=prepareImageSearchBitmap(decoded)
                val exif=ExifInterface(ByteArrayInputStream(result.bytes))
                assertFalse(exif.getLatLong(FloatArray(2)))
                assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNull(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                assertFalse(result.bytes.toString(Charsets.ISO_8859_1).contains(marker))
                assertFalse(decoded.isRecycled)
            }finally {decoded.recycle()}
        }finally {source.recycle();fixture.delete()}
    }

    @Test fun ordinaryCachedImagePreparationDoesNotConnectOrAlterTheCache()=runBlocking {prepareCached(saved=false)}

    @Test fun savedImagePreparationDoesNotConnectOrAlterTheProtectedCopy()=runBlocking {prepareCached(saved=true)}

    private suspend fun prepareCached(saved: Boolean) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        val host="image-search-${UUID.randomUUID()}.invalid"
        val url="https://$host/synthetic-image.png";val sourceUrl="https://$host/public-post/1"
        val key=Repository.hash(url)
        val file=File(context.cacheDir,"image-search-fixture-$key.png")
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        val originalBytes=try {ByteArrayOutputStream().use {output->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));output.toByteArray()
        }}finally {bitmap.recycle()}
        val events=CopyOnWriteArrayList<NetworkEvent>()
        val previous=repo.gateway.onNetworkEvent
        try {
            file.writeBytes(originalBytes)
            repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis(),saved=saved))
            repo.gateway.onNetworkEvent={event->if(event.host==host)events+=event;previous?.invoke(event)}
            val shared=repo.images.load(url,sourceUrl,maxEdge=IMAGE_SEARCH_MAX_EDGE)
            val result=prepareImageSearch(repo,Media(url),sourceUrl)
            assertEquals("image/jpeg",result.mimeType);assertTrue(result.bytes.size<=IMAGE_SEARCH_MAX_BYTES)
            assertSame(shared,repo.images.load(url,sourceUrl,maxEdge=IMAGE_SEARCH_MAX_EDGE))
            assertFalse(shared.isRecycled);assertEquals(Color.CYAN,shared.getPixel(32,32))
            assertTrue(events.isEmpty());assertArrayEquals(originalBytes,file.readBytes())
            assertEquals(saved,requireNotNull(repo.dao.media(key)).saved)
            if(saved)assertEquals(file.path,repo.savedMedia(url)?.path)
        }finally {
            repo.gateway.onNetworkEvent=previous
            repo.dao.removeMedia(listOf(key));file.delete()
        }
    }

    private fun assertColorNear(expected: Int,actual: Int) {
        assertEquals(255,Color.alpha(actual))
        assertTrue(kotlin.math.abs(Color.red(expected)-Color.red(actual))<=4)
        assertTrue(kotlin.math.abs(Color.green(expected)-Color.green(actual))<=4)
        assertTrue(kotlin.math.abs(Color.blue(expected)-Color.blue(actual))<=4)
    }
}
