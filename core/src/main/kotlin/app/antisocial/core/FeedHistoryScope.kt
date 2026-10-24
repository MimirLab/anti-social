/* anti social, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One collector owns its recovery allowance and original connection policy.
 * This context is neither a Page field nor a persisted cursor/cache. Only an
 * actual fresh SourcePool feed read can bind it to an exact pool and target. */
internal class FeedHistoryScope(val platform:Platform):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<FeedHistoryScope>
    private var owner:Any?=null
    private var target:Target?=null
    private var initialSource:Source?=null
    private var guard:((Source)->Unit)?=null
    private val excluded=mutableSetOf<String>()
    private val attempted=mutableSetOf<String>()
    var originalAllowed=false;private set
    @Synchronized fun bind(pool:Any,target:Target,source:Source,separateTimeline:Boolean,originals:Boolean,check:(Source)->Unit) {
        if(owner!=null||target.platform!=platform||platform==Platform.RSS||target.kind !in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY,TargetKind.TAG))return
        check(source)
        owner=pool;this.target=target;initialSource=source;guard=check;originalAllowed=originals
        // Most API adapters implement feed() by calling fetch(). Do not repeat
        // that same representation merely because the RSS-shaped head is short.
        if(!separateTimeline)excluded+=source.key
    }
    @Synchronized fun owns(pool:Any,target:Target)=owner===pool&&this.target==target
    @Synchronized fun remaining()=3-attempted.size
    @Synchronized fun canRecover()=owner!=null&&remaining()>0
    @Synchronized fun eligible(source:Source)=remaining()>0&&source.key !in attempted&&source.key !in excluded
    @Synchronized fun admit(source:Source):Boolean {
        if(!eligible(source))return false
        attempted+=source.key;return true
    }
    fun check(source:Source?=null){guard?.invoke(source ?: requireNotNull(initialSource))}
}

/** Only the selected shortlist can enter the normal timeline race. Actual
 * starts, including cancelled hedges, share the collector's three-route limit. */
internal class FeedHistoryPlan(val scope:FeedHistoryScope,val target:Target,val sources:Set<String>):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<FeedHistoryPlan>
    fun allows(source:Source)=source.key in sources&&scope.eligible(source)
    fun admit(source:Source)=source.key in sources&&scope.admit(source)
}
