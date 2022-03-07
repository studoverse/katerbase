package com.moshbit.katerbase

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.reflect.KClass


class MongoDatabaseDefinition {
  class Collection<T : MongoMainEntry>(
    val modelClass: KClass<T>,
    val collectionName: String,
    val collectionSizeCap: Long?
  ) {
    class Index(val index: Bson, val partialIndex: Array<FilterPair>?, val indexOptions: (IndexOptions.() -> Unit)?) {
      init {
        partialIndex?.forEach { filterPair ->
          (filterPair.value as? Document)?.entries?.forEach { (operator: String, value: Any) ->
            // Not all operators are allowed for partial indexes, see https://docs.mongodb.com/manual/core/index-partial/#create-a-partial-index
            when {
              operator.removePrefix("$") in allowedPartialIndexQueryOperators -> Unit
              operator == "\$exists" && value == true -> {
                // $exists operator only allowed with value true
              }
              else -> throw IllegalArgumentException(
                "$operator cannot be used for partial indexes, see https://docs.mongodb.com/manual/core/index-partial/#create-a-partial-index"
              )
            }
          }
        }
      }

      companion object {
        private val allowedPartialIndexQueryOperators = setOf("gt", "gte", "lt", "lte", "eq", "type")
      }
    }

    val indexes = mutableListOf<Index>()

    fun index(vararg index: Bson, partialIndex: Array<FilterPair>? = null, indexOptions: (IndexOptions.() -> Unit)? = null) {
      when (index.count()) {
        0 -> throw IllegalArgumentException("Index must be specified")
        1 -> indexes.add(Index(index.first(), partialIndex, indexOptions))
        else -> indexes.add(Index(Indexes.compoundIndex(*index), partialIndex, indexOptions))
      }
    }

    fun MongoEntryField<*>.ascending(): Bson = Indexes.ascending(name)

    fun MongoEntryField<*>.descending(): Bson = Indexes.descending(name)

    // https://docs.mongodb.com/manual/text-search/#text-index
    fun MongoEntryField<*>.textIndex(): Bson = Indexes.text(name)
  }

  val collections = mutableListOf<Collection<*>>()

  inline fun <reified T : MongoMainEntry> collection(
    collectionName: String,
    collectionSizeCap: Long? = null,
    noinline indexes: (Collection<T>.() -> Unit)? = null
  ) {
    require(collections.none { it.modelClass == T::class }) {
      "Duplicate modelClass definition in database"
    }

    collections.add(Collection(T::class, collectionName, collectionSizeCap).apply { indexes?.invoke(this) })
  }
}