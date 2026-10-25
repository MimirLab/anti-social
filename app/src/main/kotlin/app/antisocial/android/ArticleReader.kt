/* AntiSocial, GPL-3.0-or-later. Shared Darwin public-source acquisition; native reader. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

internal data class ArticleReadingState(val url:String,val loading:Boolean=true,val document:ContextArticleEvidence?=null,
    val pdf:File?=null,val pages:Int=0,val access:ArticleAccess?=null,val error:String?=null)

internal class ArticleReaderController(internal val repo:Repository,private val scope:CoroutineScope) {
    val state=MutableStateFlow<ArticleReadingState?>(null)
    private val routes=DarwinArticleRoutes()
    internal var fetch:suspend (String)->ContextArticleFetch={repo.evidenceReader.articleConditional(it,it,ContextArticleValidators(),purpose=FetchPurpose.DELIBERATE)}
    internal var fetchAnnouncement:suspend (String,LocalEvent)->ContextArticleFetch={url,event->repo.evidenceReader.articleConditional(url,url,ContextArticleValidators(),purpose=FetchPurpose.DELIBERATE,announcement=event)}
    internal var pdfLoad:suspend (ContextArticleFetch.PublicPdf)->Pair<File,Int> = {loadPdf(it)}
    private var job:Job?=null
    private val recent=linkedMapOf<String,Pair<Long,ContextArticleEvidence>>()
    fun canOpen(url:String)=routes.canRead(url)
    fun close(){job?.cancel();state.value=null}
    fun open(url:String)=openRequest(url,null)
    fun open(url:String,announcement:LocalEvent)=openRequest(url,announcement)
    private fun openRequest(url:String,announcement:LocalEvent?) {
        job?.cancel();state.value=ArticleReadingState(url)
        val mode=repo.gateway.currentPolicy();val key="$mode|$url|${announcement?.let(LocalAnnouncementReading::key).orEmpty()}"
        job=scope.launch {
            try {
                val cached=recent[key]?.takeIf {System.currentTimeMillis()-it.first in 0..15*60_000L}?.second ?: repo.evidenceReader.publicArticle(url,announcement)
                val result=if(cached!=null)ContextArticleFetch.Ready(cached)else withContext(Dispatchers.IO){if(announcement==null)fetch(url)else fetchAnnouncement(url,announcement)}
                ensureActive();check(repo.gateway.currentPolicy()==mode){"Network mode changed · reopen this article"}
                when(result) {
                    is ContextArticleFetch.Ready->{
                        recent.remove(key);recent[key]=System.currentTimeMillis() to result.document
                        while(recent.size>8)recent.remove(recent.keys.first())
                        state.value=ArticleReadingState(url,false,document=result.document,access=result.document.access)
                    }
                    is ContextArticleFetch.NotModified->error("The source returned no article body")
                    is ContextArticleFetch.PublicPdf->{
                        val lease=repo.articleReadingCache.lease(result.url)
                        try {
                        val loaded=withContext(Dispatchers.IO){pdfLoad(result)}
                        ensureActive();check(repo.gateway.currentPolicy()==mode){"Network mode changed · reopen this document"}
                        state.value=ArticleReadingState(url,false,pdf=loaded.first,pages=loaded.second,access=result.access)
                        repo.scheduleCleanup()
                        awaitCancellation()
                        }finally {lease.close()}
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception) {
                state.value=ArticleReadingState(url,false,error=e.message?.take(200) ?: "No readable public copy was available")
            }
        }
    }
    private suspend fun loadPdf(result:ContextArticleFetch.PublicPdf):Pair<File,Int> {
        val cache=repo.articleReadingCache
        val directory=cache.directory.apply {mkdirs()}
        val file=cache.file(result.url)
        val now=System.currentTimeMillis()
        cache.cleanup(reserve=cache.maxFileBytes)
        check(directory.usableSpace>=256L*1024*1024){"Storage is low"}
        try {
            if(!file.isFile||file.length()==0L||file.length()>cache.maxFileBytes||now-file.lastModified() !in 0..15*60_000L) {
                repo.gateway.download(result.url,result.url,file,type="document",maxBytes=cache.maxFileBytes,purpose=FetchPurpose.DELIBERATE)
            }
            val pages=repo.contentSandbox.pdfCount(file)
            require(pages in 1..2000){"This document has too many pages for the reader"}
            file.setLastModified(now)
            return file to pages
        }catch(e:Exception){file.delete();repo.evidenceReader.articles.reject(result.originalUrl,result.url);throw e}
    }
}
