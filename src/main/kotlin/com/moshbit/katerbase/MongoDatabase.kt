package com.moshbit.katerbase

import ch.qos.logback.classic.Level
import com.mongodb.*
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.*
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1

// TODO Constructor parameters vs. abstract variables
abstract class MongoDatabase(uri: String, allowReadFromSecondaries: Boolean = false, createNonExistentCollections: Boolean = false) {
  private val localMode: Boolean = "localhost" in uri || "127.0.0.1" in uri // TODO should be configurable
  protected val isReplicaSet: Boolean
  protected val client: MongoClient
  protected val database: MongoDatabase
  val mongoCollections: Map<KClass<out MongoMainEntry>, MongoCollection<out MongoMainEntry>>

  abstract fun getCollections(): Map<out KClass<out MongoMainEntry>, String>

  abstract fun getCappedCollectionsMaxBytes(): Map<out KClass<out MongoMainEntry>, Long>

  open fun <T : MongoMainEntry> getCollection(entryClass: KClass<T>): MongoCollection<T> {
    @Suppress("UNCHECKED_CAST")
    return mongoCollections[entryClass] as? MongoCollection<T>
      ?: throw IllegalArgumentException("No collection exists for ${entryClass.simpleName}")
  }

  inline fun <reified T : MongoMainEntry> getCollection(): MongoCollection<T> {
    return getCollection(entryClass = T::class)
  }

  class DuplicateKeyException(key: String) : IllegalStateException("Duplicate key: $key was already in collection.")

  init {
    // Disable mongo driver logging
    setLogLevel("org.mongodb", Level.OFF)

    val connectionString = ConnectionString(uri)
    isReplicaSet = connectionString.isMultiNodeSetup
    client = createMongoClientFromUri(connectionString, allowReadFromSecondaries)
    database = client.getDatabase(connectionString.database!!)
    mongoCollections = this.getCollections()
      .map { (clazz, collectionName) -> clazz to MongoCollection(database.getCollection(collectionName), clazz) }
      .toMap()

    if (createNonExistentCollections) {
      // Create collections which doesnt exist
      val newCollections = this.getCollections()
        .filter { it.value !in database.listCollectionNames() }

      val cappedCollectionsMaxBytes = this.getCappedCollectionsMaxBytes()

      if (newCollections.isNotEmpty()) {
        println("Creating ${newCollections.size} new collections:")
        newCollections.forEach { (collectionClass, collectionName) ->
          val cappedCollectionMaxBytes = cappedCollectionsMaxBytes[collectionClass]
          if (cappedCollectionMaxBytes == null) {
            database.createCollection(collectionName)
          } else {
            database.createCollection(collectionName, CreateCollectionOptions().capped(true).sizeInBytes(cappedCollectionMaxBytes))
          }

          println("Successfully created collection $collectionName")
        }
      }

      // Process indexes
      this.getIndexes()
      mongoCollections.forEach { (_, collection) -> collection.createIndexes() }
    } else {
      this.getIndexes() // Only create indexes in memory but do not actually create it in mongodb, because we need them when using hints
    }

    if (localMode) {
      thread {
        mongoCollections.keys.forEach { mongoEntryClass ->
          mongoEntryClass.java.declaredFields.forEach { field ->
            fun errorMessage(msg: String) = "Field error in ${mongoEntryClass.simpleName} -> ${field.name}: $msg"
            require(!field.name.startsWith("is")) { errorMessage("Can't start with 'is'") }
            require(!field.name.startsWith("set")) { errorMessage("Can't start with 'set'") }
            require(!field.name.startsWith("get")) { errorMessage("Can't start with 'get'") }
          }
        }
      }
    }
  }

  abstract fun getIndexes()

  // Mongo collection wrapper for Kotlin
  inner class MongoCollection<Entry : MongoMainEntry>(
    val collection: com.mongodb.client.MongoCollection<Document>,
    private val entryClazz: KClass<Entry>
  ) {

    val name: String get() = collection.namespace.collectionName

    inner class MongoIndex(val bson: Bson, val partialIndex: Document?) {
      val indexName: String

      init {
        val baseName = bson
          .toBsonDocument(BsonDocument::class.java, null)
          .toList()
          .joinToString(separator = "_", transform = { "${it.first}_${it.second.asInt32().value}" })

        val partialSuffix = partialIndex
          ?.map { (key, value) -> key to value }
          ?.joinToString(separator = "_", transform = { (operator, value) -> "${operator}_$value" })
          ?.let { suffix -> "_$suffix" } ?: ""

        indexName = baseName + partialSuffix
      }

      fun createIndex(): String? = collection.createIndex(bson, IndexOptions().background(true).name(indexName)
        .apply {
          if (partialIndex != null) {
            partialFilterExpression(partialIndex)
          }
        }
      )
    }

    private val indexes: MutableList<MongoIndex> = mutableListOf()

    fun createIndex(index: Bson, partialIndex: Array<FilterPair>? = null) =
      indexes.add(MongoIndex(bson = index, partialIndex = partialIndex?.toFilterDocument()))

    fun createIndexes() {
      val localIndexes = collection.listIndexes().toList().map { it["name"] as String }

      // Drop indexes which do not exist in the codebase anymore
      localIndexes
        .asSequence()
        .filter { indexName -> indexName != "_id_" } // Never drop _id
        .filter { indexName -> indexes.none { index -> index.indexName == indexName } }
        .forEach { indexName ->
          collection.dropIndex(indexName)
          println("Successfully dropped index $indexName")
        }

      // Create new indexes which doesn't exist locally
      indexes
        .filter { index -> index.indexName !in localIndexes }
        .forEach { index ->
          thread {
            // Don't wait for this, application can be started without the indexes
            println("Creating index ${index.indexName} ...")
            index.createIndex()
            println("Successfully creaded index ${index.indexName}")
          }
        }
    }

    fun getIndex(indexName: String) = indexes.singleOrNull { it.indexName == indexName }

    // Used to create indexes for childFields
    fun <Class, Value> MongoEntryField<out Any>.child(property: KMutableProperty1<Class, Value>): MongoEntryField<Value> {
      return this.toMongoField().extend(property.name).toProperty()
    }

    fun drop() {
      if (localMode) {
        collection.drop()
      } else {
        throw IllegalStateException("Dropping a collection on a production server is not supported. Do it with a database client UI.")
      }
    }

    fun clear() {
      if (localMode) {
        deleteMany()
      } else {
        throw IllegalStateException("Clearing a collection on a production server is not supported. Do it with a database client UI.")
      }
    }

    fun count(vararg filter: FilterPair): Long {
      return if (filter.isEmpty()) collection.estimatedDocumentCount() else collection.countDocuments(filter.toFilterDocument())
    }

    fun bulkWrite(options: BulkWriteOptions = BulkWriteOptions(), action: BulkOperation.() -> Unit): BulkWriteResult? {
      val models = BulkOperation().apply { action(this) }.models
      if (models.isEmpty()) return null
      return collection.bulkWrite(models, options)
    }

    private fun Document.toClass() = JsonHandler.fromBson(this, entryClazz)
    private fun FindIterable<Document>.toClasses() = FindCursor(this, entryClazz, this@MongoCollection)

    // Returns a MongoDocument as a list of mutators. Useful if you want to set all values in an update block (never set _id)
    private fun Entry.asMutator(withId: Boolean): List<MutatorPair<Any>> = this.toBSONDocument().map { (key, value) ->
      @Suppress("DEPRECATION")
      MutatorPair<Any>(MongoField(key), value)
    }.let { if (withId) it else it.filter { it.key.name != "_id" } }

    // Single operators
    @Deprecated("Use only for hacks", ReplaceWith("find"))
    fun rawFind(vararg filter: FilterPair): FindIterable<Document> {
      return collection.find(filter.toFilterDocument())
    }

    fun find(vararg filter: FilterPair): FindCursor<Entry> {
      return collection.find(filter.toFilterDocument()).toClasses()
    }

    fun findOne(vararg filter: FilterPair): Entry? {
      if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
      return find(*filter).limit(1).firstOrNull()
    }

    // Returns a document or inserts the document and then returns it.
    // This works atomically but newEntry may be called even if the document exists
    fun findOneOrCreate(vararg filter: FilterPair, setWithId: Boolean = false, newEntry: () -> Entry): Entry {
      if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
      // This is a performance optimization, when using updateOneAndFind the document is locked
      return findOne(*filter) ?: updateOneAndFind(*filter, upsert = true) {
        @Suppress("DEPRECATION") // Use this because the properties already lost their types to use them as properties
        setOnInsert(*newEntry().asMutator(withId = setWithId).toTypedArray())
      }!!
    }


    /**
     * Use this if you need a set of distinct specific value of a document
     * More info: https://docs.mongodb.com/manual/reference/method/db.collection.distinct/
     */
    fun <T : Any> distinct(distinctField: MongoEntryField<T>, entryClazz: KClass<T>, vararg filter: FilterPair): DistinctCursor<T> {
      return DistinctCursor(
        mongoIterable = collection.distinct(
          /*fieldName = */ distinctField.name,
          /*filter = */ filter.toFilterDocument(),
          /*resultClass = */ entryClazz.java
        ),
        clazz = entryClazz
      )
    }

    inline fun <reified T : Any> distinct(distinctField: MongoEntryField<T>, vararg filter: FilterPair): DistinctCursor<T> {
      return distinct(distinctField, T::class, *filter)
    }

    fun updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult {
      if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
      val mutator = UpdateOperation().apply { update(this) }.mutator
      if (mutator.isEmpty()) return UpdateResult.unacknowledged() // Due to if branches, we might end up with an "empty" update
      return collection.updateOne(filter.toFilterDocument(), mutator, UpdateOptions().upsert(false))
    }

    fun updateOneOrCreate(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult {
      if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
      if (localMode) require(filter.any { it.key.name == "_id" }) { "An _id must be specified for document creation" }
      val mutator = UpdateOperation().apply { update(this) }.mutator

      fun executeOperation() = collection.updateOne(filter.toFilterDocument(), mutator, UpdateOptions().upsert(true))

      return try {
        executeOperation()
      } catch (e: MongoWriteException) {
        // Fix https://jira.mongodb.org/browse/SERVER-14322: Try once again. The second time should always work
        if (e.code == 11000 && e.message?.matches(Regex("E11000 duplicate key error collection: .* index: _id_ dup key:.*")) == true) {
          executeOperation()
        } else {
          throw e // Every other exception than duplicate key
        }
      }
    }

    fun updateOneAndFind(vararg filter: FilterPair, upsert: Boolean = false, update: UpdateOperation.() -> Unit): Entry? {
      if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
      val mutator = UpdateOperation().apply { update(this) }.mutator
      val options = FindOneAndUpdateOptions().apply {
        upsert(upsert)
        returnDocument(ReturnDocument.AFTER)
      }

      fun executeOperation() = collection.findOneAndUpdate(filter.toFilterDocument(), mutator, options)?.toClass()

      return try {
        executeOperation()
      } catch (e: MongoWriteException) {
        // Fix https://jira.mongodb.org/browse/SERVER-14322: Try once again. The second time should always work
        if (e.code == 11000 && e.message?.matches(Regex("E11000 duplicate key error collection: .* index: _id_ dup key:.*")) == true) {
          executeOperation()
        } else {
          throw e // Every other exception than duplicate key
        }
      }
    }

    fun updateMany(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult {
      val mutator = UpdateOperation().apply { update(this) }.mutator
      if (mutator.isEmpty()) return UpdateResult.unacknowledged() // Due to if branches, we might end up with an "empty" update
      return collection.updateMany(filter.toFilterDocument(), mutator)
    }

    /** Throws on duplicate key when upsert=false */
    fun insertOne(document: Entry, upsert: Boolean) {
      if (upsert) {
        fun executeReplaceOneOperation() = collection.replaceOne(
          Document().apply { put("_id", document._id) },
          document.toBSONDocument(),
          ReplaceOptions().upsert(true)
        )

        try {
          executeReplaceOneOperation()
        } catch (e: MongoWriteException) {
          // Fix https://jira.mongodb.org/browse/SERVER-14322: Try once again. The second time should always work
          if (e.code == 11000 && e.message?.matches(Regex("E11000 duplicate key error collection: .* index: _id_ dup key:.*")) == true) {
            executeReplaceOneOperation()
          } else {
            throw e // Every other exception than duplicate key
          }
        }
      } else {
        insertOne(document, onDuplicateKey = { throw DuplicateKeyException(key = document._id) })
      }
    }

    fun insertMany(documents: List<Entry>, upsert: Boolean) {
      bulkWrite { insertMany(documents, upsert) }
    }

    fun insertOne(document: Entry, onDuplicateKey: (() -> Unit)) {
      try {
        collection.insertOne(document.toBSONDocument())
      } catch (e: MongoWriteException) {
        if (e.code == 11000 && e.message?.matches(Regex("E11000 duplicate key error collection: .* index: _id_ dup key:.*")) == true) {
          onDuplicateKey.invoke()
        } else {
          throw e // Every other exception than duplicate key
        }
      }
    }

    fun deleteOne(vararg filter: FilterPair): DeleteResult {
      if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
      return collection.deleteOne(filter.toFilterDocument())
    }

    fun deleteMany(vararg filter: FilterPair): DeleteResult = collection.deleteMany(filter.toFilterDocument())

    // Update operators
    inner class UpdateOperation {
      private val mutatorPairs: MutableMap<String, MutableList<MongoPair>> = LinkedHashMap()

      val mutator: Document
        get() = mutatorPairs.map { (op, values) -> "$$op" to values.toTypedArray().toFilterDocument() }.toMap(Document())

      @Deprecated("")
      private fun updateMutator(mutator: String, mutators: Array<out MongoPair>) {
        mutatorPairs.getOrPut(mutator) { mutableListOf() }.addAll(mutators)
      }

      private fun updateMutator(operator: String, mutator: MongoPair) {
        mutatorPairs.getOrPut(operator) { mutableListOf() }.add(mutator)
      }

      /**
       * Use this if you want to modify a subdocument's field
       */
      fun <Class, Value> MongoEntryField<out Any>.child(property: KMutableProperty1<Class, Value>): MongoEntryField<Value> {
        return this.toMongoField().extend(property.name).toProperty()
      }

      @JvmName("childOnNullableProperty")
      fun <Class, Value> NullableMongoEntryField<out Any>.child(property: KMutableProperty1<Class, Value>): MongoEntryField<Value> {
        return this.toMongoField().extend(property.name).toProperty()
      }

      /**
       * Use this if you want to modify a map's field
       */
      fun <Value> MongoEntryField<Map<String, Value>>.child(key: String): MongoEntryField<Value> {
        return this.toMongoField().extend(key).toProperty()
      }

      @JvmName("childOnNullableKey")
      fun <Value> NullableMongoEntryField<Map<String, Value>>.child(key: String): MongoEntryField<Value> {
        return this.toMongoField().extend(key).toProperty()
      }

      fun <Key : Enum<*>, Value> MongoEntryField<Map<Key, Value>>.child(key: Key): MongoEntryField<Value> {
        return this.toMongoField().extend(key.name).toProperty()
      }

      /**
       * Use this if you want to update a subdocument in an array.
       * More info: https://docs.mongodb.com/manual/reference/operator/update/positional/#update-documents-in-an-array
       */
      fun <Class, Value> MongoEntryField<out Any>.childWithCursor(property: KMutableProperty1<Class, Value>): MongoEntryField<Value> {
        return this.toMongoField().extendWithCursor(property.name).toProperty()
      }

      /**
       * Use this if you want to modify a field
       * More info: https://docs.mongodb.com/manual/reference/operator/update/set/
       */
      infix fun <Value> MongoEntryField<Value>.setTo(value: Value) {
        updateMutator(operator = "set", mutator = MutatorPair(this, value))
      }

      /**
       * Use this if you want to remove a field
       * More info: https://docs.mongodb.com/manual/reference/operator/update/unset/
       */
      fun <T> MongoEntryField<T>.unset() {
        @Suppress("DEPRECATION")
        updateMutator(operator = "unset", mutator = UnsetPair(this))
      }

      // TODO do something with this function
      @Suppress("DEPRECATION")
      @Deprecated("Use the other setOnInsert syntax")
      fun setOnInsert(vararg values: MutatorPair<*>) = updateMutator("setOnInsert", values)

      /**
       * Use this in combination with [updateOneOrCreate] if you want to set specific fields if a new document is created
       * More info: https://docs.mongodb.com/manual/reference/operator/update/setOnInsert/
       */
      infix fun <Value> MongoEntryField<Value>.setToOnInsert(value: Value) {
        updateMutator(operator = "setOnInsert", mutator = MutatorPair(this, value))
      }

      /**
       * Use this if you want to increment or decrement a numeric field
       * More info: https://docs.mongodb.com/manual/reference/operator/update/inc/
       */
      infix fun <Value : Number> MongoEntryField<Value>.incrementBy(value: Value) {
        updateMutator(operator = "inc", mutator = MutatorPair(this, value))
      }

      /**
       * Use this if you want to multiply or divide a numeric field
       * More info: https://docs.mongodb.com/manual/reference/operator/update/mul/
       */
      infix fun <Value : Number> MongoEntryField<Value>.multiplyBy(value: Value) {
        updateMutator(operator = "mul", mutator = MutatorPair(this, value))
      }

      /**
       * Use this if you want to push a new item to a list
       * More info: https://docs.mongodb.com/manual/reference/operator/update/push/
       */
      infix fun <Value> MongoEntryField<List<Value>>.push(value: Value) {
        updateMutator(operator = "push", mutator = PushPair(this, value))
      }

      /**
       * Use this if you want to push a new item to a set
       * This only works with primitives!!!!
       * More info: https://docs.mongodb.com/manual/reference/operator/update/addToSet/
       */
      @JvmName("pushToSet")
      infix fun <Value> MongoEntryField<Set<Value>>.push(value: Value) {
        @Suppress("DEPRECATION") // Use the hack version because List != Set
        updateMutator(operator = "addToSet", mutator = PushPair<Value>(this.toMongoField(), value))
      }

      /**
       * Use this if you want to remove an item from a list or set
       * More info: https://docs.mongodb.com/manual/reference/operator/update/pull/
       */
      infix fun <Value> MongoEntryField<List<Value>>.pull(value: Value) {
        updateMutator(operator = "pull", mutator = PushPair(this, value))
      }

      /**
       * Use this if you want to remove an item from a list or set
       * More info: https://docs.mongodb.com/manual/reference/operator/update/pull/
       */
      @JvmName("pullFromSet")
      infix fun <Value> MongoEntryField<Set<Value>>.pull(value: Value) {
        @Suppress("DEPRECATION")
        updateMutator(operator = "pull", mutator = PushPair<Value>(this.toMongoField(), value))
      }

      /**
       * Use this if you want to remove an subdocument with specific criteria from a list or set
       * More info: https://docs.mongodb.com/manual/reference/operator/update/pull/
       */
      fun <Value> MongoEntryField<List<Value>>.pullWhere(vararg filter: FilterPair) {
        updateMutator(operator = "pull", mutator = PushPair(this, org.bson.Document(filter.map { it.key.fieldName to it.value }.toMap())))
      }
    }

    // Bulk write operators
    inner class BulkOperation {
      val models = mutableListOf<WriteModel<Document>>()

      fun updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit) {
        if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
        val mutator = UpdateOperation().apply { update(this) }.mutator
        if (mutator.isNotEmpty()) {
          models.add(UpdateOneModel(filter.toFilterDocument(), mutator))
        }
      }

      fun updateMany(vararg filter: FilterPair, update: UpdateOperation.() -> Unit) {
        val mutator = UpdateOperation().apply { update(this) }.mutator
        if (mutator.isNotEmpty()) {
          models.add(UpdateManyModel(filter.toFilterDocument(), mutator))
        }
      }

      fun insertOne(document: Entry, upsert: Boolean) {
        if (upsert) {
          models.add(
            ReplaceOneModel(
              Document().apply { put("_id", document._id) },
              document.toBSONDocument(),
              ReplaceOptions().upsert(true)
            )
          )
        } else {
          models.add(InsertOneModel(document.toBSONDocument()))
        }
      }

      fun insertMany(documents: List<Entry>, upsert: Boolean) {
        documents.forEach { insertOne(it, upsert) }
      }

      fun deleteOne(vararg filter: FilterPair): Boolean {
        if (filter.isEmpty()) throw IllegalArgumentException("A filter must be provided when interacting with only one object.")
        return models.add(DeleteOneModel(filter.toFilterDocument()))
      }

      fun deleteMany(vararg filter: FilterPair) = models.add(DeleteManyModel(filter.toFilterDocument()))
    }
  }

  companion object {
    private val ConnectionString.isMultiNodeSetup get() = hosts.size > 1 // true if replication or sharding is in place

    fun createMongoClientFromUri(connectionString: ConnectionString, allowReadFromSecondaries: Boolean): MongoClient {
      val clientSettings = MongoClientSettings.builder()
        .applyConnectionString(connectionString)
        .apply {
          readConcern(ReadConcern.LOCAL)
          when {
            connectionString.isMultiNodeSetup && allowReadFromSecondaries -> {
              // Only allow read from secondaries if it is a multi node setup
              // Set maxStalenessSeconds, see https://docs.mongodb.com/manual/core/read-preference/#maxstalenessseconds
              readPreference(ReadPreference.secondaryPreferred(90L, TimeUnit.SECONDS))
              writeConcern(WriteConcern.MAJORITY)
            }
            connectionString.isMultiNodeSetup -> {
              readPreference(ReadPreference.primaryPreferred())
              writeConcern(WriteConcern.W1)
            }
            else -> {
              readPreference(ReadPreference.primary())
              writeConcern(WriteConcern.W1)
            }
          }
        }
        .build()

      return MongoClients.create(clientSettings)
    }
  }
}