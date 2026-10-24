/* AntiSocial, GPL-3.0-or-later. Actual Android XML/descriptor qualification. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class JatsArticleNativeTest {
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get()=(context.applicationContext as AntiSocialApplication).repo
    private val url="https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12345/fullTextXML"
    private val original="https://doi.org/10.1234/repair"
    private fun fixture()=InstrumentationRegistry.getInstrumentation().context.assets.open("jats-repair.xml").reader().use {it.readText()}
    private fun inputs()=File(context.cacheDir,"content-input").listFiles().orEmpty().filter {it.name.startsWith("article-")}.map {it.name}.toSet()
    private suspend fun read(xml:String=fixture())=repo.documentSandbox.article(Payload(xml,url,"application/xml",200),original,1234)
    @Test fun structuredArticlesUseThePermissionlessWorkerAndRetainUsefulMaterial():Unit=runBlocking {
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true");repo.awaitReady()
        val before=inputs();val document=read()
        assertEquals("10.1234/repair",document.publicationDoi);assertEquals("Alex Smith",document.author)
        assertTrue(document.text.contains("retention force of 12 N after twenty cycles"))
        assertTrue(document.text.contains("Part: Connector A; Force: 12 N"))
        assertTrue(document.text.contains("Connector orientation. The keyed edge"));assertFalse(document.truncated)
        assertEquals(setOf("https://doi.org/10.1234/guide","https://maker.example.org/manual"),document.references!!.map {it.url}.toSet())
        assertEquals("https://creativecommons.org/licenses/by/4.0/",document.access!!.license)
        val probe=File(context.cacheDir,"jats-probe-${System.nanoTime()}").apply {writeText(absolutePath)}
        try {val isolation=repo.documentSandbox.probe(probe);assertNotEquals(android.os.Process.myUid(),isolation.first);assertFalse(isolation.second);assertFalse(isolation.third)}finally {probe.delete()}
        assertEquals(before,inputs())
    }
    @Test fun externalDtdIsInertAndMalformedOrEntityPayloadsLeaveTheWorkerUsable():Unit=runBlocking {
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true");repo.awaitReady()
        val before=inputs()
        val external=fixture().replace("<article ","<!DOCTYPE article SYSTEM 'http://127.0.0.1:9/never-load.dtd'>\n<article ")
        assertEquals("10.1234/repair",read(external).publicationDoi)
        for(bad in listOf(fixture().dropLast(30),fixture().replace("<article ","<!DOCTYPE article [<!ENTITY stolen SYSTEM 'file:///data/user/0/app.antisocial.android/shared_prefs/antisocial.xml'>]><article "))) {
            val error=runCatching {read(bad)}.exceptionOrNull();assertTrue("$error",error is SourceFailure)
        }
        assertEquals("10.1234/repair",read().publicationDoi);assertEquals(before,inputs())
    }
    @Test fun retainedPublicFullPaperKeepsItsActualSectionsOnAndroid():Unit=runBlocking {
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialCapturedJats")=="true");repo.awaitReady()
        val gson=GsonBuilder().setPrettyPrinting().create()
        val payload=gson.fromJson(File(context.noBackupFilesDir,"jats-fixture/response.json").readText(),Payload::class.java)
        val original="https://doi.org/10.1038/s41586-024-07487-w"
        val start=System.nanoTime();val document=repo.documentSandbox.article(payload,original,1234);val ms=(System.nanoTime()-start)/1_000_000
        assertEquals("10.1038/s41586-024-07487-w",document.publicationDoi)
        assertEquals("Accurate structure prediction of biomolecular interactions with AlphaFold 3",document.title)
        assertFalse(document.truncated);assertTrue(document.text.length>60_000)
        assertTrue(document.blocks.any {it.kind==ContextArticleBlockKind.HEADING&&it.text.contains("Data availability",true)})
        val out=File(requireNotNull(context.getExternalFilesDir("validation")),"jats").apply {mkdirs()}
        File(out,"captured-document.json").writeText(gson.toJson(document))
        File(out,"captured-worker.json").writeText(gson.toJson(mapOf("api" to android.os.Build.VERSION.SDK_INT,"elapsedMs" to ms,"inputSha256" to MessageDigest.getInstance("SHA-256").digest(payload.text.toByteArray()).joinToString(""){"%02x".format(it)},"characters" to document.text.length,"blocks" to document.blocks.size,"truncated" to document.truncated,"scope" to "Retained public paper through the actual isolated worker; worker may already be warm.")))
    }
    @Test fun liveKnownPmcRouteWorksThroughTheGatewayWithoutOriginalAccess():Unit=runBlocking {
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveJats")=="true");repo.awaitReady()
        val gateway=repo.gateway;val policy=gateway.currentPolicy();val originalAllowed=gateway.originalFallback;val listener=gateway.onNetworkEvent
        val events=Collections.synchronizedList(mutableListOf<NetworkEvent>())
        try {
            gateway.configure(NetworkPolicy());gateway.originalFallback=false;gateway.onNetworkEvent={events+=it}
            val start=System.nanoTime()
            val original=args.getString("antisocialJatsOriginal") ?: "https://pmc.ncbi.nlm.nih.gov/articles/PMC11168924/"
            val result=PublicArticleResolver(gateway,extract=repo.documentSandbox::article).conditional(original,original)
            assertTrue(result is ContextArticleFetch.Ready);val document=(result as ContextArticleFetch.Ready).document
            assertEquals("https://www.ebi.ac.uk/europepmc/webservices/rest/PMC11168924/fullTextXML",document.finalUrl)
            assertEquals("10.1038/s41586-024-07487-w",document.publicationDoi);assertFalse(document.truncated)
            val out=File(requireNotNull(context.getExternalFilesDir("validation")),"jats").apply {mkdirs()}
            File(out,"live-route.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("original" to original,"elapsedMs" to (System.nanoTime()-start)/1_000_000,"originalAccess" to false,"events" to synchronized(events){events.toList()},"doi" to document.publicationDoi,"characters" to document.text.length,"scope" to "One dated direct Android gateway acquisition, not provider uptime or physical-phone performance.")))
        }finally {gateway.onNetworkEvent=listener;gateway.originalFallback=originalAllowed;gateway.configure(policy)}
    }
}
