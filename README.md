# Katerbase

Katerbase [keɪtərbeɪs] is a Kotlin wrapper for the [MongoDB Java Driver](http://mongodb.github.io/mongo-java-driver/) and offers idiomatic Kotlin support for MongoDB.
Its goal is to write concise and simple MongoDB queries without any boilerplate or ceremony. IDE autocompletion and type safety allow you to start writing MongoDB queries, even if you haven't used the MongoDB query syntax before.

Katerbase has object mapping built in, so queried data from MongoDB get deserialized by 
[Jackson](https://github.com/FasterXML/jackson-module-kotlin) into Kotlin classes.


## Quick start

The following example showcases how MongoDB documents can be queried, inserted and modified from Kotlin:

```kotlin
class Book : MongoMainEntry() {
  var author: String = ""
  var name: String = ""
  var yearPublished: Int? = null
}

val col = database.getCollection<Book>()

// MongoDB JS syntax: db.collection.insertOne({_id: "the_hobbit", author: "Tolkien", name: "The Hobbit"})
col.insertOne(Book().apply {
  _id = "the_hobbit"
  author = "Tolkien"
  name = "The Hobbit"
}, upsert = false)

// MongoDB JS syntax: db.collection.find({author: "Tolkien"})
val tolkienBooks: Iterable<Book> = col.find(Book::author equal "Tolkien")

// MongoDB JS syntax: db.collection.updateOne({_id: "the_hobbit"}, {yearPublished: 1937}, {upsert: false})
col.updateOne(Book::_id equal "the_hobbit") {
  Book::yearPublished setTo 1937
}

// MongoDB JS syntax: db.collection.findOne({author: "Tolkien", yearPublished: {$lte: 1940}})
val book: Book? = col.findOne(Book::author equal "Tolkien", Book::yearPublished lowerEquals 1940)
```

Check out the Katerbase [read operations](#read-operations) and [write operations](#write-operations) sections for all supported MongoDB operations and examples. 

### Database Setup

A MongoDB database with all its collections is defined in code. When creating a Katerbase MongoDatabase object, the connection URI must be specified along with the collection definitions:
```kotlin
var database = MongoDatabase("mongodb://localhost:27017/moviesDatabase") {
  collection<Movie>("movies") {
    index(Movie::name.textIndex())
  }
  collection<User>("users") {
    index(User::email.ascending(), indexOptions = { unique(true) })
    index(User::ratings.child(User.MovieRating::date).ascending())
  }
  collection<SignIn>("signInLogging", collectionSizeCap = 1024L * 1024L) // 1MB
}
```

Use the `clientSettings` argument of the `MongoDatabase` constructor to configure the `MongoClientSettings` of the mongo-java-driver, see [MongoDB connection settings](https://mongodb.github.io/mongo-java-driver/3.12/driver/tutorials/connect-to-mongodb/)

The `com.moshbit.katerbase.MongoDatabase` has an `internalDatabase: com.mongodb.client.MongoDatabase` field that exposes the MongoDatabase of the mongo-java-driver to allow all operations that Katerbase doesn't support yet.

### Collection Setup

Each MongoDB database consists of multiple MongoDB collections. To create a collection, add the MongoDB collection name and the corresponding Kotlin model class to  the `collection` constructor argument of the `MongoDatabase`, see [database setup](#database-setup). As long as `autoManageCollectionsAndIndexes` is not disabled, Katerbase will automatically create the defined collection.
 
```kotlin
class Movie : MongoMainEntry() {
  class Actor : MongoSubEntry(f) {
    var name = ""
    var birthday: Date? = null
  }

  var name = ""
  var actors: List<Actor> = emptyList()
}
```

The Kotlin model class must inherit from `MongoMainEntry`, therefore `Movie` also has a `var _id: String` field. Only `MongoMainEntry` objects can be inserted and queried from the MongoDB. MongoDB [embedded/nested documents](https://docs.mongodb.com/manual/tutorial/query-embedded-documents/) must inherit from `MongoSubEntry` to explicitly opt-in into the serialization and deserialization of the subdocument class.


### Installation

Currently, the library is not yet published to Maven Central. To use Katerbase, download this Git repository and add the Kotlin files manually to your project. The library will be published to Maven Central at a later point.


## Read operations

The following operations can be executed on a collection, for example `database.getCollection<Movie>.find()`.


### find
`fun find(vararg filter: FilterPair): FindCursor<Entry>`

[db.collection.find(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.find/) MongoDB operation

Example usage:
```kotlin
col.find()
col.find(Book::_id equal "the_hobbit")
col.find(Book::author equal "Tolkien", Book::yearPublished lowerEquals 1940)
```

The order of the `vararg filter: FilterPair` argument does not matter in all Katerbase `filter` arguments. So `col.find(Book::author equal "Tolkien", Book::yearPublished lowerEquals 1940)` is the same as `col.find(Book::yearPublished lowerEquals 1940, Book::author equal "Tolkien")`.

[List of all supported filter operators](#filter-operators).

The returned `FindCursor` is an `Iterable`. Before iterating though the objects further operations can be applied to the `FindCursor`:

* `limit(limit: Int)` - [cursor.limit](https://docs.mongodb.com/manual/reference/method/cursor.limit/)
* `skip(skip: Int)` - [cursor.skip](https://docs.mongodb.com/manual/reference/method/cursor.skip/)
* `hint(index: MongoCollection.MongoIndex)` - [cursor.hint](https://docs.mongodb.com/manual/reference/method/cursor.hint/)
* `selectedFields(vararg fields: MongoEntryField)` - [find projection parameter](https://docs.mongodb.com/manual/reference/method/db.collection.find/)
* `excludedFields(vararg fields: MongoEntryField)` - [find projection parameter](https://docs.mongodb.com/manual/reference/method/db.collection.find/): Excluding fields is an anti-pattern and is not maintainable. Always try to use `selectedFields`.
* `projection(bson: Bson)` - [find projection parameter](https://docs.mongodb.com/manual/reference/method/db.collection.find/): Direct `Bson` access, use only if `selectedFields` and `excludedFields` are insufficient.
* `sortBy(field: MongoEntryField)` - [cursor.sort](https://docs.mongodb.com/manual/reference/method/cursor.sort/)
* `sortByDescending(field: MongoEntryField)` - [cursor.sort](https://docs.mongodb.com/manual/reference/method/cursor.sort/)
* `sort(bson: Bson)` - [cursor.sort](https://docs.mongodb.com/manual/reference/method/cursor.sort/): Direct `Bson` access, use only if `sortBy` and `sortByDescending` are insufficient.

The order of the `FindCursor` operations do not matter. As soon as the iteration starts, the `FindCursor` gets serialized and sent to the MongoDB. Note that each `FindCursor` should only be iterated once, as each iteration results in a network access to the database, see [MongoIterable](http://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/MongoIterable.html). Use `FindCurosor.toList()` in you need to traverse the `Iterator` more than once. If a `FindCursor` won't get iterated, no database operation gets executed. A `FindCursor` is mutable and comparable.

Example usage:
```kotlin
val books: Iterable<Book> = col.find(Book::author equal "Tokien")
    .selectedFields(Book::author, Book::yearPublished)
    .sortByDescending(Book::yearPublished)
    .skip(20)
    .limit(10)
```


### findOne
`fun findOne(vararg filter: FilterPair): Entry?`

[db.collection.findOne(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.findOne/) MongoDB operation

In contrast to `find()`, the `findOne()` operation gets immediately executed on the database, and the retuned value is the actual Kotlin object.
 
`findOne(filter)` is implemented by `find(*filter).limit(1).firstOrNull()`. So all filter operators from [find](#find) apply here too, cursor operators can't be used. If you need to call `findOne` with additional cursor operators, just use `find` with `limit(1)` and `firstOrNull`.


### findDocuments
`fun findDocuments(vararg filter: FilterPair): FindIterable<Document>`

[db.collection.find(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.find/) MongoDB operation

`findDocuments()` returns a `FindIterable<Document>` in contrast to `find()` which returns a `FindCursor<Entry>`. So no type mapping is done, and instead of using the Katerbase find-operations like `limit()` the mongo-java-driver operations of the FindIterable can be used. `Document` is in package `org.bson.Document` of the mongo-java-driver library and implements `Map<String, Object>, Serializable, Bson`. `FindIterable` is in package `com.mongodb.client` of the mongo-java-driver library and inherits from `MongoIterable<TResult>` which inherits from `Iterable<TResult>`.

By using `findDocuments()` Katerbase offers direct access to the mongo-java-driver for all edge-cases that Katerbase doesn't support. The MongoDB documentation for [FindIterable](https://mongodb.github.io/mongo-java-driver/3.12/driver/tutorials/perform-read-operations/#finditerable) explains all supported methods you can chain to the `findDocuments()`  operation.

### count
`fun count(vararg filter: FilterPair): Long`

[db.collection.count(query, options)](https://docs.mongodb.com/manual/reference/method/db.collection.count/) MongoDB operation

Counts how many matching documents in a collection are. If the filter is empty, [estimatedDocumentCount](https://docs.mongodb.com/manual/reference/method/db.collection.estimatedDocumentCount/) is used which  always results in *O(1)* runtime, but the returned count might be out of date. If a filter is specified, [countDocuments](https://docs.mongodb.com/manual/reference/method/db.collection.countDocuments/) with runtime of up to *O(n)* (depending on whether an index is used or not) is used.


### distinct
`fun <reified T : Any> distinct(distinctField: MongoEntryField<T>, vararg filter: FilterPair): DistinctCursor<T>`

[db.collection.distinct()](https://docs.mongodb.com/manual/reference/method/db.collection.distinct/) MongoDB operation

Returns an `Iterable` of the specified field with no duplicates. E.g. `col.distinct(Book::author)` returns an `Iterable<String>` with unique Strings. When applying filtering you get e.g. by calling `col.distinct(Book::author, Book::yearPublished lower 2000)` all author names that have published at least one book before year 2000.

As soon as the iteration starts, the `DistinctCursor` gets serialized and sent to the MongoDB. Note that each `DistinctCursor` should be iterated only once, as each iteration results in a network access to the database, see [DistinctIterable](http://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/DistinctIterable.html). `DistinctCursor` inherits from `MongoIterable`. Use `DistinctCursor.toSet()` if you need to traverse the `Iterator` more than once. If a `DistinctCursor` won't get iterated, no database operation gets executed. A `DistinctCursor` is mutable and comparable.

In case `T` can't be reified, pass the `entryClass` to the overloaded function
`fun <T : Any> distinct(distinctField: MongoEntryField<T>, entryClass: KClass<T>, vararg filter: FilterPair): DistinctCursor<T>`.

Due to a [Kotlin compiler bug](https://youtrack.jetbrains.com/issue/KT-35105) that happens when using Kotlin-NewInference starting at Kotlin 1.3.60, this function might not be callable. Therefore, you can use meanwhile the function `fun <reified T : Any> distinct_mitigateCompilerBug(distinctField: MongoEntryField<T>, vararg filter: FilterPair): DistinctCursor<T>` as workaround. Kotlin 1.4 should fix this compiler bug.


## Write operations

The following update and delete operations all have a `vararg filter: FilterPair` argument, see [find(vararg filter: FilterPair)](#find). The insert operations deserialize the Kotlin class into a MongoDB document with Jackson. 


### updateOne
`fun updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult`

[db.collection.updateOne(filter, update, options)](https://docs.mongodb.com/manual/reference/method/db.collection.updateOne/) MongoDB operation

Updates a single document if matched by `filter` with the specified `update` lambda. The returned [UpdateResult](https://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/result/UpdateResult.html) holds information about the number of documents matched by the query, and the number of documents modified by the update.

If no document matches the given query, no new document is created. Use [insertOne with upsert=true](#upsert-on-duplicate-_id) if you want to overwrite an existing document. Use [updateOnOrInsert](#updateoneorinsert) if you want to insert a specific document and use that `update` lambda if the document already exists.

If the `update` lambda did not call any [update operators](#update-operators), the query won't get executed on the database and will instantly return for performance reasons.

#### Example with fixed operators
```kotlin
col.updateOne(User::email equal "john.doe@example.com") {
  User::lastSignIn setTo Date()
  User::eventHistory push SignInEvent(Date(), browser, authenticationMethod)
  User::loginCount incrementBy 1
}
```

#### Example with dynamic update operators
```kotlin
col.updateOne(CronJob::_id equal cronJobId, CronJob::state equal CronJob.State.Running) {
  CronJob::state setTo CronJob.State.Finished

  if (successfullyFinished) {
    CronJob::lastFinishDate setTo Date()
    CronJob::crashCount setTo 0
  } else {
    CronJob::lastCrashDate setTo Date()
    CronJob::crashCount incrementBy 1
  }
}
```

In this example `CronJob::state` will always be set. Depending on `successfullyFinished`, one of the two if branches will get evaluated when calling the `update` lambda.

The `update` argument is in contrast to the `filter` argument not a list of operations but a true lambda. The `update` lambda puts all [update operators](#update-operators) into a private `MutableMap<String, MutableList<MongoPair>>` inside the currently prepared `UpdateOperation` object. Therefore, all update operators like `setTo` or `incrementBy` that are called at runtime will be added to that `MutableMap`. Other update operators that are in this lambda but are not executed will therefore be not seen by the `UpdateOperation`. This API pattern is also used by the Kotlin [kotlinx.html](https://github.com/Kotlin/kotlinx.html) library and allows an idiomatic Kotlin experience while preparing the update operation. Therefore, all Kotlin language features like branches, functions and loops are available in the `update` lambda.


### updateMany
`fun updateMany(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult`

[db.collection.updateMany(filter, update, options)](https://docs.mongodb.com/manual/reference/method/db.collection.updateMany/) MongoDB operation

Updates all matched documents in the specified collection. See [updateOne](#updateone).

If the `update` lambda did not call any [update operators](#update-operators), the query won't get executed on the database and will instantly return for performance reasons.


### updateOneAndFind
`fun updateOneAndFind(vararg filter: FilterPair, upsert: Boolean = false, update: UpdateOperation.() -> Unit): Entry?`

[db.collection.findOneAndUpdate(filter, update, options)](https://docs.mongodb.com/manual/reference/method/db.collection.findOneAndUpdate/) MongoDB operation

Updates a single document and returns the found or inserted entry instead of the `UpdateOperation`. See [updateOne](#updateone) and [find](#find).

* If `upsert` is not set and no document can be found for the query `null` is returned.
* If `upsert` is set and no document can be found for the query a new document is created in the MongoDB collection. The new document has all fields set that are either specified in the `filter` or that are set in the `update` lambda. See [MongoDB upsert behavior](https://docs.mongodb.com/manual/reference/method/db.collection.update/#upsert-behavior) for details. Note that the inserted document might therefore lack certain fields that would have been added to the document if `insertOne` with an actual Kotlin model object would have been used. This schemaless behavior is native to MongoDB and might at first be confusing when coming from a traditional SQL background. Katerbase only wraps that MongoDB behavior, please check out the MongoDB documentation for further details on that. Section [missing kotlin field](#missing-kotlin-field) explains how Katerbase treats then this "partial" inserted document in subsequent calls.

If a new document gets created, the `setOnInsert` operator might help.


### updateOneOrInsert
`fun updateOneOrInsert(filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult`

`updateOneOrInsert` is extension to [updateOne](#updateone):
* If a matching document exists, the `update` operation will get applied to that document, see [updateOne](#updateone).
* If no matching document exists, a new document is inserted. The new document has all fields set that are either specified in the `filter` or that are set in the `update` lambda. See [MongoDB upsert behavior](https://docs.mongodb.com/manual/reference/method/db.collection.update/#upsert-behavior) for details. Note that the inserted document might therefore lack certain fields that would have been added to the document if `insertOne` with an actual Kotlin model object would have been used. This schemaless behavior is native to MongoDB and might at first be confusing when coming from a traditional SQL background. Katerbase only wraps that MongoDB behavior, please check out the MongoDB documentation for further details on that. Section [missing kotlin field](#missing-kotlin-field) explains how Katerbase treats then this "partial" inserted document in subsequent calls.

If a new document gets created, the `setOnInsert` operator might help.

Alternatively, use [insertOne with upsert=true](#upsert-on-duplicate-_id) if you have a Kotlin model object, and you want to overwrite an existing document.

Example:
```kotlin
col.updateOneOrInsert(User::_id equal "user_id") {
  User::lastSignIn setTo Date()
  User::signUp setOnInsert Date()
}
```
If the user with the given `_id` already exists, only `lastSignIn` will be updated in the corresponding document. But if the user did not exist, a new user will get created, the final document is then `{_id: "user_id", lastSignIn: date, signUp: date}`.


### insertOne
`insertOne(document: Entry, upsert: Boolean): Unit` and
`fun insertOne(document: Entry, onDuplicateKey: (() -> Unit)): Unit`

[db.collection.insertOne()](https://docs.mongodb.com/manual/reference/method/db.collection.insertOne/) and [db.collection.replaceOne(filter, replacement, options)](https://docs.mongodb.com/manual/reference/method/db.collection.replaceOne/) MongoDB operation

Inserts the provided document. See section [type mapping](#type-mapping) for Kotlin serialization details. 

If you want to insert multiple documents at once, use `bulkWrite { documents.foreEach { insertOne(it) } }`, see [bulk operations](#bulk-operations).

Examples:
```kotlin
val newUserEmail = "john.doe@example.com"

col.insertOne(User().apply {
  _id = generateId(newUserEmail)
  email = newUserEmail
}, upsert = false)

col.insertOne(User().apply {
  _id = generateId(newUserEmail)
  email = newUserEmail
}, onDuplicateKey = {
  return@loginFunction LoginResult.UserAlreadyExists
})
```

Three different `insertOne` calls can be used, depending on the required use case:


#### upsert on duplicate _id
When calling `insertOne(document = Book().apply { _id = "the_hobbit"; authorName = "X" }, upsert = true)` and a document with the _id "the_hobbit" already exists, the given document will get replaced. Katerbase uses in that case the [db.collection.replaceOne(filter, replacement, options)](https://docs.mongodb.com/manual/reference/method/db.collection.replaceOne/) MongoDB operation with `upsert: true` parameter. In this example the resulting document will have `authorName: "X"` set, no matter if the
document previously exited in the MongoDB collection or not.

This is useful if you expect that duplicates might happen, but the newer data should always be used. 

Alternatively, use [updateOneOrInsert](#updateoneorinsert) if you want to atomically either insert or update the document with fine-grained control.


#### throw on duplicate _id
When calling `insertOne(document = Book().apply { _id = "the_hobbit" }, upsert = false)` and a document with the _id "the_hobbit" already exists, the document won't get updated. Instead, a `DuplicateKeyException` will be thrown.

This is useful when you do not expect that duplicates will happen. [Failing fast can reduce debugging’s cost and pain significantly](https://www.martinfowler.com/ieeeSoftware/failFast.pdf).


#### handle duplicate _id in Kotlin
When calling `insertOne(document: Book().apply { _id = "the_hobbit" }, onDuplicateKey: { ... })` and a document with the _id "the_hobbit" already exists, the document won't get updated. Instead, the `onDuplicateKey` lambda gets called.

This is useful if you expect that duplicates might happen, and you can resolve that duplicates on your own by writing Kotlin code and e.g. use an [updateOne](#updateone) operation in the `onDuplicateKey` lambda.

Note that the `onDuplicateKey` lambda is not atomically executed, but called after MongoDB finishes the `insertOne` call. If you can achieve an atomic update query without the need of custom Kotlin logic use [updateOneOrInsert](#updateoneorinsert) instead.


### findOneOrInsert
`fun findOneOrInsert(vararg filter: FilterPair, newEntry: () -> Entry): Entry`

[db.collection.findOne(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.findOne/) and [db.collection.findOneAndUpdate(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.findOneAndUpdate/) MongoDB operation

Find the document or in case the filtered document does not exist insert a new document. The document is returned no matter if it exists previously, or it was just inserted. `newEntry` might get called even if the document exists during the `findOneOrInsert` operation. In that case the document won't get created, but the already existent document will be returned.

`findOneOrInsert()` uses internally at first `find` to look up a document. In case the document is not found, it will execute the `findOneAndUpdate` MongoDB operation. This is a performance optimization, when using `findOneAndUpdate` the MongoDB document is locked, but when using `find` the document won't get locked by MongoDB. So in case the document can be found, MongoDB won't use any locks. In case the document won't get found via `find`, `findOneOrInsert()` will use internally the `updateOneAndFind` MongoDB operation with `upsert = true` parameter to insert the document or just return the existing document.

#### Example usage with _id filter:
```kotlin
val book = collection.findOneOrInsert(Book::_id equal "the_hobbit") {
  Book().apply {
    // On inserting a new document, _id will be used from the filter query, so "the_hobbit" in this case
    name = "The inserted Hobbit"
    author = "Tolkien"
  }
}
```
In this example, `book._id` is always "the_hobbit", no matter if the document was inserted or already existed.
`book.name` is "The inserted Hobbit" in case the document was just inserted, or whatever it was before in case it already existed.

#### Example usage without _id filter:
```kotlin
val book = collection.findOneOrInsert(Book::name equal "The Hobbit", Book::author equal "Tolkien") {
  Book().apply {
    _id = "the_new_inserted_hobbit"
    // On inserting a new document, name and author will be used from the filter query.
  }
}
```
In this example, `book._name` is always "The Hobbit" and `book.author` is always "Tokien", no matter if the document was inserted or already existed.
`book._id` is "the_new_inserted_hobbit" in case the document was just inserted, or whatever it was before in case it already existed.


### deleteOne
`fun deleteOne(vararg filter: FilterPair): DeleteResult`

[db.collection.deleteOne()](https://docs.mongodb.com/manual/reference/method/db.collection.deleteOne/) MongoDB operation

Deletes a single document from the collection. The filter must not be empty, otherwise an undefined document will be deleted.

The returned [DeleteResult](https://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/result/DeleteResult.html) holds information about the number of documents deleted, which can be 0 in case no document matched the given query.


#### deleteMany
`fun deleteMany(vararg filter: FilterPair): DeleteResult`

[db.collection.deleteMany()](https://docs.mongodb.com/manual/reference/method/db.collection.deleteMany/) MongoDB operation

Deletes a single document from the collection. In case the filter is empty, all documents wil be deleted in the given collection.

The returned [DeleteResult](https://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/result/DeleteResult.html) holds information about the number of documents deleted.


### drop
`col.drop() -> Unit`

[db.collection.drop()](https://docs.mongodb.com/manual/reference/method/db.collection.drop/) MongoDB operation

Removes a collection from the database. As long as Katerbase is not initialized with `autoManageCollectionsAndIndexes = false`, the collection will be automatically created next time the `MongoDatabase` is initialized with Katerbase.


### clear
`col.clear() -> Unit`

[db.collection.clear()](https://docs.mongodb.com/manual/reference/method/db.collection.deleteMany/) MongoDB operation

Calls [deleteMany](#deletemany) with no arguments, so all documents in the collection will be deleted.


## Other operators

### aggregate
`fun <reified T : MongoEntry> aggregate(noinline pipeline: AggregationPipeline.() -> Unit): AggregateCursor<T>`

[db.collection.aggregate](https://docs.mongodb.com/manual/reference/method/db.collection.aggregate/) MongoDB operation

In case `T` can't be reified, pass the `entryClass` to the overloaded function `fun <T : MongoEntry> aggregate(pipeline: AggregationPipeline, entryClass: KClass<T>): AggregateCursor<T>`.

`aggregate` is currently in an experimental state.


### watch
`fun watch(ignoredFields: List<MongoEntryField<*>> = emptyList(), action: (PayloadChange<Entry>) -> Unit)`

Watch only works if MongoDB is a replica set. Use `ignoredFields` to exclude a set of fields, if any change occurs to these fields it will be ignored.

Use `aggregationPipeline` to start a new aggregation. The following operators are currently supported:
* match
* group
* project
* transform: include, project
* sortBy
* sortByDescending
* accumulators: sum, average, max, min, count

`aggregate` is currently in an experimental state.


## Filter operators
* equal
* notEqual
* contains
* containsCaseInsensitive
* startsWith
* startsWithCaseInsensitive
* endsWith
* has
* inArray
* notInArray
* hasAnyInArray
* hasNoneInArray
* lower
* lowerEquals
* greater
* greaterEquals
* inRange
* exists
* valueDocument
* or
* and
* textSearch
* any
* none
* child


## Update operators
* setTo
* unset
* setToOnInsert
* incrementBy
* multiplyBy
* min
* max
* push
* pull
* pullWhere
* child
* childWithCursor


## Bulk operations
`fun bulkWrite(options: BulkWriteOptions = BulkWriteOptions(), action: BulkOperation.() -> Unit): BulkWriteResult`

[db.collection.bulkWrite](https://docs.mongodb.com/manual/reference/method/db.collection.bulkWrite/) MongoDB operation

If the `actions` lambda did not call any bulk operations, the query won't get executed on the database and will instantly return for performance reasons.

The following bulkWrite operations can be used in the `action` lambda:
* updateOne: `updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit)`
* updateMany: `updateMany(vararg filter: FilterPair, update: UpdateOperation.() -> Unit)`
* insertOne: `fun insertOne(document: Entry, upsert: Boolean)`
* deleteOne: `fun deleteOne(vararg filter: FilterPair): Boolean`
* deleteMany: `fun deleteMany(vararg filter: FilterPair): Unit`

See the corresponding [write operations](#write-operations) for more details on `filter` and `update` arguments.

Example:
```kotlin
col.bulkWrite {
  newBooks.forEach { newBook ->
    insertOne(newBook, upsert = true)
  }
  deletedBooks.forEach { deletedBook ->
    deleteOne(Book::_id equal deletedBook._id)
  }
}
```

In case the supported Katerbase functions are not sufficient, the `BulkOperations.models: MutableList<WriteModel<Document>>` field of the mongo-java-driver is exposed. You can add any [WriteModel](https://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/model/WriteModel.html) (DeleteManyModel, DeleteOneModel, InsertOneModel, ReplaceOneModel, UpdateManyModel, UpdateOneModel):
```kotlin
col.bulkWrite {
  models.add(UpdateManyModel<Book>(filter, update, options))
}
```


### Indexes
`fun index(vararg index: Bson, partialIndex: Array<FilterPair>? = null, indexOptions: (IndexOptions.() -> Unit)? = null)`

Indexes are defined inside each collection definition (see [database setup](#database-setup)) with the `index()` function:

```kotlin
colections = {
  collection<Movie>("movies") {
    index(Movie::name.textIndex())
    index(Movie::name.ascending(), partialIndex = arrayOf(BackendCv::name notEqual ""))
  }
  collection<User>("users") {
    index(User::email.ascending(), indexOptions = { unique(true) })
    index(User::ratings.child(User.MovieRating::date).ascending())
  }
}
```

Both single field indexes and compound indexes are supported. Each index can be furthermore configured by specifying a [partialIndex](https://docs.mongodb.com/manual/core/index-partial/). All mongo-java-driver [IndexOptions](https://mongodb.github.io/mongo-java-driver/3.12/javadoc/com/mongodb/client/model/IndexOptions.html) are also exposed via `indexOptions`, so you have full flexibility when creating the index.

Each index field must be one of the following:
* ascending
* descending
* [textIndex](https://docs.mongodb.com/manual/text-search/#text-index)

The [indexes MongoDB documentation](https://docs.mongodb.com/manual/indexes/) explains the index handling in more details.

Indexes are named based on the `index: Bson` and `partialIndex: Array<FilterPair>`, so Katerbase can delete indexes that are not any more defined in the code and create new indexes. If an index is changed in code, the next time Katerbase initializes the database the new index gets created and the old index gets deleted. Note that this does not apply to custom `indexOptions`: When changing the `indexOptions` in code, make sure you update the corresponding indexOption also in MongoDB or delete the index in MongoDB and let Katerbase create the new index.

All specified indexes will be automatically created by Katerbase when `autoManageCollectionsAndIndexes` is set in the `MongoDatabase` constructor. The default value is `true`, so in case you do not want to manage your MongoDB collections and indexes via Katerbase set `autoManageCollectionsAndIndexes` to `false`. If set to false, no collections are created or deleted and no indexes get created or deleted by Katerbase. This mode can be useful if you manage the collections and indexes not in the project where Katerbase is used but on another project. Also if you start multiple JVM executables with Katerbase concurrently, make sure that only one executable has `autoManageCollectionsAndIndexes` enabled, since the management of the collections and indexes is not atomic.

```kotlin
database = MongoDatabase("mongodb://localhost:27017/moviesDatabase", autoManageCollectionsAndIndexes = false, collections = { /* ... */ })
```

### Type mapping

Katerbase supports currently for the queried documents type mapping with [Jackson](https://github.com/FasterXML/jackson-module-kotlin).

#### Field types

Katerbase supports the following Kotlin types that are stored in a MongoDB document, see also [MongoDB BSON Types](https://docs.mongodb.com/manual/reference/bson-types/).

| MongoDB       | Kotlin        | 
|---------------|---------------|
| Double        | Double, Float |
| String        | String, Enum  |
| Object        | Map           |
| Array         | Collection    |
| Binary data   | ByteArray     |
| ObjectId      | -             |
| Boolean       | Boolean       |
| Date          | Date          |
| Null          | null          |
| Regular       | -             |
| JavaScript    | -             |
| 32-bit integer| Int           |
| Timestamp     | -             |
| 64-bit integer| Long          |
| Decimal128    | -             |
| Min key       | -             |
| Max key       | -             |

Deprecated BSON types are not supported by Katerbase and are here omitted.

[MongoDB field names](https://docs.mongodb.com/manual/core/document/#field-names) must be of type String, therefore nested Maps must be of the type `Map<String, *>`. Collections can be `List<*>`, `Set<*>` or any other collection that is serializable and deserializable by Jackson. `*` must be a Kotlin type listed in the table above.


#### Kotlin fields

By using the [@Transient](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-transient/) field annotation, a field can be marked as to be ignored on serialization, therefore it won't get stored in the MongoDB document. [Getters and setters](https://kotlinlang.org/docs/reference/properties.html#getters-and-setters) won't get serialized or deserialized by Katerbase. Also, functions within the Kotlin model class will be ignored by the serialization and deserialization. All kind of field visibility modifiers are acceptable, so it does not matter if a field of a Kotlin model is `public`, `internal`, `protected` or `private`.


#### Missing Kotlin field

MongoDB collections can be [schemaless](https://www.mongodb.com/blog/post/why-schemaless), although [document schemas](https://docs.mongodb.com/stitch/mongodb/document-schemas/) can be enforced. In case the MonoDB document has properties that do not have a corresponding Kotlin field, the **property will be ignored** on deserialization. This is useful when adding fields in the MongoDB document if the updated Kotlin models are not yet deployed.

A [Movie](#collection-setup) MongoDB document `{_id: "first", actors: [], website: "https://example.org"}` will get deserialized into the Kotlin class `Movie(_id=first, actors=[])`.


#### Additional Kotlin field

In case the MongoDB document does not have a property value that is defined in the Kotlin model, respectively the MongoDB property is `undefined`, the **default field value** will be used. This is useful when adding fields to Kotlin models if the MongoDB documents are not yet migrated.

A [Movie](#collection-setup) MongoDB document `{_id: "first", actors: [{name: "actorname"}, {birthday: ISODate(0)}]}` will get deserialized into the Kotlin class `Movie(_id=first, actors=[Actor(name=actorname, birthday=null), Actor(name=, birthday=Date(0))])`.


#### Null and undefined

All Kotlin field values can be nullable, in that case `null` will be stored in the MongoDB document. MongoDB supports two nullable JavaScript types: `undefined` and `null`. If a field in a MongoDB document is `undefined` the behavior described in [additional Kotlin field](#additional-kotlin-field) applies. If a MongoDB document field value is `null` then it is either deserialized to the Kotlin `null` type in case of non-primitive types (e.g. `String?` or `User?`) or to `0`/`0.0` in case of [primitive types](https://kotlinlang.org/docs/tutorials/kotlin-for-py/primitive-data-types-and-their-limitations.html). This is a known limitation that happens because of the Jackson deserialization, a later field access in Kotlin will fail then with a `NullPointerException` on object types.


## Project state
Katerbase evolved from a few extensions functions that were created in December 2016 to a bunch of internally used MongoDB utility functions. The utility functions are currently used at [Moshbit](https://moshbit.com) in several projects. In 2019, we decided to create a standalone library out of the proofed mongo-java-driver wrapper functions. The library design was adapted several times to provide the goal of Katerbase: Writing concise and simple MongoDB queries without any boilerplate or ceremony. Many thanks to [@functionaldude](https://github.com/functionaldude) for all the long design discussions that lead into the current state of the project.

It is planned to move Katerbase to the async driver of the mongo-java-driver and incorporate Kotlin coroutines into that.
