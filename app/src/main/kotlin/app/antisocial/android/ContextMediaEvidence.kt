/* AntiSocial, GPL-3.0-or-later. Reuse published text before local media inference. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import java.io.File

internal data class ContextMediaEvidenceResult(val documents: List<ContextDocument> = emptyList(),val limited: Boolean=false,
    val message: String="Available media text read",val more: Boolean=false,val work: Map<String,String> = emptyMap())

internal class ContextMediaEvidence(private val repo: Repository) {
    // Narrow deterministic fixture seams; production always uses the gateway
    // and the isolated local media worker.
    internal var downloadFile: suspend (String,String,File,Long)->Unit={url,source,file,limit->repo.gateway.download(url,source,file,maxBytes=limit);Unit}
    internal var sourceHash: suspend (File)->String={repo.mediaTools.sourceSha256(it)}
    internal var readAudio: suspend (File,Long,Long)->TranscriptResult={file,start,duration->repo.mediaTools.transcribe(file,start,duration)}
    internal var readPdf: suspend (File,Int,String,Boolean)->ContextPdfPageResult={file,page,language,allow->repo.mediaTools.pdfPage(file,page,language,allow)}
    internal var availableImage: suspend (String)->File?={repo.availableMedia(it)}
    internal var loadImage: suspend (String,String)->File={url,source->repo.media(url,source)}
    internal var readImage: suspend (File,String)->OcrResult={file,language->repo.mediaTools.ocr(file,language)}
    private val store get()=repo.contextReader.store
    private val reader get()=repo.evidenceReader
    private fun metadata(root: String,vararg details: Pair<String,String>)=mapOf("root" to root)+details.toMap()
    private fun directory(owner: String)=File(repo.context.cacheDir,"context-work/$owner").apply {mkdirs()}
    private fun allowHeavy(background: Boolean)=!background||repo.contextReader.automaticAllowed(true)
    private suspend fun localFile(owner: String,key: String,url: String,source: String,maxBytes: Long=64L*1024*1024,refresh: Boolean=false): File {
        val file=File(directory(owner),Repository.hash(key))
        if(!refresh&&file.isFile&&file.length()>0)return file
        val available=(repo.prefs.cacheMb.toLong()*1024*1024-repo.contextReader.temporaryBytes()-store.logicalBytes(false)).coerceAtLeast(0)
        val limit=minOf(maxBytes,available)
        check(limit>=1024*1024){"Temporary media reached the cache allowance · progress saved"}
        check(repo.context.filesDir.usableSpace>maxBytes+256L*1024*1024){"Not enough free space for temporary media · text Context is retained"}
        val part=File(file.path+".part")
        try {downloadFile(url,source,part,limit);check(part.renameTo(file)){"Could not retain the temporary media"};return file}finally {part.delete()}
    }
    suspend fun read(record: ContextRecord,stream: ContextStream,background: Boolean): ContextMediaEvidenceResult {
        val index=stream.work["index"]?.toIntOrNull() ?: 0
        val media=record.snapshot.media
        if(index>=media.size)return ContextMediaEvidenceResult(message="Available media text read")
        val item=media[index]
        val first=record.snapshot.roots.first()
        val origin=record.snapshot.roots.firstOrNull {post->post.media.any {it.url==item.url}} ?: first
        val root=CrossPosts.originalKey(origin)
        val key="media:${Repository.hash(item.url)}"
        val result=when {
            first.platform==Platform.YOUTUBE->youtube(record.id,first.canonicalUrl,stream,background)
            YouTubeUrls.videoId(item.url)!=null->youtube(record.id,item.url,stream,background)
            item.type=="image"->image(record.id,item,key,root,origin.contentSourceUrl,background)
            else->audio(record.id,item.audioUrl ?: item.url,item.audioMimeType ?: item.mimeType,key,root,origin.contentSourceUrl,stream.work,background)
        }
        if(result.more)return result.copy(limited=stream.limited||result.limited,work=result.work+mapOf("index" to index.toString()))
        // Once a slice is complete its content receipts remain with the post;
        // advancing the media inventory cannot erase a previous limitation.
        val limited=stream.limited||result.limited
        val next=if(first.platform==Platform.YOUTUBE)media.size else index+1
        return result.copy(limited=limited,more=next<media.size,work=mapOf("index" to next.toString()),message=if(limited&&next>=media.size&&!result.limited)"Some media could not be fully read"else result.message)
    }
    private suspend fun image(owner: String,item: Media,key: String,root: String,source: String,background: Boolean): ContextMediaEvidenceResult {
        val id="ocr:$key"
        val old=store.document(owner,id)?.document
        val recognized=old?.metadata?.get("recognized")!="false"
        // An empty updated description is still a receipt: removing supplied
        // alt text must invalidate an older description's findings too.
        val alt=item.description.trim()
        val description=ContextDocument("description:$key",Repository.hash(alt),alt,url=source,kind="article",fetchedAt=System.currentTimeMillis(),metadata=metadata(root,"medium" to "Source-supplied image description"))
        val state=repo.mediaTools.state.value
        fun earlier(message: String)=ContextMediaEvidenceResult(listOfNotNull(description,old?.takeIf {recognized}),true,message+if(old!=null&&recognized)" · earlier local text kept"else "")
        if(!state.ocrEnabled||state.languages.isEmpty())return earlier("Image text tools are off")
        if(!allowHeavy(background))return earlier("Image refresh is waiting for your processing conditions")
        val language=state.languages.first();val resource="image:$key"
        var changed=false
        val lease=Repository.hash(item.url);repo.lease(lease,true)
        try {
            // The ordinary/saved image cache is never overwritten by Context.
            // First recognition reuses displayed bytes; later checks validate
            // a bounded, independently expiring source copy once per cycle.
            val file=if(old==null)availableImage(item.url) ?: loadImage(item.url,source)
                else localFile(owner,resource,item.url,source,16L*1024*1024,refresh=true)
            val sha=sourceHash(file)
            if(old!=null&&recognized&&old.metadata["sourceSha256"]==sha&&old.metadata["language"]==language)
                return ContextMediaEvidenceResult(listOf(description,old),old.metadata["truncated"]=="true","Reused verified image text")
            changed=old!=null&&old.metadata["sourceSha256"]!=sha
            if(changed) {
                // Withdraw current support before inference: if recognition
                // fails, old image facts cannot be revived as current evidence.
                store.ingest(owner,listOf(ContextDocument(id,Repository.hash("pending-image|$sha|$language"),"",url=source,kind="article",metadata=metadata(root,"resource" to resource,"sourceSha256" to sha,"language" to language,"recognized" to "false"))))
            }
            repo.ai.releaseForMedia()
            val result=readImage(file,language)
            check(result.sourceSha256==sha){"The image changed while it was being read"}
            val doc=ContextDocument(id,Repository.hash("image-v2|$sha|${result.engine}|$language"),result.text,url=source,kind="article",fetchedAt=file.lastModified().takeIf {it>0} ?: System.currentTimeMillis(),
                metadata=metadata(root,"medium" to "Local image text recognition","language" to result.language,"uncertain" to result.uncertain.toString(),"mediaUrl" to item.url,"sourceSha256" to sha,
                    "resource" to resource,"recognized" to "true","truncated" to result.truncated.toString(),"scaled" to result.scaled.toString()))
            return ContextMediaEvidenceResult(listOf(description,doc),result.truncated,if(result.text.isBlank())"No readable image text found"else if(result.uncertain)"Image text read · recognition may contain errors"else "Image text read")
        }catch(e:CancellationException){throw e}
        catch(_:Exception){return if(changed)ContextMediaEvidenceResult(listOf(description),true,"Image changed · its current text could not be read")else earlier("Image refresh unavailable")}
        finally {repo.lease(lease,false)}
    }
    suspend fun youtube(owner: String,url: String,stream: ContextStream,background: Boolean): ContextMediaEvidenceResult {
        val video=YouTubeUrls.videoId(url) ?: return ContextMediaEvidenceResult(limited=true,message="The video identity is unavailable")
        val id="captions:youtube:$video"
        val cached=store.document(owner,id)?.document
        if(cached!=null&&System.currentTimeMillis()-cached.fetchedAt<repo.contextReader.preferences.state.value.refreshMinutes*60_000L)
            return ContextMediaEvidenceResult(listOf(cached),cached.metadata["truncated"]=="true","Reused video captions")
        if(stream.work["offset"]!=null) {
            // A resumed transcription already established that captions were
            // unavailable. Continue its durable slice before another discovery.
            return youtubeAudio(owner,url,video,stream,background)
        }
        return when(val result=reader.captions(url,repo.prefs.originalFallback)) {
            is ContextCaptionResult.Available->{
                val text=result.cues.joinToString("\n"){cue->"[${timestamp(cue.startMs)}] ${cue.text}"}
                val doc=ContextDocument(id,Repository.hash(result.language+result.automatic+text),text,result.title,url=url,kind="article",fetchedAt=System.currentTimeMillis(),
                    metadata=metadata(stream.originKey,"medium" to if(result.automatic)"Automatically generated captions"else "Published captions","language" to result.language,"truncated" to result.truncated.toString(),"videoUrl" to url,"timestamps" to "true"))
                ContextMediaEvidenceResult(listOf(doc),result.truncated,if(result.truncated)"Caption reading window reached"else "Video captions read")
            }
            is ContextCaptionResult.Unavailable->{
                if(result.reason==ContextEvidenceFailure.ABSENT)youtubeAudio(owner,url,video,stream,background)
                else ContextMediaEvidenceResult(limited=true,message=result.message)
            }
        }
    }
    private suspend fun youtubeAudio(owner: String,url: String,video: String,stream: ContextStream,background: Boolean): ContextMediaEvidenceResult {
        if(!repo.mediaTools.state.value.speechEnabled)return ContextMediaEvidenceResult(limited=true,message="No published captions · local speech tools are not enabled")
        if(!allowHeavy(background))return ContextMediaEvidenceResult(limited=true,message="Transcription is waiting for your processing conditions",work=stream.work)
        val descriptor=reader.audio(url,repo.prefs.originalFallback) ?: return ContextMediaEvidenceResult(limited=true,message="No readable public audio source was available",work=stream.work)
        if(descriptor.durationMs==null)return ContextMediaEvidenceResult(limited=true,message="This source does not expose a finite video duration",work=stream.work)
        return audio(owner,descriptor.url,descriptor.mimeType,"youtube:$video",stream.originKey,url,stream.work,background)
    }
    private suspend fun audio(owner: String,url: String,mimeType: String?,key: String,root: String,source: String,work: Map<String,String>,background: Boolean): ContextMediaEvidenceResult {
        if(!repo.mediaTools.state.value.speechEnabled)return ContextMediaEvidenceResult(limited=true,message="Speech tools are not enabled",work=work)
        if(!allowHeavy(background))return ContextMediaEvidenceResult(limited=true,message="Transcription is waiting for your processing conditions",work=work)
        val offset=work["offset"]?.toLongOrNull() ?: 0L
        val id="speech:$key:$offset"
        val cycle=(store.record(owner)?.cycleStartedAt ?: 0L).toString()
        // A new successful check validates the complete source once. Resumed
        // slices use that local edition; a re-fetched edition must match SHA.
        val file=localFile(owner,key,url,source,refresh=work["audioCycle"]!=cycle)
        val sourceSha=sourceHash(file)
        val previous=store.document(owner,id)?.document
        val priorSha=work["audioSha"] ?: previous?.metadata?.get("sourceSha256")
            ?: store.documents(owner).firstOrNull {it.document.metadata["resource"]==key}?.document?.metadata?.get("sourceSha256")
        val validated=mapOf("audioCycle" to cycle,"audioSha" to sourceSha)
        if(priorSha!=null&&priorSha!=sourceSha) {
            store.invalidateMedia(owner,key,sourceSha)
            return ContextMediaEvidenceResult(message="Audio changed · restarting its transcription",more=true,work=validated+mapOf("offset" to "0"))
        }
        if(previous!=null&&previous.metadata["sourceSha256"]==sourceSha) {
            val next=previous.metadata["nextOffset"]?.toLongOrNull() ?: offset
            val complete=previous.metadata["complete"]=="true"
            check(complete||next>offset){"Saved transcription did not advance · refresh to retry"}
            return ContextMediaEvidenceResult(listOf(previous),limited=previous.metadata["truncated"]=="true",message="Reused saved transcription",more=!complete,work=if(complete)emptyMap()else validated+mapOf("offset" to next.toString()))
        }
        repo.ai.releaseForMedia()
        val result=readAudio(file,offset,120_000)
        check(result.sourceSha256==sourceSha){"The audio changed while it was being read · progress retained for retry"}
        check(result.complete||result.nextOffsetMs>offset){"Transcription did not advance · progress saved"}
        val text=if(result.hasSpeech)result.segments.joinToString("\n"){"[${timestamp(it.startMs)}] ${it.text}"}else "[No speech detected in ${timestamp(offset)}–${timestamp(result.nextOffsetMs)}]"
        val doc=ContextDocument(id,Repository.hash(result.sourceSha256+result.engine+offset+result.nextOffsetMs),text,url=source,kind=if(result.hasSpeech)"article"else "comment",fetchedAt=System.currentTimeMillis(),
            metadata=metadata(root,"medium" to "Local speech transcription","language" to result.language,"uncertain" to result.uncertain.toString(),"sourceSha256" to result.sourceSha256,
                "nextOffset" to result.nextOffsetMs.toString(),"complete" to result.complete.toString(),"timestamps" to "true","mimeType" to mimeType.orEmpty(),"resource" to key,"truncated" to result.truncated.toString()))
        val progress=if(result.sourceDurationMs>0)"Transcribing ${timestamp(result.nextOffsetMs)} of ${timestamp(result.sourceDurationMs)}"else "Transcribed through ${timestamp(result.nextOffsetMs)}"
        return ContextMediaEvidenceResult(listOf(doc),limited=result.truncated, message=if(result.complete)"Video speech read"else progress,
            more=!result.complete,work=if(result.complete)emptyMap()else validated+mapOf("offset" to result.nextOffsetMs.toString()))
    }
    suspend fun pdf(owner: String,url: String,stream: ContextStream,background: Boolean): ContextMediaEvidenceResult {
        val page=stream.work["page"]?.toIntOrNull() ?: 0
        val resource="pdf:$url"
        val cycle=(store.record(owner)?.cycleStartedAt ?: 0L).toString()
        val file=localFile(owner,resource,url,url,32L*1024*1024,refresh=stream.work["pdfCycle"]!=cycle)
        val sha=sourceHash(file)
        val priorSha=stream.work["pdfSha"] ?: store.documents(owner).firstOrNull {it.document.metadata["resource"]==resource}?.document?.metadata?.get("sourceSha256")
        val validated=mapOf("pdfSha" to sha,"pdfCycle" to cycle)
        // Detect a shorter replacement before asking the renderer for a page
        // index that only existed in the previous edition.
        if(priorSha!=null&&priorSha!=sha) {
            store.invalidateMedia(owner,resource,sha)
            return ContextMediaEvidenceResult(message="Document changed · restarting its reading",more=true,work=validated+mapOf("page" to "0"))
        }
        val language=repo.mediaTools.state.value.languages.firstOrNull() ?: "eng"
        val id="pdf:${Repository.hash(url)}:$page"
        val cached=store.document(owner,id)?.document
        val cachedCount=cached?.metadata?.get("pageCount")?.toIntOrNull()
        if(cached!=null&&cached.metadata["sourceSha256"]==sha&&cachedCount!=null&&page<cachedCount&&
            (cached.metadata["ocr"]!="true"||cached.metadata["language"]==language)) {
            return ContextMediaEvidenceResult(listOf(cached),limited=stream.limited||cached.metadata["truncated"]=="true",message="Reused document page ${page+1} of $cachedCount",more=page+1<cachedCount,work=validated+mapOf("page" to (page+1).toString()))
        }
        repo.ai.releaseForMedia()
        val result=readPdf(file,page,language,allowHeavy(background))
        check(result.sourceSha256==sha){"The document changed while it was being read · refresh to retry"}
        if(!result.available)return ContextMediaEvidenceResult(limited=true,message=result.reason ?: "This document page could not be read",work=validated+mapOf("page" to page.toString()))
        val doc=ContextDocument(id,Repository.hash("pdf-page-v2|$sha|$page|${result.ocr}|$language|${result.text}"),result.text,url="$url#page=${page+1}",kind="article",fetchedAt=file.lastModified(),
            metadata=metadata(stream.originKey,"medium" to if(result.ocr)"Recognized document page"else "Document text","page" to (page+1).toString(),"pageCount" to result.pageCount.toString(),"ocr" to result.ocr.toString(),"language" to language,
                "uncertain" to result.uncertain.toString(),"sourceSha256" to result.sourceSha256,"resource" to resource,"truncated" to result.truncated.toString()))
        // Even a genuinely blank page gets a no-text receipt, preventing an
        // unchanged scanned page from repeatedly invoking recognition.
        return ContextMediaEvidenceResult(listOf(doc),limited=stream.limited||result.truncated,message="Read page ${page+1} of ${result.pageCount}",more=page+1<result.pageCount,work=validated+mapOf("page" to (page+1).toString()))
    }
    private fun timestamp(ms: Long): String {val seconds=ms.coerceAtLeast(0)/1000;return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)else "%d:%02d".format(seconds/60,seconds%60)}
}
