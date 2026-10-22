/* AntiSocial, GPL-3.0-or-later. Optional retrieval hints never decide what survives. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

/** A small RAM-only bank reuses the installed English embedding companion.
 * Context never initiates a remote embedding request or downloads a model. */
internal class ContextCandidateBank(private val repo: Repository) {
    private val vectors=object: LinkedHashMap<String,EmbeddingResult.Vector>(32,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,EmbeddingResult.Vector>?)=size>256
    }
    suspend fun select(text: String,findings: List<ContextFinding>): List<ContextFinding>? {
        if(findings.isEmpty()||!repo.ai.contextLocalEmbeddingAvailable())return null
        val letters=text.count(Char::isLetter)
        if(letters==0||text.count {it.isLetter()&&it.code<128}.toDouble()/letters<.9)return null
        return try {
            val query=vector(text.take(1500)) ?: return null
            val candidates=findings.takeLast(256).mapNotNull {finding->
                val item=vector(finding.text.take(1500)) ?: return@mapNotNull null
                if(item.spaceId!=query.spaceId||item.values.size!=query.values.size)return@mapNotNull null
                finding to cosine(query.values,item.values)
            }.filter {it.second.isFinite()&&it.second>.25}.sortedByDescending {it.second}.take(8).map {it.first}
            candidates.takeIf {it.isNotEmpty()}
        }catch(e: CancellationException){throw e}catch(_: Exception){null}
    }
    private suspend fun vector(text: String): EmbeddingResult.Vector? {
        val model=repo.ai.contextEmbeddingIdentity()
        val identity=model+":"+Repository.hash(text)
        synchronized(vectors){vectors[identity]}?.let {return it}
        val vector=repo.ai.contextLocalEmbed(text,model) as? EmbeddingResult.Vector ?: return null
        synchronized(vectors){vectors[identity]=vector};return vector
    }
    private fun cosine(a: FloatArray,b: FloatArray): Double {
        var dot=0.0;var aa=0.0;var bb=0.0
        for(i in a.indices){dot+=a[i].toDouble()*b[i];aa+=a[i].toDouble()*a[i];bb+=b[i].toDouble()*b[i]}
        return if(aa>0&&bb>0)dot/sqrt(aa*bb)else Double.NaN
    }
    fun clear(){synchronized(vectors){vectors.clear()}}
}
