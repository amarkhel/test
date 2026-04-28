package weather.util

import scala.concurrent.duration._
import java.time.{Instant, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.util.Try

object HttpHeaderParsers {
  def parseRetryAfter(value: String): Option[FiniteDuration] = {
    val trimmed = value.trim
    if (trimmed.forall(_.isDigit)) {
      Try(trimmed.toLong).toOption.filter(_ >= 0).map(_.seconds)
    } else {
      Try(ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)).toOption
        .map(_.toInstant)
        .map { target =>
          val millis = ChronoUnit.MILLIS.between(Instant.now(), target)
          math.max(0L, millis).millis
        }
    }
  }
}

