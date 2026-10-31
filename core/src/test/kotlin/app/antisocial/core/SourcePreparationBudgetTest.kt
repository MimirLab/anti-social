package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class SourcePreparationBudgetTest {
    private val mib=1024L*1024
    private val healthy=SourcePreparationMemory(false,false,256*mib,32*mib,512*mib,128*mib)
    @Test fun expansionRequiresBothHeapAndSystemMargins() {
        assertEquals(SourcePreparationBudget.EXPANDED,SourcePreparationBudget.choose(SourcePreparationBudget.BASIC,healthy))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.BASIC,healthy.copy(lowRam=true)))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.BASIC,healthy.copy(heapUsed=161*mib)))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.BASIC,healthy.copy(systemAvailable=255*mib)))
    }
    @Test fun measuredPressureDisablesOnlyDisposableReuse() {
        for(memory in listOf(healthy.copy(lowMemory=true),healthy.copy(heapUsed=225*mib),healthy.copy(systemAvailable=128*mib)))
            assertEquals(0L,SourcePreparationBudget.choose(SourcePreparationBudget.EXPANDED,memory))
    }
    @Test fun retentionMarginsAvoidTogglingOnTheCachesOwnAllocations() {
        val medium=healthy.copy(heapUsed=176*mib,systemAvailable=224*mib)
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.BASIC,medium))
        assertEquals(SourcePreparationBudget.EXPANDED,SourcePreparationBudget.choose(SourcePreparationBudget.EXPANDED,medium))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.EXPANDED,medium.copy(heapUsed=193*mib)))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.EXPANDED,medium.copy(systemAvailable=191*mib)))
    }
    @Test fun recoveryHasItsOwnMargin() {
        assertEquals(0L,SourcePreparationBudget.choose(0,healthy.copy(heapUsed=209*mib)))
        assertEquals(0L,SourcePreparationBudget.choose(0,healthy.copy(systemAvailable=159*mib)))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(0,healthy.copy(heapUsed=208*mib,systemAvailable=160*mib)))
    }
    @Test fun unknownOrMalformedFactsNeverExpandTheWorkingSet() {
        for(memory in listOf(healthy.copy(heapMaximum=0),healthy.copy(heapUsed=-1),healthy.copy(heapUsed=257*mib),
            healthy.copy(systemAvailable=-1,systemThreshold=-1),healthy.copy(systemThreshold=-1)))
            assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(SourcePreparationBudget.EXPANDED,memory))
    }
    @Test fun extremeValuesKeepTheSameFiniteAllowance() {
        assertEquals(SourcePreparationBudget.EXPANDED,SourcePreparationBudget.choose(Long.MAX_VALUE,healthy.copy(heapMaximum=Long.MAX_VALUE,heapUsed=0,systemAvailable=Long.MAX_VALUE,systemThreshold=0)))
        assertEquals(SourcePreparationBudget.BASIC,SourcePreparationBudget.choose(Long.MAX_VALUE,healthy.copy(heapUsed=Long.MIN_VALUE)))
        assertEquals(0L,SourcePreparationBudget.choose(Long.MAX_VALUE,healthy.copy(systemAvailable=Long.MAX_VALUE,systemThreshold=Long.MAX_VALUE)))
    }
}
