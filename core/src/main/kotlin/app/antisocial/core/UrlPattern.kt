/* anti social, GPL-3.0-or-later. RE2/J: BSD-3-Clause, Google contributors. */
package app.antisocial.core

/** Preserve Java's Unicode case folding and unsupported constructs. Only the
 * qualified ASCII subset takes the linear path. The fallback's character-read
 * budget applies on the desktop JVM. Android materializes the CharSequence for
 * native ICU; that wrapper alone does not establish a native work deadline. */
internal class UrlPattern(value:String,ignoreCase:Boolean,linear:Boolean) {
    class WorkLimit:IllegalArgumentException("URL cleaning exceeded its work allowance")
    private class Budget(var left:Long)
    private class Text(private val value:String,private val budget:Budget,private val start:Int=0,private val end:Int=value.length):CharSequence {
        override val length get()=end-start
        override fun get(index:Int):Char {if(--budget.left<0)throw WorkLimit();return value[start+index]}
        override fun subSequence(startIndex:Int,endIndex:Int):CharSequence=Text(value,budget,start+startIndex,start+endIndex)
        override fun toString()=value.substring(start,end)
    }
    private val legacy=if(ignoreCase)Regex(value,RegexOption.IGNORE_CASE)else Regex(value)
    // Compilation alone is not equivalence: RE2 has different Unicode classes
    // and case-folding. The bundled ASCII syntax excludes these constructs.
    private val re2=if(linear&&value.all {it.code<128}&&listOf("\\b","\\B","\\w","\\W","\\s","\\S","\\p","\\P","&&","[:","(?i","(?u","(?d","(?s","(?m","(?x").none {it in value})
        runCatching {com.google.re2j.Pattern.compile(value,if(ignoreCase)com.google.re2j.Pattern.CASE_INSENSITIVE else 0)}.getOrNull()else null
    private val required=if(re2!=null)requiredLiteral(value)else null
    val linearAvailable get()=re2!=null
    private fun bounded(value:String)=Text(value,Budget(100_000L+value.length.toLong()*128))
    fun contains(value:String,ascii:Boolean,folded:String=value.lowercase(java.util.Locale.ROOT)):Boolean=if(ascii&&re2!=null) {
        if(required!=null&&!folded.contains(required))false else re2.matcher(value).find()
    }else legacy.containsMatchIn(bounded(value))
    fun target(value:String,ascii:Boolean):String? {
        if(ascii&&re2!=null){val m=re2.matcher(value);return if(m.find()&&m.groupCount()>0)m.group(1)else null}
        return legacy.find(bounded(value))?.groupValues?.getOrNull(1)
    }
    fun remove(value:String,ascii:Boolean):String=if(ascii&&re2!=null)re2.matcher(value).replaceAll("")else legacy.replace(bounded(value),"")

    companion object {
        /** Necessary substring, not a guess from a provider/domain name. Ignore
         * whole groups/classes; a root alternation or unfamiliar escape disables
         * pruning. Optional atoms never become required, and repeated atoms are
         * barriers to joining neighbouring runs. Full RE2 matching still follows.
         * This saves hundreds of NFA allocations per ordinary URL. */
        private fun requiredLiteral(pattern:String):String? {
            val runs=ArrayList<String>();val run=StringBuilder();var depth=0;var at=0
            fun flush(){if(run.length>=3)runs+=run.toString().lowercase(java.util.Locale.ROOT);run.setLength(0)}
            while(at<pattern.length) {
                val c=pattern[at++]
                if(c=='\\') {
                    if(at==pattern.length)return null
                    val escaped=pattern[at++]
                    if(escaped.isLetterOrDigit())return null
                    if(depth==0)run.append(escaped)
                    continue
                }
                if(c=='[') {
                    if(depth==0)flush()
                    if(pattern.getOrNull(at)=='^')at++
                    if(pattern.getOrNull(at)==']')at++
                    var closed=false
                    while(at<pattern.length){val k=pattern[at++];if(k=='\\'){at++;if(at>pattern.length)return null}else if(k==']'){closed=true;break}}
                    if(!closed)return null
                    continue
                }
                if(c=='('){if(depth==0)flush();depth++;continue}
                if(c==')'){depth--;if(depth<0)return null;continue}
                if(depth>0)continue
                when(c) {
                    '|'->return null
                    '*','?'->{if(run.isNotEmpty())run.setLength(run.length-1);flush()}
                    '+'->flush()
                    '{'->{
                        val end=pattern.indexOf('}',at);if(end<0)return null
                        val bounds=pattern.substring(at,end);val min=bounds.substringBefore(',').toIntOrNull() ?: return null
                        if(min==0&&run.isNotEmpty())run.setLength(run.length-1)
                        flush();at=end+1
                    }
                    '^','$','.'->flush()
                    else->run.append(c)
                }
            }
            if(depth!=0)return null
            flush()
            return runs.filter {it !in setOf("http","https","://")}.maxByOrNull {it.length}
        }
    }
}
