package app.antisocial.core

/** Observed publication time, never inferred from retrieval or relative prose. */
data class ContextPublicationTime(val instant: java.time.Instant,val earliestKnownEdition: Boolean=false) {
    val identity: String get() = instant.toString()+"|"+earliestKnownEdition
    val readable:String get()=DISPLAY.format(instant)
    fun label(speaker:String):String = speaker+" ("+(if(earliestKnownEdition)"earliest known edition " else "posted ")+
        readable+")"
    companion object {
        private val DISPLAY=java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm:ss 'UTC'",java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC)
        fun parse(value:String?,earliestKnownEdition:Boolean=false):ContextPublicationTime? =
            value?.takeIf {it.length in 1..80}?.let {runCatching {
                val instant=java.time.Instant.parse(it);instant.toEpochMilli()
                ContextPublicationTime(instant,earliestKnownEdition)
            }.getOrNull()}
    }
}
data class ContextSourcePublication(val speaker:String,val time:ContextPublicationTime?) {
    val timeIdentity:String get()=time?.identity ?: "unavailable"
    fun displayLabel()=time?.label(speaker) ?: "$speaker (publication time unavailable)"
    companion object {
        fun encode(editions:List<ContextSourcePublication>):Map<String,String> {
            require(editions.size<=64&&editions.none {it.time?.earliestKnownEdition==true}){"Exact edition timestamps or explicit unknown dates are required"}
            val rows=editions.map {listOf(it.speaker.filterNot(Char::isISOControl).take(100).trimEnd(Char::isHighSurrogate),it.time?.instant?.toString().orEmpty())}
                .distinct().sortedBy {it.joinToString("\u001f")}
            fun json(items:List<List<String>>)=com.google.gson.JsonArray().apply {items.forEach {row->add(com.google.gson.JsonArray().apply {row.forEach {add(it)}})}}.toString()
            val chunks=mutableListOf<List<List<String>>>();var current=mutableListOf<List<String>>()
            for(row in rows) {
                if(current.isNotEmpty()&&json(current+listOf(row)).length>8192){chunks+=current;current=mutableListOf()}
                current+=row
            }
            if(current.isNotEmpty())chunks+=current
            require(chunks.size<=2)
            return chunks.mapIndexed {i,chunk->(if(i==0)"editionTimes"else "editionTimes2") to json(chunk)}.toMap().also {require(it.values.all {v->v.length<=8192})}
        }
        fun from(document:ContextDocument):List<ContextSourcePublication> {
            val editions=document.metadata["editionTimes"]?.takeIf {it.length<=8192}?.let {raw->runCatching {
                val rows=com.google.gson.JsonArray().apply {
                    addAll(com.google.gson.JsonParser.parseString(raw).asJsonArray)
                    document.metadata["editionTimes2"]?.let {more->require(more.length<=8192);addAll(com.google.gson.JsonParser.parseString(more).asJsonArray)}
                };require(rows.size()<=64)
                rows.map {row->val fields=row.asJsonArray;require(fields.size()==2)
                    ContextSourcePublication(fields[0].asString.filterNot(Char::isISOControl).take(100),ContextPublicationTime.parse(fields[1].takeUnless {it.isJsonNull}?.asString))
                }.distinct()
            }.getOrNull()}.orEmpty()
            if(editions.isNotEmpty())return editions
            return ContextPublicationTime.parse(document.metadata["published"],document.kind=="post"&&document.id.startsWith("merged:"))?.let {
                listOf(ContextSourcePublication(document.author.trim().removePrefix("@").take(100).ifBlank {"Public source"},it))
            }.orEmpty()
        }
    }
}

data class ContextDocument(
    val id: String,
    val revision: String,
    val text: String,
    val title: String = "",
    val author: String = "",
    val url: String = "",
    val kind: String = "comment",
    val parentText: String = "",
    val fetchedAt: Long = 0,
    val metadata: Map<String, String> = emptyMap()
)

data class ContextFinding(
    val id: String,
    val text: String,
    val kind: String,
    val sources: List<String>,
    val quotes: Map<String, String> = emptyMap(),
    /** English working quotations, when translation was necessary. `quotes`
     * always contains exact original evidence. Nullable for old saved JSON. */
    val readingQuotes: Map<String, String>? = null
)

enum class ContextUnitStatus { SIGNAL, NO_SIGNAL, REPEATED }

/** Half-open UTF-16 offsets into the exact revision's original text. Unit IDs
 * also bind its content, prompt version and the caller's processing profile. */
data class ContextUnitOutcome(
    val unitId: String,
    val documentId: String,
    val revision: String,
    val start: Int,
    val end: Int,
    val status: ContextUnitStatus,
    val findings: List<ContextFinding>,
    val level: Int = 0,
    val unitIndex: Int = 0,
    val unitCount: Int = 1
)

data class ContextBatchResult(
    val documents: List<String>,
    val findings: List<ContextFinding>,
    val changed: Boolean,
    val outcomes: List<ContextUnitOutcome> = emptyList(),
    val completedUnits: Int = 0,
    val totalUnits: Int = 0
)

/** A compacted node retains its entire leaf evidence outside the generated
 * prose. Compaction never deletes findings or turns proximity into agreement. */
data class ContextSummaryReceipt(
    val id: String,
    val inputs: List<String>,
    val text: String,
    val sources: List<String>,
    val leafFindings: List<String>,
    val level: Int
)

data class ContextSummary(
    val text: String,
    val sources: List<String>,
    val findings: List<ContextFinding>,
    val levels: Int,
    val changed: Boolean,
    val receipts: List<ContextSummaryReceipt> = emptyList(),
    /** Null for legacy ledger composition; empty is a completed no-addition review. */
    val selectedFindings:List<String>?=null
)

interface ContextModel {
    /** Wire-level generation policy is part of the durable review recipe. */
    val generationProfile: String get() = "default"
    suspend fun count(system: String, user: String): Int
    suspend fun generate(system: String, user: String, maxTokens: Int): String
    /** Optional candidate retrieval only. The engine binds returned IDs to its
     * existing evidence and retains lexical candidates; this never discards a
     * source or delegates routing/actions to a model. */
    suspend fun selectKnown(text: String, findings: List<ContextFinding>): List<ContextFinding>? = null
    val contextTokens: Int get() = 2048
    val maxOutputTokens: Int get() = 512
    /** A native non-thinking worker can answer a narrow vote cheaply. Remote
     * adapters retain their full response allowance unless they know otherwise. */
    val shortDecisionTokens: Int get() = maxOutputTokens
    val processingPlan: ContextProcessingPlan get() = ContextProcessingPlan.SINGLE
}

/** Invalid model output remains retryable work; it is never committed as
 * successfully reviewed, empty material. */
class ContextProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A repeated short response can have different parents on different streams.
 * Selected finding IDs alone must not lend it a rejected source interpretation. */
object ContextBriefEvidence {
    fun select(findings:List<ContextFinding>,ids:List<String>?,sources:List<String>?):List<ContextFinding> {
        if(ids==null)return findings
        val selected=ids.toHashSet();val allowed=sources?.toHashSet()
        return findings.mapNotNull {finding->
            if(finding.id !in selected)return@mapNotNull null
            if(allowed==null)return@mapNotNull finding
            val kept=finding.sources.filter {it in allowed}.toSet()
            if(kept.isEmpty())null else finding.copy(sources=finding.sources.filter {it in kept},quotes=finding.quotes.filterKeys {it in kept},readingQuotes=finding.readingQuotes?.filterKeys {it in kept})
        }
    }
}
