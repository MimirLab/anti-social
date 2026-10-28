/* anti social, GPL-3.0-or-later. Independent, bounded container hints.
 * Format references: webmproject.org/docs/container/ and ISO BMFF file-type
 * boxes as documented by loc.gov/preservation/digital/formats/fdd/fdd000155.shtml.
 * A header selects a reader; it authenticates neither the file nor its contents. */
package app.antisocial.core

internal object VideoContainerHints {
    private const val LIMIT=4096
    private val videoBrands=setOf("isom","iso2","iso3","iso4","iso5","iso6","iso8","iso9","mp41","mp42","avc1","hvc1","hev1","av01","M4V ","M4VP","qt  ","MSNV","dash","dby1")
    private val otherBrands=setOf("avif","avis","heic","heix","hevc","hevx","heim","heis","hevm","hevs","mif1","msf1","miaf","M4A ","M4B ","M4P ")
    fun sniff(bytes:ByteArray):String? {
        val end=minOf(LIMIT,bytes.size)
        return if(mp4(bytes,end))"video/mp4"else if(webm(bytes,end))"video/webm"else null
    }
    private fun uint(bytes:ByteArray,at:Int):Long {
        var value=0L
        for(i in at until at+4)value=(value shl 8)or(bytes[i].toLong()and 255)
        return value
    }
    private fun tag(bytes:ByteArray,at:Int)=String(bytes,at,4,Charsets.US_ASCII)
    private fun mp4(bytes:ByteArray,end:Int):Boolean {
        var at=0
        repeat(32) {
            if(at+8>end)return false
            var size=uint(bytes,at);var header=8
            if(size==1L) {
                if(at+16>end||uint(bytes,at+8)!=0L)return false
                size=uint(bytes,at+12);header=16
            }
            if(size<header||size>end-at)return false
            val next=at+size.toInt()
            if(tag(bytes,at+4)=="ftyp") {
                if(size<header+8||(size-header-8)%4!=0L)return false
                val major=tag(bytes,at+header)
                // AVIF/HEIF and audio-only declarations can share the same
                // container family and compatibility brands. Keep them out.
                if(major in otherBrands)return false
                if(major in videoBrands)return true
                for(brand in at+header+8 until next step 4)if(tag(bytes,brand) in videoBrands)return true
                return false
            }
            at=next
        }
        return false
    }
    private data class Integer(val value:Long,val next:Int)
    private fun integer(bytes:ByteArray,at:Int,end:Int,maximum:Int,strip:Boolean):Integer? {
        if(at>=end)return null
        val first=bytes[at].toInt()and 255
        var marker=128;var width=1
        while(marker>0&&first and marker==0){marker=marker ushr 1;width++}
        if(marker==0||width>maximum||at+width>end)return null
        var value=(if(strip)first and(marker-1)else first).toLong()
        for(i in at+1 until at+width)value=(value shl 8)or(bytes[i].toLong()and 255)
        if(strip&&value==(1L shl(7*width))-1)return null
        return Integer(value,at+width)
    }
    private fun webm(bytes:ByteArray,end:Int):Boolean {
        if(end<5||uint(bytes,0)!=0x1a45dfa3L)return false
        val header=integer(bytes,4,end,8,true) ?: return false
        if(header.value>end-header.next)return false
        val stop=header.next+header.value.toInt();var at=header.next;var declared=false;var count=0
        while(at<stop) {
            if(++count>32)return false
            val id=integer(bytes,at,stop,4,false) ?: return false
            val size=integer(bytes,id.next,stop,8,true) ?: return false
            if(size.value>stop-size.next)return false
            val next=size.next+size.value.toInt()
            if(id.value==0x4282L) {
                if(declared||size.value!=4L||tag(bytes,size.next)!="webm")return false
                declared=true
            }
            at=next
        }
        return declared
    }
}
