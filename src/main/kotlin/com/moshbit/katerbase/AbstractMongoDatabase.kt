package com.moshbit.katerbase

import kotlin.reflect.KClass

abstract class AbstractMongoDatabase {
  abstract fun <T : MongoMainEntry> getSuspendingCollection(entryClass: KClass<T>): MongoDatabase.SuspendingMongoCollection<T>
  inline fun <reified T : MongoMainEntry> getSuspendingCollection() = getSuspendingCollection(entryClass = T::class)

  abstract fun <T : MongoMainEntry> getCollection(entryClass: KClass<T>): MongoDatabase.MongoCollection<T>
  inline fun <reified T : MongoMainEntry> getCollection() = getCollection(entryClass = T::class)
}