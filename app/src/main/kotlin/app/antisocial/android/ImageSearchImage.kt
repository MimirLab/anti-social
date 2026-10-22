/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.antisocial.core.ImageSearchInput
import app.antisocial.core.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal const val IMAGE_SEARCH_MAX_EDGE=1024
internal const val IMAGE_SEARCH_MAX_BYTES=2*1024*1024

/** The selected image alone is submitted, never its URL, attribution or file metadata.
 * ImageLoader retains ownership of its cached bitmap; only our fresh render is recycled.
 */
internal suspend fun prepareImageSearch(repo: Repository,media: Media,source: String): ImageSearchInput {
    require(media.type=="image") {"Choose an image to search"}
    val bitmap=repo.images.load(media.url,source,maxEdge=IMAGE_SEARCH_MAX_EDGE)
    return withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        prepareImageSearchBitmap(bitmap)
    }
}

/** Re-rendering pixels discards EXIF, comments, embedded URLs and other source metadata.
 * Transparent pixels are composited onto white before JPEG encoding. This does not
 * remove information visibly printed in the picture itself.
 */
internal fun prepareImageSearchBitmap(source: Bitmap): ImageSearchInput {
    require(!source.isRecycled&&source.width>0&&source.height>0) {"Image is unavailable"}
    val scale=(IMAGE_SEARCH_MAX_EDGE.toDouble()/maxOf(source.width,source.height)).coerceAtMost(1.0)
    val width=(source.width*scale).roundToInt().coerceAtLeast(1)
    val height=(source.height*scale).roundToInt().coerceAtLeast(1)
    val copy=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
    try {
        val canvas=Canvas(copy)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source,null,Rect(0,0,width,height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        // The first quality normally fits comfortably, even for a noisy 1024px image.
        // Keep the upload bound explicit rather than trusting a codec's compression ratio.
        for(quality in intArrayOf(88,76,64,48,32)) {
            val bytes=ByteArrayOutputStream().use {output->
                check(copy.compress(Bitmap.CompressFormat.JPEG,quality,output)) {"Image could not be prepared"}
                output.toByteArray()
            }
            if(bytes.size<=IMAGE_SEARCH_MAX_BYTES)return ImageSearchInput(bytes,mimeType="image/jpeg")
        }
        error("Image is too large to search")
    }finally {copy.recycle()}
}
