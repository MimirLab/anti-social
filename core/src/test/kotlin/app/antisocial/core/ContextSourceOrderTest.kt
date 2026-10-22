package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ContextSourceOrderTest {
    private fun order(ids:List<String>,parents:Map<String,List<String>>) = ContextSourceOrder.order(ids,{it}){parents[it].orEmpty()}
    @Test fun unrelatedSourcesStayStableAndParentsPrecedeChildren() {
        assertEquals(listOf("unrelated","parent","child","grandchild"),order(listOf("grandchild","child","unrelated","parent"),mapOf("child" to listOf("parent"),"grandchild" to listOf("child"))))
        assertEquals(listOf("one","two","three"),order(listOf("one","two","three"),emptyMap()))
    }
    @Test fun cyclicSourcesRemainStableAndTheirExternalDescendantsWait() {
        val ids=listOf("descendant","b","unrelated","a")
        val parents=mapOf("a" to listOf("b"),"b" to listOf("a"),"descendant" to listOf("b"))
        assertEquals(listOf("b","a","descendant","unrelated"),order(ids,parents))
        assertEquals(order(ids,parents),order(ids,parents.mapValues {it.value.reversed()+it.value}))
    }
    @Test fun absentAndSelfReferencesDoNotInventPendingWork() {
        assertEquals(listOf("child","other"),order(listOf("child","other"),mapOf("child" to listOf("absent","child"))))
    }
    @Test fun longChainsUseNoRecursiveStack() {
        val ids=(0 until 10000).map(Int::toString)
        val parents=ids.dropLast(1).associateWith {listOf((it.toInt()+1).toString())}
        assertEquals(ids.reversed(),order(ids,parents))
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateIdsAreRejected() {order(listOf("same","same"),emptyMap())}
    @Test fun variedAcyclicGraphsRetainEverySourceAndEveryDependency() {
        val random=java.util.Random(91)
        repeat(100) {
            val ids=(0 until 40).map(Int::toString)
            val parents=ids.associateWith {child->ids.take(child.toInt()).filter {random.nextInt(8)==0}}
            val preferred=ids.shuffled(kotlin.random.Random(random.nextLong()))
            val result=order(preferred,parents);val positions=result.withIndex().associate {it.value to it.index}
            assertEquals(ids.toSet(),result.toSet());assertEquals(ids.size,result.size)
            parents.forEach {(child,list)->list.forEach {parent->assertTrue(positions.getValue(parent)<positions.getValue(child))}}
            assertEquals(result,order(preferred,parents.mapValues {it.value.reversed()}))
        }
    }

}
