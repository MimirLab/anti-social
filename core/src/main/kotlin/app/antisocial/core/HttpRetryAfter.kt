/* anti social, GPL-3.0-or-later. Shared bounded HTTP wait parsing. */
package app.antisocial.core

internal object HttpRetryAfter {
    fun parse(header:String?,now:Long=System.currentTimeMillis()):Long? {
        val value=header?.trim()?.takeIf {it.isNotEmpty()&&it.length<=128} ?: return null
        val day=86_400_000L
        if(value.all {it in '0'..'9'}) {
            val significant=value.trimStart('0').ifEmpty {"0"}
            return (significant.toLongOrNull() ?: return day).coerceAtMost(86_400L)*1000L
        }
        for(pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz","EEEE, dd-MMM-yy HH:mm:ss zzz","EEE MMM d HH:mm:ss yyyy")) {
            val parser=java.text.SimpleDateFormat(pattern,java.util.Locale.US).apply {isLenient=false;timeZone=java.util.TimeZone.getTimeZone("GMT")}
            val position=java.text.ParsePosition(0)
            val date=parser.parse(value,position) ?: continue
            if(position.index==value.length)return (date.time-now).coerceIn(0L,day)
        }
        return null
    }
}
