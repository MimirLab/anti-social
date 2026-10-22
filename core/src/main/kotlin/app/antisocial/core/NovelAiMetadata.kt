/* anti social, GPL-3.0-or-later.
 * Format and verification mechanics adapted from NovelAI/novelai-image-metadata (MIT).
 * Copyright (c) 2023 NovelAI. See licenses/media-info-provenance.md. No FEC repair or file mutation. */
package app.antisocial.core

import com.google.gson.*
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.*
import java.util.Base64
import java.util.zip.*

data class NovelAiInspection(val recipes:List<GenerationRecipe> = emptyList(),val limited:Boolean=false)

/** Exact, non-premultiplied PNG samples. Pixel decoding happens only on explicit inspection.
 * Interlaced/high-bit-depth PNG and WebP are left unsupported rather than color-converted.
 */
object NovelAiMetadata {
    const val MAX_PIXELS=4*1024*1024
    const val MAX_ENCODED_BYTES=12*1024*1024
    private const val KEY="Y2JcQAOhLwzwSDUJPNgL04nS0Tbqm7cSRc4xk0vRMic="
    fun inspect(bytes:ByteArray,fields:Map<String,String>):NovelAiInspection {
        if(bytes.size>MAX_ENCODED_BYTES)return NovelAiInspection(limited=true)
        return try {
            val image=decode(bytes) ?: return NovelAiInspection()
            val candidates=mutableListOf<Pair<Map<String,String>,String>>()
            if(fields["Software"]=="NovelAI")candidates+=fields to "File metadata"
            alpha(image)?.let {candidates+=it to "Hidden alpha-channel record"}
            val recipes=candidates.mapNotNull {(record,carrier)->
                val recipe=GenerationRecipes.novel(record,carrier) ?: return@mapNotNull null
                val comment=record["Comment"]?.let(GenerationRecipes::obj) ?: return@mapNotNull null
                val signed=comment.remove("signed_hash")
                val state=if(signed==null)RecipeSignature.UNSIGNED else {
                    val signature=runCatching {Base64.getDecoder().decode(signed.asString)}.getOrNull()
                    if(signature==null||signature.size!=64)RecipeSignature.INVALID else {
                        val canonical=pythonJson(comment).toByteArray(Charsets.UTF_8)
                        val valid=verify(image.rgb(),canonical,signature)||image.channels==4&&verify(image.maskedRgba(),canonical,signature)
                        if(valid)RecipeSignature.VALID else RecipeSignature.INVALID
                    }
                }
                recipe.copy(signature=state)
            }.distinct().take(2)
            NovelAiInspection(recipes)
        }catch(_:Limit){NovelAiInspection(limited=true)}catch(_:Exception){NovelAiInspection()}
    }
    private data class Pixels(val width:Int,val height:Int,val channels:Int,val samples:ByteArray) {
        fun rgb()=if(channels==3)samples else ByteArray(width*height*3).also {out->for(i in 0 until width*height)System.arraycopy(samples,i*4,out,i*3,3)}
        fun maskedRgba()=samples.copyOf().also {a->for(i in 3 until a.size step 4)a[i]=(a[i].toInt() and 254).toByte()}
    }
    private class Limit:Exception()
    private fun decode(b:ByteArray):Pixels? {
        if(b.size<33||!b.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))return null
        fun int(at:Int):Long=(0..3).fold(0L){v,i->(v shl 8)+(b[at+i].toInt() and 255)}
        var at=8;var w=0;var h=0;var channels=0;var seen=false;val compressed=ByteArrayOutputStream()
        while(at+12<=b.size) {
            val n=int(at);require(n<=Int.MAX_VALUE&&at.toLong()+12+n<=b.size);val size=n.toInt()
            val type=String(b,at+4,4,Charsets.US_ASCII)
            require(CRC32().apply {update(b,at+4,size+4)}.value==int(at+8+size))
            when(type) {
                "IHDR"->{require(at==8&&size==13);w=int(at+8).toInt();h=int(at+12).toInt()
                    if(w<=0||h<=0||w.toLong()*h>MAX_PIXELS)throw Limit()
                    if(b[at+16].toInt()!=8||b[at+20].toInt()!=0||b[at+18].toInt()!=0||b[at+19].toInt()!=0)return null
                    channels=when(b[at+17].toInt()){2->3;6->4;else->return null}}
                "IDAT"->{require(channels>0);compressed.write(b,at+8,size)}
                "acTL","fcTL","fdAT"->return null // A still-image signature cannot attest to an animation.
                "IEND"->{require(size==0&&at+12==b.size);seen=true;break}
            };at+=size+12
        }
        require(seen&&compressed.size()>0)
        val stride=w*channels;val samples=ByteArray(stride*h);val row=ByteArray(stride)
        InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use {input->
            for(y in 0 until h) {
                if(Thread.currentThread().isInterrupted)throw InterruptedIOException()
                val filter=input.read();require(filter in 0..4);var n=0
                while(n<stride){val read=input.read(row,n,stride-n);require(read>0);n+=read}
                for(x in 0 until stride) {
                    val a=if(x>=channels)samples[y*stride+x-channels].toInt() and 255 else 0
                    val up=if(y>0)samples[(y-1)*stride+x].toInt() and 255 else 0
                    val c=if(y>0&&x>=channels)samples[(y-1)*stride+x-channels].toInt() and 255 else 0
                    val predictor=when(filter){0->0;1->a;2->up;3->(a+up)/2;else->{val p=a+up-c;val pa=kotlin.math.abs(p-a);val pb=kotlin.math.abs(p-up);val pc=kotlin.math.abs(p-c);if(pa<=pb&&pa<=pc)a else if(pb<=pc)up else c}}
                    samples[y*stride+x]=(row[x].toInt()+predictor).toByte()
                }
            };require(input.read()==-1)
        }
        return Pixels(w,h,channels,samples)
    }
    private fun alpha(p:Pixels):Map<String,String>? {
        if(p.channels!=4)return null
        val capacity=p.width*p.height;var bit=0
        fun read(n:Int):ByteArray {require(n>=0&&bit.toLong()+8L*n<=capacity);return ByteArray(n){var v=0;repeat(8){val x=bit/p.height;val y=bit%p.height;v=(v shl 1) or (p.samples[(y*p.width+x)*4+3].toInt() and 1);bit++};v.toByte()}}
        val magic="stealth_pngcomp"
        if(capacity<(magic.length+4)*8||String(read(magic.length),Charsets.US_ASCII)!=magic)return null
        val bits=read(4).fold(0L){v,b->(v shl 8)+(b.toInt() and 255)}
        require(bits%8==0L);if(bits/8>GenerationRecipes.MAX_TEXT)throw Limit()
        val data=GZIPInputStream(ByteArrayInputStream(read((bits/8).toInt()))).use {input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>GenerationRecipes.MAX_TEXT)throw Limit();out.write(buffer,0,n)};out.toString("UTF-8")
        }
        val obj=GenerationRecipes.obj(data) ?: return null
        return obj.entrySet().take(32).mapNotNull {(k,v)->if(v.isJsonPrimitive&&v.asJsonPrimitive.isString)k to v.asString else if(k=="Comment"&&v.isJsonObject)k to v.toString() else null}.toMap()
    }
    private fun verify(pixels:ByteArray,comment:ByteArray,signature:ByteArray):Boolean = verifyRecord(pixels,comment,signature,Base64.getDecoder().decode(KEY))
    internal fun verifyRecord(pixels:ByteArray,comment:ByteArray,signature:ByteArray,publicKey:ByteArray):Boolean=runCatching {
        val curve=EdDSANamedCurveTable.getByName("Ed25519")
        val key=EdDSAPublicKey(EdDSAPublicKeySpec(publicKey,curve))
        EdDSAEngine().run {initVerify(key);update(pixels);update(comment);verify(signature)}
    }.getOrDefault(false)
    /** Match Python json.dumps defaults, including ASCII escaping and insertion order.
     * Nonintegral numeric spellings are left intact; uncertain serialization fails verification.
     */
    internal fun pythonJson(v:JsonElement):String=when {
        v.isJsonObject->v.asJsonObject.entrySet().joinToString(", ","{","}"){(k,x)->pythonString(k)+": "+pythonJson(x)}
        v.isJsonArray->v.asJsonArray.joinToString(", ","[","]",transform=::pythonJson)
        v.isJsonNull->"null"
        v.asJsonPrimitive.isString->pythonString(v.asString)
        else->v.toString()
    }
    private fun pythonString(s:String):String=buildString {
        append('"');for(c in s)append(when(c){'"'->"\\\"";'\\'->"\\\\";'\b'->"\\b";'\u000c'->"\\f";'\n'->"\\n";'\r'->"\\r";'\t'->"\\t";else->if(c.code<32||c.code>126)"\\u%04x".format(c.code)else c.toString()});append('"')
    }
}
