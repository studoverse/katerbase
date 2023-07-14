import ReplicaSetDatabaseTests.Companion.testDb
import com.moshbit.katerbase.MongoDatabase
import com.moshbit.katerbase.equal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * These tests require a replica set as [testDb]. Look for setup instructions in the "local-development" folder.
 */
class ReplicaSetDatabaseTests {

  @Test
  fun transactionSingleCollectionTest() = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { clear() }

    Assertions.assertEquals(0, collection.count(EnumMongoPayload::long equal 42))

    testDb.executeTransaction { database ->
      val transactionalCollection = database.getSuspendingCollection<EnumMongoPayload>()

      transactionalCollection.insertOne(EnumMongoPayload().apply { _id = "1"; long = 42 }, upsert = true)
      transactionalCollection.insertOne(EnumMongoPayload().apply { _id = "2"; long = 42 }, upsert = true)

      Assertions.assertEquals(2, transactionalCollection.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(0, collection.count(EnumMongoPayload::long equal 42))

      collection.insertOne(EnumMongoPayload().apply { _id = "3"; long = 42 }, upsert = true)
      Assertions.assertEquals(2, transactionalCollection.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(1, collection.count(EnumMongoPayload::long equal 42))
    }

    Assertions.assertEquals(3, collection.count(EnumMongoPayload::long equal 42))
  }

  @Test
  fun transactionMultiCollectionTest() = runBlocking {
    val collection1 = testDb.getSuspendingCollection<EnumMongoPayload>().apply { clear() }
    val collection2 = testDb.getSuspendingCollection<SimpleMongoPayload>().apply { clear() }

    Assertions.assertEquals(0, collection1.count(EnumMongoPayload::long equal 42))
    Assertions.assertEquals(0, collection2.count(SimpleMongoPayload::string equal "42"))

    testDb.executeTransaction { database ->
      val transactionalCollection1 = database.getSuspendingCollection<EnumMongoPayload>()
      val transactionalCollection2 = database.getSuspendingCollection<SimpleMongoPayload>()

      transactionalCollection1.insertOne(EnumMongoPayload().apply { _id = "1"; long = 42 }, upsert = true)
      transactionalCollection1.insertOne(EnumMongoPayload().apply { _id = "2"; long = 42 }, upsert = true)
      transactionalCollection2.insertOne(SimpleMongoPayload().apply { _id = "1"; string = "42" }, upsert = true)
      transactionalCollection2.insertOne(SimpleMongoPayload().apply { _id = "2"; string = "42" }, upsert = true)

      Assertions.assertEquals(2, transactionalCollection1.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(0, collection1.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(2, transactionalCollection2.count(SimpleMongoPayload::string equal "42"))
      Assertions.assertEquals(0, collection2.count(SimpleMongoPayload::string equal "42"))

      collection1.insertOne(EnumMongoPayload().apply { _id = "3"; long = 42 }, upsert = true)
      collection2.insertOne(SimpleMongoPayload().apply { _id = "3"; string = "42" }, upsert = true)

      Assertions.assertEquals(2, transactionalCollection1.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(1, collection1.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(2, transactionalCollection2.count(SimpleMongoPayload::string equal "42"))
      Assertions.assertEquals(1, collection2.count(SimpleMongoPayload::string equal "42"))
    }

    Assertions.assertEquals(3, collection1.count(EnumMongoPayload::long equal 42))
    Assertions.assertEquals(3, collection2.count(SimpleMongoPayload::string equal "42"))
  }

  companion object {
    lateinit var testDb: MongoDatabase

    @Suppress("unused")
    @BeforeAll
    @JvmStatic
    fun setup(): Unit = runBlocking {
      testDb =
        MongoDatabase.create(
          uri = "mongodb://server1:27027,server2:27037/testDb?replicaSet=local-rs&readPreference=primary&serverSelectionTimeoutMS=5000&connectTimeoutMS=10000",
          supportChangeStreams = true,
        ) {
          collection<EnumMongoPayload>("enumColl") {
            index(EnumMongoPayload::value1.ascending())
            index(EnumMongoPayload::value1.ascending(), EnumMongoPayload::date.ascending())
            index(
              EnumMongoPayload::nullableString.ascending(), partialIndex = arrayOf(
                EnumMongoPayload::nullableString equal null
              )
            )
          }
          collection<SimpleMongoPayload>("simpleMongoColl")
          collection<NullableSimpleMongoPayload>("simpleMongoColl") // Use the same underlying mongoDb collection as SimpleMongoPayload
        }
    }
  }
}