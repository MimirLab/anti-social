/* anti social, GPL-3.0-or-later. Source proof is independent of reuse capacity. */
package app.antisocial.core

data class SourcePreparationMemory(val lowRam:Boolean,val lowMemory:Boolean,
    val heapMaximum:Long,val heapUsed:Long,val systemAvailable:Long,val systemThreshold:Long)

/** Conservative admission for a small source-analysis working set. These are
 * local snapshots, not a guarantee that system RAM can all be allocated. */
object SourcePreparationBudget {
    private const val MIB=1024L*1024
    const val BASIC=8L*MIB
    const val EXPANDED=16L*MIB
    fun choose(previous:Long,memory:SourcePreparationMemory):Long {
        if(memory.lowMemory)return 0
        if(memory.heapMaximum<=0||memory.heapUsed !in 0..memory.heapMaximum)return BASIC
        val heap=memory.heapMaximum-memory.heapUsed
        if(heap<32*MIB)return 0
        if(memory.systemAvailable>=0&&memory.systemThreshold>0&&memory.systemAvailable<=memory.systemThreshold)return 0
        val system=if(memory.systemAvailable>0&&memory.systemThreshold>=0&&memory.systemAvailable>memory.systemThreshold)
            memory.systemAvailable-memory.systemThreshold else 0L
        // Recovery and expansion use wider margins than retention, preventing
        // the working set's own allocations from toggling its allowance.
        if(previous==0L&&(heap<48*MIB||system<32*MIB))return 0
        if(!memory.lowRam&&(previous==EXPANDED&&heap>=64*MIB&&system>=64*MIB||heap>=96*MIB&&system>=128*MIB))return EXPANDED
        return BASIC
    }
}
