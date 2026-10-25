/* anti social, GPL-3.0-or-later. JNI bytes only; NetworkGateway owns transport. */
package app.antisocial.android

import androidx.annotation.Keep
import app.antisocial.core.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

internal class NativePublicReadFactory:PublicReadEngineFactory {
    private val capabilities by lazy {
        try {PublicReadNative.supported()}catch(_:LinkageError){0}
    }
    override fun supports(cohort:Int)=cohort in 0..1&&capabilities and (1 shl cohort)!=0
    override fun open(cohort:Int,host:String,port:Int,path:String,headers:List<Pair<String,String>>,limit:Int,
        verify:(PublicReadPeer)->Boolean,decoded:(Int)->Unit):PublicReadByteEngine {
        check(supports(cohort)){"Native public preview profile is unavailable"}
        val callbacks=PublicReadCallbacks(verify,decoded)
        val handle=PublicReadNative.create(cohort,host,port,path,headers.flatMap {listOf(it.first,it.second)}.toTypedArray(),limit,callbacks)
        if(handle<=0)throw IOException("Native public preview could not start")
        return NativePublicRead(handle,callbacks,limit)
    }
}

@Keep
internal class PublicReadCallbacks(private val verify:(PublicReadPeer)->Boolean,private val decoded:(Int)->Unit) {
    fun verifyPeer(chain:Array<ByteArray>,protocol:String,cipher:String,ocsp:ByteArray,sct:ByteArray)=verify(PublicReadPeer(chain.toList(),protocol,cipher,ocsp,sct))
    fun decodedBytes(count:Int):Boolean {require(count in 1..32768);decoded(count);return true}
}

private class NativePublicRead(private var handle:Long,@Suppress("unused") private val callbacks:PublicReadCallbacks,private val limit:Int):PublicReadByteEngine {
    override fun step()=PublicReadNative.step(handle)
    override fun feed(bytes:ByteArray,count:Int)=PublicReadNative.feed(handle,bytes,count)
    override fun output(bytes:ByteArray)=PublicReadNative.output(handle,bytes)
    override fun result():PublicReadResult {
        val bytes=PublicReadNative.result(handle)
        if(bytes.size>limit+32784)throw IOException("Native response exceeded its allowance")
        return DataInputStream(ByteArrayInputStream(bytes)).use {input->
            val status=input.readInt();require(status in 200..599)
            val count=input.readInt();require(count in 0..128)
            var total=0
            fun field(max:Int):String {
                val length=input.readInt();require(length in 0..max&&length<=input.available())
                total+=length+16;require(total<=32768)
                return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
            }
            val headers=List(count){field(256) to field(8192)}
            val length=input.readInt();require(length in 0..limit&&input.available()==length)
            PublicReadResult(status,headers,ByteArray(length).also(input::readFully))
        }
    }
    override fun close(){if(handle!=0L){PublicReadNative.dispose(handle);handle=0}}
}

@Keep
internal object PublicReadNative {
    init {System.loadLibrary("antisocial_public_read")}
    @JvmStatic external fun supported():Int
    @JvmStatic external fun versions():String
    @JvmStatic external fun create(cohort:Int,host:String,port:Int,path:String,headers:Array<String>,limit:Int,callbacks:PublicReadCallbacks):Long
    @JvmStatic external fun step(handle:Long):Int
    @JvmStatic external fun feed(handle:Long,bytes:ByteArray,count:Int)
    @JvmStatic external fun output(handle:Long,bytes:ByteArray):Int
    @JvmStatic external fun result(handle:Long):ByteArray
    @JvmStatic external fun dispose(handle:Long)
}
