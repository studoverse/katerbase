package util

import java.util.*


fun Date.addYears(year: Int): Date {
  val cal = Calendar.getInstance()
  cal.time = this
  cal.add(Calendar.YEAR, year)
  return cal.time
}