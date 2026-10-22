/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** NewPipe Page.body is a byte array; Gson's numeric-array form can be much
 * larger than a real three-tab continuation. Compress the bounded wire only;
 * source/target/endpoint/cookie validation still applies to the decoded model. */
internal object YouTubeCursorWire {
    const val MAX_WIRE=24*1024
    const val MAX_RAW=256*1024
    class SizeLimit: IllegalArgumentException("YouTube continuation exceeds its size limit")
    fun encode(json: ByteArray): String {
        if(json.size>MAX_RAW)throw SizeLimit()
        val output=ByteArrayOutputStream()
        val compressor=Deflater(Deflater.BEST_SPEED)
        try {DeflaterOutputStream(output,compressor).use {it.write(json)}}finally {compressor.end()}
        val value="np2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        if(value.length>MAX_WIRE)throw SizeLimit()
        return value
    }
    fun decode(value: String): ByteArray {
        require(value.length<=MAX_WIRE&&(value.startsWith("np2:")||value.startsWith("np1:"))){"Invalid YouTube continuation"}
        val encoded=value.substring(4)
        require(encoded.isNotEmpty()&&encoded.all {it.isLetterOrDigit()&&it.code<128||it=='-'||it=='_'}){"Invalid YouTube continuation encoding"}
        val compressed=Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)==encoded){"Invalid YouTube continuation encoding"}
        // Existing small pre-compression cursors remain readable. The old raw
        // limit stays in force for that format; all newly emitted cursors np2.
        if(value.startsWith("np1:")){if(compressed.size>18*1024)throw SizeLimit();return compressed}
        val inflater=Inflater()
        val output=ByteArrayOutputStream()
        try {
            inflater.setInput(compressed)
            val chunk=ByteArray(4096)
            while(!inflater.finished()) {
                val count=inflater.inflate(chunk)
                if(output.size()+count>MAX_RAW)throw SizeLimit()
                if(count>0)output.write(chunk,0,count)
                else require(inflater.finished()){"Incomplete or unsupported YouTube continuation compression"}
            }
            require(inflater.remaining==0){"YouTube continuation contains trailing compressed data"}
        }catch(e: DataFormatException){throw IllegalArgumentException("Invalid YouTube continuation compression",e)}finally {inflater.end()}
        return output.toByteArray()
    }
}
