package app.antisocial.core

import com.google.gson.Gson
import java.io.Closeable

interface RequestFilter { fun check(url: String, sourceUrl: String, type: String): FilterDecision }
object NativeBridge {
    init { System.getProperty("antisocial.native.path")?.let { System.load(it) } ?: System.loadLibrary("antisocial_filter") }
    external fun create(rules: String): Long
    external fun check(handle: Long, url: String, source: String, type: String): String
    external fun release(handle: Long)
    external fun compiled(handle: Long): ByteArray
    external fun restore(bytes: ByteArray): Long
    external fun restoreFile(path: String,offset: Long,length: Long): Long
}
class NativeFilter private constructor(private var handle: Long) : RequestFilter, Closeable {
    constructor(rules: String):this(NativeBridge.create(rules))
    init {require(handle!=0L){"Filter engine could not load"}}
    private val gson = Gson()
    @Synchronized override fun check(url: String, sourceUrl: String, type: String): FilterDecision {
        check(handle != 0L) { "Filtering is not ready" }
        return gson.fromJson(NativeBridge.check(handle,url,sourceUrl,type),FilterDecision::class.java)
    }
    @Synchronized fun serialize(): ByteArray = NativeBridge.compiled(handle)
    @Synchronized override fun close() { if (handle != 0L) NativeBridge.release(handle); handle = 0L }
    companion object {
        fun restore(bytes: ByteArray): NativeFilter = NativeFilter(NativeBridge.restore(bytes))
        fun restoreFile(file: java.io.File): NativeFilter=restoreFileRange(file.absolutePath,0,-1)
        fun restoreFileRange(path: String,offset: Long,length: Long): NativeFilter=NativeFilter(NativeBridge.restoreFile(path,offset,length))
    }
}
