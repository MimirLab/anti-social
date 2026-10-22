package app.antisocial.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialize one resource without holding up unrelated downloads. */
class KeyedMutex<K> {
    private class Entry(val mutex: Mutex=Mutex(),var users: Int=0)
    private val entries=HashMap<K,Entry>()
    suspend fun <T> withKey(key: K,action: suspend ()->T): T {
        val entry=synchronized(entries){entries.getOrPut(key){Entry()}.also {it.users++}}
        try {return entry.mutex.withLock {action()}}
        finally {synchronized(entries){entry.users--;if(entry.users==0)entries.remove(key)}}
    }
}
