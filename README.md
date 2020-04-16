# Katerbase

Katerbase [keɪtərbeɪs] is a Kotlin wrapper for the [MongoDB Java Drivers](http://mongodb.github.io/mongo-java-driver/) to provide idiomatic Kotlin support for MongoDB.
Its goal is to write concise and simple MongoDB queries without any boilerplate or ceremony. IDE autocompletion and type safety allow you to start writing MongoDB queries, even if you haven't used the MongoDB query syntax before.

Katerbase has object mapping built in, so queried data from MongoDB get deserialized by 
[Jackson](https://github.com/FasterXML/jackson-module-kotlin) into Kotlin objects.


## Quick start

The following example showcases how MongoDB documents can be queried, inserted and modified from Kotlin.

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

Check out the Katerbase [read operations](#read-operations) and [write operation](#write-operations) sections for all supported MongoDB operations and examples. 

### Database Setup

Katerbase supports multiple MongoDB databases. Each database is defined in code. By creating a MongoDatabase object, the connection URI must be specified along with the collection definitions:
```kotlin
var database = object : MongoDatabase("mongodb://localhost:27017/moviesDatabase") {
  override fun getCollections(): Map<out KClass<out MongoMainEntry>, String> = mapOf(
    Movie::class to "movies",
    User::class to "users",
    SignIn::class to "signInLogging"
  )

  override fun getIndexes() {
    with(getCollection<Movie>()) {
      createIndex(Movie::name.toMongoField().textIndex())
    }
    with(getCollection<User>()) {
      createIndex(User::email.toMongoField().ascending(), customOptions = { unique(true) })
      createIndex(User::ratings.child(User.MovieRating::date).toMongoField().ascending())
    }
  }

  override fun getCappedCollectionsMaxBytes(): Map<out KClass<out MongoMainEntry>, Long> = mapOf(
    SignIn::class to 1024L * 1024L // 1 MB
  )
}
```

### Collection Setup

Each MongoDB database consists of multiple MongoDB collections. To create a collection, add the MongoDB collection name and the corresponding Kotlin model class to the `override fun getCollections()`.
 
```kotlin
class Movie : MongoMainEntry() {
  class Actor : MongoSubEntry() {
    var name = ""
    var birthday: Date? = null
  }

  var name = ""
  var actors: List<Actor> = emptyList()
}
```

The Kotlin model class must inherit from `MongoMainEntry`, therefore `Movie` also has a `var _id: String` field. Only `MongoMainEntry` objects can be inserted and queried from the MongoDB. MongoDB [embedded/nested documents](https://docs.mongodb.com/manual/tutorial/query-embedded-documents/) must inherit from `MongoSubEntry` to explicitly opt-in into the serialization and deserialization of this subdocument class.


### Installation

Currently, the library is not yet published to Maven Central, to use Katerbase download this Git repository and add the Kotlin files manually to your project. The library will be published to Maven Central at a later point.


## Read operations

The following operators can be executed on a collection, for example `database.getCollection<Movie>.find()`.

### find
`fun find(vararg filter: FilterPair): FindCursor<Entry>`

[db.collection.find(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.find/) MongoDB operation

Example usage:
```
col.find()
col.find(Book::_id equal "the_hobbit")
col.find(Book::author equal "Tolkien", Book::yearPublished lowerEquals 1940)
```

[List of all supported filter modifiers](#filter-modifiers).

The returned `FindCursor` is an `Iterable`. Before iterating though the objects further operations can be applied to the `FindCursor`:

* `limit(limit: Int)` - [cursor.limit](https://docs.mongodb.com/manual/reference/method/cursor.limit/)
* `skip(skip: Int)` - [cursor.skip](https://docs.mongodb.com/manual/reference/method/cursor.skip/)
* `hint(index: MongoCollection.MongoIndex)` - [cursor.hint](https://docs.mongodb.com/manual/reference/method/cursor.hint/)
* `selectedFields(vararg fields: MongoEntryField)` - [projection parameter](https://docs.mongodb.com/manual/reference/method/db.collection.find/)
* `excludedFields(vararg fields: MongoEntryField)` - [projection parameter](https://docs.mongodb.com/manual/reference/method/db.collection.find/): Excluding fields is an anti-pattern and is not maintainable. Always try to use `selectedFields`
* `projection(bson: Bson)` - [projection parameter](https://docs.mongodb.com/manual/reference/method/db.collection.find/): Direct `Bson` access, use only if selectedFields or excludedFields are insufficient
* `sortBy(field: MongoEntryField)` - [cursor.sort](https://docs.mongodb.com/manual/reference/method/cursor.sort/)
* `sortByDescending(field: MongoEntryField)` - [cursor.sort](https://docs.mongodb.com/manual/reference/method/cursor.sort/)
* `sort(bson: Bson)` - [cursor.sort](https://docs.mongodb.com/manual/reference/method/cursor.sort/): direct `Bson` access, use only if sortBy or sortByDescending are insufficient

The order of the `FindCursor` operators do not matter. As soon as the iteration starts, the `FindCursor` gets serialized and sent to the MongoDB. Note that each `FindCursor` should be iterated only once, as each iteration creates network access to the database, see [MongoIterable](http://mongodb.github.io/mongo-java-driver/3.8/javadoc/com/mongodb/client/MongoIterable.html). Use `FindCurosor.toList()` in case you need to traverse the `Iterator` more than once. If a `FindCursor` won't get iterated, no database operation gets executed. A `FindCursor` is mutable and comparable.

Example usage:
```
val books: Iterable<Book> = col.find(Book::author equal "Tokien")
    .selectedFields(Book::author, Book::yearPublished)
    .sortByDescending(Book::yearPublished)
    .skip(20)
    .limit(10)
```


### findOne
`fun findOne(vararg filter: FilterPair): Entry?`

[db.collection.findOne(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.findOne/) MongoDB operation

In contrast to `find()`, the `findOne()` operation in gets immediately executed on the database, and the retuned value is the actual Kotlin object.
 
`findOne(filter)` is implemented by `find(*filter).limit(1).firstOrNull()`. So all filter operators from [find](#find) apply here too, cursor operators can't be used. If you need to call `findOne` with additional cursor operators, just use `find` with `limit(1)` and `firstOrNull`.


### findDocuments
`fun findDocuments(vararg filter: FilterPair): FindIterable<Document>`

[db.collection.find(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.find/) MongoDB operation

`findDocuments()` returns a `FindIterable<Document>` in contrast to `find()` which returns a `FindCursor<Entry>`. So no type mapping is done, and instead of using the Katerbase find-operations like `limit()` the mongo-java-driver operaations of the FindIterable can be used. `Document` is in package `org.bson.Document` of the mongo-java-driver library and implements `Map<String, Object>, Serializable, Bson`. `FindIterable` is in package `com.mongodb.client` of the mongo-java-driver library and inherits from `MongoIterable<TResult>` which inherits from `Iterable<TResult>`.

By using `findDocuments()` Katerbase offers direct access to the mongo-java-driver for all edge-cases that Katerbase doesn't supports. The MongoDB documentation for [FindIterable](https://mongodb.github.io/mongo-java-driver/3.12/driver/tutorials/perform-read-operations/#finditerable) explains all supported methods you can chain to the `findDocuments()`  operation.

### count
`fun count(vararg filter: FilterPair): Long`

TODO


### distinct
`fun <reified T : Any> distinct(distinctField: MongoEntryField<T>, vararg filter: FilterPair): DistinctCursor<T>`

TODO

In case `T` can not be reified, pass the `entryClass` to the overloaded function
`fun <T : Any> distinct(distinctField: MongoEntryField<T>, entryClazz: KClass<T>, vararg filter: FilterPair): DistinctCursor<T>`.

Due to a [Kotlin compiler bug](https://youtrack.jetbrains.com/issue/KT-35105) that happens when using Kotlin-NewInference starting at Kotlin 1.3.60, this function might not be callable, therefore you can use meanwhile the function `fun <reified T : Any> distinct_mitigateCompilerBug(distinctField: MongoEntryField<T>, vararg filter: FilterPair): DistinctCursor<T>` as workaround. Kotlin 1.4 should fix this compiler bug.


## Write operations

TODO


### updateOne
`fun updateOne(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult`

TODO


### updateMany
`fun updateMany(vararg filter: FilterPair, update: UpdateOperation.() -> Unit): UpdateResult`

TODO


### updateOneAndFind
`fun updateOneAndFind(vararg filter: FilterPair, upsert: Boolean = false, update: UpdateOperation.() -> Unit): Entry?`

TODO


### insertOne
`insertOne(document: Entry, upsert: Boolean)` and
`fun insertOne(document: Entry, onDuplicateKey: (() -> Unit))`

TODO


### insertMany
`fun insertMany(documents: List<Entry>, upsert: Boolean)`

TODO


### findOneOrInsert
`fun findOneOrInsert(vararg filter: FilterPair, setWithId: Boolean = false, newEntry: () -> Entry): Entry`

[db.collection.findOne(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.findOne/) and [db.collection.findOneAndUpdate(query, projection)](https://docs.mongodb.com/manual/reference/method/db.collection.findOneAndUpdate/) MongoDB operation

Find the document or in case the filtered document does not exist insert a new document. The document is returned matter if it exists previously or it was just inserted. This works atomically, so `newEntry` may be called even if the document exists during the `findOneOrInsert` operation. In that case the document won't get created but the already existent document will be returned.

`findOneOrInsert()` uses internally at first `find()` to look up a document. In case the document is not found, it will execute the `findOneAndUpdate` MongoDB operation. This is a performance optimization, when using `findOneAndUpdate` the MongoDB document is locked, but when using `find()` the document won't get locked. So in case the document can be found, MongoDB won't use any locks. In case the document won't get found via `find()`, `findOneOrInsert` will use internally the `updateOneAndFind` MongoDB operation with `upsert = true` parameter to insert the document or just return the existent document.

#### Example usage with _id filter:
```kotlin
val book = collection.findOneOrInsert(Book::_id equal "the_hobbit") {
  Book().apply {
    // // On inserting a new document, _id will be used from the filter query, so "the_hobbit" in this case
    name = "The inserted Hobbit"
    author = "Tolkien"
  }
}
```
`book._id` is always "the_hobbit", no matter if the document was inserted or already existed.
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
`book._name` is always "The Hobbit" and `book.author` is always "Tokien", no matter if the document was inserted or already existed.
`book._id` is "the_new_inserted_hobbit" in case the document was just inserted, or whatever it was before in case it already existed.


### deleteOne
`fun deleteOne(vararg filter: FilterPair): DeleteResult`

TODO


#### deleteMany
`fun deleteMany(vararg filter: FilterPair): DeleteResult`

TODO


### drop
`col.drop() -> Unit`

[db.collection.drop()](https://docs.mongodb.com/manual/reference/method/db.collection.drop/) MongoDB operation

This command is only available if connecting to a local databases due to the destructive impact of this command.


### clear
`col.clear() -> Unit`

[db.collection.clear()](https://docs.mongodb.com/manual/reference/method/db.collection.clear/) MongoDB operation

This command is only available if connecting to a local databases due to the destructive impact of this command.


## Other operators

### aggregate
`fun <T : MongoEntry> aggregate(pipeline: AggregationPipeline, entryClazz: KClass<T>): AggregateCursor<T>` and
`fun <reified T : MongoEntry> aggregate(noinline pipeline: AggregationPipeline.() -> Unit): AggregateCursor<T>`

TODO


### watch

TODO


## Filter operators
* TODO equal, notEqual, lower, any, or...
* TODO child, childWithCursor
* TODO FindCursor: selectedFields, limit...


## Update operators
* TODO setTo, child, childWithCurosor, unset, seetOnInsert, incrementBy, push (2x), pull, pullWhere...
* TODO lambda -> if statements within update operation

## Bulk operations

TOOD


### Indexes
`fun createIndex(index: Bson, partialIndex: Array<FilterPair>? = null, customOptions: (IndexOptions.() -> Unit)? = null)`
* TODO Creation (multiple microservices): `createIndexes()`
* TODO all following types
* TODO getIndex for hinting

#### Simple index

TODO


### Compound index

TODO


### Text index

TODO


### Custom options

TODO


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

[MongoDB field names](https://docs.mongodb.com/manual/core/document/#field-names) must be of type String, therefore nested Maps must be of the type `Map<String, *>`,. Collections can be `List<*>`, `Set<*>` or any other collection that is serializable and deserializable by Jackson. `*` must be a Kotlin type listed in the table above.


#### Kotlin fields

By using the [@Transient](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-transient/) field annotation, a field can be marked no not being serialized, therefore it won't get stored in the MongoDB document. [Getters and setters](https://kotlinlang.org/docs/reference/properties.html#getters-and-setters) won't get serialized or deserialized by Katerbase. Also, functions within the Kotlin model class will be ignored by the serialization and deserialization. All kind of field visibility modifiers are acceptable, so it does not matter if a field of a Kotlin model is `public`, `internal`, `protected` or `private`.


#### Missing Kotlin field

MongoDB collections can be [schemaless](https://www.mongodb.com/blog/post/why-schemaless), although [document schemas](https://docs.mongodb.com/stitch/mongodb/document-schemas/) can be enforced. In case the MonoDB document has properties that do not have a corresponding Kotlin field, the **property will be ignored** on deserialization. This is useful when adding fields in the MongoDB document if the updated Kotlin models are not yet deployed.

A [Movie](#collection-setup) MongoDB document `{_id: "first", actors: [], website: "https://example.org"}` will get deserialized into the Kotlin class `Movie(_id=first, actors=[]`.


#### Additional Kotlin field

In case the MongoDB document does not have a property value that is defined in the Kotlin model, respectively the MongoDB property is `undefined`, the **default field value** will be used. This is useful when adding fields to Kotlin models if the MongoDB documents are not yet migrated.

A [Movie](#collection-setup) MongoDB document `{_id: "first", actors: [{name: "actorname"}, {birthday: ISODate(0)}]}` will get deserialized into the Kotlin class `Movie(_id=first, actors=[Actor(name=actorname, birthday=null), Actor(name=, birthday=Date(0))]`.


#### Double and Float


#### String and Enum


#### List, Set and other Collections


#### Null and undefined

All Kotlin field values can be nullable, in that case `null` will be stored in the MongoDB document. MongoDB supports two nullable JavaScript types: `undefined` and `null`. If a field in a MongoDB document is `undefined`, then the Kotlin model has an additional field, see [additional Kotlin field](#additional-kotlin-field). If a MongoDB document field value is `null` then it is either deserialized to the Kotlin `null` type in case of non-primitive types (e.g. `String?` or `User?`) or to `0` in case of [primitive types](https://kotlinlang.org/docs/tutorials/kotlin-for-py/primitive-data-types-and-their-limitations.html). This is a known limitation that happens because of the Jackson deserialization, a later field access in Kotlin will fail then with a `NullPointerException` on object types.


## Database configuration
TODO
* createMongoClientFromUri
* logAllQueries
* uri
* allowReadFromSecondaries
* supportChangeStreams
* createNonExistentCollections
* localMode (better naming, local does not mean development)
* immutable mongoCollections

TODO implement
* logging