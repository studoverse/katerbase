import TestPayload.Companion.testId
import com.moshbit.katerbase.MongoDatabase
import com.moshbit.katerbase.MongoMainEntry
import com.moshbit.katerbase.equal
import kotlin.concurrent.thread

class TestPayload : MongoMainEntry() {
  var testValue = 1
  var testString = ""

  companion object {
    val testId = "testInstance"
  }
}

val localRs = object : MongoDatabase(
  uri = "mongodb://server1:27027,server2:27037/test?replicaSet=local-rs&readPreference=primary&serverSelectionTimeoutMS=5000&connectTimeoutMS=10000",
  allowReadFromSecondaries = false,
  supportChangeStreams = true,
  collections = {
    collection<TestPayload>("testCol")
  }) {}

val collection = localRs.getCollection<TestPayload>()

fun main() {

  var lastChange = 0

  collection.watch { change ->
    if (change.isFailure) {
      println("Change exception: ${change.exceptionOrNull()?.message}")
      return@watch
    }
    val testValueInChange =
      change.getOrNull()?.internalChangeStreamDocument?.updateDescription?.updatedFields?.get(TestPayload::testValue.name)?.asInt32()?.value
        ?: 1

    if (testValueInChange != lastChange + 1) {
      println("Missed change -> testValueInChange: ${testValueInChange}, lastChange: ${lastChange}")
    } else if (lastChange % 10 == 0) {
      println("lastChange: $lastChange")
    }
    lastChange = testValueInChange
  }

  collection.insertOne(TestPayload().apply { _id = testId }, upsert = true)

  thread {
    while (true) {
      try {
        collection.updateOne(TestPayload::_id equal testId) {
          TestPayload::testValue incrementBy 1
          TestPayload::testString setTo (0..100_000).toList().shuffled().joinToString()
        }
      } catch (e: Exception) {
        println("Update failed!!!! (${e.message})")
      }
    }
  }
}