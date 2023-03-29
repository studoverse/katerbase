package com.moshbit.katerbase

import ch.qos.logback.classic.Level
import com.mongodb.*
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.ClientSession
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.*
import com.mongodb.client.model.changestream.ChangeStreamDocument
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bson.*
import org.bson.conversions.Bson
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1

/**
 * How to set-up a local mongodb development environment (on OSX)
 * 1) Open a terminal ;)
 * 2) Install homebrew https://brew.sh/index_de
 * 3) Remove any existing mongodb installation from your machine
 * 4) Add the new official mongo brew repository, type "brew tap mongodb/brew"
 * 5) Install mongodb, type "brew install mongodb-community"
 * 6) Start the service, type "brew services start mongodb-community"
 * 7) Check if it worked using Studio 3t or just type "mongo" to open the mongo shell.
 */

open class MongoDatabase(
  uri: String,
  allowReadFromSecondaries: Boolean = false,
  useMajorityWrite: Boolean = allowReadFromSecondaries,
  private val supportChangeStreams: Boolean = false,
  autoCreateCollections: Boolean = true,
  autoCreateIndexes: Boolean = true,
  autoDeleteIndexes: Boolean = true,
  clientSettings: (MongoClientSettings.Builder.() -> Unit)? = null,
  collections: MongoDatabaseDefinition.() -> Unit
) : AbstractMongoDatabase() {
  protected val client: MongoClient
  protected val internalDatabase: com.mongodb.client.MongoDatabase
  protected val mongoCollections: Map<KClass<out MongoMainEntry>, MongoCollection<out MongoMainEntry>>
  protected val changeStreamClient: MongoClient?
  protected val changeStreamCollections: Map<KClass<out MongoMainEntry>, MongoCollection<out MongoMainEntry>>

  open override fun <T : MongoMainEntry> getCollection(entryClass: KClass<T>): MongoCollection<T> {
    @Suppress("UNCHECKED_CAST")
    return mongoCollections[entryClass] as? MongoCollection<T>
      ?: throw IllegalArgumentException("No collection exists for ${entryClass.simpleName}")
  }


  open override fun <T : MongoMainEntry> getSuspendingCollection(entryClass: KClass<T>): SuspendingMongoCollection<T> {
    return SuspendingMongoCollection(blockingCollection = getCollection(entryClass))
  }

  /**
   * Use the [TransactionalDatabase]s to modify the DB state in a transaction.
   * See https://www.mongodb.com/docs/manual/core/transactions/ for more info and examples.
   */
  suspend fun executeTransaction(action: suspend (database: TransactionalDatabase) -> Unit) {
    require(supportChangeStreams) { "supportChangeStreams must be true for the executeTransaction() operation, transactions are only supported on replica sets." }
    val transactionalDatabase = runOnIo { TransactionalDatabase() }

    // Sessions time out after 30 minutes, but close it now to save resources
    // https://www.mongodb.com/docs/manual/reference/command/startSession/
    transactionalDatabase.session.use { session ->
      runOnIo { session.startTransaction() }
      try {
        action(transactionalDatabase)
        runOnIo { session.commitTransaction() }
      } catch (throwable: Throwable) {
        runOnIo { session.abortTransaction() } // Do not commit transaction to DB in case of exception.
        throw throwable
      }
    }
  }

  inner class TransactionalDatabase : AbstractMongoDatabase() {
    val session: ClientSession = client.startSession(
      ClientSessionOptions.builder()
        .causallyConsistent(true)
        .snapshot(false)
        .defaultTransactionOptions(
          TransactionOptions.builder()
            .readPreference(ReadPreference.primary())
            .build()
        )
        .build()
    )

    inner class TransactionalCollection<Entry : MongoMainEntry>(mongoCollection: MongoCollection<Entry>) : MongoCollection<Entry>(
      internalCollection = mongoCollection.internalCollection,
      entryClass = mongoCollection.entryClass,
      session = session,
      indexes = emptyList(), // Indexes are already created so no need to declare them here.
    )

    override fun <T : MongoMainEntry> getCollection(entryClass: KClass<T>): MongoCollection<T> {
      return TransactionalCollection(mongoCollection = this@MongoDatabase.getCollection(entryClass))
    }

    override fun <T : MongoMainEntry> getSuspendingCollection(entryClass: KClass<T>): SuspendingMongoCollection<T> {
      val transactionalCollection = TransactionalCollection(mongoCollection = this@MongoDatabase.getCollection(entryClass))
      return SuspendingMongoCollection(blockingCollection = transactionalCollection)
    }

  }

  class DuplicateKeyException(key: String) : IllegalStateException("Duplicate key: $key was already in collection.")

  fun getDatabaseStats(): DatabaseStats {
    val commandResult = internalDatabase.runCommand(Document(mapOf("dbStats" to 1, "scale" to 1)))
    return JsonHandler.fromBson(commandResult, DatabaseStats::class)
  }

  init {
    // Disable mongo driver logging
    setLogLevel("org.mongodb", Level.ERROR)

    val connectionString = ConnectionString(uri)

    client = createMongoClientFromUri(
      connectionString, allowReadFromSecondaries = allowReadFromSecondaries, useMajorityWrite = useMajorityWrite,
      clientSettings = clientSettings
    )

    changeStreamClient = if (supportChangeStreams) {
      createMongoClientFromUri(connectionString, allowReadFromSecondaries = false, useMajorityWrite = false, clientSettings = {
        readPreference(ReadPreference.primaryPreferred())

        // ChangeStreams work until MongoDB 4.2 only with ReadConcern.MAJORITY, see https://docs.mongodb.com/manual/changeStreams/
        readConcern(ReadConcern.MAJORITY)

        clientSettings?.invoke(this)
      })
    } else {
      null
    }

    internalDatabase = client.getDatabase(connectionString.database!!)

    val databaseDefinition = MongoDatabaseDefinition().apply { collections() }

    mongoCollections = databaseDefinition.collections.associateBy(
      keySelector = { it.modelClass },
      valueTransform = {
        MongoCollection(
          internalCollection = internalDatabase.getCollection(it.collectionName),
          entryClass = it.modelClass,
          indexes = it.indexes,
        )
      }
    )

    changeStreamCollections = if (supportChangeStreams) {
      val changeStreamClientDatabase = changeStreamClient!!.getDatabase(connectionString.database!!)
      databaseDefinition.collections.associateBy(
        keySelector = { it.modelClass },
        valueTransform = {
          MongoCollection(
            internalCollection = changeStreamClientDatabase.getCollection(it.collectionName),
            entryClass = it.modelClass,
            indexes = it.indexes,
          )
        }
      )
    } else emptyMap()

    if (autoCreateCollections) {
      // Create collections which don't exist
      val newCollections = databaseDefinition.collections
        .filter { it.collectionName !in internalDatabase.listCollectionNames() }

      if (newCollections.isNotEmpty()) {
        println("Creating ${newCollections.size} new collections:")
        newCollections.forEach { newCollection ->
          if (newCollection.collectionSizeCap == null) {
            internalDatabase.createCollection(newCollection.collectionName)
          } else {
            internalDatabase.createCollection(
              newCollection.collectionName,
              CreateCollectionOptions().capped(true).sizeInBytes(newCollection.collectionSizeCap)
            )
          }

          println("Successfully created collection ${newCollection.collectionName}")
        }
      }
    }

    // Create and delete indexes in MongoDB
    mongoCollections.forEach { (_, collection) ->

      // Validate that index names are unique, otherwise the below creation/dropping on indexes won't work
      val indexNames = collection.indexes.map { it.indexName }
      require(indexNames.toSet().size == indexNames.size) {
        "Index names must be unique, but there are duplicates in ${collection.entryClass.simpleName}:\n${indexNames.joinToString("\n")}"
      }

      val existingIndexes = collection.internalCollection.listIndexes().toList().map { it["name"] as String }

      if (autoDeleteIndexes) {
        // Drop indexes which do not exist in the codebase anymore
        existingIndexes
          .filter { indexName -> indexName != "_id_" } // Never drop _id
          .filter { indexName -> collection.indexes.none { index -> index.indexName == indexName } }
          .forEach { indexName ->
            thread {
              collection.internalCollection.dropIndex(indexName)
              println("Successfully dropped index $indexName")
            }
          }
      }

      // Make sure all indices are dropped first before creating new indexes so in case we change a textIndex we don't throw because
      // only one text index per collection is allowed.

      if (autoCreateIndexes) {
        // Create new indexes which doesn't exist locally
        collection.indexes
          .filter { index -> index.indexName !in existingIndexes }
          .forEach { index ->
            thread {
              // Don't wait for this, application can be started without the indexes
              println("Creating index ${index.indexName} ...")
              index.createIndex()
              println("Successfully created index ${index.indexName}")
            }
          }
      }
    }

    // Validation for Jackson to avoid serialization/deserialization issues
    mongoCollections.keys.forEach { mongoEntryClass ->
      mongoEntryClass.java.declaredFields.forEach { field ->
        fun errorMessage(msg: String) = "Field error in ${mongoEntryClass.simpleName} -> ${field.name}: $msg"
        require(!field.name.startsWith("is")) { errorMessage("Can't start with 'is'") }
        require(!field.name.startsWith("set")) { errorMessage("Can't start with 'set'") }
        require(!field.name.startsWith("get")) { errorMessage("Can't start with 'get'") }
      }
    }
  }

  data class PayloadChange<Entry : MongoMainEntry>(
    val _id: String,
    val payload: Entry?, // Payload is not available when operationType is DELETED
    val operationType: OperationType,
    val internalChangeStreamDocument: ChangeStreamDocument<Document>,
  )

  // Mongo collection wrapper for Kotlin
  open inner class MongoCollection<Entry : MongoMainEntry>(
    val internalCollection: com.mongodb.client.MongoCollection<Document>,
    val entryClass: KClass<Entry>,
    val session: ClientSession? = null,
    indexes: List<MongoDatabaseDefinition.Collection.Index>,
  ) {

    val name: String get() = internalCollection.namespace.collectionName

    internal val indexes: List<MongoIndex> = indexes.map { MongoIndex(it) }

    /**
     * This only works if MongoDB is a replica set
     * To test this set up a local replica set (follow the README in local-development > local-mongo-replica-set)
     * Use a custom [pipeline] to include only a specific set of fields / changes.
     * Operation types: https://www.mongodb.com/docs/manual/reference/change-events/
     */
    fun watch(pipeline: Document = defaultWatchPipeline, action: (Result<PayloadChange<Entry>>) -> Unit) {
      require(supportChangeStreams) { "supportChangeStreams must be true for the watch() operation, change streams are only supported on replica sets." }

      val internalCollection = changeStreamCollections.getValue(entryClass).internalCollection

      var watchStartedSemaphore: Semaphore? = Semaphore(0)

      fun executeActionSafely(result: Result<PayloadChange<Entry>>) {
        try {
          action(result)
        } catch (e: Exception) {
          // If action fails, handle the exception but do not close the changeStream
          Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), e) ?: thread { throw e }
        }
      }

      thread(isDaemon = true, name = "katerbase-watch-${name}-${Random().nextInt().absoluteValue}") {
        fun watch() = if (session != null) {
          internalCollection.watch(session, listOf(pipeline))
        } else {
          internalCollection.watch(listOf(pipeline))
        }

        while (true) {
          try {
            watch()
              .apply { fullDocument(FullDocument.UPDATE_LOOKUP) }
              .iterator() // Calling the iterator actually executes the query on the DB server.
              .also {
                // Query has been executed, so now it's safe to return for the watch function.
                // This makes sure that when watch returns, changes are already coming in.
                watchStartedSemaphore?.release()
              }
              .forEach { document: ChangeStreamDocument<Document> ->
                val change = PayloadChange(
                  _id = (document.documentKey!!["_id"] as BsonString).value,
                  payload = document.fullDocument?.let { JsonHandler.fromBson(it, entryClass) },
                  operationType = document.operationType,
                  internalChangeStreamDocument = document,
                )
                executeActionSafely(Result.success(change))
              }
          } catch (e: Exception) {
            if ((e as? MongoCommandException)?.code == 40573) {
              // The $changeStream stage is only supported on replica sets
              throw IllegalStateException("watch() can only be used in a replica set", e)
            } else {
              executeActionSafely(Result.failure(e))
            }
          }
        }
      }

      watchStartedSemaphore!!.acquire()
      watchStartedSemaphore = null
    }

    // The index name is based on bson and partialIndex. Therefore when changing the bson or the partialIndex, the old index
    // will get deleted and a new index is created.
    // Keep in mind that changing indexOptions do not create a new index, so you need to manually delete the index or modify the index
    // appropriately in the database.
    inner class MongoIndex(indexDefinition: MongoDatabaseDefinition.Collection.Index) {
      val bson: Bson = indexDefinition.index
      val partialIndex: Document? = indexDefinition.partialIndex?.toFilterDocument()
      val indexOptions: (IndexOptions.() -> Unit)? = indexDefinition.indexOptions
      val indexName: String

      private inner class PartialIndexFilter(val fieldName: String, val operator: String, val value: Any?) {
        val valueAsString: String = when (value) {
          is Date -> value.toIsoString()
          else -> value.toString()
        }

        override fun toString(): String = "$fieldName->{$operator:$valueAsString}"
      }

      init {
        fun BsonValue.toIndexValue() = when (this) {
          is BsonNumber -> this.intValue()
          is BsonString -> this.value
          else -> throw IllegalArgumentException("Invalid index value")
        }

        val baseName = bson
          .toBsonDocument(BsonDocument::class.java, null)
          .toList()
          .joinToString(separator = "_", transform = { "${it.first}_${it.second.toIndexValue()}" })

        val partialSuffix = partialIndex
          ?.map { (key, value) ->
            when (value) {
              is Document -> PartialIndexFilter(fieldName = key, operator = value.entries.first().key, value = value.entries.first().value)
              else -> PartialIndexFilter(fieldName = key, operator = "\$eq", value = value)
            }
          }
          ?.joinToString(separator = "_", transform = { it.toString() })
          ?.let { suffix -> "_$suffix" } ?: ""

        this.indexName = (baseName + partialSuffix).ensureMaxIndexLength()
      }

      // Ensure indexes are not too long on older mongodb versions, see https://www.mongodb.com/docs/v4.0/reference/limits/#Index-Name-Length
      // The documentation states a length of 128, however the error message when creating a too long index says 127.
      private fun String.ensureMaxIndexLength(): String {
        val maxIndexLength = 127 - internalCollection.namespace.fullName.length - ".\$".length

        return if (this.length > maxIndexLength) {
          val hashLength = 16
          val hashDelimiter = "-"
          // Append original indexName hash, so we probably don't have 2 indexes with the same name.
          // We still check for duplicates when we created all indexes, so hash collisions won't cause index problems.
          this.take(maxIndexLength - hashLength - hashDelimiter.length) + hashDelimiter + this.sha256().take(hashLength)
        } else {
          this
        }
      }

      fun createIndex(): String? = internalCollection.createIndex(bson, IndexOptions()
        .background(true)
        .apply {
          if (partialIndex != null) {
            partialFilterExpression(partialIndex)
          }
        }
        .apply { indexOptions?.invoke(this) }
        .name(indexName) // Set name after indexOptions invoke, as our index management relies on that name
      )
    }

    fun getIndex(indexName: String): MongoIndex? = indexes.singleOrNull { it.indexName == indexName }

    // Used to create indexes for childFields
    fun <Value> MongoEntryField<out Any>.child(property: MongoEntryField<Value>): MongoEntryField<Value> {
      return this.toMongoField().extend(property.name).toProperty()
    }

    fun drop() {
      internalCollection.drop()
    }

    fun clear(): DeleteResult {
      return deleteMany()
    }

    fun count(vararg filter: FilterPair): Long {
      if (logAllQueries) println("count: ${filter.toFilterDocument().asJsonString()}")
      return when {
        session != null -> internalCollection.countDocuments(session, filter.toFilterDocument())
        filter.isEmpty() -> internalCollection.estimatedDocumentCount()
        else -> internalCollection.countDocuments(filter.toFilterDocument())
      }
    }

    fun bulkWrite(options: BulkWriteOptions = BulkWriteOptions(), action: BulkOperation.() -> Unit): BulkWriteResult {
      val models = BulkOperation().apply { action(this) }.models
      if (models.isEmpty()) {
        // Acknowledge empty bulk write
        return BulkWriteResult.acknowledged(0, 0, 0, 0, emptyList(), emptyList())
      }
      return if (session != null) {
        internalCollection.bulkWrite(session, models, options)
      } else {
        internalCollection.bulkWrite(models, options)
      }
    }

    private fun Document.toClass() = JsonHandler.fromBson(this, entryClass)
    private fun FindIterable<Document>.toClasses() = FindCursor(this, entryClass, this@MongoCollection)

    // Returns a MongoDocument as a list of mutators. Useful if you want to set all values in an update block.
    // In case _id should be included in the mutator, set withId to true.
    private fun Entry.asMutator(withId: Boolean): List<MutatorPair<Any>> = this.toBSONDocument().map { (key, value) ->
      @Suppress("DEPRECATION")
      MutatorPair<Any>(MongoField(key), value)
    }.let { mutator -> if (withId) mutator else mutator.filter { it.key.name != "_id" } }

    // Single operators
    @Deprecated("Use only for hacks", ReplaceWith("find"))
    fun findDocuments(vararg filter: FilterPair): FindIterable<Document> {
      return internalCollection.find(filter.toFilterDocument())
    }

    fun <T : MongoEntry> aggregate(pipeline: AggregationPipeline, entryClass: KClass<T>): AggregateCursor<T> {
      return AggregateCursor(
        mongoIterable = if (session != null) {
          internalCollection.aggregate(
            /* clientSession = */ session,
            /* pipeline = */ pipeline.bson,
            /* resultClass = */ Document::class.java
          )
        } else {
          internalCollection.aggregate(
            /* pipeline = */ pipeline.bson,
            /* resultClass = */ Document::class.java
          )
        },
        clazz = entryClass
      )
    }

    inline fun <reified T : MongoEntry> aggregate(noinline pipeline: AggregationPipeline.() -> Unit): AggregateCursor<T> {
      return aggregate(
        pipeline = aggregationPipeline(pipeline),
        entryClass = T::class
      )
    }

    fun sample(size: Int): AggregateCursor<Entry> {
      return aggregate(
        pipeline = aggregationPipeline { sample(size) },
        entryClass = entryClass
      )
    }

    fun find(vararg filter: FilterPair): FindCursor<Entry> {
      if (logAllQueries) println("find: ${filter.toFilterDocument().asJsonString()} (pipeline: ${filter.getExecutionPipeline()})")
      return if (session != null) {
        internalCollection.find(session, filter.toFilterDocument())
      } else {
        internalCollection.find(filter.toFilterDocument())
      }.toClasses()
    }

    fun findOne(vararg filter: FilterPair): Entry? {
      require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }
      return find(*filter).limit(1).firstOrNull()
    }

    // Returns a document or inserts the document and then returns it.
    // This works atomically, so newEntry may be called even if the document exists
    fun findOneOrInsert(vararg filter: FilterPair, newEntry: () -> Entry): Entry {
      require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }

      // This is a performance optimization, when using updateOneAndFind the document is locked
      return findOne(*filter) ?: updateOneAndFind(*filter, upsert = true) {

        val useNewEntryId = filter.none { it.key.name == "_id" }
        val mutator = newEntry().asMutator(withId = useNewEntryId)
        if (useNewEntryId && mutator.none { it.key.name == "_id" && it.value != "" }) {
          throw IllegalArgumentException("_id must either be in filter or must be set in newEntry()")
        }

        mutator.forEach { updateMutator("setOnInsert", it) }
      }!!
    }

    /**
     * Use this if you need a set of distinct specific value of a document
     * More info: https://docs.mongodb.com/manual/reference/method/db.collection.distinct/
     */
    fun <T : Any> distinct(distinctField: MongoEntryField<T>, entryClass: KClass<T>, vararg filter: FilterPair): DistinctCursor<T> {
      return DistinctCursor(
        mongoIterable = if (session != null) {
          internalCollection.distinct(
            /* clientSession = */ session,
            /* fieldName = */ distinctField.name,
            /* filter = */ filter.toFilterDocument(),
            /* resultClass = */ entryClass.java
          )
        } else {
          internalCollection.distinct(
            /* fieldName = */ distinctField.name,
            /* filter = */ filter.toFilterDocument(),
            /* resultClass = */ entryClass.java
          )
        },
        clazz = entryClass
      )
    }

    inline fun <reified T : Any> distinct(distinctField: MongoEntryField<T>, vararg filter: FilterPair): DistinctCursor<T> {
      return distinct(distinctField, T::class, *filter)
    }

    fun updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult {
      require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }
      val mutator = UpdateOperation().apply { update(this) }.mutator
      if (mutator.isEmpty()) return UpdateResult.acknowledged(0, 0, null) // Due to if branches, we might end up with an "empty" update
      if (logAllQueries) println(buildString {
        append("updateOne:\n")
        append("   filter: ${filter.toFilterDocument().asJsonString()}\n")
        append("   mutator: ${mutator.asJsonString()}\n")
        append("   pipeline: ${filter.getExecutionPipeline()}\n")
      })
      return if (session != null) {
        internalCollection.updateOne(session, filter.toFilterDocument(), mutator, UpdateOptions().upsert(false))
      } else {
        internalCollection.updateOne(filter.toFilterDocument(), mutator, UpdateOptions().upsert(false))
      }
    }

    fun updateOneOrInsert(filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult {
      require(filter.key.name == "_id") {
        "An _id filter must be provided when interacting with only one object and no other filters are allowed to mitigate a DuplicateKeyException on update."
      }
      val mutator = UpdateOperation().apply { update(this) }.mutator

      if (logAllQueries) println(buildString {
        append("updateOneOrInsert:\n")
        append("   filter: ${arrayOf(filter).toFilterDocument().asJsonString()}\n")
        append("   mutator: ${mutator.asJsonString()}\n")
        append("   pipeline: ${arrayOf(filter).getExecutionPipeline()}\n")
      })

      return retryMongoOperationOnDuplicateKeyError {
        if (session != null) {
          internalCollection.updateOne(session, arrayOf(filter).toFilterDocument(), mutator, UpdateOptions().upsert(true))
        } else {
          internalCollection.updateOne(arrayOf(filter).toFilterDocument(), mutator, UpdateOptions().upsert(true))
        }
      }
    }

    fun updateOneAndFind(
      vararg filter: FilterPair,
      upsert: Boolean = false,
      returnDocument: ReturnDocument = ReturnDocument.AFTER,
      update: UpdateOperation.() -> Unit
    ): Entry? {
      require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }
      val mutator = UpdateOperation().apply { update(this) }.mutator
      val options = FindOneAndUpdateOptions().apply {
        upsert(upsert)
        returnDocument(returnDocument)
      }
      if (logAllQueries) println(buildString {
        append("updateOneAndFind:\n")
        append("   filter: ${filter.toFilterDocument().asJsonString()}\n")
        append("   mutator: ${mutator.asJsonString()}\n")
        append("   pipeline: ${filter.getExecutionPipeline()}\n")
      })

      return retryMongoOperationOnDuplicateKeyError {
        if (session != null) {
          internalCollection.findOneAndUpdate(session, filter.toFilterDocument(), mutator, options)?.toClass()
        } else {
          internalCollection.findOneAndUpdate(filter.toFilterDocument(), mutator, options)?.toClass()
        }
      }
    }

    fun updateMany(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult {
      val mutator = UpdateOperation().apply { update(this) }.mutator
      if (mutator.isEmpty()) return UpdateResult.acknowledged(0, 0, null) // Due to if branches, we might end up with an "empty" update
      if (logAllQueries) println(buildString {
        append("updateMany:\n")
        append("   filter: ${filter.toFilterDocument().asJsonString()}\n")
        append("   mutator: ${mutator.asJsonString()}\n")
        append("   pipeline: ${filter.getExecutionPipeline()}\n")
      })
      return if (session != null) {
        internalCollection.updateMany(session, filter.toFilterDocument(), mutator)
      } else {
        internalCollection.updateMany(filter.toFilterDocument(), mutator)
      }
    }

    /** Throws on duplicate key when upsert=false */
    fun insertOne(document: Entry, upsert: Boolean): Any /* InsertOneResult | UpdateResult */ {
      return if (upsert) {
        retryMongoOperationOnDuplicateKeyError {
          if (session != null) {
            internalCollection.replaceOne(
              session,
              Document().apply { put("_id", document._id) },
              document.toBSONDocument(),
              ReplaceOptions().upsert(true)
            )
          } else {
            internalCollection.replaceOne(
              Document().apply { put("_id", document._id) },
              document.toBSONDocument(),
              ReplaceOptions().upsert(true)
            )
          }
        }
      } else {
        insertOne(document, onDuplicateKey = { throw DuplicateKeyException(key = document._id) })!!
      }
    }

    fun insertOne(document: Entry, onDuplicateKey: (() -> Unit)): InsertOneResult? {
      try {
        return if (session != null) {
          internalCollection.insertOne(session, document.toBSONDocument())
        } else {
          internalCollection.insertOne(document.toBSONDocument())
        }
      } catch (e: MongoServerException) {
        if (e.code == 11000 && e.message?.matches(Regex(".*E11000 duplicate key error collection: .* index: _id_ dup key:.*")) == true) {
          onDuplicateKey.invoke()
          return null
        } else {
          throw e // Every other exception than duplicate key
        }
      }
    }

    fun deleteOne(vararg filter: FilterPair): DeleteResult {
      require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }
      if (logAllQueries) println("deleteOne: ${filter.toFilterDocument().asJsonString()}")
      return if (session != null) {
        internalCollection.deleteOne(session, filter.toFilterDocument())
      } else {
        internalCollection.deleteOne(filter.toFilterDocument())
      }
    }

    fun deleteMany(vararg filter: FilterPair): DeleteResult {
      if (logAllQueries) println("deleteMany: ${filter.toFilterDocument().asJsonString()}")
      return if (session != null) {
        internalCollection.deleteMany(session, filter.toFilterDocument())
      } else {
        internalCollection.deleteMany(filter.toFilterDocument())
      }
    }

    /**
     * This is an ongoing mongo issue
     * Some of this is mitigated in MongoDB 4.2 however not every operation can be retried, so this function retries every operation
     * that failed because of a *duplicate key error*.
     *
     * See https://jira.mongodb.org/browse/SERVER-14322 for more information on the specific supported and unsupported
     * operations
     */
    private fun <T> retryMongoOperationOnDuplicateKeyError(tries: Int = 2, operation: () -> T): T {
      return try {
        operation()
      } catch (e: MongoServerException) {
        if (e.code == 11000 &&
          e.message?.matches(Regex(".*E11000 duplicate key error collection: .* index: _id_ dup key:.*")) == true &&
          tries > 0
        ) {
          retryMongoOperationOnDuplicateKeyError(tries = tries - 1, operation = operation)
        } else {
          throw e // Every other exception than duplicate key
        }
      }
    }

    // Update operators
    inner class UpdateOperation {
      private val mutatorPairs: MutableMap<String, MutableList<MongoPair>> = LinkedHashMap()

      val mutator: Document
        get() = mutatorPairs.map { (op, values) -> "$$op" to values.toTypedArray().toFilterDocument() }.toMap(Document())

      internal fun updateMutator(operator: String, mutator: MongoPair) {
        mutatorPairs.getOrPut(operator) { mutableListOf() }.add(mutator)
      }

      fun randomId() = MongoMainEntry.randomId()
      fun secureRandomId() = MongoMainEntry.secureRandomId()

      fun generateId(compoundValue: String, vararg compoundValues: String): String =
        MongoMainEntry.generateId(compoundValue, *compoundValues)

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

      /**
       * Use this in combination with [updateOneOrInsert] if you want to set specific fields if a new document is created
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
       * Use this if you want to change value if the specified value is lower than the current value of the field
       * More info: https://docs.mongodb.com/manual/reference/operator/update/min/
       */
      infix fun <Value : Number> MongoEntryField<Value>.min(value: Value) {
        updateMutator(operator = "min", mutator = MutatorPair(this, value))
      }

      /**
       * Use this if you want to change value if the specified value is greater than the current value of the field
       * More info: https://docs.mongodb.com/manual/reference/operator/update/max/
       */
      infix fun <Value : Number> MongoEntryField<Value>.max(value: Value) {
        updateMutator(operator = "max", mutator = MutatorPair(this, value))
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
        updateMutator(operator = "pull", mutator = PushPair(this, Document(filter.map { it.key.fieldName to it.value }.toMap())))
      }
    }

    // Bulk write operators
    inner class BulkOperation {
      val models = mutableListOf<WriteModel<Document>>()

      fun updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit) {
        require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }
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

      fun deleteOne(vararg filter: FilterPair): Boolean {
        require(filter.isNotEmpty()) { "A filter must be provided when interacting with only one object." }
        return models.add(DeleteOneModel(filter.toFilterDocument()))
      }

      fun deleteMany(vararg filter: FilterPair): Unit {
        models.add(DeleteManyModel(filter.toFilterDocument()))
      }
    }

    private fun Array<out FilterPair>.getExecutionPipeline(): String = getQueryStats(filter = this).executionPipelineString

    fun getQueryStats(vararg filter: FilterPair, limit: Int? = null): QueryStats {
      val explainCommand = Document().apply {
        put("explain", Document().apply {
          put("find", name)
          put("filter", filter.toFilterDocument())
          if (limit != null) put("limit", limit)
        })
        put("verbosity", "executionStats")
      }
      val res = internalDatabase.runCommand(explainCommand)
      val executionStats = res.getValue("executionStats") as Document

      val winningPlan = (res.getValue("queryPlanner") as Document).getValue("winningPlan") as Document
      fun getPipeline(stage: Document): List<String> {
        val currentStagePipeline = listOf(stage.getValue("stage") as String)
        val inputStage = stage["inputStage"] as? Document ?: return currentStagePipeline // No more stages, so just return current stage
        return currentStagePipeline + getPipeline(inputStage)
      }

      return QueryStats(
        winningPlan = getPipeline(winningPlan),
        executionStatsRaw = executionStats,
      )
    }
  }

  inner class SuspendingMongoCollection<Entry : MongoMainEntry>(
    val blockingCollection: MongoCollection<Entry>
  ) {
    val name: String get() = blockingCollection.name
    val entryClass: KClass<Entry> get() = blockingCollection.entryClass

    fun watch(pipeline: Document = defaultWatchPipeline, changeBufferCapacity: Int = 64): Channel<Result<PayloadChange<Entry>>> {
      val channel = Channel<Result<PayloadChange<Entry>>>(changeBufferCapacity)

      blockingCollection.watch(pipeline) { change ->
        runBlocking { channel.send(change) }
      }

      return channel
    }

    suspend fun drop() {
      runOnIo { blockingCollection.drop() }
    }

    suspend fun clear(): DeleteResult {
      return runOnIo { blockingCollection.clear() }
    }

    suspend fun count(vararg filter: FilterPair): Long {
      return runOnIo { blockingCollection.count(*filter) }
    }

    suspend fun bulkWrite(
      options: BulkWriteOptions = BulkWriteOptions(),
      action: MongoCollection<Entry>.BulkOperation.() -> Unit
    ): BulkWriteResult {
      return runOnIo { blockingCollection.bulkWrite(options, action) }
    }

    suspend fun <T : MongoEntry> aggregate(pipeline: AggregationPipeline, entryClass: KClass<T>): FlowAggregateCursor<T> {
      return runOnIo { FlowAggregateCursor(blockingCollection.aggregate(pipeline, entryClass)) }
    }

    suspend inline fun <reified T : MongoEntry> aggregate(noinline pipeline: AggregationPipeline.() -> Unit): FlowAggregateCursor<T> {
      return aggregate(
        pipeline = aggregationPipeline(pipeline),
        entryClass = T::class
      )
    }

    suspend fun sample(size: Int): FlowAggregateCursor<Entry> {
      return aggregate(
        pipeline = aggregationPipeline { sample(size) },
        entryClass = entryClass
      )
    }

    suspend fun find(vararg filter: FilterPair): FlowFindCursor<Entry> {
      return withContext(Dispatchers.IO) {
        val cursor = blockingCollection.find(*filter)
        FlowFindCursor(cursor.mongoIterable, cursor.clazz, cursor.collection)
      }
    }

    suspend fun findOne(vararg filter: FilterPair): Entry? {
      return runOnIo { blockingCollection.findOne(*filter) }
    }

    suspend fun findOneOrInsert(vararg filter: FilterPair, newEntry: () -> Entry): Entry {
      return runOnIo { blockingCollection.findOneOrInsert(*filter, newEntry = newEntry) }
    }

    /**
     * Use this if you need a set of distinct specific value of a document
     * More info: https://docs.mongodb.com/manual/reference/method/db.collection.distinct/
     */
    suspend fun <T : Any> distinct(
      distinctField: MongoEntryField<T>,
      entryClass: KClass<T>,
      vararg filter: FilterPair
    ): FlowDistinctCursor<T> {
      return runOnIo {
        FlowDistinctCursor(
          mongoIterable = blockingCollection.internalCollection.distinct(
            /* fieldName = */ distinctField.name,
            /* filter = */ filter.toFilterDocument(),
            /* resultClass = */ entryClass.java
          ),
          clazz = entryClass
        )
      }
    }

    suspend inline fun <reified T : Any> distinct(distinctField: MongoEntryField<T>, vararg filter: FilterPair): FlowDistinctCursor<T> {
      return distinct(distinctField, T::class, *filter)
    }

    suspend fun updateOne(vararg filter: FilterPair, update: MongoCollection<Entry>.UpdateOperation.() -> Unit): UpdateResult {
      return runOnIo { blockingCollection.updateOne(*filter, update = update) }
    }

    suspend fun updateOneOrInsert(filter: FilterPair, update: MongoCollection<Entry>.UpdateOperation.() -> Unit): UpdateResult {
      return runOnIo { blockingCollection.updateOneOrInsert(filter, update = update) }
    }

    suspend fun updateOneAndFind(
      vararg filter: FilterPair,
      upsert: Boolean = false,
      returnDocument: ReturnDocument = ReturnDocument.AFTER,
      update: MongoCollection<Entry>.UpdateOperation.() -> Unit
    ): Entry? {
      return runOnIo { blockingCollection.updateOneAndFind(*filter, upsert = upsert, returnDocument = returnDocument, update = update) }
    }

    suspend fun updateMany(vararg filter: FilterPair, update: MongoCollection<Entry>.UpdateOperation.() -> Unit): UpdateResult {
      return runOnIo { blockingCollection.updateMany(*filter, update = update) }
    }

    suspend fun insertOne(document: Entry, upsert: Boolean) {
      return runOnIo { blockingCollection.insertOne(document, upsert) }
    }

    suspend fun insertOne(document: Entry, onDuplicateKey: (() -> Unit)): InsertOneResult? {
      return runOnIo { blockingCollection.insertOne(document, onDuplicateKey) }
    }

    suspend fun deleteOne(vararg filter: FilterPair): DeleteResult {
      return runOnIo { blockingCollection.deleteOne(*filter) }
    }

    suspend fun deleteMany(vararg filter: FilterPair): DeleteResult {
      return runOnIo { blockingCollection.deleteMany(*filter) }
    }

    suspend fun getQueryStats(vararg filter: FilterPair, limit: Int? = null): QueryStats {
      return runOnIo { blockingCollection.getQueryStats(*filter, limit = limit) }
    }
  }

  companion object {
    var logAllQueries = false // Set this to true if you want to debug your database queries
    private fun Document.asJsonString() = JsonHandler.toJsonString(this)

    private val defaultWatchPipeline
      get() = Document().apply {
        this["\$match"] = Document().apply {
          this["\$or"] = listOf(
            Document("operationType", "insert"),
            Document("operationType", "replace"),
            Document("operationType", "delete"),
            Document("operationType", "update"),
          )
        }
      }

    fun createMongoClientFromUri(
      connectionString: ConnectionString,
      allowReadFromSecondaries: Boolean,
      useMajorityWrite: Boolean,
      clientSettings: (MongoClientSettings.Builder.() -> Unit)?
    ): MongoClient {
      if (!useMajorityWrite && allowReadFromSecondaries) {
        throw IllegalArgumentException("Reading from secondaries requires useMajorityWrite = true")
      }
      return MongoClientSettings.builder()
        .applyConnectionString(connectionString)
        .apply {
          when {
            allowReadFromSecondaries -> {
              // Set maxStalenessSeconds, see https://docs.mongodb.com/manual/core/read-preference/#maxstalenessseconds
              readPreference(ReadPreference.secondaryPreferred(90L, TimeUnit.SECONDS))
              readConcern(ReadConcern.MAJORITY)
              writeConcern(WriteConcern.MAJORITY)
            }

            else -> {
              readPreference(ReadPreference.primaryPreferred())
              readConcern(ReadConcern.LOCAL)

              if (useMajorityWrite) {
                writeConcern(WriteConcern.MAJORITY)
              } else {
                writeConcern(WriteConcern.W1)
              }
            }
          }

          clientSettings?.invoke(this)
        }
        .build()
        .let { MongoClients.create(it) }
    }

    private suspend fun <R> runOnIo(block: suspend CoroutineScope.() -> R): R {
      return withContext(Dispatchers.IO, block)
    }
  }
}