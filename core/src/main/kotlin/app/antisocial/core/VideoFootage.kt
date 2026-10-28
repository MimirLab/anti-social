/* anti social, GPL-3.0-or-later. Sampled scenes are not recording identity. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Version 1 positions are the odd eighths of the measured rendition duration.
 * Missing positions stay missing. An observation never substitutes for audio,
 * complete-file equality, a source edition or cryptographic provenance. */
data class SampledVideoScene(val position:Int,val fingerprint:String)
data class VideoFootageEvidence(val renditionUrl:String,val durationMs:Long,val sampledAt:Long,
    val scenes:List<SampledVideoScene>,val version:Int=1)
data class SharedVideoFootage internal constructor(val scenes:Int,val weakestCorrelation:Double)

/** Pure bounded comparison for already acquired samples. Call off the UI thread.
 * A crop-aware normalized correlation supports portrait/landscape editions.
 * Callers must keep every source caption and distinct playable recording.
 * The independent implementation uses the standard normalized correlation
 * coefficient; no computer-vision runtime or model is loaded.
 */
object VideoFootage {
    const val POSITIONS=4
    const val MAX_DURATION_MS=30L*60*1000
    private const val WIDTH=8
    private const val HEIGHT=14
    private const val CELLS=WIDTH*HEIGHT
    private const val MIN_VARIANCE=CELLS*64.0
    private const val MIN_CORRELATION=.92
    internal fun qualified(proof:SharedVideoFootage)=proof.scenes in 3..POSITIONS&&proof.weakestCorrelation in MIN_CORRELATION..1.0

    fun requestedAt(durationMs:Long,position:Int):Long {
        require(durationMs in 2_000..MAX_DURATION_MS&&position in 0 until POSITIONS)
        return durationMs*(position*2+1)/8
    }
    fun valid(value:VideoFootageEvidence):Boolean=runCatching {
        value.version==1&&value.sampledAt>0&&value.durationMs in 2_000..MAX_DURATION_MS&&value.renditionUrl.length<=8192&&
            RssSources.feedUrl(value.renditionUrl)!=null&&value.renditionUrl.toHttpUrlOrNull()?.isHttps==true&&
            value.scenes.size in 1..POSITIONS&&value.scenes.map {it.position}.let {it==it.distinct().sorted()&&it.all {p->p in 0 until POSITIONS}}&&
            value.scenes.all {scene->scene.fingerprint.startsWith("f2|")&&VisualFrames.sampledRaster(scene.fingerprint)?.aspect?.let {it in .2..5.0}==true}
    }.getOrDefault(false)

    /** A rendition observation belongs only to its exact URL or a qualified
     * same-provider clip. It is never inherited through a thumbnail. */
    fun valid(media:Media):Boolean {
        if(!VideoAssetIdentity.validSampling(media))return false
        val evidence=media.videoFootage ?: return true
        if(media.type!="video"||!valid(evidence))return false
        return media.url==evidence.renditionUrl||VideoAssetIdentity.key(media.url)?.let {it==VideoAssetIdentity.key(evidence.renditionUrl)}==true
    }
    fun related(first:Media,second:Media,checkpoint:()->Unit={}):SharedVideoFootage? =
        if(valid(first)&&valid(second))shared(first.videoFootage,second.videoFootage,checkpoint)else null
    fun related(first:Post,second:Post,checkpoint:()->Unit={}):SharedVideoFootage? =
        first.media.asSequence().filter {it.type=="video"&&it.videoFootage!=null}.flatMap {left->
            second.media.asSequence().filter {it.type=="video"&&it.videoFootage!=null}.mapNotNull {right->related(left,right,checkpoint)}
        }.firstOrNull()

    /** Replayable content evidence for an explicit saved collection. This does
     * not assert common account ownership. Unmeasured editions still need a
     * literal caption relation to a measured one, and every measured recording
     * must match every other measured recording. */
    fun collection(posts:List<Post>,checkpoint:()->Unit={}):Boolean=CrossPostFusion.withPrepared {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size||
            posts.any {it.preview||it.parentId!=null||it.recoveredArchive!=null||it.publishedAt?.let {t->t>0}!=true})return@withPrepared false
        if(posts.maxOf {requireNotNull(it.publishedAt)}-posts.minOf {requireNotNull(it.publishedAt)}>6*60*60_000L)return@withPrepared false
        val measured=posts.mapNotNull {post->
            val clips=post.media.filter {it.type=="video"}
            if(clips.map {VideoAssetIdentity.key(it.url) ?: it.url}.distinct().size>1)return@withPrepared false
            clips.firstOrNull {it.videoFootage!=null&&valid(it)}?.let {post to it}
        }
        if(measured.size !in 2..8||measured.map {it.first.platform}.distinct().size<2)return@withPrepared false
        for(i in measured.indices)for(j in i+1 until measured.size) {
            checkpoint();if(related(measured[i].second,measured[j].second,checkpoint)==null)return@withPrepared false
        }
        posts.all {post->
            checkpoint()
            measured.any {it.first==post}||measured.any {(known,_)->CrossPostFusion.compose(listOf(post,known))!=null}
        }
    }

    fun shared(first:VideoFootageEvidence?,second:VideoFootageEvidence?,checkpoint:()->Unit={}):SharedVideoFootage?=CrossPostFusion.withPrepared {
        if(first==null||second==null||!valid(first)||!valid(second))return@withPrepared null
        if(abs(first.durationMs-second.durationMs)>maxOf(750L,maxOf(first.durationMs,second.durationMs)/100))return@withPrepared null
        val right=second.scenes.associateBy {it.position}
        val accepted=ArrayList<Pair<SampledVideoScene,SampledVideoScene>>()
        var weakest=1.0
        for(left in first.scenes) {
            checkpoint()
            val other=right[left.position] ?: continue
            val score=sceneScore(left.fingerprint,other.fingerprint,checkpoint) ?: continue
            if(score<MIN_CORRELATION)continue
            // A repeated intro, logo or static background does not provide
            // several independent scenes merely by being sampled repeatedly.
            if(accepted.any {(a,b)->VisualFrames.sameImage(a.fingerprint,left.fingerprint)||VisualFrames.sameImage(b.fingerprint,other.fingerprint)})continue
            accepted+=left to other;weakest=minOf(weakest,score)
        }
        if(accepted.size>=3)SharedVideoFootage(accepted.size,weakest)else null
    }

    private class Raster(val aspect:Double,pixels:ByteArray) {
        private val integral=IntArray(65*65)
        val projected=DoubleArray(CELLS)
        val variance:Double
        init {
            for(y in 0 until 64) {
                var row=0
                for(x in 0 until 64){row+=pixels[y*64+x].toInt()and 255;integral[(y+1)*65+x+1]=integral[y*65+x+1]+row}
            }
            project(0,0,64,64,projected)
            val mean=projected.sum()/CELLS
            var sum=0.0
            for(i in projected.indices){projected[i]-=mean;sum+=projected[i]*projected[i]}
            variance=sum
        }
        fun project(x:Int,y:Int,width:Int,height:Int,result:DoubleArray) {
            var i=0
            for(row in 0 until HEIGHT) {
                val top=y+row*height/HEIGHT;val bottom=y+(row+1)*height/HEIGHT
                for(column in 0 until WIDTH) {
                    val left=x+column*width/WIDTH;val right=x+(column+1)*width/WIDTH
                    val sum=integral[bottom*65+right]-integral[top*65+right]-integral[bottom*65+left]+integral[top*65+left]
                    result[i++]=sum.toDouble()/((right-left)*(bottom-top))
                }
            }
        }
    }
    internal fun sceneScore(first:String,second:String,checkpoint:()->Unit={}):Double? {
        val a=VisualFrames.sampledRaster(first) ?: return null
        val b=VisualFrames.sampledRaster(second) ?: return null
        if(a.aspect !in .2..5.0||b.aspect !in .2..5.0)return null
        val source=Raster(a.aspect,requireNotNull(a.detail));val target=Raster(b.aspect,requireNotNull(b.detail))
        if(target.variance<MIN_VARIANCE||source.variance<MIN_VARIANCE)return null
        if(VisualFrames.sameImage(first,second))return 1.0
        return maxOf(cropScore(source,target,checkpoint),cropScore(target,source,checkpoint))
    }
    private fun cropScore(source:Raster,target:Raster,checkpoint:()->Unit):Double {
        val ratio=target.aspect/source.aspect
        val projected=DoubleArray(CELLS)
        var best=-1.0;var bestX=0;var bestY=0;var bestExtent=64
        fun windowWidth(extent:Int)=if(ratio<=1)(extent*ratio).roundToInt()else extent
        fun windowHeight(extent:Int)=if(ratio<=1)extent else (extent/ratio).roundToInt()
        fun compare(x:Int,y:Int,extent:Int) {
            val width=windowWidth(extent);val height=windowHeight(extent)
            if(width<WIDTH||height<HEIGHT)return
            if(x<0||y<0||x+width>64||y+height>64)return
            source.project(x,y,width,height,projected)
            var sum=0.0;var squares=0.0;var dot=0.0
            for(i in projected.indices){val v=projected[i];sum+=v;squares+=v*v;dot+=v*target.projected[i]}
            val variance=squares-sum*sum/CELLS
            if(variance<MIN_VARIANCE)return
            val score=(dot/sqrt(variance*target.variance)).coerceIn(-1.0,1.0)
            if(score>best){best=score;bestX=x;bestY=y;bestExtent=extent}
        }
        for(extent in 32..64 step 4) {
            checkpoint()
            val width=windowWidth(extent);val height=windowHeight(extent)
            if(width<WIDTH||height<HEIGHT)continue
            for(y in 0..64-height step 2)for(x in 0..64-width step 2)compare(x,y,extent)
        }
        val x=bestX;val y=bestY;val extent=bestExtent
        for(e in maxOf(32,extent-3)..minOf(64,extent+3)) {
            checkpoint()
            for(yy in y-2..y+2)for(xx in x-2..x+2)compare(xx,yy,e)
        }
        return best
    }
}
