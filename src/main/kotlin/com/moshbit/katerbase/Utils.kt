package com.moshbit.katerbase

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import org.apache.commons.codec.binary.Hex
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

internal fun setLogLevel(name: String, level: Level) {
  (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger(name)?.apply {
    this.level = level
  } ?: throw IllegalStateException("Failed to set log level: Invalid logger context or logger.")
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