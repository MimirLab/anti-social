/* anti social, GPL-3.0-or-later. Ephemeral work ownership; no requests or retained history. */
package app.antisocial.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

/** Only running/queued jobs own entries. Independent jobs sharing a card must
 * finish independently; completion, errors and cancellation all release leases. */
class CardWork {
    data class Task(val id:Long,val keys:Set<String>,val label:String)
    private val lock=Any()
    private var next=0L
    private val state=MutableStateFlow<List<Task>>(emptyList())
    val active=state.asStateFlow()
    fun begin(keys:kotlin.collections.Collection<String>,label:String):AutoCloseable {
        val owners=keys.filter(String::isNotBlank).toSet()
        if(owners.isEmpty())return AutoCloseable {}
        val task=synchronized(lock){Task(++next,owners,label).also {state.value=state.value+it}}
        val closed=AtomicBoolean()
        return AutoCloseable {if(closed.compareAndSet(false,true))synchronized(lock){state.value=state.value.filterNot {it.id==task.id}}}
    }
    fun activity(keys:Set<String>)=active.map {tasks->tasks.filter {task->keys.any {it in task.keys}}.map {it.label}.distinct().sorted()}.distinctUntilChanged()
    suspend fun <T> track(keys:kotlin.collections.Collection<String>,label:String,block:suspend ()->T):T {
        val lease=begin(keys,label)
        return try {block()}finally {lease.close()}
    }
}
