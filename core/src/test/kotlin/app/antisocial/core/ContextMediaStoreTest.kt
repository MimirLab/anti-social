package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContextMediaStoreTest {
    private fun catalogBytes(english:ByteArray="reader".toByteArray(),extra:Boolean=false):ByteArray {
        val packs=JsonArray()
        fun pack(id:String,kind:String,language:String,body:ByteArray) {packs.add(JsonObject().apply {
            addProperty("id",id);addProperty("label",id);addProperty("kind",kind);addProperty("language",language);addProperty("revision","a".repeat(40))
            add("assets",JsonArray().apply {add(JsonObject().apply {addProperty("filename",if(kind=="OCR")"$language.traineddata" else "weights.bin");addProperty("url","https://models.example/$id.bin");addProperty("bytes",body.size);addProperty("sha256",AiTestFixtures.sha(body))})})
        })}
        pack("ocr-eng","OCR","eng",english);pack("speech","SPEECH","multilingual","speech".toByteArray());if(extra)pack("ocr-spa","OCR","spa","spanish".toByteArray())
        return JsonObject().apply {addProperty("schema","antisocial-context-media-v1");add("packs",packs)}.toString().toByteArray()
    }
    private fun catalog(bytes:ByteArray=catalogBytes())=ContextMediaCatalog.verify(bytes,AiTestFixtures.signature(bytes),AiTestFixtures.keys.public)
    private fun bundledCatalogBytes()=requireNotNull(javaClass.getResourceAsStream("/bundled-catalogs/context-media/catalog.json")){
        "The actual bundled media catalog must be included in the core test resources."
    }.use {it.readBytes()}
    @Test fun actualBundledCatalogValidatesBeforeAnyDownloadAndSelectsChineseIndependentlyOfItsId() {
        // Sign the exact app asset with the fixture certificate to exercise
        // production validation of every pack, rather than a synthetic schema.
        val actual=catalog(bundledCatalogBytes())
        val english=actual.pack(ContextMediaKind.OCR,"eng")
        assertEquals(4_113_088L,english.downloadBytes);assertEquals("eng.traineddata",english.assets.single().filename)
        val chinese=actual.pack(ContextMediaKind.OCR,"chi_sim")
        assertEquals("ocr-chi-sim",chinese.id);assertEquals("chi_sim.traineddata",chinese.assets.single().filename)
        val speech=actual.pack(ContextMediaKind.SPEECH)
        assertEquals("multilingual",speech.language);assertEquals(2,speech.assets.size)
    }
    @Test fun actualCatalogCannotUseLanguageSeparatorsOrPathsAsPackIds() {
        for(id in listOf("ocr-chi_sim","../outside")) {
            val changed=JsonParser.parseString(bundledCatalogBytes().toString(Charsets.UTF_8)).asJsonObject
            changed.getAsJsonArray("packs").first {it.asJsonObject["language"].asString=="chi_sim"}.asJsonObject.addProperty("id",id)
            val error=assertThrows(IllegalArgumentException::class.java){catalog(changed.toString().toByteArray())}
            assertTrue(error.message.orEmpty().contains("media pack id"))
        }
    }
    @Test fun signedCatalogRejectsTamperingAndTraversal() {
        val bytes=catalogBytes();assertEquals(2,catalog(bytes).packs.size)
        assertThrows(IllegalArgumentException::class.java){ContextMediaCatalog.verify(bytes+byteArrayOf(32),AiTestFixtures.signature(bytes),AiTestFixtures.keys.public)}
        val changed=JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        changed.getAsJsonArray("packs")[0].asJsonObject.getAsJsonArray("assets")[0].asJsonObject.addProperty("filename","../outside")
        assertThrows(IllegalArgumentException::class.java){catalog(changed.toString().toByteArray())}
    }
    @Test fun verifiedInstallSurvivesCatalogLanguageAdditionAndRemovalIsIndependent(): Unit=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val first=catalog();val gateway=AiTestFixtures.gateway(server);val store=ContextMediaStore(root,gateway,first)
            server.enqueue(MockResponse().setBody("reader"));val english=store.install(first.pack(ContextMediaKind.OCR));assertEquals("reader",File(english,"eng.traineddata").readText())
            server.enqueue(MockResponse().setBody("speech"));store.install(first.pack(ContextMediaKind.SPEECH))
            val newer=catalog(catalogBytes(extra=true));val restored=ContextMediaStore(root,gateway,newer)
            assertNotNull(restored.installed(newer.pack(ContextMediaKind.OCR)));restored.verify(newer.pack(ContextMediaKind.OCR))
            restored.remove(newer.pack(ContextMediaKind.SPEECH));assertNull(restored.installed(newer.pack(ContextMediaKind.SPEECH)));assertNotNull(restored.installed(newer.pack(ContextMediaKind.OCR)))
            assertEquals(2,server.requestCount)
        }}finally {root.deleteRecursively()}
    }
    @Test fun corruptDownloadNeverActivatesAndCanBeRetried(): Unit=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val catalog=catalog();val pack=catalog.pack(ContextMediaKind.OCR);val store=ContextMediaStore(root,AiTestFixtures.gateway(server),catalog)
            server.enqueue(MockResponse().setBody("broken"));assertThrows(IllegalStateException::class.java){runBlocking {store.install(pack)}};assertNull(store.installed(pack))
            server.enqueue(MockResponse().setBody("reader"));val installed=store.install(pack);assertNotNull(store.installed(pack))
            File(installed,"eng.traineddata").writeText("tamper");assertThrows(IllegalStateException::class.java){runBlocking {store.verify(pack)}}
        }}finally {root.deleteRecursively()}
    }
    @Test fun oldRevisionBytesRemainExplicitlyRemovable(): Unit=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val catalog=catalog();val pack=catalog.pack(ContextMediaKind.OCR);val store=ContextMediaStore(root,AiTestFixtures.gateway(server),catalog)
            File(root,"packs/${pack.id}-${"b".repeat(40)}/eng.traineddata").apply {parentFile!!.mkdirs();writeText("old download")}
            assertTrue(store.hasDownload(pack));assertNull(store.installed(pack));store.remove(pack);assertFalse(store.hasDownload(pack));assertEquals(0,server.requestCount)
        }}finally {root.deleteRecursively()}
    }
    @Test fun partialArtifactResumesOnlyAtValidatedRange(): Unit=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val catalog=catalog();val pack=catalog.pack(ContextMediaKind.OCR);val store=ContextMediaStore(root,AiTestFixtures.gateway(server),catalog)
            File(root,"staging/${pack.id}-${pack.revision}/eng.traineddata").apply {parentFile!!.mkdirs();writeText("rea")}
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range","bytes 3-5/6").setBody("der"));store.install(pack)
            assertEquals("bytes=3-",server.takeRequest().getHeader("Range"));assertNotNull(store.installed(pack))
        }}finally {root.deleteRecursively()}
    }
}
