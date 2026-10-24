/* anti social, GPL-3.0-or-later. Preserve bounded public evidence across partial refreshes. */
package app.antisocial.core

object DiscoverySnapshots {
    const val RETAIN_MS=6*60*60_000L
    private fun edge(e:AdjacentEvidence)=listOf(e.kind.name,AdjacentDiscovery.key(e.owner),e.via?.let(AdjacentDiscovery::key).orEmpty(),e.sourceUrl,e.method)
    fun retained(value:AdjacentSnapshot,now:Long):AdjacentSnapshot=value.copy(cards=value.cards.mapNotNull {card->
        val evidence=card.evidence.filter {it.checkedAt in (now-RETAIN_MS)..(now+60_000L)}.take(12)
        if(evidence.isEmpty())null else card.copy(evidence=evidence)
    }.take(AdjacentDiscovery.MAX_CARDS),loading=false)

    /** An empty/partial refresh is absence of new evidence, not a deletion notice.
     * Original observation times survive so repeated refreshes cannot keep old facts forever. */
    fun merge(previous:AdjacentSnapshot?,current:AdjacentSnapshot,now:Long):AdjacentSnapshot {
        val fresh=retained(current,now)
        if(previous==null)return fresh
        val cards=linkedMapOf<String,AdjacentCard>()
        retained(previous,now).cards.forEach {cards[it.key]=it}
        for(card in fresh.cards) {
            val old=cards[card.key]
            if(old==null){if(cards.size<AdjacentDiscovery.MAX_CARDS)cards[card.key]=card;continue}
            val evidence=linkedMapOf<List<String>,AdjacentEvidence>()
            (old.evidence+card.evidence).forEach {e->val key=edge(e);if((evidence[key]?.checkedAt ?: Long.MIN_VALUE)<=e.checkedAt)evidence[key]=e}
            val profile=if(card.profile.displayNameSupplied||!old.profile.displayNameSupplied)card.profile else old.profile
            cards[card.key]=card.copy(profile=profile,evidence=evidence.values.toList().takeLast(12))
        }
        val coverage=(previous.coverage+fresh.coverage).associateBy {it.source}.values.toList().takeLast(40)
        return fresh.copy(cards=cards.values.toList(),coverage=coverage)
    }
}
