/* AntiSocial, GPL-3.0-or-later. Provenance is retained even when prose is reused. */
package app.antisocial.core

/** An exact additional source copy need not rewrite an existing overview.
 * This is deliberately stronger than finding-ID or paraphrase equality: every
 * earlier document and quotation must survive, and the added complete source
 * must match an earlier supporter with the same context and evidence quality.
 * Different authors remain separate provenance, never independent verification
 * or agreement inferred by this check. No network or model decision is needed. */
object ContextBriefReuse {
    private data class CopyKey(val text: String,val title: String,val kind: String,
        val parent: String,val metadata: Map<String,String>)
    private data class Support(val document: CopyKey,val quote: String,val readingQuote: String?)

    fun onlyExactCopiesAdded(
        previous: List<ContextFinding>, current: List<ContextFinding>,
        previousDocuments: List<ContextDocument>, currentDocuments: List<ContextDocument>
    ): Boolean {
        if(previous.isEmpty()||previous.size!=current.size||previous.size>20_000||
            previousDocuments.size>10_000||currentDocuments.size>10_000)return false
        val before=previous.associateBy {it.id};val after=current.associateBy {it.id}
        if(before.size!=previous.size||after.size!=current.size||before.keys!=after.keys)return false
        val oldDocs=previousDocuments.associateBy {it.id};val newDocs=currentDocuments.associateBy {it.id}
        if(oldDocs.size!=previousDocuments.size||newDocs.size!=currentDocuments.size)return false
        // Include earlier parents and other background documents, not just the
        // spans cited by a finding. A changed qualification outside that span
        // or a changed parent must not masquerade as extra provenance.
        if(oldDocs.any {(id,doc)->newDocs[id]?.copy(fetchedAt=0)!=doc.copy(fetchedAt=0)})return false
        val copyKeys=mutableMapOf<String,CopyKey>()
        fun key(doc:ContextDocument)=copyKeys.getOrPut(doc.id) {
            CopyKey(doc.text,doc.title,doc.kind,doc.parentText,doc.metadata.filterKeys {it !in COPY_PROVENANCE}.mapValues {(name,value)->
                if(name=="published")ContextPublicationTime.parse(value)?.instant?.toString() ?: value else value
            })
        }
        var added=false
        for((id,old) in before) {
            val next=after.getValue(id)
            val oldSources=old.sources.toSet();val nextSources=next.sources.toSet()
            if(old.text!=next.text||old.kind!=next.kind||old.sources.isEmpty()||
                oldSources.size!=old.sources.size||nextSources.size!=next.sources.size||!nextSources.containsAll(oldSources))return false
            if(old.quotes.keys!=oldSources||next.quotes.keys!=nextSources||
                old.readingQuotes.orEmpty().keys.any {it !in oldSources}||next.readingQuotes.orEmpty().keys.any {it !in nextSources})return false
            val supports=hashSetOf<Support>()
            for(source in old.sources) {
                if(old.quotes[source]!=next.quotes[source]||old.readingQuotes?.get(source)!=next.readingQuotes?.get(source))return false
                val doc=oldDocs[source] ?: return false
                val quote=old.quotes[source]?.takeIf {it.isNotBlank()&&doc.text.contains(it)} ?: return false
                supports+=Support(key(doc),quote,old.readingQuotes?.get(source))
            }
            for(source in nextSources-oldSources) {
                if(source in oldDocs)return false
                val doc=newDocs[source] ?: return false
                val quote=next.quotes[source]?.takeIf {it.isNotBlank()&&doc.text.contains(it)} ?: return false
                if(Support(key(doc),quote,next.readingQuotes?.get(source)) !in supports)return false
                added=true
            }
        }
        return added
    }

    // Location is additional provenance. Publication time remains interpretive
    // context: "tomorrow", weekdays or a repeated status may refer to a new event.
    // Unknown metadata, attachments, parent references, language,
    // recognition uncertainty and extraction limits remain part of equality.
    private val COPY_PROVENANCE=setOf("root","origin","contextKey","contextAliases","platform")
}
