/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.graphics.Bitmap
import app.antisocial.core.Post
import app.antisocial.core.VisualFrames
import app.antisocial.core.ImageAssetIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One comparison pass uses one immutable local observation set. It cannot
 * acquire a new image or borrow evidence learned halfway through that pass. */
internal class MediaIdentitySnapshot(val posts:List<Post>,private val compare:(Post,Post)->Boolean) {
    fun same(a:Post,b:Post)=compare(a,b)
}

/** Local evidence from images the reader has already decoded. This never fetches an image.
 *
 * A close match is supporting evidence for cross-post text matching, not an account identity.
 * Fingerprints cover resize/reencoding, not cropping, watermarks or substantially edited art.
 * Only bounded derived samples are retained; original bitmaps, files and source URLs are unchanged.
 */
class MediaIdentityStore(
    private val maxEntries: Int=2048,
    private val ttlMs: Long=7L*24*60*60*1000,
    private val clock: ()->Long=System::currentTimeMillis
) {
    init {require(maxEntries>0);require(ttlMs>0)}
    private data class Fingerprint(
        val aspect: Double,val horizontal: Long,val vertical: Long,
        val cells: List<Int>,val contrast: Double,val encoded: String,val postMatch:Boolean
    )
    private data class Observation(val fingerprint: Fingerprint,val observedAt: Long,val asset: String?)
    private val observations=LinkedHashMap<String,Observation>()
    private val assetUrls=mutableMapOf<String,MutableSet<String>>()
    private var nextExpiry=Long.MAX_VALUE
    private var lastClock=Long.MIN_VALUE
    private val changes=MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes.asStateFlow()

    /** Decoder-worker use: derive one bounded sample without retaining a URL,
     * file or bitmap in this bank. The caller owns the bitmap lifecycle. */
    internal fun sampledFingerprint(bitmap:Bitmap):String?=fingerprint(bitmap)?.encoded

    /** A global image decode is not necessarily new evidence for this deck.
     * Compare the exact effective fingerprints used by same()/captured(),
     * including alias conflicts and expiry. Observation times alone cannot
     * change a comparison. Source changes start a new caller-owned collection. */
    internal fun matchingSnapshots(posts:List<Post>):Flow<MediaIdentitySnapshot> {
        val urls=posts.flatMap(::images).distinct()
        return revision.map {
            withContext(Dispatchers.Default) {
                // Copy only bounded bank references under its monitor. URL and
                // raster comparison can then run without holding up a visible
                // image's own lookup/observation on another thread.
                val bank=synchronized(observations) {
                    if(expire(clock()))changes.value+=1
                    if(urls.isEmpty()||observations.isEmpty())null
                    else observations.toMap() to assetUrls.mapValues {it.value.toSet()}
                }
                if(bank==null)urls.associateWith {null}
                else urls.associateWith {observedFingerprint(it,bank.first,bank.second)}
            }
        }.distinctUntilChanged().map {evidence->
            withContext(Dispatchers.Default) {
                MediaIdentitySnapshot(posts.map {capturedWith(it,evidence::get)}) {a,b->
                    val first=images(a).mapNotNull(evidence::get);val second=images(b).mapNotNull(evidence::get)
                    first.any {left->second.any {right->matches(left,right)}}
                }
            }
        }
    }

    fun knows(url: String): Boolean=synchronized(observations) {
        if(expire(clock()))changes.value+=1
        url in observations
    }

    /** Enrich copies only. Source records, bitmap bytes and URLs are unchanged. */
    fun captured(post: Post): Post=synchronized(observations) {
        if(expire(clock()))changes.value+=1
        capturedWith(post,::observedFingerprint)
    }
    private fun capturedWith(post:Post,lookup:(String)->Fingerprint?):Post {
        val media=post.media.map {item->
            val url=when(item.type){"image"->item.url;"video"->item.preview;else->null}
            val f=url?.let(lookup)
            if(f==null)item else if(f.postMatch)item.copy(visualFingerprint=f.encoded,attachmentFingerprint=null)
            else item.copy(visualFingerprint=null,attachmentFingerprint=f.encoded)
        }
        return if(media==post.media)post else post.copy(media=media)
    }

    /** Call on the image loader's worker dispatcher; never mutates or recycles [bitmap]. */
    fun observe(url: String,bitmap: Bitmap) {
        if(url.isBlank()||url.length>8192)return
        val fingerprint=runCatching {fingerprint(bitmap)}.getOrNull()
        synchronized(observations) {
            val now=clock()
            var changed=expire(now)
            val previous=observations.remove(url)
            forgetAsset(url,previous)
            if(fingerprint!=null) {
                val asset=ImageAssetIdentity.key(url)
                observations[url]=Observation(fingerprint,now,asset)
                if(asset!=null)assetUrls.getOrPut(asset){linkedSetOf()}.add(url)
                nextExpiry=minOf(nextExpiry,now+ttlMs)
                changed=changed||previous?.fingerprint!=fingerprint
            }else changed=changed||previous!=null
            while(observations.size>maxEntries) {
                observations.entries.iterator().apply {val old=next();forgetAsset(old.key,old.value);remove()};changed=true
            }
            if(changed)changes.value+=1
        }
    }

    fun same(a: Post,b: Post): Boolean=synchronized(observations) {
        if(expire(clock()))changes.value+=1
        val first=images(a).mapNotNull(::observedFingerprint)
        val second=images(b).mapNotNull(::observedFingerprint)
        first.any {left->second.any {right->matches(left,right)}}
    }

    fun clear()=synchronized(observations) {
        if(observations.isNotEmpty()) {observations.clear();assetUrls.clear();nextExpiry=Long.MAX_VALUE;changes.value+=1}
    }

    /** A refreshed signed URL does not erase already observed artwork. Use only
     * the existing qualified CDN object identity; contrary decoded copies block
     * this fallback. No cached bytes, file hashes or credentials are transferred.
     * knows() remains exact so an actual read still observes the refreshed file. */
    private fun observedFingerprint(url:String)=observedFingerprint(url,observations,assetUrls)
    private fun observedFingerprint(url:String,known:Map<String,Observation>,aliases:Map<String,Set<String>>):Fingerprint? {
        known[url]?.let {return it.fingerprint}
        val asset=ImageAssetIdentity.key(url) ?: return null
        val copies=aliases[asset].orEmpty().mapNotNull {known[it]?.fingerprint}
        val first=copies.firstOrNull() ?: return null
        return first.takeIf {copies.all {other->other===first||VisualFrames.sameImage(first.encoded,other.encoded)}}
    }
    private fun forgetAsset(url:String,observation:Observation?) {
        val asset=observation?.asset ?: return
        assetUrls[asset]?.let {urls->urls.remove(url);if(urls.isEmpty())assetUrls.remove(asset)}
    }

    private fun expire(now: Long): Boolean {
        // Grouping compares many pairs. Sweep once per expiry, not once per post pair.
        if(now>=lastClock&&now<nextExpiry) {lastClock=now;return false}
        lastClock=now;nextExpiry=Long.MAX_VALUE
        val iterator=observations.entries.iterator();var changed=false
        while(iterator.hasNext()) {
            val entry=iterator.next();val observation=entry.value
            val age=now-observation.observedAt
            if(age<0||age>=ttlMs) {forgetAsset(entry.key,observation);iterator.remove();changed=true}
            else nextExpiry=minOf(nextExpiry,observation.observedAt+ttlMs)
        }
        return changed
    }

    /** Avatars are intentionally excluded: a shared avatar does not identify a shared post. */
    private fun images(post: Post)=post.media.mapNotNull {media->
        when(media.type.lowercase()) {"image"->media.url;"video"->media.preview;else->null}
    }

    private fun matches(a: Fingerprint,b: Fingerprint): Boolean {
        if(!a.postMatch||!b.postMatch)return false
        if(abs(a.aspect-b.aspect)/maxOf(a.aspect,b.aspect)>.025)return false
        val horizontal=java.lang.Long.bitCount(a.horizontal xor b.horizontal)
        val vertical=java.lang.Long.bitCount(a.vertical xor b.vertical)
        if(horizontal>6||vertical>6||horizontal+vertical>9)return VisualFrames.sameImage(a.encoded,b.encoded)
        if(abs(a.contrast-b.contrast)>12)return false
        // Preserve color and its location: a repeated outline or gradient alone is insufficient.
        var total=0L;var worst=0
        a.cells.indices.forEach {index->
            val difference=abs(a.cells[index]-b.cells[index])
            total+=difference;worst=maxOf(worst,difference)
        }
        return worst<=52&&total.toDouble()/a.cells.size<=14
    }

    private fun fingerprint(bitmap: Bitmap): Fingerprint? {
        if(bitmap.isRecycled||bitmap.width<64||bitmap.height<64)return null
        val pixels=sample(bitmap,32,32)
        val histogram=IntArray(512)
        val grayHistogram=IntArray(16)
        var sum=0.0;var squares=0.0;var opaque=0;var edges=0
        val luminance=IntArray(pixels.size)
        pixels.forEachIndexed {index,color->
            if(color ushr 24>=240)opaque++
            val r=color shr 16 and 255;val g=color shr 8 and 255;val b=color and 255
            histogram[((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)]++
            val value=luma(color);luminance[index]=value;sum+=value;squares+=value*value
            grayHistogram[value/16]++
        }
        // Transparent artwork and near-solid placeholders/logos are weak identity evidence.
        if(opaque<pixels.size*.98)return null
        val mean=sum/pixels.size
        val contrast=sqrt((squares/pixels.size-mean*mean).coerceAtLeast(0.0))
        var entropy=0.0
        histogram.filter {it>0}.forEach {count->val p=count.toDouble()/pixels.size;entropy-=p*ln(p)/ln(2.0)}
        // RGB's coarse histogram has only eight possible bins for a grayscale
        // photograph. Do not mistake detailed black-and-white media for a logo.
        // Require broad luminance detail as an alternative, with all opacity,
        // contrast, dominant-color and spatial edge guards still in force.
        var grayEntropy=0.0
        grayHistogram.filter {it>0}.forEach {count->val p=count.toDouble()/pixels.size;grayEntropy-=p*ln(p)/ln(2.0)}
        val detailedGray=grayEntropy>=2.3&&grayHistogram.count {it>=3}>=6
        for(y in 0 until 32)for(x in 0 until 32) {
            val index=y*32+x
            if(x>0&&abs(luminance[index]-luminance[index-1])>=8)edges++
            if(y>0&&abs(luminance[index]-luminance[index-32])>=8)edges++
        }
        // Detailed astronomy photographs can be mostly black. Admit that
        // narrow case through spatial detail and color diversity, while simple
        // dark logos, placeholders and flat backgrounds remain ineligible.
        val detailedDark=mean<65&&contrast>=25&&histogram.maxOrNull()!!<=pixels.size*.8&&
            histogram.count {it>=3}>=16&&edges>=400
        val postMatch=contrast>=20&&edges>=160&&
            (histogram.maxOrNull()!!<=pixels.size*.62||detailedDark)&&
            (entropy>=2.5&&histogram.count {it>=3}>=12||detailedGray||detailedDark)
        val cells=ArrayList<Int>(48)
        for(cellY in 0 until 4)for(cellX in 0 until 4) {
            var red=0;var green=0;var blue=0
            for(y in cellY*8 until cellY*8+8)for(x in cellX*8 until cellX*8+8) {
                val color=pixels[y*32+x];red+=color shr 16 and 255;green+=color shr 8 and 255;blue+=color and 255
            }
            cells+=red/64;cells+=green/64;cells+=blue/64
        }
        // Derive both hashes from the same anti-aliased thumbnail. A single
        // full-size -> 9px bilinear resize samples isolated texture rather than
        // averaging it; JPEG or a source thumbnail then flips otherwise-flat
        // comparisons even when the picture's geometry and colors are intact.
        val thumbnail=Bitmap.createBitmap(pixels,32,32,Bitmap.Config.ARGB_8888)
        val horizontal: IntArray;val vertical: IntArray
        try {horizontal=sample(thumbnail,9,8);vertical=sample(thumbnail,8,9)}finally {thumbnail.recycle()}
        var hashX=0L;var hashY=0L
        for(y in 0 until 8)for(x in 0 until 8) {
            val bit=1L shl (y*8+x)
            if(luma(horizontal[y*9+x])>luma(horizontal[y*9+x+1]))hashX=hashX or bit
            if(luma(vertical[y*8+x])>luma(vertical[(y+1)*8+x]))hashY=hashY or bit
        }
        val aspect=bitmap.width.toDouble()/bitmap.height
        val detail=areaLuma(bitmap)
        // Low-contrast photographs and graphic cards are useful attachment
        // comparisons even when they cannot corroborate a post relationship.
        // Keep this observation separate from general post identity. A bounded
        // source-qualified literal-passage route can use it as supporting
        // artwork; that does not authenticate or erase either source's media.
        val encoded=VisualFrames.encode(aspect,contrast.coerceAtLeast(20.0),hashX,hashY,cells,detail) ?: return null
        return Fingerprint(aspect,hashX,hashY,cells,contrast,encoded,postMatch)
    }

    private fun sample(bitmap: Bitmap,width: Int,height: Int): IntArray {
        var scaled=bitmap
        try {
            // Android's bilinear filter covers only neighboring input pixels.
            // Successive halves provide the prefilter needed for large shrinks.
            while(scaled.width>width*2||scaled.height>height*2) {
                val next=Bitmap.createScaledBitmap(scaled,maxOf(width,scaled.width/2),maxOf(height,scaled.height/2),true)
                if(scaled!==bitmap)scaled.recycle()
                scaled=next
            }
            if(scaled.width!=width||scaled.height!=height) {
                val next=Bitmap.createScaledBitmap(scaled,width,height,true)
                if(scaled!==bitmap)scaled.recycle()
                scaled=next
            }
            return IntArray(width*height).also {scaled.getPixels(it,0,width,0,0,width,height)}
        }finally {if(scaled!==bitmap)scaled.recycle()}
    }
    /** True area averages keep thin lettering stable across unrelated resize
     * chains. Read one source row at a time; no second full-size raster exists. */
    private fun areaLuma(bitmap: Bitmap): ByteArray {
        val out=ByteArray(4096);val row=IntArray(bitmap.width)
        val scaleX=bitmap.width/64.0;val scaleY=bitmap.height/64.0
        for(y in 0 until 64) {
            val sums=DoubleArray(64);val top=y*scaleY;val bottom=(y+1)*scaleY
            for(sy in floor(top).toInt() until minOf(bitmap.height,ceil(bottom).toInt())) {
                bitmap.getPixels(row,0,bitmap.width,0,sy,bitmap.width,1)
                val wy=minOf(bottom,sy+1.0)-maxOf(top,sy.toDouble())
                for(x in 0 until 64) {
                    val left=x*scaleX;val right=(x+1)*scaleX
                    for(sx in floor(left).toInt() until minOf(bitmap.width,ceil(right).toInt())) {
                        val wx=minOf(right,sx+1.0)-maxOf(left,sx.toDouble())
                        sums[x]+=luma(row[sx])*wx*wy
                    }
                }
            }
            for(x in 0 until 64)out[y*64+x]=(sums[x]/(scaleX*scaleY)).roundToInt().coerceIn(0,255).toByte()
        }
        return out
    }
    private fun luma(color: Int): Int=((color shr 16 and 255)*77+(color shr 8 and 255)*150+(color and 255)*29) shr 8
}
