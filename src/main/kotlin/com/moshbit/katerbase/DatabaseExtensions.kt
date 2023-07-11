package com.moshbit.katerbase

import com.fasterxml.jackson.core.JsonProcessingException
import com.mongodb.MongoCursorNotFoundException
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.AggregateFlow
import com.mongodb.kotlin.client.coroutine.FindFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.Document
import org.bson.conversions.Bson
import java.io.IOException
import kotlin.reflect.KClass

class FlowAggregateCursor<Entry : Any>(
  aggregateFlow: AggregateFlow<Document>,
  val clazz: KClass<Entry>
) : Flow<Entry> by flowForDocumentClass(aggregateFlow, clazz)

abstract class AbstractFindCursor<Entry : MongoMainEntry, Cursor : AbstractFindCursor<Entry, Cursor>>(
  val mongoIterable: FindFlow<Document>,
  val clazz: KClass<Entry>,
  val collection: MongoDatabase.MongoCollection<Entry>
) {
  protected var limit = 0
  protected var skip = 0
  protected var batchSize = 0
  protected var projection = BsonDocument()
  protected var sort: Bson? = null
  protected var hint: Bson? = null
  protected val mongoFilter: Bson by lazy {
    val wrapped = wrappedGetter.get(mongoIterable)
    val filter = filterGetter.get(wrapped)
    filter as Bson
  }

  // Safe to do because Entry always stays the same.
  // Needed for config functions (limit(), skip()...) to return `this` with the correct type and not AbstractFindCursor
  @Suppress("UNCHECKED_CAST")
  private val cursor: Cursor
    get() = this as Cursor

  /* Limit number of returned objects */
  fun limit(limit: Int): Cursor = cursor.apply {
    mongoIterable.limit(limit)
    this.limit = limit
  }

  fun skip(skip: Int): Cursor = cursor.apply {
    mongoIterable.skip(skip)
    this.skip = skip
  }

  fun batchSize(batchSize: Int): Cursor = cursor.apply {
    mongoIterable.batchSize(batchSize)
    this.batchSize = batchSize
  }

  fun hint(indexName: String): Cursor =
    hint(
      collection.getIndex(indexName)
        ?: throw IllegalArgumentException("Index $indexName was not found in collection ${collection.name}")
    )

  fun hint(index: MongoDatabase.MongoCollection<Entry>.MongoIndex): Cursor = cursor.apply {
    mongoIterable.hint(index.bson)
    this.hint = index.bson
  }

  @Deprecated("Use only for hacks")
  fun projection(bson: Bson): Cursor = cursor.apply {
    mongoIterable.projection(bson)
  }

  fun <T> selectedFields(vararg fields: MongoEntryField<out T>): Cursor = cursor.apply {
    val bson = fields.includeBson()
    this.projection.combine(bson)
    mongoIterable.projection(this.projection)
  }

  @Deprecated(
    "Excluding fields is an anti-pattern and is not maintainable. Always use selected fields. " +
        "You can also structure your db-object into sub-objects so you only need to select one field."
  )
  fun <T> excludeFields(vararg fields: MongoEntryField<out T>): Cursor = cursor.apply {
    val bson = fields.excludeBson()
    this.projection.combine(bson)
    mongoIterable.projection(this.projection)
  }

  @Deprecated("Use only for hacks")
  fun sort(bson: Bson): Cursor = cursor.apply {
    mongoIterable.sort(bson)
    this.sort = bson
  }

  fun <T> sortByDescending(field: MongoEntryField<T>): Cursor = cursor.apply {
    val fieldName = field.toMongoField().name
    val bson = Sorts.descending(fieldName)
    mongoIterable.sort(bson)
    this.sort = bson
  }

  fun <T> sortBy(field: MongoEntryField<T>): Cursor = cursor.apply {
    val fieldName = field.toMongoField().name
    val bson = Sorts.ascending(fieldName)
    mongoIterable.sort(bson)
    this.sort = bson
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as AbstractFindCursor<*, *>

    if (clazz != other.clazz) return false
    if (limit != other.limit) return false
    if (skip != other.skip) return false
    if (batchSize != other.batchSize) return false
    if (projection != other.projection) return false
    if (sort != other.sort) return false
    if (hint != other.hint) return false
    if (mongoFilter != other.mongoFilter) return false

    return true
  }

  override fun hashCode(): Int {
    var result = clazz.hashCode()
    result = 31 * result + limit
    result = 31 * result + skip
    result = 31 * result + batchSize
    result = 31 * result + projection.hashCode()
    result = 31 * result + (sort?.hashCode() ?: 0)
    result = 31 * result + (hint?.hashCode() ?: 0)
    result = 31 * result + (mongoFilter.hashCode())

    return result
  }

  companion object {
    private val wrappedGetter = Class.forName("com.mongodb.kotlin.client.coroutine.FindFlow").declaredFields
      .find { it.name == "wrapped" }!!
      .apply { isAccessible = true }

    private val filterGetter = Class.forName("com.mongodb.reactivestreams.client.internal.FindPublisherImpl").declaredFields
      .find { it.name == "filter" }!!
      .apply { isAccessible = true }
  }
}

class FlowFindCursor<Entry : MongoMainEntry>(
  mongoIterable: FindFlow<Document>,
  clazz: KClass<Entry>,
  collection: MongoDatabase.MongoCollection<Entry>,
) : AbstractFindCursor<Entry, FlowFindCursor<Entry>>(mongoIterable, clazz, collection),
  Flow<Entry> by flowForDocumentClass(mongoIterable, clazz) {

  @Deprecated("Flow analogue of 'forEach' is 'collect'", replaceWith = ReplaceWith("collect(block)"))
  suspend inline fun forEach(noinline block: (Entry) -> Unit) = collect(block)
}

private fun <Entry : Any> flowForDocumentClass(
  mongoIterable: Flow<Document>,
  clazz: KClass<Entry>
): Flow<Entry> = mongoIterable
  .map { document -> deserialize(document, clazz) }

private fun <Entry : Any> deserialize(document: Document, clazz: KClass<Entry>): Entry {
  return try {
    JsonHandler.fromBson(document, clazz)
  } catch (e: JsonProcessingException) {
    throw IllegalArgumentException(
      "Could not deserialize mongo entry of type ${clazz.simpleName} with id ${document["_id"]}. " +
          "This can be due to a schema conflict between database and the application. " +
          "Or in case of missing value a selectedFields()/excludeFields() statement that excludes then required values.", e
    )
  } catch (e: MongoCursorNotFoundException) {
    throw IOException(
      "Cursor was not (any more) found. " +
          "In case iterating over a large result and doing longer operations in the iteration, " +
          "consider using .batchSize() to not timeout the MongoCursor. " +
          "See https://docs.mongodb.com/manual/tutorial/iterate-a-cursor/#cursor-batches", e
    )
  }
}

fun BsonDocument.combine(other: BsonDocument): BsonDocument {
  other.forEach { (key, value) ->
    this.remove(key)
    this[key] = value
  }
  return this
}

fun combineBsonWithValue(keys: List<String>, value: Int) = BsonDocument().apply {
  keys.forEach { key -> this.append(key, BsonInt32(value)) }
}

fun <T> Array<out MongoEntryField<out T>>.includeBson() = combineBsonWithValue(map { it.toMongoField().name }, value = 1)
fun <T> Array<out MongoEntryField<out T>>.excludeBson() = combineBsonWithValue(map { it.toMongoField().name }, value = 0)
