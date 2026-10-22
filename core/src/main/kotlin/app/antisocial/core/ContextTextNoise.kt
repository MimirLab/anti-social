/* AntiSocial, GPL-3.0-or-later. Cheap text triage, never source deletion. */
package app.antisocial.core

import java.util.Locale

/** A deliberately small, reviewed decoration set. Directions, flags, numbers,
 * prohibitions, warnings, fire, checkmarks and ambiguous irony remain data.
 * Unknown/partly supported joined sequences stay intact. */
internal object ContextDecorations {
    private val bases="😀😃😄😁😆😅😂🤣😊😍🥰😎🤩🥳👍👏🙌🎉🎊✨💖💗💓💕💞💘💝❤".codePoints().toArray().toSet()
    private fun modifier(code:Int)=code in 0xfe0e..0xfe0f||code in 0x1f3fb..0x1f3ff
    fun without(text:String):String {
        if(text.codePoints().noneMatch {it in bases})return text
        val out=StringBuilder(text.length);var at=0
        while(at<text.length) {
            val first=text.codePointAt(at);var end=at+Character.charCount(first);var removable=first in bases
            while(end<text.length&&modifier(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end))
            while(end<text.length&&text.codePointAt(end)==0x200d) {
                val next=end+1
                if(next>=text.length){removable=false;end=next;break}
                val base=text.codePointAt(next);removable=removable&&base in bases;end=next+Character.charCount(base)
                while(end<text.length&&modifier(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end))
            }
            if(removable)out.append(' ')else out.append(text,at,end)
            at=end
        }
        return out.toString()
    }
}

internal object ContextTextNoise {
    // Length only bounds a cheap exact phrase lookup. A short fact, number,
    // correction, link, question or unknown phrase still receives review.
    private val reactions=setOf("great","great job","great work","good job","good work","well done","nice","awesome","amazing","brilliant","wonderful",
        "love this","love it","so true","exactly","this","based","good point","thanks for sharing","thanks for the update","thank you so much",
        "idiot","idiots","moron","morons","you are an idiot","fucking idiots","learn to read")
    private val oldReaction=Regex("(?:lol|lmao|rofl|thanks|thank\\s+you|agreed|\\+1)[.!?,…]*",RegexOption.IGNORE_CASE)
    private val punctuation=setOf(Character.CONNECTOR_PUNCTUATION.toInt(),Character.DASH_PUNCTUATION.toInt(),Character.START_PUNCTUATION.toInt(),Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),Character.FINAL_QUOTE_PUNCTUATION.toInt(),Character.OTHER_PUNCTUATION.toInt())
    fun reaction(text:String):Boolean {
        val body=text.trim()
        if(body.isEmpty()||oldReaction.matches(body))return true
        if(body.length>512)return false
        val undecorated=ContextDecorations.without(body)
        // Unknown pictographs and mathematical/currency/directional symbols
        // can be the entire observation. Only reviewed decorations and empty
        // punctuation can disappear without a model decision.
        if(undecorated.codePoints().allMatch {Character.isWhitespace(it)||Character.getType(it) in punctuation})return true
        val plain=undecorated.trim().trimEnd('.','!',',','…').trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
        return plain.length<=80&&(plain in reactions||oldReaction.matches(plain))
    }
    fun needsContext(document:ContextDocument,background:ContextBackground?):Boolean {
        val parent=background?.reply ?: background?.post
        return if(parent==null)question(document.parentText)else parent.unavailable||parent.incomplete||parent.uncertain||question(parent.text)
    }
    private fun question(text:String)=text.any {it in "?？؟"}
}
