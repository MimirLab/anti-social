/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import com.google.gson.JsonParser

data class EmbeddedMediaMetadata(val metadata: AiMetadata?,val imageKey: String?,val families: Set<String>,val mime: String?,
    val textFields:Map<String,String> = emptyMap(),val credentialReferences:List<String> = emptyList())

/** Reads actual container metadata, never searches compressed pixels or prose for AI-related words.
 * Image keys cover unchanged encoded picture/color data, excluding only recognized metadata chunks.
 */
object EmbeddedAiMetadata {
    const val MAX_BYTES=32*1024*1024
    private const val MAX_XMP=512*1024
    private const val IPTC="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    private const val RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val adobe="http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val adobeExtended="http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.US_ASCII)
    private val exif="Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
    private val photoshop="Photoshop 3.0\u0000".toByteArray(Charsets.US_ASCII)
    private class Scan {
        val packets=mutableListOf<ByteArray>();val families=linkedSetOf<String>()
        val text=linkedMapOf<String,String>();val conflicts=mutableSetOf<String>();val references=linkedSetOf<String>();var textBytes=0
        var limited=false;var malformed=false;var key: String?=null;var mime: String?=null
        fun xmp(value: ByteArray) {
            families+="xmp"
            if(value.size>MAX_XMP||packets.size>=8)limited=true else packets+=value
        }
        fun rememberText(key:String,value:String) {
            if(key in conflicts)return
            if(text.containsKey(key)&&text[key]!=value){text.remove(key);conflicts+=key;malformed=true;return}
            if(text.containsKey(key))return
            if(text.size>=32||value.length>GenerationRecipes.MAX_TEXT||textBytes+value.length>MAX_XMP){limited=true;return}
            text[key]=value;textBytes+=value.length
        }
    }
    fun inspect(bytes: ByteArray): EmbeddedMediaMetadata {
        if(bytes.size>MAX_BYTES)return EmbeddedMediaMetadata(AiMetadata(limitations=listOf(AiMetadataLimit.SIZE_LIMIT)),null,emptySet(),null)
        val scan=Scan()
        try {when {
            bytes.size>=2&&u(bytes,0)==255&&u(bytes,1)==216->jpeg(bytes,scan)
            bytes.size>=8&&bytes.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->png(bytes,scan)
            bytes.size>=12&&ascii(bytes,0,4)=="RIFF"&&ascii(bytes,8,4)=="WEBP"->webp(bytes,scan)
            bytes.size>=8&&(ascii(bytes,0,2)=="II"||ascii(bytes,0,2)=="MM")->{scan.mime="image/tiff";tiff(bytes,0,bytes.size,scan)}
        }}catch(_: Exception){scan.malformed=true;scan.key=null}
        val recipes=GenerationRecipes.parse(scan.text)
        val disclosures=(scan.packets.flatMap {packet->try {xml(packet){scan.references+=it}}catch(_: Exception){scan.malformed=true;emptyList()}}+
            recipes.map {AiDisclosure(it.use,AiEvidence.GENERATION_RECIPE,tool=it.generator)}).distinct().take(64)
        val limits=buildList {if(scan.limited)add(AiMetadataLimit.SIZE_LIMIT);if(scan.malformed)add(AiMetadataLimit.MALFORMED)}
        val metadata=if(disclosures.isNotEmpty()||limits.isNotEmpty()||recipes.isNotEmpty())AiMetadata(disclosures,limitations=limits,recipes=recipes)else null
        return EmbeddedMediaMetadata(metadata,scan.key,scan.families,scan.mime,scan.text,scan.references.take(4))
    }
    private fun jpeg(b: ByteArray,s: Scan) {
        s.mime="image/jpeg";val hash=MessageDigest.getInstance("SHA-256");hash.update(b,0,2)
        var at=2;var count=0
        while(at<b.size&&count++<8192) {
            val markerStart=at
            require(u(b,at++)==255)
            while(at<b.size&&u(b,at)==255)at++
            require(at<b.size);val marker=u(b,at++)
            if(marker==217) {hash.update(b,markerStart,at-markerStart);s.key="jpeg:"+hex(hash.digest());return}
            require(marker !in 0xd0..0xd8&&marker!=0x01&&marker!=0)
            require(at+2<=b.size);val length=be16(b,at);require(length>=2&&at.toLong()+length<=b.size)
            val start=at+2;val end=at+length
            if(marker==0xe1) {
                when {
                    prefix(b,start,end,adobe)->s.xmp(b.copyOfRange(start+adobe.size,end))
                    // Extended packets are not reassembled here, but they are
                    // still metadata and must never be reported as absent.
                    prefix(b,start,end,adobeExtended)->s.families.add("xmp")
                    prefix(b,start,end,exif)->{s.families+="exif";tiff(b,start+exif.size,end,s)}
                }
            }
            if(marker==0xed&&prefix(b,start,end,photoshop))s.families+="photoshop"
            if(marker==0xfe)s.families+="comments"
            if(marker==0xda) {
                // Everything from the first scan onward must be identical. This
                // deliberately misses unusual metadata inserted between scans.
                require(b.size>=2&&u(b,b.size-2)==255&&u(b,b.size-1)==217)
                hash.update(b,markerStart,b.size-markerStart);s.key="jpeg:"+hex(hash.digest());return
            }
            // APP11 can carry JPEG-XT picture extensions, not just credentials.
            // Keep opaque extensions; a conservative missed comparison is safer
            // than calling different encoded pictures an identical copy.
            if(marker !in setOf(0xe1,0xed,0xfe))hash.update(b,markerStart,end-markerStart)
            at=end
        }
        error("Incomplete JPEG")
    }
    private fun png(b: ByteArray,s: Scan) {
        s.mime="image/png";val hash=MessageDigest.getInstance("SHA-256");var at=8;var count=0;var image=false
        while(at+12<=b.size&&count++<8192) {
            val length=be32(b,at);require(length<=Int.MAX_VALUE&&at.toLong()+12+length<=b.size)
            val size=length.toInt();val type=ascii(b,at+4,4);val start=at+8;val end=start+size
            val crc=CRC32().apply {update(b,at+4,4+size)}.value;require(crc==be32(b,end))
            when(type) {
                "iTXt"->pngText(b,start,end,s,true)
                "tEXt"->pngText(b,start,end,s,false)
                // XMP's standard PNG carrier is iTXt. Compressed ordinary text
                // is still metadata for exact-picture comparisons, but its
                // arbitrary contents cannot declare AI use.
                "zTXt"->pngText(b,start,end,s,false,true)
                "eXIf"->{s.families+="exif";tiff(b,start,end,s)}
                "IDAT"->image=true
            }
            if(type !in setOf("iTXt","tEXt","zTXt","eXIf","caBX"))hash.update(b,at+4,4+size)
            if(type=="IEND") {require(image&&size==0&&end+4==b.size);s.key="png:"+hex(hash.digest());return}
            at=end+4
        }
        error("Incomplete PNG")
    }
    private fun pngText(b: ByteArray,start: Int,end: Int,s: Scan,international: Boolean,zipped:Boolean=false) {
        val keyEnd=zero(b,start,end);if(keyEnd<0||keyEnd-start>79)return
        val key=ascii(b,start,keyEnd-start)
        // PNG XMP uses iTXt (or the previously supported uncompressed tEXt).
        // zTXt XML-shaped prose is not an XMP declaration.
        val xmp=key=="XML:com.adobe.xmp"&&!zipped
        if(!xmp)s.families+="comments"
        if(!xmp&&key !in setOf("parameters","prompt","workflow","Software","Comment","Source","Description","invokeai_metadata","negative_prompt","seed","steps","sampler_name","guidance_scale","stable_diffusion_model"))return
        var at=keyEnd+1;var compressed=if(zipped)1 else 0
        if(international) {
            require(at+2<=end);compressed=u(b,at++);val method=u(b,at++);require(compressed in 0..1&&method==0)
            at=zero(b,at,end).takeIf {it>=0}?.plus(1) ?: error("Missing language terminator")
            at=zero(b,at,end).takeIf {it>=0}?.plus(1) ?: error("Missing translation terminator")
        }else if(zipped){require(at<end&&u(b,at++)==0)}
        val limit=if(xmp)MAX_XMP else GenerationRecipes.MAX_TEXT
        val value=if(compressed==0) {
            if(end-at>limit){s.limited=true;return};b.copyOfRange(at,end)
        }else {
            if(xmp)s.families+="xmp"
            val out=ByteArrayOutputStream()
            InflaterInputStream(ByteArrayInputStream(b,at,end-at)).use {input->
                val buffer=ByteArray(8192)
                while(true) {val n=input.read(buffer);if(n<0)break;if(out.size()+n>limit){s.limited=true;return};out.write(buffer,0,n)}
            }
            out.toByteArray()
        }
        if(xmp)s.xmp(value)else if(s.text.size<32&&s.textBytes+value.size<=MAX_XMP) {
            // Conflicting same-name records are ambiguous rather than last-writer wins.
            val text=value.toString(if(international)Charsets.UTF_8 else Charsets.ISO_8859_1)
            s.rememberText(key,text)
        }else s.limited=true
    }
    private fun webp(b: ByteArray,s: Scan) {
        s.mime="image/webp";val declared=le32(b,4)+8;require(declared==b.size.toLong())
        val hash=MessageDigest.getInstance("SHA-256");var at=12;var count=0;var image=false
        while(at+8<=b.size&&count++<8192) {
            val type=ascii(b,at,4);val length=le32(b,at+4);require(length<=Int.MAX_VALUE&&at.toLong()+8+length+(length%2)<=b.size)
            val start=at+8;val end=start+length.toInt()
            when(type) {
                "XMP "->s.xmp(b.copyOfRange(start,end))
                "EXIF"->{s.families+="exif";tiff(b,start,end,s)}
                "VP8 ","VP8L","ANMF"->image=true
            }
            if(type !in setOf("XMP ","EXIF","C2PA")) {
                hash.update(type.toByteArray(Charsets.US_ASCII))
                if(type=="VP8X"&&end>start) {hash.update((u(b,start) and 0xf3).toByte());hash.update(b,start+1,end-start-1)}else hash.update(b,start,end-start)
            }
            at=end+(length%2).toInt()
        }
        require(at==b.size&&image);s.key="webp:"+hex(hash.digest())
    }
    private fun tiff(b: ByteArray,start: Int,end: Int,s: Scan) {
        if(end-start<8)return
        val little=ascii(b,start,2)=="II";if(!little&&ascii(b,start,2)!="MM")return
        fun word(at: Int): Int {require(at>=start&&at+2<=end);return if(little)u(b,at)+(u(b,at+1) shl 8)else be16(b,at)}
        fun dword(at: Int): Long {require(at>=start&&at+4<=end);return if(little)le32(b,at)else be32(b,at)}
        if(word(start+2)!=42)return
        val pending=ArrayDeque<Long>().apply {add(dword(start+4))};val visited=mutableSetOf<Long>()
        repeat(16) {
            if(pending.isEmpty())return
            val next=pending.removeFirst()
            if(next==0L||!visited.add(next))return@repeat
            require(next<=Int.MAX_VALUE&&start.toLong()+next+2<=end)
            val at=start+next.toInt();val count=word(at);require(count<=1024&&at.toLong()+2+count*12+4<=end)
            repeat(count){index->val entry=at+2+index*12
                val tag=word(entry);val type=word(entry+2);val amount=dword(entry+4)
                if(tag==34665&&type==4&&amount==1L)pending.add(dword(entry+8))
                if(tag in setOf(0x9286,0x0131)&&type in setOf(1,2,7)&&amount in 1..GenerationRecipes.MAX_TEXT.toLong()) {
                    val offset=if(amount<=4)(entry+8).toLong()else start+dword(entry+8)
                    require(offset>=start&&offset+amount<=end)
                    val raw=b.copyOfRange(offset.toInt(),(offset+amount).toInt())
                    if(tag==0x0131)s.rememberText("Software",String(raw,Charsets.ISO_8859_1).trimEnd('\u0000'))
                    else if(raw.size>=8) {
                        s.families+="comments"
                        val prefix=String(raw,0,8,Charsets.US_ASCII)
                        val text=when {
                            prefix=="ASCII\u0000\u0000\u0000"->String(raw,8,raw.size-8,Charsets.UTF_8)
                            prefix=="UNICODE\u0000"->String(raw,8,raw.size-8,if(raw.size>=10&&raw[8]==0xff.toByte()&&raw[9]==0xfe.toByte())Charsets.UTF_16LE else Charsets.UTF_16BE)
                            else->null
                        }?.trim('\u0000','\ufeff')
                        if(text!=null) {
                            val outer=GenerationRecipes.obj(text)
                            if(outer!=null&&outer["Software"]?.takeIf {it.isJsonPrimitive}?.asString=="NovelAI") {
                                for(key in listOf("Software","Comment","Description","Source"))outer[key]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString?.let {s.rememberText(key,it)}
                            }else s.rememberText("parameters",text)
                        }
                    }
                }
                if(word(entry)==700) {
                    val size=dword(entry+4)
                    if(type in setOf(1,7)&&size>0) {
                        s.families+="xmp"
                        if(size>MAX_XMP)s.limited=true else {
                            val offset=if(size<=4)(entry+8).toLong()else start+dword(entry+8)
                            require(offset>=start&&offset+size<=end);s.xmp(b.copyOfRange(offset.toInt(),(offset+size).toInt()))
                        }
                    }
                }
            }
            pending.add(dword(at+2+count*12))
        }
    }
    private fun xml(bytes: ByteArray,reference:(String)->Unit={}): List<AiDisclosure> {
        require(bytes.size<=MAX_XMP)
        val raw=bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        require(!raw.contains("<!DOCTYPE",true)&&!raw.contains("<!ENTITY",true))
        val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true;isExpandEntityReferences=false
            runCatching {setFeature("http://xml.org/sax/features/external-general-entities",false)}
            runCatching {setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
        }
        val builder=factory.newDocumentBuilder().apply {
            setEntityResolver {_,_->throw SAXException("External entities are disabled")}
            setErrorHandler(object: DefaultHandler(){override fun fatalError(e: org.xml.sax.SAXParseException){throw e};override fun error(e: org.xml.sax.SAXParseException){throw e}})
        }
        val root=builder.parse(InputSource(raw.reader())).documentElement ?: return emptyList()
        val descriptions=root.getElementsByTagNameNS(RDF,"Description")
        require(descriptions.length<=128)
        val result=mutableListOf<AiDisclosure>()
        val nodes=root.getElementsByTagName("*");require(nodes.length<=2048)
        for(i in 0 until descriptions.length) {
            val desc=descriptions.item(i) as? Element ?: continue
            if(desc.getAttributeNS(RDF,"about").isNotBlank())continue
            // Nested RDF descriptions (ingredients, thumbnails, people) are not
            // the current asset's metadata and cannot label the whole picture.
            var parent=desc.parentNode;var nested=false
            while(parent!=null&&parent!==root) {if(parent is Element&&parent.namespaceURI==RDF&&parent.localName=="Description")nested=true;parent=parent.parentNode}
            if(nested)continue
            fun property(ns: String,name: String): String? {
                val values=mutableListOf<String>()
                desc.getAttributeNS(ns,name).takeIf {it.isNotBlank()}?.let {values+=it}
                var child=desc.firstChild
                while(child!=null) {
                    if(child is Element&&child.namespaceURI==ns&&child.localName==name)
                        values+=child.getAttributeNS(RDF,"resource").takeIf {it.isNotBlank()} ?: child.textContent
                    child=child.nextSibling
                }
                val distinct=values.map {it.trim()}.filter {it.isNotBlank()}.distinct()
                require(distinct.size<=1){"Conflicting XMP fields"}
                return distinct.firstOrNull()
            }
            val source=property(IPTC,"DigitalSourceType")?.trim()
            property("http://purl.org/dc/terms/","provenance")?.takeIf {it.length<=4096}?.let(reference)
            val use=AiMetadataValues.sourceType(source)
            val tool=AiMetadataValues.text(property(IPTC,"AISystemUsed"))
            val version=AiMetadataValues.text(property(IPTC,"AISystemVersionUsed"),80)
            if(use!=null||tool!=null)result+=AiDisclosure(use ?: AiUse.UNSPECIFIED,AiEvidence.IPTC_XMP,source,tool,version)
            for(ns in listOf("http://www.tc260.org.cn/ns/AIGC/1.0/","http://www.tc260.org.cn/ns/AIGC/1.0")) {
                val declaration=property(ns,"AIGC") ?: continue
                if(declaration.length>16*1024)continue
                val outer=runCatching {JsonParser.parseString(declaration).asJsonObject}.getOrNull() ?: continue
                val obj=outer["AIGC"]?.takeIf {it.isJsonObject}?.asJsonObject ?: outer
                val label=obj["Label"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
                if(label=="1")result+=AiDisclosure(AiUse.UNSPECIFIED,AiEvidence.EMBEDDED_DECLARATION,label="Embedded AI-generation declaration")
            }
        }
        return result
    }
    private fun prefix(b: ByteArray,at: Int,end: Int,p: ByteArray)=at+p.size<=end&&p.indices.all {b[at+it]==p[it]}
    private fun zero(b: ByteArray,start: Int,end: Int): Int {for(i in start until end)if(b[i]==0.toByte())return i;return -1}
    private fun ascii(b: ByteArray,at: Int,size: Int)=String(b,at,size,Charsets.US_ASCII)
    private fun u(b: ByteArray,at: Int)=b[at].toInt() and 255
    private fun be16(b: ByteArray,at: Int)=(u(b,at) shl 8)+u(b,at+1)
    private fun be32(b: ByteArray,at: Int)=(0..3).fold(0L){v,i->(v shl 8)+u(b,at+i)}
    private fun le32(b: ByteArray,at: Int)=(0..3).fold(0L){v,i->v or (u(b,at+i).toLong() shl (8*i))}
    private fun hex(bytes: ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
}
