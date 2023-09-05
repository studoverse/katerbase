package com.moshbit.katerbase

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.WriterBasedJsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.POJONode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.bson.Document
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.jvm.kotlinProperty

object JsonHandler {
  private val mongoJacksonMapper: ObjectMapper = JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // Don't fail when JSON has new/additional values
    .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER) // Jackson doesn't ignore Transient members, so make it ignore them
    .enable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL) // This is used to handle db migration of enum values softly
    .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS) // Used for Double.INFINITY
    .addModule(KotlinModule.Builder().build())
    .addModule(
      SimpleModule(
        /* name = */ "MongoWrapperModule",
        /* version = */ Version.unknownVersion(),
        /* deserializers = */ mapOf(
          Date::class.java to MongoDateDeserializer(),
          ByteArray::class.java to MongoByteArrayDeserializer()
        ),
        /* serializers = */ listOf(MongoDateSerializer())
      )
    )
    .visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE) // Ignore all computed properties and functions
    .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
    .build()

  fun <T : Any> ObjectMapper.constructCollectionType(kClass: KClass<T>) =
    typeFactory.constructCollectionType(List::class.java, kClass.java)

  private val classMap = ConcurrentHashMap<String, ClassDescriptor<out Any>>(50, 9.0f, 1)
  private val valueMethodCache = ConcurrentHashMap<KClass<out Any>, Method>(50, 9.0f, 1)

  private class ClassDescriptor<T : Any>(kClass: KClass<T>) {
    val enumIterableTypes: List<Pair<KProperty<*>, KClass<*>>>
    val nonNullableListTypes: List<KProperty<*>>

    init {
      val kotlinProperties = kClass.java.declaredFields.toList().mapNotNull { it.kotlinProperty }

      nonNullableListTypes = kotlinProperties.filter { it.returnType.isSubtypeOf(nonNullableListType) }

      enumIterableTypes = kotlinProperties
        .filter { it.returnType.isSubtypeOf(enumIterableType) }
        .map { it to it.returnType.arguments.first().type!!.classifier as KClass<*> }
    }

    companion object {
      private val enumIterableType = Iterable::class.createType(
        listOf(
          KTypeProjection(
            variance = KVariance.INVARIANT, type = Enum::class.createType(
              listOf(
                KTypeProjection(variance = KVariance.OUT, type = Any::class.createType())
              )
            )
          )
        )
      )

      private val nonNullableListType = Collection::class.createType(
        listOf(
          KTypeProjection(variance = KVariance.OUT, type = Any::class.createType(nullable = false))
        )
      )
    }
  }

  fun <T : Any> toJsonString(payload: T): String = mongoJacksonMapper.writeValueAsString(payload)

  fun transformBsonDocument(doc: MutableMap<String, Any?>, transform: (String, Any) -> Any?) {
    @Suppress("UNCHECKED_CAST")
    doc.entries.forEach { entry ->
      val key = entry.key
      val value = entry.value

      fun handleIterable(iter: ArrayList<Any?>): Unit = iter.forEachIndexed innerForEach@{ index, innerValue ->
        when (innerValue) {
          null -> return@innerForEach
          is Map<*, *> -> transformBsonDocument(innerValue as LinkedHashMap<String, Any?>, transform)
          is List<*> -> handleIterable(innerValue as ArrayList<Any?>)
          else -> transform(key, innerValue)?.let { iter.set(index, it) }
        }
      }

      when (value) {
        null -> return@forEach
        is Map<*, *> -> transformBsonDocument(value as LinkedHashMap<String, Any?>, transform)
        is List<*> -> handleIterable(value as ArrayList<Any?>)
        else -> transform(key, value)?.let { newValue -> entry.setValue(newValue) }
      }
    }
  }

  fun <T : Any> toBsonDocument(payload: T): Document = mongoJacksonMapper.convertValue(payload, Document::class.java)

  @Deprecated("Use new", ReplaceWith("convert(payload, kClass)"))
  fun <T : Any, R : Any> convertValue(payload: T, kClass: KClass<R>): R = convert(payload, kClass)

  @Deprecated("Use new", ReplaceWith("convert(payload, R::class)"))
  inline fun <reified R : Any> convertValue(payload: Any): R = convert(payload, R::class)

  fun <T : Any, R : Any> convert(payload: T, kClass: KClass<R>): R = mongoJacksonMapper.convertValue(payload, kClass.java)
  inline fun <reified R : Any> convert(payload: Any): R = convert(payload, R::class)

  @Deprecated("Use new", ReplaceWith("convertList(payload, kClass)"))
  fun <T : Any, R : Any> convertValueList(payload: T, kClass: KClass<R>): List<R> = convertList(payload, kClass)

  fun <T : Any, R : Any> convertList(payload: T, kClass: KClass<R>): List<R> =
    mongoJacksonMapper.convertValue(payload, mongoJacksonMapper.constructCollectionType(kClass))

  fun <T : Any> fromBson(document: Document, clazz: KClass<T>): T {
    return mongoJacksonMapper.fromTree(mongoJacksonMapper.valueToTree(document), clazz)
  }

  fun <T : Any> ObjectMapper.fromTree(tree: JsonNode, clazz: KClass<T>): T {
    when {
      clazz.java.isAnonymousClass ->
        throw IllegalArgumentException("Jackson doesn't support serialization of anonymous classes: ${clazz.simpleName}")
      clazz.java.isLocalClass -> {
        // See https://github.com/FasterXML/jackson-module-kotlin/issues/135
        throw IllegalArgumentException("Jackson doesn't support serialization of local classes. Please move the class out its function: ${clazz.simpleName}")
      }
    }

    // Cache every computation which is applied to a class, because it should happen pretty often
    val classDescriptor = classMap.getOrPut(clazz.qualifiedName) { ClassDescriptor(clazz) }
    fun getId() = tree["_id"].textValue()

    if (tree is ObjectNode) {
      /*
        If there is a null value in a non-nullable List, Jackson doesn't throw an exception.
        So always check every non-nullable list for null values
      */
      classDescriptor.nonNullableListTypes.forEach { property ->
        val list = tree[property.name]?.filter { !it.isNull } ?: return@forEach
        if (tree[property.name].size() != list.size) {
          println("Array ${property.name} in ${clazz.simpleName} contains null, but is a non-nullable collection: _id=${getId()}")
          tree.set<ArrayNode>(property.name, ArrayNode(null, list))
        }
      }

      /*
        If there is an enum value in the database which doesn't exits in the code anymore
        (faulty db migration, only happens with lists of enums), Jackson creates a faulty list where size > count(),
        because READ_UNKNOWN_ENUM_VALUES_AS_NULL is enabled.
        So always set these vars manually, and get every enum value with "valueOf" method
      */
      classDescriptor.enumIterableTypes.forEach { (property, enumClass) ->
        val valueOfMethod =
          valueMethodCache.getOrPut(enumClass) { enumClass.java.declaredMethods.find { it.name == "valueOf" }!! } // Cache this because declaredMethods is expensive
        val stringValues = tree[property.name]?.map { node ->
          node.textValue() // Throw if we want to serialize e.g. an array to an enum.
            ?: throw NullPointerException("${property.name} has no textValue() because it is type ${node.nodeType}. Document: $tree")
        } ?: return@forEach // Not unchecked because MongoDB enums can only have string values
        val validValues = stringValues.mapNotNull {
          // Try to deserialize enum values from strings, if none found drop it
          try {
            valueOfMethod.invoke(null, it)
            return@mapNotNull it
          } catch (ignore: InvocationTargetException) { // Catch InvocationTargetException because exception happens in reflected call
            println("Enum value $it of type ${enumClass.simpleName} doesn't exists any more but still present in database: ${clazz.simpleName}, _id=${getId()}")
          }
          return@mapNotNull null
        }
        tree.set<ArrayNode>(property.name, ArrayNode(null, validValues.map { TextNode(it) }))
      }
    }

    return treeToValue(tree, clazz.java)
  }

  private class MongoDateSerializer : JsonSerializer<Date>() {
    override fun serialize(value: Date, gen: JsonGenerator, serializers: SerializerProvider) {
      if (gen is WriterBasedJsonGenerator) {
        gen.writeString(value.toInstant().toString()) // E.g. used when calling JsonHandler.toString(bson)
      } else {
        gen.writeEmbeddedObject(value) // Used when passing the katerbase object to mongodb
      }
    }
    override fun handledType(): Class<Date> = Date::class.java
  }

  private class MongoDateDeserializer : JsonDeserializer<Date>() {
    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): Date? = p!!.readValueAs(POJONode::class.java).pojo as? Date
    override fun isCachable(): Boolean = true
  }

  private class MongoByteArrayDeserializer : JsonDeserializer<ByteArray>() {
    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): ByteArray? =
      p!!.readValueAsTree<ObjectNode>()["data"]?.binaryValue()

    override fun isCachable(): Boolean = true
  }
}