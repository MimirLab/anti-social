/* AntiSocial, GPL-3.0-or-later. Bounded menu documents through NetworkGateway only. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal class LocalMenuReader(private val repo:Repository) {
    companion object {
        private val readers=Semaphore(2)
        private val inFlight=ConcurrentHashMap<String,CompletableDeferred<LocalExtraction>>()
        private val activeFiles=mutableSetOf<String>()
        fun cleanup(cache:File,clear:Boolean=false)=synchronized(activeFiles) {
            File(cache,"restaurant-menus").listFiles()?.filter {it.path !in activeFiles&&(clear||System.currentTimeMillis()-it.lastModified()>3600000)}?.forEach {it.delete()}
            if(activeFiles.isEmpty())File(cache,"menu-pdf-scratch").listFiles()?.filter {clear||System.currentTimeMillis()-it.lastModified()>3600000}?.forEach {it.delete()}
        }
    }
    suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,b:LocalBusiness):LocalExtraction {
        val key="${System.identityHashCode(repo)}|${b.id}|${source.url}|${source.menuBranch}"
        val result=CompletableDeferred<LocalExtraction>()
        inFlight.putIfAbsent(key,result)?.let {return it.await()}
        try {return readers.withPermit {readMenu(source,query,b)}.also {result.complete(it)}}
        catch(e:Throwable){result.completeExceptionally(e);throw e}
        finally {inFlight.remove(key,result)}
    }
    private suspend fun readMenu(source:LocalPublisherSource,query:LocalDiscoveryQuery,b:LocalBusiness):LocalExtraction {
        require(source.kind==LocalSourceKind.MENU&&source.businessId==b.id)
        require(LocalMenuRouting.known(b).any {it.url==source.url}||b.websites.any {it==source.url}){"This menu was not offered by the restaurant's location record"}
        val url=LocalMenuRouting.request(source.url)
        if(url.substringBefore('?').endsWith(".pdf",true))return pdf(source,b,url)
        val raw=repo.gateway.extractorDocument(url,sourceUrl=source.discoveredFrom ?: source.url,maxBytes=LocalStructuredData.MAX_DOCUMENT.toLong(),
            headers=mapOf("Accept" to "text/html,application/ld+json,application/json;q=0.9,*/*;q=0.5"))
        if(raw.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"This published menu is unavailable",httpStatus=raw.status)
        val content=raw.headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value?.firstOrNull().orEmpty()
        if(content.contains("application/pdf",true))return pdf(source,b,raw.finalUrl)
        val at=System.currentTimeMillis()
        if(content.contains("json",true)||raw.text.trimStart().startsWith("{")) {
            val menus=LocalMenus.structured(raw.text,raw.finalUrl,at,b,true,source.label,source.menuBranch==true,query.area).map {it.copy(sourceUrl=source.url)}
            if(menus.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"No menu for this restaurant location was provided")
            return LocalExtraction(businesses=listOf(b.copy(menus=LocalMenus.merge(b.menus.orEmpty()+menus),checkedAt=at)))
        }
        return html(Payload(raw.text,raw.finalUrl,content,raw.status),source,query,b,at)
    }
    internal fun html(payload:Payload,source:LocalPublisherSource,query:LocalDiscoveryQuery,b:LocalBusiness,at:Long):LocalExtraction {
        // A published ordering catalogue can include an optional login dialog.
        // Require actual location-scoped menu records before accepting it;
        // article login heuristics must not discard already public dishes.
        val typed=LocalStructuredData.html(payload.text,payload.finalUrl,at,query.area,b)
        val catalogue=LocalMenuRouting.discover(payload.text,payload.finalUrl,b,typed.businesses,at,linked=true,branchProof=source.menuBranch==true,area=query.area)
        if(catalogue.businesses.any {owner->owner.menus.orEmpty().any {it.checkedAt==at&&it.items.isNotEmpty()}})return catalogue
        val data=LocalDocumentWashing.extract(payload,at,query.area,b,menuSource=true,menuBranch=source.menuBranch==true)
        val ours=data.businesses.filter {LocalDiscoveryMerge.sameBusiness(b,it)}.map {it.copy(menus=it.menus.orEmpty().filter {m->m.checkedAt==at&&m.sourceUrl==payload.finalUrl}.takeIf {m->m.isNotEmpty()})}
        val onward=data.sources.filter {it.kind==LocalSourceKind.MENU&&it.url!=source.url&&it.url!=payload.finalUrl}
        if(ours.none {it.menus.orEmpty().isNotEmpty()}&&onward.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"No readable menu for this location was supplied")
        return data.copy(businesses=ours,sources=onward)
    }
    private suspend fun pdf(source:LocalPublisherSource,b:LocalBusiness,url:String):LocalExtraction {
        // Networkless isolated PDF worker; disposable binaries are removed on
        // success, rejection, timeout and cancellation. Saved text has its own owner.
        val dir=File(repo.context.cacheDir,"restaurant-menus").apply {mkdirs()}
        cleanup(repo.context.cacheDir)
        check(dir.usableSpace>128L*1024*1024){"Storage is low"}
        val file=synchronized(activeFiles){File.createTempFile("menu-",".pdf",dir).also {activeFiles+=it.path}}
        try {
            repo.gateway.download(url,source.discoveredFrom ?: source.url,file,type="document",maxBytes=4L*1024*1024,purpose=FetchPurpose.SUPPLEMENTAL)
            val magic=file.inputStream().use {input->val bytes=ByteArray(5);if(input.read(bytes)==5)bytes.toString(Charsets.US_ASCII)else ""}
            require(magic=="%PDF-"){"This menu did not provide a PDF document"}
            val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val chunk=ByteArray(16384);while(true){currentCoroutineContext().ensureActive();val n=input.read(chunk);if(n<0)break;digest.update(chunk,0,n)}}
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            val at=System.currentTimeMillis()
            val old=b.menus.orEmpty().firstOrNull {it.sourceUrl==source.url&&it.sourceHash==hash}
            val menu=if(old!=null)old.copy(checkedAt=at)else {
                val pages=mutableListOf<String>();var total=1;var partial=false
                for(page in 0 until 6) {
                    currentCoroutineContext().ensureActive()
                    val result=repo.mediaTools.pdfTextPage(file,page)
                    total=result.pageCount
                    if(!result.available){if(pages.isEmpty())throw SourceFailure(HealthStatus.UNSUPPORTED,result.reason ?: "This menu has no embedded text");partial=true;break}
                    if(result.text.isNotBlank())pages+=result.text
                    partial=partial||result.truncated
                    if(page+1>=total||pages.sumOf {it.length}>=LocalMenus.MAX_TEXT)break
                }
                LocalMenus.document(pages.joinToString("\n\n"),source.url,at,b,source.label,hash,partial||total>6,source.menuBranch==true)
                    ?: throw SourceFailure(HealthStatus.EMPTY,"This menu has no readable embedded text")
            }
            return LocalExtraction(businesses=listOf(b.copy(menus=LocalMenus.merge(b.menus.orEmpty()+menu),checkedAt=at)))
        }finally {synchronized(activeFiles){file.delete();activeFiles-=file.path}}
    }
}
