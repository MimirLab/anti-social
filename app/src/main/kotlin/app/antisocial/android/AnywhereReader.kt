/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class AnywhereDraft(val text: String="",val target: AnywhereComment?=null,val editing: Boolean=false,
    val working: Boolean=false,val message: String?=null,val checkRequired: Boolean=false)
data class AnywhereRead(val post: Post,val url: String?=null,val order: CommentOrder=CommentOrder.NEWEST,
    val page: AnywherePage?=null,val loading: Boolean=false,val error: String?=null,val owner: String?=null,val draft: AnywhereDraft=AnywhereDraft())

/** Read sessions cancel on tab/edition changes. Explicit writes run once in the
 * viewmodel scope and retain their own URL/draft even if the reader moves on. */
class AnywhereReader(private val routes: DarwinRoutes,private val scope: CoroutineScope,private val access: AnywhereAccess) {
    val state=MutableStateFlow<AnywhereRead?>(null)
    private val drafts=linkedMapOf<String,AnywhereDraft>()
    private val loaded=linkedMapOf<String,AnywherePage>()
    private var job: Job?=null
    private var generation=0
    fun open(post: Post,order: CommentOrder) {
        close()
        prune()
        try {val url=AnywhereUrls.root(post,routes);state.value=AnywhereRead(post,url,order,draft=drafts[url] ?: AnywhereDraft());refresh()}
        catch(e: Exception){state.value=AnywhereRead(post,error=e.message ?: "This post has no safe original address.")}
    }
    fun close(){generation++;job?.cancel();job=null;state.value=null}
    fun clear(){close();loaded.clear();access.clear()}
    private fun prune(){val now=System.currentTimeMillis();loaded.entries.removeAll {it.key!=state.value?.url&&now-it.value.fetchedAt>5*60_000}}
    fun captures(roots: List<Post>): List<CapturedThread> {prune();return roots.mapNotNull {root->
        val url=runCatching {AnywhereUrls.root(root,routes)}.getOrNull() ?: return@mapNotNull null
        loaded[url]?.let {CapturedThread(root,emptyList(),it.order.name,it.fetchedAt,it.limited,commentAnywhere=it)}
    }}
    fun draft(text: String){val current=state.value ?: return;if(current.draft.working)return;updateDraft(current.url ?: return,current.draft.copy(text=text.take(8000),message=null))}
    fun target(comment: AnywhereComment?,editing: Boolean=false) {
        val current=state.value ?: return;if(current.draft.working)return
        updateDraft(current.url ?: return,current.draft.copy(target=comment,editing=editing,text=if(editing)comment?.text.orEmpty()else current.draft.text,message=null))
    }
    private fun updateDraft(url: String,draft: AnywhereDraft) {
        drafts.remove(url);drafts[url]=draft
        while(drafts.size>20) {val key=drafts.entries.firstOrNull {!it.value.working}?.key ?: break;drafts.remove(key)}
        state.update {if(it?.url==url)it.copy(draft=draft)else it}
    }
    fun refresh(more: Boolean=false,parent: String?=null) {
        val current=state.value ?: return;val url=current.url ?: return
        if(current.loading||current.draft.working)return
        val bound=generation;state.value=current.copy(loading=true,error=null)
        val limit=if(more)(current.page?.limit ?: 30).plus(30).coerceAtMost(100)else current.page?.limit ?: 30
        job=scope.launch {
            try {
                val result=access.read(url,current.order,force=current.page!=null||current.error!=null||parent!=null,limit=limit,parentId=parent)
                val page=if(parent==null)result else {
                    fun replace(nodes: List<AnywhereComment>): List<AnywhereComment> = nodes.map {node->if(node.id==parent)node.copy(children=result.items,hasMore=result.limited)else node.copy(children=replace(node.children))}
                    val old=requireNotNull(current.page)
                    old.copy(items=replace(old.items),fetchedAt=result.fetchedAt)
                }
                // A malicious/deep subtree cannot grow the in-memory UI beyond
                // the same parser budget as an initial response.
                val bounded=CommentAnywhereClient.parse(com.google.gson.Gson().toJson(mapOf("items" to page.items)),url,page.order,page.limit,page.fetchedAt)
                    .copy(limited=page.limited)
                val owner=owner()
                if(bound==generation) {
                    loaded.remove(url);loaded[url]=bounded;while(loaded.size>12||loaded.values.sumOf {it.flattened().sumOf {entry->entry.first.text.length*2L+1024}}>2*1024*1024)loaded.remove(loaded.keys.first())
                    val draft=drafts[url] ?: current.draft
                    updateDraft(url,draft.copy(checkRequired=false,message=if(draft.checkRequired)"Check the thread for your earlier action before trying again."else draft.message))
                    state.update {it?.copy(page=bounded,loading=false,error=null,owner=owner)}
                }
            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;if(bound==generation)state.update {it?.copy(loading=false,error="CommentAnywhere timed out. Try again later.")}}
            catch(e: Exception){if(bound==generation)state.update {it?.copy(loading=false,error=e.message ?: "CommentAnywhere is unavailable.")}}
        }
    }
    fun publish() {
        val current=state.value ?: return;val draft=current.draft
        if(draft.text.isBlank()||draft.text.toByteArray().size>8000)return
        val action=if(draft.editing)AnywhereAction.EDIT else if(draft.target!=null)AnywhereAction.REPLY else AnywhereAction.POST
        mutate(action,draft.target?.id,text=draft.text)
    }
    fun mutate(action: AnywhereAction,id: String?=null,value: Int?=null,text: String="") {
        val current=state.value ?: return;val url=current.url ?: return;val draft=current.draft
        if(draft.working||draft.checkRequired||current.loading||current.error!=null||current.page==null)return
        updateDraft(url,draft.copy(working=true,message=null,checkRequired=true))
        scope.launch {
            try {
                access.mutate(url,current.order,action,text,id,value)
                val clearText=action in setOf(AnywhereAction.POST,AnywhereAction.REPLY,AnywhereAction.EDIT)
                updateDraft(url,if(clearText)AnywhereDraft(message="Published to the shared discussion.")else draft.copy(working=false,message="Shared discussion updated.",checkRequired=false))
                if(state.value?.url==url){val owner=owner();state.update {it?.copy(owner=owner)};refresh()}
            }catch(e: CancellationException){updateDraft(url,draft.copy(message="The action could not be confirmed. Refresh and check the thread before trying again.",checkRequired=true));throw e}
            catch(e: Exception){updateDraft(url,draft.copy(message="The action could not be confirmed. Your draft is kept. Refresh and check the thread before trying again.",checkRequired=true))}
        }
    }
    fun forget() {
        val current=state.value ?: return;if(current.draft.working)return
        scope.launch {try {access.forget();state.update {it?.copy(owner=null)}}catch(e: CancellationException){throw e}catch(e: Exception){state.update {it?.copy(error="The local identity could not be reset.")}}}
    }
    private suspend fun owner(): String?=try {access.owner()}catch(e: CancellationException){throw e}catch(e: Exception){null}
}
