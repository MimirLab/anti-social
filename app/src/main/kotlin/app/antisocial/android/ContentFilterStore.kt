/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only explicit rules are persistent. Reveals and previews are session state;
 * no second unbounded archive of filtered posts is maintained. */
class ContentFilterStore(private val prefs: Preferences) {
    private val mutableRules=MutableStateFlow<List<ContentRule>>(emptyList())
    private val mutableError=MutableStateFlow<String?>(null)
    val rules=mutableRules.asStateFlow()
    val error=mutableError.asStateFlow()
    @Suppress("unused") private val observer=prefs.observeContentRules(::refresh)
    init {refresh()}
    private fun refresh() {
        try {
            val raw=prefs.contentRuleSnapshot()
            require(raw==null||raw is String) {"Saved filter rules are unreadable."}
            mutableRules.value=if(raw==null)emptyList()else ContentRuleCodec.decode(raw as String)
            mutableError.value=null
        }catch(_: Exception){mutableError.value="Saved feed filters couldn't be read. Filtering is paused; your posts are still available.";mutableRules.value=emptyList()}
    }
    @Synchronized fun save(rule: ContentRule) {
        check(mutableError.value==null) {"Restore or reset unreadable rules first."}
        val previous=mutableRules.value.firstOrNull {it.id==rule.id}
        val next=rule.copy(revision=previous?.revision?.let {Math.addExact(it,1)} ?: 1)
        write(mutableRules.value.filterNot {it.id==next.id}+next)
    }
    @Synchronized fun delete(id: String) {check(mutableError.value==null);write(mutableRules.value.filterNot {it.id==id})}
    @Synchronized fun importRules(rules: List<ContentRule>) {
        check(mutableError.value==null)
        val known=mutableRules.value.map {it.id}.toSet()
        write(mutableRules.value+rules.map {if(it.id in known)it.copy(id="filter:"+java.util.UUID.randomUUID(),revision=1)else it})
    }
    @Synchronized fun reset() {write(emptyList())}
    private fun write(rules: List<ContentRule>) {prefs.writeContentRules(ContentRuleCodec.encode(rules));refresh()}
}
