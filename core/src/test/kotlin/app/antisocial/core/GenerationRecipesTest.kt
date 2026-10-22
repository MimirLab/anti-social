package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class GenerationRecipesTest {
    private val recipe="A small lighthouse\nNegative prompt: blurry\nSteps: 24, Sampler: Euler a, CFG scale: 7, Seed: 42, Size: 512x512, Model: harbor"
    @Test fun aStructuredRecipeIsUsefulWhileOrdinaryPromptWordsAreNotEvidence() {
        val value=GenerationRecipes.parse(mapOf("parameters" to recipe)).single()
        assertEquals("Stable Diffusion WebUI",value.generator);assertEquals("A small lighthouse",value.prompt)
        assertEquals("blurry",value.negativePrompt);assertEquals("harbor",value.model)
        assertEquals(AiUse.UNSPECIFIED,value.use);assertEquals(RecipeSignature.UNSIGNED,value.signature)
        listOf("AI art ComfyUI", "Steps: 24, Sampler: Euler", "Prompt: photograph").forEach {assertTrue(GenerationRecipes.parse(mapOf("parameters" to it)).isEmpty())}
        assertTrue(GenerationRecipes.parse(mapOf("Software" to "NovelAI","Comment" to "An ordinary caption")).isEmpty())
    }
    @Test fun comfyRequiresAConnectedGenerationPathRatherThanAnUnusedWorkflowNode() {
        val graph="""{"1":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"harbor"}},"2":{"class_type":"CLIPTextEncode","inputs":{"text":"A lighthouse","clip":["1",1]}},"3":{"class_type":"KSampler","inputs":{"steps":20,"seed":42,"sampler_name":"euler","positive":["2",0],"model":["1",0]}},"4":{"class_type":"VAEDecode","inputs":{"samples":["3",0]}},"5":{"class_type":"SaveImage","inputs":{"images":["4",0]}}}"""
        assertEquals("ComfyUI",GenerationRecipes.parse(mapOf("prompt" to graph)).single().generator)
        assertTrue(GenerationRecipes.parse(mapOf("prompt" to graph.replace("[\"4\",0]","[\"1\",0]"))).isEmpty())
        assertTrue(GenerationRecipes.parse(mapOf("prompt" to "{\"text\":\"A ComfyUI drawing\"}")).isEmpty())
    }
    @Test fun boundedPngTextHandlesOrdinaryAndCompressedRecordsWithoutTreatingCaptionsAsDeclarations() {
        val normal=EmbeddedAiMetadata.inspect(png("parameters",recipe,false))
        val zipped=EmbeddedAiMetadata.inspect(png("parameters",recipe,true))
        assertEquals(normal.metadata?.recipes,zipped.metadata?.recipes)
        assertEquals(AiEvidence.GENERATION_RECIPE,zipped.metadata!!.disclosures.single().evidence)
        assertTrue(EmbeddedAiMetadata.inspect(png("Comment","I used AI in this caption",false)).metadata?.disclosures.orEmpty().isEmpty())
        val bomb=EmbeddedAiMetadata.inspect(png("parameters","x".repeat(GenerationRecipes.MAX_TEXT+1),true))
        assertTrue(bomb.metadata!!.limitations.contains(AiMetadataLimit.SIZE_LIMIT));assertTrue(bomb.metadata.recipes.isEmpty())
    }
    @Test fun deeplyNestedAndOversizeRecipesFailClosedWithoutRecursionFailures() {
        val deep="{\"x\":".repeat(100)+"0"+"}".repeat(100)
        assertTrue(GenerationRecipes.parse(mapOf("prompt" to deep)).isEmpty())
        assertTrue(GenerationRecipes.parse(mapOf("parameters" to "x".repeat(GenerationRecipes.MAX_TEXT+1))).isEmpty())
    }
    @Test fun credentialLinksAreSmallExplicitRelationsResolvedAgainstTheFinalMediaUrl() {
        val values=listOf("</media/image.c2pa>; rel=\"c2pa-manifest\"; type=\"application/c2pa\", </other>; rel=alternate")
        assertEquals(listOf("https://cdn.example/media/image.c2pa"),MediaCredentialLinks.headers(values,"https://cdn.example/image.png"))
        assertTrue(MediaCredentialLinks.headers(listOf("<https://user:password@example.com/x>; rel=c2pa-manifest"),"https://cdn.example/a").isEmpty())
        assertTrue(MediaCredentialLinks.headers(listOf("<file:///x>; rel=c2pa-manifest", "<http://example.com/x>; rel=c2pa-manifest"),"https://cdn.example/a").isEmpty())
        assertTrue(MediaCredentialLinks.headers(listOf("<https://example.com/a>; rel=\"not-c2pa-manifest\""),"https://cdn.example/a").isEmpty())
    }
    @Test fun copiedSignedObservationsLoseTheirVerifiedStatus() {
        val captured=AiMetadataValues.captured(AiMetadata(disclosures=listOf(AiDisclosure(AiUse.GENERATED,AiEvidence.NOVELAI_SIGNATURE,tool="NovelAI")),
            recipes=listOf(GenerationRecipe("NovelAI",signature=RecipeSignature.VALID))))!!
        assertEquals(RecipeSignature.UNCHECKED,captured.recipes.single().signature)
        assertEquals(AiEvidence.GENERATION_RECIPE,captured.disclosures.single().evidence)
        assertFalse(AiMetadataNarrative.verification(captured)!!.contains("verified the decoded"))
    }
    @Test fun exifUserCommentsCarryWebUiRecipesInJpegAndWebpWithoutGuessingFromSoftware() {
        val comment="ASCII\u0000\u0000\u0000".toByteArray()+recipe.toByteArray()
        val tiff=java.nio.ByteBuffer.allocate(44+comment.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put('I'.code.toByte());put('I'.code.toByte());putShort(42);putInt(8);putShort(1)
            putShort(34665.toShort());putShort(4);putInt(1);putInt(26);putInt(0)
            putShort(1);putShort(0x9286.toShort());putShort(7);putInt(comment.size);putInt(44);putInt(0);put(comment)
        }.array()
        val jpeg=ByteArrayOutputStream();jpeg.write(byteArrayOf(-1,-40,-1,-31));DataOutputStream(jpeg).writeShort(tiff.size+8);jpeg.write("Exif\u0000\u0000".toByteArray());jpeg.write(tiff);jpeg.write(byteArrayOf(-1,-39))
        assertEquals("Stable Diffusion WebUI",EmbeddedAiMetadata.inspect(jpeg.toByteArray()).metadata!!.recipes.single().generator)
        val payload=ByteArrayOutputStream();payload.write("WEBPEXIF".toByteArray());payload.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(tiff.size).array());payload.write(tiff);if(tiff.size%2==1)payload.write(0)
        payload.write("VP8 ".toByteArray());payload.write(byteArrayOf(2,0,0,0,0,0))
        val webp=ByteArrayOutputStream();webp.write("RIFF".toByteArray());webp.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(payload.size()).array());webp.write(payload.toByteArray())
        assertEquals("Stable Diffusion WebUI",EmbeddedAiMetadata.inspect(webp.toByteArray()).metadata!!.recipes.single().generator)
    }
    private fun png(key:String,value:String,zipped:Boolean):ByteArray {
        val out=ByteArrayOutputStream();out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(type:String,b:ByteArray){val d=DataOutputStream(out);d.writeInt(b.size);val t=type.toByteArray();d.write(t);d.write(b);d.writeInt(CRC32().apply {update(t);update(b)}.value.toInt())}
        val h=ByteArrayOutputStream();DataOutputStream(h).apply {writeInt(1);writeInt(1);write(byteArrayOf(8,6,0,0,0))};chunk("IHDR",h.toByteArray())
        val text=ByteArrayOutputStream();text.write(key.toByteArray());text.write(0)
        if(zipped){text.write(0);DeflaterOutputStream(text).use {it.write(value.toByteArray())}}else text.write(value.toByteArray())
        chunk(if(zipped)"zTXt"else "tEXt",text.toByteArray())
        val compressed=ByteArrayOutputStream();DeflaterOutputStream(compressed).use {it.write(byteArrayOf(0,20,40,60,-1))};chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
        return out.toByteArray()
    }
}
