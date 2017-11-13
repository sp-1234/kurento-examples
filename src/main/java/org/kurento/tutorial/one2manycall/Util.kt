package org.kurento.tutorial.one2manycall

import org.kurento.client.Continuation
import java.util.concurrent.ConcurrentMap

// return the new element iff the element was absent and therefore was added;
// otherwise return null
inline fun <K, V> ConcurrentMap<K, V>.putIfAbsentLazy(key: K, crossinline valueFun: () -> V): V? {
    var added = false
    val elem = computeIfAbsent(key, {
        added = true
        valueFun()
    })
    return if (added) {
        elem
    } else {
        null
    }
}

inline fun <T> simpleContinuation(crossinline f: (T) -> Unit): Continuation<T> {
    return object : Continuation<T> {
        override fun onSuccess(result: T) {
            f(result)
        }

        override fun onError(cause: Throwable) {
            throw cause
        }
    }
}
