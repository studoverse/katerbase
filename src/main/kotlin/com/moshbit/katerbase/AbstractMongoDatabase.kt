package com.moshbit.katerbase

import kotlin.reflect.KClass

abstract class AbstractMongoDatabase {
  abstract fun <T : MongoMainEntry> getCollection(entryClass: KClass<T>): MongoDatabase.MongoCollection<T>
  inline fun <reified T : MongoMainEntry> getCollection() = getCollection(entryClass = T::class)
}