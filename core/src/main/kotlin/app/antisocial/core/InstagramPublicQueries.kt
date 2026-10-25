/* anti social, GPL-3.0-or-later. Validated, source-scoped query metadata only. */
package app.antisocial.core

/** This bank never supplies content or grants request permission. Admit only
 * after parsing usable selected content and checking its actual Gateway session. */
internal class InstagramPublicQueries(private val clock:()->Long,private val sessionFor:(String)->NetworkSession,
    private val maxEntries:Int=16,private val ttl:Long=10*60_000L) {
    private data class Key(val source:Source,val target:Target,val session:NetworkSession,
        val operation:String,val defaultId:String,val variables:String)
    private data class Entry(val query:InstagramPublicGraphQl.Query,val created:Long)
    private val entries=linkedMapOf<Key,Entry>()
    private val document=Regex("[0-9]{10,32}")
    private val provider=Regex("__relay_internal__pv__[A-Za-z0-9_]{1,100}relayprovider")
    init {require(maxEntries in 1..32&&ttl in 1..10*60_000L)}
    private fun scope(source:Source,target:Target,session:NetworkSession,expected:InstagramPublicGraphQl.Query):Key? {
        if(sessionFor(source.root)!==session)return null
        val variables=expected.variables.toString().takeIf {it.length<=8192} ?: return null
        return Key(source,target,session,expected.name,expected.id,variables)
    }
    private fun trim(session:NetworkSession) {
        val now=clock()
        entries.entries.removeAll {it.key.session!==session||now<it.value.created||now-it.value.created !in 0 until ttl}
    }
    private fun copy(query:InstagramPublicGraphQl.Query)=query.copy(variables=query.variables.deepCopy())
    @Synchronized fun resolve(source:Source,target:Target,session:NetworkSession,expected:InstagramPublicGraphQl.Query):InstagramPublicGraphQl.Query? {
        val key=scope(source,target,session,expected) ?: return null
        trim(session)
        return entries[key]?.query?.let(::copy)
    }
    @Synchronized fun remember(source:Source,target:Target,session:NetworkSession,expected:InstagramPublicGraphQl.Query,query:InstagramPublicGraphQl.Query) {
        val key=scope(source,target,session,expected) ?: return
        trim(session)
        if(query==expected||query.name!=expected.name||!document.matches(query.id)||query.variables.size()>32||query.variables.toString().length>8192)return
        // Discovery can add only bounded boolean Relay-provider declarations;
        // the selected identity, order, window and all other inputs stay fixed.
        if(expected.variables.entrySet().any {(name,value)->!query.variables.has(name)||!provider.matches(name)&&query.variables.get(name)!=value})return
        if(query.variables.entrySet().any {(name,value)->
            if(provider.matches(name))!value.isJsonPrimitive||!value.asJsonPrimitive.isBoolean
            else !expected.variables.has(name)||expected.variables.get(name)!=value
        })return
        val loggedIn=query.variables.get("__relay_internal__pv__PolarisIsLoggedInrelayprovider")
        if(loggedIn!=null&&loggedIn.asBoolean)return
        if(entries[key]?.query==query)return // Reuse cannot renew absolute expiry.
        entries.remove(key);entries[key]=Entry(copy(query),clock())
        while(entries.size>maxEntries)entries.remove(entries.keys.first())
    }
    @Synchronized fun forget(source:Source,target:Target,session:NetworkSession,expected:InstagramPublicGraphQl.Query,failed:InstagramPublicGraphQl.Query) {
        val key=scope(source,target,session,expected) ?: return
        trim(session)
        if(entries[key]?.query==failed)entries.remove(key)
    }
    @Synchronized internal fun size()=entries.size
}
