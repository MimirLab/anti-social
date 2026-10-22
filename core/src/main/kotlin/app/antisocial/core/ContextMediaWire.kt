/* AntiSocial, GPL-3.0-or-later. Native wire decoding always invokes Kotlin constructors. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Native extraction has no file hash or Android slice metadata. Gson's unsafe
 * allocation would turn omitted non-null defaults into null. Decode required
 * wire fields explicitly, and attach app-owned provenance only after decoding. */
object ContextMediaWire {
    private fun json(bytes:ByteArray):JsonObject {
        require(bytes.size in 1..256*1024){"The media result exceeds its processing plan."}
        return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    }
    private fun JsonObject.string(key:String,default:String?=null):String {
        val value=get(key)
        return if(value==null||value.isJsonNull)requireNotNull(default){"Missing media result field: $key"}else value.asString
    }
    private fun JsonObject.boolean(key:String,default:Boolean=false)=get(key)?.takeUnless {it.isJsonNull}?.asBoolean ?: default
    private fun JsonObject.score(key:String,max:Float=1f):Float=get(key).asFloat.also {require(it.isFinite()&&it in 0f..max){"Invalid media result score."}}
    fun ocr(bytes:ByteArray,scaled:Boolean=false):OcrResult {
        val raw=json(bytes);val width=raw["width"].asInt;val height=raw["height"].asInt
        require(width>0&&height>0&&width.toLong()*height<=12_000_000){"Invalid image text dimensions."}
        val rows=raw.getAsJsonArray("lines");require(rows.size()<=512)
        val lines=rows.map {entry->val row=entry.asJsonObject
            OcrLine(row.string("text"),row.score("confidence",100f),row["left"].asInt,row["top"].asInt,row["right"].asInt,row["bottom"].asInt).also {
                require(it.left in 0..width&&it.right in it.left..width&&it.top in 0..height&&it.bottom in it.top..height)
            }
        }
        return OcrResult(raw.string("text"),lines,raw.string("language"),width,height,raw.score("meanConfidence",100f),raw.boolean("uncertain"),
            truncated=raw.boolean("truncated"),sourceSha256="",engine=raw.string("engine","Tesseract 5 · fast language model"),scaled=scaled)
    }
    fun transcript(bytes:ByteArray):TranscriptResult {
        val raw=json(bytes);val duration=raw["durationMs"].asLong;require(duration in 1..1_200_000)
        val rows=raw.getAsJsonArray("segments");require(rows.size()<=1024)
        val segments=rows.map {entry->val row=entry.asJsonObject
            TranscriptSegment(row["startMs"].asLong,row["endMs"].asLong,row.string("text"),row.score("meanTokenProbability"),row.score("noSpeechProbability"),row.boolean("uncertain")).also {
                require(it.startMs>=0&&it.endMs>=it.startMs)
            }
        }
        return TranscriptResult(raw.string("text"),segments,raw.string("language",""),duration,raw.boolean("hasSpeech"),raw.boolean("uncertain"),
            truncated=raw.boolean("truncated"),sourceSha256="",engine=raw.string("engine","Whisper base multilingual · Q5_1 · Silero VAD"),
            startOffsetMs=0,nextOffsetMs=duration,complete=true,sourceDurationMs=duration)
    }
}
