/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonElement
import java.math.BigDecimal

/** Optional publisher-supplied runtime only. Reading this never probes a media URL. */
internal object MediaDuration {
    // Reject sentinel values and corrupt metadata, including overflowing unit conversions.
    // A year is a generous sanity limit for a single social-media attachment.
    private val maximumMs=BigDecimal("31622400000")
    private fun number(value: JsonElement?): String?=value?.takeIf {it.isJsonPrimitive&&!it.asJsonPrimitive.isBoolean}?.asString
    fun seconds(value: JsonElement?): Long?=seconds(number(value))
    fun seconds(value: String?): Long?=parse(value,BigDecimal(1000))
    fun milliseconds(value: JsonElement?): Long?=parse(number(value),BigDecimal.ONE)
    /** Alternative encodings may repeat a URL; retain the chosen presentation and its runtime. */
    fun distinctMedia(media: List<Media>): List<Media> {
        val known=media.filter {it.type=="video"&&it.durationMs!=null}.groupBy {it.url}
        return media.distinctBy {it.url}.map {item->
            if(item.type=="video"&&item.durationMs==null)item.copy(durationMs=known[item.url]?.firstOrNull()?.durationMs)else item
        }
    }
    private fun parse(value: String?,unit: BigDecimal): Long? {
        val raw=value?.trim()?.takeIf {it.length in 1..64} ?: return null
        val supplied=raw.toBigDecimalOrNull() ?: return null
        if(supplied.signum()<=0)return null
        // Check before multiplying: huge decimal exponents need no large allocation.
        if(supplied<BigDecimal.ONE.divide(unit)||supplied>maximumMs.divide(unit))return null
        val milliseconds=supplied.multiply(unit).toLong()
        return milliseconds.takeIf {it>0}
    }
}
