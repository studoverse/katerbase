package util

import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Dispatcher group.
Create an util.MbAsyncGroup and put in some background tasks.
Then call wait() to block until all background tasks finished.
Per default up to 8 background threads handle the tasks, this can be changed in the constructor parameter maxThreads.

Example usage 1:
val group = util.MbAsyncGroup()
group.background { longFun1() }
group.background { longFun2(1, 2, 3) }
group.wait()
println("Every group has finished")

Example usage 2:
util.MbAsyncGroup()
.background { longFun1()) }
.background { longFun2(1, 2, 3) }
.wait()
println("Every group has finished")

The group can be reused after wait() was called.
wait() will throw in case an exception occoured in background()
 */
class MbAsyncGroup(private val maxThreads: Int = 8) {
  var waitSemaphore = Semaphore(maxThreads)
  private var error: Throwable? = null

  fun background(task: () -> Unit): MbAsyncGroup {
    waitSemaphore.acquire()
    thread {
      try {
        task()
      } catch (e: Throwable) { // Catching all Throwables (and not Exceptions) here is fine, as we rethrow it
        error = e // Don't throw, as we throw (the last) error on wait()
      } finally {
        waitSemaphore.release()
      }
    }

    return this
  }

  fun wait(): MbAsyncGroup {
    waitSemaphore.acquire(maxThreads)
    waitSemaphore.release(maxThreads)

    error?.let { error ->
      throw error
    }

    return this
  }
}