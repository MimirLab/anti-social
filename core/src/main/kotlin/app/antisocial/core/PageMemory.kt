package app.antisocial.core

/** Recent native pages, keyed by the actual destination rather than just a tab. */
class PageMemory(private val limit: Int=24,private val maxCharacters: Int=4*1024*1024) {
    private data class Entry(val page: Page,val at: Long,val characters: Int,val complete: Boolean)
    private val entries=LinkedHashMap<String,Entry>(16,.75f,true)
    private fun key(target: Target)="${target.platform.id}:${target.kind}:${target.canonicalUrl}"
    fun get(target: Target)=entries[key(target)]?.page
    fun fresh(target: Target,now: Long=System.currentTimeMillis())=entries[key(target)]?.let {it.complete&&now-it.at in 0..300_000} ?: false
    fun put(target: Target,page: Page,complete: Boolean=true) {
        val characters=page.posts.sumOf {it.body.length+it.title.length+it.author.length+it.canonicalUrl.length+512+it.media.sumOf {m->m.url.length+256}}+(page.profile?.description?.length ?: 0)+page.collections.sumOf {it.name.length+it.detail.length+it.target.canonicalUrl.length+it.image.orEmpty().length+256}+page.accounts.sumOf {it.name.length+it.description.length+it.target.canonicalUrl.length+512}+512
        entries.remove(key(target))
        if(characters>maxCharacters)return
        entries[key(target)]=Entry(page,page.provenance?.fetchedAt ?: System.currentTimeMillis(),characters,complete)
        while(entries.size>limit||entries.values.sumOf {it.characters}>maxCharacters)entries.remove(entries.keys.first())
    }
    fun clear(){entries.clear()}
}
