/* AntiSocial, GPL-3.0-or-later. Local image observations, never source authentication. */
package app.antisocial.core

import kotlin.math.abs

/** A tiny, versioned observation of an already decoded image. Captured editions
 * retain it so reopening an offline collection does not depend on disposable cache.
 * It is supporting presentation evidence, not a claim that a source is authentic. */
object VisualFrames {
    private data class Frame(val aspect: Double,val contrast: Double,val horizontal: Long,val vertical: Long,val cells: List<Int>)
    fun encode(aspect: Double,contrast: Double,horizontal: Long,vertical: Long,cells: List<Int>): String? {
        if(!aspect.isFinite()||aspect !in .05..20.0||!contrast.isFinite()||contrast !in 20.0..128.0||cells.size!=48||cells.any {it !in 0..255})return null
        return listOf("f1",(aspect*10_000).toInt(),(contrast*100).toInt(),horizontal.toULong().toString(16),vertical.toULong().toString(16),cells.joinToString(""){it.toString(16).padStart(2,'0')}).joinToString("|")
    }
    private fun decode(raw: String?): Frame?=runCatching {
        if(raw==null||raw.length !in 112..170)return null
        val p=raw.split('|');if(p.size!=6||p[0]!="f1"||p[5].length!=96)return null
        val aspect=p[1].toInt()/10_000.0;val contrast=p[2].toInt()/100.0
        if(aspect !in .05..20.0||contrast !in 20.0..128.0||p[3].length !in 1..16||p[4].length !in 1..16)return null
        Frame(aspect,contrast,p[3].toULong(16).toLong(),p[4].toULong(16).toLong(),p[5].chunked(2).map {it.toInt(16)})
    }.getOrNull()
    fun valid(raw: String?)=raw==null||decode(raw)!=null
    fun same(first: String?,second: String?,nearbyFrame: Boolean=false): Boolean {
        val a=decode(first) ?: return false;val b=decode(second) ?: return false
        if(abs(a.aspect-b.aspect)/maxOf(a.aspect,b.aspect)>.025||abs(a.contrast-b.contrast)>12)return false
        val h=java.lang.Long.bitCount(a.horizontal xor b.horizontal);val v=java.lang.Long.bitCount(a.vertical xor b.vertical)
        if(if(nearbyFrame)h>12||v>16||h+v>24 else h>6||v>6||h+v>9)return false
        val differences=a.cells.zip(b.cells){x,y->abs(x-y)}
        return differences.max()<=52&&differences.average()<=14
    }
}
