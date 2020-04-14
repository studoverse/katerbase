# Katerbase

Katerbase is a Kotlin wrapper for the [MongoDB Java Drivers](http://mongodb.github.io/mongo-java-driver/) to provide idiomatic Kotlin support for MongoDB.
It's goal is to write concise and simple MongoDB queries without any boilerplate or ceremony. IDE autocompletion and type safety allow you to start writing MongoDB queries, even if you haven't used the MongoDB query syntax before.

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

// MongoDB JS-syntax: db.collection.insertOne({_id: "the_hobbit", author: "J.R.R. Tolkien", name: "The Hobbit"})
col.insertOne(Book().apply {
  _id = "the_hobbit"
  author = "J.R.R. Tolkien"
  name = "The Hobbit"
}, upsert = false)

// MongoDB JS-syntax: db.collection.find({author: "J.R.R. Tolkien"})
val tolkienBooks: Iterable<Book> = col.find(Book::author equal "J.R.R. Tolkien")

// MongoDB JS-syntax: db.collection.updateOne({_id: "the_hobbit"}, {yearPublished: 1937}, {upsert: false})
col.updateOne(Book::_id equal "the_hobbit") {
  Book::yearPublished setTo 1937
}

// MongoDB JS-syntax: db.collection.findOne({author: "J.R.R. Tolkien", yearPublished: {$lte: 1940}})
val oldTolkienBook: Book? = col.findOne(Book::author equal "J.R.R. Tolkien", Book::yearPublished lowerEquals 1940)
```

Check out the *Operators* section for all supported MongoDB operations and examples. 

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
      createIndex(Movie::name.toMongoField().ascending()) // TODO text index
    }
    with(getCollection<User>()) {
      createIndex(User::email.toMongoField().ascending()) // TODO unique index
      createIndex(User::ratings.child(User.MovieRating::date).toMongoField().ascending())
    }
  }

  override fun getCappedCollectionsMaxBytes(): Map<out KClass<out MongoMainEntry>, Long> = mapOf(
    SignIn::class to 1024L * 1024L // 1 MB
  )
}
```

### Collection Setup

Each MongoDB database consists of multiple MongoDB collections. To create a collection, add the MongoDB collection name and the corresponding Kotlin class model to the `override fun getCollections()`. T
 
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

The Kotlin class model must inherit from `MongoMainEntry`, therefore `Movie` also has a `var _id: String` field. Only `MongoMainEntrie` objects can be inserted and queried from the MongoDB. MongoDB [embedded/nested documents](https://docs.mongodb.com/manual/tutorial/query-embedded-documents/) must inherit from `MongoSubEntry` to explicitly opt-in into the serialization and deserialization of this subdocument class.


### Installation

Currently, the library is not yet published to Maven Central, to use Katerbase download this Git repository and add the Kotlin files manually to your project. The library will be published to Maven Central at a later point.


## Operators

TODO

#### find
The [db.collection.find() MongoDB operation](https://docs.mongodb.com/manual/reference/method/db.collection.find/) translates t


#### Update modifiers
* lambda -> if statements within update operation



### Indexes

* Creation (multiple microservices)

#### Simple index

### Compound index

### Text index


### Type mapping

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
| Null          | Null          |
| Regular       | -             |
| JavaScript    | -             |
| 32-bit integer| Int           |
| Timestamp     | -             |
| 64-bit integer| Long          |
| Decimal128    | -             |
| Min key       | -             |
| Max key       | -             |

Deprecated BSON types are not supported by Katerbase and are here omitted.

#### Kotlin fields

By using the [Transient](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-transient/) field annotation, a field can be marked no not being serialized, therefore it won't get stored in the MongoDB document. [Getters and setters](https://kotlinlang.org/docs/reference/properties.html#getters-and-setters) won't get serialized or deserialized by Katerbase. Also, functions within the Kotlin model class will be ignored by the serialization and deserialization. All kind of field visibility modifiers are acceptable, so it does not matter if a field of a Kotlin model is `public`, `internal`, `protected` or `private`.


#### Missing Kotlin field

MongoDB collections can be [schemaless](https://www.mongodb.com/blog/post/why-schemaless), although [document schemas](https://docs.mongodb.com/stitch/mongodb/document-schemas/) can be enforced. In case the MonoDB document has properties that do not have a corresponding Kotlin field, the property will just be ignored on deserialization. This is useful when adding fields in the MongoDB document but the updated Kotlin models are not yet deployed.

A [Movie](#collection-setup) MongoDB document `{_id: "first", actors: [], website: "https://example.org"}` will get deserialized into the Kotlin class `Movie(_id=first, actors=[]`.


#### Additional Kotlin field

In case the MongoDB document does not have a property value that is defined in the Kotlin model, the default field content will be used. This is useful when adding fields to Kotlin models but the MongoDB documents are not yet migrated.

A [Movie](#collection-setup) MongoDB document `{_id: "first", actors: [{name: "actorname"}, {birthday: ISODate(0)}]}` will get deserialized into the Kotlin class `Movie(_id=first, actors=[Actor(name=actorname, birthday=null), Actor(name=, birthday=Date(0))]`.


#### Double and Float

#### String and Enum

#### List, Set and other Collections

#### Undefined
* null vs undefined
* default field values
* unset()
* migrations and deployment of multiple microservices with the same database
