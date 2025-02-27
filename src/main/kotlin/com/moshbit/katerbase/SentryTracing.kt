package com.moshbit.katerbase

import com.mongodb.event.*
import io.sentry.ISpan
import io.sentry.SpanStatus
import kotlinx.coroutines.ThreadContextElement
import org.bson.BsonValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

internal object SentryTracer {
  private val threadLocalContext: ThreadLocal<TraceContext?> = ThreadLocal()

  fun getContext(): TraceContext? = threadLocalContext.get()
  fun setContext(context: TraceContext?) = threadLocalContext.set(context)
}

internal class SentryTracingListener : CommandListener {

  override fun commandStarted(event: CommandStartedEvent) {
    SentryTracer.getContext()?.start(event)
  }

  override fun commandSucceeded(event: CommandSucceededEvent) {
    SentryTracer.getContext()?.finish(event)
  }

  override fun commandFailed(event: CommandFailedEvent) {
    SentryTracer.getContext()?.finish(event)
  }
}

internal class SentryTracerContext(
  private val context: TraceContext
) : ThreadContextElement<TraceContext?>, AbstractCoroutineContextElement(Key) {
  override fun updateThreadContext(context: CoroutineContext): TraceContext? {
    val oldState = SentryTracer.getContext()
    SentryTracer.setContext(this.context)
    return oldState
  }

  override fun restoreThreadContext(context: CoroutineContext, oldState: TraceContext?) {
    SentryTracer.setContext(oldState)
  }

  companion object {
    private object Key : CoroutineContext.Key<SentryTracerContext>

    operator fun invoke(context: TraceContext?) = if (context == null) EmptyCoroutineContext else SentryTracerContext(context)
  }
}

internal class TraceContext(private val rootSpan: ISpan) {
  private val eventSpans = ConcurrentHashMap<Int, ISpan>()

  fun start(event: CommandStartedEvent) {
    val span = rootSpan.startChild("db.query", event.command.toParameterizedJson())
    span.setData("db.system", "mongodb")
    span.setData("db.operation", event.commandName)
    span.setData("db.name", event.databaseName)
    span.setTag("db.name", event.databaseName)
    event.collectionName?.also {
      span.setData("db.mongodb.collection", it)
      span.setTag("db.mongodb.collection", it)
    }

    eventSpans[event.requestId] = span
  }

  fun finish(event: CommandEvent) {
    val span = eventSpans.remove(event.requestId) ?: return
    when (event) {
      is CommandSucceededEvent -> span.finish(SpanStatus.OK)
      is CommandFailedEvent -> {
        span.throwable = event.throwable
        span.finish(SpanStatus.UNKNOWN_ERROR)
      }
    }
  }

  fun finishRoot(cause: Throwable? = null) {
    if (this.rootSpan.isFinished) return
    if (cause == null) {
      this.rootSpan.status = SpanStatus.OK
    } else {
      this.rootSpan.status = if (cause is CancellationException) SpanStatus.ABORTED else SpanStatus.UNKNOWN_ERROR
      this.rootSpan.throwable = cause
    }
    this.rootSpan.finish()
  }

  companion object {
    // For those commands the value of the field with the command name is the collection name
    // e.g. { "aggregate": "collectionName", ... }
    private val COMMANDS_WITH_COLLECTION_NAME: Set<String> = setOf(
      "aggregate", "count", "distinct", "mapReduce", "geoSearch", "delete", "find", "findAndModify",
      "insert", "update", "collMod", "compact", "convertToCapped", "create", "createIndexes", "drop", "dropIndexes",
      "killCursors", "listIndexes", "reIndex"
    )

    private val CommandStartedEvent.collectionName: String?
      get() {
        fun BsonValue.stringOrNull(): String? {
          val stringValue = takeIf { it.isString }?.asString()?.value?.trim()
          return if (stringValue.isNullOrEmpty()) null else stringValue
        }

        if (this.commandName in COMMANDS_WITH_COLLECTION_NAME) {
          val collectionName = command[commandName]?.stringOrNull()
          if (collectionName != null) return collectionName
        }

        // Some commands (e.g. getMore) have a dedicated collection field
        return command["collection"]?.stringOrNull()
      }
  }
}