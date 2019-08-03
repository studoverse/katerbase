package com.moshbit.katerbase

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.impl.StaticLoggerBinder

internal fun setLogLevel(name: String, level: Level) {
  (StaticLoggerBinder.getSingleton().loggerFactory.getLogger(name) as Logger).level = level
}