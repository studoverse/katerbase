package com.moshbit.katerbase

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

internal fun setLogLevel(name: String, level: Level) {
  (LoggerFactory.getLogger(name) as Logger).level = level
}

internal fun ByteArray.sha256(): String {
  val md = MessageDigest.getInstance("SHA-256")
  val hex = md.digest(this)
  return Hex.encodeHexString(hex)
}

internal fun String.sha256(): String {
  return this.toByteArray().sha256()
}

internal fun Date.toIsoString(): String {
  return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX")
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(this)
}