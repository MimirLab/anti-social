/* AntiSocial, GPL-3.0-or-later. Complete temporal bounds, bounded representatives. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ContextPublicationFrame {
    fun ordered(entries:List<ContextSourcePublication>):List<ContextSourcePublication> = entries.map {
        it.copy(speaker=it.speaker.filterNot(Char::isISOControl).trim().take(100).trimEnd(Char::isHighSurrogate).ifBlank {"Public source"})
    }.distinct().sortedWith(compareBy<ContextSourcePublication> {it.time==null}.thenBy {it.time?.instant}.thenBy {it.speaker}.thenBy {it.timeIdentity})

    /** Every bounded view contains the earliest, latest and an undated context
     * when present. Remaining slots sample the interior; input order is inert. */
    fun representatives(entries:List<ContextSourcePublication>,limit:Int=4):List<ContextSourcePublication> {
        require(limit in 3..64)
        val all=ordered(entries)
        if(all.size<=limit)return all
        val dated=all.filter {it.time!=null};val undated=all.filter {it.time==null}
        val selected=linkedSetOf<ContextSourcePublication>()
        dated.firstOrNull()?.let(selected::add);dated.lastOrNull()?.let(selected::add)
        undated.firstOrNull()?.let(selected::add)
        val candidates=if(dated.isNotEmpty())dated else undated
        if(selected.size<limit&&candidates.isNotEmpty())selected.add(candidates[candidates.size/2])
        for(entry in all) {if(selected.size>=limit)break;selected.add(entry)}
        return ordered(selected.toList())
    }

    fun display(entries:List<ContextSourcePublication>,limit:Int=3):String {
        val all=ordered(entries);val chosen=representatives(all,limit)
        return chosen.joinToString(" · "){it.displayLabel()}+if(all.size>chosen.size)" · ${all.size-chosen.size} more editions" else ""
    }

    fun write(target:JsonObject,entries:List<ContextSourcePublication>,limit:Int=4) {
        val all=ordered(entries)
        if(all.isEmpty())return
        val chosen=representatives(all,limit)
        target.add("source_times",JsonArray().apply {chosen.forEach {entry->add(JsonObject().apply {
            addProperty("speaker",entry.speaker)
            val time=entry.time
            if(time==null)addProperty("publication_time_unavailable",true)else {
                addProperty("published",time.instant.toString())
                if(time.earliestKnownEdition)addProperty("earliest_known_edition",true)
            }
        })}})
        if(all.size>chosen.size) {
            val dated=all.mapNotNull {it.time}
            target.addProperty("additional_source_contexts",all.size-chosen.size)
            target.add("publication_context",JsonObject().apply {
                addProperty("dated_contexts",dated.size);addProperty("undated_contexts",all.size-dated.size)
                addProperty("distinct_instants",dated.map {it.instant}.distinct().size)
                if(dated.isNotEmpty()) {
                    addProperty("earliest_known",dated.minOf {it.instant}.toString())
                    addProperty("latest_known",dated.maxOf {it.instant}.toString())
                }
                val partial=dated.count {it.earliestKnownEdition}
                if(partial>0)addProperty("earliest_edition_only_contexts",partial)
            })
        }
    }
}
