/* AntiSocial, GPL-3.0-or-later. Real container boundaries, synthetic declarations. */
package app.antisocial.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class EmbeddedAiMetadataTest {
    private val iptc="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    private val rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val generated="http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
    private val edited="http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia"
    private val adobe="http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private fun xmp(properties: String="",attributes: String="",namespace: String=iptc,about: String="")="""<x:xmpmeta xmlns:x="adobe:ns:meta/"><r:RDF xmlns:r="$rdf"><r:Description r:about="$about" xmlns:unusual="$namespace" $attributes>$properties</r:Description></r:RDF></x:xmpmeta>""".toByteArray()
    private fun attr(value: String=generated)=xmp(attributes="""unusual:DigitalSourceType="$value"""")
    private fun element(value: String=generated)=xmp(properties="""<unusual:DigitalSourceType r:resource="$value"/>""")
    private fun uses(value: EmbeddedMediaMetadata)=value.metadata?.disclosures.orEmpty().map {it.use}
    private fun noAi(value: EmbeddedMediaMetadata)=assertTrue(value.metadata?.disclosures.isNullOrEmpty())
    private fun limit(value: EmbeddedMediaMetadata,limit: AiMetadataLimit)=assertTrue(value.metadata?.limitations.orEmpty().contains(limit))
    private fun jpeg(color: Int=0x3366cc): ByteArray {
        val image=BufferedImage(4,3,BufferedImage.TYPE_INT_RGB)
        repeat(3){y->repeat(4){x->image.setRGB(x,y,color xor ((x+y)*0x010101))}}
        return ByteArrayOutputStream().apply {assertTrue(ImageIO.write(image,"jpeg",this))}.toByteArray()
    }
    private fun segment(marker: Int,payload: ByteArray): ByteArray=ByteArrayOutputStream().apply {
        write(0xff);write(marker);DataOutputStream(this).writeShort(payload.size+2);write(payload)
    }.toByteArray()
    private fun withJpegSegments(image: ByteArray,vararg segments: ByteArray)=image.copyOfRange(0,2)+segments.fold(ByteArray(0)){a,b->a+b}+image.copyOfRange(2,image.size)
    private fun jpegXmp(packet: ByteArray,image: ByteArray=jpeg())=withJpegSegments(image,segment(0xe1,adobe+packet))
    private fun compressed(value: ByteArray)=ByteArrayOutputStream().apply {DeflaterOutputStream(this).use {it.write(value)}}.toByteArray()
    private fun chunk(type: String,payload: ByteArray): ByteArray=ByteArrayOutputStream().apply {
        val data=type.toByteArray(Charsets.US_ASCII)+payload
        DataOutputStream(this).writeInt(payload.size);write(data)
        DataOutputStream(this).writeInt(CRC32().apply {update(data)}.value.toInt())
    }.toByteArray()
    private fun pngText(packet: ByteArray,compress: Boolean=false,key: String="XML:com.adobe.xmp")=
        key.toByteArray()+byteArrayOf(0,if(compress)1 else 0,0,0,0)+(if(compress)compressed(packet)else packet)
    private fun png(metadata: List<Pair<String,ByteArray>> = emptyList(),red: Int=255,gamma: Int?=null): ByteArray {
        val header=ByteBuffer.allocate(13).putInt(2).putInt(1).put(8).put(2).put(0).put(0).put(0).array()
        val pixels=compressed(byteArrayOf(0,red.toByte(),0,0,0,0,255.toByte()))
        return byteArrayOf(137.toByte(),80,78,71,13,10,26,10)+chunk("IHDR",header)+
            (gamma?.let {chunk("gAMA",ByteBuffer.allocate(4).putInt(it).array())} ?: ByteArray(0))+
            metadata.fold(ByteArray(0)){a,m->a+chunk(m.first,m.second)}+chunk("IDAT",pixels)+chunk("IEND",ByteArray(0))
    }
    private fun webp(packet: ByteArray?=null,icc: ByteArray?=null): ByteArray {
        fun webpChunk(type: String,payload: ByteArray)=type.toByteArray()+ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()+payload+ByteArray(payload.size%2)
        val flags=(if(packet!=null)4 else 0) or (if(icc!=null)32 else 0)
        val payload=webpChunk("VP8X",byteArrayOf(flags.toByte(),0,0,0,0,0,0,0,0,0))+
            (icc?.let {webpChunk("ICCP",it)} ?: ByteArray(0))+
            webpChunk("VP8L",byteArrayOf(0x2f,0,0,0,0))+(packet?.let {webpChunk("XMP ",it)} ?: ByteArray(0))
        return "RIFF".toByteArray()+ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size+4).array()+"WEBP".toByteArray()+payload
    }
    private fun tiff(packet: ByteArray,little: Boolean=true,type: Int=7,count: Int=packet.size,offset: Int=26): ByteArray {
        val order=if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer=ByteBuffer.allocate(26).order(order)
        buffer.put(if(little)"II".toByteArray()else "MM".toByteArray()).putShort(42).putInt(8)
        buffer.putShort(1).putShort(700).putShort(type.toShort()).putInt(count).putInt(offset).putInt(0)
        return buffer.array()+packet
    }

    @Test fun jpegApp1ReadsExactNamespaceWithArbitraryPrefixInAttributesAndElements() {
        for(packet in listOf(attr(),element(),xmp(properties="""<unusual:DigitalSourceType>$edited</unusual:DigitalSourceType>"""))) {
            val image=jpegXmp(packet)
            assertEquals(4,ImageIO.read(ByteArrayInputStream(image)).width)
            val found=EmbeddedAiMetadata.inspect(image)
            assertEquals("image/jpeg",found.mime)
            assertEquals(1,found.metadata!!.disclosures.size)
            assertEquals(AiEvidence.IPTC_XMP,found.metadata!!.disclosures.single().evidence)
            assertTrue(found.families.contains("xmp"))
            assertNotNull(found.imageKey)
        }
        assertEquals(listOf(AiUse.EDITED),uses(EmbeddedAiMetadata.inspect(jpegXmp(element(edited)))))
    }

    @Test fun pngInternationalTextReadsCompressedAndUncompressedXmpPackets() {
        for(compress in listOf(false,true)) {
            val bytes=png(listOf("iTXt" to pngText(attr(),compress)))
            assertEquals(2,ImageIO.read(ByteArrayInputStream(bytes)).width)
            val found=EmbeddedAiMetadata.inspect(bytes)
            assertEquals(listOf(AiUse.GENERATED),uses(found))
            assertEquals("image/png",found.mime)
            assertEquals(setOf("xmp"),found.families)
        }
    }

    @Test fun webpAndBothEndianTiffMetadataUseTheirActualDeclaredChunks() {
        val found=EmbeddedAiMetadata.inspect(webp(attr()))
        assertEquals("image/webp",found.mime)
        assertEquals(listOf(AiUse.GENERATED),uses(found))
        for(little in listOf(false,true)) {
            val t=EmbeddedAiMetadata.inspect(tiff(element(edited),little))
            assertEquals(listOf(AiUse.EDITED),uses(t))
            assertTrue(t.families.contains("xmp"))
            assertNull(t.imageKey) // No claim of equivalence for TIFF pixel storage.
        }
    }

    @Test fun jpegExifIfdTag700CanCarryTheXmpProperty() {
        val image=withJpegSegments(jpeg(),segment(0xe1,"Exif\u0000\u0000".toByteArray()+tiff(attr())))
        val found=EmbeddedAiMetadata.inspect(image)
        assertEquals(listOf(AiUse.GENERATED),uses(found))
        assertEquals(setOf("xmp","exif"),found.families)
    }

    @Test fun strippingOnlyMetadataPreservesTheEncodedImageIdentity() {
        val j=jpeg()
        assertEquals(EmbeddedAiMetadata.inspect(j).imageKey,EmbeddedAiMetadata.inspect(jpegXmp(attr(),j)).imageKey)
        assertEquals(EmbeddedAiMetadata.inspect(png()).imageKey,EmbeddedAiMetadata.inspect(png(listOf("iTXt" to pngText(attr())))).imageKey)
        assertEquals(EmbeddedAiMetadata.inspect(webp()).imageKey,EmbeddedAiMetadata.inspect(webp(attr())).imageKey)
        assertNotEquals(EmbeddedAiMetadata.inspect(j).imageKey,EmbeddedAiMetadata.inspect(jpeg(0xeeee44)).imageKey)
        assertNotEquals(EmbeddedAiMetadata.inspect(png()).imageKey,EmbeddedAiMetadata.inspect(png(red=24)).imageKey)
    }

    @Test fun compressedOrdinaryPngTextIsRecordedForCopyComparisonsWithoutDeclaringAi() {
        val plain=EmbeddedAiMetadata.inspect(png())
        for(key in listOf("Comment","XML:com.adobe.xmp")) {
            // zTXt is ordinary compressed Latin-1 text, not the standard XMP
            // iTXt carrier. Even XML-shaped content remains a comment here.
            val payload=key.toByteArray()+byteArrayOf(0,0)+compressed(attr())
            val observed=EmbeddedAiMetadata.inspect(png(listOf("zTXt" to payload)))
            assertEquals(plain.imageKey,observed.imageKey)
            assertEquals(setOf("comments"),observed.families)
            assertTrue(observed.metadata?.removals.isNullOrEmpty())
            noAi(observed)
        }
        assertTrue(plain.families.isEmpty())
    }

    @Test fun changedColorRenderingChunksArePartOfImageIdentity() {
        assertNotEquals(EmbeddedAiMetadata.inspect(png(gamma=45455)).imageKey,EmbeddedAiMetadata.inspect(png(gamma=100000)).imageKey)
        assertNotEquals(EmbeddedAiMetadata.inspect(webp(icc=byteArrayOf(1,2,3))).imageKey,EmbeddedAiMetadata.inspect(webp(icc=byteArrayOf(1,2,4))).imageKey)
        assertEquals(EmbeddedAiMetadata.inspect(webp(icc=byteArrayOf(1,2,3))).imageKey,EmbeddedAiMetadata.inspect(webp(attr(),byteArrayOf(1,2,3))).imageKey)
    }

    @Test fun unrelatedNamespacePixelBytesAndCommentTextCannotDeclareAi() {
        val foreign=xmp(attributes="unusual:DigitalSourceType=\"$generated\"",namespace="https://evil.example/iptc")
        noAi(EmbeddedAiMetadata.inspect(jpegXmp(foreign)))
        noAi(EmbeddedAiMetadata.inspect(png(listOf("iTXt" to pngText(attr(),key="Comment")))))
        noAi(EmbeddedAiMetadata.inspect(png(listOf("tEXt" to ("Description\u0000".toByteArray()+attr())))))
        noAi(EmbeddedAiMetadata.inspect(withJpegSegments(jpeg(),segment(0xfe,attr()))))
        noAi(EmbeddedAiMetadata.inspect(attr())) // An XML body alone is not an image container.
    }

    @Test fun ordinarySoftwareAndAlgorithmicEditsAreNotInferredAsGenerativeAi() {
        val software="""<xmp:CreatorTool xmlns:xmp="http://ns.adobe.com/xap/1.0/">Adobe Photoshop AI 2026</xmp:CreatorTool><unusual:Description>Generated with AI #aigenerated</unusual:Description>"""
        noAi(EmbeddedAiMetadata.inspect(jpegXmp(xmp(properties=software))))
        for(term in listOf("algorithmicallyEnhanced","softwareImage","computationalCapture","digitalCapture")) {
            noAi(EmbeddedAiMetadata.inspect(jpegXmp(attr("http://cv.iptc.org/newscodes/digitalsourcetype/$term"))))
        }
    }

    @Test fun namedForeignSubjectsAndNestedIngredientsCannotLabelTheCurrentAsset() {
        val foreign=xmp(attributes="unusual:DigitalSourceType=\"$generated\"",about="https://unrelated.example/other.jpg")
        noAi(EmbeddedAiMetadata.inspect(jpegXmp(foreign)))
        val nested=xmp(properties="""<unusual:Ingredient><r:Description unusual:DigitalSourceType="$generated"/></unusual:Ingredient>""")
        noAi(EmbeddedAiMetadata.inspect(jpegXmp(nested)))
    }

    @Test fun dtdAndExternalEntitiesAreRejectedWithoutProducingDeclarations() {
        val prefix="""<!DOCTYPE x:xmpmeta [<!ENTITY remote SYSTEM "https://invalid.example/private">]>""".toByteArray()
        val withEntity=xmp(properties="""<unusual:DigitalSourceType>&remote;</unusual:DigitalSourceType>""")
        val found=EmbeddedAiMetadata.inspect(jpegXmp(prefix+withEntity))
        noAi(found);limit(found,AiMetadataLimit.MALFORMED)
        val expansion="""<!DOCTYPE x:xmpmeta [<!ENTITY a "xx"><!ENTITY b "&a;&a;&a;&a;">]>""".toByteArray()+withEntity
        val repeated=EmbeddedAiMetadata.inspect(jpegXmp(expansion))
        noAi(repeated);limit(repeated,AiMetadataLimit.MALFORMED)
    }

    @Test fun duplicatePacketsAreDeduplicatedAndPacketCountIsBounded() {
        val repeated=png(List(10){"iTXt" to pngText(attr())})
        val found=EmbeddedAiMetadata.inspect(repeated)
        assertEquals(listOf(AiUse.GENERATED),uses(found))
        limit(found,AiMetadataLimit.SIZE_LIMIT)
        val invalid=xmp(properties="""<unusual:DigitalSourceType>$generated</unusual:DigitalSourceType>""",attributes="""unusual:DigitalSourceType="$generated" unusual:DigitalSourceType="$edited"""")
        val duplicateAttributes=EmbeddedAiMetadata.inspect(jpegXmp(invalid))
        noAi(duplicateAttributes);limit(duplicateAttributes,AiMetadataLimit.MALFORMED)
        val conflicting=xmp(properties="""<unusual:DigitalSourceType>$generated</unusual:DigitalSourceType><unusual:DigitalSourceType>$edited</unusual:DigitalSourceType>""")
        val conflict=EmbeddedAiMetadata.inspect(jpegXmp(conflicting))
        noAi(conflict);limit(conflict,AiMetadataLimit.MALFORMED)
        val identical=xmp(properties="""<unusual:DigitalSourceType>$generated</unusual:DigitalSourceType><unusual:DigitalSourceType>$generated</unusual:DigitalSourceType>""")
        assertEquals(listOf(AiUse.GENERATED),uses(EmbeddedAiMetadata.inspect(jpegXmp(identical))))
    }

    @Test fun compressedAndUncompressedPacketLimitsPreventUnboundedXmlWork() {
        val enormous=xmp(properties=" ".repeat(700_000))
        for(compress in listOf(false,true)) {
            val found=EmbeddedAiMetadata.inspect(png(listOf("iTXt" to pngText(enormous,compress))))
            noAi(found);limit(found,AiMetadataLimit.SIZE_LIMIT)
        }
        val manyDescriptions=("""<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:r="$rdf"><r:RDF>"""+
            "<r:Description/>".repeat(150)+"</r:RDF></x:xmpmeta>").toByteArray()
        val found=EmbeddedAiMetadata.inspect(png(listOf("iTXt" to pngText(manyDescriptions))))
        noAi(found);limit(found,AiMetadataLimit.MALFORMED)
    }

    @Test fun corruptCrcLengthsAndTiffOffsetsCannotYieldAnImageIdentity() {
        val badCrc=png().copyOf().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        val badPngLength=png().copyOf().apply {this[8]=0x7f;this[9]=0xff.toByte();this[10]=0xff.toByte();this[11]=0xff.toByte()}
        val badRiffLength=webp(attr()).copyOf().apply {this[4]=0xff.toByte();this[5]=0xff.toByte();this[6]=0xff.toByte();this[7]=0x7f}
        for(bytes in listOf(badCrc,badPngLength,badRiffLength,tiff(attr(),offset=Int.MAX_VALUE))) {
            val found=EmbeddedAiMetadata.inspect(bytes)
            noAi(found);limit(found,AiMetadataLimit.MALFORMED);assertNull(found.imageKey)
        }
        val oversized=EmbeddedAiMetadata.inspect(tiff(ByteArray(0),count=Int.MAX_VALUE))
        noAi(oversized);limit(oversized,AiMetadataLimit.SIZE_LIMIT)
    }

    @Test fun exactChineseNamespaceSupportsOnlyTheConfirmedStringLabel() {
        for(ns in listOf("http://www.tc260.org.cn/ns/AIGC/1.0","http://www.tc260.org.cn/ns/AIGC/1.0/")) {
            for(value in listOf("""{"Label":"1","ContentProducer":"Example","ProduceID":"123"}""","""{"AIGC":{"Label":"1","ContentProducer":"Example","ProduceID":"123"}}""")) {
                val found=EmbeddedAiMetadata.inspect(jpegXmp(xmp(properties="""<unusual:AIGC>$value</unusual:AIGC>""",namespace=ns)))
                assertEquals(listOf(AiUse.UNSPECIFIED),uses(found))
                assertEquals(AiEvidence.EMBEDDED_DECLARATION,found.metadata!!.disclosures.single().evidence)
                assertNull(found.metadata!!.disclosures.single().tool)
            }
        }
        for(label in listOf("\"2\"","\"3\"","1","true","null","\"01\"")) {
            val packet=xmp(properties="""<unusual:AIGC>{"Label":$label,"ContentProducer":"Example"}</unusual:AIGC>""",namespace="http://www.tc260.org.cn/ns/AIGC/1.0/")
            noAi(EmbeddedAiMetadata.inspect(jpegXmp(packet)))
        }
        val foreign=xmp(properties="""<unusual:AIGC>{"Label":"1"}</unusual:AIGC>""",namespace="http://www.tc260.org.cn.evil.example/ns/AIGC/1.0/")
        noAi(EmbeddedAiMetadata.inspect(jpegXmp(foreign)))
    }

    @Test fun explicitAiSystemFieldsKeepOnlyTheirDeclaredNamesAndVersions() {
        val packet=xmp(properties="""<unusual:AISystemUsed>Declared generator</unusual:AISystemUsed><unusual:AISystemVersionUsed>2.1</unusual:AISystemVersionUsed><unusual:AIPromptInformation>PRIVATE PROMPT NOT FOR DISPLAY</unusual:AIPromptInformation>""")
        val found=EmbeddedAiMetadata.inspect(jpegXmp(packet))
        val d=found.metadata!!.disclosures.single()
        assertEquals(AiUse.UNSPECIFIED,d.use);assertEquals("Declared generator",d.tool);assertEquals("2.1",d.version)
        assertFalse(found.toString().contains("PRIVATE PROMPT"))
    }

    @Test fun wholeFileLimitRejectsOversizeInputBeforeContainerParsing() {
        val bytes=ByteArray(EmbeddedAiMetadata.MAX_BYTES+1).apply {this[0]=0xff.toByte();this[1]=0xd8.toByte()}
        val found=EmbeddedAiMetadata.inspect(bytes)
        noAi(found);limit(found,AiMetadataLimit.SIZE_LIMIT);assertNull(found.imageKey)
    }

    @Test fun jpegPictureExtensionsStayInTheIdentityAndUnparsedExtendedXmpIsNeverCalledMissing() {
        val plain=jpeg()
        val extension=withJpegSegments(plain,segment(0xeb,"JP opaque picture extension".toByteArray()))
        assertNotEquals(EmbeddedAiMetadata.inspect(plain).imageKey,EmbeddedAiMetadata.inspect(extension).imageKey)
        val xmpExtension="http://ns.adobe.com/xmp/extension/\u0000".toByteArray()+ByteArray(40)
        val extended=EmbeddedAiMetadata.inspect(withJpegSegments(plain,segment(0xe1,xmpExtension)))
        assertTrue(extended.families.contains("xmp"));noAi(extended)
        assertEquals(EmbeddedAiMetadata.inspect(plain).imageKey,extended.imageKey)
    }
}
