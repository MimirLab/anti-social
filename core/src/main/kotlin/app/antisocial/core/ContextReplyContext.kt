/* AntiSocial, GPL-3.0-or-later. Explicit source relationships, never inferred answers. */
package app.antisocial.core

import java.security.MessageDigest

data class ContextReplyContext(val identity:String,val text:String,val label:String="",val partial:Boolean=false,
    val unavailable:Boolean=false,val uncertain:Boolean=false,val sources:List<String> = emptyList(),val languagePending:Boolean=false) {
    companion object {
        fun from(background:ContextBackground?,sources:List<String> = emptyList(),unknownKey:String=""):ContextReplyContext? {
            val parent=background?.reply ?: return null
            val unavailable=parent.unavailable||parent.text.isBlank()
            val full=parent.text
            val text=if(unavailable)""else full.take(640).safeEnd()
            val key=listOf("reply-context-v1",background.post?.fingerprint,parent.fingerprint,unknownKey.takeIf {unavailable}).joinToString("\n")
            val identity=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
            return ContextReplyContext(identity,text,parent.label.filterNot(Char::isISOControl).take(100).safeEnd(),
                parent.incomplete||text.length<full.length,unavailable,parent.uncertain,(parent.sourceId.takeIf {it.isNotBlank()}?.let(::listOf) ?: sources.takeUnless {unavailable}.orEmpty()).distinct().sorted())
        }
        /** This only controls whether to display context, never relevance or
         * truth. Longer self-contained contributions can keep one text block. */
        fun needs(text:String):Boolean {
            val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?").findAll(text).toList()
            return words.size<=12||words.size<=48&&Regex("\\b(?:it|this|that|those|these|they|them|here|there|same)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        private fun String.safeEnd()=if(lastOrNull()?.isHighSurrogate()==true)dropLast(1)else this
    }
}

/** References normalize repeated parent text across many retained replies. */
data class ContextReplyContexts(val references:Map<String,String> = emptyMap(),val frames:Map<String,ContextReplyContext> = emptyMap()) {
    fun source(id:String):ContextReplyContext?=references[id]?.let(frames::get)
    companion object {
        fun collect(entries:Map<String,ContextReplyContext>):ContextReplyContexts {
            val frames=linkedMapOf<String,ContextReplyContext>()
            entries.values.forEach {frame->val old=frames[frame.identity]
                frames[frame.identity]=if(old==null)frame else old.copy(sources=(old.sources+frame.sources).distinct().sorted())}
            return ContextReplyContexts(entries.mapValues {it.value.identity}.toSortedMap(),frames.toSortedMap())
        }
    }
}

data class ContextContribution(val finding:ContextFinding,val reply:ContextReplyContext?)

object ContextContributions {
    fun groups(findings:List<ContextFinding>,reply:(String)->ContextReplyContext?):List<ContextContribution> = findings.flatMap {finding->
        if(!ContextReplyContext.needs(finding.text))listOf(ContextContribution(finding,null))else finding.sources.groupBy {reply(it)?.identity}.values.map {sources->
            ContextContribution(finding.copy(sources=sources,quotes=finding.quotes.filterKeys {it in sources},readingQuotes=finding.readingQuotes?.filterKeys {it in sources}),sources.firstNotNullOfOrNull(reply))
        }
    }
    fun render(groups:List<ContextContribution>,label:(List<String>)->String):String {
        // A question quoted in its answer's frame need not be printed again.
        // Preserve separate contextual questions and incomplete excerpts.
        val answered=groups.mapNotNull {it.reply?.takeIf {r->!r.partial&&!r.unavailable} }
        return groups.filterNot {group->group.reply==null&&group.finding.kind=="question"&&answered.any {
            it.text==group.finding.text&&it.sources.containsAll(group.finding.sources)
        }}.joinToString("\n\n") {group->
            val finding=group.finding;val response="${label(finding.sources)}: ${finding.text}"
            val frame=group.reply ?: return@joinToString response
            val context=when {
                frame.languagePending->"Earlier reply is waiting for English preparation."
                frame.unavailable->"Earlier reply context unavailable."
                else->"In reply to ${if(frame.sources.isEmpty())frame.label.ifBlank {"an earlier reply"}else label(frame.sources)}:\n“${frame.text}”"+
                    (if(frame.partial)"\nEarlier reply excerpt is partial."else "")+
                    (if(frame.uncertain)"\nEarlier reply text is marked uncertain."else "")
            }
            context+"\n"+response
        }
    }
}
