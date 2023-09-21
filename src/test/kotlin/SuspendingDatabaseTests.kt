import com.moshbit.katerbase.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import util.addYears
import util.forEachAsyncCoroutine
import java.util.*

// Keep BlockingDatabaseTests and SuspendingDatabaseTests in sync, and only change getCollection with getSuspendingCollection
class SuspendingDatabaseTests {
  @Test
  fun enumHandling1(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "testId" }
    testDb.getSuspendingCollection<EnumMongoPayload>().insertOne(payload, upsert = true)

    val results = testDb.getSuspendingCollection<EnumMongoPayload>().find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE1)
    assert(results.toList().isNotEmpty())
    testDb.getSuspendingCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "testId")
  }

  @Test
  fun enumHandling2(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "testId" }
    testDb.getSuspendingCollection<EnumMongoPayload>().insertOne(payload, upsert = true)

    testDb.getSuspendingCollection<EnumMongoPayload>().updateOne(EnumMongoPayload::_id equal "testId") {
      EnumMongoPayload::value1 setTo EnumMongoPayload.Enum1.VALUE2
    }

    val results = testDb.getSuspendingCollection<EnumMongoPayload>().find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE2)
    assert(results.toList().isNotEmpty())

    testDb.getSuspendingCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "testId")
  }

  @Test
  fun faultyEnumList(): Unit = runBlocking {
    // Katerbase will print those 2 warnings on stdout:
    // Array enumList in EnumMongoPayload contains null, but is a non-nullable collection: _id=faultyEnumList
    // Enum value FAULTY of type Enum1 doesn't exists any more but still present in database: EnumMongoPayload, _id=faultyEnumList

    testDb.getSuspendingCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "faultyEnumList")
    testDb.getSuspendingCollection<EnumMongoPayload>().blockingCollection.internalCollection.insertOne(
      Document(
        listOf(
          "_id" to "faultyEnumList",
          "enumList" to listOf(
            "VALUE1", "FAULTY", null, "VALUE3"
          )
        ).toMap()
      )
    )

    val result = testDb.getSuspendingCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal "faultyEnumList")

    assertEquals(2, result!!.enumList.size)
    assertEquals(EnumMongoPayload.Enum1.VALUE1, result.enumList[0])
    assertEquals(EnumMongoPayload.Enum1.VALUE3, result.enumList[1])
  }

  @Test
  fun faultyEnumSet(): Unit = runBlocking {
    // Katerbase will print those 2 warnings on stdout:
    // Array enumSet in EnumMongoPayload contains null, but is a non-nullable collection: _id=faultyEnumSet
    // Enum value FAULTY of type Enum1 doesn't exists any more but still present in database: EnumMongoPayload, _id=faultyEnumSet

    testDb.getSuspendingCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "faultyEnumSet")
    testDb.getSuspendingCollection<EnumMongoPayload>().blockingCollection.internalCollection.insertOne(
      Document(
        setOf(
          "_id" to "faultyEnumSet",
          "enumSet" to setOf(
            "VALUE1", "FAULTY", null, "VALUE3"
          )
        ).toMap()
      )
    )

    val result = testDb.getSuspendingCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal "faultyEnumSet")

    assertEquals(2, result!!.enumSet.size)

    val enumListSorted = result.enumSet.toList().sorted()

    assertEquals(2, enumListSorted.size)
    assertEquals(EnumMongoPayload.Enum1.VALUE1, enumListSorted[0])
    assertEquals(EnumMongoPayload.Enum1.VALUE3, enumListSorted[1])
  }

  @Test
  fun dateDeserialization(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "datetest" }
    testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assert(res.date.toString() == payload.date.toString())
    }
  }

  @Test
  fun customByteArrayDeserialization1(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "bytetest" }
    testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assertEquals(String(payload.byteArray), String(res.byteArray))
      assertEquals(payload.byteArray.size, res.byteArray.size)
    }
  }

  @Test
  fun customByteArrayDeserialization2(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "bytetest"; byteArray = "yo 😍 \u0000 😄".toByteArray() }
    testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assertEquals(String(payload.byteArray), String(res.byteArray))
      assertEquals(payload.byteArray.size, res.byteArray.size)
    }
  }

  class CustomDateArrayCLass {
    val array = listOf(Date(), Date().addYears(-1), Date().addYears(-10))
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun customDateArrayTest(): Unit = runBlocking {
    val payload = CustomDateArrayCLass()
    val bson = JsonHandler.toBsonDocument(payload)
    val newPayload = JsonHandler.fromBson(bson, CustomDateArrayCLass::class)

    (payload.array zip (bson["array"] as List<Date>)).forEach { (old, new) -> assert(old == new) }
    (payload.array zip newPayload.array).forEach { (old, new) -> assert(old == new) }
  }

  @Test
  fun multithreadedFindOneOrInsert(): Unit = runBlocking {
    val id = "multicreateid"
    val value = EnumMongoPayload.Enum1.VALUE2
    var createNewCalls = 0
    testDb.getSuspendingCollection<EnumMongoPayload>().drop()
    (1..50).forEachAsyncCoroutine {
      (1..500).forEach {
        val result = testDb.getSuspendingCollection<EnumMongoPayload>().findOneOrInsert(EnumMongoPayload::_id equal id) {
          createNewCalls++
          EnumMongoPayload(value1 = value)
        }
        assert(result._id == id)
        assert(result.value1 == value)
      }
    }
    println("Called create $createNewCalls times")
  }

  @Test
  fun primitiveList(): Unit = runBlocking {
    val id = "primitiveTest"
    val payload = EnumMongoPayload().apply { stringList = listOf("a", "b"); _id = id }

    testDb.getSuspendingCollection<EnumMongoPayload>().insertOne(payload, upsert = true)
    var retrievedPayload = testDb.getSuspendingCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal id)!!
    assert(payload.stringList == retrievedPayload.stringList)

    testDb.getSuspendingCollection<EnumMongoPayload>().updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::stringList setTo listOf("c", "d")
    }
    retrievedPayload = testDb.getSuspendingCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal id)!!

    assert(listOf("c", "d") == retrievedPayload.stringList)
  }

  @Test
  fun persistencyTest(): Unit = runBlocking {
    val range = (1..10000)
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>()
    val idPrefix = "persistencyTest"

    range.forEach { index ->
      val id = idPrefix + index
      collection.deleteOne(EnumMongoPayload::_id equal id)
    }

    range.forEachAsyncCoroutine { index ->
      val id = idPrefix + index
      collection.deleteOne(EnumMongoPayload::_id equal id)
      collection.insertOne(EnumMongoPayload().apply { _id = id }, upsert = false)
      assert(collection.findOne(EnumMongoPayload::_id equal id) != null)
      collection.deleteOne(EnumMongoPayload::_id equal id)
    }

    range.forEachAsyncCoroutine { index ->
      val id = idPrefix + index
      collection.deleteOne(EnumMongoPayload::_id equal id)
      collection.findOneOrInsert(EnumMongoPayload::_id equal id) { EnumMongoPayload() }
      assert(collection.findOne(EnumMongoPayload::_id equal id) != null)
      collection.deleteOne(EnumMongoPayload::_id equal id)
    }
  }

  @Test
  fun computedPropTest(): Unit = runBlocking {
    val payload = EnumMongoPayload()
    val bson = payload.toBSONDocument()
    assert(bson["computedProp"] == null)
    assertEquals(19, bson.size)
  }
  /*
    @Test
    fun nullListTest(): Unit = runBlocking {
      val raw = """{"_id":"","value1":"VALUE1","enumList":[],"date":"2017-08-23T14:52:30.252+02","stringList":["test1", null, "test2"],"staticProp":true,"computedProp":true}"""
      val payload: EnumMongoPayload = JsonHandler.fromJson(raw)
      assert(payload.stringList.size == 2)
    }*/

  @Test
  fun testInfinity(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityA"; double = Double.POSITIVE_INFINITY }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityB"; double = Double.MAX_VALUE }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityC"; double = Double.MIN_VALUE }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityD"; double = 0.0 }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityE"; double = Double.NEGATIVE_INFINITY }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityF"; double = Double.NaN }, upsert = false)

    assert(collection.find().count() == 6)
    assert(collection.find(EnumMongoPayload::double equal Double.POSITIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.MAX_VALUE).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.MIN_VALUE).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal 0.0).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.NEGATIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.NaN).count() == 1)

    assert(collection.find(EnumMongoPayload::double lowerEquals Double.POSITIVE_INFINITY).count() == 5)
    assert(collection.find(EnumMongoPayload::double lower Double.POSITIVE_INFINITY).count() == 4)
    assert(collection.find(EnumMongoPayload::double lowerEquals Double.MAX_VALUE).count() == 4)
    assert(collection.find(EnumMongoPayload::double lower Double.MAX_VALUE).count() == 3)
    assert(collection.find(EnumMongoPayload::double lower 1000.0).count() == 3)
    assert(collection.find(EnumMongoPayload::double lowerEquals Double.MIN_VALUE).count() == 3)
    assert(collection.find(EnumMongoPayload::double lower Double.MIN_VALUE).count() == 2)
    assert(collection.find(EnumMongoPayload::double lowerEquals 0.0).count() == 2)
    assert(collection.find(EnumMongoPayload::double lower 0.0).count() == 1)
    assert(collection.find(EnumMongoPayload::double lower -1000.0).count() == 1)
    assert(collection.find(EnumMongoPayload::double lowerEquals Double.NEGATIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double lower Double.NEGATIVE_INFINITY).count() == 0)

    assert(collection.find(EnumMongoPayload::double greater Double.POSITIVE_INFINITY).count() == 0)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.POSITIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double greater Double.MAX_VALUE).count() == 1)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.MAX_VALUE).count() == 2)
    assert(collection.find(EnumMongoPayload::double greater 1000.0).count() == 2)
    assert(collection.find(EnumMongoPayload::double greater Double.MIN_VALUE).count() == 2)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.MIN_VALUE).count() == 3)
    assert(collection.find(EnumMongoPayload::double greater 0.0).count() == 3)
    assert(collection.find(EnumMongoPayload::double greaterEquals 0.0).count() == 4)
    assert(collection.find(EnumMongoPayload::double greater -1000.0).count() == 4)
    assert(collection.find(EnumMongoPayload::double greater Double.NEGATIVE_INFINITY).count() == 4)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.NEGATIVE_INFINITY).count() == 5)
  }

  @Test
  fun unsetTest(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "unsetTest"
    collection.insertOne(document = EnumMongoPayload().apply { _id = id }, upsert = false)

    suspend fun put(key: String) = collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::map.child(key) setTo key
    }

    suspend fun remove(key: String) = collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::map.child(key).unset()
    }

    suspend fun get() = collection.findOne(EnumMongoPayload::_id equal id)!!.map

    assert(get().isEmpty())

    (1..10).forEach { put(it.toString()) }
    get().let { map -> (1..10).map { it.toString() }.forEach { assert(map[it] == it) } }

    (1..5).forEach { remove(it.toString()) }
    get().let { map -> (6..10).map { it.toString() }.forEach { assert(map[it] == it) } }

    collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::map.unset()
    }

    assert(get().isEmpty())
  }

  @Test
  fun distinctTest(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "distinctTest"

    (0..100).forEach { index ->
      collection.insertOne(EnumMongoPayload().apply {
        _id = "$id-$index-first"
        this.double = index.toDouble()
      }, upsert = false)
      collection.insertOne(EnumMongoPayload().apply {
        _id = "$id-$index-second"
        this.double = index.toDouble()
      }, upsert = false)
    }

    val distinctValues = collection.distinct(EnumMongoPayload::double).toList()

    assert(distinctValues.distinct().count() == distinctValues.count())
  }

  @Test
  fun equalsTest(): Unit = runBlocking {
    val collection1 = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val collection2 = testDb.getSuspendingCollection<SimpleMongoPayload>().apply { drop() }


    // Find
    val cursor1 = collection1.find()
    val cursor1b = collection1.find()
    val cursor2 = collection2.find()

    // Different collection
    assert(cursor1 != cursor2)
    assert(cursor1.hashCode() != cursor2.hashCode())

    // Same collection, same cursor
    assert(cursor1 == cursor1b)
    assert(cursor1.hashCode() == cursor1b.hashCode())


    // Distinct
    val distinct1 = collection1.distinct(EnumMongoPayload::double)
    //val distinct1b = collection1.distinct(EnumMongoPayload::double)
    val distinct2 = collection2.distinct(SimpleMongoPayload::double)

    // Different collection
    assert(distinct1 != distinct2)
    assert(distinct1.hashCode() != distinct2.hashCode())

    // We don't have equals/hashCode for distinct
    //assert(distinct1 == distinct1b)
    //assert(distinct1.hashCode() == distinct1b.hashCode())
  }

  @Test
  fun longTest(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "longTest" }

    // 0
    (-100L..100L).forEach { long ->
      testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // INT_MIN
    (Int.MIN_VALUE.toLong() - 100L..Int.MIN_VALUE.toLong() + 100L).forEach { long ->
      testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // INT_MAX
    (Int.MAX_VALUE.toLong() - 100L..Int.MAX_VALUE.toLong() + 100L).forEach { long ->
      testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // LONG_MIN
    (Long.MIN_VALUE..Long.MIN_VALUE + 100L).forEach { long ->
      testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // LONG_MAX
    (Long.MAX_VALUE - 100L..Long.MAX_VALUE).forEach { long ->
      testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }
  }

  @Test
  fun dateArrayTest(): Unit = runBlocking {
    val payload = EnumMongoPayload().apply { _id = "dateArrayTest" }

    testDb.getSuspendingCollection<EnumMongoPayload>().let { coll ->
      payload.dateArray = listOf(Date(), Date().addYears(1), Date().addYears(10))
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assertEquals(payload.dateArray[0], res.dateArray[0])
      assertEquals(payload.dateArray[1], res.dateArray[1])
      assertEquals(payload.dateArray[2], res.dateArray[2])
      assertEquals(payload.dateArray.size, res.dateArray.size)
    }
  }

  @Test
  fun hintTest(): Unit = runBlocking {
    testDb.getSuspendingCollection<EnumMongoPayload>()
      .find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE1)
      .hint("value1_1_date_1")
  }

  @Test
  fun invalidHintTest(): Unit = runBlocking {
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking {
        testDb.getSuspendingCollection<EnumMongoPayload>()
          .find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE1)
          .hint("value1_1_date_-1")
      }
    }
  }

  @Test
  fun nullableTest(): Unit = runBlocking {
    val payload = NullableSimpleMongoPayload().apply { _id = "nullableTest" }
    testDb.getSuspendingCollection<NullableSimpleMongoPayload>().insertOne(payload, upsert = true)
    assertNotNull(
      testDb.getSuspendingCollection<NullableSimpleMongoPayload>().findOne(NullableSimpleMongoPayload::_id equal "nullableTest")
    )
    val simpleMongoPayload = testDb.getSuspendingCollection<SimpleMongoPayload>().findOne(SimpleMongoPayload::_id equal "nullableTest")!!

    // Known limitation: SimpleMongoPayload.string is not nullable, but due to the Jackson deserialization we throw a NPE on access.
    try {
      println(simpleMongoPayload.string.length)
      assert(false)
    } catch (e: NullPointerException) {
      assert(true)
    }

    testDb.getSuspendingCollection<NullableSimpleMongoPayload>().deleteOne(NullableSimpleMongoPayload::_id equal "testId")
  }

  @Test
  fun findOneOrInsertTest(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { clear() }

    val payload = EnumMongoPayload().apply { long = 69 }
    var returnVal = collection.findOneOrInsert(EnumMongoPayload::_id equal "findOneOrInsertTest", newEntry = { payload })
    assertEquals(payload.long, returnVal.long)

    collection.updateOne(EnumMongoPayload::_id equal "findOneOrInsertTest") {
      EnumMongoPayload::long incrementBy 1
    }

    returnVal = collection.findOneOrInsert(EnumMongoPayload::_id equal "findOneOrInsertTest", newEntry = { payload })
    assertEquals(payload.long + 1, returnVal.long)
  }

  @Test
  fun suspendingFindTest(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { clear() }

    val payloads = (1..50)
      .map {
        EnumMongoPayload().apply {
          _id = randomId()
          long = it.toLong()
        }
      }
      .onEach { collection.insertOne(it, upsert = false) }

    collection.find().collect { payload ->
      assert(payloads.any { it._id == payload._id })
    }
  }

  @Test
  fun enumMaps(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "enumMaps"

    // Insert payload
    val insertPayload = EnumMongoPayload().apply {
      _id = id
      enumMap1 = mapOf(EnumMongoPayload.Enum1.VALUE2 to 2)
      enumMap2 = mapOf(EnumMongoPayload.Enum1.VALUE3 to EnumMongoPayload.Enum1.VALUE2)
    }
    collection.insertOne(insertPayload, upsert = false)

    collection.findOne(EnumMongoPayload::_id equal id)!!.apply {
      assertEquals(insertPayload.enumMap1, enumMap1)
      assertEquals(insertPayload.enumMap2, enumMap2)
    }

    // Change fields
    collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::enumMap1 setTo mapOf(EnumMongoPayload.Enum1.VALUE1 to 1)
      EnumMongoPayload::enumMap2 setTo mapOf(EnumMongoPayload.Enum1.VALUE2 to EnumMongoPayload.Enum1.VALUE3)
    }

    collection.findOne(EnumMongoPayload::_id equal id)!!.apply {
      assertEquals(mapOf(EnumMongoPayload.Enum1.VALUE1 to 1), enumMap1)
      assertEquals(mapOf(EnumMongoPayload.Enum1.VALUE2 to EnumMongoPayload.Enum1.VALUE3), enumMap2)
    }
  }

  @Test
  fun queryStats(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }

    (1..100)
      .map { EnumMongoPayload().apply { _id = randomId() } }
      .forEach { collection.insertOne(it, upsert = false) }

    val stats = collection.getQueryStats()

    assertEquals(true, stats.executionSuccess)
    assertEquals(100, stats.returnedDocuments)
  }

  @Test
  fun dbStats(): Unit = runBlocking {
    val stats = testDb.getDatabaseStats()
    assert(stats.collections > 0)
  }

  @Test
  fun indexOperatorCheck(): Unit = runBlocking {
    with(MongoDatabaseDefinition.Collection(EnumMongoPayload::class, "enumColl", collectionSizeCap = null)) {
      index(
        EnumMongoPayload::double.ascending(), partialIndex = arrayOf(
          EnumMongoPayload::double greater 0.0
        )
      )
      index(
        EnumMongoPayload::double.ascending(), partialIndex = arrayOf(
          EnumMongoPayload::double equal 0.0
        )
      )
      index(
        EnumMongoPayload::double.ascending(), partialIndex = arrayOf(
          EnumMongoPayload::double exists true
        )
      )

      assertThrows(IllegalArgumentException::class.java) {
        index(
          EnumMongoPayload::double.ascending(), partialIndex = arrayOf(
            EnumMongoPayload::double notEqual 0.0
          )
        )
      }
      assertThrows(IllegalArgumentException::class.java) {
        index(
          EnumMongoPayload::double.ascending(), partialIndex = arrayOf(
            EnumMongoPayload::double exists false
          )
        )
      }
    }
  }

  @Test
  fun limitAndSkip(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }

    (1..100)
      .map { EnumMongoPayload().apply { _id = randomId() } }
      .forEach { collection.insertOne(it, upsert = false) }

    assertEquals(100, collection.find().toList().count())
    assertEquals(1, collection.find().limit(1).toList().count())
    assertEquals(10, collection.find().limit(10).toList().count())
    assertEquals(100, collection.find().limit(100).toList().count())
    assertEquals(100, collection.find().limit(1000).toList().count())

    assertEquals(1, collection.find().limit(1).skip(0).toList().count())
    assertEquals(1, collection.find().limit(1).skip(1).toList().count())
    assertEquals(1, collection.find().limit(1).skip(99).toList().count())
    assertEquals(0, collection.find().limit(1).skip(100).toList().count())
    assertEquals(10, collection.find().limit(10).skip(85).toList().count())
    assertEquals(5, collection.find().limit(10).skip(95).toList().count())
    assertEquals(77, collection.find().limit(100).skip(23).toList().count())
    assertEquals(2, collection.find().limit(1000).skip(98).toList().count())
  }

  @Test
  fun sort(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }

    val insertedPayloads = (1..100)
      .map { EnumMongoPayload().apply { _id = randomId(); long = it.toLong() % 23 } }
      .onEach { collection.insertOne(it, upsert = false) }

    val query = collection.find().sortByDescending(EnumMongoPayload::long)
    val iter = query.mongoIterable.iterator()
    assertEquals(22, iter.next().getLong("long"))

    assertEquals(insertedPayloads.minOf { it.long }, collection.find().sortBy(EnumMongoPayload::long).toList().first().long)
    assertEquals(insertedPayloads.maxOf { it.long }, collection.find().sortBy(EnumMongoPayload::long).toList().last().long)

    assertEquals(insertedPayloads.maxOf { it.long }, collection.find().sortByDescending(EnumMongoPayload::long).toList().first().long)
    assertEquals(insertedPayloads.minOf { it.long }, collection.find().sortByDescending(EnumMongoPayload::long).toList().last().long)
  }

  @Test
  fun fieldSelection(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val longInsertedValue = 12345678L
    val stringListInsertedValue = listOf("a", "b", "c")

    (1..100)
      .map { EnumMongoPayload().apply { _id = randomId(); long = longInsertedValue; stringList = stringListInsertedValue } }
      .onEach { collection.insertOne(it, upsert = false) }

    @Suppress("DEPRECATION")
    assertEquals(EnumMongoPayload().long, collection.find().excludeFields(EnumMongoPayload::long).toList().first().long)
    @Suppress("DEPRECATION")
    assertEquals(stringListInsertedValue, collection.find().excludeFields(EnumMongoPayload::long).toList().first().stringList)

    assertEquals(longInsertedValue, collection.find().selectedFields(EnumMongoPayload::long).toList().first().long)
    assertEquals(EnumMongoPayload().stringList, collection.find().selectedFields(EnumMongoPayload::long).toList().first().stringList)
  }

  @Test
  fun testUpdateOneOrInsert(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "testUpdateOneOrInsert"
    assertEquals(0, collection.find().count())

    repeat(10) { count ->
      collection.updateOneOrInsert(EnumMongoPayload::_id equal id) {
        EnumMongoPayload::stringList push count.toString()
      }
      assertEquals(1, collection.find().count())
      val document = collection.find().first()
      assertEquals(id, document._id)
      assertEquals((0..count).map { it.toString() }, document.stringList)
    }
  }

  @Test
  fun testPushMultiple() = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "testPushMultiple"
    assertEquals(0, collection.find().count())

    collection.updateOneOrInsert(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::stringList.push("a", "b", "c")
      EnumMongoPayload::enumSet.push(*EnumMongoPayload.Enum1.entries.toTypedArray())
    }

    assertEquals(listOf("a", "b", "c"), collection.find().first().stringList)
    assertEquals(EnumMongoPayload.Enum1.entries.toSet(), collection.find().first().enumSet.toSet())
  }

  @Test
  fun testPushAndSlice() = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "testPushAndSlice"
    assertEquals(0, collection.find().count())

    repeat(10) { count ->
      collection.updateOneOrInsert(EnumMongoPayload::_id equal id) {
        EnumMongoPayload::stringList.push(count.toString(), slice = -5)
        // Can't slice a set, so test only stringList
      }
    }
    assertEquals(listOf("5", "6", "7", "8", "9"), collection.find().first().stringList)
  }

  @Test
  fun testEmptyPushAndSlice() = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "testEmptyPushAndSlice"
    assertEquals(0, collection.find().count())

    collection.updateOneOrInsert(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::stringList.push("a", "b", "c", "d", "e", "f", "g")
    }
    assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), collection.find().first().stringList)

    // Cap to 5 elements (remove the last elements)
    collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::stringList.push(slice = 5)
    }
    assertEquals(listOf("a", "b", "c", "d", "e"), collection.find().first().stringList)

    // Cap to 3 elements (remove the first elements)
    collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::stringList.push(slice = -3)
    }
    assertEquals(listOf("c", "d", "e"), collection.find().first().stringList)
  }

  @Test
  fun nameEscapeTest(): Unit = runBlocking {
    val collection = testDb.getSuspendingCollection<EnumMongoPayload>().apply { drop() }
    val id = "nameEscapeTest"

    collection.insertOne(EnumMongoPayload().apply { _id = id }, upsert = false)

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking {
        collection.updateOne(EnumMongoPayload::_id equal id) {
          EnumMongoPayload::map.child("test.test") setTo ""
        }
      }
    }

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking {
        collection.updateOne(EnumMongoPayload::_id equal id) {
          EnumMongoPayload::map.child("\$test") setTo ""
        }
      }
    }
  }

  @Test
  fun openClassTest(): Unit = runBlocking {
    val id = "openClassTest"
    val sealedClass1Test = OpenClassMongoPayload.SealedClass.Class1(string = "string1")
    val sealedClass2test = OpenClassMongoPayload.SealedClass.Class2(int = 20)
    val payload = OpenClassMongoPayload().apply {
      _id = id
      sealedClass1 = sealedClass1Test
      sealedClass2 = sealedClass2test
    }
    testDb.getSuspendingCollection<OpenClassMongoPayload>().insertOne(payload, upsert = true)

    val simpleMongoPayload = testDb.getSuspendingCollection<OpenClassMongoPayload>().findOne(OpenClassMongoPayload::_id equal id)
    assertEquals(sealedClass1Test.string, (simpleMongoPayload!!.sealedClass1 as OpenClassMongoPayload.SealedClass.Class1).string)

    val sealedClass2testUpdated = OpenClassMongoPayload.SealedClass.Class2(int = 100)
    testDb.getSuspendingCollection<OpenClassMongoPayload>().updateOne(OpenClassMongoPayload::_id equal id) {
      OpenClassMongoPayload::sealedClass2 setTo sealedClass2testUpdated
    }
    val updatedMongoPayload = testDb.getSuspendingCollection<OpenClassMongoPayload>().findOne(OpenClassMongoPayload::_id equal id)
    assertEquals(sealedClass2testUpdated.int, (updatedMongoPayload!!.sealedClass2 as OpenClassMongoPayload.SealedClass.Class2).int)

    testDb.getSuspendingCollection<OpenClassMongoPayload>().deleteOne(OpenClassMongoPayload::_id equal id)
  }

  @Test
  fun findEquality(): Unit = runBlocking {
    with(testDb.getSuspendingCollection<EnumMongoPayload>()) {

      assertEquals(find(), find())
      assertEquals(find().hashCode(), find().hashCode())

      assertNotEquals(find().limit(1), find())
      assertNotEquals(find().limit(1).hashCode(), find().hashCode())

      assertNotEquals(find().skip(1), find())
      assertNotEquals(find().skip(1).hashCode(), find().hashCode())

      assertNotEquals(find().batchSize(1), find())
      assertNotEquals(find().batchSize(1).hashCode(), find().hashCode())

      assertNotEquals(find().selectedFields(EnumMongoPayload::double), find())
      assertNotEquals(find().selectedFields(EnumMongoPayload::double).hashCode(), find().hashCode())

      assertNotEquals(find().sortBy(EnumMongoPayload::double), find())
      assertNotEquals(find().sortBy(EnumMongoPayload::double).hashCode(), find().hashCode())

      assertNotEquals(find().sortBy(EnumMongoPayload::double), find().sortByDescending(EnumMongoPayload::double))
      assertNotEquals(find().sortBy(EnumMongoPayload::double).hashCode(), find().sortByDescending(EnumMongoPayload::double).hashCode())

      assertNotEquals(find().hint("value1_1_date_1"), find())
      assertNotEquals(find().hint("value1_1_date_1").hashCode(), find().hashCode())

      assertNotEquals(find(EnumMongoPayload::_id equal ""), find())
      assertNotEquals(find(EnumMongoPayload::_id equal "").hashCode(), find().hashCode())
    }
  }

  @Test
  fun childHandling() = runBlocking {
    val payload = EnumMongoPayload().apply {
      _id = "childHandling"
      child = EnumMongoPayload.Child().apply { string = "child" }
      nullableChild = EnumMongoPayload.Child().apply { string = "nullableChild" }
      listOfChilds = listOf(EnumMongoPayload.Child().apply { string = "listOfChilds" })
      listOfNullableChilds = listOf(EnumMongoPayload.Child().apply { string = "listOfNullableChilds" })
      stringList = listOf("a", "b", "c")
    }
    with(testDb.getSuspendingCollection<EnumMongoPayload>()) {
      insertOne(EnumMongoPayload().apply { _id = "childHandling-anotherId1" }, upsert = true)
      insertOne(payload, upsert = true)
      insertOne(EnumMongoPayload().apply { _id = "childHandling-anotherId2" }, upsert = true)

      assertEquals(payload, findOne(EnumMongoPayload::stringList equal listOf("a", "b", "c")))
      assertEquals(null, findOne(EnumMongoPayload::stringList equal listOf("b", "b", "a")))

      assertEquals(payload, findOne(EnumMongoPayload::nullableChild notEqual null))
      assertEquals(null, findOne(EnumMongoPayload::_id equal payload._id, EnumMongoPayload::nullableChild equal null))

      assertEquals(payload, findOne(EnumMongoPayload::child.child(EnumMongoPayload.Child::string) equal "child"))
      assertEquals(payload, findOne(EnumMongoPayload::nullableChild.child(EnumMongoPayload.Child::string) equal "nullableChild"))
      assertEquals(payload, findOne(EnumMongoPayload::listOfChilds.child(EnumMongoPayload.Child::string) equal "listOfChilds"))
      assertEquals(
        payload,
        findOne(EnumMongoPayload::listOfNullableChilds.child(EnumMongoPayload.Child::string) equal "listOfNullableChilds")
      )

      // In an ideal world, the following statements would not compile, because it uses the wrong type (Int instead of String).
      // However, currently we do not support type checking here.
      assertEquals(null, findOne(EnumMongoPayload::stringList equal 123))
      assertEquals(null, findOne(EnumMongoPayload::_id equal 123))
    }
  }


  companion object {
    lateinit var testDb: MongoDatabase

    @Suppress("unused")
    @BeforeAll
    @JvmStatic
    fun setup(): Unit = runBlocking {
      testDb = MongoDatabase("mongodb://localhost:27017/local") {
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
        collection<OpenClassMongoPayload>("simpleMongoColl")
      }
    }
  }
}