import com.moshbit.katerbase.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.*
import kotlin.reflect.KClass

@TestMethodOrder(MethodOrderer.Alphanumeric::class)
class MoviesExample {

  class Movie : MongoMainEntry() {
    class Actor : MongoSubEntry() {
      var name = ""
      var birthday: Date? = null
    }

    var name = ""
    var actors: List<Actor> = emptyList()
  }

  class User : MongoMainEntry() {
    class MovieRating : MongoSubEntry() {
      var date = Date()
      var stars = 0 // 0 to 10
    }

    var email = ""
    var signUp = Date()
    var lastSignIn = Date()
    var ratings = emptyMap<String, MovieRating>() // movieId to Rating
  }

  class SignIn : MongoMainEntry() {
    var userId = ""
    var date = Date()
  }

  @Test
  fun `01 sign up`() {
    assertNull(database.getCollection<User>().findOne(User::email equal "john.doe@example.com"))
    assertEquals(0, database.getCollection<User>().find(User::email equal "john.doe@example.com").count())

    database.getCollection<User>().insertOne(User().apply {
      _id = Random().nextLong().toString()
      email = "john.doe@example.com"
    }, upsert = false)

    assertNotNull(database.getCollection<User>().findOne(User::email equal "john.doe@example.com"))
    assertEquals(1, database.getCollection<User>().find(User::email equal "john.doe@example.com").count())
  }

  @Test
  fun `02 sign in`() {
    val signInDate = Date()

    // Set user.lastSignIn

    assertEquals(0, database.getCollection<User>().find(User::lastSignIn greaterEquals signInDate).count())
    assertNotEquals(signInDate, database.getCollection<User>().findOne(User::email equal "john.doe@example.com")!!.lastSignIn)

    val user = database.getCollection<User>().updateOneAndFind(User::email equal "john.doe@example.com") {
      User::lastSignIn setTo signInDate
    }!!

    assertEquals(signInDate, user.lastSignIn)
    assertEquals(1, database.getCollection<User>().find(User::lastSignIn greaterEquals signInDate).count())


    // Add SignIn to SignIn collection for logging purposes

    assertNull(database.getCollection<SignIn>().findOne(SignIn::userId equal user._id, SignIn::date equal signInDate))

    database.getCollection<SignIn>().insertOne(SignIn().apply {
      _id = Random().nextLong().toString()
      userId = user._id
      date = signInDate
    }, upsert = false)

    assertNotNull(database.getCollection<SignIn>().findOne(SignIn::userId equal user._id, SignIn::date equal signInDate))
  }

  @Test
  fun `03 sign in - update result`() {
    val signInDate = Date()

    assertEquals(0, database.getCollection<User>().find(User::lastSignIn greaterEquals signInDate).count())

    var updateResult = database.getCollection<User>().updateOne(User::email equal "john.doe@example.com") {
      User::lastSignIn setTo signInDate
    }
    assertEquals(1, updateResult.matchedCount)
    assertEquals(1, updateResult.modifiedCount)

    updateResult = database.getCollection<User>().updateOne(User::email equal "john.doe@example.com") {
      User::lastSignIn setTo signInDate
    }
    assertEquals(1, updateResult.matchedCount)
    assertEquals(0, updateResult.modifiedCount)
  }

  companion object {
    lateinit var database: MongoDatabase

    @Suppress("unused")
    @BeforeAll
    @JvmStatic
    fun setup() {
      database = object : MongoDatabase("mongodb://localhost:27017/moviesDatabase") {
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

      // For testing purposes, clean all data
      database.getCollection<User>().deleteMany()
      database.getCollection<Movie>().deleteMany()
    }
  }
}