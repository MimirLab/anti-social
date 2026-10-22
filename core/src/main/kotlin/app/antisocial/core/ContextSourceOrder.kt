/* AntiSocial, GPL-3.0-or-later. Local dependency order grants no new source authority. */
package app.antisocial.core

import java.util.PriorityQueue

/** Stable review order, independent of publication order. Resolve keys before
 * calling; absent/ambiguous parents are not invented. Strongly connected groups
 * keep their original order so malformed cycles cannot hang the reader or move
 * their otherwise acyclic descendants ahead of them. All traversals are iterative. */
object ContextSourceOrder {
    fun <T> order(items:List<T>,key:(T)->String,parents:(T)->kotlin.collections.Collection<String>):List<T> {
        if(items.size<2)return items
        val ids=items.map(key);require(ids.distinct().size==ids.size){"Duplicate Context source IDs"}
        val positions=ids.withIndex().associate {it.value to it.index}
        val dependencies=items.mapIndexed {index,item->parents(item).mapNotNull(positions::get).filter {it!=index}.distinct()}
        if(dependencies.all {it.isEmpty()})return items
        val children=Array(items.size){mutableListOf<Int>()}
        dependencies.forEachIndexed {child,list->list.forEach {children[it]+=child}}
        val visited=BooleanArray(items.size);val finished=ArrayList<Int>(items.size)
        // A cursor per stack frame preserves true DFS finish order without a
        // recursive call stack, even for an entire long conversation chain.
        val stack=ArrayDeque<Pair<Int,Iterator<Int>>>()
        for(start in items.indices) {
            if(visited[start])continue
            visited[start]=true;stack.addLast(start to children[start].iterator())
            while(stack.isNotEmpty()) {
                val (node,next)=stack.last()
                if(next.hasNext()) {
                    val child=next.next()
                    if(!visited[child]) {visited[child]=true;stack.addLast(child to children[child].iterator())}
                }else {stack.removeLast();finished+=node}
            }
        }
        val component=IntArray(items.size){-1};val members=mutableListOf<MutableList<Int>>()
        val pending=ArrayDeque<Int>()
        for(start in finished.asReversed()) {
            if(component[start]>=0)continue
            val index=members.size;val group=mutableListOf<Int>();members+=group
            component[start]=index;pending.addLast(start)
            while(pending.isNotEmpty()) {
                val node=pending.removeLast();group+=node
                dependencies[node].forEach {parent->if(component[parent]<0) {component[parent]=index;pending.addLast(parent)}}
            }
            group.sort()
        }
        val outgoing=Array(members.size){linkedSetOf<Int>()};val degree=IntArray(members.size)
        dependencies.forEachIndexed {child,list->list.forEach {parent->
            val a=component[parent];val b=component[child]
            if(a!=b&&outgoing[a].add(b))degree[b]++
        }}
        val ready=PriorityQueue<Int>(compareBy {members[it].first()})
        members.indices.filterTo(ready){degree[it]==0}
        val ordered=ArrayList<T>(items.size)
        while(ready.isNotEmpty()) {
            val group=ready.remove();members[group].forEach {ordered+=items[it]}
            outgoing[group].forEach {child->if(--degree[child]==0)ready+=child}
        }
        check(ordered.size==items.size)
        return ordered
    }
}
