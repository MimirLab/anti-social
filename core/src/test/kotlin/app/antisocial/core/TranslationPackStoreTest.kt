package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranslationPackStoreTest {
    private val host="https://firefox-settings-attachments.cdn.mozilla.net"
    private fun bytes(languages:List<String> = listOf("es","fr","de"))=JsonObject().apply {
        addProperty("schema","antisocial-translation-v1");add("packs",JsonArray().apply {languages.forEach {language->add(JsonObject().apply {
            addProperty("language",language);addProperty("version","1.0");addProperty("revision",AiTestFixtures.sha(language.toByteArray()))
            add("assets",JsonArray().apply {for(role in listOf("model","lex","vocab"))add(JsonObject().apply {
                addProperty("role",role);addProperty("filename","$role.bin");addProperty("url","$host/$language/$role.bin");addProperty("bytes",role.length);addProperty("sha256",AiTestFixtures.sha(role.toByteArray()))
            })})
        })}})
    }.toString().toByteArray()
    private fun catalog(raw:ByteArray=bytes())=TranslationCatalog.verify(raw,AiTestFixtures.signature(raw),AiTestFixtures.keys.public)
    private fun gateway(server:MockWebServer):NetworkGateway {
        val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
        return NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false,rewrittenUrl=if(url.startsWith("$host/"))server.url(url.removePrefix(host)).toString()else null)},true)
    }
    private fun enqueue(server:MockWebServer){for(role in listOf("model","lex","vocab"))server.enqueue(MockResponse().setBody(role))}
    @Test fun realCatalogAuthenticatesEveryPinnedStableAndroidDirectionIncludingSeparateChineseVocabularies() {
        val raw=javaClass.getResourceAsStream("/bundled-catalogs/translation/catalog.json")!!.readBytes();val catalog=catalog(raw)
        assertEquals(57,catalog.packs.size);assertNotNull(catalog.pack("es"));assertNotNull(catalog.pack("ja"));assertNotNull(catalog.pack("ko"))
        assertEquals(setOf("model","lex","srcvocab","trgvocab"),catalog.pack("zh-Hant")!!.assets.map {it.role}.toSet())
        assertThrows(IllegalArgumentException::class.java){TranslationCatalog.verify(raw+byteArrayOf(32),AiTestFixtures.signature(raw),AiTestFixtures.keys.public)}
    }
    @Test fun signedManifestsCannotNameAnUnapprovedHostOrEscapePrivateStorage() {
        for((field,value) in listOf("filename" to "../other","url" to "https://unapproved.example/model")) {
            val json=JsonParser.parseString(bytes().toString(Charsets.UTF_8)).asJsonObject
            json.getAsJsonArray("packs")[0].asJsonObject.getAsJsonArray("assets")[0].asJsonObject.addProperty(field,value)
            assertThrows(IllegalArgumentException::class.java){catalog(json.toString().toByteArray())}
        }
    }
    @Test fun installsAreVerifiedThroughTheGatewayAndExplicitDownloadsSurviveCacheClearing()=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val cat=catalog();val store=TranslationPackStore(root,gateway(server),cat)
            enqueue(server);val es=cat.pack("es")!!;store.install(es,true,1024)
            enqueue(server);val fr=cat.pack("fr")!!;store.install(fr,false,1024)
            assertEquals(6,server.requestCount);assertTrue(store.protected(es));assertFalse(store.protected(fr))
            store.prune(0,true);assertNotNull(store.installed(es));assertNull(store.installed(fr));assertEquals(0L,store.automaticBytes())
            store.remove(es);assertFalse(store.hasDownload(es));assertEquals(0L,store.localBytes())
        }}finally {root.deleteRecursively()}
    }
    @Test fun failedIntegrityNeverActivatesAndTheFollowingAttemptCanResumeItsOtherAssets()=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val cat=catalog();val es=cat.pack("es")!!;val store=TranslationPackStore(root,gateway(server),cat)
            server.enqueue(MockResponse().setBody("wrong"));try {store.install(es,false,1024);fail()}catch(_:IllegalStateException){}
            assertNull(store.installed(es));enqueue(server);val dir=store.install(es,false,1024);assertEquals("model",File(dir,"model.bin").readText())
            File(dir,"model.bin").writeText("wrong");try {store.verify(es);fail()}catch(_:IllegalStateException){}
            assertNull(store.installed(es))
        }}finally {root.deleteRecursively()}
    }
    @Test fun stagedAndCompletedAutomaticBytesShareAnActualByteBudget()=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val cat=catalog();val store=TranslationPackStore(root,gateway(server),cat)
            val old=File(root,"staging/obsolete-${"a".repeat(64)}/partial.bin").apply {parentFile!!.mkdirs();writeBytes(ByteArray(900))}
            enqueue(server);val es=cat.pack("es")!!;store.install(es,false,600)
            assertFalse(old.exists());assertTrue(store.automaticBytes()<=600)
            enqueue(server);store.install(cat.pack("fr")!!,false,600)
            enqueue(server);store.install(cat.pack("de")!!,false,600)
            assertTrue(store.automaticBytes()<=600);assertNull(store.installed(es))
        }}finally {root.deleteRecursively()}
    }
    @Test fun expiredAutomaticPacksArePurgedButKeptPacksAndTheirIndependentRemovalRemainVisible()=runBlocking {
        val root=AiTestFixtures.directory()
        try {MockWebServer().use {server->server.start();val cat=catalog();val store=TranslationPackStore(root,gateway(server),cat);val es=cat.pack("es")!!;val fr=cat.pack("fr")!!
            enqueue(server);store.install(es,true,1024);enqueue(server);store.install(fr,false,1024)
            File(store.directory(es),".complete").setLastModified(1);File(store.directory(fr),".complete").setLastModified(1)
            store.prune(1024);assertNotNull(store.installed(es));assertNull(store.installed(fr));assertTrue(store.hasDownload(es))
        }}finally {root.deleteRecursively()}
    }
}
