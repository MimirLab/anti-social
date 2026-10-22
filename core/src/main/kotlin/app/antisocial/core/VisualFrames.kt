/* AntiSocial, GPL-3.0-or-later. Local image observations, never source authentication. */
package app.antisocial.core

import kotlin.math.abs

/** A tiny, versioned observation of an already decoded image. Captured editions
 * retain it so reopening an offline collection does not depend on disposable cache.
 * It is supporting presentation evidence, not a claim that a source is authentic. */
object VisualFrames {
    private data class Frame(val aspect: Double,val contrast: Double,val horizontal: Long,val vertical: Long,val cells: List<Int>,val detail: ByteArray?=null)
    fun encode(aspect: Double,contrast: Double,horizontal: Long,vertical: Long,cells: List<Int>,detail: ByteArray?=null): String? {
        if(!aspect.isFinite()||aspect !in .05..20.0||!contrast.isFinite()||contrast !in 20.0..128.0||cells.size!=48||cells.any {it !in 0..255})return null
        if(detail!=null&&detail.size!=4096)return null
        val base=listOf(if(detail==null)"f1"else "f2",(aspect*10_000).toInt(),(contrast*100).toInt(),horizontal.toULong().toString(16),vertical.toULong().toString(16),cells.joinToString(""){it.toString(16).padStart(2,'0')}).joinToString("|")
        return if(detail==null)base else base+"|"+java.util.Base64.getEncoder().encodeToString(detail)
    }
    private fun decode(raw: String?,withDetail: Boolean=false): Frame?=runCatching {
        if(raw==null)return null
        val detailed=raw.startsWith("f2|")
        if(if(detailed)raw.length !in 5577..5635 else raw.length !in 112..170)return null
        val boundary=if(detailed)raw.lastIndexOf('|')else raw.length
        if(detailed&&raw.length-boundary-1!=5464)return null
        val p=raw.substring(0,boundary).split('|');if(p.size!=6||p[0]!=(if(detailed)"f2"else "f1")||p[5].length!=96)return null
        val aspect=p[1].toInt()/10_000.0;val contrast=p[2].toInt()/100.0
        if(aspect !in .05..20.0||contrast !in 20.0..128.0||p[3].length !in 1..16||p[4].length !in 1..16)return null
        val detail=if(detailed&&withDetail)java.util.Base64.getDecoder().decode(raw.substring(boundary+1)).takeIf {it.size==4096} ?: return null else null
        Frame(aspect,contrast,p[3].toULong(16).toLong(),p[4].toULong(16).toLong(),p[5].chunked(2).map {it.toInt(16)},detail)
    }.getOrNull()
    fun valid(raw: String?)=raw==null||decode(raw,withDetail=true)!=null

    /** Gallery equivalence has a separate job from finding related posts. A
     * fine local raster tolerates reencoding while preserving changed regions
     * in charts and text cards. It never permits a crop or a different frame. */
    fun sameImage(first: String?,second: String?): Boolean {
        val a=decode(first) ?: return false;val b=decode(second) ?: return false
        if(first?.startsWith("f2|")!=true||second?.startsWith("f2|")!=true) {
            if(!valid(first)||!valid(second))return false
            return same(first,second)
        }
        if(abs(a.aspect-b.aspect)/maxOf(a.aspect,b.aspect)>.025||abs(a.contrast-b.contrast)>12)return false
        val colors=a.cells.zip(b.cells){m,n->abs(m-n)}
        if(colors.max()>52||colors.average()>14)return false
        val x=decode(first,withDetail=true)?.detail ?: return false
        val y=decode(second,withDetail=true)?.detail ?: return false
        var total=0L
        for(tileY in 0 until 8)for(tileX in 0 until 8) {
            var local=0
            for(dy in 0 until 8)for(dx in 0 until 8) {
                val i=(tileY*8+dy)*64+tileX*8+dx
                val difference=abs((x[i].toInt() and 255)-(y[i].toInt() and 255))
                if(difference>48)return false
                local+=difference
            }
            if(local>12*64)return false
            total+=local
        }
        return total<=6*4096
    }
    fun same(first: String?,second: String?,nearbyFrame: Boolean=false): Boolean {
        val a=decode(first) ?: return false;val b=decode(second) ?: return false
        if(abs(a.aspect-b.aspect)/maxOf(a.aspect,b.aspect)>.025||abs(a.contrast-b.contrast)>12)return false
        val h=java.lang.Long.bitCount(a.horizontal xor b.horizontal);val v=java.lang.Long.bitCount(a.vertical xor b.vertical)
        if(if(nearbyFrame)h>12||v>16||h+v>24 else h>6||v>6||h+v>9)return false
        val differences=a.cells.zip(b.cells){x,y->abs(x-y)}
        return differences.max()<=52&&differences.average()<=14
    }
}
