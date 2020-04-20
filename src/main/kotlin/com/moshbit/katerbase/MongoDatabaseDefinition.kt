package com.moshbit.katerbase

import com.mongodb.client.model.IndexOptions
import org.bson.conversions.Bson
import kotlin.reflect.KClass


class MongoDatabaseDefinition {
  class Collection<T : MongoMainEntry>(
    val modelClass: KClass<T>,
    val collectionName: String,
    val collectionSizeCap: Long?
  ) {
    class Index(val index: Bson, val partialIndex: Array<FilterPair>?, val indexOptions: (IndexOptions.() -> Unit)?)

    val indexes = mutableListOf<Index>()

    fun index(index: Bson, partialIndex: Array<FilterPair>? = null, indexOptions: (IndexOptions.() -> Unit)? = null) {
      indexes.add(Index(index, partialIndex, indexOptions))
    }
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