import com.moshbit.katerbase.MongoMainEntry
import java.util.*

class EnumMongoPayload(val value1: Enum1 = Enum1.VALUE1) : MongoMainEntry() {
  enum class Enum1 {
    VALUE1, VALUE2, VALUE3
  }

  var enumList: List<Enum1> = emptyList()
  var enumSet: Set<Enum1> = emptySet()
  var enumMap1: Map<Enum1, Int> = emptyMap()
  var enumMap2: Map<Enum1, Enum1> = emptyMap()
  var date = Date()
  var long = 0L
  var stringList: List<String> = emptyList()
  val computedProp get() = value1 == Enum1.VALUE1
  val staticProp = value1 == Enum1.VALUE1
  var double = 0.0
  var map: Map<String, String> = mapOf()
  var byteArray: ByteArray = "yolo".toByteArray()
  var dateArray = emptyList<Date>()
  var nullableString: String? = null
}

class SimpleMongoPayload : MongoMainEntry() {
  var double = 3.0
  var string = ""
  var stringList: List<String> = emptyList()
}

class NullableSimpleMongoPayload : MongoMainEntry() {
  var double: Double? = null
  var string: String? = null
  var stringList: List<String?>? = null
}