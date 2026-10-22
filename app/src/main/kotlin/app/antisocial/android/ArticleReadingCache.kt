package app.antisocial.android

import java.io.Closeable
import java.io.File

/** Ordinary PDF viewing copies only. Saved/Context evidence has other owners.
 * An open reader holds a lease; explicit clear finishes when it closes. */
internal class ArticleReadingCache(val directory:File,private val budget:()->Long,private val clock:()->Long=System::currentTimeMillis,private val onChange:()->Unit={}) {
    private val active=mutableMapOf<String,Int>()
    private val clearOnRelease=mutableSetOf<String>()
    val maxFileBytes:Long get()=minOf(32L*1024*1024,budget().coerceAtLeast(4L*1024*1024))
    fun file(url:String)=File(directory,Repository.hash(url)+".pdf")
    @Synchronized fun lease(url:String):Closeable {
        directory.mkdirs();val name=file(url).name;active[name]=(active[name] ?: 0)+1
        var closed=false
        return Closeable {synchronized(this){if(!closed){closed=true
            val remaining=(active[name] ?: 1)-1
            if(remaining>0)active[name]=remaining else {active.remove(name);if(clearOnRelease.remove(name))File(directory,name).delete()}
            cleanup();onChange()
        }}}
    }
    @Synchronized fun cleanup(clear:Boolean=false,reserve:Long=0) {
        var bytes=reserve;var count=0;val now=clock()
        directory.listFiles().orEmpty().filter {it.isFile}.sortedWith(compareByDescending<File>{active.containsKey(it.name)}.thenByDescending {it.lastModified()}).forEach {file->
            if(active.containsKey(file.name)) {bytes+=file.length();count++;if(clear)clearOnRelease+=file.name}
            else if(clear||now-file.lastModified() !in 0..24*60*60_000L||count>=3||bytes+file.length()>budget())file.delete()
            else {bytes+=file.length();count++}
        }
    }
    @Synchronized fun bytes()=directory.listFiles().orEmpty().filter {it.isFile}.sumOf {it.length()}
}
