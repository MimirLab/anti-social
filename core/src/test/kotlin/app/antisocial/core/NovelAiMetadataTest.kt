package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.*

class NovelAiMetadataTest {
    private val fields=mapOf("Software" to "NovelAI","Description" to "A small lighthouse","Source" to "test model", "Comment" to
        """{"prompt":"A small lighthouse","seed":42,"steps":20,"sampler":"k_euler","scale":5.0}""")
    @Test fun hiddenAlphaRecordIsReadLocallyWithoutAlteringAnyImageBytes() {
        val image=png(fields);val original=image.clone()
        val result=NovelAiMetadata.inspect(image,emptyMap())
        assertEquals("A small lighthouse",result.recipes.single().prompt)
        assertEquals("Hidden alpha-channel record",result.recipes.single().carrier)
        assertEquals(RecipeSignature.UNSIGNED,result.recipes.single().signature)
        assertArrayEquals(original,image)
    }
    @Test fun aCopiedOrInventedSignatureCannotVerifyAnImage() {
        val comment=fields.getValue("Comment").dropLast(1)+",\"signed_hash\":\""+java.util.Base64.getEncoder().encodeToString(ByteArray(64))+"\"}"
        val record=fields+("Comment" to comment)
        assertEquals(RecipeSignature.INVALID,NovelAiMetadata.inspect(png(emptyMap()),record).recipes.single().signature)
        assertTrue(NovelAiMetadata.inspect(png(mapOf("Comment" to "ordinary caption")),emptyMap()).recipes.isEmpty())
    }
    @Test fun exactVerifierPassesAnRfc8032VectorAndRejectsChangedDataAndKeys() {
        fun hex(s:String)=s.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        val key=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature=hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(NovelAiMetadata.verifyRecord(byteArrayOf(),byteArrayOf(),signature,key))
        assertFalse(NovelAiMetadata.verifyRecord(byteArrayOf(1),byteArrayOf(),signature,key))
        assertFalse(NovelAiMetadata.verifyRecord(byteArrayOf(),byteArrayOf(),signature,key.clone().also {it[0]=0}))
    }
    @Test fun pythonCompatibleCommentSerializationPreservesOrderAsciiEscapesAndSeparators() {
        assertEquals("{\"prompt\": \"caf\\u00e9 \\ud83c\\udf0a\", \"steps\": 20, \"scale\": 5.0, \"flags\": [true, null]}",
            NovelAiMetadata.pythonJson(JsonParser.parseString("""{"prompt":"café 🌊","steps":20,"scale":5.0,"flags":[true,null]}""")))
    }
    @Test fun oversizedDimensionsAndPayloadsStopBeforeAllocatingUnboundedPixels() {
        val image=png(fields);val bad=image.clone()
        bad[16]=0x7f;bad[17]=0x7f;bad[18]=0x7f;bad[19]=0x7f
        val crc=CRC32().apply {update(bad,12,17)}.value
        for(i in 0..3)bad[29+i]=(crc shr ((3-i)*8)).toByte()
        assertTrue(NovelAiMetadata.inspect(bad,fields).limited)
        assertTrue(NovelAiMetadata.inspect(byteArrayOf(),fields).recipes.isEmpty())
    }
    private fun png(fields:Map<String,String>):ByteArray {
        val w=128;val h=128;val pixels=ByteArray(w*h*4){i->if(i%4==3)254.toByte()else (i%200).toByte()}
        if(fields.isNotEmpty()) {
            val compressed=ByteArrayOutputStream();GZIPOutputStream(compressed).use {it.write(com.google.gson.Gson().toJson(fields).toByteArray())}
            val payload=ByteArrayOutputStream();payload.write("stealth_pngcomp".toByteArray());DataOutputStream(payload).writeInt(compressed.size()*8);payload.write(compressed.toByteArray())
            payload.toByteArray().forEachIndexed {i,b->repeat(8){j->val at=i*8+j;val x=at/h;val y=at%h;val offset=(y*w+x)*4+3;pixels[offset]=(254 or ((b.toInt() shr (7-j)) and 1)).toByte()}}
        }
        val out=ByteArrayOutputStream();out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(t:String,b:ByteArray){DataOutputStream(out).apply {writeInt(b.size);write(t.toByteArray());write(b);writeInt(CRC32().apply {update(t.toByteArray());update(b)}.value.toInt())}}
        val head=ByteArrayOutputStream();DataOutputStream(head).apply {writeInt(w);writeInt(h);write(byteArrayOf(8,6,0,0,0))};chunk("IHDR",head.toByteArray())
        val compressed=ByteArrayOutputStream();DeflaterOutputStream(compressed).use {z->repeat(h){y->z.write(0);z.write(pixels,y*w*4,w*4)}};chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
        return out.toByteArray()
    }
}
