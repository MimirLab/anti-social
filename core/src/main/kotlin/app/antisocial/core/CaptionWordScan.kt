/* anti social, GPL-3.0-or-later. Call-owned literal caption matchers. */
package app.antisocial.core

/** The two existing caption expressions, with no new token normalization.
 * Each call owns one matcher. Bounds include the first disqualifying word;
 * they are not a truncated caption or evidence of a complete source. */
internal object CaptionWordScan {
    private val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*").toPattern()
    private val simpleWords=Regex("[\\p{L}\\p{N}]+").toPattern()

    fun read(value:String,maximum:Int):List<String> {
        require(maximum>=0)
        if(maximum==0)return emptyList()
        val matcher=words.matcher(value)
        val result=ArrayList<String>()
        while(result.size<maximum&&matcher.find())result+=matcher.group()
        return result
    }

    fun count(value:String,maximum:Int,simple:Boolean=false):Int {
        require(maximum>=0)
        if(maximum==0)return 0
        val matcher=(if(simple)simpleWords else words).matcher(value)
        var count=0
        while(count<maximum&&matcher.find())count++
        return count
    }
}
