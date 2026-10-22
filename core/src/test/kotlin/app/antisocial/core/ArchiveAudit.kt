package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.io.File

/** Explicit opt-in diagnostic; never runs as a test or on app startup. */
fun main(): Unit=runBlocking {
    val output=File(System.getProperty("antisocial.archiveEvidence","/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/archive-history-2026-10-16/reader-live"));output.mkdirs()
    fun asset(name: String)=ArchiveReader::class.java.classLoader.getResourceAsStream(name)!!
    val cleaner=UrlCleaner(JsonParser.parseReader(asset("clean-links.json").reader()).asJsonObject)
    val routes=DarwinRoutes(JsonParser.parseReader(asset("darwin-routes.json").reader()).asJsonObject,cleaner)
    NativeFilter.restore(asset("network-rules.compiled").readBytes()).use {filter->
        val gateway=NetworkGateway(cleaner,filter)
        var response=0
        val responses=mutableListOf<Map<String,Any>>()
        gateway.onTextResponse={payload->
            val file="response-${++response}.txt";File(output,file).writeText(payload.text)
            responses+=mapOf("file" to file,"finalUrl" to payload.finalUrl,"contentType" to payload.contentType,"status" to payload.status,"bytes" to payload.text.toByteArray().size)
        }
        val events=mutableListOf<NetworkEvent>()
        gateway.onNetworkEvent={event->synchronized(events){events+=event}}
        val post=Post("post:20",Platform.X,"https://x.com/jack/status/20","jack","Current comparison placeholder: this audit tests archive retrieval, not whether this public post was edited.")
        val started=System.nanoTime()
        var failure: String?=null
        val recovery=System.getProperty("antisocial.archiveRecovery")=="true"
        val version=try {if(recovery)ArchiveReader(gateway,routes).recover(requireNotNull(routes.recognize(post.canonicalUrl)))else ArchiveReader(gateway,routes).oldest(post)}catch(e: SourceFailure){failure="${e.status}: ${e.message}";null}
        val record=mapOf("mode" to if(recovery)"unavailable-post recovery"else "older-version comparison","failure" to failure,"responses" to responses,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"version" to version,"events" to synchronized(events){events.toList()},"note" to "Read-only direct Gateway audit; no account, cookies, JavaScript or archive submission. A recovered capture is not proof this post was edited or that it matches the unavailable current edition. Tor archive availability not tested by this audit.")
        File(output,"result.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(record))
        println("Archive reader: ${version?.provider ?: "no usable capture"}; body=${version?.snapshot?.body?.length ?: 0} characters; requests=$response; evidence=$output")
        gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
    }
}
