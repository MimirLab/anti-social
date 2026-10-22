/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** Display-only ranges. Joining them restores every source character. */
data class PostBodySplit(val body: String,val details: String="",val separator: String="",val referenceCount: Int=0,
    val leadingDetails: String="",val leadingSeparator: String="",val trailingDetails: String=details) {
    val hasDetails get()=details.isNotEmpty()
    fun originalText()=leadingDetails+leadingSeparator+body+separator+trailingDetails
}

/** Fold detached leading/trailing references, never rewrite a source. A single footer
 * link is still a footer. In-context credits, meaningful closing prose and a
 * short post whose link supplies its point remain visible. Standalone folding
 * is reserved for attributed fusion additions whose substantive shared passage
 * is already on screen.
 */
object PostBodyDetails {
    private const val MAX_TEXT=65_536
    private const val MAX_REFERENCES=256
    private val reference=Regex(
        """https?://[^\s<>]+|www\.[^\s<>]+|(?<![\p{L}\p{N}_@.])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?::[0-9]{1,5})?(?:[/?#][^\s<>]*)?|(?<![\p{L}\p{N}_@])@[\p{L}\p{N}_][\p{L}\p{N}_.-]{0,255}(?:@[a-z0-9.-]{1,253})?|(?<![\p{L}\p{N}_#])#[\p{L}\p{N}_]+""",
        RegexOption.IGNORE_CASE,
    )
    private val word=Regex("[\\p{L}\\p{N}]+")
    private val punctuation=",;:|•·/\\-—–()[]{}<>\"'“”‘’.!?…".toSet()
    private val tailLabel=Regex(
        """(?:tags|hashtags|mentions|links|references|cc|source|sources|video|watch|watch video|read more|learn more|full story|full article|more info(?:rmation)?|details)(?:[\s\p{Z}]+(?:at|here))?[\s\p{Z}:|•·/\-—–⬇↓→↗🔗🎥\uFE0F]*$""",
        RegexOption.IGNORE_CASE,
    )
    private fun separator(value: String)=value.all {it.isWhitespace()||Character.isSpaceChar(it)||it in punctuation}
    private fun substantive(value: String): Boolean {
        val prose=reference.replace(value,"")
        return prose.count(Char::isLetter)>=32&&word.findAll(prose).take(6).count()>=6
    }
    fun split(value: String,allowStandalone: Boolean=false): PostBodySplit {
        if(value.isBlank()||value.length>MAX_TEXT)return PostBodySplit(value)
        val references=reference.findAll(value).take(MAX_REFERENCES+1).toList()
        if(references.isEmpty()||references.size>MAX_REFERENCES)return PostBodySplit(value)
        val head=leadingInventoryEnd(value)
        val leading=value.substring(0,head).trimEnd()
        val leadingSeparator=value.substring(leading.length,head)
        val trailing=trailing(value.substring(head),allowStandalone)
        if(leading.isEmpty())return trailing
        return trailing.copy(
            details=listOf(leading,trailing.details).filter(String::isNotEmpty).joinToString("\n\n"),
            referenceCount=reference.findAll(leading).count()+trailing.referenceCount,
            leadingDetails=leading,leadingSeparator=leadingSeparator,trailingDetails=trailing.details,
        )
    }
    /** Only whole leading reference lines move. An address in the opening
     * sentence, or a title with words around its link, is still the message. */
    private fun leadingInventoryEnd(value: String): Int {
        var offset=0
        var foundReference=false
        while(offset<value.length) {
            val newline=value.indexOf('\n',offset)
            if(newline<0)break
            val line=value.substring(offset,newline)
            val refs=reference.findAll(line).toList()
            if(refs.isEmpty()) {
                if(line.isNotBlank()&&!tailLabel.matches(line.trim()))break
            } else {
                val before=line.substring(0,refs.first().range.first)
                if(before.isNotBlank()&&!tailLabel.matches(before.trim()))break
                if(!separator(line.substring(refs.last().range.last+1)))break
                if(refs.zipWithNext().any {(first,next)->!separator(line.substring(first.range.last+1,next.range.first))})break
                foundReference=true
            }
            offset=newline+1
        }
        return if(foundReference&&substantive(value.substring(offset)))offset else 0
    }
    private fun trailing(value: String,allowStandalone: Boolean): PostBodySplit {
        namedInventory(value)?.let {return it}
        val references=reference.findAll(value).toList()
        if(references.isEmpty())return PostBodySplit(value)
        if(!separator(value.substring(references.last().range.last+1)))return PostBodySplit(value)
        var first=references.lastIndex
        while(first>0&&separator(value.substring(references[first-1].range.last+1,references[first].range.first)))first--
        for(index in first..references.lastIndex) {
            val count=references.size-index
            var start=references[index].range.first
            val lineStart=value.lastIndexOf('\n',start-1)+1
            val beforeReference=value.substring(lineStart,start)
            val label=tailLabel.find(beforeReference)
            if(label!=null) {
                val beforeLabel=beforeReference.substring(0,label.range.first)
                // Recognize an otherwise empty footer line, or a plain call to
                // action after a completed sentence. Never consume an ordinary
                // phrase such as "this video" in the middle of a sentence.
                if(beforeLabel.isBlank()||completedSentence(beforeLabel))start=lineStart+label.range.first
            }
            val prefix=value.substring(0,start)
            if(prefix.isBlank()) {
                if(allowStandalone)return PostBodySplit("",value.substring(start),prefix,count)
                continue
            }
            if(!substantive(prefix))continue
            // A line beginning with prose such as "Thanks to @one @two @three"
            // is a sentence, not a detached reference list. Inline piles require
            // the preceding sentence to have actually ended.
            val line=prefix.substringAfterLast('\n').trimEnd()
            if(line.isNotEmpty()&&!completedSentence(line))continue
            val body=prefix.trimEnd()
            return PostBodySplit(body,value.substring(start),prefix.substring(body.length),count)
        }
        return PostBodySplit(value)
    }
    /** A roundup can be a flat RSS paragraph of “Name: URL” entries. Only a
     * whole labelled list after a real introductory sentence folds; ordinary
     * linked explanations and their surrounding sentences stay on screen. */
    private fun namedInventory(value:String):PostBodySplit? {
        val urls=reference.findAll(value).filter {!it.value.startsWith('@')&&!it.value.startsWith('#')}.toList()
        if(urls.size<3)return null
        if(!separator(value.substring(urls.last().range.last+1)))return null
        fun label(text:String):Boolean {
            val clean=text.trim()
            if(clean.length !in 2..120||clean.last() !in setOf(':','—','–','-'))return false
            val words=word.findAll(reference.replace(clean,"")).map {it.value}.toList()
            return words.size<=6&&words.none {it.lowercase() in setOf("is","are","was","were","must","should","because","before","after","says","said","explains","reports","think","thinks")}
        }
        if(urls.zipWithNext().any {(a,b)->!label(value.substring(a.range.last+1,b.range.first))})return null
        val before=value.substring(0,urls.first().range.first)
        val boundary=Regex("[.!?…][\\s\\p{Z}]+|\\n\\s*\\n").findAll(before).lastOrNull() ?: return null
        val start=boundary.range.last+1
        if(!label(before.substring(start))||!substantive(before.substring(0,start)))return null
        val body=value.substring(0,start).trimEnd()
        return PostBodySplit(body,value.substring(start),value.substring(body.length,start),reference.findAll(value.substring(start)).count())
    }
    private fun completedSentence(value: String)=value.trimEnd().trimEnd('"','\'','”','’',')',']').lastOrNull() in setOf('.','!','?','…')
}
