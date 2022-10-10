package util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

inline fun <T> Iterable<T>.forEachAsync(threads: Int = 8, crossinline action: (T) -> Unit) {
  val group = MbAsyncGroup(threads)
  this.forEach { group.background { action(it) } }
  group.wait()
}

suspend fun <T> Iterable<T>.forEachAsyncCoroutine(
  concurrentJobs: Int? = null,
  action: suspend CoroutineScope.(T) -> Unit,
) {
  coroutineScope {
    val semaphore = concurrentJobs?.let { Semaphore(it) }
    this@forEachAsyncCoroutine.map { entry ->
      semaphore?.acquire()
      launch {
        try {
          action(entry)
        } finally {
          semaphore?.release()
        }
      }
    }.joinAll()
  }
}