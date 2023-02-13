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
  fun transactionTest() = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { clear() }

    Assertions.assertEquals(0, collection.count(EnumMongoPayload::long equal 42))

    testDb.executeTransaction { database ->
      val transactionalCollection = database.getSuspendingCollection<EnumMongoPayload>()

      transactionalCollection.insertOne(EnumMongoPayload().apply { _id = "1"; long = 42 }, upsert = true)
      transactionalCollection.insertOne(EnumMongoPayload().apply { _id = "2"; long = 42 }, upsert = true)

      Assertions.assertEquals(2, transactionalCollection.count(EnumMongoPayload::long equal 42))
      Assertions.assertEquals(0, collection.count(EnumMongoPayload::long equal 42))
    }

    Assertions.assertEquals(2, collection.count(EnumMongoPayload::long equal 42))
  }

  companion object {
    lateinit var testDb: MongoDatabase

    @Suppress("unused")
    @BeforeAll
    @JvmStatic
    fun setup(): Unit = runBlocking {
      testDb =
        MongoDatabase(
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