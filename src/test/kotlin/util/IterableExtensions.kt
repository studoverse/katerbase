package util

inline fun <T> Iterable<T>.forEachAsync(threads: Int = 8, crossinline action: (T) -> Unit) {
  val group = MbAsyncGroup(threads)
  this.forEach { group.background { action(it) } }
  group.wait()
}
