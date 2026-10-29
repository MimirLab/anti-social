/* anti social, GPL-3.0-or-later. Literal caption tokens, unchanged match rules. */
package app.antisocial.core

/** Preserve the existing numeric-first expression. Kotlin's lazy findAll
 * creates another native Matcher for each result; a caption needs only one
 * word matcher and one whitespace matcher. Both belong to this call, so no
 * source text or mutable matcher is shared across operations or readers. */
internal object CrossPostTokens {
    private val words=Regex("(?:[<>!]=?|[≤≥≠≈=])?\\s*(?:[+\\-−\\p{Sc}]\\s*){0,3}\\p{N}+(?:[.,]\\p{N}+)*(?:\\s*[%‰\\p{Sc}])?|[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)?").toPattern()
    private val spaces=Regex("[\\p{Z}\\s]+").toPattern()
    fun read(value:String):List<String> {
        val matches=words.matcher(value.lowercase())
        val whitespace=spaces.matcher("")
        val tokens=ArrayList<String>()
        while(matches.find())tokens+=whitespace.reset(matches.group()).replaceAll("").replace('−','-')
        return tokens
    }
}
